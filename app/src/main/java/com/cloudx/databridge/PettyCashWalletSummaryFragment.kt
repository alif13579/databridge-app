package com.cloudx.databridge

import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.text.NumberFormat
import java.util.Locale

/**
 * Petty Cash Management — Wallet Summary (mockup screen 9).
 *
 * Phase 9 of the Petty Cash feature build: layout + static mock data,
 * computed utilization bar, and a cosmetic 30s auto-refresh countdown
 * (re-renders the same mock values — real polling/live listener wiring
 * lands with PettyCashViewModel).
 */
class PettyCashWalletSummaryFragment : Fragment() {

    private var branchId: String = ""
    private var refreshTimer: CountDownTimer? = null

    // Mock wallet figures — consistent with Dashboard's mock data
    private val availableBalance = 141500.0
    private val approvedWaitingSettlement = 21350.0
    private val pendingApproval = 58200.0
    private val settledThisMonth = 278900.0
    private val totalFund = 500000.0

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        fun newInstance(branchId: String): PettyCashWalletSummaryFragment {
            val f = PettyCashWalletSummaryFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_wallet_summary, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        view.findViewById<View>(R.id.btnPcWalletBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        renderMockWallet(view)
        startAutoRefreshCountdown()
    }

    override fun onDestroyView() {
        refreshTimer?.cancel()
        refreshTimer = null
        super.onDestroyView()
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        return "\u09F3${NumberFormat.getNumberInstance(Locale.US).format(whole)}"
    }

    private fun renderMockWallet(root: View) {
        root.findViewById<TextView>(R.id.tvPcWalletAvailableBalance).text = taka(availableBalance)

        bindWalletRow(root, R.id.rowWalletApprovedWaiting, "\u23F3", "Approved (Waiting Settlement)", taka(approvedWaitingSettlement), "#EDE9FE")
        bindWalletRow(root, R.id.rowWalletPendingApproval, "\uD83D\uDD34", "Pending Approval", taka(pendingApproval), "#FFEDD5")
        bindWalletRow(root, R.id.rowWalletSettledMonth, "\u2705", "Settled This Month", taka(settledThisMonth), "#D1FAE5")
        bindWalletRow(root, R.id.rowWalletTotalFund, "\uD83D\uDCE6", "Total Fund", taka(totalFund), "#DBEAFE")

        val utilization = (settledThisMonth / totalFund * 100)
        val utilizationRounded = Math.round(utilization * 10) / 10.0

        val bar = root.findViewById<View>(R.id.viewPcWalletUtilizationBar)
        bar.post {
            val parentWidth = (bar.parent as View).width
            val lp = bar.layoutParams
            lp.width = (parentWidth * (utilization / 100)).toInt()
            bar.layoutParams = lp
        }

        root.findViewById<TextView>(R.id.tvPcWalletUtilizationLabel).text =
            "${utilizationRounded}% Used \u2022 ${taka(settledThisMonth)} / ${taka(totalFund)}"
    }

    private fun bindWalletRow(root: View, includeId: Int, icon: String, label: String, value: String, bg: String) {
        val row = root.findViewById<View>(includeId)
        val tvIcon = row.findViewById<TextView>(R.id.tvWalletRowIcon)
        tvIcon.text = icon
        tvIcon.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor(bg))
            cornerRadius = dp(10).toFloat()
        }
        row.findViewById<TextView>(R.id.tvWalletRowLabel).text = label
        row.findViewById<TextView>(R.id.tvWalletRowValue).text = value
    }

    private fun startAutoRefreshCountdown() {
        // The mockup shows a live "Auto refresh in 30 sec" countdown. This is
        // cosmetic for now — it just re-renders the same mock values every
        // cycle. Real implementation swaps this for a Firebase
        // ValueEventListener once PettyCashViewModel exists, and this timer
        // goes away entirely.
        refreshTimer?.cancel()
        refreshTimer = object : CountDownTimer(30_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {}
            override fun onFinish() {
                if (isAdded) {
                    renderMockWallet(requireView())
                    startAutoRefreshCountdown()
                }
            }
        }.start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
