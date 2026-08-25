package com.cloudx.databridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.view.isVisible
import java.text.NumberFormat
import java.util.Locale

/**
 * Adds/removes the incoming-call popup as a real system overlay (WindowManager.addView),
 * so it draws over the phone app's native call screen -- same permission
 * (SYSTEM_ALERT_WINDOW) already requested during onboarding for the auto-dialer feature.
 * Shared by IncomingCallScreeningService (API 29+) and IncomingCallLegacyWatcherService
 * (API 23-28) so the actual popup behavior is identical regardless of which one detected
 * the call.
 */
object IncomingCallOverlay {

    private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoDismissRunnable: Runnable? = null
    private const val AUTO_DISMISS_MS = 30_000L

    fun show(context: Context, rawPhone: String, match: CallerMatch?, otherCount: Int) {
        val appContext = context.applicationContext
        if (!android.provider.Settings.canDrawOverlays(appContext)) return
        mainHandler.post { showInternal(appContext, rawPhone, match, otherCount) }
    }

    fun dismiss() {
        mainHandler.post { dismissInternal() }
    }

    private fun showInternal(context: Context, rawPhone: String, match: CallerMatch?, otherCount: Int) {
        dismissInternal() // never stack two

        val lookupFromCcEnabled = context.getSharedPreferences("databridge_toggles", Context.MODE_PRIVATE)
            .getBoolean("lookup_from_cc", false)
        // The unmatched card exists only to offer the CC search shortcut -- with the
        // toggle off there's nothing useful to show for a call with no matched parcel.
        if (match == null && !lookupFromCcEnabled) return

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_incoming_call, null)

        val tvViewDetails = view.findViewById<View>(R.id.btnOverlayViewDetails)
        val tvNoMatch = view.findViewById<TextView>(R.id.tvOverlayNoMatch)

        if (match != null) {
            view.findViewById<TextView>(R.id.tvOverlayName).text = match.name.ifBlank { "Unknown customer" }
            view.findViewById<TextView>(R.id.tvOverlayPhone).text = match.phone.ifBlank { rawPhone }
            view.findViewById<TextView>(R.id.tvOverlayStatus).text = match.statusLabel

            val tvCod = view.findViewById<TextView>(R.id.tvOverlayCod)
            if (match.cod > 0) {
                val nf = NumberFormat.getNumberInstance(Locale.US)
                tvCod.text = "Tk " + nf.format(match.cod) + " COD"
                tvCod.isVisible = true
            } else {
                tvCod.isVisible = false
            }

            val tvAddress = view.findViewById<TextView>(R.id.tvOverlayAddress)
            if (match.address.isNotBlank()) {
                tvAddress.text = "Address: ${match.address}"
                tvAddress.isVisible = true
            } else {
                tvAddress.isVisible = false
            }

            val tvMore = view.findViewById<TextView>(R.id.tvOverlayMore)
            if (otherCount > 0) {
                tvMore.text = "+$otherCount more active parcel" + if (otherCount > 1) "s" else ""
                tvMore.isVisible = true
            } else {
                tvMore.isVisible = false
            }

            tvViewDetails.isVisible = true
            tvNoMatch.isVisible = false
            tvViewDetails.setOnClickListener {
                openParcelDetail(context, match.consignmentId)
                dismissInternal()
            }
        } else {
            // No matching parcel — minimal card: just the raw number and a way to search it.
            view.findViewById<TextView>(R.id.tvOverlayName).text = "অজানা নম্বর"
            view.findViewById<TextView>(R.id.tvOverlayPhone).text = rawPhone
            view.findViewById<TextView>(R.id.tvOverlayStatus).isVisible = false
            view.findViewById<TextView>(R.id.tvOverlayCod).isVisible = false
            view.findViewById<TextView>(R.id.tvOverlayAddress).isVisible = false
            view.findViewById<TextView>(R.id.tvOverlayMore).isVisible = false
            tvViewDetails.isVisible = false
            tvNoMatch.isVisible = true
        }

        val btnSearch = view.findViewById<View>(R.id.btnOverlaySearch)
        btnSearch.isVisible = lookupFromCcEnabled
        btnSearch.setOnClickListener {
            openCallCenterSearch(context, rawPhone)
            dismissInternal()
        }
        view.findViewById<View>(R.id.btnOverlayClose).setOnClickListener { dismissInternal() }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = dpToPx(context, 46)
            x = dpToPx(context, 14)
            width = context.resources.displayMetrics.widthPixels - dpToPx(context, 28)
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            val runnable = Runnable { dismissInternal() }
            autoDismissRunnable = runnable
            mainHandler.postDelayed(runnable, AUTO_DISMISS_MS)
        } catch (_: Exception) {
            // Overlay permission revoked mid-flight, or OEM restriction -- fail silently,
            // this popup is a convenience, never something that should crash a call.
        }
    }

    private fun dismissInternal() {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = null
        val view = overlayView ?: return
        overlayView = null
        try {
            val windowManager = view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            windowManager?.removeView(view)
        } catch (_: Exception) {
            // Already removed / view not attached -- nothing to do.
        }
    }

    /** Same MainActivity deep-link MainActivity.handleNotificationIntent() already handles
     *  for tapping a status-bar notification -- reused as-is so this doesn't need its own
     *  navigation path. Defaults to the call-center scope (handleNotificationIntent's own
     *  default for anything other than "worker"), since an incoming customer call is a
     *  call-center scenario. */
    /** Opens the app straight into CallCenterFragment with [rawPhone] pre-filled in the
     *  search box -- for calls with no matched parcel (or when the agent wants to search
     *  manually instead of jumping to the auto-matched one). */
    private fun openCallCenterSearch(context: Context, rawPhone: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AppNotificationManager.EXTRA_SEARCH_PHONE, rawPhone)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun openParcelDetail(context: Context, consignmentId: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AppNotificationManager.EXTRA_PARCEL_ID, consignmentId)
            putExtra(AppNotificationManager.EXTRA_SCOPE, "cc")
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Nothing more we can do from a service context if this fails.
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
