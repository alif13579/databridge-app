package com.cloudx.databridge

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.text.NumberFormat
import java.util.Locale

/**
 * Petty Cash Management — Dashboard (Accounts view).
 *
 * Wired to PettyCashViewModel: real requests/deposits/wallet balance for
 * the branch, live settlement queue (requests in PC_STATUS_APPROVED,
 * waiting for Accounts to settle).
 *
 * Entry point: reached from the drawer's "Petty Cash" item (top-level, see
 * MainActivity), or from Cash Management's related-feature card.
 */
class PettyCashDashboardFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

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
        val btnNewRequest = view.findViewById<View>(R.id.btnPcDashboardNewRequest)
        // Only roles explicitly granted "petty_cash_requester" (Access Manager)
        // can submit new requests — e.g. Pickup Agent, Delivery Agent. Whether
        // someone can also see this whole Dashboard (nav_petty_cash) is a
        // separate permission; this button doesn't assume the two overlap.
        btnNewRequest.isVisible = RbacManager.hasPermission("petty_cash_requester")
        btnNewRequest.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashRequestCreateFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        swipeRefresh.setOnRefreshListener { viewModel.load(branchId) }

        tvViewAllQueue.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashPendingSettlementFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        view.findViewById<View>(R.id.cardPcTotalFund).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashWalletSummaryFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        wireQuickActions(view)

        viewModel.state.observe(viewLifecycleOwner) { state -> render(state) }
        if (branchId.isBlank()) {
            render(PettyCashState.Error("No branch selected"))
        } else {
            viewModel.load(branchId)
        }
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        val formatted = NumberFormat.getNumberInstance(Locale.US).format(whole)
        return "\u09F3$formatted"
    }

    private fun render(state: PettyCashState) {
        val root = view ?: return
        val pbLoading = root.findViewById<View>(R.id.pbPcDashboardLoading)
        val layoutError = root.findViewById<View>(R.id.layoutPcDashboardError)

        swipeRefresh.isRefreshing = false

        when (state) {
            is PettyCashState.Loading -> {
                pbLoading.isVisible = true
                layoutError.isVisible = false
                layoutContent.isVisible = false
            }
            is PettyCashState.Error -> {
                pbLoading.isVisible = false
                layoutContent.isVisible = false
                layoutError.isVisible = true
                root.findViewById<TextView>(R.id.tvPcDashboardError).text = state.message
                root.findViewById<View>(R.id.btnPcDashboardRetry).setOnClickListener {
                    if (branchId.isNotBlank()) viewModel.load(branchId)
                }
            }
            is PettyCashState.Success -> {
                pbLoading.isVisible = false
                layoutError.isVisible = false
                layoutContent.isVisible = true
                renderSuccess(root, state)
            }
        }
    }

    private fun renderSuccess(root: View, state: PettyCashState.Success) {
        tvAvailableBalance.text = taka(state.walletBalance)
        tvTotalFund.text = taka(state.totalFund)

        bindStatCard(root, R.id.statPcPendingApproval, "\u23F3", "Pending\nApproval", taka(state.pendingApprovalTotal), "#FFEDD5", "#C2410C")
        bindStatCard(root, R.id.statPcApprovedSettlement, "\u23F3", "Approved\n(Settlement)", taka(state.approvedWaitingSettlementTotal), "#EDE9FE", "#6D28D9")
        bindStatCard(root, R.id.statPcSettledMonth, "\u2705", "Settled\nThis Month", taka(state.settledThisMonthTotal), "#D1FAE5", "#059669")

        val queue = state.pendingSettlementQueue
        tvQueueTitle.text = "Settlement Queue (${queue.size})"
        buildQueueList(queue, canSettle = state.roles.isAccounts)
    }

    private fun buildQueueList(items: List<PettyCashRequest>, canSettle: Boolean) {
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
        items.take(5).forEach { item ->
            val row = layoutInflater.inflate(R.layout.item_petty_cash_queue_row, layoutQueueList, false)
            row.findViewById<TextView>(R.id.tvQueueRowCode).text = item.requestCode
            row.findViewById<TextView>(R.id.tvQueueRowSubtitle).text = "${item.workerName}\n${item.category}"
            row.findViewById<TextView>(R.id.tvQueueRowAmount).text = taka(item.amount)
            row.findViewById<TextView>(R.id.tvQueueRowStatus).text = "POC Approved"

            val btnSettle = row.findViewById<TextView>(R.id.btnQueueRowSettle)
            btnSettle.isVisible = canSettle
            val openDetails = View.OnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, PettyCashSettlementDetailsFragment.newInstance(branchId, item.requestCode))
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }
            row.setOnClickListener(openDetails)
            btnSettle.setOnClickListener(openDetails)

            layoutQueueList.addView(row)
        }
    }

    private fun bindStatCard(root: View, includeId: Int, icon: String, label: String, value: String, bg: String, fg: String) {
        val statRoot = root.findViewById<View>(includeId) ?: return
        val tvIcon = statRoot.findViewById<TextView>(R.id.tvStatCardIcon)
        val tvLabel = statRoot.findViewById<TextView>(R.id.tvStatCardLabel)
        val tvValue = statRoot.findViewById<TextView>(R.id.tvStatCardValue)
        tvIcon.text = icon
        tvIcon.setTextColor(Color.parseColor(fg))
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
            Toast.makeText(requireContext(), "Reports coming soon", Toast.LENGTH_SHORT).show()
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

    private fun roundedDrawable(hexColor: String, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(hexColor))
            cornerRadius = radiusPx.toFloat()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
