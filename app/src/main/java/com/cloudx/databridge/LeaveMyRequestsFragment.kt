package com.cloudx.databridge

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Leave Management — My Requests (Requester landing screen).
 *
 * Mirrors PettyCashMyRequestsFragment's shape and purpose: reached instead
 * of the approval Queue when someone has leave_requester but isn't an
 * Incharge/Shift Lead — the Queue's acknowledge/approve UI would be
 * meaningless to them. Shows their own request history with status, plus
 * a branch switcher for anyone assigned to more than one branch.
 *
 * No money hero card here (Petty Cash's "Total Approved This Month" has no
 * Leave equivalent) — just the 4 status-count stat tiles and the list.
 */
class LeaveMyRequestsFragment : Fragment() {

    private val viewModel: LeaveViewModel by viewModels()

    private lateinit var tvBranchName: TextView
    private var branchId: String = ""

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        fun newInstance(branchId: String): LeaveMyRequestsFragment {
            val f = LeaveMyRequestsFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_leave_my_requests, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = LeaveBranchSwitcher.resolveInitialBranchId(requireContext(), arguments?.getString(ARG_BRANCH_ID).orEmpty())
        tvBranchName = view.findViewById(R.id.tvLmMyRequestsBranchName)

        view.findViewById<View>(R.id.btnLmMyRequestsBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<View>(R.id.btnLmMyRequestsNew).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, LeaveRequestCreateFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        view.findViewById<View>(R.id.btnLmMyRequestsRetry).setOnClickListener {
            if (branchId.isNotBlank()) viewModel.load(branchId)
        }

        setupBranchSwitcher()

        viewModel.state.observe(viewLifecycleOwner) { state -> render(state) }
        if (branchId.isBlank()) {
            render(LeaveState.Error("No branch selected"))
        } else {
            viewModel.load(branchId)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh when returning from Request Create so a just-submitted
        // request shows up immediately without a manual pull-to-refresh.
        if (branchId.isNotBlank()) viewModel.load(branchId)
    }

    // ── Branch switcher ──────────────────────────────────────────────────────

    private fun setupBranchSwitcher() {
        LeaveBranchSwitcher.setup(
            context = requireContext(),
            scope = viewLifecycleOwner.lifecycleScope,
            chip = tvBranchName,
            currentBranchId = branchId
        ) { newBranchId ->
            if (newBranchId != branchId) {
                branchId = newBranchId
                viewModel.load(branchId)
            }
        }
    }

    private fun formatDate(millis: Long): String {
        if (millis == 0L) return "—"
        return SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(millis))
    }

    // ── Render ───────────────────────────────────────────────────────────────

    private fun render(state: LeaveState) {
        val root = view ?: return
        val pbLoading = root.findViewById<View>(R.id.pbLmMyRequestsLoading)
        val layoutError = root.findViewById<View>(R.id.layoutLmMyRequestsError)
        val scroll = root.findViewById<View>(R.id.scrollLmMyRequests)

        when (state) {
            is LeaveState.Loading -> {
                if (scroll.isVisible.not()) {
                    pbLoading.isVisible = true
                    layoutError.isVisible = false
                }
            }
            is LeaveState.Error -> {
                if (scroll.isVisible.not()) {
                    pbLoading.isVisible = false
                    scroll.isVisible = false
                    layoutError.isVisible = true
                    root.findViewById<TextView>(R.id.tvLmMyRequestsError).text = state.message
                }
            }
            is LeaveState.Success -> {
                pbLoading.isVisible = false
                layoutError.isVisible = false
                scroll.isVisible = true
                renderMyRequests(root, state)
            }
        }
    }

    private fun statusDisplay(request: LeaveRequest): Triple<String, Int, String> = when (request.status) {
        LM_STATUS_PENDING -> Triple("Pending", R.drawable.bg_pc_status_pending, "#C2410C")
        LM_STATUS_ACKNOWLEDGED -> Triple("Acknowledged", R.drawable.bg_pc_status_approved, "#6D28D9")
        LM_STATUS_APPROVED -> Triple("Approved", R.drawable.bg_pc_status_settled, "#059669")
        LM_STATUS_REJECTED -> Triple("Rejected", R.drawable.bg_pc_status_pending, "#B91C1C")
        else -> Triple(request.status, R.drawable.bg_pc_status_pending, "#64748B")
    }

    private fun renderMyRequests(root: View, state: LeaveState.Success) {
        val mine = state.myRequests

        val pendingCount = mine.count { it.status == LM_STATUS_PENDING }
        val acknowledgedCount = mine.count { it.status == LM_STATUS_ACKNOWLEDGED }
        val approvedCount = mine.count { it.status == LM_STATUS_APPROVED }

        bindStatTile(root, R.id.statLmMyRequestsTotal, "\uD83D\uDCCB", "My\nRequests", mine.size.toString(), "#F1F5F9", "#0F172A")
        bindStatTile(root, R.id.statLmMyRequestsPending, "\u23F3", "Pending", pendingCount.toString(), "#FFEDD5", "#C2410C")
        bindStatTile(root, R.id.statLmMyRequestsAcknowledged, "\uD83D\uDC41", "Acknowledged", acknowledgedCount.toString(), "#EDE9FE", "#6D28D9")
        bindStatTile(root, R.id.statLmMyRequestsApproved, "\u2705", "Approved", approvedCount.toString(), "#D1FAE5", "#059669")

        val container = root.findViewById<android.widget.LinearLayout>(R.id.layoutLmMyRequestsList)
        container.removeAllViews()

        if (mine.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "You haven't submitted any leave requests yet."
                textSize = 13f
                setTextColor(0xFF94A3B8.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(32), dp(8), dp(32))
            })
            return
        }

        mine.forEach { item ->
            val (label, badgeBg, badgeColor) = statusDisplay(item)
            val row = layoutInflater.inflate(R.layout.item_petty_cash_all_request_row, container, false)
            row.findViewById<TextView>(R.id.tvAllReqRowIcon).text = item.leaveType.take(1).uppercase()
            row.findViewById<TextView>(R.id.tvAllReqRowCode).text = item.requestCode
            row.findViewById<TextView>(R.id.tvAllReqRowSubtitle).text = item.leaveType
            row.findViewById<TextView>(R.id.tvAllReqRowAmount).text = formatDate(item.leaveDateMillis).substringBefore(",")
            row.findViewById<TextView>(R.id.tvAllReqRowDate).text = formatDate(item.createdAt)
            row.findViewById<TextView>(R.id.tvAllReqRowStatus).apply {
                text = label
                setTextColor(Color.parseColor(badgeColor))
                background = ContextCompat.getDrawable(requireContext(), badgeBg)
            }
            // Tapping any request (regardless of status) opens the Details
            // timeline, which itself surfaces Edit/Delete for the owner
            // while status == PENDING — no need to duplicate that logic here.
            row.setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, LeaveDetailsFragment.newInstance(branchId, item.id))
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }
            container.addView(row)
        }
    }

    private fun bindStatTile(root: View, includeId: Int, icon: String, label: String, value: String, bg: String, fg: String) {
        val statRoot = root.findViewById<View>(includeId) ?: return
        val tvIcon = statRoot.findViewById<TextView>(R.id.tvStatCardIcon)
        val tvLabel = statRoot.findViewById<TextView>(R.id.tvStatCardLabel)
        val tvValue = statRoot.findViewById<TextView>(R.id.tvStatCardValue)
        tvIcon.text = icon
        tvIcon.setTextColor(Color.parseColor(fg))
        tvIcon.background = GradientDrawable().apply {
            setColor(Color.parseColor(bg))
            cornerRadius = dp(8).toFloat()
        }
        tvLabel.text = label
        tvValue.text = value
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
