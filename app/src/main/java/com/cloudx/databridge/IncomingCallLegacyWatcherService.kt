package com.cloudx.databridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Fallback for API 23-28 only -- IncomingCallScreeningService (RoleManager.ROLE_CALL_SCREENING)
 * needs API 29, so devices below that can't use it. On these older OS versions the
 * privacy restriction CallScreeningService exists to work around doesn't apply yet, so
 * the classic PhoneStateListener callback still reliably includes the phone number
 * directly -- no special role needed here, just READ_PHONE_STATE + READ_CALL_LOG
 * (already requested during onboarding for other features).
 *
 * Runs as a foreground service since anything that needs to keep listening in the
 * background is subject to Android's background execution limits otherwise.
 */
class IncomingCallLegacyWatcherService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var telephony: TelephonyManager? = null

    @Suppress("DEPRECATION")
    private val listener = object : PhoneStateListener() {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    val number = phoneNumber
                    if (!number.isNullOrBlank()) {
                        serviceScope.launch {
                            val result = IncomingCallerLookup.lookup(number) ?: return@launch
                            IncomingCallOverlay.show(applicationContext, result.primary, result.otherCount)
                        }
                    }
                }
                TelephonyManager.CALL_STATE_IDLE -> IncomingCallOverlay.dismiss()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        telephony = (getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.also {
            @Suppress("DEPRECATION")
            it.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    override fun onDestroy() {
        @Suppress("DEPRECATION")
        telephony?.listen(listener, PhoneStateListener.LISTEN_NONE)
        IncomingCallOverlay.dismiss()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "incoming_caller_id"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(channelId) == null) {
                manager?.createNotificationChannel(
                    NotificationChannel(channelId, "Caller ID Popup", NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("DataBridge Caller ID is active")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 8821
    }
}
