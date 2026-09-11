package com.cloudx.databridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Background Sheet Sync (Call Center ⇪): runs [RemarkSheetMirror.bulkSyncToSheet]
 * for a chosen date range in a foreground dataSync service, so the sync keeps
 * going when the popup is minimized or the agent leaves the screen.
 *
 * - Progress: ongoing notification, updated per sheet ("Day 2/5 · Sheet X ·
 *   120/400 rows · 280 pending"), silent updates (no buzz per tick).
 * - Finish: heads-up "flash" notification with the TOTAL summary
 *   (synced/cells/filled/no-CC/filtered/scanned/errors), then stops itself.
 * - The open sync dialog (if any) gets the same progress + final summary via
 *   [onProgress]/[onFinish] hooks — purely best-effort UI mirrors.
 *
 * Single-flight: a second start while running is refused ([start] returns
 * false → caller toasts "already running").
 */
class SheetSyncService : Service() {

    companion object {
        const val ACTION_START = "com.cloudx.databridge.SHEET_SYNC_START"
        const val EXTRA_BRANCH_IDS = "branch_ids"
        const val EXTRA_START_DATE = "start_date" // yyyy-MM-dd
        const val EXTRA_END_DATE = "end_date" // yyyy-MM-dd

        private const val CHANNEL_PROGRESS = "sheet_sync_progress"
        private const val CHANNEL_SUMMARY = "sheet_sync_summary"
        private const val NOTIF_PROGRESS_ID = 5101
        private const val NOTIF_SUMMARY_ID = 5102

        @Volatile var isRunning: Boolean = false
            private set

        /** Live progress mirror for an open dialog (main thread). Null-safe. */
        @Volatile var onProgress: ((RemarkSheetMirror.BulkProgress) -> Unit)? = null

        /** One-shot finish hook for an open dialog. Cleared after delivery. */
        @Volatile var onFinish: ((String) -> Unit)? = null

        /** Starts a sync; false when one is already running. */
        fun start(context: Context, branchIds: List<String>, start: LocalDate, end: LocalDate): Boolean {
            if (isRunning) return false
            val intent = Intent(context.applicationContext, SheetSyncService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_BRANCH_IDS, ArrayList(branchIds))
                putExtra(EXTRA_START_DATE, start.toString())
                putExtra(EXTRA_END_DATE, end.toString())
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            } catch (_: Exception) {
                return false
            }
            return true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY
        isRunning = true
        val branchIds = intent.getStringArrayListExtra(EXTRA_BRANCH_IDS).orEmpty()
        val start = runCatching { LocalDate.parse(intent.getStringExtra(EXTRA_START_DATE)) }.getOrNull()
        val end = runCatching { LocalDate.parse(intent.getStringExtra(EXTRA_END_DATE)) }.getOrNull()
        if (branchIds.isEmpty() || start == null || end == null) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannels()
        ServiceCompat.startForeground(
            this,
            NOTIF_PROGRESS_ID,
            progressNotification("Starting…", 0, 0, start, end),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else 0
        )
        scope.launch {
            val summary = try {
                RemarkSheetMirror.bulkSyncToSheet(
                    appContext = applicationContext,
                    branchIds = branchIds,
                    onProgress = { /* label-level; detail carries counts */ },
                    // No onAuthNeeded: a service has no Activity for the auth
                    // dialog — the no-token case returns as summary text.
                    startDate = start,
                    endDate = end,
                    onProgressDetail = { p ->
                        try {
                            onProgress?.let { cb ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post { cb(p) }
                            }
                        } catch (_: Exception) { }
                        updateProgress(p, start, end)
                    }
                )
            } catch (e: Exception) {
                "Sync failed: ${e.message?.take(120) ?: "error"}"
            }
            finishWithSummary(summary)
        }
        return START_NOT_STICKY
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_PROGRESS, "Sheet sync progress", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Ongoing sheet-sync progress — required while sync runs"
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        })
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_SUMMARY, "Sheet sync summary", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Final sheet-sync total summary" })
    }

    private fun openAppIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this, 77, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun progressNotification(
        text: String, done: Int, total: Int, start: LocalDate, end: LocalDate,
    ): android.app.Notification {
        val range = if (start == end) start.toString() else "$start → $end"
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle("Syncing sheet · $range")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .setProgress(if (total > 0) total else 0, done, total <= 0)
            .build()
    }

    private fun updateProgress(p: RemarkSheetMirror.BulkProgress, start: LocalDate, end: LocalDate) {
        try {
            val text = "Day ${p.dayIndex}/${p.dayCount} · ${p.label} · " +
                "${p.rowsDone}/${p.rowsTotal} rows · ${p.pending} pending"
            val nm = NotificationManagerCompat.from(this)
            nm.notify(NOTIF_PROGRESS_ID, progressNotification(text, p.rowsDone, p.rowsTotal, start, end))
        } catch (_: Exception) { }
    }

    private fun finishWithSummary(summary: String) {
        try {
            val ok = summary.trimStart().startsWith("✓")
            val flash = NotificationCompat.Builder(this, CHANNEL_SUMMARY)
                .setContentTitle(if (ok) "✓ Sheet sync done" else "⚠ Sheet sync finished")
                .setContentText(summary.take(400))
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION") stopForeground(true)
                }
            } catch (_: Exception) {
                try {
                    @Suppress("DEPRECATION") stopForeground(true)
                } catch (_: Exception) { }
            }
            NotificationManagerCompat.from(this).notify(NOTIF_SUMMARY_ID, flash)
            try {
                val cb = onFinish
                onFinish = null
                onProgress = null
                cb?.let { android.os.Handler(android.os.Looper.getMainLooper()).post { it(summary) } }
            } catch (_: Exception) { }
        } finally {
            isRunning = false
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }
}
