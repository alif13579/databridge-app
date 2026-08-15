package com.cloudx.databridge

import android.graphics.Color
import android.os.Bundle
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Leave Management — Approval Queue (Incharge / Shift Lead screen).
 *
 * Unlike PettyCashDashboardFragment's role toggle (which switches between
 * three DIFFERENT screens' worth of stats), this queue is simpler because
 * both roles here look at the same shape of list — just a different status
 * filter and a different action. The toggle only appears at all when the
 * signed-in user holds both roles at this branch (rare, but LeaveViewModel
 * doesn't rule it out); otherwise the fragment locks to whichever single
 * role the user has and hides the toggle entirely.
 *
 * Incharge queue: LM_STATUS_PENDING requests -> action = Acknowledge.
 * Shift Lead queue: LM_STATUS_ACKNOWLEDGED requests -> action = Approve.
 * Reject is available from either queue (rejectRequest() itself validates
 * the request is still in a rejectable stage, so a stale/duplicate tap
 * from two Inchages acting near-simultaneously fails safely server-side).
 */
class LeaveQueueFragment : Fragment() {

    private val viewModel: LeaveViewModel by viewModels()

    private var branchId: String = ""
    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var rv: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var tvEmptyText: TextView
    private lateinit var pbLoading: View
    private lateinit var layoutRoleToggle: View
    private lateinit var btnRoleIncharge: TextView
    private lateinit var btnRoleShiftLead: TextView

    private enum class RoleView { INCHARGE, SHIFT_LEAD }
    private var selectedView: RoleView? = null
    private var lastState: LeaveState.Success? = null

    private val adapter = LeaveQueueAdapter(
        onAcknowledge = { request -> act { viewModel.acknowledgeRequest(branchId, request.id) } },
        onApprove = { request -> act { viewModel.approveRequest(branchId, request.id) } },
        onReject = { request -> showRejectDialog(request) },
        onRowClick = { request ->
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, LeaveDetailsFragment.newInstance(branchId, request.id))
                .addToBackStack(null)
                .commit()
        }
    )

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        fun newInstance(branchId: String): LeaveQueueFragment {
            val f = LeaveQueueFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_leave_queue, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        swipeRefresh = view.findViewById(R.id.swipeRefreshLmQueue)
        rv = view.findViewById(R.id.rvLmQueue)
        layoutEmpty = view.findViewById(R.id.layoutLmQueueEmpty)
        tvEmptyText = view.findViewById(R.id.tvLmQueueEmptyText)
        pbLoading = view.findViewById(R.id.pbLmQueueLoading)
        layoutRoleToggle = view.findViewById(R.id.layoutLmQueueRoleToggle)
        btnRoleIncharge = view.findViewById(R.id.btnLmQueueRoleIncharge)
        btnRoleShiftLead = view.findViewById(R.id.btnLmQueueRoleShiftLead)

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        view.findViewById<View>(R.id.btnLmQueueBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        btnRoleIncharge.setOnClickListener { selectedView = RoleView.INCHARGE; render() }
        btnRoleShiftLead.setOnClickListener { selectedView = RoleView.SHIFT_LEAD; render() }

        swipeRefresh.setOnRefreshListener { viewModel.load(branchId) }

        viewModel.state.observe(viewLifecycleOwner) { state -> onState(state) }
        if (branchId.isNotBlank()) viewModel.load(branchId)
    }

    private fun onState(state: LeaveState) {
        swipeRefresh.isRefreshing = false
        when (state) {
            is LeaveState.Loading -> {
                pbLoading.isVisible = lastState == null
            }
            is LeaveState.Success -> {
                pbLoading.isVisible = false
                lastState = state

                if (!state.roles.isAnyApprover) {
                    Toast.makeText(requireContext(), "You don't have an approval role at this branch", Toast.LENGTH_LONG).show()
                    parentFragmentManager.popBackStack()
                    return
                }

                // Default to whichever single role they hold; if they hold
                // both, keep the toggle's current selection (or default to
                // Incharge the first time) rather than silently picking one.
                if (selectedView == null) {
                    selectedView = if (state.roles.isIncharge) RoleView.INCHARGE else RoleView.SHIFT_LEAD
                }
                layoutRoleToggle.isVisible = state.roles.isIncharge && state.roles.isShiftLead
                render()
            }
            is LeaveState.Error -> {
                pbLoading.isVisible = false
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun render() {
        val state = lastState ?: return
        val view = selectedView ?: return

        highlightToggle(view)

        val (list, action) = when (view) {
            RoleView.INCHARGE -> state.inchargeQueue to LeaveQueueAdapter.Action.ACKNOWLEDGE
            RoleView.SHIFT_LEAD -> state.shiftLeadQueue to LeaveQueueAdapter.Action.APPROVE
        }

        adapter.submit(list, action)
        layoutEmpty.isVisible = list.isEmpty()
        tvEmptyText.text = when (view) {
            RoleView.INCHARGE -> "No leave requests waiting for acknowledgement"
            RoleView.SHIFT_LEAD -> "No leave requests waiting for approval"
        }
        rv.isVisible = list.isNotEmpty()
    }

    private fun highlightToggle(view: RoleView) {
        val activeBg = "#0F172A"
        val inactiveBg = "#FFFFFF"
        btnRoleIncharge.setBackgroundColor(Color.parseColor(if (view == RoleView.INCHARGE) activeBg else inactiveBg))
        btnRoleIncharge.setTextColor(Color.parseColor(if (view == RoleView.INCHARGE) "#FFFFFF" else "#334155"))
        btnRoleShiftLead.setBackgroundColor(Color.parseColor(if (view == RoleView.SHIFT_LEAD) activeBg else inactiveBg))
        btnRoleShiftLead.setTextColor(Color.parseColor(if (view == RoleView.SHIFT_LEAD) "#FFFFFF" else "#334155"))
    }

    private fun showRejectDialog(request: LeaveRequest) {
        val input = EditText(requireContext()).apply {
            hint = "Reason for rejection"
            setPadding(40, 24, 40, 24)
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Reject ${request.requestCode}")
            .setView(input)
            .setPositiveButton("Reject") { _, _ ->
                val reason = input.text?.toString().orEmpty().trim()
                if (reason.isBlank()) {
                    Toast.makeText(requireContext(), "A reason is required to reject", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                act { viewModel.rejectRequest(branchId, request.id, reason) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun act(block: suspend () -> Result<Unit>) {
        lifecycleScope.launch {
            val result = block()
            if (result.isSuccess) {
                viewModel.load(branchId)
            } else {
                Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Action failed", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * RecyclerView adapter for the queue list. A single view holder shape
 * serves both Incharge and Shift Lead queues — only the primary action
 * button's label/behavior changes (Acknowledge vs Approve), matching how
 * PettyCashDashboardFragment's queue row swaps its action per role rather
 * than using separate adapters.
 */
class LeaveQueueAdapter(
    private val onAcknowledge: (LeaveRequest) -> Unit,
    private val onApprove: (LeaveRequest) -> Unit,
    private val onReject: (LeaveRequest) -> Unit,
    private val onRowClick: (LeaveRequest) -> Unit
) : RecyclerView.Adapter<LeaveQueueAdapter.ViewHolder>() {

    enum class Action { ACKNOWLEDGE, APPROVE }

    private var items: List<LeaveRequest> = emptyList()
    private var action: Action = Action.ACKNOWLEDGE
    private val dateFormat = SimpleDateFormat("dd MMM", Locale.getDefault())

    fun submit(newItems: List<LeaveRequest>, newAction: Action) {
        items = newItems
        action = newAction
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_leave_queue_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], action)
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvAvatar: TextView = itemView.findViewById(R.id.tvLmQueueRowAvatar)
        private val tvCode: TextView = itemView.findViewById(R.id.tvLmQueueRowCode)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvLmQueueRowSubtitle)
        private val tvLeaveType: TextView = itemView.findViewById(R.id.tvLmQueueRowLeaveType)
        private val tvDates: TextView = itemView.findViewById(R.id.tvLmQueueRowDates)
        private val tvReliever: TextView = itemView.findViewById(R.id.tvLmQueueRowReliever)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvLmQueueRowStatus)
        private val btnAction: TextView = itemView.findViewById(R.id.btnLmQueueRowAction)
        private val btnReject: TextView = itemView.findViewById(R.id.btnLmQueueRowReject)

        fun bind(request: LeaveRequest, action: Action) {
            tvAvatar.text = request.workerName.firstOrNull()?.uppercase() ?: "?"
            tvCode.text = request.requestCode
            tvSubtitle.text = "${request.workerName} \u2022 ${request.workerRole}"
            tvLeaveType.text = request.leaveType

            tvDates.text = if (request.leaveType == LEAVE_TYPE_EXCHANGE && request.dutyDateMillis > 0L) {
                "Leave: ${dateFormat.format(request.leaveDateMillis)}  \u2192  Duty: ${dateFormat.format(request.dutyDateMillis)}"
            } else {
                "Leave: ${dateFormat.format(request.leaveDateMillis)}"
            }

            tvReliever.text = if (request.relieverName.isNotBlank()) {
                "Reliever: ${request.relieverName}"
            } else {
                "Reliever: Not selected"
            }

            tvStatus.text = when (request.status) {
                LM_STATUS_PENDING -> "Awaiting Incharge acknowledgement"
                LM_STATUS_ACKNOWLEDGED -> "Awaiting Shift Lead approval"
                else -> request.status.replaceFirstChar { it.uppercase() }
            }

            when (action) {
                Action.ACKNOWLEDGE -> {
                    btnAction.text = "Acknowledge"
                    btnAction.setOnClickListener { onAcknowledge(request) }
                }
                Action.APPROVE -> {
                    btnAction.text = "Approve"
                    btnAction.setOnClickListener { onApprove(request) }
                }
            }
            btnReject.setOnClickListener { onReject(request) }
            itemView.setOnClickListener { onRowClick(request) }
        }
    }
}
