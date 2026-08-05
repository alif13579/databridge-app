package com.cloudx.databridge

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
 * Petty Cash Management — Dashboard (Accounts view).
 *
 * Phase 1 of the Petty Cash feature build: layout + static mock data matching
 * the approved mockup (screen 1 "Accounts Dashboard"). Firebase wiring lands
 * in a later phase once PettyCashViewModel is built.
 *
 * Entry point: reached from CashManagementHomeFragment as a sub-section.
 */
class PettyCashDashboardFragment : Fragment() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var layoutContent: View
    private lateinit var tvAvailableBalance: TextView
    private lateinit var tvTotalFund: TextView
    private lateinit var tvQueueTitle: TextView
    private lateinit var tvViewAllQueue: TextView
    private lateinit var layoutQueueList: LinearLayout

    private var branchId: String = ""

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        fun newInstance(branchId: String): PettyCashDashboardFragment {
            val f = PettyCashDashboardFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        swipeRefresh    = view.findViewById(R.id.swipeRefreshPcDashboard)
        layoutContent   = view.findViewById(R.id.layoutPcDashboardContent)
        tvAvailableBalance = view.findViewById(R.id.tvPcAvailableBalance)
        tvTotalFund     = view.findViewById(R.id.tvPcTotalFund)
        tvQueueTitle    = view.findViewById(R.id.tvPcQueueTitle)
        tvViewAllQueue  = view.findViewById(R.id.tvPcViewAllQueue)
        layoutQueueList = view.findViewById(R.id.layoutPcQueueList)

        view.findViewById<View>(R.id.btnPcDashboardBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        swipeRefresh.setOnRefreshListener {
            swipeRefresh.isRefreshing = false
            loadMockData()
        }

        wireQuickActions(view)
        loadMockData()
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        val formatted = NumberFormat.getNumberInstance(Locale.US).format(whole)
        return "\u09F3$formatted"
    }

    // ── Mock data (Phase 1) — will be replaced by PettyCashViewModel + Firebase ──
    private fun loadMockData() {
        view?.findViewById<View>(R.id.pbPcDashboardLoading)?.isVisible = false
        layoutContent.isVisible = true

        tvAvailableBalance.text = taka(142750.0)
        tvTotalFund.text = taka(500000.0)

        bindStatCard(R.id.statPcPendingApproval, "\u23F3", "Pending\nApproval", taka(58200.0), "#FFEDD5", "#C2410C")
        bindStatCard(R.id.statPcApprovedSettlement, "\u23F3", "Approved\n(Settlement)", taka(21350.0), "#EDE9FE", "#6D28D9")
        bindStatCard(R.id.statPcSettledMonth, "\u2705", "Settled\nThis Month", taka(278900.0), "#D1FAE5", "#059669")

        tvQueueTitle.text = "Settlement Queue (10)"

        val mockQueue = listOf(
            QueueRowData("REQ-2401", "Hasib Khan", "Travel Expense", 1250.0, "POC Approved"),
            QueueRowData("REQ-2400", "Salman Khan", "Fuel Expense", 950.0, "POC Approved")
        )
        buildQueueList(mockQueue)

        tvViewAllQueue.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashPendingSettlementFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
    }

    private data class QueueRowData(val code: String, val worker: String, val category: String, val amount: Double, val status: String)

    private fun buildQueueList(items: List<QueueRowData>) {
        layoutQueueList.removeAllViews()
        if (items.isEmpty()) {
            layoutQueueList.addView(TextView(requireContext()).apply {
                text = "No pending settlements."
                textSize = 13f
                setTextColor(0xFF94A3B8.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(20), dp(8), dp(20))
            })
            return
        }
        items.forEach { item ->
            val row = layoutInflater.inflate(R.layout.item_petty_cash_queue_row, layoutQueueList, false)
            row.findViewById<TextView>(R.id.tvQueueRowCode).text = item.code
            row.findViewById<TextView>(R.id.tvQueueRowSubtitle).text = "${item.worker}\n${item.category}"
            row.findViewById<TextView>(R.id.tvQueueRowAmount).text = taka(item.amount)
            row.findViewById<TextView>(R.id.tvQueueRowStatus).text = item.status
            row.findViewById<TextView>(R.id.btnQueueRowSettle).setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, PettyCashSettlementDetailsFragment.newInstance(branchId, item.code))
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }
            layoutQueueList.addView(row)
        }
    }

    private fun bindStatCard(includeId: Int, icon: String, label: String, value: String, bg: String, fg: String) {
        val root = view?.findViewById<View>(includeId) ?: return
        val tvIcon = root.findViewById<TextView>(R.id.tvStatCardIcon)
        val tvLabel = root.findViewById<TextView>(R.id.tvStatCardLabel)
        val tvValue = root.findViewById<TextView>(R.id.tvStatCardValue)
        tvIcon.text = icon
        tvIcon.setTextColor(android.graphics.Color.parseColor(fg))
        tvIcon.background = roundedDrawable(bg, dp(8))
        tvLabel.text = label
        tvValue.text = value
    }

    private fun wireQuickActions(root: View) {
        bindQuickAction(root.findViewById(R.id.actionPcDepositFund), "\uD83D\uDCB0", "Deposit\nFund") {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashDepositFundFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        bindQuickAction(root.findViewById(R.id.actionPcPendingSettlement), "\uD83D\uDCCB", "Pending\nSettlement") {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashPendingSettlementFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        bindQuickAction(root.findViewById(R.id.actionPcAllRequests), "\uD83D\uDC65", "All\nRequests") {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashAllRequestsFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        bindQuickAction(root.findViewById(R.id.actionPcReports), "\uD83D\uDCCA", "Reports") {
            // Reports screen not in current mockup batch — placeholder for now.
        }
    }

    private fun bindQuickAction(root: View, icon: String, label: String, onClick: () -> Unit) {
        val tvIcon = root.findViewById<TextView>(R.id.tvQuickActionIcon)
        val tvLabel = root.findViewById<TextView>(R.id.tvQuickActionLabel)
        tvIcon.text = icon
        tvIcon.background = roundedDrawable("#F1F5F9", dp(12))
        tvLabel.text = label
        root.setOnClickListener { onClick() }
    }

    private fun roundedDrawable(hexColor: String, radiusPx: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor(hexColor))
            cornerRadius = radiusPx.toFloat()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
