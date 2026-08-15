package com.cloudx.databridge

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Leave Management — New/Edit Request (Requester screen).
 *
 * Mirrors PettyCashRequestCreateFragment's structure: category-style
 * dropdowns, conditional field groups, Fragment Result-free direct
 * ViewModel submission. Edit mode follows the same "only while PENDING"
 * rule as Petty Cash edit.
 *
 * Leave Type-specific fields:
 *   - Exchange: Leave Date + Duty Date both required.
 *   - Unpaid Leave: Leave Date only — Duty Date group stays hidden and its
 *     value is cleared, same "switching category clears the other
 *     category's field" pattern PettyCashRequestCreateFragment uses for
 *     Consignment ID / Merchant.
 *
 * Reliever is OPTIONAL — a branch/role can genuinely have nobody else
 * active to cover, and blocking submission on that would trap the
 * requester. loadRelieverCandidates() (same branch + same role_id +
 * active, excluding self) runs as soon as the fragment has the
 * signed-in user's role_id; if it comes back empty the dropdown still
 * opens but shows a "No one available" hint instead of a picker list.
 */
class LeaveRequestCreateFragment : Fragment() {

    private val viewModel: LeaveViewModel by viewModels()

    private var branchId: String = ""
    private var editRequestId: String = ""
    private val isEditMode: Boolean get() = editRequestId.isNotBlank()

    private val leaveTypeOptions = listOf(LEAVE_TYPE_EXCHANGE, LEAVE_TYPE_UNPAID)
    private var relievers: List<RelieverCandidate> = emptyList()
    private var relieversLoaded = false

    private var selectedLeaveType: String = ""
    private var selectedLeaveDateMillis: Long = 0L
    private var selectedDutyDateMillis: Long = 0L
    private var selectedRelieverUid: String = ""
    private var selectedRelieverName: String = ""

    private lateinit var tvTitle: TextView
    private lateinit var tvLeaveTypeSelected: TextView
    private lateinit var groupLeaveDate: View
    private lateinit var tvLeaveDateSelected: TextView
    private lateinit var groupDutyDate: View
    private lateinit var tvDutyDateSelected: TextView
    private lateinit var tvRelieverSelected: TextView
    private lateinit var tvRelieverHint: TextView
    private lateinit var etReason: EditText
    private lateinit var tvReasonCount: TextView
    private lateinit var btnSubmit: android.widget.Button

    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val ARG_EDIT_REQUEST_ID = "edit_request_id"

        fun newInstance(branchId: String, editRequestId: String = ""): LeaveRequestCreateFragment {
            val f = LeaveRequestCreateFragment()
            f.arguments = Bundle().apply {
                putString(ARG_BRANCH_ID, branchId)
                putString(ARG_EDIT_REQUEST_ID, editRequestId)
            }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_leave_request_create, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()
        editRequestId = arguments?.getString(ARG_EDIT_REQUEST_ID).orEmpty()

        tvTitle = view.findViewById(R.id.tvLmRequestCreateTitle)
        tvLeaveTypeSelected = view.findViewById(R.id.tvLmRequestLeaveTypeSelected)
        groupLeaveDate = view.findViewById(R.id.groupLmRequestLeaveDate)
        tvLeaveDateSelected = view.findViewById(R.id.tvLmRequestLeaveDateSelected)
        groupDutyDate = view.findViewById(R.id.groupLmRequestDutyDate)
        tvDutyDateSelected = view.findViewById(R.id.tvLmRequestDutyDateSelected)
        tvRelieverSelected = view.findViewById(R.id.tvLmRequestRelieverSelected)
        tvRelieverHint = view.findViewById(R.id.tvLmRequestRelieverHint)
        etReason = view.findViewById(R.id.etLmRequestReason)
        tvReasonCount = view.findViewById(R.id.tvLmRequestReasonCount)
        btnSubmit = view.findViewById(R.id.btnLmRequestSubmit)

        if (!isEditMode && !RbacManager.hasPermission("leave_requester")) {
            Toast.makeText(requireContext(), "Your role isn't set up to submit leave requests", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            return
        }

        tvTitle.text = if (isEditMode) "Edit Leave Request" else "New Leave Request"
        btnSubmit.text = if (isEditMode) "Update Request" else "Submit Request"

        view.findViewById<View>(R.id.btnLmRequestCreateBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<View>(R.id.layoutLmRequestLeaveType).setOnClickListener { showLeaveTypePicker() }
        view.findViewById<View>(R.id.layoutLmRequestLeaveDate).setOnClickListener { showDatePicker(isDutyDate = false) }
        view.findViewById<View>(R.id.layoutLmRequestDutyDate).setOnClickListener { showDatePicker(isDutyDate = true) }
        view.findViewById<View>(R.id.layoutLmRequestReliever).setOnClickListener { showRelieverPicker() }

        etReason.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                tvReasonCount.text = "${s?.length ?: 0}/200"
            }
        })

        btnSubmit.setOnClickListener { onSubmit() }

        loadRelievers()

        if (isEditMode) {
            viewModel.state.observe(viewLifecycleOwner) { state -> prefillIfEditing(state) }
            if (branchId.isNotBlank()) viewModel.load(branchId)
        }
    }

    private var prefilled = false

    private fun prefillIfEditing(state: LeaveState) {
        if (prefilled || state !is LeaveState.Success) return
        val request = state.requests.find { it.id == editRequestId } ?: return
        if (request.status != LM_STATUS_PENDING) {
            Toast.makeText(requireContext(), "This request can no longer be edited", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            return
        }
        applyLeaveType(request.leaveType)
        if (request.leaveDateMillis > 0L) applyLeaveDate(request.leaveDateMillis)
        if (request.dutyDateMillis > 0L) applyDutyDate(request.dutyDateMillis)
        if (request.relieverUid.isNotBlank()) {
            selectedRelieverUid = request.relieverUid
            selectedRelieverName = request.relieverName
            tvRelieverSelected.text = request.relieverName
            tvRelieverSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
        }
        etReason.setText(request.reason)
        prefilled = true
    }

    private fun showLeaveTypePicker() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Leave Type")
            .setItems(leaveTypeOptions.toTypedArray()) { _, index -> applyLeaveType(leaveTypeOptions[index]) }
            .show()
    }

    /** Sets the selected leave type and shows/hides the type-specific field groups. */
    private fun applyLeaveType(leaveType: String) {
        selectedLeaveType = leaveType
        tvLeaveTypeSelected.text = leaveType
        tvLeaveTypeSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))

        // Both leave types need a Leave Date; only Exchange also needs a Duty Date.
        groupLeaveDate.isVisible = true
        groupDutyDate.isVisible = leaveType == LEAVE_TYPE_EXCHANGE

        // Switching away from Exchange clears a half-picked Duty Date so it
        // doesn't silently survive a switch to Unpaid Leave and get submitted.
        if (leaveType != LEAVE_TYPE_EXCHANGE) {
            selectedDutyDateMillis = 0L
            tvDutyDateSelected.text = "Select Duty Date"
            tvDutyDateSelected.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
        }
    }

    private fun showDatePicker(isDutyDate: Boolean) {
        val cal = Calendar.getInstance()
        val existing = if (isDutyDate) selectedDutyDateMillis else selectedLeaveDateMillis
        if (existing > 0L) cal.timeInMillis = existing

        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (isDutyDate) applyDutyDate(picked) else applyLeaveDate(picked)
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun applyLeaveDate(millis: Long) {
        selectedLeaveDateMillis = millis
        tvLeaveDateSelected.text = dateFormat.format(millis)
        tvLeaveDateSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
    }

    private fun applyDutyDate(millis: Long) {
        selectedDutyDateMillis = millis
        tvDutyDateSelected.text = dateFormat.format(millis)
        tvDutyDateSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
    }

    private fun showRelieverPicker() {
        if (!relieversLoaded) {
            Toast.makeText(requireContext(), "Still loading reliever list, try again in a moment", Toast.LENGTH_SHORT).show()
            return
        }
        if (relievers.isEmpty()) {
            Toast.makeText(requireContext(), "No one else is available to cover right now — you can leave this blank", Toast.LENGTH_LONG).show()
            return
        }
        val names = relievers.map { it.name }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Reliever")
            .setItems(names) { _, index ->
                selectedRelieverUid = relievers[index].uid
                selectedRelieverName = relievers[index].name
                tvRelieverSelected.text = selectedRelieverName
                tvRelieverSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
            }
            .show()
    }

    /** Same branch + same role_id + active, excluding the signed-in user themself. */
    private fun loadRelievers() {
        val workerRoleId = RbacManager.current.roleId
        if (branchId.isBlank() || workerRoleId.isBlank()) {
            relieversLoaded = true
            tvRelieverHint.text = "Reliever list unavailable — no branch or role on this account"
            return
        }
        lifecycleScope.launch {
            try {
                relievers = viewModel.loadRelieverCandidates(branchId, workerRoleId)
                relieversLoaded = true
                tvRelieverHint.text = if (relievers.isEmpty()) {
                    "No one else with your role is currently active at this branch — optional, you can leave this blank"
                } else {
                    ""
                }
            } catch (e: Exception) {
                relieversLoaded = true
                tvRelieverHint.text = "Couldn't load reliever list: ${e.message}"
            }
        }
    }

    private fun onSubmit() {
        val reason = etReason.text?.toString().orEmpty().trim()

        if (branchId.isBlank()) {
            Toast.makeText(requireContext(), "No branch selected", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedLeaveType.isBlank()) {
            Toast.makeText(requireContext(), "Select a leave type", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedLeaveDateMillis <= 0L) {
            Toast.makeText(requireContext(), "Select the leave date", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedLeaveType == LEAVE_TYPE_EXCHANGE && selectedDutyDateMillis <= 0L) {
            Toast.makeText(requireContext(), "Select the duty date", Toast.LENGTH_SHORT).show()
            return
        }
        if (reason.isBlank()) {
            Toast.makeText(requireContext(), "Describe the reason", Toast.LENGTH_SHORT).show()
            return
        }

        val finalDutyDate = if (selectedLeaveType == LEAVE_TYPE_EXCHANGE) selectedDutyDateMillis else 0L

        btnSubmit.isEnabled = false
        if (isEditMode) {
            lifecycleScope.launch {
                val result = viewModel.updateRequest(
                    branchId, editRequestId, selectedLeaveType, selectedLeaveDateMillis, finalDutyDate,
                    relieverUid = selectedRelieverUid, relieverName = selectedRelieverName, reason = reason
                )
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), "Request updated", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                } else {
                    btnSubmit.isEnabled = true
                    Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Update failed", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            lifecycleScope.launch {
                val result = viewModel.submitRequest(
                    branchId = branchId,
                    leaveType = selectedLeaveType,
                    leaveDateMillis = selectedLeaveDateMillis,
                    dutyDateMillis = finalDutyDate,
                    relieverUid = selectedRelieverUid,
                    relieverName = selectedRelieverName,
                    reason = reason,
                    workerRole = RbacManager.current.roleName.ifBlank { RbacManager.current.roleId }
                )
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), "Request ${result.getOrNull()} submitted", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                } else {
                    btnSubmit.isEnabled = true
                    Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Submit failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
