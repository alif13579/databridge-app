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
import androidx.core.view.isVisible
import java.text.NumberFormat
import java.util.Locale

/**
 * Reminder overlay for a parcel with an outstanding CC delivery-request (today's
 * DELIVERY_REQUEST, still unanswered — see fetchTodayDeliveryRequestsForWorker).
 * Shown by DeliveryReminderReceiver's alarm and by notification taps (via
 * DeliveryReminderTapReceiver).
 *
 * Three Bangla options, all writing/arming directly:
 * - "আজ ডেলিভারি করবো" → WORKER CONFIRMED remark (catalog text, so the
 *   Verify-Delivery dashboard counts it)
 * - "ডেলিভারি হয়ে গেছে" → WORKER DELIVERED remark (same)
 * - "পরে জানাচ্ছি" → no remark; one-shot reminder in 2h (snooze). A worker
 *   reply in the meantime still silences it.
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
        val otherPendingCount: Int = 0,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var autoDismissRunnable: Runnable? = null
    private const val AUTO_DISMISS_MS = 60_000L

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
        view.findViewById<TextView>(R.id.tvDrMoreCount).apply {
            isVisible = data.otherPendingCount > 0
            text = "+ আরও ${data.otherPendingCount}টা pending request আছে"
        }
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
            submitQuickRemark(context, data, "CONFIRMED", "The parcel will be delivered today")
        }
        view.findViewById<View>(R.id.optDrDelivered).setOnClickListener {
            submitQuickRemark(context, data, "DELIVERED", "The parcel has delivered to the customer")
        }
        view.findViewById<View>(R.id.optDrOthers).setOnClickListener {
            // "পরে জানাচ্ছি" — no remark, one reminder in 2h.
            DeliveryReminderReceiver.snooze(context, data.consignmentId)
            dismissInternal()
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
        val runnable = Runnable { dismissInternal() }
        autoDismissRunnable = runnable
        mainHandler.postDelayed(runnable, AUTO_DISMISS_MS)
    }

    /** Quick WORKER remark with catalog status + text (matches the
     *  validation_remarks WORKER options, so dashboards and Bangla labels
     *  resolve it like any worker reply — and the pending request clears). */
    private fun submitQuickRemark(context: Context, data: Data, status: String, remarkText: String) {
        SupabaseRemarkValidationWriter.write(
            assignedAgentSystemId = data.assignedAgentSystemId,
            branchId = data.branchId,
            consignmentId = data.consignmentId,
            status = status,
            remarksText = remarkText,
            noteText = "",
            source = "WORKER",
            screen = "DeliveryReminderOverlay"
        )
        dismissInternal()
    }

    private fun placeCall(context: Context, phone: String) {
        // Reuses AutoDialHelper's existing normalizer instead of dialing the raw stored
        // value as-is -- that value can come from Firebase in any shape ("8801XXXXXXXXX",
        // "+8801XXXXXXXXX", missing its leading 0, etc.), and normalizeBdPhone() already
        // handles every one of those into the correct 01XXXXXXXXX local dial format.
        val normalized = AutoDialHelper.normalizeBdPhone(phone)
        if (normalized.isBlank()) return
        val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        try {
            if (hasCallPerm) {
                context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$normalized")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } else {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$normalized")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
        } catch (_: Exception) {
        }
    }

    private fun dismissInternal() {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = null
        val wm = windowManager
        val view = overlayView
        if (wm != null && view != null) {
            try { wm.removeView(view) } catch (_: Exception) { }
        }
        windowManager = null
        overlayView = null
    }
}
