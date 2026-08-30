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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.view.isVisible
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * Adds/removes the incoming-call popup as a real system overlay (WindowManager.addView),
 * so it draws over the phone app's native call screen -- same permission
 * (SYSTEM_ALERT_WINDOW) already requested during onboarding for the auto-dialer feature.
 * Shared by IncomingCallScreeningService (API 29+) and IncomingCallLegacyWatcherService
 * (API 23-28) so the actual popup behavior is identical regardless of which one detected
 * the call.
 *
 * Draggable to any position (persisted in databridge_toggles across calls) and can be
 * minimized to a small chip mid-call without fully dismissing it.
 */
object IncomingCallOverlay {

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoDismissRunnable: Runnable? = null
    private const val AUTO_DISMISS_MS = 30_000L
    private const val PREFS_NAME = "databridge_toggles"
    private const val KEY_POS_X = "overlay_pos_x"
    private const val KEY_POS_Y = "overlay_pos_y"

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

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lookupFromCcEnabled = prefs.getBoolean("lookup_from_cc", false)
        // The unmatched card exists only to offer the CC search shortcut -- with the
        // toggle off there's nothing useful to show for a call with no matched parcel.
        if (match == null && !lookupFromCcEnabled) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_incoming_call, null)

        val llExpanded = view.findViewById<View>(R.id.llOverlayExpanded)
        val llMinimized = view.findViewById<View>(R.id.llOverlayMinimized)
        val tvMinimizedLabel = view.findViewById<TextView>(R.id.tvOverlayMinimizedLabel)
        val tvViewDetails = view.findViewById<View>(R.id.btnOverlayViewDetails)
        val tvNoMatch = view.findViewById<TextView>(R.id.tvOverlayNoMatch)

        val displayName = if (match != null) match.name.ifBlank { "Unknown customer" } else "অজানা নম্বর"
        tvMinimizedLabel.text = "📞 $displayName"

        if (match != null) {
            view.findViewById<TextView>(R.id.tvOverlayName).text = displayName
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
            view.findViewById<TextView>(R.id.tvOverlayName).text = displayName
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

        // WRAP_CONTENT + explicit x/y (rather than the old MATCH_PARENT banner) is what
        // makes this a positionable card instead of a full-width bar — x/y are plain
        // top-left pixel offsets since gravity is TOP|START, matching setOnTouchListener's
        // drag math below one-to-one.
        val savedX = prefs.getInt(KEY_POS_X, dpToPx(context, 14))
        val savedY = prefs.getInt(KEY_POS_Y, dpToPx(context, 46))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        setupDrag(context, view, wm, params)
        setupMinimizeToggle(view, llExpanded, llMinimized)

        try {
            wm.addView(view, params)
            overlayView = view
            windowManager = wm
            layoutParams = params
            val runnable = Runnable { dismissInternal() }
            autoDismissRunnable = runnable
            mainHandler.postDelayed(runnable, AUTO_DISMISS_MS)
        } catch (_: Exception) {
            // Overlay permission revoked mid-flight, or OEM restriction -- fail silently,
            // this popup is a convenience, never something that should crash a call.
        }
    }

    /** Drag-anywhere-on-the-card-background repositioning. Buttons still get their own taps
     *  normally (a child view's own touch handling wins for touches landing on it), so this
     *  only fires for drags starting on the card's non-button surface — exactly how a floating
     *  chat-head-style widget is normally dragged. A touch that never moves past touchSlop is
     *  treated as a tap and passed through rather than consumed, so tapping the card background
     *  (as opposed to a button) doesn't accidentally swallow anything. */
    private fun setupDrag(context: Context, view: View, wm: WindowManager, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false // let a plain tap still reach a button underneath
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) isDragging = true
                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try { wm.updateViewLayout(view, params) } catch (_: Exception) { }
                    }
                    isDragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putInt(KEY_POS_X, params.x)
                            .putInt(KEY_POS_Y, params.y)
                            .apply()
                    }
                    val wasDragging = isDragging
                    isDragging = false
                    wasDragging
                }
                else -> false
            }
        }
    }

    /** Tapping the minimize icon collapses the card to just the small chip (llMinimized);
     *  tapping that chip re-expands. The window itself shrinks/grows with it since both
     *  states are WRAP_CONTENT — nothing to resize manually beyond toggling visibility. */
    private fun setupMinimizeToggle(view: View, llExpanded: View, llMinimized: View) {
        view.findViewById<View>(R.id.btnOverlayMinimize).setOnClickListener {
            llExpanded.isVisible = false
            llMinimized.isVisible = true
        }
        llMinimized.setOnClickListener {
            llMinimized.isVisible = false
            llExpanded.isVisible = true
        }
    }

    private fun dismissInternal() {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = null
        val view = overlayView ?: return
        overlayView = null
        val wm = windowManager
        windowManager = null
        layoutParams = null
        try {
            wm?.removeView(view)
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
