package com.cloudx.databridge

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shared glue between the journey timelines and manual call recordings:
 * loading recording rows as timeline entries, the ▶ play chip, and the
 * 🎙 record/stop/save flow. Used by CallCenter + Worker (record + play) and
 * ViewOrders + ParcelDetail (play only).
 */
object JourneyRecordingUi {

    // ── Load ─────────────────────────────────────────────────────────────

    /** Recording rows for one consignment as timeline-ready entries (oldest first). */
    suspend fun loadHistory(
        consignmentId: String,
        screen: String,
        nameOf: (systemId: String) -> String = { "" },
        photoOf: (systemId: String) -> String = { "" },
    ): List<HistoryEntry> {
        if (consignmentId.isBlank()) return emptyList()
        val rows = SupabaseCallRecordings.fetchForConsignment(consignmentId, screen)
        if (rows.isEmpty()) return emptyList()
        return JourneyLogUi.recordingsToHistory(
            SupabaseCallRecordings.toRecordings(rows, nameOf, photoOf)
        )
    }

    // ── Recording action row (play / download / share) ───────────────────

    /**
     * Wires the ▶ ⬇ ↗ row on a timeline entry. [root] is the inflated
     * R.layout.item_timeline_entry. Everything hides when the entry carries
     * no recording.
     *
     * [canDelete] + [onDeleted]: journey 🗑 chip for the uploader's own rows
     * (R2 object + metadata row, same order as the Cleanup panel). Other
     * screens pass false and never show it.
     *
     * Honest link semantics: the bucket is private, so "Copy link" hands out
     * a presigned URL valid ~5 minutes (same window as claim attachments) —
     * enough to open/send right away, NOT a permanent public link. The dialog
     * says so explicitly instead of promising forever-links.
     */
    fun bindRecordingActions(
        root: View,
        entry: HistoryEntry,
        scope: LifecycleCoroutineScope,
        canDelete: Boolean = false,
        onDeleted: (() -> Unit)? = null,
    ) {
        val row = root.findViewById<View>(R.id.layoutRecordingActions)
        val playView = root.findViewById<TextView>(R.id.twTimelinePlay)
        val dlView = root.findViewById<TextView>(R.id.twTimelineDownload)
        val shareView = root.findViewById<TextView>(R.id.twTimelineShare)
        val delView = root.findViewById<TextView>(R.id.twTimelineDelete)
        if (entry.recordingR2Key.isBlank()) {
            row.visibility = View.GONE
            playView.setOnClickListener(null)
            dlView.setOnClickListener(null)
            shareView.setOnClickListener(null)
            delView.visibility = View.GONE
            delView.setOnClickListener(null)
            return
        }
        row.visibility = View.VISIBLE
        bindPlay(playView, entry, scope)
        dlView.setOnClickListener {
            val ctx = dlView.context
            dlView.text = "⏳…"
            scope.launch {
                when (val dl = AttachmentUploader.getDownloadUrl(entry.recordingR2Key)) {
                    is AttachmentUploader.DownloadResult.Success -> {
                        enqueueDownload(ctx, dl.downloadUrl, fileNameFor(entry))
                        try { Toast.makeText(ctx, "⬇ Downloading… check notifications", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                    }
                    is AttachmentUploader.DownloadResult.Failed -> {
                        try { Toast.makeText(ctx, dl.message, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                    }
                }
                dlView.text = "⬇ Save"
            }
        }
        shareView.setOnClickListener {
            val ctx = shareView.context
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Share recording")
                .setItems(arrayOf("Send file (WhatsApp / …)", "Copy link (valid ~5 min)")) { _, which ->
                    if (which == 0) shareFile(root, entry, scope, shareView)
                    else copyLink(shareView, entry, scope)
                }
                .show()
        }
        if (canDelete && entry.recordingId.isNotBlank()) {
            delView.visibility = View.VISIBLE
            delView.setOnClickListener {
                deleteRecording(delView, entry, scope, onDeleted)
            }
        } else {
            delView.visibility = View.GONE
            delView.setOnClickListener(null)
        }
    }

    /**
     * Journey 🗑: confirm → R2 object (best-effort) → metadata row → [onDeleted]
     * reload. A row failure keeps the row (same orphan rule as Cleanup).
     */
    private fun deleteRecording(
        anchor: TextView,
        entry: HistoryEntry,
        scope: LifecycleCoroutineScope,
        onDeleted: (() -> Unit)?,
    ) {
        val ctx = anchor.context
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Delete recording?")
            .setMessage("R2 file + journey entry মুছে যাবে। Undo নেই।")
            .setPositiveButton("Delete") { _, _ ->
                anchor.isEnabled = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { AttachmentUploader.deleteObject(entry.recordingR2Key) }
                    }
                    val rowOk = withContext(Dispatchers.IO) {
                        SupabaseCallRecordings.deleteRow(entry.recordingId, "JourneyDelete")
                    }
                    try {
                        anchor.isEnabled = true
                        if (rowOk) {
                            Toast.makeText(ctx, "🗑 Recording deleted", Toast.LENGTH_SHORT).show()
                            onDeleted?.invoke()
                        } else {
                            Toast.makeText(ctx, "Couldn't delete — try again", Toast.LENGTH_LONG).show()
                        }
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fileNameFor(entry: HistoryEntry): String {
        val stamp = if (entry.createdAt > 0) entry.createdAt else System.currentTimeMillis()
        return "callrec_$stamp.m4a"
    }

    private fun enqueueDownload(ctx: android.content.Context, url: String, fileName: String) {
        try {
            val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager
                ?: return
            val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                .setTitle(fileName)
                .setDescription("Call recording")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                    "DataBridge-Recordings/$fileName"
                )
                .setAllowedOverMetered(true)
            dm.enqueue(req)
        } catch (e: Exception) {
            FirebaseErrorLogger.log("CallRecording", "download_enqueue_failed", e.message ?: "dm threw")
            try { Toast.makeText(ctx, "Couldn't start download", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    private fun shareFile(root: View, entry: HistoryEntry, scope: LifecycleCoroutineScope, shareView: TextView) {
        val ctx = shareView.context
        shareView.text = "⏳…"
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(ctx.cacheDir, "shared_audio").apply { mkdirs() }
                    dir.listFiles()?.forEach { try { it.delete() } catch (_: Exception) {} }
                    val out = File(dir, fileNameFor(entry))
                    when (val dl = AttachmentUploader.getDownloadUrl(entry.recordingR2Key)) {
                        is AttachmentUploader.DownloadResult.Failed -> null
                        is AttachmentUploader.DownloadResult.Success -> {
                            val req = okhttp3.Request.Builder().url(dl.downloadUrl).get().build()
                            SupabaseClientManager.httpClient.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) return@runCatching null
                                out.outputStream().use { o -> resp.body?.byteStream()?.copyTo(o) }
                            }
                            out.takeIf { it.length() > 0 }
                        }
                    }
                }.getOrNull()
            }
            shareView.text = "↗ Share"
            if (file == null) {
                try { Toast.makeText(ctx, "Couldn't fetch the recording — try again", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                return@launch
            }
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, "${ctx.packageName}.fileprovider", file)
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "audio/mp4"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(android.content.Intent.createChooser(send, "Share recording"))
            } catch (e: Exception) {
                FirebaseErrorLogger.log("CallRecording", "share_failed", e.message ?: "share threw")
                try { Toast.makeText(ctx, "No app found to share with", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
            }
        }
    }

    private fun copyLink(view: TextView, entry: HistoryEntry, scope: LifecycleCoroutineScope) {
        val ctx = view.context
        scope.launch {
            when (val dl = AttachmentUploader.getDownloadUrl(entry.recordingR2Key)) {
                is AttachmentUploader.DownloadResult.Success -> {
                    try {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("recording link", dl.downloadUrl))
                        Toast.makeText(ctx, "🔗 Link copied — valid ~5 min, open/send now", Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {}
                }
                is AttachmentUploader.DownloadResult.Failed -> {
                    try { Toast.makeText(ctx, dl.message, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun bindPlay(
        playView: TextView,
        entry: HistoryEntry,
        scope: LifecycleCoroutineScope,
    ) {
        val idleLabel = "▶ Play" +
            (if (entry.recordingDurationSec > 0) " (${JourneyLogUi.formatDurationSec(entry.recordingDurationSec)})" else "")
        playView.text = idleLabel
        playView.setOnClickListener {
            val ctx = playView.context
            playView.text = "⏳ Loading…"
            scope.launch {
                when (val dl = AttachmentUploader.getDownloadUrl(entry.recordingR2Key)) {
                    is AttachmentUploader.DownloadResult.Success -> {
                        CallRecordingPlayer.setListener { playing, _ ->
                            try {
                                playView.text = if (playing) "⏹ Stop" else idleLabel
                            } catch (_: Exception) {}
                        }
                        CallRecordingPlayer.toggle(dl.downloadUrl) { msg ->
                            try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                        }
                        playView.text = "⏹ Stop"
                    }
                    is AttachmentUploader.DownloadResult.Failed -> {
                        playView.text = idleLabel
                        try { Toast.makeText(ctx, dl.message, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    // ── Record button ────────────────────────────────────────────────────

    fun recordLabel(consignmentId: String): String =
        if (CallRecordingStore.recording && CallRecordingStore.consignmentId == consignmentId) {
            if (CallRecordingStore.paused) "⏸ Paused" else "⏹ Stop"
        } else "🎙 Record"

    fun hasAudioPermission(fragment: Fragment): Boolean =
        ContextCompat.checkSelfPermission(fragment.requireContext(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 🎙 tap routing:
     *  - recording this parcel → control dialog (pause / resume / stop-review).
     *  - another parcel recording → nudge to stop that one first.
     *  - unsent local file for this parcel → review dialog (upload / clear).
     *  - else → start confirm → service records (notification has pause/stop too).
     *
     * Nothing uploads without the agent seeing the review step first — except
     * the review's own 10s no-action auto-upload.
     */
    fun onRecordClick(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        button: TextView,
        consignmentId: String,
        branchId: String,
        authorSystemId: String,
        source: String,
        requestPermission: () -> Unit,
        onReload: () -> Unit,
    ) {
        val ctx = fragment.requireContext()
        // Another parcel's recording is running — stop that one first.
        if (CallRecordingStore.recording && CallRecordingStore.consignmentId != consignmentId) {
            Toast.makeText(ctx, "⏺ Already recording ${CallRecordingStore.consignmentId} — stop it first", Toast.LENGTH_LONG).show()
            return
        }
        // This parcel is recording — manage it (pause / resume / stop).
        if (CallRecordingStore.recording) {
            showControlDialog(fragment, scope, button, consignmentId, branchId, authorSystemId, source, onReload)
            return
        }
        // Leftover unsent file (notification-stop, dismissed review…) — review it.
        CallRecordingManager.pendingFile(ctx, consignmentId)?.let { file ->
            showReviewDialog(fragment, scope, file, consignmentId, branchId, authorSystemId, source,
                autoUpload = false, onReload = onReload,
                onClosed = { button.text = recordLabel(consignmentId) })
            return
        }
        // Start — needs mic permission first.
        if (!hasAudioPermission(fragment)) {
            requestPermission()
            return
        }
        // Mid-call starts often fail: the voice call holds the mic and the
        // manager's fallback sources can't always win it back. Say so upfront.
        val inCall = CallRecordingManager.isCallActive(ctx)
        val msg = if (inCall)
            "Call চলছে — এই অবস্থায় record সব phone-এ হয় না (mic call-এর দখলে থাকে)। Try করছি; শুরু না হলে call-এর আগেই 🎙 চাপুন।\n\nSpeakerphone (loudspeaker) ON রাখুন — না হলে অপর পাশের কথা উঠবে না।"
        else
            "Call-এ কথা বলার সময় SPEAKERPHONE (loudspeaker) ON রাখুন — না হলে অপর পাশের কথা record হবে না (Android limitation, শুধু আপনার কথা উঠবে).\n\nRecord শুরু করে তারপর dial করুন।"
        android.app.AlertDialog.Builder(ctx)
            .setTitle("🎙 Record this call?")
            .setMessage(msg)
            .setPositiveButton("Start") { _, _ ->
                CallRecordService.start(ctx, consignmentId)
                scope.launch {
                    delay(600)
                    button.text = recordLabel(consignmentId)
                    if (CallRecordingStore.recording) {
                        Toast.makeText(ctx, "⏺ Recording… speakerphone ON রাখুন", Toast.LENGTH_LONG).show()
                        // Started mid-call but possibly capturing silence (mic still
                        // held by telephony). Sample amplitude; all-zero over ~3s
                        // while a call is active almost certainly means blocked.
                        if (inCall) {
                            var heard = false
                            for (i in 0 until 3) {
                                delay(1000)
                                if (!CallRecordingStore.recording) break
                                if (CallRecordingManager.maxAmplitude() > 0) { heard = true; break }
                            }
                            if (!heard && CallRecordingStore.recording) {
                                try {
                                    Toast.makeText(
                                        ctx,
                                        "⚠ Mic blocked মনে হচ্ছে — file silent হতে পারে। Speakerphone ON করে দেখুন, না হলে call-এর আগে record করুন।",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } catch (_: Exception) {}
                            }
                        }
                    } else {
                        val why = if (CallRecordingManager.isCallActive(ctx))
                            "Call চলাকালীন mic busy — record শুরু করা যায়নি। Call-এর আগেই 🎙 চাপুন।"
                        else
                            "Couldn't start recording — mic busy?"
                        Toast.makeText(ctx, why, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Control dialog (pause / resume / stop) ─────────────────────────

    private fun controlTitle(consignmentId: String): String {
        val secs = CallRecordingManager.elapsedSec()
        val mm = secs / 60
        val ss = secs % 60
        val state = if (CallRecordingStore.paused) "⏸ Paused" else "⏺ Recording"
        return "$state %d:%02d · $consignmentId".format(mm, ss)
    }

    private fun showControlDialog(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        button: TextView,
        consignmentId: String,
        branchId: String,
        authorSystemId: String,
        source: String,
        onReload: () -> Unit,
    ) {
        val ctx = fragment.requireContext()
        val paused = CallRecordingStore.paused
        val dlg = android.app.AlertDialog.Builder(ctx)
            .setTitle(controlTitle(consignmentId))
            .setMessage(
                if (paused) "Paused — timer stopped. Resume to keep recording, or stop to review."
                else "Recording… speakerphone ON রাখুন। Pause করে বিরতি নিতে পারেন।"
            )
            .setPositiveButton("⏹ Stop") { _, _ ->
                stopAndReview(fragment, scope, button, consignmentId, branchId, authorSystemId, source, onReload)
            }
            .setNeutralButton(if (paused) "▶ Resume" else "⏸ Pause", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
                    Toast.makeText(ctx, "Pause needs Android 7+", Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                    return@setOnClickListener
                }
                // Route through the service so its notification (⏸/▶) refreshes too.
                val action = if (CallRecordingStore.paused) CallRecordService.ACTION_RESUME
                else CallRecordService.ACTION_PAUSE
                try {
                    ctx.applicationContext.startService(
                        android.content.Intent(ctx.applicationContext, CallRecordService::class.java)
                            .setAction(action)
                    )
                } catch (_: Exception) {}
                dlg.dismiss()
                scope.launch {
                    delay(400)
                    try { button.text = recordLabel(consignmentId) } catch (_: Exception) {}
                }
            }
        }
        dlg.show()
    }

    private fun stopAndReview(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        button: TextView,
        consignmentId: String,
        branchId: String,
        authorSystemId: String,
        source: String,
        onReload: () -> Unit,
    ) {
        val ctx = fragment.requireContext()
        button.isEnabled = false
        button.text = "⏳ Stopping…"
        scope.launch {
            CallRecordService.stop(ctx)
            delay(900) // let the service finalize the file
            button.isEnabled = true
            val file = CallRecordingManager.pendingFile(ctx, consignmentId)
            if (file == null) {
                button.text = recordLabel(consignmentId)
                try { Toast.makeText(ctx, "No audio captured", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                return@launch
            }
            button.text = recordLabel(consignmentId)
            showReviewDialog(fragment, scope, file, consignmentId, branchId, authorSystemId, source,
                autoUpload = true, onReload = onReload,
                onClosed = { button.text = recordLabel(consignmentId) })
        }
    }

    // ── Review dialog (upload / preview / clear + 10s auto-upload) ──────

    private const val REVIEW_AUTO_UPLOAD_SEC = 10

    private fun reviewDesc(file: File): String {
        val secs = audioDurationSec(file)
        val mm = secs / 60
        val ss = secs % 60
        val size = if (file.length() < 1024) "${file.length()} B"
        else "%.1f MB".format(file.length() / 1024.0 / 1024.0)
        return "%d:%02d · %s".format(mm, ss, size)
    }

    /**
     * Post-stop review. [autoUpload] (fresh stop) starts a 10s countdown in the
     * message — Clear inside the window cancels the upload; silence uploads.
     * Stale leftovers (notification-stop, dismissed review) pass false: no
     * countdown, the agent decides explicitly.
     */
    private fun showReviewDialog(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        file: File,
        consignmentId: String,
        branchId: String,
        authorSystemId: String,
        source: String,
        autoUpload: Boolean,
        onReload: () -> Unit,
        onClosed: () -> Unit,
    ) {
        val ctx = fragment.requireContext()
        var closed = false
        var countdown: kotlinx.coroutines.Job? = null
        val dlg = android.app.AlertDialog.Builder(ctx)
            .setTitle("🎙 Recording ready · ${reviewDesc(file)}")
            .setMessage(
                if (autoUpload) "$REVIEW_AUTO_UPLOAD_SEC s পরে auto-upload হবে — রাখতে না চাইলে Clear চাপুন।"
                else "Upload করে journey-তে save করুন, বা Clear করে মুছে দিন।"
            )
            .setPositiveButton("⬆ Upload", null)
            .setNeutralButton("▶ Play", null)
            .setNegativeButton("🗑 Clear", null)
            .setCancelable(true)
            .create()
        // Dismiss with no action (back/outside tap) = decide later: keep the
        // file on device, cancel the countdown. Next 🎙 tap re-opens review.
        dlg.setOnDismissListener {
            countdown?.cancel()
            CallRecordingPlayer.stop()
            if (!closed) {
                closed = true
                try { onClosed() } catch (_: Exception) {}
            }
        }
        dlg.setOnShowListener {
            val btnUpload = dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            val btnPlay = dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
            val btnClear = dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
            fun doUpload() {
                if (closed) return
                closed = true
                countdown?.cancel()
                CallRecordingPlayer.stop()
                btnUpload.isEnabled = false
                btnClear.isEnabled = false
                btnPlay.isEnabled = false
                dlg.setMessage("⏳ Uploading…")
                scope.launch {
                    val ok = performUpload(fragment, file, consignmentId, branchId, authorSystemId, source)
                    try {
                        dlg.dismiss()
                        onClosed()
                        if (ok) onReload()
                    } catch (_: Exception) {}
                }
            }
            btnUpload.setOnClickListener { doUpload() }
            btnPlay.setOnClickListener {
                try {
                    if (CallRecordingPlayer.isPlaying) {
                        CallRecordingPlayer.stop()
                        btnPlay.text = "▶ Play"
                    } else {
                        CallRecordingPlayer.setListener { playing, _ ->
                            try { btnPlay.text = if (playing) "⏹ Stop" else "▶ Play" } catch (_: Exception) {}
                        }
                        CallRecordingPlayer.toggle(file.absolutePath) { msg ->
                            try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
            btnClear.setOnClickListener {
                if (closed) return@setOnClickListener
                closed = true
                countdown?.cancel()
                CallRecordingPlayer.stop()
                CallRecordingManager.discard(file)
                try {
                    Toast.makeText(ctx, "🗑 Cleared — not uploaded", Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                    onClosed()
                } catch (_: Exception) {}
            }
            if (autoUpload) {
                countdown = scope.launch {
                    for (left in REVIEW_AUTO_UPLOAD_SEC downTo 1) {
                        try {
                            dlg.setMessage("$left s পরে auto-upload হবে — রাখতে না চাইলে Clear চাপুন।")
                        } catch (_: Exception) { return@launch }
                        delay(1000)
                    }
                    try { doUpload() } catch (_: Exception) {}
                }
            }
        }
        dlg.show()
    }

    /**
     * Uploads [file] + inserts the call_recordings row. True only when the
     * journey now has the entry (file discarded). False keeps the local file
     * so the agent can retry from the next 🎙 tap.
     */
    private suspend fun performUpload(
        fragment: Fragment,
        file: File,
        consignmentId: String,
        branchId: String,
        authorSystemId: String,
        source: String,
    ): Boolean {
        val ctx = fragment.requireContext()
        if (branchId.isBlank()) {
            try { Toast.makeText(ctx, "Branch unknown — recording kept on device, retry later", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            return false
        }
        val sysId = authorSystemId.ifBlank { resolveOwnSystemId().orEmpty() }
        if (sysId.isBlank()) {
            try { Toast.makeText(ctx, "Couldn't identify you — recording kept on device", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            return false
        }
        val durationSec = audioDurationSec(file)
        val up = withContext(Dispatchers.IO) {
            CallRecordingUploader.upload(file, "$consignmentId.m4a")
        }
        when (up) {
            is CallRecordingUploader.Result.Failed -> {
                try { Toast.makeText(ctx, "${up.message} — kept on device, tap 🎙 to retry", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                return false
            }
            is CallRecordingUploader.Result.Success -> {
                val id = SupabaseCallRecordings.insert(
                    screen = "JourneyRecording", consignment = consignmentId,
                    authorSystemId = sysId, source = source, branchId = branchId,
                    r2Key = up.objectKey, durationSec = durationSec, fileSizeBytes = file.length(),
                )
                if (id != null) {
                    CallRecordingManager.discard(file)
                    try { Toast.makeText(ctx, "✅ Recording saved to journey", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                    return true
                }
                try { Toast.makeText(ctx, "Upload ok, save failed — kept on device, tap 🎙 to retry", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                return false
            }
        }
    }

    /** Own system_id via Firebase uid → profile (same read WorkerSpace uses at login). */
    suspend fun resolveOwnSystemId(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@runCatching null
            FirebaseDatabase.getInstance().reference
                .child("users/$uid/profile/company_info/system_id")
                .get().await().getValue(String::class.java)?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    // ── Picker evidence upload ───────────────────────────────────────────
    // Agent picks any audio file from the device (past recordings, WhatsApp
    // audio, …) as future-reference evidence on this parcel's journey. Same
    // R2 family + 10 MB cap + play/share/download as in-app recordings.

    const val PICKER_MIME_TYPE = "audio/*"

    /**
     * Uploads a picked [uri] as journey evidence. [button] is the ＋Audio
     * button (shows ⏳ state); [authorSystemId] may be blank (resolved here).
     */
    fun uploadPickedAudio(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        button: TextView,
        uri: android.net.Uri,
        consignmentId: String,
        branchId: String,
        authorSystemId: String,
        source: String,
        onReload: () -> Unit,
    ) {
        val ctx = fragment.requireContext()
        button.isEnabled = false
        button.text = "⏳…"
        scope.launch {
            val sysId = authorSystemId.ifBlank { resolveOwnSystemId().orEmpty() }
            if (branchId.isBlank() || sysId.isBlank()) {
                button.isEnabled = true
                button.text = "＋ Audio"
                Toast.makeText(ctx, "Couldn't verify you/branch — try again", Toast.LENGTH_LONG).show()
                return@launch
            }
            val staged = withContext(Dispatchers.IO) { stagePickedAudio(ctx, uri) }
            if (staged == null) {
                button.isEnabled = true
                button.text = "＋ Audio"
                Toast.makeText(ctx, "Only audio files up to 10MB", Toast.LENGTH_LONG).show()
                return@launch
            }
            val (file, displayName) = staged
            button.text = "⏳ Uploading…"
            val durationSec = audioDurationSec(file)
            val up = withContext(Dispatchers.IO) {
                CallRecordingUploader.upload(file, displayName)
            }
            try { if (file.exists()) file.delete() } catch (_: Exception) {}
            when (up) {
                is CallRecordingUploader.Result.Failed -> {
                    button.isEnabled = true
                    button.text = "＋ Audio"
                    Toast.makeText(ctx, up.message, Toast.LENGTH_LONG).show()
                }
                is CallRecordingUploader.Result.Success -> {
                    button.text = "⏳ Saving…"
                    val id = SupabaseCallRecordings.insert(
                        screen = "JourneyEvidence", consignment = consignmentId,
                        authorSystemId = sysId, source = source, branchId = branchId,
                        r2Key = up.objectKey, durationSec = durationSec, fileSizeBytes = file.length(),
                        note = displayName,
                    )
                    button.isEnabled = true
                    button.text = "＋ Audio"
                    if (id != null) {
                        Toast.makeText(ctx, "✅ Audio evidence saved", Toast.LENGTH_SHORT).show()
                        onReload()
                    } else {
                        Toast.makeText(ctx, "Upload ok, save failed — try again", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /**
     * Validates a picked audio Uri (10 MB cap) and copies it into cache.
     * Returns the staged file + display name, or null when rejected.
     */
    private fun stagePickedAudio(ctx: android.content.Context, uri: android.net.Uri): Pair<File, String>? {
        return try {
            val resolver = ctx.contentResolver
            val mime = resolver.getType(uri).orEmpty()
            if (!mime.startsWith("audio/")) return null
            var name = uri.lastPathSegment.orEmpty()
            var size = -1L
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                        ?.let { name = c.getString(it) ?: name }
                    c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 }
                        ?.let { size = c.getLong(it) }
                }
            }
            if (name.isBlank()) name = "evidence.m4a"
            val dir = File(ctx.cacheDir, "evidence_stage").apply { mkdirs() }
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
            val out = File(dir, "${System.currentTimeMillis()}_$safeName")
            resolver.openInputStream(uri)?.use { ins ->
                out.outputStream().use { o -> ins.copyTo(o) }
            } ?: return null
            if (out.length() <= 0 || out.length() > CallRecordingUploader.MAX_FILE_BYTES) {
                try { out.delete() } catch (_: Exception) {}
                return null
            }
            out to name
        } catch (_: Exception) { null }
    }

    fun audioDurationSec(file: File): Int {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(file.absolutePath)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release()
            (ms / 1000).toInt().coerceAtLeast(0)
        } catch (_: Exception) { 0 }
    }
}
