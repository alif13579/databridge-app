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
 * Petty Cash Management — Pending Settlement List (mockup screen 3).
 *
 * Wired to PettyCashViewModel: shows real requests in PC_STATUS_APPROVED
 * (POC has approved, waiting for Accounts to settle), with a working
 * All/High/Normal priority tab filter. The per-card Settle button only
 * shows for users whose roles.isAccounts is true — everyone else can still
 * see the queue (read-only) by tapping through to Settlement Details.
 */
class PettyCashPendingSettlementFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var layoutTabs: LinearLayout
    private lateinit var layoutList: LinearLayout
    private lateinit var pbLoading: View
    private lateinit var layoutError: View

    private var branchId: String = ""
    private var selectedFilter: String = FILTER_ALL
    private var latestState: PettyCashState.Success? = null
    private var advancedFilter: PettyCashFilterState = PettyCashFilterState()

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val FILTER_ALL = "all"
        private const val FILTER_HIGH = "high"
        private const val FILTER_NORMAL = "normal"

        fun newInstance(branchId: String): PettyCashPendingSettlementFragment {
            val f = PettyCashPendingSettlementFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_pending_settlement, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

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
        val queue = latestState?.pendingSettlementQueue.orEmpty()
        val highCount = queue.count { it.priority == PC_PRIORITY_HIGH }
        val normalCount = queue.count { it.priority == PC_PRIORITY_NORMAL }
        val tabs = listOf(
            Pair(FILTER_ALL, "All (${queue.size})"),
            Pair(FILTER_HIGH, "High ($highCount)"),
            Pair(FILTER_NORMAL, "Normal ($normalCount)")
        )
        tabs.forEach { (key, label) ->
            val tab = layoutInflater.inflate(R.layout.item_petty_cash_filter_tab, layoutTabs, false) as TextView
            tab.text = label
            tab.setOnClickListener {
                selectedFilter = key
                buildTabs()
                renderList()
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

    private fun formatApprovedAt(millis: Long): String {
        if (millis == 0L) return "—"
        return SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(millis))
    }

    private fun renderList() {
        val state = latestState ?: return
        val queue = state.pendingSettlementQueue
        val priorityFiltered = when (selectedFilter) {
            FILTER_HIGH -> queue.filter { it.priority == PC_PRIORITY_HIGH }
            FILTER_NORMAL -> queue.filter { it.priority == PC_PRIORITY_NORMAL }
            else -> queue
        }
        val filtered = if (advancedFilter.isActive) priorityFiltered.filter { advancedFilter.matches(it) } else priorityFiltered
        val canSettle = state.roles.isAccounts

        layoutList.removeAllViews()
        if (filtered.isEmpty()) {
            layoutList.addView(TextView(requireContext()).apply {
                text = "No pending settlements."
                textSize = 13f
                setTextColor(0xFF94A3B8.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(40), dp(8), dp(40))
            })
            return
        }
        filtered.forEach { item ->
            val card = layoutInflater.inflate(R.layout.item_petty_cash_settlement_card, layoutList, false)
            card.findViewById<TextView>(R.id.tvPsCardCode).text = item.requestCode
            card.findViewById<TextView>(R.id.tvPsCardWorker).text = item.workerName
            card.findViewById<TextView>(R.id.tvPsCardCategory).text = item.category
            card.findViewById<TextView>(R.id.tvPsCardAmount).text = taka(item.amount)
            card.findViewById<TextView>(R.id.tvPsCardApprovedInfo).text = "POC Approved: ${formatApprovedAt(item.pocApprovedAt)}"
            card.findViewById<TextView>(R.id.tvPsCardApprovedBy).text =
                "Approved by: ${item.pocApprovedByName.ifBlank { "—" }}"

            val tvPriority = card.findViewById<TextView>(R.id.tvPsCardPriority)
            tvPriority.isVisible = item.priority == PC_PRIORITY_HIGH
            if (item.priority == PC_PRIORITY_HIGH) tvPriority.text = "High"

            val btnSettle = card.findViewById<TextView>(R.id.btnPsCardSettle)
            btnSettle.isVisible = canSettle

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
