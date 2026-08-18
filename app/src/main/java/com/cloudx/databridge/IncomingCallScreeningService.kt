package com.cloudx.databridge

import android.telecom.Call
import android.telecom.CallScreeningService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Primary path (API 29+) for the incoming-call popup. Android 12+ stripped the phone
 * number out of the simple TelephonyCallback/PhoneStateListener path for privacy reasons
 * (see CallStateWatcher.kt's API split for that same boundary on the state-only side) --
 * CallScreeningService is the platform's supported way to still see the number, but it
 * requires being granted RoleManager.ROLE_CALL_SCREENING, which only one app can hold at
 * a time (enabling this in Settings will replace Truecaller or the carrier's own spam
 * screening if the user has one set). Requested from SettingsFragment, not here.
 *
 * This never blocks or silences a call -- onScreenCall always responds with an empty
 * (allow-through) CallResponse. It only reads the number to look up a possible customer
 * match and, if found, show the popup; screening/blocking is not something this feature
 * does or should do.
 */
class IncomingCallScreeningService : CallScreeningService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onScreenCall(callDetails: Call.Details) {
        // Always let the call through -- this service exists to read caller info, not
        // to screen/block anything. Respond immediately regardless of what the lookup
        // below finds or how long it takes.
        respondToCall(callDetails, CallResponse.Builder().build())

        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) return
        val prefs = applicationContext.getSharedPreferences("databridge_toggles", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("caller_id_popup", false)) return
        val number = callDetails.handle?.schemeSpecificPart ?: return
        if (number.isBlank()) return

        serviceScope.launch {
            val result = IncomingCallerLookup.lookup(number) ?: return@launch
            IncomingCallOverlay.show(applicationContext, result.primary, result.otherCount)
        }
    }
}
