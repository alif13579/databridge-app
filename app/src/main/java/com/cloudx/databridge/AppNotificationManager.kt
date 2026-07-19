package com.cloudx.databridge

import android.content.Context
import android.media.RingtoneManager

/**
 * In-app notification manager — holds a list of app-generated notifications
 * (new remarks, alerts, etc.) and drives the top-bar badge + notification sound.
 *
 * Lifecycle: process-scoped singleton. Notifications are in-memory only;
 * they reset when the app process is killed.
 */
object AppNotificationManager {

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

    fun setBadgeListener(listener: (Int) -> Unit) {
        badgeListener = listener
        listener(unreadCount) // deliver current count immediately
    }

    /**
     * Add a new notification. Plays a sound and updates the badge.
     * Should be called from the main thread (or post to main).
     */
    fun add(context: Context, item: NotifItem) {
        _notifications.add(0, item) // newest first
        if (_notifications.size > MAX_NOTIFICATIONS) {
            _notifications.removeAt(_notifications.lastIndex)
        }
        badgeListener?.invoke(unreadCount)
        playSound(context)
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
