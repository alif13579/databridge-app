package com.cloudx.databridge

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch
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
 *
 * myRequestsOnly (see newInstance): when true, this is a Requester's own
 * "My Requests" list rather than the branch-wide approver view --
 * requests are pre-filtered to workerUid == current user before tabs/counts
 * are built (so tab counts reflect only their own requests), the
 * approver-only access gate is skipped, the title reads "My Requests", and
 * Settlement History is hidden (that screen is branch-wide/unscoped, same
 * reason it's not linked from PettyCashMyRequestsFragment's Reports item —
 * would let a Requester browse everyone else's settlement history).
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
    private var myRequestsOnly: Boolean = false
    private var latestState: PettyCashState.Success? = null
    private var advancedFilter: PettyCashFilterState = PettyCashFilterState()

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val ARG_INITIAL_STATUS = "initial_status"
        private const val ARG_MY_REQUESTS_ONLY = "my_requests_only"
        private const val FILTER_ALL = "all"

        fun newInstance(branchId: String, initialStatus: String = FILTER_ALL, myRequestsOnly: Boolean = false): PettyCashPendingSettlementFragment {
            val f = PettyCashPendingSettlementFragment()
            f.arguments = Bundle().apply {
                putString(ARG_BRANCH_ID, branchId)
                putString(ARG_INITIAL_STATUS, initialStatus)
                putBoolean(ARG_MY_REQUESTS_ONLY, myRequestsOnly)
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
        myRequestsOnly = arguments?.getBoolean(ARG_MY_REQUESTS_ONLY) ?: false

        swipeRefresh = view.findViewById(R.id.swipeRefreshPcPending)
        layoutTabs   = view.findViewById(R.id.layoutPcPendingTabs)
        layoutList   = view.findViewById(R.id.layoutPcPendingList)
        pbLoading    = view.findViewById(R.id.pbPcPendingLoading)
        layoutError  = view.findViewById(R.id.layoutPcPendingError)

        if (myRequestsOnly) {
            view.findViewById<TextView>(R.id.tvPcPendingTitle).text = "My Requests"
            // Settlement History is branch-wide, not scoped to this user's
            // own requests -- hide it here for the same reason it stays a
            // "coming soon" toast on the Requester's Reports item.
            view.findViewById<View>(R.id.btnPcPendingHistory).isVisible = false
        }

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
        PC_STATUS_ACKNOWLEDGED -> "Verified"
        PC_STATUS_APPROVED -> "Approved"
        PC_STATUS_SETTLE_IN_PROCESS -> "Ready to Settle"
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
                if (!myRequestsOnly && !state.roles.isAnyApprover) {
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

    /** state.requests, or just this user's own when myRequestsOnly is set. */
    private fun scopedRequests(): List<PettyCashRequest> {
        val all = latestState?.requests.orEmpty()
        if (!myRequestsOnly) return all
        val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        return all.filter { it.workerUid == myUid }
    }

    private fun buildTabs() {
        layoutTabs.removeAllViews()
        val all = scopedRequests()

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
        val all = scopedRequests()
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
            // Settle (final step) gets a quick inline confirm instead of opening details --
            // Mark Ready has no extra fields to collect, so it still just opens details
            // (which shows the right stage's action, same as tapping the card itself).
            btnSettle.setOnClickListener(
                if (item.status == PC_STATUS_SETTLE_IN_PROCESS) View.OnClickListener { showQuickSettleDialog(item) }
                else openDetails
            )

            layoutList.addView(card)
        }
    }

    /** Quick inline confirm for the final Settle step, straight from the list card --
     *  skips opening PettyCashSettlementDetailsFragment. Collects the same three fields
     *  that screen's settle form does (Payment Method, Settle Amount, Transaction ID)
     *  since PettyCashViewModel.settleRequest() requires paymentMethod/trxId; a bare
     *  "Yes" without them would have to guess defaults for money-affecting fields. */
    private fun showQuickSettleDialog(item: PettyCashRequest) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pc_quick_settle, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerQsPaymentMethod)
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, arrayOf("Cash", "Bank"))
        val etAmount = dialogView.findViewById<EditText>(R.id.etQsAmount)
        val defaultAmount = item.approvedAmount.takeIf { it > 0 } ?: item.amount
        etAmount.setText(if (defaultAmount == defaultAmount.toLong().toDouble())
            defaultAmount.toLong().toString() else defaultAmount.toString())
        val etTrxId = dialogView.findViewById<EditText>(R.id.etQsTrxId)

        AlertDialog.Builder(requireContext())
            .setTitle("Settle Confirm")
            .setMessage("${item.requestCode} settle করবেন?")
            .setView(dialogView)
            .setPositiveButton("Yes") { _, _ ->
                val amount = etAmount.text?.toString()?.trim()?.toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    Toast.makeText(requireContext(), "Enter a valid settle amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val paymentMethod = spinner.selectedItem?.toString() ?: "Cash"
                val typedTrxId = etTrxId.text?.toString()?.trim().orEmpty()
                val trxId = typedTrxId.ifBlank { "TXN-${System.currentTimeMillis().toString().takeLast(5)}" }
                lifecycleScope.launch {
                    val result = viewModel.settleRequest(branchId, item.id, paymentMethod, trxId, amount,
                        onSupabaseResult = { ok ->
                            activity?.runOnUiThread {
                                if (isAdded) Toast.makeText(requireContext(),
                                    if (ok) "✓ Supabase saved" else "⚠ Supabase save failed", Toast.LENGTH_SHORT).show()
                            }
                        })
                    if (isAdded) {
                        Toast.makeText(requireContext(),
                            if (result.isSuccess) "✓ Settled" else "⚠ Settle failed: ${result.exceptionOrNull()?.message}",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
