package com.cloudx.databridge

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * Petty Cash Management — Deposit Fund (mockup screen 2).
 *
 * Wired to PettyCashViewModel. Current balance is the real wallet balance;
 * "After Deposit" preview updates live as the amount is typed. Deposit Now
 * writes a real PettyCashDeposit and increments the wallet balance via
 * viewModel.depositFund() (Supabase read-compute-write — see
 * SupabasePettyCashWriter's concurrency note for the race caveat the old
 * Firebase transaction didn't have).
 */
class PettyCashDepositFundFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private var branchId: String = ""
    private var currentBalance: Double = 0.0
    private var dataLoaded = false

    private val sources = listOf("Cash", "Bank", "Adjustment")
    private var selectedSource: String = ""

    private lateinit var etAmount: EditText
    private lateinit var etRemarks: EditText
    private lateinit var tvRemarksCount: TextView
    private lateinit var tvSourceSelected: TextView
    private lateinit var btnDepositNow: Button

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        fun newInstance(branchId: String): PettyCashDepositFundFragment {
            val f = PettyCashDepositFundFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_deposit_fund, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        etAmount = view.findViewById(R.id.etPcDepositAmount)
        etRemarks = view.findViewById(R.id.etPcDepositRemarks)
        tvRemarksCount = view.findViewById(R.id.tvPcDepositRemarksCount)
        tvSourceSelected = view.findViewById(R.id.tvPcDepositSourceSelected)
        btnDepositNow = view.findViewById(R.id.btnPcDepositNow)
        btnDepositNow.isEnabled = false // enabled once the real balance has loaded

        view.findViewById<View>(R.id.btnPcDepositBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btnPcDepositHistory).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashDepositHistoryFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        view.findViewById<View>(R.id.layoutPcDepositSource).setOnClickListener { showSourcePicker() }

        etAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateAfterDepositPreview(view) }
        })

        etRemarks.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                tvRemarksCount.text = "${s?.length ?: 0}/120"
            }
        })

        btnDepositNow.setOnClickListener { onDepositNow() }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            if (state is PettyCashState.Success) {
                if (!state.roles.isAccounts) {
                    Toast.makeText(requireContext(), "Only Accounts can deposit funds", Toast.LENGTH_LONG).show()
                    parentFragmentManager.popBackStack()
                    return@observe
                }
                currentBalance = state.walletBalance
                dataLoaded = true
                btnDepositNow.isEnabled = true
                view.findViewById<TextView>(R.id.tvPcDepositCurrentBalance).text = taka(currentBalance)
                updateAfterDepositPreview(view)
            } else if (state is PettyCashState.Error) {
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
            }
        }
        if (branchId.isNotBlank()) viewModel.load(branchId)
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        return "\u09F3${NumberFormat.getNumberInstance(Locale.US).format(whole)}"
    }

    private fun enteredAmount(): Double = etAmount.text?.toString()?.toDoubleOrNull() ?: 0.0

    private fun updateAfterDepositPreview(root: View) {
        val amount = enteredAmount()
        val newBalance = currentBalance + amount

        bindRow(root, R.id.rowPcAfterPrevious, "Previous Balance", taka(currentBalance))
        bindRow(root, R.id.rowPcAfterDeposit, "+ Deposit Amount", taka(amount))
        bindRow(root, R.id.rowPcAfterNewBalance, "New Balance", taka(newBalance))
    }

    private fun bindRow(root: View, includeId: Int, label: String, value: String) {
        val row = root.findViewById<View>(includeId)
        row.findViewById<TextView>(R.id.tvDetailRowLabel).text = label
        row.findViewById<TextView>(R.id.tvDetailRowValue).text = value
    }

    private fun showSourcePicker() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Source")
            .setItems(sources.toTypedArray()) { _, index ->
                selectedSource = sources[index]
                tvSourceSelected.text = selectedSource
                tvSourceSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
            }
            .show()
    }

    private fun onDepositNow() {
        if (!dataLoaded) {
            Toast.makeText(requireContext(), "Still loading wallet balance, try again in a moment", Toast.LENGTH_SHORT).show()
            return
        }
        val amount = enteredAmount()
        if (amount <= 0.0) {
            Toast.makeText(requireContext(), "Enter a valid deposit amount", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedSource.isBlank()) {
            Toast.makeText(requireContext(), "Select a source", Toast.LENGTH_SHORT).show()
            return
        }
        val reference = view?.findViewById<EditText>(R.id.etPcDepositReference)?.text?.toString().orEmpty()
        val remarks = etRemarks.text?.toString().orEmpty()

        btnDepositNow.isEnabled = false
        lifecycleScope.launch {
            val result = viewModel.depositFund(branchId, amount, selectedSource, reference, remarks) { ok ->
                activity?.runOnUiThread {
                    if (isAdded) Toast.makeText(requireContext(),
                        if (ok) "✓ Supabase saved" else "⚠ Supabase save failed", Toast.LENGTH_SHORT).show()
                }
            }
            if (result.isSuccess) {
                Toast.makeText(requireContext(), "Deposited ${taka(amount)} via $selectedSource", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } else {
                btnDepositNow.isEnabled = true
                Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Deposit failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
