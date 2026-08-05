package com.cloudx.databridge

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * TEMPORARY placeholders for Petty Cash screens not yet built.
 * Each will be replaced by its real Fragment in a later phase of this feature branch.
 * Remove the corresponding stub here once the real screen lands.
 *
 * Phase plan (mockup screens):
 *  [x] 1. Dashboard               -> PettyCashDashboardFragment (done)
 *  [ ] 2. Deposit Fund            -> PettyCashDepositFundFragment
 *  [x] 3. Pending Settlement List -> PettyCashPendingSettlementFragment (done)
 *  [x] 4. Settlement Details      -> PettyCashSettlementDetailsFragment (done)
 *  [ ] 5. Settlement Success      -> PettyCashSettlementSuccessFragment
 *  [ ] 6. Deposit History         -> PettyCashDepositHistoryFragment
 *  [ ] 7. Settlement History      -> PettyCashSettlementHistoryFragment
 *  [ ] 8. All Requests            -> PettyCashAllRequestsFragment
 *  [ ] 9. Wallet Summary          -> PettyCashWalletSummaryFragment
 *  [ ] 10. Filter / Search        -> PettyCashFilterFragment
 */
private fun placeholderView(context: android.content.Context, title: String): View {
    return TextView(context).apply {
        text = "$title\n(coming in a later phase)"
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(0xFF64748B.toInt())
        setPadding(48, 200, 48, 48)
    }
}

class PettyCashDepositFundFragment : Fragment() {
    private var branchId: String = ""
    companion object {
        fun newInstance(branchId: String) = PettyCashDepositFundFragment().apply {
            arguments = Bundle().apply { putString("branch_id", branchId) }
        }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        branchId = arguments?.getString("branch_id").orEmpty()
        return placeholderView(requireContext(), "Deposit Fund")
    }
}

class PettyCashSettlementSuccessFragment : Fragment() {
    private var branchId: String = ""
    private var requestCode: String = ""
    companion object {
        fun newInstance(branchId: String, requestCode: String) = PettyCashSettlementSuccessFragment().apply {
            arguments = Bundle().apply {
                putString("branch_id", branchId)
                putString("request_code", requestCode)
            }
        }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        branchId = arguments?.getString("branch_id").orEmpty()
        requestCode = arguments?.getString("request_code").orEmpty()
        return placeholderView(requireContext(), "Settlement Success\n$requestCode")
    }
}

class PettyCashAllRequestsFragment : Fragment() {
    private var branchId: String = ""
    companion object {
        fun newInstance(branchId: String) = PettyCashAllRequestsFragment().apply {
            arguments = Bundle().apply { putString("branch_id", branchId) }
        }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        branchId = arguments?.getString("branch_id").orEmpty()
        return placeholderView(requireContext(), "All Requests")
    }
}

class PettyCashFilterFragment : Fragment() {
    private var branchId: String = ""
    companion object {
        fun newInstance(branchId: String) = PettyCashFilterFragment().apply {
            arguments = Bundle().apply { putString("branch_id", branchId) }
        }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        branchId = arguments?.getString("branch_id").orEmpty()
        return placeholderView(requireContext(), "Filter / Search")
    }
}
