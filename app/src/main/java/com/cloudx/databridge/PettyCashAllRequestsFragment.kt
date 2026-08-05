package com.cloudx.databridge

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.text.NumberFormat
import java.util.Locale

/**
 * Petty Cash Management — All Requests (mockup screen 8).
 *
 * Phase 8 of the Petty Cash feature build: layout + static mock data + working
 * All/My Requests/Pending/Approved/Settled tab filter + static pagination
 * display. Firebase wiring (reading all petty_cash/{branch}/requests,
 * paginated) lands once PettyCashViewModel is built.
 */
class PettyCashAllRequestsFragment : Fragment() {

    private lateinit var layoutTabs: LinearLayout
    private lateinit var layoutList: LinearLayout

    private var branchId: String = ""
    private var selectedFilter: String = FILTER_ALL

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val FILTER_ALL = "all"
        private const val FILTER_MINE = "mine"
        private const val FILTER_PENDING = "pending"
        private const val FILTER_APPROVED = "approved"
        private const val FILTER_SETTLED = "settled"

        fun newInstance(branchId: String): PettyCashAllRequestsFragment {
            val f = PettyCashAllRequestsFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    private data class MockRequest(
        val code: String,
        val worker: String,
        val category: String,
        val amount: Double,
        val status: String, // "Pending (POC)" | "Pending (Team Aligned)" | "Approved (POC)" | "Settled"
        val statusKey: String, // FILTER_PENDING / FILTER_APPROVED / FILTER_SETTLED for filtering
        val date: String,
        val isMine: Boolean
    )

    private val mockData = listOf(
        MockRequest("REQ-2401", "Hasib Khan", "Travel Expense", 1250.0, "Pending (POC)", FILTER_PENDING, "01 Aug, 10:20 AM", true),
        MockRequest("REQ-2400", "Salman Khan", "Fuel Expense", 950.0, "Approved (POC)", FILTER_APPROVED, "01 Aug, 10:15 AM", false),
        MockRequest("REQ-2399", "Jannatul", "Stationery", 620.0, "Pending (Team Aligned)", FILTER_PENDING, "01 Aug, 09:55 AM", false),
        MockRequest("REQ-2396", "Riya Akter", "Office Supplies", 850.0, "Settled", FILTER_SETTLED, "31 Jul, 04:30 PM", false),
        MockRequest("REQ-2393", "Hasib Khan", "Travel Expense", 1100.0, "Settled", FILTER_SETTLED, "31 Jul, 03:10 PM", true)
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_all_requests, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        layoutTabs = view.findViewById(R.id.layoutPcAllReqTabs)
        layoutList = view.findViewById(R.id.layoutPcAllReqList)

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

        buildTabs()
        renderList()
        buildPagination(view)
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        return "\u09F3${NumberFormat.getNumberInstance(Locale.US).format(whole)}"
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

    private fun statusDrawableFor(statusKey: String): Int = when (statusKey) {
        FILTER_APPROVED -> R.drawable.bg_pc_status_approved
        FILTER_SETTLED -> R.drawable.bg_pc_status_settled
        else -> R.drawable.bg_pc_status_pending
    }

    private fun statusTextColorFor(statusKey: String): String = when (statusKey) {
        FILTER_APPROVED -> "#6D28D9"
        FILTER_SETTLED -> "#059669"
        else -> "#C2410C"
    }

    private fun renderList() {
        val filtered = when (selectedFilter) {
            FILTER_MINE -> mockData.filter { it.isMine }
            FILTER_PENDING, FILTER_APPROVED, FILTER_SETTLED -> mockData.filter { it.statusKey == selectedFilter }
            else -> mockData
        }
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

        filtered.forEach { item ->
            val row = layoutInflater.inflate(R.layout.item_petty_cash_all_request_row, layoutList, false)
            row.findViewById<TextView>(R.id.tvAllReqRowIcon).text = item.worker.take(1).uppercase()
            row.findViewById<TextView>(R.id.tvAllReqRowCode).text = item.code
            row.findViewById<TextView>(R.id.tvAllReqRowSubtitle).text = "${item.worker}\n${item.category}"
            row.findViewById<TextView>(R.id.tvAllReqRowAmount).text = taka(item.amount)
            row.findViewById<TextView>(R.id.tvAllReqRowDate).text = item.date
            row.findViewById<TextView>(R.id.tvAllReqRowStatus).apply {
                text = item.status
                setTextColor(Color.parseColor(statusTextColorFor(item.statusKey)))
                background = androidx.core.content.ContextCompat.getDrawable(requireContext(), statusDrawableFor(item.statusKey))
            }

            row.setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, PettyCashSettlementDetailsFragment.newInstance(branchId, item.code))
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }

            layoutList.addView(row)
        }
    }

    private fun buildPagination(root: View) {
        root.findViewById<TextView>(R.id.tvPcAllReqPageInfo).text = "Showing 1 to 5 of 48"

        val container = root.findViewById<LinearLayout>(R.id.layoutPcAllReqPageButtons)
        container.removeAllViews()
        // Static page buttons matching the mockup — real pagination wiring
        // (page state + Firebase query cursors) lands with the ViewModel phase.
        listOf("1", "2", "3", "...", "10").forEach { label ->
            val btn = TextView(requireContext()).apply {
                text = label
                textSize = 12f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setTextColor(if (label == "1") Color.WHITE else Color.parseColor("#64748B"))
                background = androidx.core.content.ContextCompat.getDrawable(
                    requireContext(),
                    if (label == "1") R.drawable.bg_pc_step_done else R.drawable.bg_pc_tab_inactive
                )
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginStart = dp(4)
                layoutParams = lp
            }
            container.addView(btn)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
