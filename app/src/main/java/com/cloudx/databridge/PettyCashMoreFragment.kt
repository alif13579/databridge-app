package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels

/**
 * Petty Cash — More.
 *
 * NOT CURRENTLY REACHABLE: was opened from the bottom action bar's "More"
 * item, which has been removed (was a redundant extra bar — Dashboard's
 * "Total Fund" card already opens the same Wallet Summary this menu's only
 * item pointed to). No other screen links here. Kept as-is pending a call
 * on whether to delete it outright; wire up a new entry point instead if
 * this ever grows a second item.
 *
 * Was intentionally just a menu into what already exists in the app, not a
 * new settings/notifications feature — those already live in MainActivity's
 * drawer and aren't duplicated here.
 *
 * Currently just Wallet Summary (Accounts-only — the wallet balance
 * breakdown isn't meaningful for Team Aligned/Cash POC/Requester). Shows an
 * empty state rather than a blank screen for anyone without an item here.
 */
class PettyCashMoreFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()
    private var branchId: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_more, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        view.findViewById<View>(R.id.btnPcMoreBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val menu = view.findViewById<LinearLayout>(R.id.layoutPcMoreMenu)
        val tvEmpty = view.findViewById<TextView>(R.id.tvPcMoreEmpty)

        if (branchId.isBlank()) {
            tvEmpty.isVisible = true
            return
        }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            if (state is PettyCashState.Success && state.roles.isAccounts && menu.childCount == 0) {
                val row = layoutInflater.inflate(R.layout.item_petty_cash_menu_row, menu, false)
                row.findViewById<TextView>(R.id.tvPcMenuRowIcon).text = "\uD83D\uDC5B"
                row.findViewById<TextView>(R.id.tvPcMenuRowTitle).text = "Wallet Summary"
                row.findViewById<TextView>(R.id.tvPcMenuRowSubtitle).text = "Balance, fund, and total breakdown"
                row.setOnClickListener {
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.container, PettyCashWalletSummaryFragment.newInstance(branchId))
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
                }
                menu.addView(row)
                tvEmpty.isVisible = false
            } else if (state is PettyCashState.Success && !state.roles.isAccounts) {
                tvEmpty.isVisible = menu.childCount == 0
            }
        }
        viewModel.load(branchId)
    }

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        fun newInstance(branchId: String): PettyCashMoreFragment {
            val f = PettyCashMoreFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }
}
