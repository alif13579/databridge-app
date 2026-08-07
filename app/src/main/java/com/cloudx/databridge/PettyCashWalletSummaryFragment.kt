package com.cloudx.databridge

import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import java.text.NumberFormat
import java.util.Locale

/**
 * Petty Cash Management — Wallet Summary (mockup screen 9).
 *
 * Wired to PettyCashViewModel: real wallet balance and derived aggregates
 * (same PettyCashState.Success computed properties Dashboard uses, so the
 * numbers always agree between the two screens). The mockup's "Auto refresh
 * in 30 sec" countdown now triggers a real viewModel.load() every cycle
 * instead of just re-rendering the same static numbers.
 */
class PettyCashWalletSummaryFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private var branchId: String = ""
    private var refreshTimer: CountDownTimer? = null

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

        viewModel.state.observe(viewLifecycleOwner) { state -> render(state) }
        if (branchId.isBlank()) {
            render(PettyCashState.Error("No branch selected"))
        } else {
            viewModel.load(branchId)
        }

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

    private fun render(state: PettyCashState) {
        val root = view ?: return
        val pbLoading = root.findViewById<View>(R.id.pbPcWalletLoading)
        val layoutError = root.findViewById<View>(R.id.layoutPcWalletError)
        val scroll = root.findViewById<View>(R.id.scrollPcWallet)

        when (state) {
            is PettyCashState.Loading -> {
                // Skip showing the spinner on background auto-refresh reloads —
                // only show it if we don't have any content on screen yet.
                if (scroll.isVisible.not()) {
                    pbLoading.isVisible = true
                    layoutError.isVisible = false
                }
            }
            is PettyCashState.Error -> {
                if (scroll.isVisible.not()) {
                    pbLoading.isVisible = false
                    scroll.isVisible = false
                    layoutError.isVisible = true
                    root.findViewById<TextView>(R.id.tvPcWalletError).text = state.message
                    root.findViewById<View>(R.id.btnPcWalletRetry).setOnClickListener {
                        if (branchId.isNotBlank()) viewModel.load(branchId)
                    }
                }
                // If we already have content on screen, a background refresh
                // failure is silently ignored — the last-known numbers stay up.
            }
            is PettyCashState.Success -> {
                pbLoading.isVisible = false
                layoutError.isVisible = false
                scroll.isVisible = true
                renderWallet(root, state)
            }
        }
    }

    private fun renderWallet(root: View, state: PettyCashState.Success) {
        root.findViewById<TextView>(R.id.tvPcWalletAvailableBalance).text = taka(state.walletBalance)

        bindWalletRow(root, R.id.rowWalletApprovedWaiting, "\u23F3", "Approved (Waiting Settlement)", taka(state.approvedWaitingSettlementTotal), "#EDE9FE")
        bindWalletRow(root, R.id.rowWalletPendingApproval, "\uD83D\uDD34", "Pending Approval", taka(state.pendingApprovalTotal), "#FFEDD5")
        bindWalletRow(root, R.id.rowWalletSettledMonth, "\u2705", "Settled This Month", taka(state.settledThisMonthTotal), "#D1FAE5")
        bindWalletRow(root, R.id.rowWalletTotalFund, "\uD83D\uDCE6", "Total Fund", taka(state.totalFund), "#DBEAFE")

        val utilization = if (state.totalFund > 0) (state.settledThisMonthTotal / state.totalFund * 100) else 0.0
        val utilizationRounded = Math.round(utilization * 10) / 10.0

        val bar = root.findViewById<View>(R.id.viewPcWalletUtilizationBar)
        bar.post {
            val parentWidth = (bar.parent as View).width
            val lp = bar.layoutParams
            lp.width = (parentWidth * (utilization / 100)).toInt().coerceAtMost(parentWidth)
            bar.layoutParams = lp
        }

        root.findViewById<TextView>(R.id.tvPcWalletUtilizationLabel).text =
            "${utilizationRounded}% Used \u2022 ${taka(state.settledThisMonthTotal)} / ${taka(state.totalFund)}"
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
        refreshTimer?.cancel()
        refreshTimer = object : CountDownTimer(30_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {}
            override fun onFinish() {
                if (isAdded && branchId.isNotBlank()) {
                    viewModel.load(branchId)
                    startAutoRefreshCountdown()
                }
            }
        }.start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
