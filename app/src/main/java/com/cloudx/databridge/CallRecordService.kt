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
import androidx.core.app.ServiceCompat

/**
 * Foreground service that owns the MIC recording while the agent is on a
 * parcel call (the dialer holds the foreground, so an in-activity recorder
 * would be killed — the service keeps it alive).
 *
 * Manual save only: started/stopped from the journey log's 🎙 button via
 * [start] / [stop]. The finished file stays in app-private storage; upload
 * happens only when the agent confirms (see SupabaseCallRecordings +
 * CallRecordingUploader). Stopping via the notification action works too.
 */
class CallRecordService : Service() {

    companion object {
        private const val CHANNEL_ID = "call_record_channel"
        private const val NOTIF_ID = 21
        const val ACTION_START = "com.cloudx.databridge.callrecord.START"
        const val ACTION_STOP = "com.cloudx.databridge.callrecord.STOP"
        const val ACTION_PAUSE = "com.cloudx.databridge.callrecord.PAUSE"
        const val ACTION_RESUME = "com.cloudx.databridge.callrecord.RESUME"
        const val EXTRA_CONSIGNMENT = "consignment_id"

        fun start(context: Context, consignmentId: String) {
            val intent = Intent(context, CallRecordService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONSIGNMENT, consignmentId)
            context.applicationContext.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CallRecordService::class.java).setAction(ACTION_STOP)
            context.applicationContext.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val cid = intent.getStringExtra(EXTRA_CONSIGNMENT).orEmpty()
                if (cid.isBlank()) { stopSelf(); return START_NOT_STICKY }
                goForeground(cid)
                val ok = CallRecordingManager.start(this, cid)
                if (!ok) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    refreshNotification()
                }
            }
            ACTION_STOP -> {
                CallRecordingManager.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_PAUSE -> {
                CallRecordingManager.pause()
                refreshNotification()
            }
            ACTION_RESUME -> {
                CallRecordingManager.resume()
                refreshNotification()
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(cid: String) {
        createChannel()
        val notif = buildNotification(cid, 0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
            } else {
                ServiceCompat.startForeground(this, NOTIF_ID, notif, 0)
            }
        } catch (e: Exception) {
            FirebaseErrorLogger.log("CallRecording", "fg_start_failed", e.message ?: "startForeground threw")
            stopSelf()
        }
    }

    private fun refreshNotification() {
        val cid = CallRecordingStore.consignmentId.ifBlank { return }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIF_ID, buildNotification(cid, CallRecordingManager.elapsedSec()))
    }

    private fun buildNotification(cid: String, elapsedSec: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                if (CallRecordingStore.paused) "⏸ Recording paused — $cid"
                else "🎙 Recording call — $cid"
            )
            .setContentText(
                if (CallRecordingManager.isBothSide) "Both-side capture · ${elapsedSec}s"
                else "Speaker auto-ON · অপর পাশ loudspeaker দিয়ে আসবে · ${elapsedSec}s"
            )
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                if (CallRecordingStore.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (CallRecordingStore.paused) "Resume" else "Pause",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, CallRecordService::class.java).setAction(
                        if (CallRecordingStore.paused) ACTION_RESUME else ACTION_PAUSE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop",
                PendingIntent.getService(
                    this, 0,
                    Intent(this, CallRecordService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Call recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        // Service killed (crash/reboot): never strand the recorder or a
        // half-written file — stop releases MediaRecorder either way.
        try { CallRecordingManager.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
