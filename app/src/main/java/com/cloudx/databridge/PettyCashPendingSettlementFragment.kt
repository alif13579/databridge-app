package com.cloudx.databridge

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Petty Cash Management — Requests / Settlement List (mockup screen 3).
 *
 * Wired to PettyCashViewModel. Originally scoped to only PC_STATUS_APPROVED
 * (POC-approved, waiting for Accounts) with a High/Normal priority filter —
 * changed per feedback: this screen now shows requests of EVERY status, with
 * tabs generated dynamically from whatever statuses actually exist in the
 * branch's requests (so a Requester's freshly-submitted PENDING_TEAM_ALIGN
 * request is visible here too, not just PC_STATUS_APPROVED ones). "All"
 * always shows everything; each other tab is one status, labeled with a
 * human-readable name and a live count.
 */
class PettyCashPendingSettlementFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var layoutTabs: LinearLayout
    private lateinit var layoutList: LinearLayout
    private lateinit var pbLoading: View
    private lateinit var layoutError: View

    private var branchId: String = ""
    private var selectedStatus: String = FILTER_ALL // FILTER_ALL or one of the PC_STATUS_* constants
    private var latestState: PettyCashState.Success? = null
    private var advancedFilter: PettyCashFilterState = PettyCashFilterState()

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val ARG_INITIAL_STATUS = "initial_status"
        private const val FILTER_ALL = "all"

        fun newInstance(branchId: String, initialStatus: String = FILTER_ALL): PettyCashPendingSettlementFragment {
            val f = PettyCashPendingSettlementFragment()
            f.arguments = Bundle().apply {
                putString(ARG_BRANCH_ID, branchId)
                putString(ARG_INITIAL_STATUS, initialStatus)
            }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_pending_settlement, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()
        selectedStatus = arguments?.getString(ARG_INITIAL_STATUS).orEmpty().ifBlank { FILTER_ALL }

        swipeRefresh = view.findViewById(R.id.swipeRefreshPcPending)
        layoutTabs   = view.findViewById(R.id.layoutPcPendingTabs)
        layoutList   = view.findViewById(R.id.layoutPcPendingList)
        pbLoading    = view.findViewById(R.id.pbPcPendingLoading)
        layoutError  = view.findViewById(R.id.layoutPcPendingError)

        view.findViewById<View>(R.id.btnPcPendingBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btnPcPendingHistory).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashSettlementHistoryFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        view.findViewById<View>(R.id.btnPcPendingFilter).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashFilterFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        swipeRefresh.setOnRefreshListener { if (branchId.isNotBlank()) viewModel.load(branchId) }

        parentFragmentManager.setFragmentResultListener(PettyCashFilterState.FRAGMENT_RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            val stateBundle = bundle.getBundle(PettyCashFilterState.BUNDLE_KEY_STATE)
            advancedFilter = stateBundle?.let { PettyCashFilterState.fromBundle(it) } ?: PettyCashFilterState()
            renderList()
        }

        viewModel.state.observe(viewLifecycleOwner) { state -> render(state) }
        if (branchId.isBlank()) {
            render(PettyCashState.Error("No branch selected"))
        } else {
            viewModel.load(branchId)
        }
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        return "\u09F3${NumberFormat.getNumberInstance(Locale.US).format(whole)}"
    }

    private fun statusLabel(status: String): String = when (status) {
        PC_STATUS_PENDING -> "Pending"
        PC_STATUS_ACKNOWLEDGED -> "Acknowledged"
        PC_STATUS_APPROVED -> "Approved"
        PC_STATUS_SETTLE_IN_PROCESS -> "Settle in Process"
        PC_STATUS_SETTLED -> "Settled"
        PC_STATUS_REJECTED -> "Rejected"
        else -> status
    }

    private fun render(state: PettyCashState) {
        swipeRefresh.isRefreshing = false
        when (state) {
            is PettyCashState.Loading -> {
                pbLoading.isVisible = true
                layoutError.isVisible = false
            }
            is PettyCashState.Error -> {
                pbLoading.isVisible = false
                layoutError.isVisible = true
                view?.findViewById<TextView>(R.id.tvPcPendingError)?.text = state.message
                view?.findViewById<View>(R.id.btnPcPendingRetry)?.setOnClickListener {
                    if (branchId.isNotBlank()) viewModel.load(branchId)
                }
            }
            is PettyCashState.Success -> {
                if (!state.roles.isAnyApprover) {
                    pbLoading.isVisible = false
                    layoutError.isVisible = true
                    view?.findViewById<TextView>(R.id.tvPcPendingError)?.text = "Only approvers can view this screen"
                    view?.findViewById<View>(R.id.btnPcPendingRetry)?.isVisible = false
                    return
                }
                pbLoading.isVisible = false
                layoutError.isVisible = false
                latestState = state
                buildTabs()
                renderList()
            }
        }
    }

    private fun buildTabs() {
        layoutTabs.removeAllViews()
        val all = latestState?.requests.orEmpty()

        // Dynamic tabs: one per unique status actually present, in a fixed
        // canonical order (rather than whatever order they happen to appear
        // in the data) so tabs don't reshuffle as requests move through the
        // approval chain.
        val canonicalOrder = listOf(PC_STATUS_PENDING, PC_STATUS_ACKNOWLEDGED, PC_STATUS_APPROVED, PC_STATUS_SETTLE_IN_PROCESS, PC_STATUS_SETTLED, PC_STATUS_REJECTED)
        val presentStatuses = canonicalOrder.filter { status -> all.any { it.status == status } }

        val tabs = mutableListOf(Pair(FILTER_ALL, "All (${all.size})"))
        presentStatuses.forEach { status ->
            val count = all.count { it.status == status }
            tabs.add(Pair(status, "${statusLabel(status)} ($count)"))
        }

        tabs.forEach { (key, label) ->
            val tab = layoutInflater.inflate(R.layout.item_petty_cash_filter_tab, layoutTabs, false) as TextView
            tab.text = label
            tab.setOnClickListener {
                selectedStatus = key
                buildTabs()
                renderList()
            }
            styleTab(tab, key == selectedStatus)
            layoutTabs.addView(tab)
        }
    }

    private fun styleTab(tab: TextView, active: Boolean) {
        if (active) {
            tab.setTextColor(Color.parseColor("#059669"))
            tab.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_pc_tab_active)
        } else {
            tab.setTextColor(Color.parseColor("#64748B"))
            tab.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_pc_tab_inactive)
        }
    }

    private fun formatDateTime(millis: Long): String {
        if (millis == 0L) return "—"
        return SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(millis))
    }

    /** Status-appropriate secondary line — what to show instead of a hardcoded "POC Approved:" for every card. */
    private fun statusInfoLine(item: PettyCashRequest): Pair<String, String> = when (item.status) {
        PC_STATUS_PENDING -> "Submitted: ${formatDateTime(item.createdAt)}" to "By: ${item.workerName}"
        PC_STATUS_ACKNOWLEDGED -> "Acknowledged: ${formatDateTime(item.staffAt)}" to "By: ${item.staffByName.ifBlank { "—" }}"
        PC_STATUS_APPROVED -> "Approved: ${formatDateTime(item.pocApprovedAt)}" to "By: ${item.pocApprovedByName.ifBlank { "—" }}"
        PC_STATUS_SETTLE_IN_PROCESS -> "Ready to Settle: ${formatDateTime(item.settleInProcessAt)}" to "By: ${item.settleInProcessByName.ifBlank { "—" }}"
        PC_STATUS_SETTLED -> "Settled: ${formatDateTime(item.settledAt)}" to "By: ${item.settledByName.ifBlank { "—" }}"
        PC_STATUS_REJECTED -> "Rejected: ${formatDateTime(item.rejectedAt)}" to "By: ${item.rejectedByName.ifBlank { "—" }}"
        else -> "Submitted: ${formatDateTime(item.createdAt)}" to "By: ${item.workerName}"
    }

    private fun renderList() {
        val state = latestState ?: return
        val all = state.requests
        val statusFiltered = if (selectedStatus == FILTER_ALL) all else all.filter { it.status == selectedStatus }
        val filtered = if (advancedFilter.isActive) statusFiltered.filter { advancedFilter.matches(it) } else statusFiltered
        val canSettle = state.roles.isAccounts

        layoutList.removeAllViews()
        if (filtered.isEmpty()) {
            layoutList.addView(TextView(requireContext()).apply {
                text = "No requests found."
                textSize = 13f
                setTextColor(0xFF94A3B8.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(40), dp(8), dp(40))
            })
            return
        }
        filtered.sortedByDescending { it.updatedAt }.forEach { item ->
            val card = layoutInflater.inflate(R.layout.item_petty_cash_settlement_card, layoutList, false)
            card.findViewById<TextView>(R.id.tvPsCardCode).text = item.requestCode
            card.findViewById<TextView>(R.id.tvPsCardWorker).text = item.workerName
            card.findViewById<TextView>(R.id.tvPsCardCategory).text = item.category
            card.findViewById<TextView>(R.id.tvPsCardAmount).text = taka(item.amount)

            val (infoLine, byLine) = statusInfoLine(item)
            card.findViewById<TextView>(R.id.tvPsCardApprovedInfo).text = infoLine
            card.findViewById<TextView>(R.id.tvPsCardApprovedBy).text = byLine

            val tvPriority = card.findViewById<TextView>(R.id.tvPsCardPriority)
            tvPriority.isVisible = item.priority == PC_PRIORITY_HIGH
            if (item.priority == PC_PRIORITY_HIGH) tvPriority.text = "High"

            // The inline button here just navigates to Settlement Details,
            // which shows whatever action actually fits the request's real
            // stage (Acknowledge/Approve/Mark Ready/Settle Now). Label and
            // visibility here are just a preview of what that action will be.
            val btnSettle = card.findViewById<TextView>(R.id.btnPsCardSettle)
            when {
                canSettle && item.status == PC_STATUS_APPROVED -> {
                    btnSettle.isVisible = true
                    btnSettle.text = "Mark Ready"
                }
                canSettle && item.status == PC_STATUS_SETTLE_IN_PROCESS -> {
                    btnSettle.isVisible = true
                    btnSettle.text = "Settle"
                }
                else -> btnSettle.isVisible = false
            }

            val openDetails = View.OnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, PettyCashSettlementDetailsFragment.newInstance(branchId, item.requestCode))
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }
            card.setOnClickListener(openDetails)
            btnSettle.setOnClickListener(openDetails)

            layoutList.addView(card)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
