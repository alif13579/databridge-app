package com.cloudx.databridge

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.text.NumberFormat
import java.util.Locale

/**
 * Petty Cash Management — Settlement History (mockup screen 7).
 *
 * Phase 7 of the Petty Cash feature build: layout + static mock data + working
 * All/Today/This Month tab filter. Firebase wiring (reading
 * petty_cash/{branch}/requests filtered to PC_STATUS_SETTLED, ordered by
 * settledAt) lands once PettyCashViewModel is built.
 */
class PettyCashSettlementHistoryFragment : Fragment() {

    private lateinit var layoutTabs: LinearLayout
    private lateinit var layoutList: LinearLayout

    private var branchId: String = ""
    private var selectedFilter: String = FILTER_ALL

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

    private data class MockSettled(
        val dateTime: String,
        val code: String,
        val worker: String,
        val paymentMethod: String, // Cash, Bank
        val balanceAfter: Double,
        val isToday: Boolean,
        val isThisMonth: Boolean
    )

    private val mockData = listOf(
        MockSettled("01 Aug 2025, 12:15 PM", "REQ-2401", "Hasib Khan", "Cash", 141500.0, isToday = true, isThisMonth = true),
        MockSettled("31 Jul 2025, 04:30 PM", "REQ-2396", "Jannatul", "Cash", 142750.0, isToday = false, isThisMonth = true),
        MockSettled("31 Jul 2025, 03:10 PM", "REQ-2393", "Salman Khan", "Cash", 143370.0, isToday = false, isThisMonth = true),
        MockSettled("30 Jul 2025, 01:40 PM", "REQ-2388", "Riya Akter", "Cash", 144620.0, isToday = false, isThisMonth = true),
        MockSettled("29 Jul 2025, 02:20 PM", "REQ-2380", "Hasib Khan", "Bank", 145470.0, isToday = false, isThisMonth = true)
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_settlement_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        layoutTabs = view.findViewById(R.id.layoutPcSetHistTabs)
        layoutList = view.findViewById(R.id.layoutPcSetHistList)

        view.findViewById<View>(R.id.btnPcSetHistBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btnPcSetHistFilter).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashFilterFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        buildTabs()
        renderList()
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        return "\u09F3${NumberFormat.getNumberInstance(Locale.US).format(whole)}"
    }

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

    private fun renderList() {
        val filtered = when (selectedFilter) {
            FILTER_TODAY -> mockData.filter { it.isToday }
            FILTER_MONTH -> mockData.filter { it.isThisMonth }
            else -> mockData
        }
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
            row.findViewById<TextView>(R.id.tvSetHistRowDateTime).text = item.dateTime
            row.findViewById<TextView>(R.id.tvSetHistRowTag).apply {
                text = item.paymentMethod
                background = androidx.core.content.ContextCompat.getDrawable(
                    requireContext(),
                    if (item.paymentMethod == "Bank") R.drawable.bg_pc_tag_bank else R.drawable.bg_pc_tag_cash
                )
            }
            row.findViewById<TextView>(R.id.tvSetHistRowCode).text = item.code
            row.findViewById<TextView>(R.id.tvSetHistRowWorker).text = item.worker
            row.findViewById<TextView>(R.id.tvSetHistRowBalanceAfter).text = taka(item.balanceAfter)

            row.setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, PettyCashSettlementDetailsFragment.newInstance(branchId, item.code))
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }

            layoutList.addView(row)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
