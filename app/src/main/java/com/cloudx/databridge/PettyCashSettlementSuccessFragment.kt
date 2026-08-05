package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.text.NumberFormat
import java.util.Locale

/**
 * Petty Cash Management — Settlement Success (mockup screen 5).
 *
 * Phase 4 of the Petty Cash feature build: layout + static mock data.
 * Reached after confirming "Settle Now" on Settlement Details.
 * Firebase wiring (writing the actual settle transaction) lands with
 * PettyCashViewModel.
 */
class PettyCashSettlementSuccessFragment : Fragment() {

    private var branchId: String = ""
    private var requestCode: String = ""

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

        renderMockSuccess(view)

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
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        return "\u09F3${NumberFormat.getNumberInstance(Locale.US).format(whole)}"
    }

    // ── Mock data (Phase 4) — amount keyed off the same mock set used in Phase 2/3 ──
    private fun mockAmountFor(code: String): Double = when (code) {
        "REQ-2400" -> 950.0
        "REQ-2399" -> 620.0
        "REQ-2398" -> 850.0
        else -> 1250.0
    }

    private fun renderMockSuccess(root: View) {
        val amount = mockAmountFor(requestCode)
        val previousBalance = 142750.0
        val newBalance = previousBalance - amount

        root.findViewById<TextView>(R.id.tvPcSuccessSubtitle).text =
            "$requestCode has been settled successfully."
        root.findViewById<TextView>(R.id.tvPcSuccessSettledAmount).text = taka(amount)
        root.findViewById<TextView>(R.id.tvPcSuccessNewBalance).text = taka(newBalance)

        bindRow(root, R.id.rowPcSuccessPaymentMethod, "Payment Method", "Cash")
        bindRow(root, R.id.rowPcSuccessSettledOn, "Settled On", "05 Aug 2026, 12:15 PM")
        bindRow(root, R.id.rowPcSuccessSettledBy, "Settled By", "Alif Hossain (Accounts)")
        bindRow(root, R.id.rowPcSuccessTrxId, "Transaction ID / Ref", "TXN-10081")
    }

    private fun bindRow(root: View, includeId: Int, label: String, value: String) {
        val row = root.findViewById<View>(includeId)
        row.findViewById<TextView>(R.id.tvDetailRowLabel).text = label
        row.findViewById<TextView>(R.id.tvDetailRowValue).text = value
    }
}
