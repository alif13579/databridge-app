package com.cloudx.databridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Restarts IncomingCallLegacyWatcherService after a device reboot, on API 23-28 only.
 *
 * That service is only ever started/stopped from SettingsFragment's "Caller ID popup"
 * switch (ContextCompat.startForegroundService()/stopService()) -- a foreground service
 * started that way does not survive a device reboot on its own, so without this receiver
 * the Settings switch would still show "on" (it just reflects the persisted
 * databridge_toggles/caller_id_popup preference) while the feature had silently stopped
 * working, until the user happened to open Settings and re-toggle it off then on.
 *
 * API 29+ has nothing to restart here: that path uses RoleManager.ROLE_CALL_SCREENING,
 * which is a system-held role that survives a reboot by itself -- IncomingCallScreeningService
 * is invoked by the platform whenever the role is held, with no service instance of this
 * app's own to keep alive in between.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return

        val enabled = context.getSharedPreferences("databridge_toggles", Context.MODE_PRIVATE)
            .getBoolean("caller_id_popup", false)
        if (!enabled) return

        // IncomingCallLegacyWatcherService.onCreate() already stops itself if
        // READ_PHONE_STATE isn't granted -- no need to duplicate that check here.
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, IncomingCallLegacyWatcherService::class.java)
            )
        } catch (_: Exception) {
            // Best-effort: a reboot-time restart failing here just means the user falls
            // back to the pre-existing behavior (re-toggle in Settings), never a crash.
        }
    }
}
