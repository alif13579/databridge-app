package com.cloudx.databridge

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Petty Cash Management — Settlement History (mockup screen 7).
 *
 * Wired to PettyCashViewModel: real settled requests (PC_STATUS_SETTLED),
 * with a working All/Today/This Month tab filter based on the expense date
 * (requestedDate) — same basis as every other claims screen. Tapping a row opens Settlement Details (read-only,
 * since the request is already settled — Settlement Details itself hides
 * all action buttons once status is SETTLED).
 */
class PettyCashSettlementHistoryFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private lateinit var layoutTabs: LinearLayout
    private lateinit var layoutList: LinearLayout
    private lateinit var pbLoading: View
    private lateinit var layoutError: View

    private var branchId: String = ""
    private var selectedFilter: String = FILTER_ALL
    private var latestState: PettyCashState.Success? = null
    // Date range in millis (expense-date basis, like every other claims
    // screen — a bill belongs to the month its expense happened in) — 0 = unset.
    private var dateFromMillis: Long = 0L
    private var dateToMillis: Long = 0L

    /** Expense date for range/tab filtering — requestedDate, falling back to
     *  createdAt for old rows submitted before requestedDate existed. */
    private fun expenseDateOf(r: PettyCashRequest): Long =
        if (r.requestedDate != 0L) r.requestedDate else r.createdAt

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val FILTER_ALL = "all"
        private const val FILTER_TODAY = "today"
        private const val FILTER_MONTH = "month"

        fun newInstance(branchId: String): PettyCashSettlementHistoryFragment {
            val f = PettyCashSettlementHistoryFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_settlement_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        layoutTabs = view.findViewById(R.id.layoutPcSetHistTabs)
        layoutList = view.findViewById(R.id.layoutPcSetHistList)
        pbLoading  = view.findViewById(R.id.pbPcSetHistLoading)
        layoutError = view.findViewById(R.id.layoutPcSetHistError)

        view.findViewById<View>(R.id.btnPcSetHistBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btnPcSetHistCalendar).setOnClickListener {
            showDateRangeDialog()
        }
        view.findViewById<View>(R.id.tvPcSetHistRange).setOnClickListener {
            showDateRangeDialog()
        }
        view.findViewById<View>(R.id.tvPcSetHistRangeClear).setOnClickListener {
            clearDateRange()
        }

        buildTabs()
        updateRangeLabel()

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

    private fun formatDateTime(millis: Long): String {
        if (millis == 0L) return "—"
        return BdTime.format("dd MMM yyyy, hh:mm a", millis)
    }

    private fun render(state: PettyCashState) {
        val root = view ?: return
        val scroll = root.findViewById<View>(R.id.scrollPcSetHist)

        when (state) {
            is PettyCashState.Loading -> {
                pbLoading.isVisible = true
                layoutError.isVisible = false
                scroll.isVisible = false
            }
            is PettyCashState.Error -> {
                pbLoading.isVisible = false
                scroll.isVisible = false
                layoutError.isVisible = true
                root.findViewById<TextView>(R.id.tvPcSetHistError).text = state.message
                root.findViewById<View>(R.id.btnPcSetHistRetry).setOnClickListener {
                    if (branchId.isNotBlank()) viewModel.load(branchId)
                }
            }
            is PettyCashState.Success -> {
                if (!state.roles.isAnyApprover) {
                    pbLoading.isVisible = false
                    scroll.isVisible = false
                    layoutError.isVisible = true
                    root.findViewById<TextView>(R.id.tvPcSetHistError).text = "Only approvers can view settlement history"
                    root.findViewById<View>(R.id.btnPcSetHistRetry).isVisible = false
                    return
                }
                pbLoading.isVisible = false
                layoutError.isVisible = false
                scroll.isVisible = true
                latestState = state
                buildTabs()
                renderList()
            }
        }
    }

    private fun settledRequests(): List<PettyCashRequest> =
        latestState?.requests
            ?.filter { it.status == PC_STATUS_SETTLED }
            ?.sortedByDescending { expenseDateOf(it) }
            ?: emptyList()

    private fun buildTabs() {
        layoutTabs.removeAllViews()
        // Dynamic over the date-ranged set: counts reflect the range.
        // A tab with zero claims in range falls back to All.
        val all = dateScoped()
        val hasToday = all.any { isToday(expenseDateOf(it)) }
        val hasMonth = all.any { isThisMonth(expenseDateOf(it)) }
        if ((selectedFilter == FILTER_TODAY && !hasToday) ||
            (selectedFilter == FILTER_MONTH && !hasMonth)) {
            selectedFilter = FILTER_ALL
        }
        val tabs = listOf(
            Pair(FILTER_ALL, "All (${all.size})"),
            Pair(FILTER_TODAY, "Today (${all.count { isToday(expenseDateOf(it)) }})"),
            Pair(FILTER_MONTH, "This Month (${all.count { isThisMonth(expenseDateOf(it)) }})")
        )
        tabs.forEach { (key, label) ->
            val tab = layoutInflater.inflate(R.layout.item_petty_cash_filter_tab, layoutTabs, false) as TextView
            tab.text = label
            tab.setOnClickListener {
                selectedFilter = key
                buildTabs()
                renderList()
            }
            styleTab(tab, key == selectedFilter)
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

    private fun isToday(millis: Long): Boolean {
        if (millis == 0L) return false
        val now = BdTime.cal()
        val then = BdTime.cal().apply { timeInMillis = millis }
        return now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) &&
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    }

    private fun isThisMonth(millis: Long): Boolean {
        if (millis == 0L) return false
        val now = BdTime.cal()
        val then = BdTime.cal().apply { timeInMillis = millis }
        return now.get(Calendar.MONTH) == then.get(Calendar.MONTH) &&
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    }

    private fun renderList() {
        val tabFiltered = when (selectedFilter) {
            FILTER_TODAY -> dateScoped().filter { isToday(expenseDateOf(it)) }
            FILTER_MONTH -> dateScoped().filter { isThisMonth(expenseDateOf(it)) }
            else -> dateScoped()
        }
        val filtered = tabFiltered

        layoutList.removeAllViews()
        if (filtered.isEmpty()) {
            layoutList.addView(TextView(requireContext()).apply {
                text = "No settlements found."
                textSize = 13f
                setTextColor(0xFF94A3B8.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(40), dp(8), dp(40))
            })
            return
        }

        filtered.forEach { item ->
            val row = layoutInflater.inflate(R.layout.item_petty_cash_settlement_history_row, layoutList, false)
            row.findViewById<TextView>(R.id.tvSetHistRowDateTime).text = formatDateTime(item.settledAt)
            row.findViewById<TextView>(R.id.tvSetHistRowTag).apply {
                text = item.settledPaymentMethod.ifBlank { "—" }
                background = androidx.core.content.ContextCompat.getDrawable(
                    requireContext(),
                    if (item.settledPaymentMethod == "Bank") R.drawable.bg_pc_tag_bank else R.drawable.bg_pc_tag_cash
                )
            }
            row.findViewById<TextView>(R.id.tvSetHistRowCode).text = item.requestCode
            row.findViewById<TextView>(R.id.tvSetHistRowWorker).text = item.requesterName
            // Settled figure, not requested: POC/Accounts often adjust down.
            row.findViewById<TextView>(R.id.tvSetHistRowBalanceAfter).text = taka(item.settledAmount.takeIf { it > 0 } ?: item.amount)

            row.setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, PettyCashSettlementDetailsFragment.newInstance(branchId, item.requestCode))
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }

            layoutList.addView(row)
        }
    }

    /** Date-ranged working set (expense-date basis, like the tabs). No range = all time. */
    private fun dateScoped(): List<PettyCashRequest> {
        val all = settledRequests()
        if (dateFromMillis == 0L && dateToMillis == 0L) return all
        return all.filter { r ->
            val d = expenseDateOf(r)
            (dateFromMillis == 0L || d >= dateFromMillis) &&
                (dateToMillis == 0L || d <= dateToMillis)
        }
    }

    private fun shortDate(millis: Long): String =
        BdTime.format("dd MMM", millis)

    /** Toolbar range readout + one-tap ✕ undo. */
    private fun updateRangeLabel() {
        val root = view ?: return
        val hasRange = dateFromMillis != 0L || dateToMillis != 0L
        root.findViewById<TextView>(R.id.tvPcSetHistRange).text = if (hasRange) {
            val from = if (dateFromMillis != 0L) shortDate(dateFromMillis) else "…"
            val to = if (dateToMillis != 0L) shortDate(dateToMillis) else "…"
            "$from–$to"
        } else "All Time"
        root.findViewById<View>(R.id.tvPcSetHistRangeClear).isVisible = hasRange
    }

    /** One-tap undo for the date range. */
    private fun clearDateRange() {
        dateFromMillis = 0L
        dateToMillis = 0L
        selectedFilter = FILTER_ALL
        buildTabs()
        updateRangeLabel()
        renderList()
    }

    /** From/To dialog (default today–today on fresh open) + Apply/Cancel/Clear. */
    private fun showDateRangeDialog() {
        val ctx = requireContext()
        var fromDay = BdTime.cal().apply {
            if (dateFromMillis != 0L) timeInMillis = dateFromMillis
        }
        var toDay = BdTime.cal().apply {
            if (dateToMillis != 0L) timeInMillis = dateToMillis
        }
        if (dateFromMillis == 0L) startOfDayLocal(fromDay)
        if (dateToMillis == 0L) endOfDayLocal(toDay)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        fun dayRow(label: String, initial: String): LinearLayout {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(TextView(ctx).apply {
                text = label
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#334155"))
                layoutParams = LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(ctx).apply {
                tag = label
                text = initial
                textSize = 14f
                setTextColor(Color.parseColor("#0F172A"))
                setBackgroundResource(R.drawable.bg_pc_tab_inactive)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
        val presetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        fun presetButton(label: String): TextView {
            return TextView(ctx).apply {
                text = label
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#0F766E"))
                setBackgroundResource(R.drawable.bg_pc_tab_inactive)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
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

        fun applyPreset(range: Pair<Calendar, Calendar>) {
            fromDay = range.first
            toDay = range.second
            fromValue.text = shortDate(fromDay.timeInMillis)
            toValue.text = shortDate(toDay.timeInMillis)
        }
        btnMonth.setOnClickListener { applyPreset(monthRange()) }
        btnBill.setOnClickListener { applyPreset(billCycleRange(0)) }
        btnLastBill.setOnClickListener { applyPreset(billCycleRange(-1)) }

        val dialog = android.app.AlertDialog.Builder(ctx)
            .setTitle("Select date range")
            .setView(root)
            .setPositiveButton("Apply", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear", null)
            .create()

        fun pickDay(isFrom: Boolean) {
            val base = if (isFrom) fromDay else toDay
            android.app.DatePickerDialog(ctx, { _, y, m, d ->
                val picked = BdTime.cal().apply { set(y, m, d) }
                if (isFrom) {
                    fromDay = picked
                    startOfDayLocal(fromDay)
                    fromValue.text = shortDate(fromDay.timeInMillis)
                } else {
                    toDay = picked
                    endOfDayLocal(toDay)
                    toValue.text = shortDate(toDay.timeInMillis)
                }
            }, base.get(Calendar.YEAR), base.get(Calendar.MONTH),
                base.get(Calendar.DAY_OF_MONTH)).show()
        }
        fromValue.setOnClickListener { pickDay(true) }
        toValue.setOnClickListener { pickDay(false) }

        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (toDay.timeInMillis < fromDay.timeInMillis) {
                android.widget.Toast.makeText(ctx, "End date cannot be before start date", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dateFromMillis = fromDay.timeInMillis
            dateToMillis = toDay.timeInMillis
            buildTabs()
            updateRangeLabel()
            renderList()
            android.widget.Toast.makeText(ctx,
                "📅 ${shortDate(fromDay.timeInMillis)}–${shortDate(toDay.timeInMillis)}",
                android.widget.Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            dialog.dismiss()
            clearDateRange()
        }
    }

    /** Normal calendar month: 1st → today. */
    private fun monthRange(): Pair<Calendar, Calendar> {
        val from = BdTime.cal().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            startOfDayLocal(this)
        }
        val to = BdTime.cal().apply { endOfDayLocal(this) }
        return from to to
    }

    /** Business-month bill cycle (26th–25th). offset 0 = running cycle
     *  containing today, -1 = previous cycle. */
    private fun billCycleRange(offset: Int): Pair<Calendar, Calendar> {
        val today = BdTime.cal()
        val thisCycleStartThisMonth = today.get(Calendar.DAY_OF_MONTH) >= 26
        val from = BdTime.cal().apply {
            set(Calendar.DAY_OF_MONTH, 26)
            if (!thisCycleStartThisMonth) add(Calendar.MONTH, -1)
            add(Calendar.MONTH, offset)
            startOfDayLocal(this)
        }
        val to = BdTime.cal().apply {
            timeInMillis = from.timeInMillis
            add(Calendar.MONTH, 1)
            set(Calendar.DAY_OF_MONTH, 25)
            endOfDayLocal(this)
        }
        return from to to
    }

    private fun startOfDayLocal(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    private fun endOfDayLocal(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
