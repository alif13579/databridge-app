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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
    // Refresh well inside EngagedStateManager's 5-minute staleness window so
    // workers keep seeing the ring as long as this popup is up.
    private const val ENGAGED_REFRESH_MS = 120_000L
    // Parcels this popup currently holds engaged (+ whose uid, for clearing).
    private val overlayEngagedIds = mutableSetOf<String>()
    private var overlayEngagedUid = ""
    private var engagedRefreshRunnable: Runnable? = null
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

        // Both remaining navigation paths from this popup land in CallCenter or
        // Worker Space — gate them on the same permissions those screens
        // require, so a user without access never gets sent into a fragment
        // they can't use. hasPermission() reads an in-memory cache
        // synchronously, safe from this Service context.
        val hasCcAccess = RbacManager.hasPermission("nav_call_center")
        val hasWorkerAccess = RbacManager.hasPermission("nav_space")
        // CC agents go to Call Center search; workers (no CC access) go to
        // their own space's search — both pre-filled, filter all.
        val useWorkerSearch = !hasCcAccess && hasWorkerAccess

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
            // Today's assigned agent (from today's run nodes — NOT validations,
            // which only exists after someone already saved a remark). Async;
            // the card renders first, this line fills in when ready.
            overlayScope.launch {
                val assignee = withContext(Dispatchers.IO) {
                    IncomingCallerLookup.resolveTodayAssignees(listOf(match.consignmentId), rawPhone)
                        .values.firstOrNull()
                }
                if (overlayView !== view) return@launch
                view.findViewById<TextView>(R.id.tvOverlayAssignee).apply {
                    if (assignee == null) {
                        // No run found for this number — say so plainly; the
                        // finder button below jumps into CC search (number
                        // pre-filled, filter all) to find the parcel manually.
                        text = "🔍 agent পাওয়া যায়নি — নিচে CC-তে খুঁজুন দিয়ে parcel টি বের করুন"
                    } else {
                        text = "🚚 আজ assign: ${assignee.name}"
                    }
                    isVisible = true
                }
            }
            // Supabase remark history (same rows as CC's journey log): a last-CC
            // banner against duplicate remarks + an expander for the full trail.
            overlayScope.launch {
                val rows = fetchHistoryRows(match.consignmentId)
                if (overlayView !== view || rows.isEmpty()) return@launch
                val names = resolveHistoryAuthorNames(rows)
                val sorted = rows.sortedByDescending {
                    SupabaseRemarkValidationWriter.parseCreatedAtMillis(it.optStr("created_at"))
                }
                sorted.firstOrNull {
                    it.optStr("source").trim().equals("CC", ignoreCase = true)
                }?.let { lastCc ->
                    view.findViewById<TextView>(R.id.tvOverlayLastCc).apply {
                        text = "🏷️ Last CC: ${historyRowText(lastCc)} · ${historyRowTime(lastCc)}" +
                            historyAuthorSuffix(lastCc, names)?.let { " ($it)" }.orEmpty()
                        isVisible = true
                    }
                }
                view.findViewById<TextView>(R.id.btnOverlayHistoryToggle).apply {
                    text = "▼ Remarks history (${sorted.size})"
                    isVisible = true
                    setOnClickListener {
                        val scroller = view.findViewById<View>(R.id.svOverlayHistoryList)
                        val list = view.findViewById<LinearLayout>(R.id.llOverlayHistoryList)
                        val expanding = !(scroller?.isVisible == true)
                        if (expanding) {
                            renderHistoryList(view, sorted, names)
                            cancelAutoMinimize() // reading — don't collapse mid-read
                        }
                        scroller?.isVisible = expanding
                        text = (if (expanding) "▲" else "▼") + " Remarks history (${sorted.size})"
                    }
                }
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
        // Matched card: explicit tap always bypasses the lookup toggle (force),
        // CC permission still checked — the toggle only gates the unmatched
        // card and background behaviors, never an explicit finder tap.
        // Unmatched card keeps the old gate (without a match it exists only
        // to offer the shortcut). Workers get the same finder into their own
        // space's search.
        val hasAnySearchAccess = hasCcAccess || hasWorkerAccess
        val showFinder = if (match != null) hasAnySearchAccess else lookupFromCcEnabled && hasAnySearchAccess
        btnSearch.isVisible = showFinder
        if (showFinder && useWorkerSearch) {
            (btnSearch as? TextView)?.text = "🔍 খুঁজুন"
        }
        btnSearch.setOnClickListener {
            if (useWorkerSearch) openWorkerSearch(context, rawPhone)
            else openCallCenterSearch(context, rawPhone, force = match != null)
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
            // Hold engaged on the matched parcel while the popup lives so the
            // assigned worker sees someone is working it (cleared on dismiss).
            if (match != null) markOverlayEngaged(listOf(match.consignmentId))
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

    /** Drag repositioning. The whole card listens, but scrollable children
     *  (remarks chips, history) and buttons consume their own gestures — so
     *  the TOP HEADER BAR is also a dedicated drag handle (it has no scroller,
     *  only two small buttons), guaranteeing drag always works from there.
     *  A touch that never moves past touchSlop is treated as a tap and passed
     *  through rather than consumed. */
    private fun setupDrag(context: Context, view: View, wm: WindowManager, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        val dragListener = View.OnTouchListener { _, event ->
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
        view.setOnTouchListener(dragListener)
        // Dedicated handle: header has no scroll container, so drags starting
        // here can't be stolen by one.
        view.findViewById<View>(R.id.llOverlayHeader)?.setOnTouchListener(dragListener)
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

    // ── Supabase history for the popup (compact journey log) ─────────────
    // Same rows as CallCenterFragment's journey dialog (fetchHistory = newest
    // first, Bangla labels injected). Rendered as plain rows — a system overlay
    // can't host dialogs or lists cleanly. Capped so a long trail can't blow up
    // the card.

    private suspend fun fetchHistoryRows(consignmentId: String): List<org.json.JSONObject> =
        withContext(Dispatchers.IO) {
            val deferred = CompletableDeferred<List<org.json.JSONObject>>()
            SupabaseRemarkValidationWriter.fetchHistory(consignmentId, "IncomingCallOverlay") {
                deferred.complete(it)
            }
            runCatching { withTimeout(20_000) { deferred.await() } }.getOrDefault(emptyList())
        }

    /** author_system_id → display name for history rows (embedded author object
     *  first, Firebase profile second, raw id last — same fallback chain as CC). */
    private suspend fun resolveHistoryAuthorNames(rows: List<org.json.JSONObject>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val ids = rows.map { it.optStr("author_system_id").trim() }
                .filter { it.isNotBlank() }.distinct()
            if (ids.isEmpty()) return@withContext emptyMap()
            val out = mutableMapOf<String, String>()
            rows.forEach { r ->
                val sys = r.optStr("author_system_id").trim()
                if (sys.isNotBlank() && sys !in out) {
                    r.optJSONObject("author")?.optStr("name")?.trim()
                        ?.takeIf { it.isNotBlank() }?.let { out[sys] = it }
                }
            }
            val missing = ids.filter { it !in out }
            if (missing.isEmpty()) return@withContext out
            try {
                val indexSnap = FirebaseDatabase.getInstance().reference
                    .child("users_by_systemId").get().await()
                val sysToUid = mutableMapOf<String, String>()
                indexSnap.children.forEach { child ->
                    val sys = child.key?.trim()
                    val uid = child.child("uid").getValue(String::class.java)?.trim()
                    if (!sys.isNullOrBlank() && sys in missing && !uid.isNullOrBlank()) {
                        sysToUid[sys] = uid
                    }
                }
                sysToUid.forEach { (sys, uid) ->
                    runCatching {
                        FirebaseDatabase.getInstance().reference
                            .child("users/$uid/profile/name").get().await()
                            .getValue(String::class.java)?.trim()
                    }.getOrNull()?.takeIf { it.isNotBlank() }?.let { out[sys] = it }
                }
            } catch (_: Exception) { }
            out
        }

    private fun historyRowText(row: org.json.JSONObject): String {
        val remarks = row.optStr("remarks_bn").trim()
            .ifBlank { row.optStr("remarks").trim() }
        val note = row.optStr("note").trim()
        return listOf(remarks, note.takeIf { it.isNotBlank() }?.let { "Note: $it" })
            .filterNotNull().filter { it.isNotBlank() }.joinToString("\n")
            .ifBlank { row.optStr("remarks_status").trim().ifBlank { "—" } }
    }

    private fun historyRowTime(row: org.json.JSONObject): String {
        val ms = SupabaseRemarkValidationWriter.parseCreatedAtMillis(row.optStr("created_at"))
        if (ms <= 0L) return ""
        return java.text.SimpleDateFormat("dd-MM-yy hh:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(ms))
    }

    private fun historyAuthorSuffix(row: org.json.JSONObject, names: Map<String, String>): String? {
        val sys = row.optStr("author_system_id").trim()
        if (sys.isBlank()) return null
        val src = row.optStr("source").trim().ifBlank { null }?.uppercase()
        val base = names[sys] ?: sys
        return if (src != null) "$base · $src" else base
    }

    private fun renderHistoryList(
        view: View, rows: List<org.json.JSONObject>, names: Map<String, String>
    ) {
        val context = view.context
        val container = view.findViewById<LinearLayout>(R.id.llOverlayHistoryList)
        container.removeAllViews()
        // All rows (scrollable now) + cap the scroller height so the window
        // never grows past the screen.
        rows.forEachIndexed { index, row ->
            val status = row.optStr("remarks_status").trim().ifBlank { "NOTE" }.uppercase()
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 10)
            }
            val tvHead = TextView(context).apply {
                text = "• $status — ${historyRowTime(row)}"
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFF0F172A.toInt())
            }
            line.addView(tvHead)
            val tvBody = TextView(context).apply {
                text = historyRowText(row)
                textSize = 11.5f
                setTextColor(0xFF334155.toInt())
            }
            line.addView(tvBody)
            historyAuthorSuffix(row, names)?.let { author ->
                line.addView(TextView(context).apply {
                    text = "✍ $author"
                    textSize = 10.5f
                    setTextColor(0xFF64748B.toInt())
                })
            }
            container.addView(line)
            if (index < rows.size - 1) {
                container.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(0xFFE2E8F0.toInt())
                })
            }
        }
        capScrollerHeight(view, R.id.svOverlayHistoryList)
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
                branchId = row.optStr("branch_id").trim(),
                agentSystemId = row.optStr("assigned_to_system_id").trim()
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
            view.findViewById<View>(R.id.btnOverlayAgentMissingSearch).isVisible = false
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
            // Today's run assignment for the matched parcel + same-number siblings —
            // the CC fallback when no validations row exists yet (first remark).
            val siblingIds = withContext(Dispatchers.IO) { siblingConsignmentIds(rawPhone, match.consignmentId) }
            val todayAssignees = withContext(Dispatchers.IO) {
                IncomingCallerLookup.resolveTodayAssignees(listOf(match.consignmentId) + siblingIds, rawPhone)
            }
            if (overlayView !== view) return@launch // dismissed while loading
            // Same-number siblings get the engaged ring too (fan-out save may
            // touch them) — still only our own entry, cleared on dismiss.
            if (siblingIds.isNotEmpty()) markOverlayEngaged(siblingIds)

            // Agent resolution: CC saves against the parcel's assigned agent —
            // validations row first, today's run assignment as fallback (a parcel
            // nobody remarked on yet has no row but usually has a run).
            // WORKER saves against self. Either way a blank id means no safe
            // save — point at Call Center instead of guessing.
            val agentId = if (isCc) {
                route?.agentSystemId?.takeIf { it.isNotBlank() }
                    ?: todayAssignees[match.consignmentId]?.systemId.orEmpty()
            } else selfSystemId
            if (agentId.isBlank()) {
                tvAgentMissing.isVisible = true
                // No run + no validations row: offer a one-tap jump into search
                // with this number pre-filled — no manual typing. Force flag
                // bypasses the lookup toggle (explicit tap), permission still
                // gated. Workers land in their own space's search instead.
                val ccAccess = RbacManager.hasPermission("nav_call_center")
                val workerAccess = RbacManager.hasPermission("nav_space")
                if (ccAccess || workerAccess) {
                    val workerRoute = !ccAccess && workerAccess
                    view.findViewById<TextView>(R.id.btnOverlayAgentMissingSearch).apply {
                        if (workerRoute) text = "🔍 খুঁজুন"
                        isVisible = true
                        setOnClickListener {
                            if (workerRoute) openWorkerSearch(context, rawPhone)
                            else openCallCenterSearch(context, rawPhone, force = true)
                            dismissInternal()
                        }
                    }
                }
                return@launch
            }

            renderOverlayChips(context, view, options, isCc)

            btnSave.setOnClickListener {
                val chosen = overlaySelectedOption
                val noteText = etNote.text?.toString()?.trim().orEmpty()
                // CC and worker alike: option pick OR note text suffices (same as
                // CallCenterFragment's sheet) — never strand the saver.
                if (chosen == null && noteText.isBlank()) {
                    Toast.makeText(context, "একটি রিমার্কস বেছে নিন", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // CC note-only save (no predefined option picked) mirrors
                // CallCenterFragment's sheet, which enables Save on note alone.
                saveFromOverlay(context, view, match, source, isCc, chosen = chosen, noteText = noteText,
                    agentId = agentId, selfSystemId = selfSystemId, rawPhone = rawPhone,
                    todayAssignees = todayAssignees)
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

        // Worker with no catalog: note-only save (same as CC) — otherwise the
        // worker has chips=nothing, note=hidden, save=blocked: fully stuck.
        if (!isCc && options.isEmpty()) etNote.isVisible = true

        if (isCc) etNote.isVisible = true

        // Vertical full-width rows like the CC sheet's option list (label +
        // status tag, selected highlight) — not a horizontal chip row.
        data class OptRow(val root: View, val label: TextView, val tag: TextView?)
        val rowViews = mutableListOf<OptRow>()
        fun refreshStyles() {
            rowViews.forEach { (root, label, tag) ->
                val isSelected = root.tag == overlaySelectedOption
                root.setBackgroundResource(
                    if (isSelected) R.drawable.bg_overlay_cta else R.drawable.bg_overlay_badge_status
                )
                label.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
                tag?.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFF64748B.toInt())
            }
        }
        options.forEach { option ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_overlay_badge_status)
                setPadding(28, 20, 28, 20)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
                tag = option
            }
            val tvLabel = TextView(context).apply {
                text = option.label
                textSize = 12.5f
                setTextColor(0xFF0F172A.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tvLabel)
            val tagText = option.targetStatus.trim().uppercase().ifBlank { null }
            val tvTag: TextView? = if (tagText != null) {
                TextView(context).apply {
                    text = "→$tagText"
                    textSize = 10.5f
                    setTextColor(0xFF64748B.toInt())
                }.also { row.addView(it) }
            } else null
            row.setOnClickListener {
                overlaySelectedOption = if (overlaySelectedOption == option) null else option
                // CC: selecting fills the admin-written instruction into
                // the note box (blank clears) — CallCenterFragment parity.
                if (isCc) etNote.setText(overlaySelectedOption?.instructionText.orEmpty())
                refreshStyles()
            }
            chipContainer.addView(row)
            rowViews.add(OptRow(row, tvLabel, tvTag))
        }
        // Cap the chips scroller so a huge catalog can't push Save off-screen.
        capScrollerHeight(view, R.id.svOverlayRemarkChips)
    }

    /** Save tap → sibling check → inline Yes/No fan-out (or direct save). */
    private fun saveFromOverlay(
        context: Context, view: View, match: CallerMatch, source: String, isCc: Boolean,
        chosen: OverlayRemarkOption?, noteText: String,
        agentId: String, selfSystemId: String, rawPhone: String,
        todayAssignees: Map<String, TodayAssignee> = emptyMap()
    ) {
        remarkLoadJob?.cancel()
        remarkLoadJob = overlayScope.launch {
            val siblings = withContext(Dispatchers.IO) { siblingConsignmentIds(rawPhone, match.consignmentId) }
            if (overlayView !== view) return@launch
            if (siblings.isEmpty()) {
                doOverlaySave(context, view, listOf(match.consignmentId), match, source, isCc,
                    chosen, noteText, agentId, selfSystemId, todayAssignees)
            } else {
                showOverlayFanout(context, view, match, siblings, source, isCc,
                    chosen, noteText, agentId, selfSystemId, todayAssignees)
            }
        }
    }

    private fun showOverlayFanout(
        context: Context, view: View, match: CallerMatch, siblings: List<String>,
        source: String, isCc: Boolean, chosen: OverlayRemarkOption?, noteText: String,
        agentId: String, selfSystemId: String,
        todayAssignees: Map<String, TodayAssignee> = emptyMap()
    ) {
        val llFanout = view.findViewById<View>(R.id.llOverlayFanout)
        val tvFanoutText = view.findViewById<TextView>(R.id.tvOverlayFanoutText)
        val total = siblings.size + 1
        tvFanoutText.text = "\"${match.consignmentId}\"-এর মতো একই নম্বরের মোট $total টি parcel আছে।\nসবগুলোতে একই remark দিতে চান?"
        llFanout.isVisible = true
        view.findViewById<View>(R.id.btnOverlayFanoutYes).setOnClickListener {
            llFanout.isVisible = false
            doOverlaySave(context, view, listOf(match.consignmentId) + siblings, match, source, isCc,
                chosen, noteText, agentId, selfSystemId, todayAssignees)
        }
        view.findViewById<View>(R.id.btnOverlayFanoutNo).setOnClickListener {
            llFanout.isVisible = false
            doOverlaySave(context, view, listOf(match.consignmentId), match, source, isCc,
                chosen, noteText, agentId, selfSystemId, todayAssignees)
        }
    }

    private fun doOverlaySave(
        context: Context, view: View, consignmentIds: List<String>, match: CallerMatch,
        source: String, isCc: Boolean, chosen: OverlayRemarkOption?, noteText: String,
        primaryAgentId: String, selfSystemId: String,
        todayAssignees: Map<String, TodayAssignee> = emptyMap()
    ) {
        val llRemarkSection = view.findViewById<View>(R.id.llOverlayRemarkSection)
        val tvConfirmation = view.findViewById<TextView>(R.id.tvOverlayConfirmation)
        val btnSave = view.findViewById<TextView>(R.id.btnOverlayRemarkSave)
        val btnCancel = view.findViewById<View>(R.id.btnOverlayRemarkCancel)
        val branchId = RbacManager.current.branchIds.firstOrNull().orEmpty()
        if (branchId.isBlank()) {
            Toast.makeText(context, "Branch তথ্য পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            return
        }
        // Saving state until the server answers: disabled buttons + spinner text,
        // so a slow network never looks like a dead tap (and never double-saves).
        btnSave.isEnabled = false
        btnCancel.isEnabled = false
        val saveLabel = btnSave.text
        btnSave.text = "⏳ Saving..."
        fun restoreSaveButton() {
            if (overlayView !== view) return
            btnSave.isEnabled = true
            btnCancel.isEnabled = true
            btnSave.text = saveLabel
        }
        remarkLoadJob?.cancel()
        remarkLoadJob = overlayScope.launch {
            var okCount = 0
            var failCount = 0
            consignmentIds.forEach { cid ->
                // Per-parcel route so siblings save under their own branch;
                // fall back to the primary parcel's values when a sibling
                // has no validations row yet. Agent gets one more fallback —
                // today's run assignment — before giving up on the parcel.
                val route = withContext(Dispatchers.IO) { resolveParcelRoute(cid) }
                // resolveParcelRoute reads the latest validation row, which may
                // itself hold a legacy branch NAME — rescue to ID (cached dir).
                val targetBranch = SupabaseBranchReader.canonicalBranchId(
                    route?.branchId?.takeIf { it.isNotBlank() } ?: branchId)
                // users lookup (cached) — NOT the Gmail displayName.
                val validatorName = UserNameResolver.resolveOwnValidatorName()
                val ok = if (isCc) {
                    val targetAgent = route?.agentSystemId?.takeIf { it.isNotBlank() }
                        ?: todayAssignees[cid]?.systemId?.takeIf { it.isNotBlank() }
                        ?: primaryAgentId
                    if (targetAgent.isBlank()) {
                        false
                    } else {
                        SupabaseRemarkValidationWriter.writeAwait(
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
                            feedback = chosen?.category.orEmpty(),
                            validatorName = validatorName,
                            appContext = context.applicationContext
                        )
                    }
                } else {
                    if (selfSystemId.isBlank()) {
                        false
                    } else {
                        SupabaseRemarkValidationWriter.writeAwait(
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
                if (ok) okCount++ else failCount++
            }
            if (overlayView !== view) return@launch
            if (failCount == 0) {
                tvConfirmation.text = if (consignmentIds.size > 1)
                    "✓ ${consignmentIds.size} টি parcel এ remark save হয়েছে"
                else "✓ রিমার্কস সেভ হয়েছে"
                tvConfirmation.setTextColor(0xFF15803D.toInt())
                llRemarkSection.isVisible = false
                view.findViewById<View>(R.id.llOverlayFanout).isVisible = false
                tvConfirmation.isVisible = true
                mainHandler.postDelayed({ dismissInternal() }, 2000)
            } else {
                restoreSaveButton()
                Toast.makeText(
                    context,
                    if (okCount > 0) "⚠ $okCount টি save হয়েছে, $failCount টি হয়নি — আবার চেষ্টা করুন"
                    else "⚠ Save হয়নি — network দেখে আবার চেষ্টা করুন",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun dismissInternal() {
        clearOverlayEngaged()
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

    /** Call Center / Worker engaged ring while this popup is up: workers see
     *  someone is actively working the parcel (same entries the card-expand
     *  flow writes — courier/consignments/{cid}/engaged_at/{uid}). Minimizing
     *  keeps it (still on the call); dismiss or remarks-save clears it.
     *  Refreshes every 2 min so the 5-min staleness window never lapses
     *  mid-call. Only the viewer's own entry is ever touched. */
    private fun markOverlayEngaged(consignmentIds: List<String>) {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
        val uid = user.uid
        if (uid.isBlank()) return
        overlayEngagedUid = uid
        val name = user.displayName?.trim().orEmpty().ifBlank { "CC Agent" }
        val role = if (RbacManager.hasPermission("nav_call_center")) "cc" else "worker"
        consignmentIds.forEach { cid ->
            if (cid.isNotBlank()) overlayEngagedIds.add(cid)
        }
        if (overlayEngagedIds.isEmpty()) return
        // (Re)mark everything tracked — new ids get their first stamp, old
        // ones get a freshness refresh.
        overlayEngagedIds.forEach { EngagedStateManager.markEngaged(it, uid, name, role) }
        // (Re)arm the refresh while anything is tracked.
        engagedRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        val refresh = object : Runnable {
            override fun run() {
                if (overlayEngagedIds.isEmpty() || overlayView == null) return
                overlayEngagedIds.forEach { EngagedStateManager.markEngaged(it, overlayEngagedUid, name, role) }
                mainHandler.postDelayed(this, ENGAGED_REFRESH_MS)
            }
        }
        engagedRefreshRunnable = refresh
        mainHandler.postDelayed(refresh, ENGAGED_REFRESH_MS)
    }

    private fun clearOverlayEngaged() {
        engagedRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        engagedRefreshRunnable = null
        val uid = overlayEngagedUid
        overlayEngagedUid = ""
        if (uid.isBlank() || overlayEngagedIds.isEmpty()) {
            overlayEngagedIds.clear()
            return
        }
        overlayEngagedIds.forEach { EngagedStateManager.clearEngaged(it, uid) }
        overlayEngagedIds.clear()
    }

    /** Same MainActivity deep-link MainActivity.handleNotificationIntent() already handles
     *  for tapping a status-bar notification -- reused as-is so this doesn't need its own
     *  navigation path. Defaults to the call-center scope (handleNotificationIntent's own
     *  default for anything other than "worker"), since an incoming customer call is a
     *  call-center scenario. */
    /** Opens the app straight into CallCenterFragment with [rawPhone] pre-filled in the
     *  search box -- for calls with no matched parcel (or when the agent wants to search
     *  manually instead of jumping to the auto-matched one). [force] bypasses the
     *  lookup toggle (agent-missing finder) — permission still checked inside. */
    private fun openCallCenterSearch(context: Context, rawPhone: String, force: Boolean = false) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AppNotificationManager.EXTRA_SEARCH_PHONE, rawPhone)
            if (force) putExtra(AppNotificationManager.EXTRA_FORCE_CC_SEARCH, true)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    /** Worker mirror of openCallCenterSearch: same pre-filled search handoff
     *  into Worker Space (filter reset to all there). */
    private fun openWorkerSearch(context: Context, rawPhone: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AppNotificationManager.EXTRA_SEARCH_PHONE, rawPhone)
            putExtra(AppNotificationManager.EXTRA_SEARCH_SCOPE, "worker")
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

    /** Caps a popup scroller (remarks chips, history) so a huge list can't push
     *  the window — and its Save button — off-screen. */
    private fun capScrollerHeight(view: View, scrollerId: Int, maxDp: Int = 230) {
        val scroller = view.findViewById<View>(scrollerId) ?: return
        scroller.post {
            val maxPx = dpToPx(view.context, maxDp)
            if (scroller.height > maxPx) {
                scroller.layoutParams = scroller.layoutParams.apply { height = maxPx }
            }
        }
    }
}
