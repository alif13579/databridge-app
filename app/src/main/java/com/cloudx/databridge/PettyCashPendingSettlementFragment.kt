package com.cloudx.databridge

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Petty Cash Management — Requests / Settlement List (mockup screen 3).
 *
 * Wired to PettyCashViewModel. Originally scoped to only PC_STATUS_APPROVED
 * (POC-approved, waiting for Accounts) with a High/Normal priority filter —
 * changed per feedback: this screen now shows requests of EVERY status, with
 * tabs generated dynamically from whatever statuses actually exist in the
 * branch's requests (so a Requester's freshly-submitted PENDING_TEAM_ALIGN
 * request is visible here too, not just PC_STATUS_APPROVED ones). "All"
 * always shows everything; each other tab is one status, labeled with a
 * human-readable name and a live count.
 *
 * myRequestsOnly (see newInstance): when true, this is a Requester's own
 * "My Requests" list rather than the branch-wide approver view --
 * requests are pre-filtered to requesterUid == current user before tabs/counts
 * are built (so tab counts reflect only their own requests), the
 * approver-only access gate is skipped, the title reads "My Requests", and
 * Settlement History is hidden (that screen is branch-wide/unscoped, same
 * reason it's not linked from PettyCashMyRequestsFragment's Reports item —
 * would let a Requester browse everyone else's settlement history).
 */
class PettyCashPendingSettlementFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private lateinit var layoutTabs: LinearLayout
    private lateinit var layoutList: LinearLayout
    private lateinit var pbLoading: View
    private lateinit var layoutError: View

    private var branchId: String = ""
    private var selectedStatus: String = FILTER_ALL // FILTER_ALL or one of the PC_STATUS_* constants
    private var selectedAgentUids: MutableSet<String> = mutableSetOf() // empty = all agents
    private var selectedCategories: MutableSet<String> = mutableSetOf() // empty = all categories
    private var drawerAgentsExpanded: Boolean = true
    private var drawerCategoriesExpanded: Boolean = true
    private var myRequestsOnly: Boolean = false
    private var latestState: PettyCashState.Success? = null
    private var advancedFilter: PettyCashFilterState = PettyCashFilterState()

    // Whether the bulk Select UI is available at all (any approver role,
    // set fresh on every render). Per-claim eligibility additionally depends
    // on the claim's status (see isBulkEligible).
    private var canBulkSelect: Boolean = false

    // Bulk-update selection (approver view only): claim ids picked for one
    // shared status move (see showBulkUpdateDialog).
    private var selectMode: Boolean = false
    private val selectedIds = mutableSetOf<String>()

    /** Forward transitions the signed-in user may bulk-apply from [status].
     *  Mirrors the single-claim gates in PettyCashSettlementDetailsFragment
     *  (canAcknowledge/canApprove/canMarkReady/canSettle/canReject) — send-back
     *  stays single-claim only, so it is intentionally absent here. */
    private fun bulkTargetsForStatus(status: String, roles: PettyCashUserRoles): List<String> = when (status) {
        PC_STATUS_PENDING -> listOfNotNull(
            PC_STATUS_ACKNOWLEDGED.takeIf { roles.isStaff },
            PC_STATUS_REJECTED.takeIf { roles.isStaff })
        PC_STATUS_ACKNOWLEDGED -> listOfNotNull(
            PC_STATUS_APPROVED.takeIf { roles.isCashPoc },
            PC_STATUS_REJECTED.takeIf { roles.isCashPoc })
        PC_STATUS_APPROVED -> listOfNotNull(
            PC_STATUS_SETTLE_IN_PROCESS.takeIf { roles.isAccounts })
        PC_STATUS_SETTLE_IN_PROCESS -> listOfNotNull(
            PC_STATUS_SETTLED.takeIf { roles.isAccounts })
        else -> emptyList() // settled / rejected / cancelled are terminal
    }

    private fun isBulkEligible(item: PettyCashRequest): Boolean {
        if (myRequestsOnly) return false
        val roles = latestState?.roles ?: return false
        return bulkTargetsForStatus(item.status, roles).isNotEmpty()
    }

    private fun bulkDefaultAmount(item: PettyCashRequest): Double =
        item.approvedAmount.takeIf { it > 0 } ?: item.amount

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val ARG_INITIAL_STATUS = "initial_status"
        private const val ARG_MY_REQUESTS_ONLY = "my_requests_only"
        private const val FILTER_ALL = "all"

        fun newInstance(branchId: String, initialStatus: String = FILTER_ALL, myRequestsOnly: Boolean = false): PettyCashPendingSettlementFragment {
            val f = PettyCashPendingSettlementFragment()
            f.arguments = Bundle().apply {
                putString(ARG_BRANCH_ID, branchId)
                putString(ARG_INITIAL_STATUS, initialStatus)
                putBoolean(ARG_MY_REQUESTS_ONLY, myRequestsOnly)
            }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_pending_settlement, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()
        selectedStatus = arguments?.getString(ARG_INITIAL_STATUS).orEmpty().ifBlank { FILTER_ALL }
        myRequestsOnly = arguments?.getBoolean(ARG_MY_REQUESTS_ONLY) ?: false

        layoutTabs   = view.findViewById(R.id.layoutPcPendingTabs)
        layoutList   = view.findViewById(R.id.layoutPcPendingList)
        pbLoading    = view.findViewById(R.id.pbPcPendingLoading)
        layoutError  = view.findViewById(R.id.layoutPcPendingError)

        if (myRequestsOnly) {
            view.findViewById<TextView>(R.id.tvPcPendingTitle).text = "My Requests"
        }

        view.findViewById<View>(R.id.btnPcPendingBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btnPcPendingFilter).setOnClickListener { openDrawer() }
        view.findViewById<View>(R.id.btnPcPendingExportToolbar).setOnClickListener { exportChooser() }
        view.findViewById<View>(R.id.btnPcPendingDrawerApply).setOnClickListener { closeDrawer() }
        view.findViewById<View>(R.id.tvPcPendingDrawerReset).setOnClickListener {
            advancedFilter = PettyCashFilterState()
            selectedAgentUids.clear()
            selectedCategories.clear()
            selectedStatus = FILTER_ALL
            applyDrawerChange()
        }
        view.findViewById<View>(R.id.btnPcPendingDrawerRange).setOnClickListener {
            closeDrawer()
            showDateRangePicker()
        }
        view.findViewById<View>(R.id.btnPcPendingDrawerBill).setOnClickListener { applyPresetRange(billCycleRange(0)) }
        view.findViewById<View>(R.id.btnPcPendingDrawerMonth).setOnClickListener { applyPresetRange(monthRange(0)) }
        view.findViewById<View>(R.id.btnPcPendingDrawerClearDate).setOnClickListener {
            advancedFilter = advancedFilter.copy(dateFromMillis = 0L, dateToMillis = 0L)
            selectedStatus = FILTER_ALL
            applyDrawerChange()
        }
        view.findViewById<View>(R.id.btnPcPendingDrawerExport).setOnClickListener {
            closeDrawer()
            exportChooser()
        }
        view.findViewById<View>(R.id.tvPcPendingExport).setOnClickListener { exportChooser() }
        view.findViewById<View>(R.id.tvPcPendingRange).setOnClickListener { showDateRangeOptions() }
        view.findViewById<View>(R.id.tvPcPendingRangeClear).setOnClickListener { clearDateRange() }
        view.findViewById<View>(R.id.tvPcPendingSelectMode).setOnClickListener {
            selectMode = !selectMode
            if (!selectMode) selectedIds.clear()
            // "Cancel": only exits select mode (dropping picks), never
            // applies anything. The bottom Update bar is the applier.
            view.findViewById<TextView>(R.id.tvPcPendingSelectMode).text = if (selectMode) "Cancel" else "☑ Select"
            if (selectMode) guideIfNothingEligible()
            renderList()
        }
        view.findViewById<View>(R.id.tvPcPendingSelectAll).setOnClickListener { toggleSelectAllFiltered() }
        view.findViewById<View>(R.id.btnPcBulkCancel).setOnClickListener {
            selectMode = false
            selectedIds.clear()
            view.findViewById<TextView>(R.id.tvPcPendingSelectMode).text = "☑ Select"
            renderList()
        }
        view.findViewById<View>(R.id.btnPcBulkUpdate).setOnClickListener { showBulkUpdateDialog() }

        viewModel.state.observe(viewLifecycleOwner) { state -> render(state) }
        if (branchId.isBlank()) {
            render(PettyCashState.Error("No branch selected"))
        } else {
            viewModel.load(branchId)
        }
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        return "\u09F3${NumberFormat.getNumberInstance(Locale.US).format(whole)}"
    }

    private fun statusLabel(status: String): String = when (status) {
        PC_STATUS_PENDING -> "Pending"
        PC_STATUS_ACKNOWLEDGED -> "Verified"
        PC_STATUS_APPROVED -> "Approved"
        PC_STATUS_SETTLE_IN_PROCESS -> "Settle in Process"
        PC_STATUS_SETTLED -> "Settled"
        PC_STATUS_REJECTED -> "Rejected"
        else -> status
    }

    private fun render(state: PettyCashState) {
        when (state) {
            is PettyCashState.Loading -> {
                pbLoading.isVisible = true
                layoutError.isVisible = false
            }
            is PettyCashState.Error -> {
                pbLoading.isVisible = false
                layoutError.isVisible = true
                view?.findViewById<TextView>(R.id.tvPcPendingError)?.text = state.message
                view?.findViewById<View>(R.id.btnPcPendingRetry)?.setOnClickListener {
                    if (branchId.isNotBlank()) viewModel.load(branchId)
                }
            }
            is PettyCashState.Success -> {
                if (!myRequestsOnly && !state.roles.isAnyApprover) {
                    pbLoading.isVisible = false
                    layoutError.isVisible = true
                    view?.findViewById<TextView>(R.id.tvPcPendingError)?.text = "Only approvers can view this screen"
                    view?.findViewById<View>(R.id.btnPcPendingRetry)?.isVisible = false
                    return
                }
                pbLoading.isVisible = false
                layoutError.isVisible = false
                latestState = state
                // Bulk update is available to every approver role — Staff moves
                // pending→verified, POC moves verified→approved, Accounts moves
                // approved→settle_in_process→settled. Same gate as the inline
                // action buttons below (canSettle is Accounts-only there).
                val canBulk = !myRequestsOnly && state.roles.isAnyApprover
                canBulkSelect = canBulk
                view?.findViewById<View>(R.id.tvPcPendingSelectMode)?.isVisible = canBulk
                if (!canBulk) {
                    selectMode = false
                    selectedIds.clear()
                } else {
                    // Drop picks that are no longer present/eligible after reload.
                    val eligibleIds = state.requests
                        .filter { isBulkEligible(it) }.map { it.id }.toSet()
                    selectedIds.retainAll(eligibleIds)
                }
                buildTabs()
                renderList()
            }
        }
    }

    /** state.requests, or just this user's own when myRequestsOnly is set. */
    private fun scopedRequests(): List<PettyCashRequest> {
        val all = latestState?.requests.orEmpty()
        if (!myRequestsOnly) return all
        val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        return all.filter { it.requesterUid == myUid }
    }

    private fun buildTabs() {
        layoutTabs.removeAllViews()
        // Dynamic over the DATE-RANGED set: tabs/counts show only statuses
        // present in the selected range (all time when no range).
        val all = dateScoped()

        // If the selected tab has no claims in this range, fall back to All
        // instead of stranding the list on "No requests found".
        if (selectedStatus != FILTER_ALL && all.none { it.status == selectedStatus }) {
            selectedStatus = FILTER_ALL
        }

        // Dynamic tabs: one per unique status actually present, in a fixed
        // canonical order (rather than whatever order they happen to appear
        // in the data) so tabs don't reshuffle as requests move through the
        // approval chain.
        val canonicalOrder = listOf(PC_STATUS_PENDING, PC_STATUS_ACKNOWLEDGED, PC_STATUS_APPROVED, PC_STATUS_SETTLE_IN_PROCESS, PC_STATUS_SETTLED, PC_STATUS_REJECTED)
        val presentStatuses = canonicalOrder.filter { status -> all.any { it.status == status } }

        val tabs = mutableListOf(Pair(FILTER_ALL, "All (${all.size})"))
        presentStatuses.forEach { status ->
            val count = all.count { it.status == status }
            tabs.add(Pair(status, "${statusLabel(status)} ($count)"))
        }

        tabs.forEach { (key, label) ->
            val tab = layoutInflater.inflate(R.layout.item_petty_cash_filter_tab, layoutTabs, false) as TextView
            tab.text = label
            tab.setOnClickListener {
                selectedStatus = key
                // Agent + category counts are status-wise: drop picks that have
                // zero claims under the newly selected status tab.
                val uidsInStatus = statusFiltered()
                    .map { it.requesterUid.ifBlank { "unknown" } }.toSet()
                selectedAgentUids.retainAll(uidsInStatus)
                val catsInStatus = statusFiltered()
                    .map { it.category.ifBlank { "Uncategorized" } }.toSet()
                selectedCategories.retainAll(catsInStatus)
                buildTabs()
                selectedIds.retainAll(currentFiltered().filter { isBulkEligible(it) }.map { it.id }.toSet())
                renderList()
            }
            styleTab(tab, key == selectedStatus)
            layoutTabs.addView(tab)
        }
    }

    private fun styleTab(tab: TextView, active: Boolean) {
        if (active) {
            tab.setTextColor(Color.parseColor("#059669"))
            tab.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_pc_tab_active)
        } else {
            tab.setTextColor(Color.parseColor("#64748B"))
            tab.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_pc_tab_inactive)
        }
    }

    private fun formatDateTime(millis: Long): String {
        if (millis == 0L) return "—"
        return BdTime.format("dd MMM, hh:mm a", millis)
    }

    /** Status-appropriate amount for totals: the stage figure, not requested. */
    private fun stageAmount(item: PettyCashRequest): Double = when (item.status) {
        PC_STATUS_SETTLED -> item.settledAmount.takeIf { it > 0 } ?: item.amount
        PC_STATUS_APPROVED, PC_STATUS_SETTLE_IN_PROCESS -> item.approvedAmount.takeIf { it > 0 } ?: item.amount
        else -> item.amount
    }

    /** Date-ranged working set: the calendar range applied, everything
     *  else (tabs/agents) filters down from here. No range = all time. */
    private fun dateScoped(): List<PettyCashRequest> {
        val all = scopedRequests()
        if (advancedFilter.dateFromMillis == 0L && advancedFilter.dateToMillis == 0L) return all
        return all.filter { advancedFilter.matchesDate(it) }
    }

    /** Status tab (+ advanced search) applied, agent filter NOT applied —
     *  the working set agent counts and the agent picker are built from. */
    private fun statusFiltered(): List<PettyCashRequest> {
        val all = dateScoped()
        val byStatus = if (selectedStatus == FILTER_ALL) all
            else all.filter { it.status == selectedStatus }
        return if (advancedFilter.isActive) byStatus.filter { advancedFilter.matches(it) } else byStatus
    }

    /** Agent options under the current status tab: (uid, display name,
     *  status-wise count), sorted by name. E.g. the Verified tab shows
     *  "Shahin (3)" when Shahin has 3 verified claims — even if he has 5
     *  lifetime claims in total. */
    private fun agentOptions(): List<Triple<String, String, Int>> =
        statusFiltered().groupBy { it.requesterUid.ifBlank { "unknown" } }
            .map { (uid, items) ->
                val name = items.firstOrNull()?.requesterName?.takeIf { it.isNotBlank() } ?: uid
                Triple(uid, name, items.size)
            }.sortedBy { it.second.lowercase() }

    /** Status ∩ agents ∩ categories — the working set for list, summary and select-all. */
    private fun currentFiltered(): List<PettyCashRequest> {
        var list = statusFiltered()
        if (selectedAgentUids.isNotEmpty()) {
            list = list.filter { it.requesterUid.ifBlank { "unknown" } in selectedAgentUids }
        }
        if (selectedCategories.isNotEmpty()) {
            list = list.filter { it.category in selectedCategories }
        }
        return list
    }

    /** Date filter entry — the toolbar calendar picks a date RANGE only
     *  (no statuses/categories here — those live on the tabs/agent/category
     *  chips below, computed from the ranged set). Apply scopes the list to
     *  that range's claims; Clear returns to all time. */
    private fun showDateRangeOptions() {
        val hasRange = advancedFilter.dateFromMillis != 0L || advancedFilter.dateToMillis != 0L
        val options = if (hasRange) arrayOf("Select Date Range", "✕ Clear Range (All Time)")
        else arrayOf("Select Date Range")
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Filter by date")
            .setItems(options) { _, which ->
                if (which == 1) clearDateRange()
                else showDateRangePicker()
            }
            .show()
    }

    /** One-tap undo for the date range (the ✕ in the toolbar). */
    private fun clearDateRange() {
        advancedFilter = PettyCashFilterState()
        selectedStatus = FILTER_ALL
        selectedAgentUids.clear()
        selectedCategories.clear()
        buildTabs()
        renderList()
        refreshDrawer()
    }

    private fun showDateRangePicker() {
        val ctx = requireContext()
        var fromDay = java.util.Calendar.getInstance().apply {
            if (advancedFilter.dateFromMillis != 0L) timeInMillis = advancedFilter.dateFromMillis
        }
        var toDay = java.util.Calendar.getInstance().apply {
            if (advancedFilter.dateToMillis != 0L) timeInMillis = advancedFilter.dateToMillis
        }
        // Default From = today, To = today (fresh open with no active range).
        if (advancedFilter.dateFromMillis == 0L) startOfDayLocal(fromDay)
        if (advancedFilter.dateToMillis == 0L) endOfDayLocal(toDay)

        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        fun dayRow(label: String, initial: String): android.widget.LinearLayout {
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(TextView(ctx).apply {
                text = label
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor("#334155"))
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(52), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(ctx).apply {
                tag = label
                text = initial
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#0F172A"))
                setBackgroundResource(R.drawable.bg_pc_tab_inactive)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            return row
        }
        val fromRow = dayRow("From", shortDate(fromDay.timeInMillis))
        val toRow = dayRow("To", shortDate(toDay.timeInMillis))
        root.addView(fromRow)
        root.addView(toRow)
        val fromValue = fromRow.findViewWithTag<TextView>("From")
        val toValue = toRow.findViewWithTag<TextView>("To")

        // Quick presets: normal month + business-month bill cycles (26th–25th).
        val presetRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        fun presetButton(label: String): TextView {
            return TextView(ctx).apply {
                text = label
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor("#0F766E"))
                setBackgroundResource(R.drawable.bg_pc_tab_inactive)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (label != "This Month") leftMargin = dp(6)
                }
            }
        }
        val btnMonth = presetButton("This Month")
        val btnBill = presetButton("Bill Cycle")
        val btnLastBill = presetButton("Last Bill")
        presetRow.addView(btnMonth)
        presetRow.addView(btnBill)
        presetRow.addView(btnLastBill)
        root.addView(presetRow, 0)

        fun applyPreset(range: Pair<java.util.Calendar, java.util.Calendar>) {
            fromDay = range.first
            toDay = range.second
            fromValue.text = shortDate(fromDay.timeInMillis)
            toValue.text = shortDate(toDay.timeInMillis)
        }
        btnMonth.setOnClickListener { applyPreset(monthRange(0)) }
        btnBill.setOnClickListener { applyPreset(billCycleRange(0)) }
        btnLastBill.setOnClickListener { applyPreset(billCycleRange(-1)) }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Select date range")
            .setView(root)
            .setPositiveButton("Apply", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear", null)
            .create()

        fun pickDay(isFrom: Boolean) {
            val base = if (isFrom) fromDay else toDay
            android.app.DatePickerDialog(ctx, { _, y, m, d ->
                val picked = java.util.Calendar.getInstance().apply { set(y, m, d) }
                if (isFrom) {
                    fromDay = picked
                    startOfDayLocal(fromDay)
                    fromValue.text = shortDate(fromDay.timeInMillis)
                } else {
                    toDay = picked
                    endOfDayLocal(toDay)
                    toValue.text = shortDate(toDay.timeInMillis)
                }
            }, base.get(java.util.Calendar.YEAR), base.get(java.util.Calendar.MONTH),
                base.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }
        fromValue.setOnClickListener { pickDay(true) }
        toValue.setOnClickListener { pickDay(false) }

        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (toDay.timeInMillis < fromDay.timeInMillis) {
                Toast.makeText(ctx, "End date cannot be before start date", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Date-only filter: stale status/category/agent picks are dropped,
            // otherwise they AND with the range into "No requests".
            advancedFilter = PettyCashFilterState(
                dateFromMillis = fromDay.timeInMillis, dateToMillis = toDay.timeInMillis)
            selectedStatus = FILTER_ALL
            selectedAgentUids.clear()
            selectedCategories.clear()
            buildTabs()
            renderList()
            refreshDrawer()
            Toast.makeText(ctx,
                "📅 ${shortDate(fromDay.timeInMillis)}–${shortDate(toDay.timeInMillis)}",
                Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            dialog.dismiss()
            clearDateRange()
        }
    }

    /** Normal calendar month: 1st → today. */
    private fun monthRange(unused: Int): Pair<java.util.Calendar, java.util.Calendar> {
        val from = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            startOfDayLocal(this)
        }
        val to = java.util.Calendar.getInstance().apply { endOfDayLocal(this) }
        return from to to
    }

    /** Business-month bill cycle (26th–25th). offset 0 = running cycle
     *  containing today, -1 = previous cycle. */
    private fun billCycleRange(offset: Int): Pair<java.util.Calendar, java.util.Calendar> {
        val today = java.util.Calendar.getInstance()
        val thisCycleStartThisMonth = today.get(java.util.Calendar.DAY_OF_MONTH) >= 26
        val from = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_MONTH, 26)
            if (!thisCycleStartThisMonth) add(java.util.Calendar.MONTH, -1)
            add(java.util.Calendar.MONTH, offset)
            startOfDayLocal(this)
        }
        val to = java.util.Calendar.getInstance().apply {
            timeInMillis = from.timeInMillis
            add(java.util.Calendar.MONTH, 1)
            set(java.util.Calendar.DAY_OF_MONTH, 25)
            endOfDayLocal(this)
        }
        return from to to
    }

    private fun startOfDayLocal(cal: java.util.Calendar) {        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
    }

    private fun endOfDayLocal(cal: java.util.Calendar) {
        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        cal.set(java.util.Calendar.MILLISECOND, 999)
    }

    private fun shortDate(millis: Long): String =
        BdTime.format("dd MMM", millis)

    /** Toolbar range readout + the one-tap ✕ undo.
     *  Always visible so the active scope is never a mystery. */
    private fun updateRangeLabel() {
        val root = view ?: return
        val hasRange = advancedFilter.dateFromMillis != 0L || advancedFilter.dateToMillis != 0L
        root.findViewById<TextView>(R.id.tvPcPendingRange).text = if (hasRange) {
            val from = if (advancedFilter.dateFromMillis != 0L) shortDate(advancedFilter.dateFromMillis) else "…"
            val to = if (advancedFilter.dateToMillis != 0L) shortDate(advancedFilter.dateToMillis) else "…"
            "$from–$to"
        } else "All Time"
        root.findViewById<View>(R.id.tvPcPendingRangeClear).isVisible = hasRange
    }

    // ── Right filter drawer: Date, Agents, Categories — all live. ──────────

    private fun drawer(): androidx.drawerlayout.widget.DrawerLayout? =
        view?.findViewById(R.id.drawerPcPending)

    private fun openDrawer() {
        refreshDrawer()
        drawer()?.openDrawer(androidx.core.view.GravityCompat.END)
    }

    private fun closeDrawer() {
        drawer()?.closeDrawer(androidx.core.view.GravityCompat.END)
    }

    private fun applyDrawerChange() {
        buildTabs()
        selectedIds.retainAll(currentFiltered().filter { isBulkEligible(it) }.map { it.id }.toSet())
        renderList()
        refreshDrawer()
    }

    private fun applyPresetRange(range: Pair<java.util.Calendar, java.util.Calendar>) {
        startOfDayLocal(range.first)
        endOfDayLocal(range.second)
        advancedFilter = PettyCashFilterState(
            dateFromMillis = range.first.timeInMillis, dateToMillis = range.second.timeInMillis)
        selectedStatus = FILTER_ALL
        applyDrawerChange()
    }

    private fun dateStateText(): String = when {
        advancedFilter.dateFromMillis != 0L && advancedFilter.dateToMillis != 0L ->
            "${shortDate(advancedFilter.dateFromMillis)} – ${shortDate(advancedFilter.dateToMillis)}"
        advancedFilter.dateFromMillis != 0L -> "From ${shortDate(advancedFilter.dateFromMillis)}"
        advancedFilter.dateToMillis != 0L -> "Until ${shortDate(advancedFilter.dateToMillis)}"
        else -> "Any date"
    }

    private fun drawerCheckRow(label: String, checked: Boolean, onTap: () -> Unit): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val cb = android.widget.CheckBox(ctx).apply { isChecked = checked }
        val tv = TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(cb)
        row.addView(tv)
        val toggle = {
            onTap()
            Unit
        }
        row.setOnClickListener { toggle() }
        cb.setOnClickListener { toggle() }
        return row
    }

    private fun refreshDrawer() {
        val root = view ?: return
        val ctx = requireContext()
        root.findViewById<TextView>(R.id.tvPcPendingDrawerDateState)?.text = dateStateText()

        val agentBox = root.findViewById<LinearLayout>(R.id.layoutPcPendingDrawerAgents) ?: return
        agentBox.removeAllViews()
        agentBox.isVisible = drawerAgentsExpanded
        val options = agentOptions()
        root.findViewById<TextView>(R.id.tvPcPendingDrawerAgentsHeader)?.apply {
            text = if (drawerAgentsExpanded) "Agents ▾" else "Agents ▸"
            setOnClickListener {
                drawerAgentsExpanded = !drawerAgentsExpanded
                refreshDrawer()
            }
        }
        if (options.isEmpty()) {
            agentBox.addView(TextView(ctx).apply {
                text = "No agents in current filter"
                textSize = 13f
                setTextColor(Color.parseColor("#94A3B8"))
            })
        }
        options.forEach { opt ->
            agentBox.addView(drawerCheckRow("${opt.second} (${opt.third})", opt.first in selectedAgentUids) {
                if (myRequestsOnly) return@drawerCheckRow
                if (opt.first in selectedAgentUids) selectedAgentUids.remove(opt.first)
                else selectedAgentUids.add(opt.first)
                currentPageReset()
                renderList()
                refreshDrawer()
            })
        }

        val catBox = root.findViewById<LinearLayout>(R.id.layoutPcPendingDrawerCategories) ?: return
        catBox.removeAllViews()
        catBox.isVisible = drawerCategoriesExpanded
        val cats = categoryOptions()
        root.findViewById<TextView>(R.id.tvPcPendingDrawerCategoriesHeader)?.apply {
            text = if (drawerCategoriesExpanded) "Categories ▾" else "Categories ▸"
            setOnClickListener {
                drawerCategoriesExpanded = !drawerCategoriesExpanded
                refreshDrawer()
            }
        }
        if (cats.isEmpty()) {
            catBox.addView(TextView(ctx).apply {
                text = "No categories in current filter"
                textSize = 13f
                setTextColor(Color.parseColor("#94A3B8"))
            })
        }
        cats.forEach { (cat, count) ->
            catBox.addView(drawerCheckRow("$cat ($count)", cat in selectedCategories) {
                if (cat in selectedCategories) selectedCategories.remove(cat)
                else selectedCategories.add(cat)
                currentPageReset()
                renderList()
                refreshDrawer()
            })
        }

        root.findViewById<android.widget.Button>(R.id.btnPcPendingDrawerApply)?.text =
            "Show ${currentFiltered().size} results"
    }

    private fun currentPageReset() {
        selectedIds.retainAll(currentFiltered().filter { isBulkEligible(it) }.map { it.id }.toSet())
    }

    private fun updateSummary(filtered: List<PettyCashRequest>) {
        val root = view ?: return
        val total = filtered.sumOf { stageAmount(it) }
        root.findViewById<TextView>(R.id.tvPcPendingSummary).text =
            if (filtered.isEmpty()) "No requests" else "${filtered.size} requests · Total ${pettyCashTaka(total)}"
    }

    /** Category options under the current status tab: (category, count),
     *  sorted by name. Dynamic over the date-ranged set like agents. */
    private fun categoryOptions(): List<Pair<String, Int>> =
        statusFiltered().groupBy { it.category.ifBlank { "Uncategorized" } }
            .map { (category, items) -> category to items.size }
            .sortedBy { it.first.lowercase() }

    /** Explains the silent dead-end: select mode is on but nothing in this
     *  filter can move forward under the signed-in user's role. */
    private fun guideIfNothingEligible() {
        if (!isAdded) return
        val filtered = currentFiltered()
        if (filtered.isEmpty()) {
            Toast.makeText(requireContext(), "No requests in this filter", Toast.LENGTH_SHORT).show()
            return
        }
        if (filtered.any { isBulkEligible(it) }) return
        val hint = when (selectedStatus) {
            PC_STATUS_PENDING -> "Pending → only Staff can Verify"
            PC_STATUS_ACKNOWLEDGED -> "Verified → only Cash POC can Approve"
            PC_STATUS_APPROVED -> "Approved → only Accounts can move to Settle"
            PC_STATUS_SETTLE_IN_PROCESS -> "Settle in Process → only Accounts can Settle"
            FILTER_ALL -> "Your role can't move these forward — try a tab matching your stage"
            else -> "Terminal status — nothing to move"
        }
        Toast.makeText(requireContext(), hint, Toast.LENGTH_LONG).show()
    }

    /** Toggles between picking every bulk-eligible request in the current
     *  status+agents filter and clearing the pick (label flips accordingly). */
    private fun toggleSelectAllFiltered() {        val eligible = currentFiltered().filter { isBulkEligible(it) }
        if (eligible.isEmpty()) {
            Toast.makeText(requireContext(), "No bulk-eligible requests in this filter", Toast.LENGTH_SHORT).show()
            return
        }
        if (eligible.all { it.id in selectedIds }) {
            selectedIds.removeAll(eligible.map { it.id }.toSet())
        } else {
            selectedIds.addAll(eligible.map { it.id })
        }
        renderList()
    }    /** Expense date for display — requestedDate, falling back to submission time. */
    private fun expenseDate(item: PettyCashRequest): Long =
        if (item.requestedDate != 0L) item.requestedDate else item.createdAt

    private fun formatDate(millis: Long): String {
        if (millis == 0L) return "—"
        return BdTime.format("dd MMM yyyy", millis)
    }

    /** Status-appropriate secondary line — what to show instead of a hardcoded "POC Approved:" for every card.
     *  Leads with the expense date (submit can come later); submitted time kept alongside. */
    private fun statusInfoLine(item: PettyCashRequest): Pair<String, String> = when (item.status) {
        PC_STATUS_PENDING -> "Expense: ${formatDate(expenseDate(item))} · Submitted: ${formatDateTime(item.createdAt)}" to "By: ${item.requesterName}"
        PC_STATUS_ACKNOWLEDGED -> "Authorised: ${formatDateTime(item.verifiedAt)}" to "By: ${item.verifiedByName.ifBlank { "—" }}"
        PC_STATUS_APPROVED -> "Approved: ${formatDateTime(item.approvedAt)}" to "By: ${item.approvedByName.ifBlank { "—" }}"
        PC_STATUS_SETTLE_IN_PROCESS -> "Settle in Process: ${formatDateTime(item.settleInProcessAt)}" to "By: ${item.settleInProcessByName.ifBlank { "—" }}"
        PC_STATUS_SETTLED -> "Settled: ${formatDateTime(item.settledAt)}" to "By: ${item.settledByName.ifBlank { "—" }}"
        PC_STATUS_REJECTED -> "Rejected: ${formatDateTime(item.rejectedAt)}" to "By: ${item.rejectedByName.ifBlank { "—" }}"
        else -> "Expense: ${formatDate(expenseDate(item))} · Submitted: ${formatDateTime(item.createdAt)}" to "By: ${item.requesterName}"
    }

    private fun renderList() {
        val state = latestState ?: return
        val filtered = currentFiltered()
        val canSettle = state.roles.isAccounts
        // Prune stale category picks (e.g. after reload moved claims elsewhere).
        selectedCategories.retainAll(categoryOptions().map { it.first }.toSet())
        updateRangeLabel()
        updateSummary(filtered)
        val eligibleInFilter = filtered.filter { isBulkEligible(it) }
        view?.findViewById<TextView>(R.id.tvPcPendingSelectAll)?.apply {
            isVisible = selectMode && canBulkSelect
            text = if (eligibleInFilter.isNotEmpty() && eligibleInFilter.all { it.id in selectedIds })
                "Clear all" else "Select all"
        }

        layoutList.removeAllViews()
        if (filtered.isEmpty()) {
            layoutList.addView(TextView(requireContext()).apply {
                text = "No requests found."
                textSize = 13f
                setTextColor(0xFF94A3B8.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(40), dp(8), dp(40))
            })
            return
        }
        filtered.sortedByDescending { it.updatedAt }.forEach { item ->
            val card = layoutInflater.inflate(R.layout.item_petty_cash_settlement_card, layoutList, false)
            card.findViewById<TextView>(R.id.tvPsCardCode).text = item.requestCode
            // Merchant / consignment right under the code — identifies the
            // card at a glance (bulk cid, pickup store, IC route).
            val merchantLabel = item.cidOrMerchant.ifBlank { item.consignmentId.ifBlank { item.storeName } }
            card.findViewById<TextView>(R.id.tvPsCardMerchant).apply {
                text = merchantLabel
                isVisible = merchantLabel.isNotBlank()
            }
            card.findViewById<TextView>(R.id.tvPsCardWorker).text = item.requesterName
            card.findViewById<TextView>(R.id.tvPsCardCategory).text = item.category
            val (psPrimary, psSecondary) = claimCardAmounts(item)
            card.findViewById<TextView>(R.id.tvPsCardAmount).text = psPrimary
            card.findViewById<TextView>(R.id.tvPsCardSecondaryAmount).text = psSecondary

            val (infoLine, byLine) = statusInfoLine(item)
            card.findViewById<TextView>(R.id.tvPsCardApprovedInfo).text = infoLine
            card.findViewById<TextView>(R.id.tvPsCardApprovedBy).text = byLine

            // Bulk-select checkbox (select mode + eligible only). Tapping the
            // card toggles the pick instead of opening details in this mode.
            val checkBox = card.findViewById<android.widget.CheckBox>(R.id.cbPsCardSelect)
            val selectable = selectMode && isBulkEligible(item)
            checkBox.isVisible = selectable
            // Set checked without firing the listener (recycled bind).
            checkBox.setOnCheckedChangeListener(null)
            checkBox.isChecked = item.id in selectedIds
            checkBox.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedIds.add(item.id) else selectedIds.remove(item.id)
                updateBulkBar()
            }

            // The inline button here just navigates to Settlement Details,
            // which shows whatever action actually fits the request's real
            // stage (Acknowledge/Approve/Mark Ready/Settle Now). Label and
            // visibility here are just a preview of what that action will be.
            val btnSettle = card.findViewById<TextView>(R.id.btnPsCardSettle)
            when {
                canSettle && item.status == PC_STATUS_APPROVED -> {
                    btnSettle.isVisible = true
                    btnSettle.text = "Mark Ready"
                }
                canSettle && item.status == PC_STATUS_SETTLE_IN_PROCESS -> {
                    btnSettle.isVisible = true
                    btnSettle.text = "Settle"
                }
                else -> btnSettle.isVisible = false
            }

            val openDetails = View.OnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, PettyCashSettlementDetailsFragment.newInstance(branchId, item.requestCode))
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }
            card.setOnClickListener(
                if (selectable) View.OnClickListener { checkBox.toggle() }
                else openDetails
            )
            // Settle (final step) gets a quick inline confirm instead of opening details --
            // Mark Ready has no extra fields to collect, so it still just opens details
            // (which shows the right stage's action, same as tapping the card itself).
            btnSettle.setOnClickListener(
                if (item.status == PC_STATUS_SETTLE_IN_PROCESS) View.OnClickListener { showQuickSettleDialog(item) }
                else openDetails
            )

            layoutList.addView(card)
        }

        updateBulkBar()
    }

    /** Bottom bulk bar: always visible in select mode (so the Update action
     *  can't be missed) and always tappable — with no picks it explains
     *  itself via the dialog's own "select first" guard instead of looking
     *  like a dead dimmed label next to Cancel. */
    private fun updateBulkBar() {
        val root = view ?: return
        val bar = root.findViewById<View>(R.id.layoutPcBulkBar)
        if (!selectMode) {
            bar.isVisible = false
            return
        }
        bar.isVisible = true
        val picked = latestState?.requests.orEmpty().filter { it.id in selectedIds }
        if (picked.isEmpty()) {
            root.findViewById<TextView>(R.id.tvPcBulkSummary).text = "Tick claims above, then Update"
        } else {
            val total = picked.sumOf { bulkDefaultAmount(it) }
            root.findViewById<TextView>(R.id.tvPcBulkSummary).text =
                "${picked.size} selected · Total ${pettyCashTaka(total)}"
        }
    }

    /** Next-status options for the picked claims, in pipeline order — only
     *  transitions the signed-in user may actually perform (role-gated via
     *  bulkTargetsForStatus). A target appears when at least one picked claim
     *  can move to it; claims that can't take the chosen target are skipped
     *  (counted, not failed) at apply time. */
    private fun bulkTargetOptions(picked: List<PettyCashRequest>): List<String> {
        val roles = latestState?.roles ?: return emptyList()
        val order = listOf(
            PC_STATUS_ACKNOWLEDGED, PC_STATUS_APPROVED, PC_STATUS_SETTLE_IN_PROCESS,
            PC_STATUS_SETTLED, PC_STATUS_REJECTED)
        return order.filter { target ->
            picked.any { it.status != target && target in bulkTargetsForStatus(it.status, roles) }
        }
    }

    private fun bulkTargetLabel(target: String): String = when (target) {
        PC_STATUS_ACKNOWLEDGED -> "Verified"
        PC_STATUS_APPROVED -> "Approved"
        PC_STATUS_SETTLE_IN_PROCESS -> "Settle in Process"
        PC_STATUS_SETTLED -> "Settled"
        PC_STATUS_REJECTED -> "Rejected"
        else -> target
    }

    /** Generic bulk update: one next-status for every picked claim, with the
     *  fields that status needs (shared comment / reject reason / one payment
     *  method + one transaction id for settle). Amounts stay per-claim
     *  (each claim's own stage default) — the dialog never forces one amount
     *  onto claims that were approved for different figures. */
    private fun showBulkUpdateDialog() {
        val picked = latestState?.requests.orEmpty()
            .filter { it.id in selectedIds && isBulkEligible(it) }
        if (picked.isEmpty()) {
            Toast.makeText(requireContext(), "Select at least one request first", Toast.LENGTH_SHORT).show()
            return
        }
        val options = bulkTargetOptions(picked)
        if (options.isEmpty()) {
            Toast.makeText(requireContext(), "No bulk action available for your role on these", Toast.LENGTH_SHORT).show()
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_pc_bulk_update, null)
        val spinnerTarget = dialogView.findViewById<Spinner>(R.id.spinnerBulkUpdateTarget)
        spinnerTarget.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item,
            options.map { bulkTargetLabel(it) })
        val commentGroup = dialogView.findViewById<View>(R.id.layoutBulkUpdateCommentGroup)
        val tvCommentLabel = dialogView.findViewById<TextView>(R.id.tvBulkUpdateCommentLabel)
        val etComment = dialogView.findViewById<EditText>(R.id.etBulkUpdateComment)
        val settleGroup = dialogView.findViewById<View>(R.id.layoutBulkUpdateSettleGroup)
        val spinnerMethod = dialogView.findViewById<Spinner>(R.id.spinnerBulkUpdateMethod)
        spinnerMethod.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, arrayOf("Cash", "Bank", "bKash", "Nagad"))
        val etTrxId = dialogView.findViewById<EditText>(R.id.etBulkUpdateTrxId)

        fun refreshFieldGroups(target: String) {
            settleGroup.isVisible = target == PC_STATUS_SETTLED
            commentGroup.isVisible = target != PC_STATUS_SETTLED && target != PC_STATUS_SETTLE_IN_PROCESS
            tvCommentLabel.text = if (target == PC_STATUS_REJECTED)
                "REJECT REASON (REQUIRED, ONE FOR ALL)" else "COMMENT (OPTIONAL, ONE FOR ALL)"
            etComment.hint = if (target == PC_STATUS_REJECTED)
                "Why are these being rejected?" else "Shared note for every claim"
        }
        refreshFieldGroups(options.first())
        val listContainer = dialogView.findViewById<LinearLayout>(R.id.layoutBulkUpdateClaimList)
        fun claimRow(text: String, bold: Boolean = false, color: String = "#0F172A") =
            TextView(requireContext()).apply {
                this.text = text
                textSize = if (bold) 13f else 12.5f
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(color))
                setPadding(0, 6, 0, 6)
            }
        // Claim list re-renders with the target: a Settle run groups rows by
        // agent with per-agent subtotals (one trxId usually pays ONE agent's
        // total — the warning below says so when the pick spans several),
        // every other target keeps the flat list.
        fun renderClaimList(target: String) {
            listContainer.removeAllViews()
            if (target == PC_STATUS_SETTLED) {
                val byAgent = picked.groupBy {
                    it.requesterName.takeIf { n -> n.isNotBlank() } ?: it.requesterUid.ifBlank { "—" }
                }.toList().sortedBy { it.first.lowercase() }
                if (byAgent.size > 1) {
                    listContainer.addView(claimRow(
                        "⚠ One trxId will cover ${byAgent.size} agents — split by agent if these are separate transfers.",
                        bold = true, color = "#B91C1C"))
                }
                byAgent.forEach { (agent, items) ->
                    val sub = items.sumOf { stageAmount(it) }
                    listContainer.addView(claimRow("$agent — ${items.size} claims — ${pettyCashTaka(sub)}", bold = true))
                    items.forEach { item ->
                        listContainer.addView(claimRow(
                            "  ${item.requestCode} · ${pettyCashStatusLabel(item.status)} — ${pettyCashTaka(stageAmount(item))}"))
                    }
                }
            } else {
                picked.forEach { item ->
                    listContainer.addView(claimRow(
                        "${item.requestCode} · ${item.requesterName} · ${pettyCashStatusLabel(item.status)} — ${pettyCashTaka(stageAmount(item))}"))
                }
            }
        }
        renderClaimList(options.first())
        spinnerTarget.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                refreshFieldGroups(options[pos])
                renderClaimList(options[pos])
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
        }

        val total = picked.sumOf { stageAmount(it) }
        dialogView.findViewById<TextView>(R.id.tvBulkUpdateTotal).text = "Total: ${pettyCashTaka(total)}"
        val tvProgress = dialogView.findViewById<TextView>(R.id.tvBulkUpdateProgress)

        var dialog: AlertDialog? = null
        // Last trxId the reuse warning was accepted for — re-asks if edited.
        var confirmedSettleTrx = ""
        dialog = AlertDialog.Builder(requireContext())
            .setTitle("Bulk Update (${picked.size})")
            .setView(dialogView)
            .setPositiveButton("Update", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog?.show()
        // Slide in from the left.
        dialog?.window?.let { w ->
            val attrs = w.attributes
            attrs.windowAnimations = R.style.PcBulkDialogAnimation
            w.attributes = attrs
        }
        // Override the positive button: validate first, then run without
        // auto-dismissing (progress shows on the dialog itself).
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val d = dialog ?: return@setOnClickListener
            val target = options[spinnerTarget.selectedItemPosition]
            val comment = etComment.text?.toString()?.trim().orEmpty()
            if (target == PC_STATUS_REJECTED && comment.isBlank()) {
                Toast.makeText(requireContext(), "Enter the reject reason", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val trxId = etTrxId.text?.toString()?.trim().orEmpty()
            if (target == PC_STATUS_SETTLED && trxId.isBlank()) {
                Toast.makeText(requireContext(), "Enter the transaction ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Same trxId on two payouts usually means a typo or a double
            // booking — confirm once per typed value (re-asks if edited,
            // skips the already-picked claims themselves).
            if (target == PC_STATUS_SETTLED && confirmedSettleTrx != trxId) {
                val reuse = findTrxIdReuse(latestState?.requests.orEmpty(), trxId, picked.map { it.id }.toSet())
                if (reuse.isNotEmpty()) {
                    val shown = reuse.take(5).joinToString(", ") { it.requestCode }
                    val more = if (reuse.size > 5) " +${reuse.size - 5} more" else ""
                    AlertDialog.Builder(requireContext())
                        .setTitle("⚠ Transaction ID already used")
                        .setMessage("This trxId already settled: $shown$more\n\nSettle ${picked.size} more under the same trxId?")
                        .setPositiveButton("Use anyway") { _, _ ->
                            confirmedSettleTrx = trxId
                            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                        }
                        .setNegativeButton("Back", null)
                        .show()
                    return@setOnClickListener
                }
                confirmedSettleTrx = trxId
            }
            val method = spinnerMethod.selectedItem?.toString() ?: "Cash"
            val roles = latestState?.roles
            if (roles == null) {
                Toast.makeText(requireContext(), "Still loading — try again", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val positive = d.getButton(AlertDialog.BUTTON_POSITIVE)
            val negative = d.getButton(AlertDialog.BUTTON_NEGATIVE)
            positive.isEnabled = false
            negative.isEnabled = false
            tvProgress.isVisible = true
            lifecycleScope.launch {
                var ok = 0
                var skipped = 0
                var neg = 0
                val failures = mutableListOf<String>()
                picked.forEachIndexed { index, item ->
                    tvProgress.text = "⏳ Updating ${index + 1}/${picked.size}…"
                    if (target !in bulkTargetsForStatus(item.status, roles)) {
                        skipped++
                        return@forEachIndexed
                    }
                    val result: Result<*> = when (target) {
                        PC_STATUS_ACKNOWLEDGED ->
                            viewModel.acknowledgeRequest(branchId, item.id, comment)
                        PC_STATUS_APPROVED ->
                            // Preserve Staff's pre-set verified figure when there
                            // is one (null falls back to the requested amount
                            // inside approveRequest) — passing null outright
                            // used to silently discard it.
                            viewModel.approveRequest(branchId, item.id, comment, item.approvedAmount.takeIf { it > 0 })
                        PC_STATUS_SETTLE_IN_PROCESS ->
                            viewModel.markReadyToSettle(branchId, item.id)
                        PC_STATUS_SETTLED ->
                            viewModel.settleRequest(branchId, item.id, method, trxId, null)
                        PC_STATUS_REJECTED ->
                            viewModel.rejectRequest(branchId, item.id, comment)
                        else -> Result.failure<Any>(IllegalStateException("Unsupported target"))
                    }
                    if (result.isSuccess) {
                        ok++
                        if ((result.getOrNull() as? PettyCashViewModel.SettleOutcome)?.negativeWarning == true) neg++
                    } else failures.add("${item.requestCode}: ${result.exceptionOrNull()?.message ?: "failed"}")
                }
                val failed = failures.size
                val label = bulkTargetLabel(target)
                tvProgress.text = if (failed == 0 && skipped == 0) "✓ $ok → $label"
                    else "✓ $ok → $label · ⚠ $failed failed · $skipped skipped"
                if (failed == 0) {
                    val skipNote = if (skipped > 0) " · $skipped skipped (wrong stage)" else ""
                    val negNote = if (neg > 0) " · ⚠ $neg went negative" else ""
                    val trxNote = if (target == PC_STATUS_SETTLED) " ($trxId)" else ""
                    Toast.makeText(requireContext(), "✓ $ok claims → $label$trxNote$skipNote$negNote", Toast.LENGTH_LONG).show()
                    d.dismiss()
                    selectMode = false
                    selectedIds.clear()
                    view?.findViewById<TextView>(R.id.tvPcPendingSelectMode)?.text = "☑ Select"
                    if (branchId.isNotBlank()) viewModel.load(branchId) else renderList()
                } else {
                    val friendly = "⚠ $failed failed — list reloaded, retry the rest"
                    Toast.makeText(requireContext(), friendly, Toast.LENGTH_LONG).show()
                    if (isAdded) {
                        SupabaseErrorDialog.show(requireContext(), friendly, failures.joinToString("\n"))
                    }
                    positive.isEnabled = true
                    negative.isEnabled = true
                    if (branchId.isNotBlank()) viewModel.load(branchId)
                }
            }
        }
    }


    /** Quick inline confirm for the final Settle step, straight from the list card --
     *  skips opening PettyCashSettlementDetailsFragment. Collects the same three fields
     *  that screen's settle form does (Payment Method, Settle Amount, Transaction ID)
     *  since PettyCashViewModel.settleRequest() requires paymentMethod/trxId; a bare
     *  "Yes" without them would have to guess defaults for money-affecting fields. */
    private fun showQuickSettleDialog(item: PettyCashRequest) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pc_quick_settle, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerQsPaymentMethod)
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, arrayOf("Cash", "Bank"))
        val etAmount = dialogView.findViewById<EditText>(R.id.etQsAmount)
        val defaultAmount = item.approvedAmount.takeIf { it > 0 } ?: item.amount
        etAmount.setText(if (defaultAmount == defaultAmount.toLong().toDouble())
            defaultAmount.toLong().toString() else defaultAmount.toString())
        val etTrxId = dialogView.findViewById<EditText>(R.id.etQsTrxId)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Settle Confirm")
            .setMessage("Settle ${item.requestCode}?")
            .setView(dialogView)
            .setPositiveButton("Yes", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
        // Non-auto-dismissing positive: blank-amount refusal and the trxId
        // reuse confirm both need the dialog kept open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val amount = etAmount.text?.toString()?.trim()?.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                Toast.makeText(requireContext(), "Enter a valid settle amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val paymentMethod = spinner.selectedItem?.toString() ?: "Cash"
            val typedTrxId = etTrxId.text?.toString()?.trim().orEmpty()
            val trxId = typedTrxId.ifBlank { "TXN-${System.currentTimeMillis().toString().takeLast(5)}" }
            val reuse = findTrxIdReuse(latestState?.requests.orEmpty(), trxId, setOf(item.id))
            if (reuse.isNotEmpty()) {
                val shown = reuse.take(5).joinToString(", ") { it.requestCode }
                val more = if (reuse.size > 5) " +${reuse.size - 5} more" else ""
                AlertDialog.Builder(requireContext())
                    .setTitle("⚠ Transaction ID already used")
                    .setMessage("This trxId already settled: $shown$more\n\nSettle ${item.requestCode} under the same trxId?")
                    .setPositiveButton("Settle anyway") { _, _ ->
                        dialog.dismiss()
                        runQuickSettle(item, paymentMethod, trxId, amount)
                    }
                    .setNegativeButton("Back", null)
                    .show()
                return@setOnClickListener
            }
            dialog.dismiss()
            runQuickSettle(item, paymentMethod, trxId, amount)
        }
    }

    private fun runQuickSettle(item: PettyCashRequest, paymentMethod: String, trxId: String, amount: Double) {
        lifecycleScope.launch {
            val result = viewModel.settleRequest(branchId, item.id, paymentMethod, trxId, amount,
                onSupabaseResult = { ok ->
                    activity?.runOnUiThread {
                        if (isAdded) Toast.makeText(requireContext(),
                            if (ok) "✓ Supabase saved" else "⚠ Supabase save failed", Toast.LENGTH_SHORT).show()
                    }
                })
            if (isAdded) {
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), "✓ Settled", Toast.LENGTH_SHORT).show()
                } else {
                    val friendly = UserErrorText.forSaveFailure(result.exceptionOrNull())
                    Toast.makeText(requireContext(), friendly, Toast.LENGTH_LONG).show()
                    SupabaseErrorDialog.show(requireContext(), friendly,
                        result.exceptionOrNull()?.message ?: "Settle failed")
                }
            }
        }
    }

    // ── Export (PDF / Excel / CSV of the CURRENT filter) ────────────────────

    private var pendingDownload: (() -> Unit)? = null
    private val storagePermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingDownload?.invoke()
        else Toast.makeText(requireContext(), "Storage permission denied — cannot save to Downloads (Share still works)", Toast.LENGTH_LONG).show()
        pendingDownload = null
    }

    private val exportIsoFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = BdTime.ZONE }

    private fun shiftIsoDays(iso: String, days: Int): String {
        val millis = runCatching { exportIsoFormat.parse(iso)?.time }.getOrNull() ?: return iso
        return exportIsoFormat.format(java.util.Date(millis + days * 86_400_000L))
    }

    private fun exportChooser() {
        val rows = currentFiltered()
        if (rows.isEmpty()) return toast("Nothing to export — the current filter is empty")
        AlertDialog.Builder(requireContext())
            .setTitle("Export Current Filter (${rows.size} rows)")
            .setItems(arrayOf("📄 PDF", "📊 Excel (.xlsx)", "📝 CSV")) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(requireContext())
                        .setTitle("PDF — View, Share or Download?")
                        .setItems(arrayOf("👁 View", "📤 Share", "⬇️ Download to Downloads")) { _, target ->
                            when (target) {
                                0 -> exportPdf(PdfTarget.VIEW)
                                1 -> exportPdf(PdfTarget.SHARE)
                                else -> exportPdf(PdfTarget.DOWNLOAD)
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    1 -> exportTargetChooser("Excel") { share -> exportExcel(share) }
                    else -> exportTargetChooser("CSV") { share -> exportCsv(share) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private enum class PdfTarget { VIEW, SHARE, DOWNLOAD }

    private fun exportTargetChooser(format: String, run: (Boolean) -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle("$format — Share or Download?")
            .setItems(arrayOf("📤 Share", "⬇️ Download to Downloads")) { _, which ->
                run(which == 0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun fetchExportRows(): Triple<List<SupabaseClaimsReader.ClaimRow>, String, String>? {
        val rows = currentFiltered()
        if (rows.isEmpty() || branchId.isBlank()) return null
        val ids = rows.map { it.id }.toSet()
        val iso = exportIsoFormat
        val hasRange = advancedFilter.dateFromMillis != 0L && advancedFilter.dateToMillis != 0L
        val fromIso = if (hasRange) iso.format(java.util.Date(advancedFilter.dateFromMillis)) else "1970-01-01"
        val toIso = if (hasRange) iso.format(java.util.Date(advancedFilter.dateToMillis)) else "2999-12-31"
        val all = SupabaseClaimsReader.fetchClaimsForReport(branchId, shiftIsoDays(fromIso, -1), shiftIsoDays(toIso, 1))
        val matched = all.filter { it.id in ids }
        if (matched.isEmpty()) return null
        val labelFrom = if (hasRange) fromIso else matched.minOf { it.placedDate.ifBlank { fromIso } }
        val labelTo = if (hasRange) toIso else matched.maxOf { it.placedDate.ifBlank { toIso } }
        return Triple(matched, labelFrom, labelTo)
    }

    private fun exportPdf(target: PdfTarget) {
        toast("Generating PDF…")
        lifecycleScope.launch {
            runCatching {
                val (claims, fromIso, toIso) = fetchExportRows()
                    ?: throw IllegalStateException("No matching rows found")
                val branchRegion = claims.first().branchRegion
                val branchName = claims.first().branchName.ifBlank { "Branch" }
                val pettyCashLimit = claims.first().branchPettyCashLimit
                val categoryGroups = runCatching { SupabaseClaimsReader.fetchClaimCategories() }
                    .getOrDefault(emptyList()).associate { it.name to it.group }
                val poc = runCatching { SupabaseClaimsReader.fetchPocForBranch(branchId) }.getOrNull()
                val firstAgent = claims.first()
                val ctx = requireContext()
                val exportsDir = java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }
                val outFile = java.io.File(exportsDir, "pending_settlement_${System.currentTimeMillis()}.pdf")
                PettyCashTopSheetPdfWriter.generate(
                    outFile = outFile,
                    claims = claims,
                    branchName = branchName,
                    branchRegion = branchRegion,
                    pettyCashLimit = pettyCashLimit,
                    pocName = poc?.name?.takeIf { it.isNotBlank() } ?: firstAgent.agentName,
                    pocEmployeeId = poc?.employeeId?.takeIf { it.isNotBlank() } ?: firstAgent.agentEmployeeId,
                    pocDesignation = poc?.designation?.takeIf { it.isNotBlank() } ?: firstAgent.agentDesignation,
                    pocContact = poc?.phone?.takeIf { it.isNotBlank() } ?: firstAgent.agentPhone,
                    fromDateIso = fromIso,
                    toDateIso = toIso,
                    categoryGroups = categoryGroups,
                )
                outFile to claims.size
            }.onSuccess { (file, count) ->
                when (target) {
                    PdfTarget.VIEW -> viewPdf(file)
                    PdfTarget.SHARE -> shareFile(file, "application/pdf", "Share PDF")
                    PdfTarget.DOWNLOAD -> saveToDownloads(file, "pending_settlement_${System.currentTimeMillis()}.pdf", "application/pdf", "✅ PDF saved to Downloads ($count rows)")
                }
            }.onFailure { toast(it.message ?: "PDF export failed") }
        }
    }

    private fun exportExcel(share: Boolean) {
        toast("Generating Excel…")
        lifecycleScope.launch {
            runCatching {
                val (claims, fromIso, toIso) = fetchExportRows()
                    ?: throw IllegalStateException("No matching rows found")
                val ctx = requireContext()
                val exportsDir = java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }
                val file = java.io.File(exportsDir, "pending_settlement_${fromIso}_${toIso}_${System.currentTimeMillis()}.xlsx")
                val headers = listOf("Date", "From", "To", "Vehicle", "Invoice", "Agent ID", "Agent Name", "Type", "Consignment/Merchant", "LOT ID", "Requested", "Approved", "Settled", "Status")
                val data = claims.map { c ->
                    listOf<Any>(
                        sheetDate(c.placedDate), areaDisplay(c.fromArea), areaDisplay(c.toArea), c.vehicle,
                        c.claimCode, c.agentEmployeeId, c.agentName.ifBlank { c.agentSystemId },
                        c.category, c.cidOrMerchant, c.storeId,
                        c.requestedAmount, c.approvedAmount, c.settledAmount, c.status,
                    )
                }
                val widths = listOf(11, 20, 20, 12, 20, 12, 24, 24, 22, 12, 11, 11, 11, 16)
                CashExportWriter.writeXlsx(file, "Pending $fromIso", headers, data, widths)
                file to claims.size
            }.onSuccess { (file, count) ->
                if (share) shareFile(file, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Share Excel")
                else saveToDownloads(file, file.name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "✅ Excel saved to Downloads ($count rows)")
            }.onFailure { toast("Excel export failed: ${it.message}") }
        }
    }

    private fun exportCsv(share: Boolean) {
        toast("Generating CSV…")
        lifecycleScope.launch {
            runCatching {
                val (claims, fromIso, toIso) = fetchExportRows()
                    ?: throw IllegalStateException("No matching rows found")
                val ctx = requireContext()
                val exportsDir = java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }
                val file = java.io.File(exportsDir, "pending_settlement_${fromIso}_${toIso}_${System.currentTimeMillis()}.csv")
                val headers = listOf("Date", "From", "To", "Vehicle", "Invoice", "Agent ID", "Agent Name", "Type", "Consignment/Merchant", "LOT ID", "Requested", "Approved", "Settled", "Status")
                val sb = StringBuilder()
                sb.appendLine(headers.joinToString(",") { csvCell(it) })
                claims.forEach { c ->
                    val cells = listOf(
                        sheetDate(c.placedDate), areaDisplay(c.fromArea), areaDisplay(c.toArea), c.vehicle,
                        c.claimCode, c.agentEmployeeId, c.agentName.ifBlank { c.agentSystemId },
                        c.category, c.cidOrMerchant, c.storeId,
                        c.requestedAmount.toString(), c.approvedAmount.toString(), c.settledAmount.toString(), c.status,
                    )
                    sb.appendLine(cells.joinToString(",") { csvCell(it) })
                }
                file.writeText(sb.toString(), Charsets.UTF_8)
                file to claims.size
            }.onSuccess { (file, count) ->
                if (share) shareFile(file, "text/csv", "Share CSV")
                else saveToDownloads(file, file.name, "text/csv", "✅ CSV saved to Downloads ($count rows)")
            }.onFailure { toast("CSV export failed: ${it.message}") }
        }
    }

    private fun csvCell(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"${value.replace("\"", "\"\"")}\"" else value
    }

    private fun sheetDate(iso: String): String {
        val p = iso.split("-")
        if (p.size != 3) return iso
        val mon = mapOf("01" to "Jan", "02" to "Feb", "03" to "Mar", "04" to "Apr", "05" to "May", "06" to "Jun", "07" to "Jul", "08" to "Aug", "09" to "Sep", "10" to "Oct", "11" to "Nov", "12" to "Dec")[p[1]] ?: return iso
        return "${p[2]}-$mon-${p[0].takeLast(2)}"
    }

    private fun areaDisplay(value: String): String =
        if (value.trim().equals("OFFICE", ignoreCase = true)) "Office" else value

    private fun viewPdf(file: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { toast("No PDF viewer installed — use Download instead") }
    }

    private fun shareFile(file: java.io.File, mime: String, title: String) {
        val ctx = requireContext()
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        }.getOrNull() ?: return toast("Could not create file")
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(android.content.Intent.createChooser(intent, title)) }
            .onFailure { toast("Share failed: ${it.message}") }
    }

    private fun saveToDownloads(file: java.io.File, displayName: String, mimeType: String, doneNote: String? = null) {
        val ctx = requireContext()
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = { saveToDownloads(file, displayName, mimeType, doneNote) }
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        runCatching {
            val resolver = ctx.contentResolver
            val uri: android.net.Uri? =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    }
                    resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                } else {
                    @Suppress("DEPRECATION")
                    val outFile = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                        displayName,
                    )
                    android.net.Uri.fromFile(outFile)
                }
            if (uri == null) throw IllegalStateException("Could not create file")
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
                ?: throw IllegalStateException("Could not write file")
            uri
        }.onSuccess {
            toast(doneNote ?: "✅ Saved to Downloads")
        }.onFailure { toast("Download failed: ${it.message}") }
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
