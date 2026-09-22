package com.cloudx.databridge

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * App home screen. Personal "Verify & Delivery Request" funnel, scoped to the
 * logged-in user only (own runs + own requests — see
 * VerifyDeliveryDashboardViewModel's doc comment for the full classification
 * logic and Supabase remarks_status values this depends on).
 */
class DashboardFragment : Fragment() {

    private val vm: VerifyDeliveryDashboardViewModel by viewModels()

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var pbLoading: View
    private lateinit var tvError: TextView
    private lateinit var tvDateRange: TextView
    private lateinit var tvAgentFilter: TextView
    private lateinit var tvQuickSummary: TextView

    private data class MetricCardViews(val icon: TextView, val label: TextView, val value: TextView, val subtitle: TextView)
    private lateinit var cardTotalAssign: MetricCardViews
    private lateinit var cardVerifyRequest: MetricCardViews
    private lateinit var cardHoldReturn: MetricCardViews
    private lateinit var cardDeliveryRequest: MetricCardViews

    private data class SubMetricViews(val icon: TextView, val value: TextView, val subtitle: TextView)
    private lateinit var subConfirmed: SubMetricViews
    private lateinit var subDelivered: SubMetricViews
    private lateinit var subPending: SubMetricViews

    private lateinit var tvFunnelTotalValue: TextView
    private lateinit var tvFunnelVerifyValue: TextView
    private lateinit var tvFunnelHoldReturnValue: TextView
    private lateinit var tvFunnelDeliveryReqValue: TextView
    private lateinit var tvFunnelConfirmedValue: TextView
    private lateinit var tvFunnelDeliveredValue: TextView
    private lateinit var tvFunnelPendingValue: TextView

    // Date range state -- default "This Week" (last 7 days including today).
    private var rangeStartMs: Long = 0L
    private var rangeEndMs: Long = 0L
    private var rangeLabel: String = ""
    // Team view (admin/manager/supervisor): All Agents or one picked agent.
    // Workers are always self-scoped — the picker stays inert for them.
    private var teamMode: Boolean = false
    private var selectedAgentSystemId: String? = null
    private var latestAgentOptions: List<FunnelAgentOption> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        swipeRefresh   = view.findViewById(R.id.swipeVdRefresh)
        pbLoading      = view.findViewById(R.id.pbVdLoading)
        tvError        = view.findViewById(R.id.tvVdError)
        tvDateRange    = view.findViewById(R.id.tvVdDateRange)
        tvAgentFilter  = view.findViewById(R.id.tvVdAgentFilter)
        tvQuickSummary = view.findViewById(R.id.tvVdQuickSummary)

        cardTotalAssign = bindMetricCard(view.findViewById(R.id.cardVdTotalAssign))
        cardVerifyRequest = bindMetricCard(view.findViewById(R.id.cardVdVerifyRequest))
        cardHoldReturn = bindMetricCard(view.findViewById(R.id.cardVdHoldReturn))
        cardDeliveryRequest = bindMetricCard(view.findViewById(R.id.cardVdDeliveryRequest))

        subConfirmed = bindSubMetric(view.findViewById(R.id.subVdConfirmed))
        subDelivered = bindSubMetric(view.findViewById(R.id.subVdDelivered))
        subPending = bindSubMetric(view.findViewById(R.id.subVdPending))

        tvFunnelTotalValue = view.findViewById(R.id.tvVdFunnelTotalValue)
        tvFunnelVerifyValue = view.findViewById(R.id.tvVdFunnelVerifyValue)
        tvFunnelHoldReturnValue = view.findViewById(R.id.tvVdFunnelHoldReturnValue)
        tvFunnelDeliveryReqValue = view.findViewById(R.id.tvVdFunnelDeliveryReqValue)
        tvFunnelConfirmedValue = view.findViewById(R.id.tvVdFunnelConfirmedValue)
        tvFunnelDeliveredValue = view.findViewById(R.id.tvVdFunnelDeliveredValue)
        tvFunnelPendingValue = view.findViewById(R.id.tvVdFunnelPendingValue)

        cardTotalAssign.icon.text = "👥"
        cardTotalAssign.label.text = "Total assigned"
        cardVerifyRequest.icon.text = "📞"
        cardVerifyRequest.label.text = "Verify requests"
        cardHoldReturn.icon.text = "🛡️"
        cardHoldReturn.label.text = "Verified (Hold/Return)"
        cardDeliveryRequest.icon.text = "🚚"
        cardDeliveryRequest.label.text = "Delivery requests"

        subConfirmed.icon.text = "✅"
        subConfirmed.subtitle.text = "Confirmed"
        subDelivered.icon.text = "🚚"
        subDelivered.subtitle.text = "Delivered"
        subPending.icon.text = "⏰"
        subPending.subtitle.text = "Pending"

        setRangeToThisWeek()
        tvDateRange.setOnClickListener { showDateRangePicker() }
        tvAgentFilter.setOnClickListener { if (teamMode) showAgentPicker() }
        refreshTeamMode()
        setupCcDashboard(view)
        refreshCcVisibility()

        swipeRefresh.setOnRefreshListener { loadData() }

        vm.state.observe(viewLifecycleOwner) { state -> render(state) }

        loadData()
    }

    override fun onResume() {
        super.onResume()
        // Role can arrive after this screen (RBAC loads async) — enable the
        // team picker the moment a supervisor+ role is known.
        if (refreshTeamMode()) loadData()
        refreshCcVisibility()
    }

    /** Team view is a supervisor+ privilege (admin/manager/supervisor). Returns
     *  true when the mode flipped (caller reloads). Non-team users are pinned
     *  to self scope — any stale pick is cleared. */
    private fun refreshTeamMode(): Boolean {
        val can = RbacManager.current.roleId in setOf("admin", "manager", "supervisor")
        if (can == teamMode) return false
        teamMode = can
        if (!can) selectedAgentSystemId = null
        return true
    }

    private fun bindMetricCard(root: View): MetricCardViews = MetricCardViews(
        icon = root.findViewById(R.id.tvVdMetricIcon),
        label = root.findViewById(R.id.tvVdMetricLabel),
        value = root.findViewById(R.id.tvVdMetricValue),
        subtitle = root.findViewById(R.id.tvVdMetricSubtitle),
    )

    private fun bindSubMetric(root: View): SubMetricViews = SubMetricViews(
        icon = root.findViewById(R.id.tvVdSubIcon),
        value = root.findViewById(R.id.tvVdSubValue),
        subtitle = root.findViewById(R.id.tvVdSubSubtitle),
    )

    private fun loadData() {
        vm.load(rangeStartMs, rangeEndMs, teamMode, selectedAgentSystemId)
    }

    private fun render(state: VerifyDeliveryFunnelState) {
        swipeRefresh.isRefreshing = false
        pbLoading.isVisible = state.isLoading
        tvError.isVisible = state.error != null
        tvError.text = state.error?.let { "⚠ $it" }

        latestAgentOptions = state.agentOptions
        tvAgentFilter.text = if (state.scopeName.isNotBlank()) "👤 ${state.scopeName}" else "👤"

        cardTotalAssign.value.text = state.totalAssign.toString()
        cardTotalAssign.subtitle.text = "100%"

        val verifyPct = pct(state.verifyRequest, state.totalAssign)
        cardVerifyRequest.value.text = state.verifyRequest.toString()
        cardVerifyRequest.subtitle.text = "$verifyPct% of Total Assign"

        val holdReturnPct = pct(state.holdReturn, state.verifyRequest)
        cardHoldReturn.value.text = state.holdReturn.toString()
        cardHoldReturn.subtitle.text = "$holdReturnPct% of Verify Request"

        val deliveryReqPct = pct(state.deliveryRequest, state.verifyRequest)
        cardDeliveryRequest.value.text = state.deliveryRequest.toString()
        cardDeliveryRequest.subtitle.text = "$deliveryReqPct% of Verify Request"

        val confirmedPct = pct(state.confirmed, state.deliveryRequest)
        val deliveredPct = pct(state.delivered, state.deliveryRequest)
        val pendingPct = pct(state.pending, state.deliveryRequest)
        subConfirmed.value.text = state.confirmed.toString()
        subConfirmed.subtitle.text = "Confirmed ($confirmedPct%)"
        subDelivered.value.text = state.delivered.toString()
        subDelivered.subtitle.text = "Delivered ($deliveredPct%)"
        subPending.value.text = state.pending.toString()
        subPending.subtitle.text = "Pending ($pendingPct%)"

        tvFunnelTotalValue.text = "${state.totalAssign} (100%)"
        tvFunnelVerifyValue.text = "${state.verifyRequest} ($verifyPct% of Total Assign)"
        tvFunnelHoldReturnValue.text = "${state.holdReturn} ($holdReturnPct% of Verify Request)"
        tvFunnelDeliveryReqValue.text = "${state.deliveryRequest} ($deliveryReqPct% of Verify Request)"
        tvFunnelConfirmedValue.text = "${state.confirmed} ($confirmedPct%)"
        tvFunnelDeliveredValue.text = "${state.delivered} ($deliveredPct%)"
        tvFunnelPendingValue.text = "${state.pending} ($pendingPct%)"

        tvQuickSummary.text = buildString {
            append("• Total assigned: ${state.totalAssign} (100%)\n")
            append("• Verify requests: ${state.verifyRequest} ($verifyPct% of Total Assign)\n")
            append("• Verified (Hold/Return): ${state.holdReturn} ($holdReturnPct% of Verify Request)\n")
            append("• Delivery requests: ${state.deliveryRequest} ($deliveryReqPct% of Verify Request)\n")
            append("• Confirmed: ${state.confirmed} ($confirmedPct%), Delivered: ${state.delivered} ($deliveredPct%), Pending: ${state.pending} ($pendingPct%)")
            val t = state.talk
            if (t.remarks > 0) {
                val ansPct = pct(t.answered, t.remarks)
                append("\n• Talk: ${fmtDur(t.talkSec)} talk · ${t.answered}/${t.remarks} answered ($ansPct%)")
            }
        }
    }

    private fun fmtDur(totalSec: Int): String {
        if (totalSec < 60) return "${totalSec}s"
        val m = totalSec / 60
        if (m < 60) return "${m}m"
        return "${m / 60}h${m % 60}m"
    }

    private fun pct(part: Int, whole: Int): Int = if (whole <= 0) 0 else Math.round(part * 100f / whole)

    // ── Date range ──────────────────────────────────────────────────────────

    private fun startOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    private fun setRangeToThisWeek() {
        val end = Calendar.getInstance()
        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -6)
            startOfDay(this)
        }
        rangeStartMs = start.timeInMillis
        rangeEndMs = end.timeInMillis
        rangeLabel = "This Week"
        updateDateRangeLabel()
    }

    private fun updateDateRangeLabel() {
        val fmt = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
        tvDateRange.text = "📅 ${fmt.format(Date(rangeStartMs))} - ${fmt.format(Date(rangeEndMs))}"
    }

    private fun showDateRangePicker() {
        val options = arrayOf("Today", "Yesterday", "This Week", "Last 7 Days", "This Month", "Last 30 Days", "Custom Range")
        AlertDialog.Builder(requireContext())
            .setTitle("Select date range")
            .setItems(options) { _, which ->
                val cal = Calendar.getInstance()
                when (which) {
                    0 -> { // Today
                        startOfDay(cal)
                        rangeStartMs = cal.timeInMillis
                        rangeEndMs = System.currentTimeMillis()
                        rangeLabel = "Today"
                    }
                    1 -> { // Yesterday
                        cal.add(Calendar.DAY_OF_YEAR, -1)
                        startOfDay(cal)
                        rangeStartMs = cal.timeInMillis
                        cal.add(Calendar.DAY_OF_YEAR, 1)
                        rangeEndMs = cal.timeInMillis - 1
                        rangeLabel = "Yesterday"
                    }
                    2 -> { setRangeToThisWeek(); loadData(); return@setItems }
                    3 -> { // Last 7 Days
                        rangeEndMs = System.currentTimeMillis()
                        cal.add(Calendar.DAY_OF_YEAR, -7)
                        startOfDay(cal)
                        rangeStartMs = cal.timeInMillis
                        rangeLabel = "Last 7 Days"
                    }
                    4 -> { // This Month
                        cal.set(Calendar.DAY_OF_MONTH, 1)
                        startOfDay(cal)
                        rangeStartMs = cal.timeInMillis
                        rangeEndMs = System.currentTimeMillis()
                        rangeLabel = "This Month"
                    }
                    5 -> { // Last 30 Days
                        rangeEndMs = System.currentTimeMillis()
                        cal.add(Calendar.DAY_OF_YEAR, -30)
                        startOfDay(cal)
                        rangeStartMs = cal.timeInMillis
                        rangeLabel = "Last 30 Days"
                    }
                    6 -> { showCustomRangePicker(); return@setItems }
                }
                updateDateRangeLabel()
                loadData()
            }
            .show()
    }

    private fun showCustomRangePicker() {
        val startCal = Calendar.getInstance().apply { if (rangeStartMs > 0L) timeInMillis = rangeStartMs }
        DatePickerDialog(requireContext(), { _, y, m, d ->
            val from = Calendar.getInstance().apply { set(y, m, d); startOfDay(this) }
            val endCal = Calendar.getInstance()
            DatePickerDialog(requireContext(), { _, y2, m2, d2 ->
                val to = Calendar.getInstance().apply {
                    set(y2, m2, d2); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
                }
                if (to.timeInMillis < from.timeInMillis) {
                    android.widget.Toast.makeText(requireContext(), "End date cannot be before start date", android.widget.Toast.LENGTH_SHORT).show()
                    return@DatePickerDialog
                }
                rangeStartMs = from.timeInMillis
                rangeEndMs = to.timeInMillis
                val fmt = SimpleDateFormat("dd MMM", Locale.ENGLISH)
                rangeLabel = "${fmt.format(from.time)} - ${fmt.format(to.time)}"
                updateDateRangeLabel()
                loadData()
            }, endCal.get(Calendar.YEAR), endCal.get(Calendar.MONTH), endCal.get(Calendar.DAY_OF_MONTH)).show()
        }, startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH), startCal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ── Agent filter (team view only) ───────────────────────────────────────

    private fun showAgentPicker() {
        val names = listOf("All Agents") + latestAgentOptions.map { it.name }
        AlertDialog.Builder(requireContext())
            .setTitle("Select agent")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == 0) {
                    selectedAgentSystemId = null
                } else {
                    selectedAgentSystemId = latestAgentOptions[which - 1].systemId
                }
                loadData()
            }
            .show()
    }

    // ══ CC Dashboard mirror (extension Dashboard tab) ═════════════════════
    // Visible for every role EXCEPT worker/guest. Two sections with the same
    // content and controls as the extension: Hold Validation (Summary /
    // Details toggle, branches, From/To, View report, Sheet Sync, Download,
    // search) and Team Performance (branch, Team/Agent mode, From/To, View).
    // Live presence polling + inline call/remark composer live in the
    // CallCenter screens on app — intentionally not duplicated here.

    private val ccZone: java.time.ZoneId = java.time.ZoneId.of("Asia/Dhaka")
    private fun ccToday(): java.time.LocalDate = java.time.LocalDate.now(ccZone)

    private var hvMode: String = "summary" // summary | details
    private var hvBranches: List<SupabaseClaimsReader.BranchOption> = emptyList()
    private var hvSelectedBranchIds: MutableSet<String> = mutableSetOf()
    private var hvFrom: java.time.LocalDate? = null
    private var hvTo: java.time.LocalDate? = null
    private var hvCards: List<CcDashboardRepository.HvCard> = emptyList()
    private var hvDetails: List<CcDashboardRepository.HvDetail> = emptyList()
    private var hvSearch: String = ""
    private var hvSummaryFilter: String = "all" // all | validated | pending
    private var hvBusy: Boolean = false

    private var perfBranches: List<SupabaseClaimsReader.BranchOption> = emptyList()
    private var perfBranchId: String = ""
    private var perfMode: String = "team" // team | agent
    private var perfFrom: java.time.LocalDate? = null
    private var perfTo: java.time.LocalDate? = null
    private var perfSummary: CcDashboardRepository.PerfSummary? = null
    private var perfTeamRows: List<CcDashboardRepository.PerfTeamRow> = emptyList()
    private var perfAgentRows: List<CcDashboardRepository.PerfAgentRow> = emptyList()
    private var perfParcels: List<CcDashboardRepository.PerfParcel> = emptyList()
    private var perfDrillAgent: String? = null // agentId drilled into parcels, null = agent list
    private var perfBusy: Boolean = false

    private fun ccPrefs() =
        requireContext().getSharedPreferences("databridge_toggles", android.content.Context.MODE_PRIVATE)

    private fun isCcVisible(): Boolean {
        val id = RbacManager.current.roleId.trim().lowercase()
        return id != "worker" && id != "guest"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun refreshCcVisibility(): Boolean {
        val show = isCcVisible()
        view?.findViewById<View>(R.id.layoutCcDashboard)?.isVisible = show
        return show
    }

    private fun setupCcDashboard(root: View) {
        // Defaults: today (Dhaka) for all four date fields.
        if (hvFrom == null) hvFrom = ccToday()
        if (hvTo == null) hvTo = ccToday()
        if (perfFrom == null) perfFrom = ccToday()
        if (perfTo == null) perfTo = ccToday()
        perfMode = ccPrefs().getString("cc_perf_mode", "team")?.takeIf { it == "team" || it == "agent" } ?: "team"

        root.findViewById<View>(R.id.tvCcHvModeSummary).setOnClickListener { setHvMode("summary") }
        root.findViewById<View>(R.id.tvCcHvModeDetails).setOnClickListener { setHvMode("details") }
        updateHvModeToggle()
        root.findViewById<View>(R.id.tvCcHvBranches).setOnClickListener { showHvBranchPicker() }
        root.findViewById<View>(R.id.tvCcHvFrom).setOnClickListener { pickCcDate(true) }
        root.findViewById<View>(R.id.tvCcHvTo).setOnClickListener { pickCcDate(false) }
        root.findViewById<View>(R.id.btnCcHvView).setOnClickListener { hvViewReport() }
        root.findViewById<View>(R.id.btnCcHvSync).setOnClickListener { hvSyncToSheet() }
        root.findViewById<View>(R.id.btnCcHvDownload).setOnClickListener { hvDownload() }
        root.findViewById<android.widget.EditText>(R.id.etCcHvSearch).addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    hvSearch = s?.toString().orEmpty()
                    if (hvCards.isNotEmpty() || hvDetails.isNotEmpty()) renderHvResults()
                }
            })
        root.findViewById<View>(R.id.tvCcHvStatus).setOnClickListener {
            // Tap the Total/Validated/Pending line to cycle the filter.
            hvSummaryFilter = when (hvSummaryFilter) {
                "all" -> "validated"
                "validated" -> "pending"
                else -> "all"
            }
            renderHvResults()
        }
        root.findViewById<View>(R.id.tvCcPerfBranch).setOnClickListener { showPerfBranchPicker() }
        root.findViewById<View>(R.id.tvCcPerfMode).setOnClickListener { togglePerfMode() }
        root.findViewById<View>(R.id.tvCcPerfFrom).setOnClickListener { pickPerfDate(true) }
        root.findViewById<View>(R.id.tvCcPerfTo).setOnClickListener { pickPerfDate(false) }
        root.findViewById<View>(R.id.btnCcPerfView).setOnClickListener { perfViewReport() }

        updateCcLabels()
        // Branch directory (names) once — shared by both sections.
        viewLifecycleOwner.lifecycleScope.launch {
            val all = runCatching { SupabaseClaimsReader.fetchBranches() }.getOrDefault(emptyList())
            val mine = RbacManager.current.branchIds
            val scoped = if (mine.isEmpty()) all else all.filter { it.branchId in mine }
            hvBranches = scoped.ifEmpty { all }
            perfBranches = hvBranches
            if (hvSelectedBranchIds.isEmpty()) {
                hvSelectedBranchIds = hvBranches.map { it.branchId }.toMutableSet()
            } else {
                hvSelectedBranchIds.retainAll(hvBranches.map { it.branchId }.toSet())
            }
            val savedPerf = ccPrefs().getString("cc_perf_branch_id", null)
            perfBranchId = when {
                savedPerf != null && perfBranches.any { it.branchId == savedPerf } -> savedPerf
                else -> perfBranches.firstOrNull()?.branchId.orEmpty()
            }
            updateCcLabels()
        }
    }

    private fun branchNameOf(id: String): String =
        (hvBranches + perfBranches).find { it.branchId == id }?.name?.takeIf { it.isNotBlank() } ?: id

    private fun updateCcLabels() {
        val root = view ?: return
        val hvNames = hvSelectedBranchIds.map { branchNameOf(it) }
        root.findViewById<TextView>(R.id.tvCcHvBranches).text =
            if (hvNames.isEmpty()) "Branch: none" else "Branch: ${hvNames.joinToString(", ")}"
        val dfmt = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
        root.findViewById<TextView>(R.id.tvCcHvFrom).text = "From: ${hvFrom?.format(dfmt) ?: "—"}"
        root.findViewById<TextView>(R.id.tvCcHvTo).text = "To: ${hvTo?.format(dfmt) ?: "—"}"
        root.findViewById<TextView>(R.id.tvCcPerfBranch).text = "Branch: ${branchNameOf(perfBranchId).ifBlank { "—" }}"
        root.findViewById<TextView>(R.id.tvCcPerfMode).text =
            if (perfMode == "team") "Team (CC agent-wise)" else "Agent (delivery agent-wise)"
        root.findViewById<TextView>(R.id.tvCcPerfFrom).text = "From: ${perfFrom?.format(dfmt) ?: "—"}"
        root.findViewById<TextView>(R.id.tvCcPerfTo).text = "To: ${perfTo?.format(dfmt) ?: "—"}"
    }

    private fun updateHvModeToggle() {
        val root = view ?: return
        val sum = root.findViewById<TextView>(R.id.tvCcHvModeSummary)
        val det = root.findViewById<TextView>(R.id.tvCcHvModeDetails)
        val activeBg = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_pc_tab_active)
        val inactiveBg = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_pc_tab_inactive)
        sum.background = if (hvMode == "summary") activeBg else inactiveBg
        det.background = if (hvMode == "details") activeBg else inactiveBg
        sum.setTextColor(android.graphics.Color.parseColor(if (hvMode == "summary") "#059669" else "#64748B"))
        det.setTextColor(android.graphics.Color.parseColor(if (hvMode == "details") "#059669" else "#64748B"))
    }

    private fun setHvMode(mode: String) {
        if (mode == hvMode) return
        hvMode = mode
        // Old mode's rows cleared — Summary/Details never shows the other mode's data.
        hvCards = emptyList()
        hvDetails = emptyList()
        hvSearch = ""
        hvSummaryFilter = "all"
        view?.findViewById<android.widget.EditText>(R.id.etCcHvSearch)?.setText("")
        view?.findViewById<TextView>(R.id.tvCcHvStatus)?.text = ""
        view?.findViewById<android.widget.LinearLayout>(R.id.layoutCcHvResults)?.removeAllViews()
        updateHvModeToggle()
    }

    private fun showHvBranchPicker() {
        if (hvBranches.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "No branch assigned", Toast.LENGTH_SHORT).show()
            return
        }
        val ctx = requireContext()
        val checked = hvBranches.map { it.branchId in hvSelectedBranchIds }.toMutableList()
        val boxes = mutableListOf<android.widget.CheckBox>()
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(4))
        }
        hvBranches.forEachIndexed { i, b ->
            android.widget.CheckBox(ctx).apply {
                text = b.name.ifBlank { b.branchId }
                textSize = 14f
                isChecked = checked[i]
                setOnCheckedChangeListener { _, v -> checked[i] = v }
                boxes.add(this)
                root.addView(this)
            }
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(root) }
        AlertDialog.Builder(ctx)
            .setTitle("Branches")
            .setView(scroll)
            .setPositiveButton("Apply") { _, _ ->
                hvSelectedBranchIds = hvBranches.filterIndexed { i, _ -> checked[i] }
                    .map { it.branchId }.toMutableSet()
                hvCards = emptyList()
                hvDetails = emptyList()
                updateCcLabels()
                renderHvResults()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPerfBranchPicker() {
        if (perfBranches.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "No branch assigned", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Branch")
            .setItems(perfBranches.map { it.name.ifBlank { it.branchId } }.toTypedArray()) { _, which ->
                perfBranchId = perfBranches[which].branchId
                ccPrefs().edit().putString("cc_perf_branch_id", perfBranchId).apply()
                perfSummary = null
                updateCcLabels()
                renderPerfResults()
            }
            .show()
    }

    private fun togglePerfMode() {
        perfMode = if (perfMode == "team") "agent" else "team"
        ccPrefs().edit().putString("cc_perf_mode", perfMode).apply()
        perfDrillAgent = null
        updateCcLabels()
        renderPerfResults()
    }

    private fun pickCcDate(isFrom: Boolean) {
        val base = (if (isFrom) hvFrom else hvTo) ?: ccToday()
        android.app.DatePickerDialog(requireContext(), { _, y, m, d ->
            val picked = java.time.LocalDate.of(y, m + 1, d)
            if (isFrom) hvFrom = picked else hvTo = picked
            hvCards = emptyList()
            hvDetails = emptyList()
            updateCcLabels()
            renderHvResults()
        }, base.year, base.monthValue - 1, base.dayOfMonth).show()
    }

    private fun pickPerfDate(isFrom: Boolean) {
        val base = (if (isFrom) perfFrom else perfTo) ?: ccToday()
        android.app.DatePickerDialog(requireContext(), { _, y, m, d ->
            val picked = java.time.LocalDate.of(y, m + 1, d)
            if (isFrom) perfFrom = picked else perfTo = picked
            perfSummary = null
            updateCcLabels()
            renderPerfResults()
        }, base.year, base.monthValue - 1, base.dayOfMonth).show()
    }

    private fun hvWindow(): Pair<java.time.LocalDate, java.time.LocalDate>? {
        val from = hvFrom ?: return null
        val to = hvTo ?: return null
        return if (to.isBefore(from)) null else from to to
    }

    private fun hvIsoRange(): Triple<String, String, String>? {
        val (from, to) = hvWindow() ?: return null
        val startIso = from.atStartOfDay(ccZone).toInstant().toString()
        val endIso = to.plusDays(1).atStartOfDay(ccZone).toInstant().toString()
        return Triple(startIso, endIso, "${from} → ${to}")
    }

    private fun hvSetStatus(text: String) {
        view?.findViewById<TextView>(R.id.tvCcHvStatus)?.text = text
    }

    // ── Hold Validation: View / Sync / Download ───────────────────────────

    private fun hvViewReport() {
        if (hvBusy) return
        val range = hvIsoRange()
        if (range == null) {
            android.widget.Toast.makeText(requireContext(), "Pick From/To dates (From ≤ To)", Toast.LENGTH_SHORT).show()
            return
        }
        if (hvSelectedBranchIds.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "Select at least one branch", Toast.LENGTH_SHORT).show()
            return
        }
        val (startIso, endIso, label) = range
        val branches = hvSelectedBranchIds.toList()
        hvBusy = true
        hvSetStatus("Loading $label…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val allRows = mutableListOf<CcDashboardRepository.VRow>()
                val failed = mutableListOf<String>()
                branches.forEach { bid ->
                    runCatching { CcDashboardRepository.fetchReportRows(bid, startIso, endIso) }
                        .onSuccess { allRows += it }
                        .onFailure { failed.add(branchNameOf(bid)) }
                }
                if (allRows.isEmpty()) {
                    hvCards = emptyList()
                    hvDetails = emptyList()
                    hvSetStatus(if (failed.isEmpty()) "No validation data in $label" else "⚠ No data for: ${failed.joinToString(", ")}")
                    renderHvResults()
                    return@launch
                }
                val names = CcDashboardRepository.fetchUserNames(
                    allRows.flatMap { listOf(it.assignedTo, it.authorSys) }.toSet())
                if (hvMode == "summary") {
                    hvCards = CcDashboardRepository.buildSummaryCards(allRows, names)
                    hvDetails = emptyList()
                    val v = hvCards.count { !it.stillPending }
                    hvSetStatus("✓ Total ${hvCards.size} · Validated $v · Pending ${hvCards.size - v}" +
                        (if (failed.isNotEmpty()) " · ⚠ no data: ${failed.joinToString(", ")}" else ""))
                } else {
                    hvDetails = CcDashboardRepository.buildDetails(allRows, names)
                    hvCards = emptyList()
                    hvSetStatus("✓ ${hvDetails.size} remarks found" +
                        (if (failed.isNotEmpty()) " · ⚠ no data: ${failed.joinToString(", ")}" else ""))
                }
                renderHvResults()
            } catch (e: Exception) {
                hvSetStatus("⚠ Report failed: ${e.message?.take(120) ?: "error"}")
            } finally {
                hvBusy = false
            }
        }
    }

    private fun hvMatchesSearchCard(c: CcDashboardRepository.HvCard): Boolean {
        val q = hvSearch.trim().lowercase()
        if (q.isEmpty()) return true
        val qd = q.filter { it.isLetterOrDigit() }
        if (c.cId.lowercase().contains(q)) return true
        if (qd.isNotEmpty() && c.customerPhone.filter { it.isDigit() }.contains(qd)) return true
        if (c.agentName.lowercase().contains(q) || c.agentWho.lowercase().contains(q)) return true
        if (c.validatorName.lowercase().contains(q)) return true
        return c.days.any { d ->
            d.workerRemark.lowercase().contains(q) || d.ccRemark.lowercase().contains(q) ||
                d.workerWho.lowercase().contains(q) || d.ccWho.lowercase().contains(q)
        }
    }

    private fun renderHvResults() {
        val root = view ?: return
        val box = root.findViewById<android.widget.LinearLayout>(R.id.layoutCcHvResults)
        box.removeAllViews()
        val searchBox = root.findViewById<android.widget.EditText>(R.id.etCcHvSearch)
        if (hvMode == "summary") {
            var list = hvCards
            if (hvSummaryFilter == "validated") list = list.filter { !it.stillPending }
            if (hvSummaryFilter == "pending") list = list.filter { it.stillPending }
            searchBox.isVisible = hvCards.isNotEmpty()
            list = list.filter { hvMatchesSearchCard(it) }
                .sortedWith(compareByDescending<CcDashboardRepository.HvCard> { it.stillPending }.thenByDescending { it.dateKey })
            if (hvSearch.isNotBlank()) {
                addResultLine(box, if (list.isEmpty()) "🔍 \"${hvSearch}\" — no match" else "🔍 \"${hvSearch}\" — ${list.size} matches")
            }
            if (list.isEmpty() && hvSearch.isBlank()) {
                if (hvCards.isNotEmpty()) addResultLine(box, "No entries for this filter")
                return
            }
            list.forEach { addHvSummaryCard(box, it) }
        } else {
            val q = hvSearch.trim().lowercase()
            searchBox.isVisible = hvDetails.isNotEmpty()
            var list = hvDetails
            if (q.isNotEmpty()) {
                list = list.filter { d ->
                    d.cId.lowercase().contains(q) || d.agentName.lowercase().contains(q) ||
                        d.remark.lowercase().contains(q)
                }
                addResultLine(box, if (list.isEmpty()) "🔍 \"$hvSearch\" — no match" else "🔍 \"$hvSearch\" — ${list.size} matches")
            }
            if (list.isEmpty() && q.isBlank()) {
                if (hvDetails.isNotEmpty()) addResultLine(box, "No remarks found")
                return
            }
            list.forEach { addHvDetailRow(box, it) }
        }
    }

    private fun addResultLine(box: android.widget.LinearLayout, text: String) {
        box.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#64748B"))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        })
    }

    private fun addHvSummaryCard(box: android.widget.LinearLayout, c: CcDashboardRepository.HvCard) {
        val ctx = requireContext()
        val card = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_rounded)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp
        }
        card.addView(TextView(ctx).apply {
            text = "${c.cId} | ${CcDashboardRepository.ddMmYyyy(c.dateKey).let { c.dateLabel }}"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#0F172A"))
        })
        val meta = buildString {
            append(branchNameOf(c.branchId))
            if (c.parcelStatus.isNotBlank()) append(" · 📦 ${c.parcelStatus}")
        }
        card.addView(TextView(ctx).apply {
            text = meta
            textSize = 11.5f
            setTextColor(android.graphics.Color.parseColor("#64748B"))
        })
        c.days.forEach { d ->
            if (c.days.size > 1) {
                card.addView(TextView(ctx).apply {
                    text = d.dateLabel
                    textSize = 11f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.parseColor("#334155"))
                    setPadding(0, dp(6), 0, 0)
                })
            }
            card.addView(TextView(ctx).apply {
                text = "🙋 ${d.workerWho}${if (d.workerStatus.isNotBlank()) " · ${d.workerStatus}" else ""}\n" +
                    "${d.workerRemark.ifBlank { "(no remark)" }}${if (d.workerTime.isNotBlank()) " · ${d.workerTime}" else ""}"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#0F172A"))
                setPadding(0, dp(4), 0, 0)
            })
            if (d.hasCc) {
                card.addView(TextView(ctx).apply {
                    text = "✓ ${d.ccWho}${if (d.ccStatus.isNotBlank()) " · ${d.ccStatus}" else ""}\n" +
                        "${d.ccRemark.ifBlank { "(no CC text)" }}" +
                        (if (d.ccNote.isNotBlank()) "\n📝 ${d.ccNote}" else "") +
                        (if (d.ccTime.isNotBlank()) " · ${d.ccTime}" else "")
                    textSize = 12f
                    setTextColor(android.graphics.Color.parseColor("#065F46"))
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }
        card.addView(TextView(ctx).apply {
            text = if (c.stillPending) "⏳ Pending" else "✓ Validated"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor(if (c.stillPending) "#B45309" else "#059669"))
            setPadding(0, dp(6), 0, 0)
        })
        box.addView(card)
    }

    private fun addHvDetailRow(box: android.widget.LinearLayout, d: CcDashboardRepository.HvDetail) {
        val ctx = requireContext()
        val who = if (d.source == "WORKER") "🙋" else "✓ CC"
        val aWho = if (d.source == "WORKER") {
            listOf(d.authorName, d.agentName).firstOrNull { it.isNotBlank() && it != "—" } ?: d.source
        } else d.authorName.ifBlank { d.source }
        box.addView(TextView(ctx).apply {
            text = "${d.cId} | ${d.dateLabel} · ${d.timeLabel}\n" +
                "${branchNameOf(d.branchId)} · 👤 ${d.agentName}" +
                (if (d.parcelStatus.isNotBlank()) " · 📦 ${d.parcelStatus}" else "") + "\n" +
                "$who${if (d.status.isNotBlank()) " · ${d.status}" else ""}\n" +
                "${d.remark.ifBlank { "(no remark)" }}" +
                (if (d.note.isNotBlank()) "\n📝 ${d.note}" else "") +
                "\n${d.timeLabel} · 👤 $aWho"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#0F172A"))
            setBackgroundResource(R.drawable.bg_card_rounded)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp
        })
    }

    private fun hvDownload() {
        if (hvBusy) return
        val range = hvIsoRange()
        if (range == null) {
            android.widget.Toast.makeText(requireContext(), "Pick From/To dates (From ≤ To)", Toast.LENGTH_SHORT).show()
            return
        }
        if (hvSelectedBranchIds.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "Select at least one branch", Toast.LENGTH_SHORT).show()
            return
        }
        val (startIso, endIso, label) = range
        val branches = hvSelectedBranchIds.toList()
        hvBusy = true
        hvSetStatus("Preparing download…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Fresh fetch (never stale rows), no on-screen render needed.
                val allRows = mutableListOf<CcDashboardRepository.VRow>()
                branches.forEach { bid ->
                    runCatching { CcDashboardRepository.fetchReportRows(bid, startIso, endIso) }
                        .onSuccess { allRows += it }
                }
                if (allRows.isEmpty()) {
                    hvSetStatus("No validation data in $label")
                    return@launch
                }
                val names = CcDashboardRepository.fetchUserNames(
                    allRows.flatMap { listOf(it.assignedTo, it.authorSys) }.toSet())
                val csv: String
                val mode: String
                if (hvMode == "summary") {
                    mode = "summary"
                    csv = CcDashboardRepository.summaryCsv(
                        CcDashboardRepository.buildSummaryCards(allRows, names), ::branchNameOf)
                } else {
                    mode = "details"
                    csv = CcDashboardRepository.detailsCsv(
                        CcDashboardRepository.buildDetails(allRows, names), ::branchNameOf)
                }
                val datePart = "${hvFrom}__${hvTo}".let {
                    if (hvFrom == hvTo) "$hvFrom" else "${hvFrom}_to_${hvTo}"
                }
                val branchPart = when {
                    hvSelectedBranchIds.size == hvBranches.size || hvBranches.isEmpty() -> "all-branches"
                    hvSelectedBranchIds.size == 1 -> branchNameOf(branches.first())
                        .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                        .ifBlank { "branch" }
                    else -> "${hvSelectedBranchIds.size}-branches"
                }
                val fileName = "databridge-hold-validation-${mode}_${branchPart}_${datePart}.csv"
                val f = java.io.File(requireContext().cacheDir, "exports").apply { mkdirs() }
                val out = java.io.File(f, fileName)
                out.writeText("﻿" + csv, Charsets.UTF_8)
                saveCcDownload(out, fileName, "text/csv", "✅ CSV saved to Downloads")
                hvSetStatus("✓ Download ready: $fileName")
            } catch (e: Exception) {
                hvSetStatus("⚠ Download failed: ${e.message?.take(120) ?: "error"}")
            } finally {
                hvBusy = false
            }
        }
    }

    private fun hvSyncToSheet() {
        val range = hvIsoRange()
        if (range == null) {
            android.widget.Toast.makeText(requireContext(), "Pick From/To dates (From ≤ To)", Toast.LENGTH_SHORT).show()
            return
        }
        if (hvSelectedBranchIds.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "Select at least one branch", Toast.LENGTH_SHORT).show()
            return
        }
        val (from, to) = hvWindow() ?: return
        val days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
        if (days > 31) {
            hvSetStatus("⚠ Date range too large — max 31 days at once")
            return
        }
        val branches = hvSelectedBranchIds.toList()
        hvSetStatus("Syncing $from → $to…")
        viewLifecycleOwner.lifecycleScope.launch {
            val res = RemarkSheetMirror.bulkSyncToSheet(
                appContext = requireContext().applicationContext,
                branchIds = branches,
                onProgress = { label -> activity?.runOnUiThread { hvSetStatus("Syncing $label…") } },
                onAuthNeeded = {
                    activity?.runOnUiThread {
                        android.widget.Toast.makeText(requireContext(),
                            "Sheet: Google account not connected — connect and tap Sync again",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                startDate = from,
                endDate = to,
            )
            val msg = if (res.ok) {
                "✓ ${res.rangeLabel}: ${res.syncedRows} rows synced (${res.syncedCells} cells)" +
                    (if (res.overwrittenRows > 0) " · ${res.overwrittenRows} updated" else "") +
                    (if (res.filled > 0) " · ${res.filled} already filled" else "") +
                    (if (res.noCc > 0) " · ${res.noCc} no CC yet" else "")
            } else "⚠ ${res.message.ifBlank { "sync failed" }}"
            hvSetStatus(msg + res.errors.take(2).map { " · ⚠ ${it.take(80)}" }.joinToString(""))
        }
    }

    private fun saveCcDownload(file: java.io.File, displayName: String, mimeType: String, doneNote: String) {
        val ctx = requireContext()
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            android.widget.Toast.makeText(ctx, "Storage permission needed for Downloads", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            val resolver = ctx.contentResolver
            val uri: android.net.Uri =
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
                        displayName)
                    android.net.Uri.fromFile(outFile)
                }
                ?: throw IllegalStateException("Could not create file")
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
                ?: throw IllegalStateException("Could not write file")
        }.onSuccess {
            android.widget.Toast.makeText(ctx, doneNote, android.widget.Toast.LENGTH_LONG).show()
        }.onFailure {
            android.widget.Toast.makeText(ctx, "Download failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ── Team Performance ──────────────────────────────────────────────────

    private fun perfIsoRange(): Triple<String, String, String>? {
        val from = perfFrom ?: return null
        val to = perfTo ?: return null
        if (to.isBefore(from)) return null
        return Triple(
            from.atStartOfDay(ccZone).toInstant().toString(),
            to.plusDays(1).atStartOfDay(ccZone).toInstant().toString(),
            "$from → $to",
        )
    }

    private fun perfSetStatus(text: String) {
        view?.findViewById<TextView>(R.id.tvCcPerfStatus)?.text = text
    }

    private fun perfViewReport() {
        if (perfBusy) return
        val range = perfIsoRange()
        if (range == null) {
            android.widget.Toast.makeText(requireContext(), "Pick From/To dates (From ≤ To)", Toast.LENGTH_SHORT).show()
            return
        }
        if (perfBranchId.isBlank()) {
            android.widget.Toast.makeText(requireContext(), "Select a branch", Toast.LENGTH_SHORT).show()
            return
        }
        val (startIso, endIso, label) = range
        perfBusy = true
        perfSetStatus("Loading $label…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val rows = CcDashboardRepository.fetchReportRows(perfBranchId, startIso, endIso)
                if (rows.isEmpty()) {
                    perfSummary = null
                    perfSetStatus("No CC resolutions in $label")
                    renderPerfResults()
                    return@launch
                }
                val names = CcDashboardRepository.fetchUserNames(
                    rows.flatMap { listOf(it.assignedTo, it.authorSys) }.toSet())
                if (perfMode == "team") {
                    val (summary, teamRows, parcels) = CcDashboardRepository.buildTeamPerf(rows, names)
                    perfSummary = summary
                    perfTeamRows = teamRows
                    perfAgentRows = emptyList()
                    perfParcels = parcels
                    perfSetStatus("✓ ${summary.totalUnique} unique · ${rows.count { it.isCc }} CC entries")
                } else {
                    val (agentRows, parcels) = CcDashboardRepository.buildAgentPerf(rows, names)
                    perfSummary = null
                    perfTeamRows = emptyList()
                    perfAgentRows = agentRows
                    perfParcels = parcels
                    perfSetStatus("✓ ${agentRows.size} agents · ${parcels.size} parcels")
                }
                perfDrillAgent = null
                renderPerfResults()
            } catch (e: Exception) {
                perfSetStatus("⚠ Report failed: ${e.message?.take(120) ?: "error"}")
            } finally {
                perfBusy = false
            }
        }
    }

    private fun renderPerfResults() {
        val root = view ?: return
        val box = root.findViewById<android.widget.LinearLayout>(R.id.layoutCcPerfResults)
        box.removeAllViews()
        perfSummary?.let { s ->
            addResultLine(box, "Total Unique ${s.totalUnique} · Delivery Request ${s.drUnique} (${s.drPct}%)" +
                " · ✅ ${s.converted} (${s.convertedPct}%) · Hold ${s.holdPct}% · Return ${s.returnPct}%")
        }
        if (perfMode == "team") {
            if (perfDrillAgent == null) {
                if (perfTeamRows.isEmpty() && perfSummary == null) return
                perfTeamRows.forEachIndexed { i, r ->
                    box.addView(perfRowView("#${i + 1} ${r.agentName}" +
                        (if (r.agentEmp.isNotBlank()) " (${r.agentEmp})" else "") +
                        " | Total ${r.total}",
                        "🔒 Hold ${r.holdVerified} · ↩ Return ${r.returnVerified} · 📦 Delivery ${r.deliveryRequest}" +
                            (if (r.other > 0) " · ❓ ${r.other}" else "")))
                    box.getChildAt(box.childCount - 1).setOnClickListener {
                        perfDrillAgent = r.agentId
                        renderPerfResults()
                    }
                }
            } else {
                val aid = perfDrillAgent
                val mine = perfParcels.filter {
                    it.agent == (perfTeamRows.find { t -> t.agentId == aid }?.agentName ?: aid)
                }
                addResultLine(box, "← All agents · ${mine.size} parcels")
                box.getChildAt(box.childCount - 1).setOnClickListener {
                    perfDrillAgent = null
                    renderPerfResults()
                }
                mine.forEach { addPerfParcelRow(box, it) }
            }
        } else {
            if (perfDrillAgent == null) {
                if (perfAgentRows.isEmpty()) return
                perfAgentRows.forEachIndexed { i, r ->
                    box.addView(perfRowView("#${i + 1} ${r.agentName}" +
                        (if (r.agentEmp.isNotBlank()) " (${r.agentEmp})" else "") +
                        " | Request ${r.requested}",
                        "✅ Validated ${r.validated} · ⏳ Pending ${r.requested - r.validated}"))
                    box.getChildAt(box.childCount - 1).setOnClickListener {
                        perfDrillAgent = r.agentId
                        renderPerfResults()
                    }
                }
            } else {
                val aid = perfDrillAgent
                val mine = perfParcels.filter {
                    it.agent == (perfAgentRows.find { t -> t.agentId == aid }?.agentName ?: aid)
                }
                addResultLine(box, "← All agents · ${mine.size} parcels")
                box.getChildAt(box.childCount - 1).setOnClickListener {
                    perfDrillAgent = null
                    renderPerfResults()
                }
                mine.forEach { addPerfParcelRow(box, it) }
            }
        }
    }

    private fun perfRowView(title: String, subtitle: String): TextView {
        return TextView(requireContext()).apply {
            text = "$title\n$subtitle"
            textSize = 12.5f
            setTextColor(android.graphics.Color.parseColor("#0F172A"))
            setBackgroundResource(R.drawable.bg_card_rounded)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp
        }
    }

    private fun addPerfParcelRow(box: android.widget.LinearLayout, p: CcDashboardRepository.PerfParcel) {
        box.addView(TextView(requireContext()).apply {
            text = "${p.cId} | " + (if (p.converted) "✅ Delivered" else p.ccLabel) +
                (if (!p.converted) " / ⏳ ${p.nowStatus}" else "") +
                "\n${if (p.reqDate.isNotBlank()) "Req ${p.reqDate} ×${p.reqCount} · " else ""}${p.agent} · now: ${p.nowStatus}"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#0F172A"))
            setBackgroundResource(R.drawable.bg_card_rounded)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp
        })
    }
}
