package com.cloudx.databridge

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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

    // Metric card references (label / value / sub / accent bar)
    private data class MetricCardViews(
        val label:     TextView,
        val value:     TextView,
        val sub:       TextView,
        val accentBar: View,
    )

    private lateinit var cardTotal:     MetricCardViews
    private lateinit var cardDelivered: MetricCardViews
    private lateinit var cardOnHold:    MetricCardViews
    private lateinit var cardReturned:  MetricCardViews
    private lateinit var cardTotalRuns: MetricCardViews
    private lateinit var cardOpenRuns:  MetricCardViews
    private lateinit var cardClosedRuns:MetricCardViews

    // Legend rows
    private data class LegendViews(val dot: View, val label: TextView, val value: TextView, val pct: TextView)
    private lateinit var legendDelivered: LegendViews
    private lateinit var legendOnHold:    LegendViews
    private lateinit var legendReturned:  LegendViews
    private lateinit var legendPending:   LegendViews

    // Chips
    private lateinit var chipToday:     Chip
    private lateinit var chipYesterday: Chip
    private lateinit var chipLast7:     Chip
    private lateinit var chipThisMonth: Chip
    private lateinit var chipCustom:    Chip

    // ── Branch filter state (mirrors what the ViewModel's LiveData last reported) ──
    private var availableBranches: List<BranchOption> = emptyList()
    private var selectedBranchIds: Set<String> = emptySet()

    // ── Colors ─────────────────────────────────────────────────────────────────
    private val colorGreen  = Color.parseColor("#10B981")
    private val colorAmber  = Color.parseColor("#F59E0B")
    private val colorRed    = Color.parseColor("#EF4444")
    private val colorBlue   = Color.parseColor("#3B82F6")
    private val colorMuted  = Color.parseColor("#64748B")
    private val colorAccent = Color.parseColor("#00d4ff")

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

        fun metricCard(id: Int) = root.findViewById<View>(id).let {
            MetricCardViews(
                label     = it.findViewById(R.id.tvMetricLabel),
                value     = it.findViewById(R.id.tvMetricValue),
                sub       = it.findViewById(R.id.tvMetricSub),
                accentBar = it.findViewById(R.id.viewAccentBar),
            )
        }
        cardTotal      = metricCard(R.id.cardTotal)
        cardDelivered  = metricCard(R.id.cardDelivered)
        cardOnHold     = metricCard(R.id.cardOnHold)
        cardReturned   = metricCard(R.id.cardReturned)
        cardTotalRuns  = metricCard(R.id.cardTotalRuns)
        cardOpenRuns   = metricCard(R.id.cardOpenRuns)
        cardClosedRuns = metricCard(R.id.cardClosedRuns)

        fun legendRow(id: Int) = root.findViewById<View>(id).let {
            LegendViews(
                dot   = it.findViewById(R.id.viewDot),
                label = it.findViewById(R.id.tvLegendLabel),
                value = it.findViewById(R.id.tvLegendValue),
                pct   = it.findViewById(R.id.tvLegendPct),
            )
        }
        legendDelivered = legendRow(R.id.legendDelivered)
        legendOnHold    = legendRow(R.id.legendOnHold)
        legendReturned  = legendRow(R.id.legendReturned)
        legendPending   = legendRow(R.id.legendPending)

        chipToday     = root.findViewById(R.id.chipToday)
        chipYesterday = root.findViewById(R.id.chipYesterday)
        chipLast7     = root.findViewById(R.id.chipLast7)
        chipThisMonth = root.findViewById(R.id.chipThisMonth)
        chipCustom    = root.findViewById(R.id.chipCustom)

        root.findViewById<View>(R.id.btnRetry)?.setOnClickListener { vm.refresh() }

        // Initial metric card labels
        cardTotal.label.text      = "TOTAL"
        cardDelivered.label.text  = "DELIVERED"
        cardOnHold.label.text     = "ON HOLD"
        cardReturned.label.text   = "RETURNED"
        cardTotalRuns.label.text  = "TOTAL RUNS"
        cardOpenRuns.label.text   = "OPEN"
        cardClosedRuns.label.text = "CLOSED"

        cardTotal.accentBar.setBackgroundColor(colorBlue)
        cardDelivered.accentBar.setBackgroundColor(colorGreen)
        cardOnHold.accentBar.setBackgroundColor(colorAmber)
        cardReturned.accentBar.setBackgroundColor(colorRed)
        cardTotalRuns.accentBar.setBackgroundColor(colorAccent)
        cardOpenRuns.accentBar.setBackgroundColor(colorGreen)
        cardClosedRuns.accentBar.setBackgroundColor(colorMuted)
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
        swipeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#16213e"))
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
    }

    private fun showSuccess(state: DashboardState.Success) {
        swipeRefresh.isRefreshing = false
        layoutLoading.isVisible = false
        layoutError.isVisible   = false
        swipeRefresh.isVisible  = true

        val s = state.stats

        // ── Parcel metric cards ──
        cardTotal.value.text     = s.totalParcels.toString()
        cardDelivered.value.text = s.delivered.toString()
        cardOnHold.value.text    = s.onHold.toString()
        cardReturned.value.text  = s.returned.toString()

        // Sub labels
        if (s.totalParcels > 0) {
            val rate = (s.delivered * 100) / s.totalParcels
            cardDelivered.sub.text = "$rate% success rate"
            cardDelivered.sub.isVisible = true
        }

        // ── Run metric cards ──
        cardTotalRuns.value.text  = (s.openRuns + s.closedRuns).toString()
        cardOpenRuns.value.text   = s.openRuns.toString()
        cardClosedRuns.value.text = s.closedRuns.toString()

        // ── Status breakdown bar ──
        buildStatusBar(s)

        // ── Agent list (admin/branch only) ──
        val showAgents = state.agents.isNotEmpty() && state.role !in listOf("worker", "delivery")
        cardAgents.isVisible      = showAgents
        tvSectionAgents.isVisible = showAgents
        if (showAgents) buildAgentRows(state.agents)
    }

    // ── Status breakdown bar ───────────────────────────────────────────────────

    private fun buildStatusBar(s: DashboardStats) {
        val total = s.totalParcels.coerceAtLeast(1).toFloat()
        layoutStatusBar.removeAllViews()

        data class Segment(val count: Int, val color: Int)

        val segments = listOf(
            Segment(s.delivered, colorGreen),
            Segment(s.onHold,    colorAmber),
            Segment(s.returned,  colorRed),
            Segment(s.pending,   colorMuted),
        )

        segments.filter { it.count > 0 }.forEach { seg ->
            val v = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT,
                    seg.count / total
                ).apply { setMargins(1, 0, 1, 0) }
                setBackgroundColor(seg.color)
            }
            layoutStatusBar.addView(v)
        }

        // Legend
        fun bindLegend(legend: LegendViews, label: String, count: Int, color: Int) {
            legend.dot.setBackgroundColor(color)
            legend.label.text  = label
            legend.value.text  = count.toString()
            val pct = if (total > 0) ((count / total) * 100).toInt() else 0
            legend.pct.text    = "($pct%)"
        }
        bindLegend(legendDelivered, "Delivered", s.delivered, colorGreen)
        bindLegend(legendOnHold,    "On Hold",   s.onHold,    colorAmber)
        bindLegend(legendReturned,  "Returned",  s.returned,  colorRed)
        bindLegend(legendPending,   "Pending",   s.pending,   colorMuted)
    }

    // ── Agent rows ─────────────────────────────────────────────────────────────

    private fun buildAgentRows(agents: List<AgentStat>) {
        layoutAgentRows.removeAllViews()
        agents.forEachIndexed { i, agent ->
            val row = layoutInflater.inflate(R.layout.item_agent_stat, layoutAgentRows, false)

            row.findViewById<TextView>(R.id.tvAgentRank).text      = "#${i + 1}"
            row.findViewById<TextView>(R.id.tvAgentName).text      = agent.agentName
            row.findViewById<TextView>(R.id.tvAgentRunId).text     = agent.runId
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

            layoutAgentRows.addView(row)
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
