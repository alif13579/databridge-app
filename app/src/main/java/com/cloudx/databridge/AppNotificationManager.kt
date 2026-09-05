package com.cloudx.databridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-app notification manager — holds a list of app-generated notifications
 * (new remarks, alerts, etc.) and drives the top-bar badge + notification sound
 * + (while the app process is alive) a real Android status-bar notification.
 *
 * Fed from two paths: the fragments' Realtime subscriptions while a parcel
 * screen is open, and FCM data messages via DataBridgeMessagingService (which
 * is why a push can arrive — and re-arm the worker reminder chain — with the
 * app fully closed). Tap navigates to the parcel in both cases.
 */
object AppNotificationManager {

    // New channel ID resets an older device's previously low/silent channel setting.
    private const val CHANNEL_ID = "databridge_alerts_channel_v2"
    const val EXTRA_PARCEL_ID = "notif_parcel_id"
    const val EXTRA_SCOPE = "notif_scope"
    const val EXTRA_SEARCH_PHONE = "search_phone"
    // Agent-missing finder bypasses the "lookup_from_cc" settings toggle (the
    // agent explicitly asked to search this number) — permission still checked.
    const val EXTRA_FORCE_CC_SEARCH = "force_cc_search"

    data class NotifItem(
        val id: String = System.currentTimeMillis().toString() + (Math.random() * 1000).toInt(),
        val title: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val type: String = "remark",   // "remark" | "alert" | etc.
        val parcelId: String = "",     // consignment ID — for tap-to-navigate
        val scope: String = "cc",      // "cc" | "worker" — which fragment to open
        var read: Boolean = false
    )

    private const val MAX_NOTIFICATIONS = 50

    private val _notifications = mutableListOf<NotifItem>()

    /** Snapshot of all notifications, newest first. */
    val notifications: List<NotifItem> get() = _notifications.toList()

    val unreadCount: Int get() = _notifications.count { !it.read }

    /** Called by MainActivity to update the badge whenever count changes. */
    private var badgeListener: ((Int) -> Unit)? = null

    /**
     * Process-local signal for an open parcel screen. Realtime is the primary live path;
     * FCM gives the active screen an event-triggered fallback without any periodic poll.
     */
    private val remarkListeners = CopyOnWriteArraySet<(NotifItem) -> Unit>()

    fun addRemarkListener(listener: (NotifItem) -> Unit) {
        remarkListeners.add(listener)
    }

    fun removeRemarkListener(listener: (NotifItem) -> Unit) {
        remarkListeners.remove(listener)
    }

    fun setBadgeListener(listener: (Int) -> Unit) {
        badgeListener = listener
        listener(unreadCount) // deliver current count immediately
    }

    /**
     * Add a new notification. Plays a sound, updates the badge, and (if the
     * permission is granted) posts a real status-bar notification.
     * Should be called from the main thread (or post to main).
     */
    fun add(context: Context, item: NotifItem) {
        _notifications.add(0, item) // newest first
        if (_notifications.size > MAX_NOTIFICATIONS) {
            _notifications.removeAt(_notifications.lastIndex)
        }
        badgeListener?.invoke(unreadCount)
        if (item.type == "remark") {
            remarkListeners.forEach { it(item) }
        }
        playSound(context)
        showSystemNotification(context, item)
    }

    /** Mark all notifications as read and reset badge to 0. */
    fun markAllRead() {
        _notifications.forEach { it.read = true }
        badgeListener?.invoke(0)
    }

    /** Clear all notifications. */
    fun clearAll() {
        _notifications.clear()
        badgeListener?.invoke(0)
    }

    private var channelCreated = false

    /** Create the high-importance channel at app startup as well as on the first alert.
     * FCM can render a background notification without starting our Activity, so the
     * channel must already exist for the requested heads-up behavior to apply. */
    fun initialize(context: Context) {
        ensureChannel(context.applicationContext)
    }

    private fun ensureChannel(context: Context) {
        if (channelCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "New Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "New remarks and alerts on parcels"
            enableLights(true)
            enableVibration(true)
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(sound, audioAttributes)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        channelCreated = true
    }

    /**
     * Posts a real Android status-bar notification for [item]. Tapping it opens
     * MainActivity and navigates straight to the parcel (same parcelId/scope the
     * in-app bell's NotificationListBottomSheet already uses to navigate).
     *
     * Best-effort: silently does nothing if POST_NOTIFICATIONS isn't granted
     * (Android 13+) — same posture as playSound() and the rest of the app's
     * permission-gated features (e.g. DataBridgeService's CALL_PHONE checks).
     */
    private fun showSystemNotification(context: Context, item: NotifItem) {
        try {
            val appCtx = context.applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(appCtx, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
            ) return

            ensureChannel(appCtx)

            val openIntent = Intent(appCtx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_PARCEL_ID, item.parcelId)
                putExtra(EXTRA_SCOPE, item.scope)
            }
            val pendingIntent = PendingIntent.getActivity(
                appCtx,
                item.id.hashCode(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID)
                .setContentTitle(item.title)
                .setContentText(item.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.message))
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setTicker(item.title)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            NotificationManagerCompat.from(appCtx).notify(item.id.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission race (revoked between the check above and notify()) — best-effort.
        } catch (_: Exception) {
            // Never let a notification-display failure take down the caller.
        }
    }

    private fun playSound(context: Context) {
        try {
            val appCtx = context.applicationContext

            // If the notification stream is muted (silent mode / volume 0), Ringtone.play()
            // will silently no-op anyway — but on some OEM skins it can also throw. Check
            // first so we skip cleanly instead of relying on the catch block.
            val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            val notifVolume = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION) ?: 1
            if (notifVolume <= 0) return

            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
            val ringtone = RingtoneManager.getRingtone(appCtx, uri) ?: return

            // Explicit AudioAttributes — without this, some OEM ROMs (notably some
            // Android 8-11 skins) play the ringtone at the wrong/muted stream and it
            // comes out silent even though .play() returns normally.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                ringtone.audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }

            ringtone.play()
        } catch (_: Exception) { /* ignore — sound is best-effort */ }
    }
}
