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
 * with a working All/Today/This Month tab filter based on the real
 * settledAt timestamp. Tapping a row opens Settlement Details (read-only,
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
    private var advancedFilter: PettyCashFilterState = PettyCashFilterState()

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
        view.findViewById<View>(R.id.btnPcSetHistFilter).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashFilterFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        parentFragmentManager.setFragmentResultListener(PettyCashFilterState.FRAGMENT_RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            val stateBundle = bundle.getBundle(PettyCashFilterState.BUNDLE_KEY_STATE)
            advancedFilter = stateBundle?.let { PettyCashFilterState.fromBundle(it) } ?: PettyCashFilterState()
            renderList()
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
        val whole = Math.round(amount)
        return "\u09F3${NumberFormat.getNumberInstance(Locale.US).format(whole)}"
    }

    private fun formatDateTime(millis: Long): String {
        if (millis == 0L) return "—"
        return SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(millis))
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
            ?.sortedByDescending { it.settledAt }
            ?: emptyList()

    private fun buildTabs() {
        layoutTabs.removeAllViews()
        val tabs = listOf(
            Pair(FILTER_ALL, "All"),
            Pair(FILTER_TODAY, "Today"),
            Pair(FILTER_MONTH, "This Month")
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
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        return now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) &&
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    }

    private fun isThisMonth(millis: Long): Boolean {
        if (millis == 0L) return false
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        return now.get(Calendar.MONTH) == then.get(Calendar.MONTH) &&
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    }

    private fun renderList() {
        val all = settledRequests()
        val tabFiltered = when (selectedFilter) {
            FILTER_TODAY -> all.filter { isToday(it.settledAt) }
            FILTER_MONTH -> all.filter { isThisMonth(it.settledAt) }
            else -> all
        }
        val filtered = if (advancedFilter.isActive) tabFiltered.filter { advancedFilter.matches(it) } else tabFiltered

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
            row.findViewById<TextView>(R.id.tvSetHistRowWorker).text = item.workerName
            row.findViewById<TextView>(R.id.tvSetHistRowBalanceAfter).text = taka(item.amount)

            row.setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, PettyCashSettlementDetailsFragment.newInstance(branchId, item.requestCode))
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }

            layoutList.addView(row)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
