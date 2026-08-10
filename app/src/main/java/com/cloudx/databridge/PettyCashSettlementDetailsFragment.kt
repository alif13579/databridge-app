package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Petty Cash Management — Settlement Details (mockup screen 4).
 *
 * Wired to PettyCashViewModel. This screen is the one place every role in
 * the approval chain passes through, so the primary action button changes
 * based on both the request's current status AND the signed-in user's role:
 *
 *   PENDING            + isTeamAligned -> "Acknowledge" button
 *   ACKNOWLEDGED        + isCashPoc     -> "Approve" button
 *   APPROVED            + isAccounts    -> "Mark Ready to Settle" button
 *   SETTLE_IN_PROCESS   + isAccounts    -> "Settle Now" (payment method picker)
 *   anything else                       -> no primary action, read-only
 *
 * Reject is available at PENDING (Team Aligned) and ACKNOWLEDGED (Cash POC)
 * stages only. Once POC has approved, rejecting no longer makes sense —
 * money is already earmarked; Accounts either settles it or handles it
 * manually outside this flow.
 *
 * Edit/Delete: the request's own submitter (workerUid) can edit or delete
 * it, but only while status == PENDING — before Team Aligned has even
 * looked at it. Once acknowledged, the request is "in the system" and
 * shouldn't be silently changed or removed out from under an approver.
 */
class PettyCashSettlementDetailsFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private var branchId: String = ""
    private var requestCode: String = ""
    private var latestState: PettyCashState.Success? = null

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

        viewModel.state.observe(viewLifecycleOwner) { state -> render(state) }
        if (branchId.isBlank() || requestCode.isBlank()) {
            render(PettyCashState.Error("Missing branch or request"))
        } else {
            viewModel.load(branchId)
        }
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        return "\u09F3${NumberFormat.getNumberInstance(Locale.US).format(whole)}"
    }

    private fun formatDateTime(millis: Long): String {
        if (millis == 0L) return "—"
        return SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(millis))
    }

    private fun render(state: PettyCashState) {
        val root = view ?: return
        val pbLoading = root.findViewById<View>(R.id.pbPcDetailLoading)
        val layoutError = root.findViewById<View>(R.id.layoutPcDetailError)
        val scroll = root.findViewById<View>(R.id.scrollPcDetail)

        when (state) {
            is PettyCashState.Loading -> {
                pbLoading.isVisible = true
                layoutError.isVisible = false
                scroll.isVisible = false
            }
            is PettyCashState.Error -> {
                pbLoading.isVisible = false
                scroll.isVisible = false
                layoutError.isVisible = true
                root.findViewById<TextView>(R.id.tvPcDetailError).text = state.message
                root.findViewById<View>(R.id.btnPcDetailRetry).setOnClickListener {
                    if (branchId.isNotBlank()) viewModel.load(branchId)
                }
            }
            is PettyCashState.Success -> {
                latestState = state
                val request = state.requests.find { it.requestCode == requestCode }
                if (request == null) {
                    pbLoading.isVisible = false
                    scroll.isVisible = false
                    layoutError.isVisible = true
                    root.findViewById<TextView>(R.id.tvPcDetailError).text = "Request $requestCode not found"
                    root.findViewById<View>(R.id.btnPcDetailRetry).setOnClickListener {
                        if (branchId.isNotBlank()) viewModel.load(branchId)
                    }
                } else {
                    pbLoading.isVisible = false
                    layoutError.isVisible = false
                    scroll.isVisible = true
                    renderRequest(root, request, state.roles)
                }
            }
        }
    }

    private fun renderRequest(root: View, request: PettyCashRequest, roles: PettyCashUserRoles) {
        root.findViewById<TextView>(R.id.tvPcDetailCode).text = request.requestCode
        root.findViewById<TextView>(R.id.tvPcDetailPriority).isVisible = request.priority == PC_PRIORITY_HIGH

        root.findViewById<TextView>(R.id.tvPcDetailWorkerInitial).text = request.workerName.take(1).uppercase()
        root.findViewById<TextView>(R.id.tvPcDetailWorkerName).text = request.workerName
        root.findViewById<TextView>(R.id.tvPcDetailWorkerRole).text = request.workerRole

        bindRow(root, R.id.rowPcCategory, "Category", request.category)
        bindRow(root, R.id.rowPcAmount, "Amount", taka(request.amount))

        root.findViewById<TextView>(R.id.tvPcDetailPurpose).text = request.purpose
        root.findViewById<TextView>(R.id.tvPcDetailAttachmentName).text =
            request.attachmentName.ifBlank { "No attachment" }

        val currentBalance = latestState?.walletBalance ?: 0.0
        val remaining = currentBalance - request.amount
        bindRow(root, R.id.rowPcSummaryCurrentBalance, "Current Balance", taka(currentBalance))
        bindRow(root, R.id.rowPcSummaryRequestAmount, "Request Amount", taka(request.amount))
        bindRow(root, R.id.rowPcSummaryRemaining, "Remaining After Settlement", taka(remaining))

        buildApprovalSteps(root, request)
        bindActions(root, request, roles)
    }

    private fun bindRow(root: View, includeId: Int, label: String, value: String) {
        val row = root.findViewById<View>(includeId)
        row.findViewById<TextView>(R.id.tvDetailRowLabel).text = label
        row.findViewById<TextView>(R.id.tvDetailRowValue).text = value
    }

    private fun buildApprovalSteps(root: View, request: PettyCashRequest) {
        val container = root.findViewById<LinearLayout>(R.id.layoutPcApprovalSteps)
        container.removeAllViews()

        // Build the 5 canonical stages from the request's actual fields —
        // a stage counts as "done" only if its timestamp is set, so a
        // request still at PENDING correctly shows only step 1 as done.
        data class Stage(val title: String, val subtitle: String, val at: Long)
        val stages = listOf(
            Stage("Request Submitted", request.workerName, request.createdAt),
            Stage("Team Aligned Acknowledged", request.teamAlignedByName, request.teamAlignedAt),
            Stage("POC Approval", request.pocApprovedByName, request.pocApprovedAt),
            Stage("Ready to Settle", request.settleInProcessByName, request.settleInProcessAt),
            Stage("Settled", request.settledByName, request.settledAt)
        )

        stages.forEachIndexed { index, stage ->
            val stepView = layoutInflater.inflate(R.layout.item_petty_cash_approval_step, container, false)
            val isDone = stage.at != 0L
            val isLast = index == stages.lastIndex

            stepView.findViewById<TextView>(R.id.tvStepTitle).text = stage.title
            stepView.findViewById<TextView>(R.id.tvStepSubtitle).text = stage.subtitle
            stepView.findViewById<TextView>(R.id.tvStepTime).text = if (isDone) formatDateTime(stage.at) else ""

            val tvDot = stepView.findViewById<TextView>(R.id.tvStepDot)
            if (isDone) {
                tvDot.text = "\u2713"
                tvDot.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_pc_step_done)
            } else {
                tvDot.text = ""
                tvDot.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_pc_step_pending)
            }

            stepView.findViewById<View>(R.id.viewStepConnector).isVisible = !isLast
            container.addView(stepView)
        }

        if (request.status == PC_STATUS_REJECTED) {
            val stepView = layoutInflater.inflate(R.layout.item_petty_cash_approval_step, container, false)
            stepView.findViewById<TextView>(R.id.tvStepTitle).text = "Rejected"
            stepView.findViewById<TextView>(R.id.tvStepSubtitle).text =
                "${request.rejectedByName}${if (request.rejectReason.isNotBlank()) " — ${request.rejectReason}" else ""}"
            stepView.findViewById<TextView>(R.id.tvStepTime).text = formatDateTime(request.rejectedAt)
            val tvDot = stepView.findViewById<TextView>(R.id.tvStepDot)
            tvDot.text = "\u2715"
            tvDot.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_pc_step_pending)
            stepView.findViewById<View>(R.id.viewStepConnector).isVisible = false
            container.addView(stepView)
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private fun bindActions(root: View, request: PettyCashRequest, roles: PettyCashUserRoles) {
        val btnPrimary = root.findViewById<Button>(R.id.btnPcDetailSettleNow)
        val btnReject = root.findViewById<Button>(R.id.btnPcDetailReject)

        val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val isOwner = request.workerUid == myUid

        val canAcknowledge = request.status == PC_STATUS_PENDING && roles.isTeamAligned
        val canApprove = request.status == PC_STATUS_ACKNOWLEDGED && roles.isCashPoc
        val canMarkReady = request.status == PC_STATUS_APPROVED && roles.isAccounts
        val canSettle = request.status == PC_STATUS_SETTLE_IN_PROCESS && roles.isAccounts
        val canReject = (request.status == PC_STATUS_PENDING && roles.isTeamAligned) ||
            (request.status == PC_STATUS_ACKNOWLEDGED && roles.isCashPoc)
        val canEditOrDelete = isOwner && request.status == PC_STATUS_PENDING

        btnReject.isVisible = canReject
        btnReject.setOnClickListener { confirmReject() }

        when {
            canAcknowledge -> {
                btnPrimary.isVisible = true
                btnPrimary.text = "Acknowledge Request"
                btnPrimary.setOnClickListener { confirmAcknowledge() }
            }
            canApprove -> {
                btnPrimary.isVisible = true
                btnPrimary.text = "Approve Request"
                btnPrimary.setOnClickListener { confirmApprove() }
            }
            canMarkReady -> {
                btnPrimary.isVisible = true
                btnPrimary.text = "Mark Ready to Settle"
                btnPrimary.setOnClickListener { confirmMarkReady() }
            }
            canSettle -> {
                btnPrimary.isVisible = true
                btnPrimary.text = "Settle Now"
                btnPrimary.setOnClickListener { openSettleDialog() }
            }
            else -> {
                btnPrimary.isVisible = false
            }
        }

        bindEditDeleteRow(root, request, canEditOrDelete)
    }

    /** Owner-only Edit/Delete row, only while the request is still PENDING. */
    private fun bindEditDeleteRow(root: View, request: PettyCashRequest, canEditOrDelete: Boolean) {
        val layoutEditDelete = root.findViewById<View?>(R.id.layoutPcDetailEditDelete)
        layoutEditDelete?.isVisible = canEditOrDelete
        if (!canEditOrDelete) return

        root.findViewById<View>(R.id.btnPcDetailEdit).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashRequestCreateFragment.newInstance(branchId, editRequestId = request.id))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        root.findViewById<View>(R.id.btnPcDetailDelete).setOnClickListener { confirmDelete() }
    }

    private fun confirmAcknowledge() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Acknowledge $requestCode?")
            .setMessage("This confirms the request is aligned with your team and sends it to Cash POC for approval.")
            .setPositiveButton("Acknowledge") { _, _ -> runAction { viewModel.acknowledgeRequest(branchId, requestIdFor(requestCode)) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmApprove() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Approve $requestCode?")
            .setMessage("This approves the request, queueing it for Accounts to settle.")
            .setPositiveButton("Approve") { _, _ -> runAction { viewModel.approveRequest(branchId, requestIdFor(requestCode)) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmMarkReady() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Mark $requestCode ready to settle?")
            .setMessage("This moves the request into your cash handover queue.")
            .setPositiveButton("Mark Ready") { _, _ -> runAction { viewModel.markReadyToSettle(branchId, requestIdFor(requestCode)) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSettleDialog() {
        val methods = arrayOf("Cash", "Bank")
        var selectedMethod = methods[0]
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Settle $requestCode")
            .setSingleChoiceItems(methods, 0) { _, index -> selectedMethod = methods[index] }
            .setPositiveButton("Settle") { _, _ ->
                val trxId = "TXN-${System.currentTimeMillis().toString().takeLast(5)}"
                lifecycleScope.launch {
                    val result = viewModel.settleRequest(branchId, requestIdFor(requestCode), selectedMethod, trxId)
                    if (result.isSuccess) {
                        parentFragmentManager.beginTransaction()
                            .replace(R.id.container, PettyCashSettlementSuccessFragment.newInstance(branchId, requestCode))
                            .addToBackStack(null)
                            .commitAllowingStateLoss()
                    } else {
                        Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Settlement failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmReject() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Reject $requestCode?")
            .setMessage("This request will be marked as rejected and removed from the queue.")
            .setPositiveButton("Reject") { _, _ ->
                lifecycleScope.launch {
                    val result = viewModel.rejectRequest(branchId, requestIdFor(requestCode), "")
                    if (result.isSuccess) {
                        Toast.makeText(requireContext(), "$requestCode rejected", Toast.LENGTH_SHORT).show()
                        parentFragmentManager.popBackStack()
                    } else {
                        Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Reject failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete $requestCode?")
            .setMessage("This permanently removes the request. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val result = viewModel.deleteRequest(branchId, requestIdFor(requestCode))
                    if (result.isSuccess) {
                        Toast.makeText(requireContext(), "$requestCode deleted", Toast.LENGTH_SHORT).show()
                        parentFragmentManager.popBackStack()
                    } else {
                        Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestIdFor(code: String): String =
        latestState?.requests?.find { it.requestCode == code }?.id.orEmpty()

    private fun runAction(block: suspend () -> Result<Unit>) {
        lifecycleScope.launch {
            val result = block()
            if (result.isSuccess) {
                Toast.makeText(requireContext(), "Done", Toast.LENGTH_SHORT).show()
                if (branchId.isNotBlank()) viewModel.load(branchId)
            } else {
                Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Action failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
