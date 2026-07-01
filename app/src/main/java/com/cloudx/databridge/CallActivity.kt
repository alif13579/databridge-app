package com.cloudx.databridge

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

/**
 * Transparent activity for background/lockscreen auto-dial.
 * Multi-layer compatibility: Android 5.0 → 15+
 *
 * Flow: Service → CallActivity (wakes screen + unlocks) → ACTION_CALL/DIAL → finish()
 */
class CallActivity : Activity() {

    companion object {
        const val EXTRA_NUMBER    = "extra_number"
        const val EXTRA_DIAL_ONLY = "extra_dial_only"

        fun buildIntent(context: Context, number: String, dialOnly: Boolean = false): Intent {
            return Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_DIAL_ONLY, dialOnly)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Layer 1: Show over lockscreen & wake screen ────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Android 8.1+ (API 27+) — recommended API
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        // Always apply window flags for maximum compatibility
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        // ── Layer 2: Keyguard dismiss (older devices) ──────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val km = getSystemService(KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, null)
        }

        val number   = intent?.getStringExtra(EXTRA_NUMBER) ?: run { finish(); return }
        val dialOnly = intent?.getBooleanExtra(EXTRA_DIAL_ONLY, false) ?: false

        // ── Layer 3: Fire call intent ──────────────────────────────────
        try {
            val action = if (dialOnly) Intent.ACTION_DIAL else Intent.ACTION_CALL
            val callIntent = Intent(action, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(callIntent)
            Log.d("CallActivity", "📞 ${if (dialOnly) "Dialer" else "Call"}: $number")
        } catch (e: Exception) {
            Log.e("CallActivity", "❌ Call failed: ${e.message}")
        }

        finish()
    }
}
