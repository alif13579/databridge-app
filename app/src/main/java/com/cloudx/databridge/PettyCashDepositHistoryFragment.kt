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
 * Petty Cash Management — Deposit History (mockup screen 6).
 *
 * Phase 6 of the Petty Cash feature build: layout + static mock data + working
 * All/Cash/Bank/Adjustment tab filter. Firebase wiring (reading
 * petty_cash/{branch}/wallet/deposits ordered by timestamp) lands once
 * PettyCashViewModel is built.
 */
class PettyCashDepositHistoryFragment : Fragment() {

    private lateinit var layoutTabs: LinearLayout
    private lateinit var layoutList: LinearLayout

    private var branchId: String = ""
    private var selectedFilter: String = FILTER_ALL

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

    private data class MockDeposit(
        val dateTime: String,
        val source: String, // Cash, Bank, Adjustment
        val amount: Double,
        val ref: String,
        val balanceAfter: Double
    )

    private val mockData = listOf(
        MockDeposit("01 Aug 2025, 11:50 AM", FILTER_BANK, 100000.0, "Ref: TRX-10081", 242750.0),
        MockDeposit("29 Jul 2025, 04:20 PM", FILTER_CASH, 80000.0, "Ref: —", 142750.0),
        MockDeposit("25 Jul 2025, 10:10 AM", FILTER_BANK, 100000.0, "Ref: TRX-09872", 62750.0),
        MockDeposit("20 Jul 2025, 02:30 PM", FILTER_ADJUSTMENT, 50000.0, "Ref: ADJ-0023", 12750.0),
        MockDeposit("15 Jul 2025, 11:15 AM", FILTER_CASH, 25000.0, "Ref: —", -37250.0)
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_deposit_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        layoutTabs = view.findViewById(R.id.layoutPcDepHistTabs)
        layoutList = view.findViewById(R.id.layoutPcDepHistList)

        view.findViewById<View>(R.id.btnPcDepHistBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btnPcDepHistFilter).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashFilterFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        view.findViewById<TextView>(R.id.tvPcDepHistTotalMonth).text =
            taka(mockData.filter { it.dateTime.contains("Aug 2025") }.sumOf { it.amount })

        buildTabs()
        renderList()
    }

    private fun taka(amount: Double): String {
        val sign = if (amount < 0) "-" else ""
        val whole = Math.round(Math.abs(amount))
        return "$sign\u09F3${NumberFormat.getNumberInstance(Locale.US).format(whole)}"
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

    private fun tagDrawableFor(source: String): Int = when (source) {
        FILTER_BANK -> R.drawable.bg_pc_tag_bank
        FILTER_ADJUSTMENT -> R.drawable.bg_pc_tag_adjustment
        else -> R.drawable.bg_pc_tag_cash
    }

    private fun renderList() {
        val filtered = if (selectedFilter == FILTER_ALL) mockData else mockData.filter { it.source == selectedFilter }
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
            row.findViewById<TextView>(R.id.tvDepHistRowDateTime).text = item.dateTime
            row.findViewById<TextView>(R.id.tvDepHistRowTag).apply {
                text = item.source
                background = androidx.core.content.ContextCompat.getDrawable(requireContext(), tagDrawableFor(item.source))
            }
            row.findViewById<TextView>(R.id.tvDepHistRowAmount).text = "+ ${taka(item.amount)}"
            row.findViewById<TextView>(R.id.tvDepHistRowRef).text = item.ref
            row.findViewById<TextView>(R.id.tvDepHistRowBalanceAfter).text = taka(item.balanceAfter)

            layoutList.addView(row)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
