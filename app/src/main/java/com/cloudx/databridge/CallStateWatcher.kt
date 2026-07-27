package com.cloudx.databridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Watches the device's real telephony call state so callers can reliably tell when a phone
 * call actually ENDED — as opposed to just regaining foreground focus, which the app can
 * (and often does) do while a call is still active in the background: e.g. the agent
 * switches back to DataBridge mid-call to check something, then switches away again. Plain
 * Fragment/Activity onPause()/onResume() cannot tell these two situations apart; this can.
 *
 * Requires READ_PHONE_STATE. If that isn't granted, [awaitCallEnd] returns false immediately
 * so callers can fall back to their own heuristic (e.g. a screen-focus-based signal) rather
 * than breaking entirely on older installs that haven't been through the updated onboarding.
 */
object CallStateWatcher {

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Suspends until the call state has gone OFFHOOK (a call is actually active — confirms
     * the dial we're tracking really went through) and then back to IDLE (call ended), or
     * until [timeoutMs] elapses. Requiring the OFFHOOK observation first avoids a false
     * "call ended" firing instantly for a state that was already IDLE before this call was
     * even placed (e.g. the dial failed, or there's a brief gap before the OS reports OFFHOOK).
     *
     * Returns true only on a genuine observed end-of-call. Returns false on timeout, missing
     * permission, or any error — callers should treat false as "fall back to your own signal",
     * not as "the call is still going".
     */
    suspend fun awaitCallEnd(ctx: Context, timeoutMs: Long): Boolean {
        if (!hasPermission(ctx)) return false
        val telephony = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return false

        val deferred = CompletableDeferred<Boolean>()
        var sawOffhook = false

        fun onState(state: Int) {
            if (state == TelephonyManager.CALL_STATE_OFFHOOK) sawOffhook = true
            if (state == TelephonyManager.CALL_STATE_IDLE && sawOffhook && !deferred.isCompleted) {
                deferred.complete(true)
            }
        }

        return try {
            // API 31+: PhoneStateListener's call-state callback is deprecated in favor of
            // TelephonyCallback. Branch to avoid the deprecation path on newer devices while
            // still supporting everything back to the app's actual minSdk.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onState(state)
                }
                telephony.registerTelephonyCallback(ctx.mainExecutor, callback)
                try {
                    withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
                } finally {
                    telephony.unregisterTelephonyCallback(callback)
                }
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(state)
                }
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                try {
                    withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
                } finally {
                    @Suppress("DEPRECATION")
                    telephony.listen(listener, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
