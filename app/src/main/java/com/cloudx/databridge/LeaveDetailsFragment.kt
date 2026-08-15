package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Leave Management — Request Details (timeline + role-based actions).
 *
 * Mirrors PettyCashSettlementDetailsFragment's shape: a summary card, a
 * step-by-step timeline built from fixed named stages (not the raw
 * `steps` list, so a request still at PENDING correctly shows only step 1
 * as done), and a single primary action button whose label/behavior
 * changes based on the signed-in user's role + the request's current
 * status. Reject and the Requester's own Edit/Delete follow the same
 * visibility rules as Petty Cash.
 */
class LeaveDetailsFragment : Fragment() {

    private val viewModel: LeaveViewModel by viewModels()

    private var branchId: String = ""
    private var requestId: String = ""
    private var currentRequest: LeaveRequest? = null

    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val ARG_REQUEST_ID = "request_id"
        fun newInstance(branchId: String, requestId: String): LeaveDetailsFragment {
            val f = LeaveDetailsFragment()
            f.arguments = Bundle().apply {
                putString(ARG_BRANCH_ID, branchId)
                putString(ARG_REQUEST_ID, requestId)
            }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_leave_details, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()
        requestId = arguments?.getString(ARG_REQUEST_ID).orEmpty()

        view.findViewById<View>(R.id.btnLmDetailBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        viewModel.state.observe(viewLifecycleOwner) { state -> onState(view, state) }
        if (branchId.isNotBlank()) viewModel.load(branchId)
    }

    private fun onState(root: View, state: LeaveState) {
        if (state !is LeaveState.Success) return
        val request = state.requests.find { it.id == requestId }
        if (request == null) {
            Toast.makeText(requireContext(), "Request not found", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            return
        }
        currentRequest = request
        renderSummary(root, request)
        renderTimeline(root, request)
        bindActions(root, request, state.roles)
    }

    private fun renderSummary(root: View, request: LeaveRequest) {
        root.findViewById<TextView>(R.id.tvLmDetailStatusBadge).apply {
            text = statusLabel(request.status)
            setBackgroundColor(android.graphics.Color.parseColor(statusColor(request.status)))
        }
        root.findViewById<TextView>(R.id.tvLmDetailRequestCode).text = request.requestCode
        root.findViewById<TextView>(R.id.tvLmDetailWorker).text = "${request.workerName} \u2022 ${request.workerRole}"
        root.findViewById<TextView>(R.id.tvLmDetailLeaveType).text = request.leaveType
        root.findViewById<TextView>(R.id.tvLmDetailLeaveDate).text =
            if (request.leaveDateMillis > 0L) dateFormat.format(request.leaveDateMillis) else "—"

        val groupDutyDate = root.findViewById<View>(R.id.groupLmDetailDutyDate)
        groupDutyDate.isVisible = request.leaveType == LEAVE_TYPE_EXCHANGE && request.dutyDateMillis > 0L
        if (groupDutyDate.isVisible) {
            root.findViewById<TextView>(R.id.tvLmDetailDutyDate).text = dateFormat.format(request.dutyDateMillis)
        }

        root.findViewById<TextView>(R.id.tvLmDetailReliever).text =
            request.relieverName.ifBlank { "Not selected" }
        root.findViewById<TextView>(R.id.tvLmDetailReason).text =
            request.reason.ifBlank { "—" }
    }

    private fun renderTimeline(root: View, request: LeaveRequest) {
        val container = root.findViewById<android.widget.LinearLayout>(R.id.layoutLmDetailTimeline)
        container.removeAllViews()

        data class Stage(val title: String, val subtitle: String, val at: Long)
        val stages = listOf(
            Stage("Request Submitted", request.workerName, request.createdAt),
            Stage("Incharge Acknowledged", request.acknowledgedByName, request.acknowledgedAt),
            Stage("Shift Lead Approval", request.approvedByName, request.approvedAt)
        )

        stages.forEachIndexed { index, stage ->
            val stepView = layoutInflater.inflate(R.layout.item_petty_cash_approval_step, container, false)
            val isDone = stage.at != 0L
            val isLast = index == stages.lastIndex && request.status != LM_STATUS_REJECTED

            stepView.findViewById<TextView>(R.id.tvStepTitle).text = stage.title
            stepView.findViewById<TextView>(R.id.tvStepSubtitle).text = stage.subtitle
            stepView.findViewById<TextView>(R.id.tvStepTime).text = if (isDone) dateTimeFormat.format(stage.at) else ""

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

        if (request.status == LM_STATUS_REJECTED) {
            val stepView = layoutInflater.inflate(R.layout.item_petty_cash_approval_step, container, false)
            stepView.findViewById<TextView>(R.id.tvStepTitle).text = "Rejected"
            stepView.findViewById<TextView>(R.id.tvStepSubtitle).text =
                "${request.rejectedByName}${if (request.rejectReason.isNotBlank()) " — ${request.rejectReason}" else ""}"
            stepView.findViewById<TextView>(R.id.tvStepTime).text = dateTimeFormat.format(request.rejectedAt)
            val tvDot = stepView.findViewById<TextView>(R.id.tvStepDot)
            tvDot.text = "\u2715"
            tvDot.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_pc_step_pending)
            stepView.findViewById<View>(R.id.viewStepConnector).isVisible = false
            container.addView(stepView)
        }
    }

    private fun bindActions(root: View, request: LeaveRequest, roles: LeaveUserRoles) {
        val btnPrimary = root.findViewById<Button>(R.id.btnLmDetailPrimaryAction)
        val btnReject = root.findViewById<Button>(R.id.btnLmDetailReject)
        val layoutOwnerActions = root.findViewById<View>(R.id.layoutLmDetailOwnerActions)
        val btnEdit = root.findViewById<Button>(R.id.btnLmDetailEdit)
        val btnDelete = root.findViewById<Button>(R.id.btnLmDetailDelete)

        val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val isOwner = request.workerUid == myUid

        val canAcknowledge = request.status == LM_STATUS_PENDING && roles.isIncharge
        val canApprove = request.status == LM_STATUS_ACKNOWLEDGED && roles.isShiftLead
        val canReject = (request.status == LM_STATUS_PENDING && roles.isIncharge) ||
            (request.status == LM_STATUS_ACKNOWLEDGED && roles.isShiftLead)
        val canEditOrDelete = isOwner && request.status == LM_STATUS_PENDING

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
            else -> btnPrimary.isVisible = false
        }

        layoutOwnerActions.isVisible = canEditOrDelete
        btnEdit.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, LeaveRequestCreateFragment.newInstance(branchId, request.id))
                .addToBackStack(null)
                .commit()
        }
        btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun confirmAcknowledge() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Acknowledge ${currentRequest?.requestCode}?")
            .setMessage("This confirms you've reviewed the request and it moves to the Shift Lead for approval.")
            .setPositiveButton("Acknowledge") { _, _ ->
                lifecycleScope.launch {
                    val result = viewModel.acknowledgeRequest(branchId, requestId)
                    if (result.isSuccess) {
                        viewModel.load(branchId)
                    } else {
                        Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Acknowledge failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmApprove() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Approve ${currentRequest?.requestCode}?")
            .setPositiveButton("Approve") { _, _ ->
                lifecycleScope.launch {
                    val result = viewModel.approveRequest(branchId, requestId)
                    if (result.isSuccess) {
                        viewModel.load(branchId)
                    } else {
                        Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Approve failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmReject() {
        val input = EditText(requireContext()).apply {
            hint = "Reason for rejection"
            setPadding(40, 24, 40, 24)
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Reject ${currentRequest?.requestCode}?")
            .setView(input)
            .setPositiveButton("Reject") { _, _ ->
                val reason = input.text?.toString().orEmpty().trim()
                if (reason.isBlank()) {
                    Toast.makeText(requireContext(), "A reason is required to reject", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val result = viewModel.rejectRequest(branchId, requestId, reason)
                    if (result.isSuccess) {
                        viewModel.load(branchId)
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
            .setTitle("Delete ${currentRequest?.requestCode}?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val result = viewModel.deleteRequest(branchId, requestId)
                    if (result.isSuccess) {
                        parentFragmentManager.popBackStack()
                    } else {
                        Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun statusLabel(status: String): String = when (status) {
        LM_STATUS_PENDING -> "PENDING"
        LM_STATUS_ACKNOWLEDGED -> "ACKNOWLEDGED"
        LM_STATUS_APPROVED -> "APPROVED"
        LM_STATUS_REJECTED -> "REJECTED"
        else -> status.uppercase()
    }

    private fun statusColor(status: String): String = when (status) {
        LM_STATUS_PENDING -> "#B45309"
        LM_STATUS_ACKNOWLEDGED -> "#0369A1"
        LM_STATUS_APPROVED -> "#059669"
        LM_STATUS_REJECTED -> "#DC2626"
        else -> "#64748B"
    }
}
