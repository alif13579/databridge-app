package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import java.text.SimpleDateFormat
import java.util.*

class DashboardFragment : Fragment() {

    private val vm: DashboardViewModel by viewModels()

    // ── Views ──────────────────────────────────────────────────────────────────
    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var layoutLoading: View
    private lateinit var layoutError:   View
    private lateinit var tvError:       TextView
    private lateinit var tvDateLabel:   TextView
    private lateinit var tvDateSub:     TextView
    private lateinit var tvBranchDropdown: TextView
    private lateinit var layoutStatusBar:  LinearLayout
    private lateinit var layoutAgentRows:  LinearLayout
    private lateinit var cardAgents:       View
    private lateinit var tvSectionAgents:  TextView
    private lateinit var tvRollupToggle:   TextView
    private lateinit var tvDrillBreadcrumb: TextView

    // Metric card references (label / value / sub / accent bar)
    private data class MetricCardViews(
        val label:     TextView,
        val value:     TextView,
        val sub:       TextView,
        val accentBar: View,
    )

    private lateinit var cardTotal:     MetricCardViews
    // Delivered/OnHold/Returned are no longer fixed cards — see
    // layoutDynamicOverviewCards / buildOverviewCards() below.
    private lateinit var layoutDynamicOverviewCards: LinearLayout
    private lateinit var cardTotalRuns: MetricCardViews
    private lateinit var cardOpenRuns:  MetricCardViews
    private lateinit var cardClosedRuns:MetricCardViews
    private lateinit var cardEarnings: MetricCardViews

    // Legend rows — built dynamically in buildStatusBreakdown(), one per status found
    private lateinit var layoutStatusLegend: LinearLayout

    // Chips
    private lateinit var chipToday:     Chip
    private lateinit var chipYesterday: Chip
    private lateinit var chipLast7:     Chip
    private lateinit var chipThisMonth: Chip
    private lateinit var chipCustom:    Chip

    // ── Branch filter state (mirrors what the ViewModel's LiveData last reported) ──
    private var availableBranches: List<BranchOption> = emptyList()
    private var selectedBranchIds: Set<String> = emptySet()

    // ── Manager rollup-toggle state (mirrors vm.rollupMode; used by showSuccess() to
    // label the agent section "Supervisor Performance" vs "Worker Performance") ──
    private var currentRollupMode: Boolean = true
    private var lastAgents: List<AgentStat> = emptyList() // for expandedRows re-renders,
                                                            // which fire independently of state

    // ── Colors (theme-aware — resolved via requireContext() so they follow the
    // active day/night theme; must not be read before the fragment has a context) ──
    private val colorGreen:  Int get() = ContextCompat.getColor(requireContext(), R.color.theme_green)
    private val colorAmber:  Int get() = ContextCompat.getColor(requireContext(), R.color.theme_yellow)
    private val colorRed:    Int get() = ContextCompat.getColor(requireContext(), R.color.theme_red)
    private val colorBlue:   Int get() = ContextCompat.getColor(requireContext(), R.color.theme_blue)
    private val colorMuted:  Int get() = ContextCompat.getColor(requireContext(), R.color.theme_text_secondary)
    private val colorAccent: Int get() = ContextCompat.getColor(requireContext(), R.color.theme_accent)

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupChips()
        setupSwipeRefresh()
        observeState()
        // Without this, _state stays at its MutableLiveData default (Loading) until the user
        // manually taps a date-range chip — the fragment would otherwise show the loading
        // spinner indefinitely on first open.
        vm.setDateRange(DashboardViewModel.todayRange())
    }

    // ── View binding ───────────────────────────────────────────────────────────

    private fun bindViews(root: View) {
        swipeRefresh    = root.findViewById(R.id.swipeRefresh)
        layoutLoading   = root.findViewById(R.id.layoutLoading)
        layoutError     = root.findViewById(R.id.layoutError)
        tvError         = root.findViewById(R.id.tvError)
        tvDateLabel     = root.findViewById(R.id.tvDateLabel)
        tvDateSub       = root.findViewById(R.id.tvDateSubLabel)
        tvBranchDropdown = root.findViewById(R.id.tvBranchDropdown)
        tvBranchDropdown.setOnClickListener { showBranchDropdown() }
        layoutStatusBar = root.findViewById(R.id.layoutStatusBar)
        layoutAgentRows = root.findViewById(R.id.layoutAgentRows)
        cardAgents      = root.findViewById(R.id.cardAgents)
        tvSectionAgents = root.findViewById(R.id.tvSectionAgents)
        tvRollupToggle  = root.findViewById(R.id.tvRollupToggle)
        tvRollupToggle.setOnClickListener { vm.setRollupMode(!currentRollupMode) }
        tvDrillBreadcrumb = root.findViewById(R.id.tvDrillBreadcrumb)
        tvDrillBreadcrumb.setOnClickListener { vm.drillBack() }

        fun metricCard(id: Int) = root.findViewById<View>(id).let {
            MetricCardViews(
                label     = it.findViewById(R.id.tvMetricLabel),
                value     = it.findViewById(R.id.tvMetricValue),
                sub       = it.findViewById(R.id.tvMetricSub),
                accentBar = it.findViewById(R.id.viewAccentBar),
            )
        }
        cardTotal      = metricCard(R.id.cardTotal)
        cardTotalRuns  = metricCard(R.id.cardTotalRuns)
        cardOpenRuns   = metricCard(R.id.cardOpenRuns)
        cardClosedRuns = metricCard(R.id.cardClosedRuns)
        cardEarnings   = metricCard(R.id.cardEarnings)

        layoutDynamicOverviewCards = root.findViewById(R.id.layoutDynamicOverviewCards)
        layoutStatusLegend = root.findViewById(R.id.layoutStatusLegend)

        chipToday     = root.findViewById(R.id.chipToday)
        chipYesterday = root.findViewById(R.id.chipYesterday)
        chipLast7     = root.findViewById(R.id.chipLast7)
        chipThisMonth = root.findViewById(R.id.chipThisMonth)
        chipCustom    = root.findViewById(R.id.chipCustom)

        root.findViewById<View>(R.id.btnRetry)?.setOnClickListener { vm.refresh() }

        // Initial metric card labels — Delivered/OnHold/Returned no longer have fixed
        // labels/colors here; buildOverviewCards() sets those per-card, per-render, from
        // each StatusBreakdownItem's own label/color (same source the legend already uses).
        cardTotal.label.text      = "TOTAL"
        cardTotalRuns.label.text  = "TOTAL RUNS"
        cardOpenRuns.label.text   = "OPEN"
        cardClosedRuns.label.text = "CLOSED"
        cardEarnings.label.text   = "EARNINGS"

        cardTotal.accentBar.setBackgroundColor(colorBlue)
        cardTotalRuns.accentBar.setBackgroundColor(colorAccent)
        cardOpenRuns.accentBar.setBackgroundColor(colorGreen)
        cardClosedRuns.accentBar.setBackgroundColor(colorMuted)
        cardEarnings.accentBar.setBackgroundColor(colorGreen)
    }

    // ── Chip setup ─────────────────────────────────────────────────────────────

    private fun setupChips() {
        val chips = listOf(chipToday, chipYesterday, chipLast7, chipThisMonth, chipCustom)

        fun selectChip(selected: Chip) {
            chips.forEach { it.isChecked = it == selected }
        }

        chipToday.setOnClickListener {
            selectChip(chipToday)
            vm.setDateRange(DashboardViewModel.todayRange())
        }
        chipYesterday.setOnClickListener {
            selectChip(chipYesterday)
            vm.setDateRange(DashboardViewModel.yesterdayRange())
        }
        chipLast7.setOnClickListener {
            selectChip(chipLast7)
            vm.setDateRange(DashboardViewModel.last7DaysRange())
        }
        chipThisMonth.setOnClickListener {
            selectChip(chipThisMonth)
            vm.setDateRange(DashboardViewModel.thisMonthRange())
        }
        chipCustom.setOnClickListener {
            selectChip(chipCustom)
            showDateRangePicker()
        }
    }

    private fun showDateRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select date range")
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val start = selection.first ?: return@addOnPositiveButtonClickListener
            val end   = selection.second ?: start
            vm.setDateRange(DashboardViewModel.customRange(start, end))
        }
        picker.show(childFragmentManager, "date_picker")
    }

    // ── Swipe-to-refresh ───────────────────────────────────────────────────────

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeColors(colorAccent, colorGreen)
        swipeRefresh.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(requireContext(), R.color.theme_bg_card))
        swipeRefresh.setOnRefreshListener { vm.refresh() }
    }

    // ── Observe state ──────────────────────────────────────────────────────────

    private fun observeState() {
        vm.state.observe(viewLifecycleOwner) { state ->
            when (state) {
                is DashboardState.Loading -> showLoading()
                is DashboardState.Error   -> showError(state.message)
                is DashboardState.Success -> showSuccess(state)
            }
        }
        vm.dateRange.observe(viewLifecycleOwner) { range ->
            tvDateLabel.text = range.label
            tvDateSub.text   = formatDateRange(range)
        }
        vm.availableBranches.observe(viewLifecycleOwner) { branches ->
            availableBranches = branches
            tvBranchDropdown.isVisible = branches.size > 1
            updateBranchDropdownLabel()
        }
        vm.selectedBranchIds.observe(viewLifecycleOwner) { ids ->
            selectedBranchIds = ids
            updateBranchDropdownLabel()
        }
        // Meaningful for anyone with subordinates now (hasSubordinates in showSuccess()),
        // not just role == "manager" — cheap LiveData either way, no Firebase read of its own.
        vm.rollupMode.observe(viewLifecycleOwner) { rollup ->
            currentRollupMode = rollup
            tvRollupToggle.text = if (rollup) "👥 Team View ▾" else "👤 Direct Reports ▾"
        }
        vm.drillStack.observe(viewLifecycleOwner) { stack ->
            if (stack.isEmpty()) {
                tvDrillBreadcrumb.isVisible = false
            } else {
                tvDrillBreadcrumb.isVisible = true
                tvDrillBreadcrumb.text = "← " + stack.joinToString(" / ") { it.name }
            }
        }
        // Fires independently of vm.state (a chevron tap doesn't touch state) — re-render
        // against whatever agent list showSuccess() last cached.
        vm.expandedRows.observe(viewLifecycleOwner) { expanded ->
            buildAgentRows(lastAgents, expanded)
        }
        // Refresh-with-existing-data path: keep the current Success view up, just show
        // swipeRefresh's own spinner instead of the full-screen Loading view (see load()).
        vm.isRefreshing.observe(viewLifecycleOwner) { refreshing ->
            swipeRefresh.isRefreshing = refreshing
        }
        // A refresh that failed while old data was still on screen — surface it without
        // wiping that data via showError().
        vm.refreshError.observe(viewLifecycleOwner) { message ->
            if (message == null) return@observe
            Toast.makeText(requireContext(), "Dashboard error: $message", Toast.LENGTH_LONG).show()
            com.google.android.material.snackbar.Snackbar
                .make(swipeRefresh, "⚠ $message", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .show()
            vm.clearRefreshError()
        }
    }

    // ── Branch filter dropdown ─────────────────────────────────────────────────
    // Mirrors CallCenterFragment's branch dropdown: multi-select, "empty selection
    // = all of my branches" convention, names resolved by the ViewModel and scoped
    // to RbacManager.current.branchIds (never company-wide).

    private fun updateBranchDropdownLabel() {
        val selected = selectedBranchIds.intersect(availableBranches.map { it.id }.toSet())
        val isFiltered = selected.isNotEmpty() && selected.size < availableBranches.size
        val nameOf = { id: String -> availableBranches.firstOrNull { it.id == id }?.name ?: id }
        val label = when {
            !isFiltered -> "All Branches ▾"
            selected.size == 1 -> "${nameOf(selected.first())} ▾"
            selected.size == 2 -> {
                val names = selected.map(nameOf)
                "${names[0]} & ${names[1]} ▾"
            }
            else -> {
                val names = selected.take(2).map(nameOf)
                "${names[0]}, ${names[1]} & ${selected.size - 2} more ▾"
            }
        }
        tvBranchDropdown.text = label
        val ctx = context ?: return
        tvBranchDropdown.setBackgroundResource(
            if (isFiltered) R.drawable.bg_filter_chip_active_purple else R.drawable.bg_filter_chip_inactive
        )
        tvBranchDropdown.setTextColor(
            ctx.getColor(if (isFiltered) android.R.color.white else R.color.theme_text_secondary)
        )
    }

    private fun showBranchDropdown() {
        val ctx = context ?: return
        if (availableBranches.isEmpty()) return
        val branchArray = availableBranches.map { it.id }.toTypedArray()
        val names = availableBranches.map { it.name }.toTypedArray()
        // "Empty selection = all" resting state — expand to the full set up front so
        // unchecking a box has something real to remove, then collapse back to empty
        // at Apply time if everything ended up still selected.
        val working = if (selectedBranchIds.isEmpty()) branchArray.toMutableSet()
                      else selectedBranchIds.toMutableSet()
        val checked = BooleanArray(branchArray.size) { i -> branchArray[i] in working }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Select Branches")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                if (isChecked) working.add(branchArray[which]) else working.remove(branchArray[which])
            }
            .setPositiveButton("Apply") { _, _ ->
                val finalSelection = if (working.size < branchArray.size) working else emptySet()
                vm.setSelectedBranchIds(finalSelection)
            }
            .setNeutralButton(if (selectedBranchIds.isEmpty()) "All" else "Clear") { _, _ ->
                vm.setSelectedBranchIds(emptySet())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── State renderers ────────────────────────────────────────────────────────

    private fun showLoading() {
        swipeRefresh.isRefreshing = false
        layoutLoading.isVisible = true
        layoutError.isVisible   = false
        swipeRefresh.isVisible  = false
    }

    private fun showError(msg: String) {
        swipeRefresh.isRefreshing = false
        layoutLoading.isVisible = false
        layoutError.isVisible   = true
        swipeRefresh.isVisible  = false
        tvError.text            = "⚠  $msg"
        Toast.makeText(requireContext(), "Dashboard error: $msg", Toast.LENGTH_LONG).show()
    }

    private fun showSuccess(state: DashboardState.Success) {
        swipeRefresh.isRefreshing = false
        layoutLoading.isVisible = false
        layoutError.isVisible   = false
        swipeRefresh.isVisible  = true

        val s = state.stats

        // ── Parcel metric cards ──
        cardTotal.value.text = s.totalParcels.toString()
        buildOverviewCards(state.breakdown)

        // ── Run metric cards ──
        cardTotalRuns.value.text  = (s.openRuns + s.closedRuns).toString()
        cardOpenRuns.value.text   = s.openRuns.toString()
        cardClosedRuns.value.text = s.closedRuns.toString()

        // ── Earnings ── same ৳-prefixed formatMoneyPlain() convention SalaryManagerFragment uses
        cardEarnings.value.text = "৳${formatMoneyPlain(s.earnings)}"

        // ── Status breakdown bar ──
        buildStatusBreakdown(state.breakdown)

        // ── Agent list — visible for anyone with subordinates (Success.hasSubordinates),
        // not a hardcoded role !in ["worker","delivery"] check — a role with nobody below
        // it naturally has an empty agents list either way, so this generalizes safely.
        val showAgents = state.hasSubordinates
        cardAgents.isVisible      = showAgents
        tvSectionAgents.isVisible = showAgents
        // Visible for ANYONE with at least one subordinate (subordinatePool() found someone),
        // not just role == "manager" — Phase 3 item from the dynamic role-hierarchy plan.
        // Stays true even if this particular mode found zero rows — otherwise a viewer whose
        // rollup tier is empty (but who has real people further down) would have data but no
        // visible way to flip to the flat view and see it.
        tvRollupToggle.isVisible = state.hasSubordinates
        tvSectionAgents.text = when {
            state.hasSubordinates && currentRollupMode -> "Team Performance"
            state.hasSubordinates                      -> "Direct Reports"
            else                                        -> "Agent Performance"
        }
        lastAgents = state.agents
        if (showAgents) buildAgentRows(state.agents, vm.expandedRows.value ?: emptyMap())
    }

    // ── Per-status overview cards ────────────────────────────────────────────────

    /** Replaces the old fixed Delivered/OnHold/Returned cards — one dynamic card per
     *  distinct final_status actually found in range, 2 per row (same visual convention
     *  the old fixed rows used), value = count, sub = percentage of total. Reuses the
     *  same StatusBreakdownItem list buildStatusBreakdown() already renders as the bar +
     *  legend below — no separate computation, just a second rendering of it as cards. */
    private fun buildOverviewCards(breakdown: List<StatusBreakdownItem>) {
        layoutDynamicOverviewCards.removeAllViews()

        breakdown.filter { it.count > 0 }.chunked(2).forEach { pair ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            pair.forEach { item ->
                val card = layoutInflater.inflate(R.layout.item_metric_card, row, false)
                card.findViewById<View>(R.id.viewAccentBar).setBackgroundColor(item.color)
                card.findViewById<TextView>(R.id.tvMetricLabel).text = item.label.uppercase()
                card.findViewById<TextView>(R.id.tvMetricValue).text = item.count.toString()
                card.findViewById<TextView>(R.id.tvMetricSub).apply {
                    text = "${item.percent}% of total"
                    isVisible = true
                }
                row.addView(card)
            }
            layoutDynamicOverviewCards.addView(row)
        }
    }

    // ── Status breakdown bar ───────────────────────────────────────────────────

    private fun buildStatusBreakdown(breakdown: List<StatusBreakdownItem>) {
        layoutStatusBar.removeAllViews()
        layoutStatusLegend.removeAllViews()

        val total = breakdown.sumOf { it.count }.coerceAtLeast(1).toFloat()

        breakdown.filter { it.count > 0 }.forEach { item ->
            val v = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT,
                    item.count / total
                ).apply { setMargins(1, 0, 1, 0) }
                setBackgroundColor(item.color)
            }
            layoutStatusBar.addView(v)

            val row = layoutInflater.inflate(R.layout.item_legend_row, layoutStatusLegend, false)
            row.findViewById<View>(R.id.viewDot).setBackgroundColor(item.color)
            row.findViewById<TextView>(R.id.tvLegendLabel).text = item.label
            row.findViewById<TextView>(R.id.tvLegendValue).text = item.count.toString()
            row.findViewById<TextView>(R.id.tvLegendPct).text   = "(${item.percent}%)"
            layoutStatusLegend.addView(row)
        }
    }

    // ── Agent rows ─────────────────────────────────────────────────────────────
    // Phase 3 (recursive multi-level grouping): each row's tvAgentRunId — always blank here,
    // runId is never set on a dashboard AgentStat — is repurposed as an expand/collapse
    // chevron instead of adding a new view. Expanding fetches that row's own subordinates
    // lazily (vm.toggleExpand) and renders them indented directly beneath it in this same
    // list, recursively — not a separate nested-tree data structure, just this function
    // calling itself one level deeper. Tapping the REST of the row still drills in (replaces
    // the whole screen) — two different ways to go deeper, chevron for a quick peek in
    // place, row-tap for full focus.

    private fun buildAgentRows(agents: List<AgentStat>, expandedRows: Map<String, List<AgentStat>>) {
        layoutAgentRows.removeAllViews()
        val rank = intArrayOf(1)
        agents.forEach { renderAgentRow(it, expandedRows, depth = 0, rank) }
    }

    private fun renderAgentRow(agent: AgentStat, expandedRows: Map<String, List<AgentStat>>, depth: Int, rank: IntArray) {
        val row = layoutInflater.inflate(R.layout.item_agent_stat, layoutAgentRows, false)

        row.findViewById<TextView>(R.id.tvAgentRank).text      = "#${rank[0]}"
        rank[0]++
        row.findViewById<TextView>(R.id.tvAgentName).text      = agent.agentName
        row.findViewById<TextView>(R.id.tvAgentDelivered).text = "${agent.delivered}✓"
        row.findViewById<TextView>(R.id.tvAgentReturned).text  = "${agent.returned}↩"
        row.findViewById<TextView>(R.id.tvAgentOnHold).text    = "${agent.onHold}⏸"

        val rateView = row.findViewById<TextView>(R.id.tvAgentRate)
        rateView.text = "${agent.deliveryRate}%"
        rateView.setTextColor(when {
            agent.deliveryRate >= 70 -> colorGreen
            agent.deliveryRate >= 50 -> colorAmber
            else                     -> colorRed
        })

        val isExpanded = agent.agentId in expandedRows
        val chevron = row.findViewById<TextView>(R.id.tvAgentRunId)
        chevron.text = if (isExpanded) "▼" else "▶"
        chevron.isClickable = true
        chevron.setOnClickListener {
            vm.toggleExpand(agent.agentId, agent.roleId, agent.branchIds)
        }

        // Indent nested rows so depth is visually obvious.
        row.setPadding(row.paddingLeft + depth * 32, row.paddingTop, row.paddingRight, row.paddingBottom)

        layoutAgentRows.addView(row)

        // Tap any row to drill into that person's own subordinates (their own
        // level/branch scope, not this screen's). Harmless if they turn out to have
        // nobody below them — that just falls through to their own single-person stat,
        // same self-only view they'd see logging in themselves.
        row.isClickable = true
        row.setOnClickListener {
            vm.drillInto(agent.agentId, agent.agentName, agent.level, agent.roleId, agent.branchIds)
        }

        if (isExpanded) {
            expandedRows[agent.agentId]?.forEach { child -> renderAgentRow(child, expandedRows, depth + 1, rank) }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun formatDateRange(range: DateRange): String {
        val sdf = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        return if (range.startTs == range.endTs) {
            sdf.format(Date(range.startTs))
        } else {
            "${sdf.format(Date(range.startTs))} – ${sdf.format(Date(range.endTs))}"
        }
    }
}
