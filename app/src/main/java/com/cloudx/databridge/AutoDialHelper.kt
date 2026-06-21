package com.cloudx.databridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * 📞 AutoDialHelper — Smart call dispatcher
 *
 * Reads the "auto_dial" toggle from SharedPreferences and decides:
 *   OFF  → opens dialpad (ACTION_DIAL, no permission needed)
 *   ON   → direct call (ACTION_CALL):
 *            · Single SIM / default → calls immediately
 *            · Multi-SIM, no default → shows SIM-chooser dialog first
 *
 * Used by: WorkerSpaceFragment, CallCenterFragment
 */
object AutoDialHelper {

    fun dial(fragment: Fragment, phone: String) {
        val ctx = fragment.requireContext()
        val autoDial = ctx
            .getSharedPreferences("databridge_toggles", Context.MODE_PRIVATE)
            .getBoolean("auto_dial", false)

        if (!autoDial) {
            openDialpad(fragment, phone)
            return
        }

        // Auto-dial requires CALL_PHONE permission
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(ctx, "Auto dial needs Call permission. Opening dialer.", Toast.LENGTH_SHORT).show()
            openDialpad(fragment, phone)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val accounts = try {
                telecom.callCapablePhoneAccounts ?: emptyList()
            } catch (_: SecurityException) {
                emptyList()
            }

            if (accounts.size >= 2) {
                showSimChooser(fragment, phone, accounts, telecom)
            } else {
                callDirect(fragment, phone)
            }
        } else {
            callDirect(fragment, phone)
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun openDialpad(fragment: Fragment, phone: String) {
        try {
            fragment.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        } catch (_: Exception) {
            Toast.makeText(fragment.requireContext(), "No dialer app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun callDirect(fragment: Fragment, phone: String) {
        try {
            fragment.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")))
        } catch (_: Exception) {
            openDialpad(fragment, phone) // graceful fallback
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
    private fun showSimChooser(
        fragment: Fragment,
        phone: String,
        accounts: List<android.telecom.PhoneAccountHandle>,
        telecom: TelecomManager
    ) {
        val ctx = fragment.requireContext()
        val labels = accounts.mapIndexed { i, handle ->
            try {
                telecom.getPhoneAccount(handle)?.label?.toString() ?: "SIM ${i + 1}"
            } catch (_: Exception) {
                "SIM ${i + 1}"
            }
        }.toTypedArray()

        AlertDialog.Builder(ctx)
            .setTitle("📞 Select SIM to call")
            .setItems(labels) { _, which ->
                try {
                    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
                        putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accounts[which])
                    }
                    fragment.startActivity(intent)
                } catch (_: Exception) {
                    openDialpad(fragment, phone)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}