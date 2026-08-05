package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import java.text.NumberFormat
import java.util.Locale

/**
 * Petty Cash Management — Settlement Details (mockup screen 4).
 *
 * Phase 3 of the Petty Cash feature build: layout + static mock data matching
 * the approved mockup, 4-step approval timeline (Request Submitted -> Team
 * Aligned -> POC Approval -> Accounts Settlement), Settle Now / Reject actions.
 * Firebase wiring (loading the real PettyCashRequest by requestCode, and
 * writing the settle/reject transitions) lands once PettyCashViewModel exists.
 */
class PettyCashSettlementDetailsFragment : Fragment() {

    private var branchId: String = ""
    private var requestCode: String = ""

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val ARG_REQUEST_CODE = "request_code"

        fun newInstance(branchId: String, requestCode: String): PettyCashSettlementDetailsFragment {
            val f = PettyCashSettlementDetailsFragment()
            f.arguments = Bundle().apply {
                putString(ARG_BRANCH_ID, branchId)
                putString(ARG_REQUEST_CODE, requestCode)
            }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_settlement_details, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()
        requestCode = arguments?.getString(ARG_REQUEST_CODE).orEmpty()

        view.findViewById<View>(R.id.btnPcDetailBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        renderMockDetails(view)

        view.findViewById<View>(R.id.btnPcDetailSettleNow).setOnClickListener {
            openSettleDialog()
        }
        view.findViewById<View>(R.id.btnPcDetailReject).setOnClickListener {
            confirmReject()
        }
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        return "\u09F3${NumberFormat.getNumberInstance(Locale.US).format(whole)}"
    }

    // ── Mock data (Phase 3) — keyed by requestCode, matches Phase 2's mock list ──
    private data class MockDetail(
        val code: String,
        val priority: String,
        val worker: String,
        val role: String,
        val category: String,
        val amount: Double,
        val purpose: String,
        val attachmentName: String,
        val currentBalance: Double,
        val submittedAt: String,
        val teamAlignedAt: String,
        val teamAlignedBy: String,
        val pocApprovedAt: String,
        val pocApprovedBy: String
    )

    private fun mockFor(code: String): MockDetail = when (code) {
        "REQ-2400" -> MockDetail(
            "REQ-2400", PC_PRIORITY_NORMAL, "Salman Khan", "Delivery Agent",
            "Fuel Expense", 950.0, "Fuel for delivery bike, weekly route coverage.",
            "receipt.jpg", 142750.0,
            "01 Aug, 10:05 AM", "01 Aug, 10:20 AM", "Robin Ahmed", "01 Aug, 11:10 AM", "Moin Uddin"
        )
        "REQ-2399" -> MockDetail(
            "REQ-2399", PC_PRIORITY_NORMAL, "Jannatul", "Office Staff",
            "Stationery", 620.0, "Office stationery restock for the branch desk.",
            "invoice.jpg", 142750.0,
            "01 Aug, 09:50 AM", "01 Aug, 10:05 AM", "Robin Ahmed", "01 Aug, 10:55 AM", "Moin Uddin"
        )
        "REQ-2398" -> MockDetail(
            "REQ-2398", PC_PRIORITY_NORMAL, "Riya Akter", "Office Staff",
            "Office Supplies", 850.0, "Printer cartridge and paper supplies.",
            "receipt.jpg", 142750.0,
            "01 Aug, 09:30 AM", "01 Aug, 09:45 AM", "Robin Ahmed", "01 Aug, 10:40 AM", "Moin Uddin"
        )
        else -> MockDetail(
            "REQ-2401", PC_PRIORITY_HIGH, "Hasib Khan", "Delivery Agent",
            "Travel Expense", 1250.0, "Local transport for delivery return parcel to hub.",
            "receipt.jpg", 142750.0,
            "01 Aug, 10:20 AM", "01 Aug, 10:40 AM", "Robin Ahmed", "01 Aug, 11:25 AM", "Moin Uddin"
        )
    }

    private fun renderMockDetails(root: View) {
        val d = mockFor(requestCode)

        root.findViewById<TextView>(R.id.tvPcDetailCode).text = d.code
        val tvPriority = root.findViewById<TextView>(R.id.tvPcDetailPriority)
        tvPriority.isVisible = d.priority == PC_PRIORITY_HIGH

        root.findViewById<TextView>(R.id.tvPcDetailWorkerInitial).text = d.worker.take(1).uppercase()
        root.findViewById<TextView>(R.id.tvPcDetailWorkerName).text = d.worker
        root.findViewById<TextView>(R.id.tvPcDetailWorkerRole).text = d.role

        bindRow(root, R.id.rowPcCategory, "Category", d.category)
        bindRow(root, R.id.rowPcAmount, "Amount", taka(d.amount))

        root.findViewById<TextView>(R.id.tvPcDetailPurpose).text = d.purpose
        root.findViewById<TextView>(R.id.tvPcDetailAttachmentName).text = d.attachmentName

        val remaining = d.currentBalance - d.amount
        bindRow(root, R.id.rowPcSummaryCurrentBalance, "Current Balance", taka(d.currentBalance))
        bindRow(root, R.id.rowPcSummaryRequestAmount, "Request Amount", taka(d.amount))
        bindRow(root, R.id.rowPcSummaryRemaining, "Remaining After Settlement", taka(remaining))

        buildApprovalSteps(root, d)
    }

    private fun bindRow(root: View, includeId: Int, label: String, value: String) {
        val row = root.findViewById<View>(includeId)
        row.findViewById<TextView>(R.id.tvDetailRowLabel).text = label
        row.findViewById<TextView>(R.id.tvDetailRowValue).text = value
    }

    private fun buildApprovalSteps(root: View, d: MockDetail) {
        val container = root.findViewById<LinearLayout>(R.id.layoutPcApprovalSteps)
        container.removeAllViews()

        val steps = listOf(
            Triple("Request Submitted", d.worker, d.submittedAt),
            Triple("Team Aligned Approval", d.teamAlignedBy, d.teamAlignedAt),
            Triple("POC Approval", d.pocApprovedBy, d.pocApprovedAt),
            Triple("Accounts Settlement", "", "Pending")
        )

        steps.forEachIndexed { index, (title, subtitle, time) ->
            val stepView = layoutInflater.inflate(R.layout.item_petty_cash_approval_step, container, false)
            val isPending = title == "Accounts Settlement"
            val isLast = index == steps.lastIndex

            stepView.findViewById<TextView>(R.id.tvStepTitle).text = title
            stepView.findViewById<TextView>(R.id.tvStepSubtitle).text = subtitle
            stepView.findViewById<TextView>(R.id.tvStepTime).text = if (isPending) "" else time

            val tvDot = stepView.findViewById<TextView>(R.id.tvStepDot)
            if (isPending) {
                tvDot.text = ""
                tvDot.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_pc_step_pending)
            } else {
                tvDot.text = "\u2713"
                tvDot.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_pc_step_done)
            }

            stepView.findViewById<View>(R.id.viewStepConnector).isVisible = !isLast

            container.addView(stepView)
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private fun openSettleDialog() {
        // Full payment-method picker + confirmation form lands with Settlement
        // Success (Phase 4). For now, confirm then navigate to the success screen.
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Settle $requestCode?")
            .setMessage("This will mark the request as settled and deduct from the wallet balance.")
            .setPositiveButton("Settle") { _, _ ->
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, PettyCashSettlementSuccessFragment.newInstance(branchId, requestCode))
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmReject() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Reject $requestCode?")
            .setMessage("This request will be marked as rejected and removed from the settlement queue.")
            .setPositiveButton("Reject") { _, _ ->
                Toast.makeText(requireContext(), "$requestCode rejected", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
