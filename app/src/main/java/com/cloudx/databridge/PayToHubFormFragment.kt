package com.cloudx.databridge

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.datepicker.MaterialDatePicker
import java.text.NumberFormat
import java.util.Locale

/** Pay to Hub, step 2: amount, optional trx id + remarks, confirm. */
class PayToHubFormFragment : Fragment() {

    private val vm: CashManagementViewModel by viewModels()

    private lateinit var tvWalletIcon: TextView
    private lateinit var tvWalletName: TextView
    private lateinit var tvAvailableBalance: TextView
    private lateinit var etAmount: EditText
    private lateinit var tvMax: TextView
    private lateinit var tvBalanceAfter: TextView
    private lateinit var etTrxId: EditText
    private lateinit var etRemarks: EditText
    private lateinit var btnConfirm: Button

    private var branchId: String = ""
    private var providerName: String = ""
    private var currentAccount: MfsAccountSummary? = null
    private var confirmedDateMillis: Long = System.currentTimeMillis()

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val ARG_PROVIDER = "provider"
        fun newInstance(branchId: String, providerName: String): PayToHubFormFragment {
            val f = PayToHubFormFragment()
            f.arguments = Bundle().apply {
                putString(ARG_BRANCH_ID, branchId)
                putString(ARG_PROVIDER, providerName)
            }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_pay_to_hub_form, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()
        providerName = arguments?.getString(ARG_PROVIDER).orEmpty()

        view.findViewById<ImageButton>(R.id.btnPayFormBack).setOnClickListener { parentFragmentManager.popBackStack() }
        tvWalletIcon        = view.findViewById(R.id.tvPayFormWalletIcon)
        tvWalletName        = view.findViewById(R.id.tvPayFormWalletName)
        tvAvailableBalance  = view.findViewById(R.id.tvPayFormAvailableBalance)
        etAmount            = view.findViewById(R.id.etPayFormAmount)
        tvMax               = view.findViewById(R.id.tvPayFormMax)
        tvBalanceAfter      = view.findViewById(R.id.tvPayFormBalanceAfter)
        etTrxId             = view.findViewById(R.id.etPayFormTrxId)
        etRemarks           = view.findViewById(R.id.etPayFormRemarks)
        btnConfirm          = view.findViewById(R.id.btnConfirmPayment)

        val (bg, fg) = CashChannelStyle.colors(providerName)
        tvWalletIcon.text = providerName.take(1).uppercase()
        tvWalletIcon.setTextColor(Color.parseColor(fg))
        tvWalletIcon.background = CashChannelStyle.iconDrawable(providerName, 19, resources.displayMetrics.density)
        tvWalletName.text = providerName

        etAmount.addTextChangedListener { updateBalanceAfter() }
        tvMax.setOnClickListener {
            val balance = currentAccount?.balance ?: 0.0
            etAmount.setText(Math.round(balance).toString())
            etAmount.setSelection(etAmount.text.length)
        }
        btnConfirm.setOnClickListener { confirmPayment() }

        vm.state.observe(viewLifecycleOwner) { state ->
            if (state is CashManagementState.Success) {
                currentAccount = state.accounts.find { it.provider == providerName }
                tvAvailableBalance.text = taka(currentAccount?.balance ?: 0.0)
                updateBalanceAfter()
            }
        }
        vm.load(branchId)
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        val sign = if (whole < 0) "\u2212" else ""
        return "$sign\u09F3" + NumberFormat.getNumberInstance(Locale.US).format(Math.abs(whole))
    }

    private fun updateBalanceAfter() {
        val balance = currentAccount?.balance ?: 0.0
        val amt = etAmount.text.toString().toDoubleOrNull() ?: 0.0
        tvBalanceAfter.text = taka(balance - amt)
    }

    private fun confirmPayment() {
        val account = currentAccount
        if (account == null) {
            Toast.makeText(requireContext(), "Wallet data not loaded yet", Toast.LENGTH_SHORT).show()
            return
        }
        val amt = etAmount.text.toString().toDoubleOrNull()
        if (amt == null || amt <= 0.0) {
            Toast.makeText(requireContext(), "Enter a valid amount", Toast.LENGTH_SHORT).show()
            return
        }
        if (amt > account.balance) {
            Toast.makeText(requireContext(), "Can't exceed available balance (${taka(account.balance)})", Toast.LENGTH_SHORT).show()
            return
        }
        val trxId = etTrxId.text.toString().trim()
        val remarks = etRemarks.text.toString()

        btnConfirm.isEnabled = false
        btnConfirm.text = "Paying..."
        vm.addLedgerEntry(providerName, LEDGER_TYPE_HUB_PAYMENT, amt, trxId, remarks, confirmedDateMillis) { ok ->
            if (ok) {
                Toast.makeText(requireContext(), "Payment saved", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack(BACK_STACK_PAY_TO_HUB_FLOW, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            } else {
                btnConfirm.isEnabled = true
                btnConfirm.text = "Confirm Payment"
                Toast.makeText(requireContext(), "Failed to save payment", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun EditText.addTextChangedListener(afterChanged: () -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { afterChanged() }
    })
}
