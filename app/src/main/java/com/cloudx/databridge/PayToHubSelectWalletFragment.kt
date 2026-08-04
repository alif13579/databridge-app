package com.cloudx.databridge

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import java.text.NumberFormat
import java.util.Locale

// Named so PayToHubFormFragment can pop straight back to Home after a
// successful payment, instead of stepping back through Select Wallet too.
const val BACK_STACK_PAY_TO_HUB_FLOW = "pay_to_hub_flow"

/** Pay to Hub, step 1: pick which channel to pay out from. */
class PayToHubSelectWalletFragment : Fragment() {

    private val vm: CashManagementViewModel by viewModels()
    private lateinit var layoutWalletList: LinearLayout
    private var branchId: String = ""

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        fun newInstance(branchId: String): PayToHubSelectWalletFragment {
            val f = PayToHubSelectWalletFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_pay_to_hub_select_wallet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        view.findViewById<ImageButton>(R.id.btnSelectWalletBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        layoutWalletList = view.findViewById(R.id.layoutWalletList)

        vm.state.observe(viewLifecycleOwner) { state ->
            if (state is CashManagementState.Success) renderWallets(state.accounts)
        }
        vm.load(branchId)
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        return "\u09F3" + NumberFormat.getNumberInstance(Locale.US).format(whole)
    }

    private fun renderWallets(accounts: List<MfsAccountSummary>) {
        layoutWalletList.removeAllViews()
        val withBalance = accounts.filter { it.balance > 0.0 }
        if (withBalance.isEmpty()) {
            layoutWalletList.addView(TextView(requireContext()).apply {
                text = "No channel has a balance to pay out yet."
                textSize = 13f
                setTextColor(0xFF94A3B8.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(40), dp(8), dp(40))
            })
            return
        }
        withBalance.sortedByDescending { it.balance }.forEach { account ->
            val row = layoutInflater.inflate(R.layout.item_select_wallet_row, layoutWalletList, false)
            val (bg, fg) = CashChannelStyle.colors(account.provider)
            row.findViewById<TextView>(R.id.tvSelectWalletIcon).apply {
                text = account.provider.take(1).uppercase()
                setTextColor(Color.parseColor(fg))
                background = CashChannelStyle.iconDrawable(account.provider, 19, resources.displayMetrics.density)
            }
            row.findViewById<TextView>(R.id.tvSelectWalletName).text = account.provider
            row.findViewById<TextView>(R.id.tvSelectWalletBalance).text = taka(account.balance)
            row.setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, PayToHubFormFragment.newInstance(branchId, account.provider))
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }
            layoutWalletList.addView(row)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
