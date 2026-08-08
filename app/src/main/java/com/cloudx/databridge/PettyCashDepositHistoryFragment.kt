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
 * Petty Cash Management — Deposit History (mockup screen 6).
 *
 * Wired to PettyCashViewModel: real deposits for the branch (already sorted
 * newest-first by the ViewModel), with a working All/Cash/Bank/Adjustment
 * source tab filter. "Balance After" is computed by walking the full
 * deposit list chronologically rather than trusting a stored value, since
 * older deposit rows created before this field existed wouldn't have it.
 */
class PettyCashDepositHistoryFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private lateinit var layoutTabs: LinearLayout
    private lateinit var layoutList: LinearLayout
    private lateinit var pbLoading: View
    private lateinit var layoutError: View

    private var branchId: String = ""
    private var selectedFilter: String = FILTER_ALL
    private var latestState: PettyCashState.Success? = null
    private var advancedFilter: PettyCashFilterState = PettyCashFilterState()

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val FILTER_ALL = "all"
        private const val FILTER_CASH = "Cash"
        private const val FILTER_BANK = "Bank"
        private const val FILTER_ADJUSTMENT = "Adjustment"

        fun newInstance(branchId: String): PettyCashDepositHistoryFragment {
            val f = PettyCashDepositHistoryFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_deposit_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        layoutTabs = view.findViewById(R.id.layoutPcDepHistTabs)
        layoutList = view.findViewById(R.id.layoutPcDepHistList)
        pbLoading  = view.findViewById(R.id.pbPcDepHistLoading)
        layoutError = view.findViewById(R.id.layoutPcDepHistError)

        view.findViewById<View>(R.id.btnPcDepHistBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btnPcDepHistFilter).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashFilterFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        parentFragmentManager.setFragmentResultListener(PettyCashFilterState.FRAGMENT_RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            val stateBundle = bundle.getBundle(PettyCashFilterState.BUNDLE_KEY_STATE)
            advancedFilter = stateBundle?.let { PettyCashFilterState.fromBundle(it) } ?: PettyCashFilterState()
            latestState?.let { renderList(it) }
        }

        buildTabs()

        viewModel.state.observe(viewLifecycleOwner) { state -> render(state) }
        if (branchId.isBlank()) {
            render(PettyCashState.Error("No branch selected"))
        } else {
            viewModel.load(branchId)
        }
    }

    private fun taka(amount: Double): String {
        val sign = if (amount < 0) "-" else ""
        val whole = Math.round(Math.abs(amount))
        return "$sign\u09F3${NumberFormat.getNumberInstance(Locale.US).format(whole)}"
    }

    private fun formatDateTime(millis: Long): String {
        if (millis == 0L) return "—"
        return SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(millis))
    }

    private fun render(state: PettyCashState) {
        val root = view ?: return
        val scroll = root.findViewById<View>(R.id.scrollPcDepHist)

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
                root.findViewById<TextView>(R.id.tvPcDepHistError).text = state.message
                root.findViewById<View>(R.id.btnPcDepHistRetry).setOnClickListener {
                    if (branchId.isNotBlank()) viewModel.load(branchId)
                }
            }
            is PettyCashState.Success -> {
                pbLoading.isVisible = false
                layoutError.isVisible = false
                scroll.isVisible = true
                latestState = state
                buildTabs()
                renderTotalThisMonth(root, state)
                renderList(state)
            }
        }
    }

    private fun renderTotalThisMonth(root: View, state: PettyCashState.Success) {
        val cal = Calendar.getInstance()
        val currentMonth = cal.get(Calendar.MONTH)
        val currentYear = cal.get(Calendar.YEAR)
        val total = state.deposits.filter {
            cal.timeInMillis = it.timestamp
            cal.get(Calendar.MONTH) == currentMonth && cal.get(Calendar.YEAR) == currentYear
        }.sumOf { it.amount }
        root.findViewById<TextView>(R.id.tvPcDepHistTotalMonth).text = taka(total)
    }

    private fun buildTabs() {
        layoutTabs.removeAllViews()
        val tabs = listOf(
            Pair(FILTER_ALL, "All"),
            Pair(FILTER_CASH, "Cash"),
            Pair(FILTER_BANK, "Bank"),
            Pair(FILTER_ADJUSTMENT, "Adjustment")
        )
        tabs.forEach { (key, label) ->
            val tab = layoutInflater.inflate(R.layout.item_petty_cash_filter_tab, layoutTabs, false) as TextView
            tab.text = label
            tab.setOnClickListener {
                selectedFilter = key
                buildTabs()
                latestState?.let { renderList(it) }
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

    private fun tagDrawableFor(source: String): Int = when (source) {
        FILTER_BANK -> R.drawable.bg_pc_tag_bank
        FILTER_ADJUSTMENT -> R.drawable.bg_pc_tag_adjustment
        else -> R.drawable.bg_pc_tag_cash
    }

    private fun renderList(state: PettyCashState.Success) {
        // Compute a running balance chronologically (oldest -> newest), since
        // the ViewModel sorts `deposits` newest-first for display but we need
        // forward order to get correct running totals.
        val chronological = state.deposits.sortedBy { it.timestamp }
        var running = 0.0
        val balanceAfterById = mutableMapOf<String, Double>()
        chronological.forEach { d ->
            running += d.amount
            balanceAfterById[d.id] = running
        }

        val source = state.deposits // newest-first, as the ViewModel provides
        val tabFiltered = if (selectedFilter == FILTER_ALL) source else source.filter { it.source == selectedFilter }
        val filtered = if (advancedFilter.isActive) tabFiltered.filter { advancedFilter.matches(it) } else tabFiltered

        layoutList.removeAllViews()
        if (filtered.isEmpty()) {
            layoutList.addView(TextView(requireContext()).apply {
                text = "No deposits found."
                textSize = 13f
                setTextColor(0xFF94A3B8.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(40), dp(8), dp(40))
            })
            return
        }

        filtered.forEach { item ->
            val row = layoutInflater.inflate(R.layout.item_petty_cash_deposit_history_row, layoutList, false)
            row.findViewById<TextView>(R.id.tvDepHistRowDateTime).text = formatDateTime(item.timestamp)
            row.findViewById<TextView>(R.id.tvDepHistRowTag).apply {
                text = item.source
                background = androidx.core.content.ContextCompat.getDrawable(requireContext(), tagDrawableFor(item.source))
            }
            row.findViewById<TextView>(R.id.tvDepHistRowAmount).text = "+ ${taka(item.amount)}"
            row.findViewById<TextView>(R.id.tvDepHistRowRef).text =
                "Ref: ${item.reference.ifBlank { "—" }}"
            row.findViewById<TextView>(R.id.tvDepHistRowBalanceAfter).text =
                taka(balanceAfterById[item.id] ?: item.balanceAfter)

            layoutList.addView(row)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
