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
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Petty Cash Management — All Requests (mockup screen 8).
 *
 * Wired to PettyCashViewModel: real requests for the branch, working
 * All/My Requests/Pending/Approved/Settled tab filter, agent filter with
 * status-wise counts, bulk select (individual cards or select-all across the
 * whole filter, all pages) + role-gated bulk next-status update, and real
 * client-side pagination (5 per page — the ViewModel loads the whole
 * branch's request list in one read, there's no server-side cursor to page
 * against yet).
 */
class PettyCashAllRequestsFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private lateinit var layoutTabs: LinearLayout
    private lateinit var layoutList: LinearLayout
    private lateinit var pbLoading: View
    private lateinit var layoutError: View

    private var branchId: String = ""
    private var selectedFilter: String = FILTER_ALL
    private var selectedAgentUids: MutableSet<String> = mutableSetOf() // empty = all agents
    private var currentPage: Int = 1
    private var latestState: PettyCashState.Success? = null
    private var advancedFilter: PettyCashFilterState = PettyCashFilterState()

    // Bulk-update selection (approvers only): claim ids picked for one shared
    // status move (see showBulkUpdateDialog). Same gates as the Requests list
    // screen — send-back stays single-claim only.
    private var canBulkSelect: Boolean = false
    private var selectMode: Boolean = false
    private val selectedIds = mutableSetOf<String>()

    /** Forward transitions the signed-in user may bulk-apply from [status].
     *  Mirrors the single-claim gates in PettyCashSettlementDetailsFragment. */
    private fun bulkTargetsForStatus(status: String, roles: PettyCashUserRoles): List<String> = when (status) {
        PC_STATUS_PENDING -> listOfNotNull(
            PC_STATUS_ACKNOWLEDGED.takeIf { roles.isStaff },
            PC_STATUS_REJECTED.takeIf { roles.isStaff })
        PC_STATUS_ACKNOWLEDGED -> listOfNotNull(
            PC_STATUS_APPROVED.takeIf { roles.isCashPoc },
            PC_STATUS_REJECTED.takeIf { roles.isCashPoc })
        PC_STATUS_APPROVED -> listOfNotNull(
            PC_STATUS_SETTLE_IN_PROCESS.takeIf { roles.isAccounts })
        PC_STATUS_SETTLE_IN_PROCESS -> listOfNotNull(
            PC_STATUS_SETTLED.takeIf { roles.isAccounts })
        else -> emptyList() // settled / rejected / cancelled are terminal
    }

    private fun isBulkEligible(item: PettyCashRequest): Boolean {
        val roles = latestState?.roles ?: return false
        return bulkTargetsForStatus(item.status, roles).isNotEmpty()
    }

    /** Status-appropriate amount for totals: the stage figure, not requested. */
    private fun stageAmount(item: PettyCashRequest): Double = when (item.status) {
        PC_STATUS_SETTLED -> item.settledAmount.takeIf { it > 0 } ?: item.amount
        PC_STATUS_APPROVED, PC_STATUS_SETTLE_IN_PROCESS -> item.approvedAmount.takeIf { it > 0 } ?: item.amount
        else -> item.amount
    }

    private fun bulkDefaultAmount(item: PettyCashRequest): Double =
        item.approvedAmount.takeIf { it > 0 } ?: item.amount

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val FILTER_ALL = "all"
        private const val FILTER_MINE = "mine"
        private const val FILTER_PENDING = "pending"
        private const val FILTER_APPROVED = "approved"
        private const val FILTER_SETTLED = "settled"
        private const val PAGE_SIZE = 5

        fun newInstance(branchId: String): PettyCashAllRequestsFragment {
            val f = PettyCashAllRequestsFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_all_requests, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        layoutTabs = view.findViewById(R.id.layoutPcAllReqTabs)
        layoutList = view.findViewById(R.id.layoutPcAllReqList)
        pbLoading  = view.findViewById(R.id.pbPcAllReqLoading)
        layoutError = view.findViewById(R.id.layoutPcAllReqError)

        view.findViewById<View>(R.id.btnPcAllReqBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.tvPcAllReqAgent).setOnClickListener { showAgentPicker() }
        view.findViewById<View>(R.id.tvPcAllReqSelectMode).setOnClickListener {
            selectMode = !selectMode
            if (!selectMode) selectedIds.clear()
            // "Cancel": only exits select mode (dropping picks), never
            // applies anything. The bottom Update bar is the applier.
            view.findViewById<TextView>(R.id.tvPcAllReqSelectMode).text = if (selectMode) "Cancel" else "☑ Select"
            if (selectMode) guideIfNothingEligible()
            renderList(view)
        }
        view.findViewById<View>(R.id.tvPcAllReqSelectAll).setOnClickListener { toggleSelectAllFiltered() }
        view.findViewById<View>(R.id.btnPcAllReqBulkCancel).setOnClickListener {
            selectMode = false
            selectedIds.clear()
            view.findViewById<TextView>(R.id.tvPcAllReqSelectMode).text = "☑ Select"
            renderList(view)
        }
        view.findViewById<View>(R.id.btnPcAllReqBulkUpdate).setOnClickListener { showBulkUpdateDialog() }

        parentFragmentManager.setFragmentResultListener(PettyCashFilterState.FRAGMENT_RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            val stateBundle = bundle.getBundle(PettyCashFilterState.BUNDLE_KEY_STATE)
            advancedFilter = stateBundle?.let { PettyCashFilterState.fromBundle(it) } ?: PettyCashFilterState()
            currentPage = 1
            // Drop picks outside the new filter.
            selectedIds.retainAll(filteredRequests().filter { isBulkEligible(it) }.map { it.id }.toSet())
            view?.let { renderList(it) }
        }

        buildTabs()

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

    private fun formatDate(millis: Long): String {
        if (millis == 0L) return "—"
        return SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(millis))
    }

    private fun render(state: PettyCashState) {
        val root = view ?: return
        val scroll = root.findViewById<View>(R.id.scrollPcAllReq)

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
                root.findViewById<TextView>(R.id.tvPcAllReqError).text = state.message
                root.findViewById<View>(R.id.btnPcAllReqRetry).setOnClickListener {
                    if (branchId.isNotBlank()) viewModel.load(branchId)
                }
            }
            is PettyCashState.Success -> {
                if (!state.roles.isAnyApprover) {
                    pbLoading.isVisible = false
                    scroll.isVisible = false
                    layoutError.isVisible = true
                    root.findViewById<TextView>(R.id.tvPcAllReqError).text = "Only approvers can view this screen"
                    root.findViewById<View>(R.id.btnPcAllReqRetry).isVisible = false
                    return
                }
                pbLoading.isVisible = false
                layoutError.isVisible = false
                scroll.isVisible = true
                latestState = state
                currentPage = 1
                // Bulk update is available to every approver role — same gate
                // as the Requests list screen.
                val canBulk = state.roles.isAnyApprover
                canBulkSelect = canBulk
                root.findViewById<View>(R.id.tvPcAllReqSelectMode)?.isVisible = canBulk
                if (!canBulk) {
                    selectMode = false
                    selectedIds.clear()
                } else {
                    // Drop picks that are no longer present/eligible after reload.
                    selectedIds.retainAll(
                        state.requests.filter { isBulkEligible(it) }.map { it.id }.toSet())
                }
                buildTabs()
                renderList(root)
            }
        }
    }

    private fun buildTabs() {
        layoutTabs.removeAllViews()
        val tabs = listOf(
            Pair(FILTER_ALL, "All"),
            Pair(FILTER_MINE, "My Requests"),
            Pair(FILTER_PENDING, "Pending"),
            Pair(FILTER_APPROVED, "Approved"),
            Pair(FILTER_SETTLED, "Settled")
        )
        tabs.forEach { (key, label) ->
            val tab = layoutInflater.inflate(R.layout.item_petty_cash_filter_tab, layoutTabs, false) as TextView
            tab.text = label
            tab.setOnClickListener {
                selectedFilter = key
                currentPage = 1
                // Agent counts are tab-wise: drop picks with zero claims
                // under the newly selected tab.
                val uidsInTab = tabFiltered()
                    .map { it.requesterUid.ifBlank { "unknown" } }.toSet()
                selectedAgentUids.retainAll(uidsInTab)
                selectedIds.retainAll(filteredRequests().filter { isBulkEligible(it) }.map { it.id }.toSet())
                buildTabs()
                view?.let { renderList(it) }
            }
            styleTab(tab, key == selectedFilter)
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

    /** Maps a request's raw status to (display label, filter bucket, badge drawable, badge text color). */
    private fun statusDisplay(request: PettyCashRequest): StatusDisplay = when (request.status) {
        PC_STATUS_PENDING -> StatusDisplay("Pending", FILTER_PENDING, R.drawable.bg_pc_status_pending, "#C2410C")
        PC_STATUS_ACKNOWLEDGED -> StatusDisplay("Verified", FILTER_PENDING, R.drawable.bg_pc_status_pending, "#C2410C")
        PC_STATUS_APPROVED -> StatusDisplay("Approved", FILTER_APPROVED, R.drawable.bg_pc_status_approved, "#6D28D9")
        PC_STATUS_SETTLE_IN_PROCESS -> StatusDisplay("Settle in Process", FILTER_APPROVED, R.drawable.bg_pc_status_approved, "#6D28D9")
        PC_STATUS_SETTLED -> StatusDisplay("Settled", FILTER_SETTLED, R.drawable.bg_pc_status_settled, "#059669")
        PC_STATUS_REJECTED -> StatusDisplay("Rejected", "rejected", R.drawable.bg_pc_status_pending, "#B91C1C")
        else -> StatusDisplay(request.status, "", R.drawable.bg_pc_status_pending, "#64748B")
    }

    private data class StatusDisplay(val label: String, val bucket: String, val badgeBg: Int, val badgeColor: String)

    private fun tabLabel(): String = when (selectedFilter) {
        FILTER_MINE -> "My Requests"
        FILTER_PENDING -> "Pending"
        FILTER_APPROVED -> "Approved"
        FILTER_SETTLED -> "Settled"
        else -> "All"
    }

    /** Tab (+ advanced search) applied, agent filter NOT applied — the working
     *  set agent counts and the agent picker are built from. */
    private fun tabFiltered(): List<PettyCashRequest> {
        val all = latestState?.requests.orEmpty()
        val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val tabFiltered = when (selectedFilter) {
            FILTER_MINE -> all.filter { it.requesterUid == myUid }
            FILTER_PENDING, FILTER_APPROVED, FILTER_SETTLED -> all.filter { statusDisplay(it).bucket == selectedFilter }
            else -> all
        }
        return if (advancedFilter.isActive) tabFiltered.filter { advancedFilter.matches(it) } else tabFiltered
    }

    /** Agent options under the current tab: (uid, display name, tab-wise
     *  count), sorted by name. */
    private fun agentOptions(): List<Triple<String, String, Int>> =
        tabFiltered().groupBy { it.requesterUid.ifBlank { "unknown" } }
            .map { (uid, items) ->
                val name = items.firstOrNull()?.requesterName?.takeIf { it.isNotBlank() } ?: uid
                Triple(uid, name, items.size)
            }.sortedBy { it.second.lowercase() }

    /** Tab ∩ agents ∩ advanced — the working set for list, summary and select-all. */
    private fun filteredRequests(): List<PettyCashRequest> {
        val byTab = tabFiltered()
        if (selectedAgentUids.isEmpty()) return byTab
        return byTab.filter { it.requesterUid.ifBlank { "unknown" } in selectedAgentUids }
    }

    private fun updateAgentRow() {
        val root = view ?: return
        val chip = root.findViewById<TextView>(R.id.tvPcAllReqAgent)
        // Prune stale picks (e.g. after reload moved claims to another status).
        val options = agentOptions()
        selectedAgentUids.retainAll(options.map { it.first }.toSet())
        chip.text = when {
            selectedAgentUids.isEmpty() -> "👥 All Agents"
            selectedAgentUids.size == 1 -> {
                val opt = options.find { it.first in selectedAgentUids }
                if (opt == null) "👥 All Agents" else "👥 ${opt.second} (${opt.third})"
            }
            else -> {
                val total = options.filter { it.first in selectedAgentUids }.sumOf { it.third }
                "👥 ${selectedAgentUids.size} agents ($total)"
            }
        }
    }

    private fun updateSummary(filtered: List<PettyCashRequest>) {
        val root = view ?: return
        val total = filtered.sumOf { stageAmount(it) }
        root.findViewById<TextView>(R.id.tvPcAllReqSummary).text =
            if (filtered.isEmpty()) "No requests" else "${filtered.size} requests · Total ${pettyCashTaka(total)}"
    }

    /** Multi-select agent picker scoped to the current tab (counts are
     *  tab-wise). Empty pick = All Agents. */
    private fun showAgentPicker() {
        val options = agentOptions()
        if (options.isEmpty()) {
            Toast.makeText(requireContext(), "No requests to filter", Toast.LENGTH_SHORT).show()
            return
        }
        val ctx = requireContext()
        val checked = options.map { it.first in selectedAgentUids }.toMutableList()
        val checkBoxes = mutableListOf<android.widget.CheckBox>()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(4))
        }
        root.addView(TextView(ctx).apply {
            text = "Agents · ${tabLabel()} (${tabFiltered().size}) — empty = all"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, dp(8))
        })
        val topRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val tvSelectAll = TextView(ctx).apply {
            text = "Select all"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#059669"))
            setPadding(0, dp(4), dp(20), dp(4))
        }
        val tvClear = TextView(ctx).apply {
            text = "Clear"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#B91C1C"))
            setPadding(0, dp(4), 0, dp(4))
        }
        topRow.addView(tvSelectAll)
        topRow.addView(tvClear)
        root.addView(topRow)

        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        options.forEachIndexed { index, opt ->
            val cb = android.widget.CheckBox(ctx).apply {
                text = "${opt.second} (${opt.third})"
                textSize = 14f
                setTextColor(Color.parseColor("#0F172A"))
                isChecked = checked[index]
                setOnCheckedChangeListener { _, isChecked -> checked[index] = isChecked }
            }
            checkBoxes.add(cb)
            list.addView(cb)
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(list) }
        root.addView(scroll)

        tvSelectAll.setOnClickListener {
            for (i in checked.indices) {
                checked[i] = true
                checkBoxes[i].isChecked = true
            }
        }
        tvClear.setOnClickListener {
            for (i in checked.indices) {
                checked[i] = false
                checkBoxes[i].isChecked = false
            }
        }

        AlertDialog.Builder(ctx)
            .setTitle("Filter by agent")
            .setView(root)
            .setPositiveButton("Apply") { _, _ ->
                selectedAgentUids = options.filterIndexed { i, _ -> checked[i] }
                    .map { it.first }.toMutableSet()
                currentPage = 1
                // Drop claim picks outside the new filter.
                val eligibleIds = filteredRequests().filter { isBulkEligible(it) }.map { it.id }.toSet()
                selectedIds.retainAll(eligibleIds)
                updateAgentRow()
                view?.let { renderList(it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Toggles between picking every bulk-eligible request in the current
     *  tab+agents filter (all pages) and clearing the pick. */
    private fun toggleSelectAllFiltered() {
        val eligible = filteredRequests().filter { isBulkEligible(it) }
        if (eligible.isEmpty()) {
            Toast.makeText(requireContext(), "No bulk-eligible requests in this filter", Toast.LENGTH_SHORT).show()
            return
        }
        if (eligible.all { it.id in selectedIds }) {
            selectedIds.removeAll(eligible.map { it.id }.toSet())
        } else {
            selectedIds.addAll(eligible.map { it.id })
        }
        view?.let { renderList(it) }
    }

    /** Explains the silent dead-end: select mode is on but nothing in this
     *  filter can move forward under the signed-in user's role. */
    private fun guideIfNothingEligible() {
        val filtered = filteredRequests()
        if (filtered.isEmpty()) {
            Toast.makeText(requireContext(), "No requests in this filter", Toast.LENGTH_SHORT).show()
            return
        }
        if (filtered.any { isBulkEligible(it) }) return
        val hint = when (selectedFilter) {
            FILTER_PENDING -> "Pending → only Staff can Verify"
            FILTER_APPROVED -> "Approved → only Accounts can move to Settle"
            FILTER_SETTLED -> "Settled is terminal — nothing to move"
            else -> "Your role can't move these forward — try another tab"
        }
        Toast.makeText(requireContext(), hint, Toast.LENGTH_LONG).show()
    }

    private fun renderList(root: View) {
        val filtered = filteredRequests()
        updateAgentRow()
        updateSummary(filtered)
        updateSelectAllLabel()

        val totalPages = max(1, ceil(filtered.size / PAGE_SIZE.toDouble()).toInt())
        currentPage = currentPage.coerceIn(1, totalPages)

        val fromIndex = (currentPage - 1) * PAGE_SIZE
        val toIndex = min(fromIndex + PAGE_SIZE, filtered.size)
        val pageItems = if (fromIndex < filtered.size) filtered.subList(fromIndex, toIndex) else emptyList()

        layoutList.removeAllViews()
        if (filtered.isEmpty()) {
            layoutList.addView(TextView(requireContext()).apply {
                text = "No requests found."
                textSize = 13f
                setTextColor(0xFF94A3B8.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(40), dp(8), dp(40))
            })
        } else {
            pageItems.forEach { item ->
                val status = statusDisplay(item)
                val row = layoutInflater.inflate(R.layout.item_petty_cash_all_request_row, layoutList, false)
                row.findViewById<TextView>(R.id.tvAllReqRowIcon).text = item.requesterName.take(1).uppercase()
                row.findViewById<TextView>(R.id.tvAllReqRowCode).text = item.requestCode
                row.findViewById<TextView>(R.id.tvAllReqRowSubtitle).text = "${item.requesterName}\n${item.category}"
                val (amountPrimary, amountSecondary) = claimCardAmounts(item)
                row.findViewById<TextView>(R.id.tvAllReqRowAmount).text = amountPrimary
                row.findViewById<TextView>(R.id.tvAllReqRowSecondaryAmount).text = amountSecondary
                row.findViewById<TextView>(R.id.tvAllReqRowDate).text =
                    formatDate(if (item.requestedDate != 0L) item.requestedDate else item.createdAt)
                row.findViewById<TextView>(R.id.tvAllReqRowStatus).apply {
                    text = status.label
                    setTextColor(Color.parseColor(status.badgeColor))
                    background = androidx.core.content.ContextCompat.getDrawable(requireContext(), status.badgeBg)
                }

                // Bulk-select checkbox (select mode + eligible only). Tapping
                // the row toggles the pick instead of opening details here.
                val checkBox = row.findViewById<android.widget.CheckBox>(R.id.cbAllReqRowSelect)
                val selectable = selectMode && isBulkEligible(item)
                checkBox.isVisible = selectable
                checkBox.setOnCheckedChangeListener(null)
                checkBox.isChecked = item.id in selectedIds
                checkBox.setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedIds.add(item.id) else selectedIds.remove(item.id)
                    updateSelectAllLabel()
                    updateBulkBar()
                }

                val openDetails = View.OnClickListener {
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.container, PettyCashSettlementDetailsFragment.newInstance(branchId, item.requestCode))
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
                }
                row.setOnClickListener(
                    if (selectable) View.OnClickListener { checkBox.toggle() }
                    else openDetails
                )

                layoutList.addView(row)
            }
        }

        buildPagination(root, filtered.size, fromIndex, toIndex, totalPages)
        updateBulkBar()
    }

    private fun updateSelectAllLabel() {
        val root = view ?: return
        val eligibleInFilter = filteredRequests().filter { isBulkEligible(it) }
        root.findViewById<TextView>(R.id.tvPcAllReqSelectAll)?.apply {
            isVisible = selectMode && canBulkSelect
            text = if (eligibleInFilter.isNotEmpty() && eligibleInFilter.all { it.id in selectedIds })
                "Clear all" else "Select all"
        }
    }

    /** Bottom bulk bar: always visible in select mode (so the Update action
     *  can't be missed) and always tappable — with no picks it explains
     *  itself via the dialog's own "select first" guard instead of looking
     *  like a dead dimmed label next to Cancel. */
    private fun updateBulkBar() {
        val root = view ?: return
        val bar = root.findViewById<View>(R.id.layoutPcAllReqBulkBar)
        if (!selectMode) {
            bar.isVisible = false
            return
        }
        bar.isVisible = true
        val picked = latestState?.requests.orEmpty().filter { it.id in selectedIds }
        if (picked.isEmpty()) {
            root.findViewById<TextView>(R.id.tvPcAllReqBulkSummary).text = "Tick claims above, then Update"
        } else {
            val total = picked.sumOf { bulkDefaultAmount(it) }
            root.findViewById<TextView>(R.id.tvPcAllReqBulkSummary).text =
                "${picked.size} selected · Total ${pettyCashTaka(total)}"
        }
    }

    /** Next-status options for the picked claims, in pipeline order — only
     *  transitions the signed-in user may actually perform. A target appears
     *  when at least one picked claim can move to it; claims that can't take
     *  the chosen target are skipped (counted, not failed) at apply time. */
    private fun bulkTargetOptions(picked: List<PettyCashRequest>): List<String> {
        val roles = latestState?.roles ?: return emptyList()
        val order = listOf(
            PC_STATUS_ACKNOWLEDGED, PC_STATUS_APPROVED, PC_STATUS_SETTLE_IN_PROCESS,
            PC_STATUS_SETTLED, PC_STATUS_REJECTED)
        return order.filter { target ->
            picked.any { it.status != target && target in bulkTargetsForStatus(it.status, roles) }
        }
    }

    private fun bulkTargetLabel(target: String): String = when (target) {
        PC_STATUS_ACKNOWLEDGED -> "Verified"
        PC_STATUS_APPROVED -> "Approved"
        PC_STATUS_SETTLE_IN_PROCESS -> "Settle in Process"
        PC_STATUS_SETTLED -> "Settled"
        PC_STATUS_REJECTED -> "Rejected"
        else -> target
    }

    /** Generic bulk update: one next-status for every picked claim, with the
     *  fields that status needs (shared comment / reject reason / one payment
     *  method + one transaction id for settle). Amounts stay per-claim
     *  (each claim's own stage default). */
    private fun showBulkUpdateDialog() {
        val picked = latestState?.requests.orEmpty()
            .filter { it.id in selectedIds && isBulkEligible(it) }
        if (picked.isEmpty()) {
            Toast.makeText(requireContext(), "Select at least one request first", Toast.LENGTH_SHORT).show()
            return
        }
        val options = bulkTargetOptions(picked)
        if (options.isEmpty()) {
            Toast.makeText(requireContext(), "No bulk action available for your role on these", Toast.LENGTH_SHORT).show()
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_pc_bulk_update, null)
        val spinnerTarget = dialogView.findViewById<Spinner>(R.id.spinnerBulkUpdateTarget)
        spinnerTarget.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item,
            options.map { bulkTargetLabel(it) })
        val commentGroup = dialogView.findViewById<View>(R.id.layoutBulkUpdateCommentGroup)
        val tvCommentLabel = dialogView.findViewById<TextView>(R.id.tvBulkUpdateCommentLabel)
        val etComment = dialogView.findViewById<EditText>(R.id.etBulkUpdateComment)
        val settleGroup = dialogView.findViewById<View>(R.id.layoutBulkUpdateSettleGroup)
        val spinnerMethod = dialogView.findViewById<Spinner>(R.id.spinnerBulkUpdateMethod)
        spinnerMethod.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, arrayOf("Cash", "Bank", "bKash", "Nagad"))
        val etTrxId = dialogView.findViewById<EditText>(R.id.etBulkUpdateTrxId)

        fun refreshFieldGroups(target: String) {
            settleGroup.isVisible = target == PC_STATUS_SETTLED
            commentGroup.isVisible = target != PC_STATUS_SETTLED && target != PC_STATUS_SETTLE_IN_PROCESS
            tvCommentLabel.text = if (target == PC_STATUS_REJECTED)
                "REJECT REASON (REQUIRED, ONE FOR ALL)" else "COMMENT (OPTIONAL, ONE FOR ALL)"
            etComment.hint = if (target == PC_STATUS_REJECTED)
                "Why are these being rejected?" else "Shared note for every claim"
        }
        refreshFieldGroups(options.first())
        val listContainer = dialogView.findViewById<LinearLayout>(R.id.layoutBulkUpdateClaimList)
        fun claimRow(text: String, bold: Boolean = false, color: String = "#0F172A") =
            TextView(requireContext()).apply {
                this.text = text
                textSize = if (bold) 13f else 12.5f
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(color))
                setPadding(0, 6, 0, 6)
            }
        // Claim list re-renders with the target: a Settle run groups rows by
        // agent with per-agent subtotals (one trxId usually pays ONE agent's
        // total), every other target keeps the flat list.
        fun renderClaimList(target: String) {
            listContainer.removeAllViews()
            if (target == PC_STATUS_SETTLED) {
                val byAgent = picked.groupBy {
                    it.requesterName.takeIf { n -> n.isNotBlank() } ?: it.requesterUid.ifBlank { "—" }
                }.toList().sortedBy { it.first.lowercase() }
                if (byAgent.size > 1) {
                    listContainer.addView(claimRow(
                        "⚠ One trxId will cover ${byAgent.size} agents — split by agent if these are separate transfers.",
                        bold = true, color = "#B91C1C"))
                }
                byAgent.forEach { (agent, items) ->
                    val sub = items.sumOf { stageAmount(it) }
                    listContainer.addView(claimRow("$agent — ${items.size} claims — ${pettyCashTaka(sub)}", bold = true))
                    items.forEach { item ->
                        listContainer.addView(claimRow(
                            "  ${item.requestCode} · ${pettyCashStatusLabel(item.status)} — ${pettyCashTaka(stageAmount(item))}"))
                    }
                }
            } else {
                picked.forEach { item ->
                    listContainer.addView(claimRow(
                        "${item.requestCode} · ${item.requesterName} · ${pettyCashStatusLabel(item.status)} — ${pettyCashTaka(stageAmount(item))}"))
                }
            }
        }
        renderClaimList(options.first())
        spinnerTarget.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                refreshFieldGroups(options[pos])
                renderClaimList(options[pos])
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
        }

        val total = picked.sumOf { stageAmount(it) }
        dialogView.findViewById<TextView>(R.id.tvBulkUpdateTotal).text = "Total: ${pettyCashTaka(total)}"
        val tvProgress = dialogView.findViewById<TextView>(R.id.tvBulkUpdateProgress)

        var dialog: AlertDialog? = null
        // Last trxId the reuse warning was accepted for — re-asks if edited.
        var confirmedSettleTrx = ""
        dialog = AlertDialog.Builder(requireContext())
            .setTitle("Bulk Update (${picked.size})")
            .setView(dialogView)
            .setPositiveButton("Update", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog?.show()
        // Slide in from the left.
        dialog?.window?.let { w ->
            val attrs = w.attributes
            attrs.windowAnimations = R.style.PcBulkDialogAnimation
            w.attributes = attrs
        }
        // Override the positive button: validate first, then run without
        // auto-dismissing (progress shows on the dialog itself).
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val d = dialog ?: return@setOnClickListener
            val target = options[spinnerTarget.selectedItemPosition]
            val comment = etComment.text?.toString()?.trim().orEmpty()
            if (target == PC_STATUS_REJECTED && comment.isBlank()) {
                Toast.makeText(requireContext(), "Enter the reject reason", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val trxId = etTrxId.text?.toString()?.trim().orEmpty()
            if (target == PC_STATUS_SETTLED && trxId.isBlank()) {
                Toast.makeText(requireContext(), "Enter the transaction ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Same trxId on two payouts usually means a typo or a double
            // booking — confirm once per typed value (re-asks if edited,
            // skips the already-picked claims themselves).
            if (target == PC_STATUS_SETTLED && confirmedSettleTrx != trxId) {
                val reuse = findTrxIdReuse(latestState?.requests.orEmpty(), trxId, picked.map { it.id }.toSet())
                if (reuse.isNotEmpty()) {
                    val shown = reuse.take(5).joinToString(", ") { it.requestCode }
                    val more = if (reuse.size > 5) " +${reuse.size - 5} more" else ""
                    AlertDialog.Builder(requireContext())
                        .setTitle("⚠ Transaction ID already used")
                        .setMessage("This trxId already settled: $shown$more\n\nSettle ${picked.size} more under the same trxId?")
                        .setPositiveButton("Use anyway") { _, _ ->
                            confirmedSettleTrx = trxId
                            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                        }
                        .setNegativeButton("Back", null)
                        .show()
                    return@setOnClickListener
                }
                confirmedSettleTrx = trxId
            }
            val method = spinnerMethod.selectedItem?.toString() ?: "Cash"
            val roles = latestState?.roles
            if (roles == null) {
                Toast.makeText(requireContext(), "Still loading — try again", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val positive = d.getButton(AlertDialog.BUTTON_POSITIVE)
            val negative = d.getButton(AlertDialog.BUTTON_NEGATIVE)
            positive.isEnabled = false
            negative.isEnabled = false
            tvProgress.isVisible = true
            lifecycleScope.launch {
                var ok = 0
                var skipped = 0
                var neg = 0
                val failures = mutableListOf<String>()
                picked.forEachIndexed { index, item ->
                    tvProgress.text = "⏳ Updating ${index + 1}/${picked.size}…"
                    if (target !in bulkTargetsForStatus(item.status, roles)) {
                        skipped++
                        return@forEachIndexed
                    }
                    val result: Result<*> = when (target) {
                        PC_STATUS_ACKNOWLEDGED ->
                            viewModel.acknowledgeRequest(branchId, item.id, comment)
                        PC_STATUS_APPROVED ->
                            // Preserve Staff's pre-set verified figure when there
                            // is one (null falls back to the requested amount
                            // inside approveRequest).
                            viewModel.approveRequest(branchId, item.id, comment, item.approvedAmount.takeIf { it > 0 })
                        PC_STATUS_SETTLE_IN_PROCESS ->
                            viewModel.markReadyToSettle(branchId, item.id)
                        PC_STATUS_SETTLED ->
                            viewModel.settleRequest(branchId, item.id, method, trxId, null)
                        PC_STATUS_REJECTED ->
                            viewModel.rejectRequest(branchId, item.id, comment)
                        else -> Result.failure<Any>(IllegalStateException("Unsupported target"))
                    }
                    if (result.isSuccess) {
                        ok++
                        if ((result.getOrNull() as? PettyCashViewModel.SettleOutcome)?.negativeWarning == true) neg++
                    } else failures.add("${item.requestCode}: ${result.exceptionOrNull()?.message ?: "failed"}")
                }
                val failed = failures.size
                val label = bulkTargetLabel(target)
                tvProgress.text = if (failed == 0 && skipped == 0) "✓ $ok → $label"
                    else "✓ $ok → $label · ⚠ $failed failed · $skipped skipped"
                if (failed == 0) {
                    val skipNote = if (skipped > 0) " · $skipped skipped (wrong stage)" else ""
                    val negNote = if (neg > 0) " · ⚠ $neg went negative" else ""
                    val trxNote = if (target == PC_STATUS_SETTLED) " ($trxId)" else ""
                    Toast.makeText(requireContext(), "✓ $ok claims → $label$trxNote$skipNote$negNote", Toast.LENGTH_LONG).show()
                    d.dismiss()
                    selectMode = false
                    selectedIds.clear()
                    view?.findViewById<TextView>(R.id.tvPcAllReqSelectMode)?.text = "☑ Select"
                    if (branchId.isNotBlank()) viewModel.load(branchId) else view?.let { renderList(it) }
                } else {
                    val friendly = "⚠ $failed failed — list reloaded, retry the rest"
                    Toast.makeText(requireContext(), friendly, Toast.LENGTH_LONG).show()
                    if (isAdded) {
                        SupabaseErrorDialog.show(requireContext(), friendly, failures.joinToString("\n"))
                    }
                    positive.isEnabled = true
                    negative.isEnabled = true
                    if (branchId.isNotBlank()) viewModel.load(branchId)
                }
            }
        }
    }

    private fun buildPagination(root: View, totalCount: Int, fromIndex: Int, toIndex: Int, totalPages: Int) {
        val pageInfo = root.findViewById<TextView>(R.id.tvPcAllReqPageInfo)
        pageInfo.text = if (totalCount == 0) "No requests" else "Showing ${fromIndex + 1} to $toIndex of $totalCount"

        val container = root.findViewById<LinearLayout>(R.id.layoutPcAllReqPageButtons)
        container.removeAllViews()
        if (totalPages <= 1) return

        for (page in 1..totalPages) {
            val btn = TextView(requireContext()).apply {
                text = page.toString()
                textSize = 12f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                val isCurrent = page == currentPage
                setTextColor(if (isCurrent) Color.WHITE else Color.parseColor("#64748B"))
                background = androidx.core.content.ContextCompat.getDrawable(
                    requireContext(),
                    if (isCurrent) R.drawable.bg_pc_step_done else R.drawable.bg_pc_tab_inactive
                )
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginStart = dp(4)
                layoutParams = lp
                setOnClickListener {
                    currentPage = page
                    renderList(root)
                }
            }
            container.addView(btn)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
