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
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
            ringtone?.play()
        } catch (_: Exception) { /* ignore — sound is best-effort */ }
    }
}
