package com.cloudx.databridge

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Petty Cash Management — My Requests (simplified Requester landing screen).
 *
 * Reached instead of the full Dashboard when someone has petty_cash_requester
 * but not nav_petty_cash — i.e. they can submit requests but aren't an
 * approver/Accounts user, so the Dashboard's balance/wallet/settlement-queue
 * UI would be meaningless (and confusing) to show them. This screen shows
 * only what a Requester actually needs: a way to submit a new request, and
 * their own request history with status.
 *
 * Also has a branch switcher (same pattern as CashManagementHomeFragment /
 * PettyCashDashboardFragment) for a Requester assigned to more than one
 * branch — e.g. a floating agent who isn't tied to a single branch.
 */
class PettyCashMyRequestsFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private lateinit var tvBranchName: TextView
    private var branchId: String = ""
    private val db = FirebaseDatabase.getInstance()
    private var branchNames: Map<String, String> = emptyMap()

    /** My Claims Summary card's date range -- see PettyCashDashboardFragment's identical
     *  fields for the shared design this mirrors, scoped here to just this requester's
     *  own claims. */
    private var claimsRangeStart: Long = 0L
    private var claimsRangeEnd: Long = 0L
    private var claimsRangeLabel: String = "This Month"

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        // Same key PettyCashDashboardFragment uses, so both screens remember
        // the same last-picked branch.
        private const val PREF_KEY_SELECTED_BRANCH = "pc_selected_branch_id"
        fun newInstance(branchId: String): PettyCashMyRequestsFragment {
            val f = PettyCashMyRequestsFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_my_requests, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()
        // Restore the last branch picked on either Petty Cash screen, so it
        // doesn't reset to the default every time this fragment reopens.
        // Only trusted if still one of the user's currently assigned branches.
        branchPrefs().getString(PREF_KEY_SELECTED_BRANCH, null)
            ?.takeIf { it in RbacManager.current.branchIds }
            ?.let { branchId = it }
        tvBranchName = view.findViewById(R.id.tvPcMyRequestsBranchName)

        view.findViewById<View>(R.id.btnPcMyRequestsBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<View>(R.id.btnPcMyRequestsNew).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashRequestCreateFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        view.findViewById<View>(R.id.btnPcMyRequestsRetry).setOnClickListener {
            if (branchId.isNotBlank()) viewModel.load(branchId)
        }

        setupBranchSwitcher()
        setClaimsRangeToThisMonth()
        view.findViewById<TextView>(R.id.tvPcMyClaimsDateRange).setOnClickListener { showClaimsRangePicker() }

        viewModel.state.observe(viewLifecycleOwner) { state -> render(state) }
        if (branchId.isBlank()) {
            render(PettyCashState.Error("No branch selected"))
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

    private fun openMyRequestsList(initialStatus: String = "all") {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, PettyCashPendingSettlementFragment.newInstance(branchId, initialStatus, myRequestsOnly = true))
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        return "\u09F3${NumberFormat.getNumberInstance(Locale.US).format(whole)}"
    }

    // ── My Claims Summary card (mirrors PettyCashDashboardFragment's Claims Summary,
    //    scoped to this requester's own claims) ─────────────────────────────────────

    private fun startOfDay(cal: java.util.Calendar) {
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
    }

    private fun setClaimsRangeToThisMonth() {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        startOfDay(cal)
        claimsRangeStart = cal.timeInMillis
        cal.add(java.util.Calendar.MONTH, 1)
        claimsRangeEnd = cal.timeInMillis
        claimsRangeLabel = "This Month"
    }

    private fun renderMyClaimsSummary(mine: List<PettyCashRequest>) {
        val root = view ?: return
        root.findViewById<TextView>(R.id.tvPcMyClaimsDateRange).text = claimsRangeLabel
        val inRange = mine.filter { it.createdAt in claimsRangeStart until claimsRangeEnd }
        val requestedTotal = inRange.sumOf { it.amount }
        val approvedTotal = inRange
            .filter { it.status == PC_STATUS_APPROVED || it.status == PC_STATUS_SETTLE_IN_PROCESS }
            .sumOf { it.approvedAmount }
        val settledTotal = inRange
            .filter { it.status == PC_STATUS_SETTLED }
            .sumOf { it.settledAmount }
        root.findViewById<TextView>(R.id.tvPcMyClaimsApproved).text = taka(approvedTotal)
        root.findViewById<TextView>(R.id.tvPcMyClaimsRequested).text = taka(requestedTotal)
        root.findViewById<TextView>(R.id.tvPcMyClaimsSettled).text = taka(settledTotal)
    }

    private fun refreshMyClaimsSummaryIfLoaded() {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        (viewModel.state.value as? PettyCashState.Success)?.let { state ->
            renderMyClaimsSummary(state.requests.filter { it.workerUid == myUid })
        }
    }

    private fun showClaimsRangePicker() {
        val options = arrayOf("This Month", "Last Month", "Last 7 Days", "Last 30 Days", "All Time", "Custom Range")
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Date Range বেছে নিন")
            .setItems(options) { _, which ->
                val cal = java.util.Calendar.getInstance()
                when (which) {
                    0 -> setClaimsRangeToThisMonth()
                    1 -> {
                        cal.add(java.util.Calendar.MONTH, -1)
                        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                        startOfDay(cal)
                        claimsRangeStart = cal.timeInMillis
                        cal.add(java.util.Calendar.MONTH, 1)
                        claimsRangeEnd = cal.timeInMillis
                        claimsRangeLabel = "Last Month"
                    }
                    2 -> {
                        claimsRangeEnd = System.currentTimeMillis()
                        cal.add(java.util.Calendar.DAY_OF_YEAR, -7)
                        startOfDay(cal)
                        claimsRangeStart = cal.timeInMillis
                        claimsRangeLabel = "Last 7 Days"
                    }
                    3 -> {
                        claimsRangeEnd = System.currentTimeMillis()
                        cal.add(java.util.Calendar.DAY_OF_YEAR, -30)
                        startOfDay(cal)
                        claimsRangeStart = cal.timeInMillis
                        claimsRangeLabel = "Last 30 Days"
                    }
                    4 -> {
                        claimsRangeStart = 0L
                        claimsRangeEnd = Long.MAX_VALUE
                        claimsRangeLabel = "All Time"
                    }
                    5 -> {
                        showCustomClaimsRangePicker()
                        return@setItems
                    }
                }
                refreshMyClaimsSummaryIfLoaded()
            }
            .show()
    }

    private fun showCustomClaimsRangePicker() {
        val startCal = java.util.Calendar.getInstance().apply {
            if (claimsRangeStart > 0L) timeInMillis = claimsRangeStart
        }
        android.app.DatePickerDialog(requireContext(), { _, y, m, d ->
            val from = java.util.Calendar.getInstance().apply {
                set(y, m, d); startOfDay(this)
            }
            val endCal = java.util.Calendar.getInstance()
            android.app.DatePickerDialog(requireContext(), { _, y2, m2, d2 ->
                val to = java.util.Calendar.getInstance().apply {
                    set(y2, m2, d2); startOfDay(this)
                    add(java.util.Calendar.DAY_OF_YEAR, 1) // inclusive end date
                }
                if (to.timeInMillis <= from.timeInMillis) {
                    android.widget.Toast.makeText(requireContext(), "End date শুরুর তারিখের আগে হতে পারবে না", android.widget.Toast.LENGTH_SHORT).show()
                    return@DatePickerDialog
                }
                claimsRangeStart = from.timeInMillis
                claimsRangeEnd = to.timeInMillis
                val fmt = SimpleDateFormat("dd MMM", Locale.ENGLISH)
                claimsRangeLabel = "${fmt.format(from.time)} - ${fmt.format(Date(to.timeInMillis - 86_400_000))}"
                refreshMyClaimsSummaryIfLoaded()
            }, endCal.get(java.util.Calendar.YEAR), endCal.get(java.util.Calendar.MONTH), endCal.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }, startCal.get(java.util.Calendar.YEAR), startCal.get(java.util.Calendar.MONTH), startCal.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }

    // ── Branch switcher ──────────────────────────────────────────────────────
    // Same pattern as CashManagementHomeFragment / PettyCashDashboardFragment.
    private fun setupBranchSwitcher() {
        val branchIds = RbacManager.current.branchIds
        if (branchIds.size <= 1) return

        val arrow = ContextCompat.getDrawable(requireContext(), R.drawable.ic_arrow_drop_down_white)?.mutate()
        arrow?.setTint(Color.parseColor("#0F172A"))
        tvBranchName.setCompoundDrawablesWithIntrinsicBounds(null, null, arrow, null)
        tvBranchName.isVisible = true
        tvBranchName.setOnClickListener { showBranchPicker(branchIds) }

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

    private fun branchPrefs() =
        requireContext().getSharedPreferences("databridge_toggles", android.content.Context.MODE_PRIVATE)

    private fun formatDate(millis: Long): String {
        if (millis == 0L) return "—"
        return SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(millis))
    }

    private fun render(state: PettyCashState) {
        val root = view ?: return
        val pbLoading = root.findViewById<View>(R.id.pbPcMyRequestsLoading)
        val layoutError = root.findViewById<View>(R.id.layoutPcMyRequestsError)
        val scroll = root.findViewById<View>(R.id.scrollPcMyRequests)

        when (state) {
            is PettyCashState.Loading -> {
                if (scroll.isVisible.not()) {
                    pbLoading.isVisible = true
                    layoutError.isVisible = false
                }
            }
            is PettyCashState.Error -> {
                if (scroll.isVisible.not()) {
                    pbLoading.isVisible = false
                    scroll.isVisible = false
                    layoutError.isVisible = true
                    root.findViewById<TextView>(R.id.tvPcMyRequestsError).text = state.message
                }
            }
            is PettyCashState.Success -> {
                pbLoading.isVisible = false
                layoutError.isVisible = false
                scroll.isVisible = true
                renderMyRequests(root, state)
            }
        }
    }

    private fun statusDisplay(request: PettyCashRequest): Triple<String, Int, String> = when (request.status) {
        PC_STATUS_PENDING -> Triple("Pending", R.drawable.bg_pc_status_pending, "#C2410C")
        PC_STATUS_ACKNOWLEDGED -> Triple("Verified", R.drawable.bg_pc_status_pending, "#C2410C")
        PC_STATUS_APPROVED -> Triple("Approved", R.drawable.bg_pc_status_approved, "#6D28D9")
        PC_STATUS_SETTLE_IN_PROCESS -> Triple("Ready to Settle", R.drawable.bg_pc_status_approved, "#6D28D9")
        PC_STATUS_SETTLED -> Triple("Settled", R.drawable.bg_pc_status_settled, "#059669")
        PC_STATUS_REJECTED -> Triple("Rejected", R.drawable.bg_pc_status_pending, "#B91C1C")
        else -> Triple(request.status, R.drawable.bg_pc_status_pending, "#64748B")
    }

    private fun renderMyRequests(root: View, state: PettyCashState.Success) {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val mine = state.requests.filter { it.workerUid == myUid }.sortedByDescending { it.createdAt }

        // My Claims Summary card -- same shape as PettyCashDashboardFragment's
        // Claims Summary, scoped to this requester's own claims (mine) and filtered by
        // createdAt within [claimsRangeStart, claimsRangeEnd).
        renderMyClaimsSummary(mine)

        val pendingCount = mine.count { it.status == PC_STATUS_PENDING || it.status == PC_STATUS_ACKNOWLEDGED }
        val approvedCount = mine.count { it.status == PC_STATUS_APPROVED || it.status == PC_STATUS_SETTLE_IN_PROCESS }
        val settledCount = mine.count { it.status == PC_STATUS_SETTLED }

        bindStatTile(root, R.id.statPcMyRequestsTotal, "\uD83D\uDCCB", "My\nRequests", mine.size.toString(), "#F1F5F9", "#0F172A")
        bindStatTile(root, R.id.statPcMyRequestsPending, "\u23F3", "Pending", pendingCount.toString(), "#FFEDD5", "#C2410C")
        bindStatTile(root, R.id.statPcMyRequestsApproved, "\u2705", "Approved", approvedCount.toString(), "#EDE9FE", "#6D28D9")
        bindStatTile(root, R.id.statPcMyRequestsSettled, "\uD83D\uDCB0", "Settled", settledCount.toString(), "#D1FAE5", "#059669")

        // "My Requests" opens the tabbed All/status-wise list, scoped to just
        // this user's own requests (myRequestsOnly=true) -- not the branch-
        // wide approver view PettyCashPendingSettlementFragment normally is.
        // Each of the other three tiles opens the same screen landed on the
        // matching tab, so switching claims are made at a glance instead of
        // starting back at "All" every time.
        //
        // Pending/Approved here are each a combined count across two
        // statuses (PENDING+ACKNOWLEDGED, APPROVED+SETTLE_IN_PROCESS) but
        // the target screen's tabs are one status each -- landing on the
        // first/primary one (PENDING, APPROVED) rather than trying to open
        // on a combined view that doesn't exist there. The other status is
        // one tab away.
        root.findViewById<View>(R.id.statPcMyRequestsTotal).setOnClickListener { openMyRequestsList() }
        root.findViewById<View>(R.id.statPcMyRequestsPending).setOnClickListener { openMyRequestsList(PC_STATUS_PENDING) }
        root.findViewById<View>(R.id.statPcMyRequestsApproved).setOnClickListener { openMyRequestsList(PC_STATUS_APPROVED) }
        root.findViewById<View>(R.id.statPcMyRequestsSettled).setOnClickListener { openMyRequestsList(PC_STATUS_SETTLED) }

        val container = root.findViewById<android.widget.LinearLayout>(R.id.layoutPcMyRequestsList)
        container.removeAllViews()

        if (mine.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "You haven't submitted any requests yet."
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
            row.findViewById<TextView>(R.id.tvAllReqRowIcon).text = item.category.take(1).uppercase()
            row.findViewById<TextView>(R.id.tvAllReqRowCode).text = item.requestCode
            row.findViewById<TextView>(R.id.tvAllReqRowSubtitle).text = item.category
            row.findViewById<TextView>(R.id.tvAllReqRowAmount).text = "Req: " + taka(item.amount)
            row.findViewById<TextView>(R.id.tvAllReqRowSecondaryAmount).text =
                if (item.status == PC_STATUS_SETTLED) "Settled: " + taka(item.settledAmount)
                else "Approved: " + taka(item.approvedAmount)
            row.findViewById<TextView>(R.id.tvAllReqRowDate).text =
                formatDate(if (item.requestedDate != 0L) item.requestedDate else item.createdAt)
            row.findViewById<TextView>(R.id.tvAllReqRowStatus).apply {
                text = label
                setTextColor(Color.parseColor(badgeColor))
                background = androidx.core.content.ContextCompat.getDrawable(requireContext(), badgeBg)
            }
            // Tapping any request (regardless of status) opens Settlement
            // Details, which itself surfaces Edit/Delete for the owner while
            // status == PENDING — no need to duplicate that logic here.
            row.setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, PettyCashSettlementDetailsFragment.newInstance(branchId, item.requestCode))
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
