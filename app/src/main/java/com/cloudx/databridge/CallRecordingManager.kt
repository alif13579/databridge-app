package com.cloudx.databridge

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Manual call recording to app-private storage (AAC in .m4a).
 *
 * Honest limitation, stated once here so every caller inherits it: Android 10+
 * blocks true two-sided call capture for non-system apps (VOICE_CALL needs
 * CAPTURE_AUDIO_OUTPUT, system-only). We record from the mic side; the other
 * side is only audible when the call is on SPEAKERPHONE.
 *
 * Mid-call wrinkle: while a voice call is active the telephony stack often
 * holds the mic exclusively, so plain MIC can fail (or capture silence) on
 * many devices. [start] therefore retries with alternate mic-side sources
 * (VOICE_COMMUNICATION → VOICE_RECOGNITION → CAMCORDER) — on some HALs one of
 * these succeeds where MIC does not. Nothing can force it when the hardware
 * says no; callers must surface that honestly (see JourneyRecordingUi).
 *
 * Files stay in app-private storage until the agent explicitly saves (upload
 * to R2 + call_recordings row); discards never leave the device.
 *
 * One recording at a time (guarded by [isRecording]). Max ~10 min / 10 MB —
 * matches the R2 audio cap (see r2-attachment-upload MAX_AUDIO_BYTES), so
 * anything recorded here is always uploadable.
 */
object CallRecordingManager {

    const val MIME_TYPE = "audio/mp4"
    const val FILE_EXT = "m4a"
    private const val MAX_DURATION_MS = 10 * 60 * 1000
    private const val MAX_FILE_BYTES = 10L * 1024 * 1024

    data class Finished(val file: File, val durationSec: Int)

    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var outFile: File? = null
    @Volatile private var startMs: Long = 0L
    @Volatile private var pausedAccumMs: Long = 0L
    @Volatile private var pauseBeganMs: Long = 0L
    /** Which mic-side source the running recording actually uses ("" = none). */
    @Volatile var activeSourceName: String = ""
        private set

    val isRecording: Boolean get() = recorder != null
    val isPaused: Boolean get() = recorder != null && pauseBeganMs > 0L

    /** True while the device is in a voice call (telephony or VoIP). No permission needed. */
    fun isCallActive(context: Context): Boolean {
        return try {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE)
                as? android.media.AudioManager
            val mode = am?.mode ?: android.media.AudioManager.MODE_NORMAL
            mode == android.media.AudioManager.MODE_IN_CALL ||
                mode == android.media.AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) { false }
    }

    /** Current mic amplitude (0 when idle/blocked). Safe to poll after [start]. */
    fun maxAmplitude(): Int {
        return try { recorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
    }

    fun dir(context: Context): File =
        File(context.filesDir, "call_recordings").apply { mkdirs() }

    /** Latest finished-but-unsent file for [consignmentId], if any. */
    fun pendingFile(context: Context, consignmentId: String): File? =
        dir(context).listFiles { f -> f.name.startsWith(safe(consignmentId) + "_") }
            ?.maxByOrNull { it.lastModified() }
            ?.takeIf { it.length() > 0 }

    /**
     * Starts recording for [consignmentId]. Returns false when already
     * recording (caller should stop first) or when setup fails on every
     * mic-side source (typical cause: an active call holding the mic).
     */
    @Synchronized
    fun start(context: Context, consignmentId: String): Boolean {
        if (recorder != null) return false
        val inCall = isCallActive(context)
        val file = File(dir(context), "${safe(consignmentId)}_${System.currentTimeMillis()}.$FILE_EXT")
        // MIC first (best quality); fallbacks for when a live call holds it.
        // VOICE_CALL is deliberately absent — system-only, always throws here.
        val sources = listOf(
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
        )
        var lastError = ""
        for ((source, name) in sources) {
            var rec: MediaRecorder? = null
            try {
                @Suppress("DEPRECATION")
                rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
                else MediaRecorder()
                rec.apply {
                    setAudioSource(source)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(44_100)
                    setMaxDuration(MAX_DURATION_MS)
                    setMaxFileSize(MAX_FILE_BYTES)
                    setOutputFile(file.absolutePath)
                    try { setOnInfoListener { _, what, _ ->
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                            what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                            try { stop() } catch (_: Exception) {}
                        }
                    } } catch (_: Exception) {}
                    prepare()
                    start()
                }
                recorder = rec
                outFile = file
                startMs = System.currentTimeMillis()
                pausedAccumMs = 0L
                pauseBeganMs = 0L
                activeSourceName = name
                CallRecordingStore.onStarted(consignmentId, file.absolutePath, startMs)
                FirebaseErrorLogger.log("CallRecording", "start_ok",
                    "source=$name inCall=$inCall", mapOf("source" to name))
                return true
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                try { rec?.release() } catch (_: Exception) {}
            }
        }
        FirebaseErrorLogger.log("CallRecording", "start_failed",
            "all sources failed inCall=$inCall last=$lastError")
        recorder = null
        activeSourceName = ""
        try { if (file.exists()) file.delete() } catch (_: Exception) {}
        outFile = null
        return false
    }

    /** Pauses the running recording (API 24+). False when nothing to pause. */
    @Synchronized
    fun pause(): Boolean {
        val rec = recorder ?: return false
        if (pauseBeganMs > 0L) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            rec.pause()
            pauseBeganMs = System.currentTimeMillis()
            CallRecordingStore.onPaused(true)
            true
        } catch (e: Exception) {
            FirebaseErrorLogger.log("CallRecording", "pause_failed", e.message ?: "pause threw")
            false
        }
    }

    /** Resumes a paused recording. False when not paused. */
    @Synchronized
    fun resume(): Boolean {
        val rec = recorder ?: return false
        if (pauseBeganMs <= 0L) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            rec.resume()
            pausedAccumMs += System.currentTimeMillis() - pauseBeganMs
            pauseBeganMs = 0L
            CallRecordingStore.onPaused(false)
            true
        } catch (e: Exception) {
            FirebaseErrorLogger.log("CallRecording", "resume_failed", e.message ?: "resume threw")
            false
        }
    }
    /** Stops and returns the finished file, or null when nothing was recording. */
    @Synchronized
    fun stop(): Finished? {
        val rec = recorder ?: return null
        recorder = null
        activeSourceName = ""
        if (pauseBeganMs > 0L) {
            pausedAccumMs += System.currentTimeMillis() - pauseBeganMs
            pauseBeganMs = 0L
        }
        val file = outFile
        outFile = null
        val secs = (elapsedLockedMs() / 1000).toInt().coerceAtLeast(0)
        startMs = 0L
        try { rec.stop() } catch (_: Exception) { /* too-short recording throws — file still usable or empty */ }
        try { rec.release() } catch (_: Exception) {}
        CallRecordingStore.onStopped()
        if (file == null || !file.exists() || file.length() == 0L) {
            try { file?.delete() } catch (_: Exception) {}
            return null
        }
        return Finished(file, secs)
    }

    /** Discards an unsent file (agent chose not to keep it). */
    fun discard(file: File) {
        try { if (file.exists()) file.delete() } catch (_: Exception) {}
    }

    fun elapsedSec(): Int = (elapsedLockedMs() / 1000).toInt().coerceAtLeast(0)

    /** Active (unpaused) recording time in ms. 0 when not recording. */
    private fun elapsedLockedMs(): Long {
        if (startMs <= 0L) return 0L
        val now = System.currentTimeMillis()
        val paused = pausedAccumMs + (if (pauseBeganMs > 0L) now - pauseBeganMs else 0L)
        return (now - startMs - paused).coerceAtLeast(0L)
    }

    private fun safe(id: String): String {
        val s = id.trim().ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return s.take(48)
    }
}

/**
 * Cross-component recording state (service ↔ dialogs). The service owns the
 * MediaRecorder while the dialer is foreground; dialogs read this to render
 * the record button without binding to the service.
 */
object CallRecordingStore {
    @Volatile var recording: Boolean = false
        private set
    @Volatile var paused: Boolean = false
        private set
    @Volatile var consignmentId: String = ""
        private set
    @Volatile var filePath: String = ""
        private set
    @Volatile var startMs: Long = 0L
        private set

    fun onStarted(consignmentId: String, filePath: String, startMs: Long) {
        recording = true
        paused = false
        this.consignmentId = consignmentId
        this.filePath = filePath
        this.startMs = startMs
    }

    fun onPaused(paused: Boolean) {
        if (recording) this.paused = paused
    }

    fun onStopped() {
        recording = false
        paused = false
        consignmentId = ""
        filePath = ""
        startMs = 0L
    }
}
