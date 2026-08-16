package com.cloudx.databridge

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.util.Locale

/**
 * Petty Cash Management — Dashboard.
 *
 * Wired to PettyCashViewModel. Shows a different summary per petty-cash
 * role instead of always rendering the Accounts view:
 *   - Accounts:     Available Balance / Total Fund hero, Settlement Queue
 *                    (requests in PC_STATUS_APPROVED or
 *                    PC_STATUS_SETTLE_IN_PROCESS, ready to settle).
 *   - Cash POC:      "POC Summary" hero (Total Requests + Pending/Approved/
 *                    Rejected counts), Pending For Approval list of
 *                    PC_STATUS_ACKNOWLEDGED requests awaiting their approval.
 *   - Team Aligned:  Same shape as Cash POC, but Pending For Approval is
 *                    PC_STATUS_PENDING requests awaiting their acknowledgement.
 *
 * Someone holding more than one of these roles (e.g. Cash POC + Accounts)
 * gets a role-switcher toggle at the top rather than one role silently
 * winning — see [RoleView] and [buildRoleToggle].
 *
 * Entry point: reached from the drawer's "Petty Cash" item (top-level, see
 * MainActivity), or from Cash Management's related-feature card.
 */
class PettyCashDashboardFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var layoutContent: View
    private lateinit var tvDashboardTitle: TextView
    private lateinit var tvDashboardSubtitle: TextView
    private lateinit var tvBranchName: TextView
    private lateinit var layoutRoleToggle: LinearLayout
    private lateinit var cardAvailableBalanceHero: View
    private lateinit var cardSummaryHero: View
    private lateinit var tvSummaryRoleLabel: TextView
    private lateinit var tvSummaryTotalRequests: TextView
    private lateinit var tvAvailableBalance: TextView
    private lateinit var cardTotalFund: View
    private lateinit var tvTotalFund: TextView
    private lateinit var tvQueueTitle: TextView
    private lateinit var tvViewAllQueue: TextView
    private lateinit var layoutQueueList: LinearLayout
    private lateinit var layoutNoRoleState: View

    private var branchId: String = ""
    private val db = FirebaseDatabase.getInstance()
    private var branchNames: Map<String, String> = emptyMap()

    /** Which role's dashboard is currently on screen. */
    private enum class RoleView { ACCOUNTS, CASH_POC, TEAM_ALIGNED }
    private var selectedView: RoleView? = null

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        // Shared with PettyCashMyRequestsFragment so both screens remember
        // the same last-picked branch.
        private const val PREF_KEY_SELECTED_BRANCH = "pc_selected_branch_id"
        fun newInstance(branchId: String): PettyCashDashboardFragment {
            val f = PettyCashDashboardFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()
        // Restore the last branch this user picked here, so switching branches
        // in setupBranchSwitcher() doesn't silently reset back to the default
        // (MainActivity's branchId) every time this screen is reopened. Only
        // trusted if it's still one of the user's currently assigned branches
        // — access can change between sessions.
        branchPrefs().getString(PREF_KEY_SELECTED_BRANCH, null)
            ?.takeIf { it in RbacManager.current.branchIds }
            ?.let { branchId = it }

        // Defense-in-depth: MainActivity's drawer routing already sends
        // Requester-only users to PettyCashMyRequestsFragment instead of
        // here, but this guard covers anyone who reaches this fragment
        // some other way (e.g. a stale back-stack entry after a permission
        // change) without the full approver Dashboard permission.
        if (!RbacManager.hasPermission("nav_petty_cash")) {
            Toast.makeText(requireContext(), "You don't have access to this screen", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            return
        }

        swipeRefresh    = view.findViewById(R.id.swipeRefreshPcDashboard)
        layoutContent   = view.findViewById(R.id.layoutPcDashboardContent)
        tvDashboardTitle = view.findViewById(R.id.tvPcDashboardTitle)
        tvDashboardSubtitle = view.findViewById(R.id.tvPcDashboardSubtitle)
        tvBranchName    = view.findViewById(R.id.tvPcDashboardBranchName)
        layoutRoleToggle = view.findViewById(R.id.layoutPcRoleToggle)
        cardAvailableBalanceHero = view.findViewById(R.id.cardPcAvailableBalanceHero)
        cardSummaryHero = view.findViewById(R.id.cardPcSummaryHero)
        tvSummaryRoleLabel = view.findViewById(R.id.tvPcSummaryRoleLabel)
        tvSummaryTotalRequests = view.findViewById(R.id.tvPcSummaryTotalRequests)
        tvAvailableBalance = view.findViewById(R.id.tvPcAvailableBalance)
        cardTotalFund   = view.findViewById(R.id.cardPcTotalFund)
        tvTotalFund     = view.findViewById(R.id.tvPcTotalFund)
        tvQueueTitle    = view.findViewById(R.id.tvPcQueueTitle)
        tvViewAllQueue  = view.findViewById(R.id.tvPcViewAllQueue)
        layoutQueueList = view.findViewById(R.id.layoutPcQueueList)
        layoutNoRoleState = view.findViewById(R.id.layoutPcNoRoleState)

        view.findViewById<View>(R.id.btnPcDashboardBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        val btnNewRequest = view.findViewById<View>(R.id.btnPcDashboardNewRequest)
        // Only roles explicitly granted "petty_cash_requester" (Access Manager)
        // can submit new requests — e.g. Pickup Agent, Delivery Agent. Whether
        // someone can also see this whole Dashboard (nav_petty_cash) is a
        // separate permission; this button doesn't assume the two overlap.
        btnNewRequest.isVisible = RbacManager.hasPermission("petty_cash_requester")
        btnNewRequest.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashRequestCreateFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        swipeRefresh.setOnRefreshListener { viewModel.load(branchId) }

        setupBranchSwitcher()

        view.findViewById<View>(R.id.cardPcTotalFund).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashWalletSummaryFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
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
        val formatted = NumberFormat.getNumberInstance(Locale.US).format(whole)
        return "\u09F3$formatted"
    }

    // ── Branch switcher ──────────────────────────────────────────────────────
    // Lets a user with more than one assigned branch (RbacManager.current.branchIds)
    // pick which branch's Petty Cash data to view — same pattern as
    // CashManagementHomeFragment's switcher. Hidden entirely for the common
    // single-branch case. Starts on branchIds.firstOrNull(), same branch
    // MainActivity already passed into newInstance(); switching here just
    // reloads this screen against the new branchId.
    private fun setupBranchSwitcher() {
        val branchIds = RbacManager.current.branchIds
        if (branchIds.size <= 1) return

        val arrow = ContextCompat.getDrawable(requireContext(), R.drawable.ic_arrow_drop_down_white)?.mutate()
        arrow?.setTint(Color.parseColor("#0F172A"))
        tvBranchName.setCompoundDrawablesWithIntrinsicBounds(null, null, arrow, null)
        tvBranchName.isVisible = true
        tvBranchName.setOnClickListener { showBranchPicker(branchIds) }

        // Seed with the primary branch's name, already resolved by RbacManager at
        // login, so there's no flash of a raw branch id while the rest load below.
        val primaryId = branchIds.first()
        if (RbacManager.current.branchName.isNotBlank()) {
            branchNames = mapOf(primaryId to RbacManager.current.branchName)
        }
        tvBranchName.text = branchNames[branchId] ?: "Branch"

        viewLifecycleOwner.lifecycleScope.launch {
            val resolved = coroutineScope {
                branchIds.filter { it !in branchNames }.associateWith { id ->
                    async {
                        runCatching {
                            db.reference.child("branches/$id/name").get().await().getValue(String::class.java)
                        }.getOrNull()
                    }
                }.mapValues { (id, deferred) -> deferred.await()?.takeIf { it.isNotBlank() } ?: id }
            }
            branchNames = branchNames + resolved
            tvBranchName.text = branchNames[branchId] ?: branchId
        }
    }

    private fun showBranchPicker(branchIds: List<String>) {
        val labels = branchIds.map { branchNames[it] ?: it }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Switch branch")
            .setItems(labels) { _, index ->
                val newBranchId = branchIds[index]
                if (newBranchId != branchId) {
                    branchId = newBranchId
                    branchPrefs().edit().putString(PREF_KEY_SELECTED_BRANCH, branchId).apply()
                    tvBranchName.text = branchNames[branchId] ?: branchId
                    viewModel.load(branchId)
                }
            }
            .show()
    }

    // Same "databridge_toggles" SharedPreferences file CallCenterFragment's
    // filter persistence uses (see PettyCashMyRequestsFragment for the
    // Requester-view counterpart of this same key).
    private fun branchPrefs() =
        requireContext().getSharedPreferences("databridge_toggles", android.content.Context.MODE_PRIVATE)

    private fun render(state: PettyCashState) {
        val root = view ?: return
        val pbLoading = root.findViewById<View>(R.id.pbPcDashboardLoading)
        val layoutError = root.findViewById<View>(R.id.layoutPcDashboardError)

        swipeRefresh.isRefreshing = false

        when (state) {
            is PettyCashState.Loading -> {
                pbLoading.isVisible = true
                layoutError.isVisible = false
                layoutContent.isVisible = false
                layoutNoRoleState.isVisible = false
            }
            is PettyCashState.Error -> {
                pbLoading.isVisible = false
                layoutContent.isVisible = false
                layoutNoRoleState.isVisible = false
                layoutError.isVisible = true
                root.findViewById<TextView>(R.id.tvPcDashboardError).text = state.message
                root.findViewById<View>(R.id.btnPcDashboardRetry).setOnClickListener {
                    if (branchId.isNotBlank()) viewModel.load(branchId)
                }
            }
            is PettyCashState.Success -> {
                pbLoading.isVisible = false
                layoutError.isVisible = false
                val views = availableViews(state.roles)
                if (views.isEmpty()) {
                    // The Requester (petty_cash_requester) permission that gates
                    // "New Request" is separate from these three, and nav_petty_cash
                    // itself is granted per-role rather than per-branch -- so someone
                    // can legitimately reach this screen (e.g. via the branch
                    // switcher) for a branch where they hold none of Team Aligned /
                    // Cash POC / Accounts. Showing an explicit "no role here" state
                    // instead of silently falling back to a fake Accounts view,
                    // which used to render real balance numbers with the Deposit
                    // action hidden -- looked like a broken Accounts dashboard
                    // rather than what it actually was: wrong branch for this role.
                    //
                    // But if they DO hold petty_cash_requester, this Dashboard was
                    // simply the wrong screen for them from the start (same case the
                    // drawer's routing already avoids for a pure Requester -- see
                    // MainActivity's nav_petty_cash handler) -- hand off to the
                    // screen that's actually meaningful to them instead of leaving
                    // them stuck on a dead end.
                    if (RbacManager.hasPermission("petty_cash_requester")) {
                        parentFragmentManager.popBackStack()
                        parentFragmentManager.beginTransaction()
                            .replace(R.id.container, PettyCashMyRequestsFragment.newInstance(branchId))
                            .addToBackStack(null)
                            .commitAllowingStateLoss()
                        return
                    }
                    layoutContent.isVisible = false
                    layoutNoRoleState.isVisible = true
                    layoutRoleToggle.isVisible = false
                } else {
                    layoutContent.isVisible = true
                    layoutNoRoleState.isVisible = false
                    renderSuccess(root, state, views)
                }
            }
        }
    }

    /** Which role dashboards this signed-in user can switch between, in display priority order. */
    private fun availableViews(roles: PettyCashUserRoles): List<RoleView> = listOfNotNull(
        RoleView.ACCOUNTS.takeIf { roles.isAccounts },
        RoleView.CASH_POC.takeIf { roles.isCashPoc },
        RoleView.TEAM_ALIGNED.takeIf { roles.isTeamAligned }
    )

    private fun renderSuccess(root: View, state: PettyCashState.Success, views: List<RoleView>) {
        if (selectedView == null || selectedView !in views) {
            selectedView = views.first()
        }

        buildRoleToggle(views)
        updateGreeting(selectedView!!)

        when (selectedView) {
            RoleView.CASH_POC -> renderApproverSummary(root, state, RoleView.CASH_POC)
            RoleView.TEAM_ALIGNED -> renderApproverSummary(root, state, RoleView.TEAM_ALIGNED)
            else -> renderAccountsSummary(root, state)
        }

        wireQuickActions(root, state.roles)
    }

    /** Horizontal role-switcher chips — only shown when the user holds more than one petty-cash role. */
    private fun buildRoleToggle(views: List<RoleView>) {
        layoutRoleToggle.removeAllViews()
        if (views.size <= 1) {
            layoutRoleToggle.isVisible = false
            return
        }
        layoutRoleToggle.isVisible = true
        views.forEach { roleView ->
            val chip = layoutInflater.inflate(R.layout.item_petty_cash_filter_tab, layoutRoleToggle, false) as TextView
            chip.text = roleLabel(roleView)
            chip.setOnClickListener {
                selectedView = roleView
                viewModel.state.value?.let { s -> if (s is PettyCashState.Success) renderSuccess(requireView(), s, views) }
            }
            val active = roleView == selectedView
            chip.setTextColor(Color.parseColor(if (active) "#059669" else "#64748B"))
            chip.background = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), if (active) R.drawable.bg_pc_tab_active else R.drawable.bg_pc_tab_inactive
            )
            layoutRoleToggle.addView(chip)
        }
    }

    private fun roleLabel(roleView: RoleView): String = when (roleView) {
        RoleView.ACCOUNTS -> "Accounts"
        RoleView.CASH_POC -> "Petty Cash POC"
        RoleView.TEAM_ALIGNED -> "Team Aligned"
    }

    // "Hi Accountant, welcome back" style greeting keyed to the *detected*
    // role rather than a generic label, so opening this screen doubles as a
    // quick sanity check that role resolution picked up the right thing for
    // this branch — if someone expected to land as Accounts but this reads
    // "Hi Team Aligned, welcome back" instead, that's immediately visible
    // instead of only showing up once they notice a missing action button.
    private fun updateGreeting(roleView: RoleView) {
        val greetingRole = when (roleView) {
            RoleView.ACCOUNTS -> "Accountant"
            RoleView.CASH_POC -> "Petty Cash POC"
            RoleView.TEAM_ALIGNED -> "Team Aligned"
        }
        tvDashboardSubtitle.text = "Hi $greetingRole, welcome back"
    }

    // ── Accounts summary: Available Balance / Total Fund / Settlement Queue ────

    private fun renderAccountsSummary(root: View, state: PettyCashState.Success) {
        tvDashboardTitle.text = "Accounts"
        cardAvailableBalanceHero.isVisible = true
        cardSummaryHero.isVisible = false
        cardTotalFund.isVisible = true

        tvAvailableBalance.text = taka(state.walletBalance)
        tvTotalFund.text = taka(state.totalFund)

        val pendingCount = state.requests.count { it.status == PC_STATUS_PENDING || it.status == PC_STATUS_ACKNOWLEDGED }
        val approvedWaitingCount = state.requests.count { it.status == PC_STATUS_APPROVED || it.status == PC_STATUS_SETTLE_IN_PROCESS }
        val settledThisMonthCount = state.requests.count { req ->
            if (req.status != PC_STATUS_SETTLED || req.settledAt == 0L) return@count false
            val cal = java.util.Calendar.getInstance()
            val nowMonth = cal.get(java.util.Calendar.MONTH)
            val nowYear = cal.get(java.util.Calendar.YEAR)
            cal.timeInMillis = req.settledAt
            cal.get(java.util.Calendar.MONTH) == nowMonth && cal.get(java.util.Calendar.YEAR) == nowYear
        }

        bindStatCard(root, R.id.statPcPendingApproval, "\u23F3", "Pending\nApproval", taka(state.pendingApprovalTotal), "#FFEDD5", "#C2410C", count = pendingCount) {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashPendingSettlementFragment.newInstance(branchId, PC_STATUS_PENDING))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        bindStatCard(root, R.id.statPcApprovedSettlement, "\u23F3", "Approved\n(Settlement)", taka(state.approvedWaitingSettlementTotal), "#EDE9FE", "#6D28D9", count = approvedWaitingCount) {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashPendingSettlementFragment.newInstance(branchId, PC_STATUS_APPROVED))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        bindStatCard(root, R.id.statPcSettledMonth, "\u2705", "Settled\nThis Month", taka(state.settledThisMonthTotal), "#D1FAE5", "#059669", count = settledThisMonthCount) {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashPendingSettlementFragment.newInstance(branchId, PC_STATUS_SETTLED))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        val queue = state.pendingSettlementQueue
        tvQueueTitle.text = "Settlement Queue (${queue.size})"
        tvViewAllQueue.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashPendingSettlementFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        buildQueueList(queue, canSettle = true, showSettleAction = true)
    }

    // ── Cash POC / Team Aligned summary: request counts + Pending For Approval ─

    private fun renderApproverSummary(root: View, state: PettyCashState.Success, roleView: RoleView) {
        tvDashboardTitle.text = roleLabel(roleView)
        cardAvailableBalanceHero.isVisible = false
        cardSummaryHero.isVisible = true
        cardTotalFund.isVisible = false

        // What "awaiting your action" means differs by stage: Team Aligned acts
        // on freshly-submitted (PENDING) requests, Cash POC acts on requests
        // Team Aligned has already acknowledged (ACKNOWLEDGED).
        val awaitingStatus = if (roleView == RoleView.TEAM_ALIGNED) PC_STATUS_PENDING else PC_STATUS_ACKNOWLEDGED
        val all = state.requests
        val pending = all.filter { it.status == awaitingStatus }
        // "Approved" here means requests that made it past this role's stage
        // (this role acted on them, or they're further along the chain).
        val approved = all.filter { it.status !in setOf(PC_STATUS_PENDING, PC_STATUS_ACKNOWLEDGED, PC_STATUS_REJECTED) ||
            (roleView == RoleView.TEAM_ALIGNED && it.status == PC_STATUS_ACKNOWLEDGED) }
        val rejected = all.filter { it.status == PC_STATUS_REJECTED }

        tvSummaryRoleLabel.text = if (roleView == RoleView.TEAM_ALIGNED) "Team Summary" else "POC Summary"
        tvSummaryTotalRequests.text = all.size.toString()

        bindStatCard(root, R.id.statPcPendingApproval, "\u23F3", "Pending\nApproval", pending.size.toString(), "#FFEDD5", "#C2410C") {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashPendingSettlementFragment.newInstance(branchId, awaitingStatus))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        bindStatCard(root, R.id.statPcApprovedSettlement, "\u2705", "Approved", approved.size.toString(), "#D1FAE5", "#059669") {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashPendingSettlementFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        bindStatCard(root, R.id.statPcSettledMonth, "\u274C", "Rejected", rejected.size.toString(), "#FEE2E2", "#B91C1C") {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashPendingSettlementFragment.newInstance(branchId, PC_STATUS_REJECTED))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        tvQueueTitle.text = "Pending For Approval (${pending.size})"
        // "View all" here means "the rest of what's awaiting me at this
        // stage" -- same status filter as the stat card above, not a
        // generic unfiltered browse-all (that's what made this a bug: it
        // used to always open the unfiltered list regardless of role).
        tvViewAllQueue.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashPendingSettlementFragment.newInstance(branchId, awaitingStatus))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        // Approvers here can't settle cash (that's Accounts-only) — tapping a
        // row just opens Settlement Details, where the Acknowledge/Approve
        // action actually lives.
        buildQueueList(pending.sortedByDescending { it.createdAt }, canSettle = false, showSettleAction = false)
    }

    private fun buildQueueList(items: List<PettyCashRequest>, canSettle: Boolean, showSettleAction: Boolean) {
        layoutQueueList.removeAllViews()
        if (items.isEmpty()) {
            layoutQueueList.addView(TextView(requireContext()).apply {
                text = "Nothing here right now."
                textSize = 13f
                setTextColor(0xFF94A3B8.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(20), dp(8), dp(20))
            })
            return
        }
        items.take(5).forEach { item ->
            val row = layoutInflater.inflate(R.layout.item_petty_cash_queue_row, layoutQueueList, false)
            row.findViewById<TextView>(R.id.tvQueueRowAvatar).text = item.workerName.take(1).uppercase()
            row.findViewById<TextView>(R.id.tvQueueRowCode).text = item.requestCode
            row.findViewById<TextView>(R.id.tvQueueRowSubtitle).text = "${item.workerName}\n${item.category}"
            row.findViewById<TextView>(R.id.tvQueueRowAmount).text = taka(item.amount)
            row.findViewById<TextView>(R.id.tvQueueRowStatus).text = when (item.status) {
                PC_STATUS_PENDING -> "Pending"
                PC_STATUS_ACKNOWLEDGED -> "Acknowledged"
                PC_STATUS_SETTLE_IN_PROCESS -> "Ready to Settle"
                PC_STATUS_APPROVED -> "Approved"
                else -> "Approved"
            }

            val btnSettle = row.findViewById<TextView>(R.id.btnQueueRowSettle)
            btnSettle.isVisible = showSettleAction && canSettle
            btnSettle.text = if (item.status == PC_STATUS_SETTLE_IN_PROCESS) "Settle" else "Mark Ready"
            val openDetails = View.OnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, PettyCashSettlementDetailsFragment.newInstance(branchId, item.requestCode))
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }
            row.setOnClickListener(openDetails)
            btnSettle.setOnClickListener(openDetails)

            layoutQueueList.addView(row)
        }
    }

    private fun bindStatCard(root: View, includeId: Int, icon: String, label: String, value: String, bg: String, fg: String, count: Int? = null, onClick: () -> Unit) {
        val statRoot = root.findViewById<View>(includeId) ?: return
        val tvIcon = statRoot.findViewById<TextView>(R.id.tvStatCardIcon)
        val tvLabel = statRoot.findViewById<TextView>(R.id.tvStatCardLabel)
        val tvValue = statRoot.findViewById<TextView>(R.id.tvStatCardValue)
        val tvCount = statRoot.findViewById<TextView>(R.id.tvStatCardCount)
        tvIcon.text = icon
        tvIcon.setTextColor(Color.parseColor(fg))
        tvIcon.background = roundedDrawable(bg, dp(8))
        tvLabel.text = label
        tvValue.text = value
        // Mockup shows both a request count and a taka total on each Accounts
        // stat tile (e.g. "10" and "৳21,350") -- the count line is optional
        // since the Team Aligned/Cash POC tiles this same include is reused
        // for already show a bare count as the main value and don't need a
        // second one under it.
        tvCount.isVisible = count != null
        if (count != null) tvCount.text = "$count requests"
        statRoot.isClickable = true
        statRoot.isFocusable = true
        statRoot.setOnClickListener { onClick() }
    }

    private fun wireQuickActions(root: View, roles: PettyCashUserRoles) {
        val actionDeposit = root.findViewById<View>(R.id.actionPcDepositFund)
        val actionRequests = root.findViewById<View>(R.id.actionPcPendingSettlement)
        val actionAllRequests = root.findViewById<View>(R.id.actionPcAllRequests)
        val actionReports = root.findViewById<View>(R.id.actionPcReports)

        // Deposit Fund is Accounts-only — it moves money into the wallet, not
        // something Team Aligned or Cash POC should be able to trigger. Gated
        // on the currently *viewed* role, not just whether the user holds the
        // Accounts role anywhere — someone with both roles shouldn't see this
        // while looking at their POC dashboard.
        actionDeposit.isVisible = roles.isAccounts && selectedView == RoleView.ACCOUNTS
        // Requests/All Requests are useful to any approver (Team Aligned,
        // Cash POC, or Accounts) for triaging what's in the pipeline —
        // gated to "any approver role" rather than a single specific one.
        actionRequests.isVisible = roles.isAnyApprover
        actionAllRequests.isVisible = roles.isAnyApprover
        // Reports has no role-specific data yet (placeholder toast), left
        // visible to everyone who can see this Dashboard at all.
        actionReports.isVisible = true

        bindQuickAction(actionDeposit, "\uD83D\uDCB0", "Deposit\nFund") {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashDepositFundFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        bindQuickAction(actionRequests, "\uD83D\uDCCB", "Requests\n(by status)") {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashPendingSettlementFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        bindQuickAction(actionAllRequests, "\uD83D\uDC65", "All\nRequests") {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashAllRequestsFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        bindQuickAction(actionReports, "\uD83D\uDCCA", "Reports") {
            Toast.makeText(requireContext(), "Reports coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindQuickAction(root: View, icon: String, label: String, onClick: () -> Unit) {
        val tvIcon = root.findViewById<TextView>(R.id.tvQuickActionIcon)
        val tvLabel = root.findViewById<TextView>(R.id.tvQuickActionLabel)
        tvIcon.text = icon
        tvIcon.background = roundedDrawable("#F1F5F9", dp(12))
        tvLabel.text = label
        root.setOnClickListener { onClick() }
    }

    private fun roundedDrawable(hexColor: String, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(hexColor))
            cornerRadius = radiusPx.toFloat()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
