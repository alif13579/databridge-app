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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.text.NumberFormat
import java.util.Locale

/**
 * Petty Cash Management — Pending Settlement List (mockup screen 3).
 *
 * Phase 2 of the Petty Cash feature build: layout + static mock data + working
 * All/High/Normal tab filter. Firebase wiring lands once PettyCashViewModel is
 * built (aggregating PC_STATUS_APPROVED requests by branch).
 */
class PettyCashPendingSettlementFragment : Fragment() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var layoutTabs: LinearLayout
    private lateinit var layoutList: LinearLayout
    private lateinit var pbLoading: View

    private var branchId: String = ""
    private var selectedFilter: String = FILTER_ALL

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val FILTER_ALL = "all"
        private const val FILTER_HIGH = "high"
        private const val FILTER_NORMAL = "normal"

        fun newInstance(branchId: String): PettyCashPendingSettlementFragment {
            val f = PettyCashPendingSettlementFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    private data class MockSettlement(
        val code: String,
        val worker: String,
        val category: String,
        val amount: Double,
        val priority: String, // PC_PRIORITY_HIGH or PC_PRIORITY_NORMAL
        val approvedAt: String,
        val approvedBy: String
    )

    private val mockData = listOf(
        MockSettlement("REQ-2401", "Hasib Khan", "Travel Expense", 1250.0, PC_PRIORITY_HIGH, "01 Aug, 11:25 AM", "Moin Uddin (POC)"),
        MockSettlement("REQ-2400", "Salman Khan", "Fuel Expense", 950.0, PC_PRIORITY_NORMAL, "01 Aug, 11:10 AM", "Moin Uddin (POC)"),
        MockSettlement("REQ-2399", "Jannatul", "Stationery", 620.0, PC_PRIORITY_NORMAL, "01 Aug, 10:55 AM", "Moin Uddin (POC)"),
        MockSettlement("REQ-2398", "Riya Akter", "Office Supplies", 850.0, PC_PRIORITY_NORMAL, "01 Aug, 10:40 AM", "Moin Uddin (POC)")
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_pending_settlement, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        swipeRefresh = view.findViewById(R.id.swipeRefreshPcPending)
        layoutTabs   = view.findViewById(R.id.layoutPcPendingTabs)
        layoutList   = view.findViewById(R.id.layoutPcPendingList)
        pbLoading    = view.findViewById(R.id.pbPcPendingLoading)

        view.findViewById<View>(R.id.btnPcPendingBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btnPcPendingFilter).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashFilterFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        swipeRefresh.setOnRefreshListener {
            swipeRefresh.isRefreshing = false
            renderList()
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
        val highCount = mockData.count { it.priority == PC_PRIORITY_HIGH }
        val normalCount = mockData.count { it.priority == PC_PRIORITY_NORMAL }
        val tabs = listOf(
            Pair(FILTER_ALL, "All (${mockData.size})"),
            Pair(FILTER_HIGH, "High ($highCount)"),
            Pair(FILTER_NORMAL, "Normal ($normalCount)")
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
        pbLoading.isVisible = false
        val filtered = when (selectedFilter) {
            FILTER_HIGH -> mockData.filter { it.priority == PC_PRIORITY_HIGH }
            FILTER_NORMAL -> mockData.filter { it.priority == PC_PRIORITY_NORMAL }
            else -> mockData
        }
        layoutList.removeAllViews()
        if (filtered.isEmpty()) {
            layoutList.addView(TextView(requireContext()).apply {
                text = "No pending settlements."
                textSize = 13f
                setTextColor(0xFF94A3B8.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(40), dp(8), dp(40))
            })
            return
        }
        filtered.forEach { item ->
            val card = layoutInflater.inflate(R.layout.item_petty_cash_settlement_card, layoutList, false)
            card.findViewById<TextView>(R.id.tvPsCardCode).text = item.code
            card.findViewById<TextView>(R.id.tvPsCardWorker).text = item.worker
            card.findViewById<TextView>(R.id.tvPsCardCategory).text = item.category
            card.findViewById<TextView>(R.id.tvPsCardAmount).text = taka(item.amount)
            card.findViewById<TextView>(R.id.tvPsCardApprovedInfo).text = "POC Approved: ${item.approvedAt}"
            card.findViewById<TextView>(R.id.tvPsCardApprovedBy).text = "Approved by: ${item.approvedBy}"

            val tvPriority = card.findViewById<TextView>(R.id.tvPsCardPriority)
            if (item.priority == PC_PRIORITY_HIGH) {
                tvPriority.isVisible = true
                tvPriority.text = "High"
            } else {
                tvPriority.isVisible = false
            }

            val openDetails = View.OnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, PettyCashSettlementDetailsFragment.newInstance(branchId, item.code))
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }
            card.setOnClickListener(openDetails)
            card.findViewById<TextView>(R.id.btnPsCardSettle).setOnClickListener(openDetails)

            layoutList.addView(card)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
