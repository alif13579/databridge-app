package com.cloudx.databridge

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Branch Summary — date-range run + validation funnel for one branch.
 *
 * Header: from/to (default today, Dhaka) + branch (user's first branch by
 * default, dropdown when several). Summary table below, then run-wise rows.
 * Workers are self-scoped (own runs only); incharge+ see the whole branch —
 * see BranchSummaryViewModel.
 */
class BranchSummaryFragment : Fragment() {

    private val vm: BranchSummaryViewModel by viewModels()

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var pbLoading: View
    private lateinit var tvError: TextView
    private lateinit var tvScope: TextView
    private lateinit var tvDateRange: TextView
    private lateinit var tvBranch: TextView
    private lateinit var layoutSummary: LinearLayout
    private lateinit var tvRunsHeader: TextView
    private lateinit var layoutRuns: LinearLayout

    private var rangeStartMs: Long = 0L
    private var rangeEndMs: Long = 0L
    private var selectedBranch: String = ""

    private val branchOptions: List<String>
        get() = RbacManager.current.branchIds.filter { it.isNotBlank() }.distinct()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_branch_summary, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swipeRefresh = view.findViewById(R.id.swipeBsRefresh)
        pbLoading = view.findViewById(R.id.pbBsLoading)
        tvError = view.findViewById(R.id.tvBsError)
        tvScope = view.findViewById(R.id.tvBsScope)
        tvDateRange = view.findViewById(R.id.tvBsDateRange)
        tvBranch = view.findViewById(R.id.tvBsBranch)
        layoutSummary = view.findViewById(R.id.layoutBsSummary)
        tvRunsHeader = view.findViewById(R.id.tvBsRunsHeader)
        layoutRuns = view.findViewById(R.id.layoutBsRuns)

        // Default: today (Dhaka) + first branch.
        rangeStartMs = DhakaTime.dayStartMillis()
        rangeEndMs = DhakaTime.dayEndMillis()
        selectedBranch = branchOptions.firstOrNull().orEmpty()

        updateLabels()
        tvDateRange.setOnClickListener { showDateRangePicker() }
        tvBranch.setOnClickListener { if (branchOptions.size > 1) showBranchPicker() }
        swipeRefresh.setOnRefreshListener { loadData() }

        vm.state.observe(viewLifecycleOwner) { render(it) }
        loadData()
    }

    override fun onResume() {
        super.onResume()
        // RBAC can arrive after this screen — pick up branches late.
        if (selectedBranch.isBlank()) {
            val first = branchOptions.firstOrNull().orEmpty()
            if (first.isNotBlank()) {
                selectedBranch = first
                updateLabels()
                loadData()
            }
        }
    }

    private fun loadData() {
        if (selectedBranch.isBlank()) {
            vm.load("", rangeStartMs, rangeEndMs)
            return
        }
        updateLabels()
        vm.load(selectedBranch, rangeStartMs, rangeEndMs)
    }

    private fun updateLabels() {
        val fmt = DhakaTime.sdf("dd MMM yyyy")
        tvDateRange.text = "\uD83D\uDCC5 ${fmt.format(Date(rangeStartMs))} - ${fmt.format(Date(rangeEndMs))}"
        tvBranch.text = if (selectedBranch.isBlank()) "No branch"
            else if (branchOptions.size > 1) "🏢 $selectedBranch ▾" else "🏢 $selectedBranch"
    }

    // ── Pickers (same shape as DashboardFragment) ────────────────────────────

    private fun showBranchPicker() {
        AlertDialog.Builder(requireContext())
            .setTitle("Select branch")
            .setItems(branchOptions.toTypedArray()) { _, which ->
                selectedBranch = branchOptions[which]
                loadData()
            }
            .show()
    }

    private fun showDateRangePicker() {
        val options = arrayOf("Today", "Yesterday", "This Week", "Last 7 Days", "This Month", "Last 30 Days", "Custom Range")
        AlertDialog.Builder(requireContext())
            .setTitle("Select date range")
            .setItems(options) { _, which ->
                val cal = DhakaTime.calendar()
                when (which) {
                    0 -> {
                        rangeStartMs = DhakaTime.dayStartMillis()
                        rangeEndMs = DhakaTime.dayEndMillis()
                    }
                    1 -> {
                        cal.add(Calendar.DAY_OF_YEAR, -1)
                        rangeStartMs = DhakaTime.dayStartMillis(cal.timeInMillis)
                        rangeEndMs = DhakaTime.dayEndMillis(cal.timeInMillis)
                    }
                    2 -> {
                        cal.add(Calendar.DAY_OF_YEAR, -6)
                        rangeStartMs = DhakaTime.dayStartMillis(cal.timeInMillis)
                        rangeEndMs = DhakaTime.dayEndMillis()
                    }
                    3 -> {
                        rangeEndMs = System.currentTimeMillis()
                        cal.add(Calendar.DAY_OF_YEAR, -7)
                        rangeStartMs = DhakaTime.dayStartMillis(cal.timeInMillis)
                    }
                    4 -> {
                        cal.set(Calendar.DAY_OF_MONTH, 1)
                        rangeStartMs = DhakaTime.dayStartMillis(cal.timeInMillis)
                        rangeEndMs = System.currentTimeMillis()
                    }
                    5 -> {
                        rangeEndMs = System.currentTimeMillis()
                        cal.add(Calendar.DAY_OF_YEAR, -30)
                        rangeStartMs = DhakaTime.dayStartMillis(cal.timeInMillis)
                    }
                    6 -> { showCustomRangePicker(); return@setItems }
                }
                loadData()
            }
            .show()
    }

    private fun showCustomRangePicker() {
        val startCal = DhakaTime.calendar().apply { if (rangeStartMs > 0L) timeInMillis = rangeStartMs }
        DatePickerDialog(requireContext(), { _, y, m, d ->
            val from = DhakaTime.calendar().apply {
                set(y, m, d)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }
            val endCal = DhakaTime.calendar()
            DatePickerDialog(requireContext(), { _, y2, m2, d2 ->
                val to = DhakaTime.calendar().apply {
                    set(y2, m2, d2)
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
                }
                if (to.timeInMillis < from.timeInMillis) {
                    android.widget.Toast.makeText(requireContext(), "End date cannot be before start date", android.widget.Toast.LENGTH_SHORT).show()
                    return@DatePickerDialog
                }
                rangeStartMs = from.timeInMillis
                rangeEndMs = to.timeInMillis
                loadData()
            }, endCal.get(Calendar.YEAR), endCal.get(Calendar.MONTH), endCal.get(Calendar.DAY_OF_MONTH)).show()
        }, startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH), startCal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ── Render ───────────────────────────────────────────────────────────────

    private fun render(state: BranchSummaryState) {
        pbLoading.isVisible = state.isLoading
        swipeRefresh.isRefreshing = false
        tvError.isVisible = state.error != null
        tvError.text = state.error.orEmpty()

        val selfNote = if (state.selfScope) " · self" else ""
        tvScope.text = if (state.scopeName.isBlank()) "" else "${state.scopeName}$selfNote"

        layoutSummary.removeAllViews()
        layoutRuns.removeAllViews()
        if (state.isLoading) return

        if (state.totalRuns == 0 && state.error == null) {
            addSummaryRow("No runs in this range", "", "")
            tvRunsHeader.text = "Runs"
            return
        }

        val pct: (Int, Int) -> String = { n, base ->
            if (base > 0) "${Math.round(n * 100.0 / base)}%" else "—"
        }
        addSummaryRow("🏃 Total runs", "${state.totalRuns}", "")
        addSummaryRow("\uD83D\uDCE6 Total parcels", "${state.totalParcels}", "")
        addSummaryRow("\uD83D\uDCDE Verify requested", "${state.verifyRequested}", pct(state.verifyRequested, state.totalParcels))
        addSummaryRow("✅ Validated", "${state.validated}", pct(state.validated, state.verifyRequested))
        addSummaryRow("\uD83D\uDD12 Verified (hold/return)", "${state.verified}", pct(state.verified, state.validated))
        addSummaryRow("\uD83D\uDE9A Delivery request", "${state.deliveryRequest}", pct(state.deliveryRequest, state.validated))
        addSummaryRow("\uD83C\uDFC6 Achievement", "${state.achievement}", pct(state.achievement, state.deliveryRequest))
        addSummaryRow("🚫 Not delivered", "${state.notDelivered}", pct(state.notDelivered, state.deliveryRequest))
        addSummaryRow("➖ Previous days", "${state.carried}", "")
        addSummaryRow("⚪ No request", "${state.noRequest}", pct(state.noRequest, state.totalParcels))
        if (state.truncated) addSummaryRow("⚠️ Showing first ${state.runs.size} runs", "", "")

        tvRunsHeader.text = "Runs (${state.runs.size})"
        val dateFmt = SimpleDateFormat("dd MMM", Locale.ENGLISH)
        state.runs.forEach { run ->
            layoutRuns.addView(runCard(run, dateFmt))
        }
    }

    private fun addSummaryRow(label: String, count: String, percent: String) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(2, 7, 2, 7)
        }
        val tvLabel = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = label
            textSize = 13f
        }
        val tvPct = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            text = percent
            textSize = 12f
            setPadding(0, 0, 16, 0)
        }
        val tvCount = TextView(requireContext()).apply {
            text = count
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(tvLabel)
        if (percent.isNotBlank()) row.addView(tvPct)
        row.addView(tvCount)
        layoutSummary.addView(row)
    }

    private fun runCard(run: BranchRunRow, dateFmt: SimpleDateFormat): View {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_rounded)
            setPadding(12, 10, 12, 10)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = 8
            layoutParams = lp
        }
        val dateStr = runCatching {
            val d = DhakaTime.sdf("yyyyMMdd").parse(run.dateKey)
            if (d != null) dateFmt.format(d) else run.dateKey
        }.getOrDefault(run.dateKey)
        val header = TextView(requireContext()).apply {
            text = "${run.runId} · $dateStr · ${run.agentName}"
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val line = TextView(requireContext()).apply {
            text = "\uD83D\uDCE6 ${run.total} · \uD83D\uDCDE ${run.verifyRequested} · " +
                "✅ ${run.validated} (\uD83D\uDD12 ${run.verified}) · " +
                "\uD83D\uDE9A ${run.deliveryRequest} · \uD83C\uDFC6 ${run.achievement}" +
                if (run.notDelivered > 0 || run.carried > 0 || run.noRequest > 0)
                    " · 🚫 ${run.notDelivered} · ➖ ${run.carried} · ⚪ ${run.noRequest}"
                else ""
            textSize = 12f
        }
        card.addView(header)
        card.addView(line)
        return card
    }
}
