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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.google.firebase.database.FirebaseDatabase
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

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
    private var autoMinimizeRunnable: Runnable? = null
    private var remarkLoadJob: Job? = null
    private val overlayScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private const val AUTO_DISMISS_MS = 30_000L
    // Long enough to read the card at a glance, short enough that the screen doesn't
    // stay covered once the agent isn't actively looking at it — auto-collapses to the
    // small bubble instead of fully dismissing, so it's still one tap away.
    private const val AUTO_MINIMIZE_MS = 8_000L
    private const val PREFS_NAME = "databridge_toggles"
    private const val KEY_POS_X = "overlay_pos_x"
    private const val KEY_POS_Y = "overlay_pos_y"
    private const val EDGE_MARGIN_DP = 8

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

        // Both remaining navigation paths from this popup (View Full Details, Search in CC)
        // land in CallCenterFragment — gate them on the same permission that screen itself
        // requires, so a user without CC access never gets sent into a fragment they can't
        // actually use. hasPermission() reads an in-memory cache synchronously, safe to call
        // from this Service context with no Firebase round-trip.
        val hasCcAccess = RbacManager.hasPermission("nav_call_center")

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

            tvViewDetails.isVisible = hasCcAccess
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
        btnSearch.isVisible = lookupFromCcEnabled && hasCcAccess
        btnSearch.setOnClickListener {
            openCallCenterSearch(context, rawPhone)
            dismissInternal()
        }
        view.findViewById<View>(R.id.btnOverlayClose).setOnClickListener { dismissInternal() }

        // Remarks (matched parcel only — saving needs a consignment). Catalog
        // follows the same CC-priority rule as RemarkPopupOverlay.
        if (match != null) {
            val remarkSource = when {
                RbacManager.hasPermission("nav_call_center") -> "CC"
                RbacManager.hasPermission("nav_space") -> "WORKER"
                else -> null
            }
            if (remarkSource != null) setupRemarks(context, view, match, rawPhone, remarkSource)
        }

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
        setupMinimizeToggle(context, view, wm, params, llExpanded, llMinimized)

        try {
            wm.addView(view, params)
            overlayView = view
            windowManager = wm
            layoutParams = params
            val dismissRunnable = Runnable { dismissInternal() }
            autoDismissRunnable = dismissRunnable
            mainHandler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
            scheduleAutoMinimize(context, view, wm, params, llExpanded, llMinimized)
        } catch (_: Exception) {
            // Overlay permission revoked mid-flight, or OEM restriction -- fail silently,
            // this popup is a convenience, never something that should crash a call.
        }
    }

    /** Collapses to the bubble on its own after AUTO_MINIMIZE_MS of no interaction — cancelled
     *  by any drag (setupDrag) and by manual minimize/expand (setupMinimizeToggle), and
     *  re-scheduled after each of those so the countdown restarts from a fresh interaction
     *  rather than firing mid-read. Not scheduled again once already minimized — there's
     *  nothing further to collapse. */
    private fun scheduleAutoMinimize(
        context: Context, view: View, wm: WindowManager, params: WindowManager.LayoutParams,
        llExpanded: View, llMinimized: View
    ) {
        autoMinimizeRunnable?.let { mainHandler.removeCallbacks(it) }
        if (llMinimized.isVisible) return // already minimized, nothing to schedule
        val runnable = Runnable { minimize(context, view, wm, params, llExpanded, llMinimized) }
        autoMinimizeRunnable = runnable
        mainHandler.postDelayed(runnable, AUTO_MINIMIZE_MS)
    }

    private fun cancelAutoMinimize() {
        autoMinimizeRunnable?.let { mainHandler.removeCallbacks(it) }
        autoMinimizeRunnable = null
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
                    cancelAutoMinimize() // reading/interacting with it — don't collapse mid-touch
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
                    val llExpanded = view.findViewById<View>(R.id.llOverlayExpanded)
                    val llMinimized = view.findViewById<View>(R.id.llOverlayMinimized)
                    scheduleAutoMinimize(context, view, wm, params, llExpanded, llMinimized)
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
    private fun setupMinimizeToggle(
        context: Context, view: View, wm: WindowManager, params: WindowManager.LayoutParams,
        llExpanded: View, llMinimized: View
    ) {
        view.findViewById<View>(R.id.btnOverlayMinimize).setOnClickListener {
            minimize(context, view, wm, params, llExpanded, llMinimized)
        }
        llMinimized.setOnClickListener {
            llMinimized.isVisible = false
            llExpanded.isVisible = true
            // The bubble may have snapped to an edge at a width far narrower than the full
            // card — clamp x back on screen so expanding never pushes part of the card off
            // the right edge.
            view.post {
                val screenWidth = context.resources.displayMetrics.widthPixels
                val margin = dpToPx(context, EDGE_MARGIN_DP)
                val maxX = screenWidth - view.width - margin
                if (params.x > maxX) params.x = maxX.coerceAtLeast(margin)
                try { wm.updateViewLayout(view, params) } catch (_: Exception) { }
            }
            scheduleAutoMinimize(context, view, wm, params, llExpanded, llMinimized)
        }
    }

    /** Shared by both the manual minimize tap and the auto-minimize timeout: collapses to the
     *  chip, then snaps it to whichever screen edge (left/right) it's already closer to —
     *  standard floating-bubble behavior, so it settles out of the way rather than sitting
     *  wherever the much-wider full card happened to be. */
    private fun minimize(
        context: Context, view: View, wm: WindowManager, params: WindowManager.LayoutParams,
        llExpanded: View, llMinimized: View
    ) {
        cancelAutoMinimize()
        if (llMinimized.isVisible) return
        llExpanded.isVisible = false
        llMinimized.isVisible = true
        view.post {
            val screenWidth = context.resources.displayMetrics.widthPixels
            val margin = dpToPx(context, EDGE_MARGIN_DP)
            val cardCenterX = params.x + view.width / 2
            params.x = if (cardCenterX < screenWidth / 2) margin else screenWidth - view.width - margin
            try { wm.updateViewLayout(view, params) } catch (_: Exception) { }
        }
    }

    // ── Remarks from the incoming-call popup ─────────────────────────────
    // Same catalog + save path as the fragments (CC: Supabase source='CC' +
    // ccLang with a note box; WORKER: source='WORKER' + workerLang, chips
    // only, no note box). Fan-out to same-phone sibling parcels asks inline
    // (Yes/No) since a system overlay can't host a second dialog cleanly.

    private data class OverlayRemarkOption(
        val label: String,
        val englishLabel: String,
        val targetStatus: String,
        val instructionText: String,
        val category: String = ""
    )

    /** Latest validations row for a consignment: who it's assigned to + which
     *  branch the row carries. Null when the parcel never had a remark. */
    private data class ParcelRoute(val branchId: String, val agentSystemId: String)

    private suspend fun fetchOverlayRemarkOptions(source: String): List<OverlayRemarkOption> {
        return try {
            val db = FirebaseDatabase.getInstance().reference
            val langPath = if (source == "WORKER") "config/language/workerLang" else "config/language/ccLang"
            val defaultLang = if (source == "WORKER") "bn_bn" else "bn_en"
            val langValue = withContext(Dispatchers.IO) {
                db.child(langPath).get().await().getValue(String::class.java)
            }?.trim().orEmpty().ifBlank { defaultLang }
            val remarkLang = langValue.substringBefore("_").ifBlank { "bn" }
            SupabaseClientManager.fetchRemarkOptions("IncomingCallOverlay", source).mapNotNull { opt ->
                val label = if (remarkLang == "en") opt.textEn.ifBlank { opt.textBn } else opt.textBn.ifBlank { opt.textEn }
                if (label.isBlank()) return@mapNotNull null
                val target = opt.targetStatus.ifBlank { return@mapNotNull null }
                OverlayRemarkOption(label, opt.textEn.ifBlank { opt.textBn }, target, opt.instructionText, opt.category)
            }
        } catch (e: Exception) {
            FirebaseErrorLogger.log("IncomingCallOverlay", "fetch_remarks_failed", e.message ?: "")
            emptyList()
        }
    }

    private suspend fun resolveParcelRoute(consignmentId: String): ParcelRoute? {
        return try {
            val rows = SupabaseClientManager.fetchValidations(
                "IncomingCallOverlay", "resolve_route", listOf(
                    "consignment" to "eq.$consignmentId",
                    "order" to "created_at.desc",
                    "limit" to "1",
                )
            )
            val row = rows.firstOrNull() ?: return null
            ParcelRoute(
                branchId = row.optString("branch_id").trim(),
                agentSystemId = row.optString("assigned_to_system_id").trim()
            )
        } catch (e: Exception) {
            FirebaseErrorLogger.log(
                "IncomingCallOverlay", "resolve_route_failed", e.message ?: "",
                mapOf("consignment" to consignmentId)
            )
            null
        }
    }

    private suspend fun ownSystemId(): String {
        return try {
            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
            if (uid.isBlank()) return ""
            withContext(Dispatchers.IO) {
                FirebaseDatabase.getInstance().reference
                    .child("users/$uid/profile/company_info/system_id").get().await()
                    .getValue(String::class.java)
            }?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /** Other consignment IDs under the same caller number (via the existing
     *  courier/consignments_by_phone index), excluding the matched one. */
    private suspend fun siblingConsignmentIds(rawPhone: String, excludeId: String): List<String> {
        return try {
            val normalized = ConfigSheetParseUtil.normalizePhone(rawPhone)
            if (normalized.isBlank()) return emptyList()
            withContext(Dispatchers.IO) {
                FirebaseDatabase.getInstance().reference
                    .child("courier/consignments_by_phone/$normalized").get().await()
                    .children.mapNotNull { it.key }
            }.filter { it != excludeId }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun setupRemarks(context: Context, view: View, match: CallerMatch, rawPhone: String, source: String) {
        val btnSetRemarks = view.findViewById<View>(R.id.btnOverlaySetRemarks)
        val llRemarkSection = view.findViewById<View>(R.id.llOverlayRemarkSection)
        val llFanout = view.findViewById<View>(R.id.llOverlayFanout)
        val tvAgentMissing = view.findViewById<TextView>(R.id.tvOverlayAgentMissing)

        btnSetRemarks.isVisible = true
        btnSetRemarks.setOnClickListener {
            btnSetRemarks.isVisible = false
            llRemarkSection.isVisible = true
            cancelAutoMinimize() // reading/picking — don't collapse mid-interaction
            loadRemarkSection(context, view, match, rawPhone, source, source == "CC")
        }
        view.findViewById<TextView>(R.id.btnOverlayRemarkCancel).setOnClickListener {
            llRemarkSection.isVisible = false
            llFanout.isVisible = false
            tvAgentMissing.isVisible = false
            btnSetRemarks.isVisible = true
        }
    }

    private fun loadRemarkSection(
        context: Context, view: View, match: CallerMatch, rawPhone: String, source: String, isCc: Boolean
    ) {
        val chipContainer = view.findViewById<LinearLayout>(R.id.llOverlayRemarkChips)
        val etNote = view.findViewById<EditText>(R.id.etOverlayNote)
        val btnSave = view.findViewById<TextView>(R.id.btnOverlayRemarkSave)
        val tvAgentMissing = view.findViewById<TextView>(R.id.tvOverlayAgentMissing)

        remarkLoadJob?.cancel()
        remarkLoadJob = overlayScope.launch {
            val options = withContext(Dispatchers.IO) { fetchOverlayRemarkOptions(source) }
            val route = withContext(Dispatchers.IO) { resolveParcelRoute(match.consignmentId) }
            val selfSystemId = if (!isCc) withContext(Dispatchers.IO) { ownSystemId() } else ""
            if (overlayView !== view) return@launch // dismissed while loading

            // Agent resolution: CC saves against the parcel's assigned agent;
            // WORKER saves against self. Either way a blank id means no safe
            // save — point at Call Center instead of guessing.
            val agentId = if (isCc) route?.agentSystemId.orEmpty() else selfSystemId
            if (agentId.isBlank()) {
                tvAgentMissing.isVisible = true
                return@launch
            }

            renderOverlayChips(context, view, options, isCc)

            btnSave.setOnClickListener {
                val chosen = overlaySelectedOption
                val noteText = etNote.text?.toString()?.trim().orEmpty()
                if (chosen == null && (!isCc || noteText.isBlank())) {
                    Toast.makeText(context, "একটি রিমার্কস বেছে নিন", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // CC note-only save (no predefined option picked) mirrors
                // CallCenterFragment's sheet, which enables Save on note alone.
                saveFromOverlay(context, view, match, source, isCc, chosen = chosen, noteText = noteText,
                    agentId = agentId, selfSystemId = selfSystemId, rawPhone = rawPhone)
            }
        }
    }

    private var overlaySelectedOption: OverlayRemarkOption? = null

    private fun renderOverlayChips(context: Context, view: View, options: List<OverlayRemarkOption>, isCc: Boolean) {
        val chipContainer = view.findViewById<LinearLayout>(R.id.llOverlayRemarkChips)
        val etNote = view.findViewById<EditText>(R.id.etOverlayNote)
        chipContainer.removeAllViews()
        overlaySelectedOption = null

        if (options.isEmpty()) {
            val tv = TextView(context).apply {
                text = if (isCc) "⚠ Config-এ কোনো remark সেট করা নেই। নোট হিসেবে লিখতে পারেন:"
                else "⚠ Config-এ কোনো remark সেট করা নেই। Admin-কে remark যোগ করতে বলুন।"
                textSize = 12f
                setTextColor(0xFFF59E0B.toInt())
            }
            chipContainer.addView(tv)
        }

        if (isCc) etNote.isVisible = true

        val chipViews = mutableListOf<TextView>()
        fun refreshStyles() {
            chipViews.forEach { chip ->
                val isSelected = chip.tag == overlaySelectedOption
                chip.setBackgroundResource(
                    if (isSelected) R.drawable.bg_overlay_cta else R.drawable.bg_overlay_badge_status
                )
                chip.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
            }
        }
        options.forEach { option ->
            val chip = TextView(context).apply {
                text = option.label
                textSize = 12f
                setPadding(28, 16, 28, 16)
                setBackgroundResource(R.drawable.bg_overlay_badge_status)
                setTextColor(0xFF0F172A.toInt())
                tag = option
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8 }
                setOnClickListener {
                    overlaySelectedOption = if (overlaySelectedOption == option) null else option
                    // CC: selecting fills the admin-written instruction into
                    // the note box (blank clears) — CallCenterFragment parity.
                    if (isCc) etNote.setText(overlaySelectedOption?.instructionText.orEmpty())
                    refreshStyles()
                }
            }
            chipContainer.addView(chip)
            chipViews.add(chip)
        }
    }

    /** Save tap → sibling check → inline Yes/No fan-out (or direct save). */
    private fun saveFromOverlay(
        context: Context, view: View, match: CallerMatch, source: String, isCc: Boolean,
        chosen: OverlayRemarkOption?, noteText: String,
        agentId: String, selfSystemId: String, rawPhone: String
    ) {
        remarkLoadJob?.cancel()
        remarkLoadJob = overlayScope.launch {
            val siblings = withContext(Dispatchers.IO) { siblingConsignmentIds(rawPhone, match.consignmentId) }
            if (overlayView !== view) return@launch
            if (siblings.isEmpty()) {
                doOverlaySave(context, view, listOf(match.consignmentId), match, source, isCc,
                    chosen, noteText, agentId, selfSystemId)
            } else {
                showOverlayFanout(context, view, match, siblings, source, isCc,
                    chosen, noteText, agentId, selfSystemId)
            }
        }
    }

    private fun showOverlayFanout(
        context: Context, view: View, match: CallerMatch, siblings: List<String>,
        source: String, isCc: Boolean, chosen: OverlayRemarkOption?, noteText: String,
        agentId: String, selfSystemId: String
    ) {
        val llFanout = view.findViewById<View>(R.id.llOverlayFanout)
        val tvFanoutText = view.findViewById<TextView>(R.id.tvOverlayFanoutText)
        val total = siblings.size + 1
        tvFanoutText.text = "\"${match.consignmentId}\"-এর মতো একই নম্বরের মোট $total টি parcel আছে।\nসবগুলোতে একই remark দিতে চান?"
        llFanout.isVisible = true
        view.findViewById<View>(R.id.btnOverlayFanoutYes).setOnClickListener {
            llFanout.isVisible = false
            doOverlaySave(context, view, listOf(match.consignmentId) + siblings, match, source, isCc,
                chosen, noteText, agentId, selfSystemId)
        }
        view.findViewById<View>(R.id.btnOverlayFanoutNo).setOnClickListener {
            llFanout.isVisible = false
            doOverlaySave(context, view, listOf(match.consignmentId), match, source, isCc,
                chosen, noteText, agentId, selfSystemId)
        }
    }

    private fun doOverlaySave(
        context: Context, view: View, consignmentIds: List<String>, match: CallerMatch,
        source: String, isCc: Boolean, chosen: OverlayRemarkOption?, noteText: String,
        primaryAgentId: String, selfSystemId: String
    ) {
        val llRemarkSection = view.findViewById<View>(R.id.llOverlayRemarkSection)
        val tvConfirmation = view.findViewById<TextView>(R.id.tvOverlayConfirmation)
        val branchId = RbacManager.current.branchIds.firstOrNull().orEmpty()
        if (branchId.isBlank()) {
            Toast.makeText(context, "Branch তথ্য পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            return
        }
        remarkLoadJob?.cancel()
        remarkLoadJob = overlayScope.launch {
            withContext(Dispatchers.IO) {
                consignmentIds.forEach { cid ->
                    // Per-parcel route so siblings save under their own branch;
                    // fall back to the primary parcel's values when a sibling
                    // has no validations row yet.
                    val route = resolveParcelRoute(cid)
                    val targetBranch = route?.branchId?.takeIf { it.isNotBlank() } ?: branchId
                    if (isCc) {
                        val targetAgent = route?.agentSystemId?.takeIf { it.isNotBlank() } ?: primaryAgentId
                        if (targetAgent.isBlank()) return@forEach
                        SupabaseRemarkValidationWriter.write(
                            assignedAgentSystemId = targetAgent,
                            branchId = targetBranch,
                            consignmentId = cid,
                            status = chosen?.targetStatus.orEmpty(),
                            remarksText = chosen?.englishLabel.orEmpty(),
                            noteText = noteText,
                            source = "CC",
                            screen = "IncomingCallOverlay",
                            remarksBnText = chosen?.let {
                                it.label.takeIf { label -> label.isNotBlank() && label != it.englishLabel }
                            } ?: "",
                            verdictText = chosen?.category.orEmpty(),
                            appContext = context.applicationContext
                        )
                    } else {
                        if (selfSystemId.isBlank()) return@forEach
                        SupabaseRemarkValidationWriter.write(
                            assignedAgentSystemId = selfSystemId,
                            branchId = targetBranch,
                            consignmentId = cid,
                            status = chosen?.targetStatus.orEmpty(),
                            remarksText = chosen?.englishLabel.orEmpty(),
                            noteText = "",
                            source = "WORKER",
                            screen = "IncomingCallOverlay",
                            remarksBnText = chosen?.let {
                                it.label.takeIf { label -> label.isNotBlank() && label != it.englishLabel }
                            } ?: ""
                        )
                    }
                }
            }
            if (overlayView !== view) return@launch
            tvConfirmation.text = if (consignmentIds.size > 1)
                "✓ ${consignmentIds.size} টি parcel এ remark save হয়েছে"
            else "✓ রিমার্কস সেভ হয়েছে"
            llRemarkSection.isVisible = false
            view.findViewById<View>(R.id.llOverlayFanout).isVisible = false
            tvConfirmation.isVisible = true
            mainHandler.postDelayed({ dismissInternal() }, 2000)
        }
    }

    private fun dismissInternal() {
        remarkLoadJob?.cancel()
        remarkLoadJob = null
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = null
        cancelAutoMinimize()
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
