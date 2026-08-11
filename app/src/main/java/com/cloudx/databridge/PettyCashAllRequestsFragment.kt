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
import com.google.firebase.auth.FirebaseAuth
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
 * All/My Requests/Pending/Approved/Settled tab filter, and real client-side
 * pagination (5 per page — the ViewModel loads the whole branch's request
 * list in one read, there's no server-side cursor to page against yet).
 */
class PettyCashAllRequestsFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private lateinit var layoutTabs: LinearLayout
    private lateinit var layoutList: LinearLayout
    private lateinit var pbLoading: View
    private lateinit var layoutError: View

    private var branchId: String = ""
    private var selectedFilter: String = FILTER_ALL
    private var currentPage: Int = 1
    private var latestState: PettyCashState.Success? = null
    private var advancedFilter: PettyCashFilterState = PettyCashFilterState()

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
        view.findViewById<View>(R.id.btnPcAllReqSearch).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashFilterFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        view.findViewById<View>(R.id.btnPcAllReqFilter).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashFilterFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        parentFragmentManager.setFragmentResultListener(PettyCashFilterState.FRAGMENT_RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            val stateBundle = bundle.getBundle(PettyCashFilterState.BUNDLE_KEY_STATE)
            advancedFilter = stateBundle?.let { PettyCashFilterState.fromBundle(it) } ?: PettyCashFilterState()
            currentPage = 1
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
        PC_STATUS_ACKNOWLEDGED -> StatusDisplay("Acknowledged", FILTER_PENDING, R.drawable.bg_pc_status_pending, "#C2410C")
        PC_STATUS_APPROVED -> StatusDisplay("Approved", FILTER_APPROVED, R.drawable.bg_pc_status_approved, "#6D28D9")
        PC_STATUS_SETTLE_IN_PROCESS -> StatusDisplay("Settle in Process", FILTER_APPROVED, R.drawable.bg_pc_status_approved, "#6D28D9")
        PC_STATUS_SETTLED -> StatusDisplay("Settled", FILTER_SETTLED, R.drawable.bg_pc_status_settled, "#059669")
        PC_STATUS_REJECTED -> StatusDisplay("Rejected", "rejected", R.drawable.bg_pc_status_pending, "#B91C1C")
        else -> StatusDisplay(request.status, "", R.drawable.bg_pc_status_pending, "#64748B")
    }

    private data class StatusDisplay(val label: String, val bucket: String, val badgeBg: Int, val badgeColor: String)

    private fun filteredRequests(): List<PettyCashRequest> {
        val all = latestState?.requests.orEmpty()
        val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val tabFiltered = when (selectedFilter) {
            FILTER_MINE -> all.filter { it.workerUid == myUid }
            FILTER_PENDING, FILTER_APPROVED, FILTER_SETTLED -> all.filter { statusDisplay(it).bucket == selectedFilter }
            else -> all
        }
        return if (advancedFilter.isActive) tabFiltered.filter { advancedFilter.matches(it) } else tabFiltered
    }

    private fun renderList(root: View) {
        val filtered = filteredRequests()
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
                row.findViewById<TextView>(R.id.tvAllReqRowIcon).text = item.workerName.take(1).uppercase()
                row.findViewById<TextView>(R.id.tvAllReqRowCode).text = item.requestCode
                row.findViewById<TextView>(R.id.tvAllReqRowSubtitle).text = "${item.workerName}\n${item.category}"
                row.findViewById<TextView>(R.id.tvAllReqRowAmount).text = taka(item.amount)
                row.findViewById<TextView>(R.id.tvAllReqRowDate).text = formatDate(item.createdAt)
                row.findViewById<TextView>(R.id.tvAllReqRowStatus).apply {
                    text = status.label
                    setTextColor(Color.parseColor(status.badgeColor))
                    background = androidx.core.content.ContextCompat.getDrawable(requireContext(), status.badgeBg)
                }

                row.setOnClickListener {
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.container, PettyCashSettlementDetailsFragment.newInstance(branchId, item.requestCode))
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
                }

                layoutList.addView(row)
            }
        }

        buildPagination(root, filtered.size, fromIndex, toIndex, totalPages)
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
