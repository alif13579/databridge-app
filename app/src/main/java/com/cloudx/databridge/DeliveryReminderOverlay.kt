package com.cloudx.databridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import java.util.Locale

/**
 * Reminder overlay for a parcel with an outstanding CC delivery-request (the consignment's
 * latest validations row is source='CC' -- the worker hasn't answered it yet). Scheduled by
 * DeliveryReminderReceiver at a random interval for as long as at least one such parcel
 * remains unanswered.
 *
 * Two of the three actions (will-deliver / delivered) write directly via
 * SupabaseRemarkValidationWriter.write() with a fixed remarks text -- same call CC/Worker
 * screens already use, source="WORKER". "Others" hands off to the app's existing
 * WorkerSpaceFragment.showWorkerRemarksDialog() instead of duplicating it here (this object
 * has no Fragment instance to call that private method on) -- see
 * WorkerSpaceFragment.PENDING_REMARKS_DIALOG_FOR handling.
 */
object DeliveryReminderOverlay {

    data class Data(
        val consignmentId: String,
        val branchId: String,
        val assignedAgentSystemId: String,
        val customerName: String,
        val customerPhone: String,
        val address: String,
        val status: String,
        val cod: Double,
        val ccRemarkText: String,
        val ccAuthorName: String,
        val ccRemarkAtMs: Long,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    fun show(context: Context, data: Data) {
        val appContext = context.applicationContext
        if (!android.provider.Settings.canDrawOverlays(appContext)) return
        mainHandler.post { showInternal(appContext, data) }
    }

    fun dismiss() {
        mainHandler.post { dismissInternal() }
    }

    private fun showInternal(context: Context, data: Data) {
        dismissInternal() // never stack two

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_delivery_reminder, null)

        view.findViewById<TextView>(R.id.tvDrConsignment).text = data.consignmentId
        view.findViewById<TextView>(R.id.tvDrCustomer).text =
            "${data.customerName.ifBlank { "Unknown customer" }} · ${data.customerPhone}"
        view.findViewById<TextView>(R.id.tvDrAddress).text = data.address.ifBlank { "—" }
        view.findViewById<TextView>(R.id.tvDrStatus).text = data.status.ifBlank { "—" }
        val nf = NumberFormat.getNumberInstance(Locale.US)
        view.findViewById<TextView>(R.id.tvDrCod).text = if (data.cod > 0) "৳" + nf.format(data.cod) else "—"

        view.findViewById<TextView>(R.id.tvDrCcRemark).text = data.ccRemarkText.ifBlank { "(no note)" }
        val ts = if (data.ccRemarkAtMs > 0) {
            val d = java.util.Date(data.ccRemarkAtMs)
            java.text.SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.US).format(d)
        } else "—"
        view.findViewById<TextView>(R.id.tvDrCcMeta).text = "${data.ccAuthorName.ifBlank { "CC" }} · $ts"

        view.findViewById<View>(R.id.btnDrClose).setOnClickListener { dismissInternal() }

        view.findViewById<View>(R.id.optDrWillDeliver).setOnClickListener {
            submitQuickRemark(context, data, "The parcel will be delivered")
        }
        view.findViewById<View>(R.id.optDrDelivered).setOnClickListener {
            submitQuickRemark(context, data, "The parcel has delivered")
        }
        view.findViewById<View>(R.id.optDrOthers).setOnClickListener {
            dismissInternal()
            openAppForOthersDialog(context, data.consignmentId)
        }
        view.findViewById<View>(R.id.btnDrCall).setOnClickListener { placeCall(context, data.customerPhone) }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 80
        }

        try {
            wm.addView(view, params)
        } catch (_: Exception) {
            return
        }
        windowManager = wm
        overlayView = view
    }

    private fun submitQuickRemark(context: Context, data: Data, remarkText: String) {
        SupabaseRemarkValidationWriter.write(
            assignedAgentSystemId = data.assignedAgentSystemId,
            branchId = data.branchId,
            consignmentId = data.consignmentId,
            status = "",
            remarksText = remarkText,
            noteText = "",
            source = "WORKER",
            screen = "DeliveryReminderOverlay"
        )
        dismissInternal()
    }

    /** No Fragment instance to call WorkerSpaceFragment.showWorkerRemarksDialog() on from
     *  here -- bring the app to the front instead and let it open that same dialog for
     *  this specific parcel once WorkerSpaceFragment has loaded (see its
     *  PENDING_REMARKS_DIALOG_FOR handling in onResume/loadData). */
    private fun openAppForOthersDialog(context: Context, consignmentId: String) {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(WorkerSpaceFragment.PENDING_REMARKS_DIALOG_FOR, consignmentId)
        })
    }

    private fun placeCall(context: Context, phone: String) {
        if (phone.isBlank()) return
        val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        try {
            if (hasCallPerm) {
                context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } else {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
        } catch (_: Exception) {
        }
    }

    private fun dismissInternal() {
        val wm = windowManager
        val view = overlayView
        if (wm != null && view != null) {
            try { wm.removeView(view) } catch (_: Exception) { }
        }
        windowManager = null
        overlayView = null
    }
}
