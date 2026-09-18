package com.cloudx.databridge

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Manual call recording to app-private storage (AAC in .m4a).
 *
 * Two-tier strategy (user asked: jekhane both-side hoy sekhane both-side,
 * jekhane hoyna sekhane loudspeaker diye holeo):
 *  - Android 9 and below (API <= 28): try VOICE_CALL first — on many OEMs
 *    (esp. Samsung) this captures BOTH sides without speaker. Needs no extra
 *    permission to *try*; where the HAL refuses it throws and we fall through
 *    to mic-side sources. Android 10+ blocks it for non-system apps
 *    (CAPTURE_AUDIO_OUTPUT, system-only), so it is skipped there to save time.
 *  - Everywhere else: mic-side sources (MIC → VOICE_COMMUNICATION →
 *    VOICE_RECOGNITION → CAMCORDER) + AUTO-SPEAKER assist — the manager turns
 *    speakerphone on while recording so the other side bleeds into the mic.
 *    Restored on stop. Without speaker only your side is audible (OS limit).
 *
 * Recommended flow stays: 🎙 Record FIRST, then dial — mid-call starts often
 * find the mic held by telephony. Files stay app-private until the agent
 * explicitly saves (upload to R2 + call_recordings row).
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
    /** True when the active source captures both sides (VOICE_CALL on Android 9-). */
    val isBothSide: Boolean get() = activeSourceName == "VOICE_CALL"
    /** True when speakerphone was auto-enabled for the running recording. */
    @Volatile var didAutoSpeaker: Boolean = false
        private set

    // Speaker-assist state (restored on stop).
    @Volatile private var speakerAssistOn = false
    @Volatile private var prevSpeakerOn = false
    @Volatile private var prevAudioMode = android.media.AudioManager.MODE_NORMAL
    @Volatile private var assistCtx: Context? = null

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
     * source (typical cause: an active call holding the mic).
     *
     * Source order: Android 9- tries VOICE_CALL (both sides) first, then
     * mic-side fallbacks; Android 10+ goes straight to mic-side + auto-speaker.
     */
    @Synchronized
    fun start(context: Context, consignmentId: String): Boolean {
        if (recorder != null) return false
        val appCtx = context.applicationContext
        val inCall = isCallActive(appCtx)
        val file = File(dir(appCtx), "${safe(consignmentId)}_${System.currentTimeMillis()}.$FILE_EXT")
        val sources = mutableListOf<Pair<Int, String>>()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // Android 9 and below: many OEMs still allow both-side capture here.
            sources += MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL"
        }
        // Mic-side fallbacks (mid-call the telephony stack often holds MIC
        // exclusively — on some HALs one of the alternates still wins).
        // VOICE_CALL deliberately absent on 10+: system-only, always throws.
        sources += listOf(
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
                didAutoSpeaker = false
                if (name != "VOICE_CALL") {
                    // Mic-only: other side needs loudspeaker — turn it on now
                    // (record-first-then-dial flow means the call starts after
                    // us, so assert early; re-asserted on dial via
                    // ensureSpeakerDuringCall). Both-side needs no speaker.
                    didAutoSpeaker = enableSpeakerAssist(appCtx)
                }
                CallRecordingStore.onStarted(consignmentId, file.absolutePath, startMs)
                FirebaseErrorLogger.log("CallRecording", "start_ok",
                    "source=$name bothSide=${name == "VOICE_CALL"} speaker=$didAutoSpeaker inCall=$inCall",
                    mapOf("source" to name))
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
        didAutoSpeaker = false
        restoreSpeakerLocked()
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

    /**
     * Re-assert speakerphone while a mic-only recording runs — call start
     * resets the speaker flag on some OEMs, so dial paths call this right
     * after launching the dialer when [isRecording] is true. No-op for
     * both-side captures (no speaker needed) or when nothing records.
     */
    fun ensureSpeakerDuringCall(context: Context) {
        if (recorder == null || isBothSide) return
        try {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE)
                as? android.media.AudioManager ?: return
            if (!am.isSpeakerphoneOn) {
                try { am.isSpeakerphoneOn = true } catch (_: Exception) {}
                didAutoSpeaker = true
                speakerAssistOn = true
            }
        } catch (_: Exception) {}
    }

    private fun audioManager(appCtx: Context): android.media.AudioManager? {
        return try {
            appCtx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        } catch (_: Exception) { null }
    }

    /** Turns speakerphone on for a mic-only recording; returns true if on. */
    private fun enableSpeakerAssist(appCtx: Context): Boolean {
        return try {
            val am = audioManager(appCtx) ?: return false
            if (!speakerAssistOn) {
                prevSpeakerOn = try { am.isSpeakerphoneOn } catch (_: Exception) { false }
                prevAudioMode = try { am.mode } catch (_: Exception) {
                    android.media.AudioManager.MODE_NORMAL
                }
                assistCtx = appCtx
            }
            // Outside a call the mode is NORMAL — IN_COMMUNICATION lets the
            // speaker flag stick so it is already on when the call begins.
            // During a real cellular call the stack moves to IN_CALL itself
            // and the speaker flag carries over on most HALs.
            try {
                if (am.mode == android.media.AudioManager.MODE_NORMAL) {
                    am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                }
            } catch (_: Exception) {}
            try { am.isSpeakerphoneOn = true } catch (_: Exception) { return false }
            speakerAssistOn = true
            try { am.isSpeakerphoneOn } catch (_: Exception) { true }
        } catch (_: Exception) { false }
    }

    private fun restoreSpeakerLocked() {
        if (!speakerAssistOn) return
        speakerAssistOn = false
        try {
            val ctx = assistCtx
            assistCtx = null
            val am = ctx?.let { audioManager(it) } ?: return
            try { am.isSpeakerphoneOn = prevSpeakerOn } catch (_: Exception) {}
            try { am.mode = prevAudioMode } catch (_: Exception) {}
        } catch (_: Exception) {}
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
