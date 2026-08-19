package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels

/**
 * Petty Cash — Reports.
 *
 * Reached from the bottom action bar's "Reports" item on the Dashboard and
 * My Requests screens (see layout_petty_cash_bottom_nav.xml). The mockup
 * shows this as a bottom-nav destination without specifying its content, so
 * this is a menu into the report-style screens that already exist elsewhere
 * in the app rather than a new report-building feature:
 *   - All Requests (filterable, already supports date/status/category)
 *   - Settlement History (everyone — shows what's been settled)
 *   - Deposit History (Accounts only — deposits are an Accounts-only action)
 */
class PettyCashReportsFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()
    private var branchId: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_reports, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        view.findViewById<View>(R.id.btnPcReportsBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val menu = view.findViewById<LinearLayout>(R.id.layoutPcReportsMenu)
        addMenuRow(menu, "📊", "Claims Report", "Date-range summary with Excel and PDF export") {
            open(ClaimsReportFragment.newInstance(branchId, lockToBranch = true))
        }
        addMenuRow(menu, "\uD83D\uDCCB", "All Requests", "Browse and filter every request") {
            open(PettyCashAllRequestsFragment.newInstance(branchId))
        }
        addMenuRow(menu, "\u2705", "Settlement History", "Requests that have been settled") {
            open(PettyCashSettlementHistoryFragment.newInstance(branchId))
        }

        // Deposit History is an Accounts-only action, so only show it once we
        // know this user actually holds that role for this branch — avoid a
        // flash of a row that then has to disappear, wait for state instead.
        if (branchId.isNotBlank()) {
            viewModel.state.observe(viewLifecycleOwner) { state ->
                if (state is PettyCashState.Success && state.roles.isAccounts && menu.findViewWithTag<View>("deposit_history") == null) {
                    addMenuRow(menu, "\uD83D\uDCB0", "Deposit History", "Funds deposited into the wallet", tag = "deposit_history") {
                        open(PettyCashDepositHistoryFragment.newInstance(branchId))
                    }
                }
            }
            viewModel.load(branchId)
        }
    }

    private fun addMenuRow(container: LinearLayout, icon: String, title: String, subtitle: String, tag: String? = null, onClick: () -> Unit) {
        val row = layoutInflater.inflate(R.layout.item_petty_cash_menu_row, container, false)
        row.tag = tag
        row.findViewById<TextView>(R.id.tvPcMenuRowIcon).text = icon
        row.findViewById<TextView>(R.id.tvPcMenuRowTitle).text = title
        row.findViewById<TextView>(R.id.tvPcMenuRowSubtitle).text = subtitle
        row.setOnClickListener { onClick() }
        container.addView(row)
    }

    private fun open(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        fun newInstance(branchId: String): PettyCashReportsFragment {
            val f = PettyCashReportsFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }
}
