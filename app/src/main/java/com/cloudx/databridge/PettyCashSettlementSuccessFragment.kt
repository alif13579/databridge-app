package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Petty Cash Management — Settlement Success (mockup screen 5).
 *
 * Wired to PettyCashViewModel. Reached right after Settlement Details'
 * "Settle Now" confirms — the ViewModel was just reloaded as part of that
 * settle flow, so the settled request should already be in state by the
 * time this screen observes it. Falls back to a brief loading label if the
 * data isn't there yet (e.g. very slow network) rather than showing wrong
 * numbers.
 */
class PettyCashSettlementSuccessFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private var branchId: String = ""
    private var requestCode: String = ""
    private var walletBalanceAtRender: Double = 0.0

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val ARG_REQUEST_CODE = "request_code"

        fun newInstance(branchId: String, requestCode: String): PettyCashSettlementSuccessFragment {
            val f = PettyCashSettlementSuccessFragment()
            f.arguments = Bundle().apply {
                putString(ARG_BRANCH_ID, branchId)
                putString(ARG_REQUEST_CODE, requestCode)
            }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_settlement_success, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()
        requestCode = arguments?.getString(ARG_REQUEST_CODE).orEmpty()

        view.findViewById<View>(R.id.btnPcSuccessViewDetails).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashSettlementDetailsFragment.newInstance(branchId, requestCode))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        view.findViewById<View>(R.id.btnPcSuccessBackToDashboard).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashDashboardFragment.newInstance(branchId))
                .commitAllowingStateLoss()
        }

        viewModel.state.observe(viewLifecycleOwner) { state -> render(state) }
        if (branchId.isNotBlank()) viewModel.load(branchId)
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
        if (state !is PettyCashState.Success) return // loading/error is brief here; keep the success chrome, don't flash an error UI

        val request = state.requests.find { it.requestCode == requestCode } ?: return
        walletBalanceAtRender = state.walletBalance

        root.findViewById<TextView>(R.id.tvPcSuccessSubtitle).text =
            "$requestCode has been settled successfully."
        root.findViewById<TextView>(R.id.tvPcSuccessSettledAmount).text = taka(request.amount)
        root.findViewById<TextView>(R.id.tvPcSuccessNewBalance).text = taka(state.walletBalance)

        bindRow(root, R.id.rowPcSuccessPaymentMethod, "Payment Method", request.settledPaymentMethod.ifBlank { "—" })
        bindRow(root, R.id.rowPcSuccessSettledOn, "Settled On", formatDateTime(request.settledAt))
        bindRow(root, R.id.rowPcSuccessSettledBy, "Settled By", request.settledByName.ifBlank { "—" })
        bindRow(root, R.id.rowPcSuccessTrxId, "Transaction ID / Ref", request.settledTrxId.ifBlank { "—" })
    }

    private fun bindRow(root: View, includeId: Int, label: String, value: String) {
        val row = root.findViewById<View>(includeId)
        row.findViewById<TextView>(R.id.tvDetailRowLabel).text = label
        row.findViewById<TextView>(R.id.tvDetailRowValue).text = value
    }
}
