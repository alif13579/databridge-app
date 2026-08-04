package com.cloudx.databridge

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import java.text.NumberFormat
import java.util.Locale

/** Add, set-default, or remove MFS channels ("wallets"). */
class ManageWalletsFragment : Fragment() {

    private val vm: CashManagementViewModel by viewModels()
    private lateinit var layoutList: LinearLayout
    private var branchId: String = ""

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        fun newInstance(branchId: String): ManageWalletsFragment {
            val f = ManageWalletsFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_manage_wallets, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        view.findViewById<ImageButton>(R.id.btnManageWalletsBack).setOnClickListener { parentFragmentManager.popBackStack() }
        view.findViewById<Button>(R.id.btnAddWallet).setOnClickListener { showAddWalletDialog() }
        layoutList = view.findViewById(R.id.layoutManageWalletList)

        vm.state.observe(viewLifecycleOwner) { state ->
            if (state is CashManagementState.Success) renderWallets(state.accounts, state.defaultProvider)
        }
        vm.load(branchId)
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        return "\u09F3" + NumberFormat.getNumberInstance(Locale.US).format(whole)
    }

    private fun renderWallets(accounts: List<MfsAccountSummary>, defaultProvider: String?) {
        layoutList.removeAllViews()
        if (accounts.isEmpty()) {
            layoutList.addView(TextView(requireContext()).apply {
                text = "No wallets yet. Tap Add Wallet to create one."
                textSize = 13f
                setTextColor(0xFF94A3B8.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(30), dp(8), dp(30))
            })
            return
        }
        accounts.sortedByDescending { it.provider == defaultProvider }.forEach { account ->
            val row = layoutInflater.inflate(R.layout.item_select_wallet_row, layoutList, false)
            val (bg, fg) = CashChannelStyle.colors(account.provider)
            row.findViewById<TextView>(R.id.tvSelectWalletIcon).apply {
                text = account.provider.take(1).uppercase()
                setTextColor(Color.parseColor(fg))
                background = CashChannelStyle.iconDrawable(account.provider, 19, resources.displayMetrics.density)
            }
            val isDefault = account.provider == defaultProvider
            row.findViewById<TextView>(R.id.tvSelectWalletName).text = if (isDefault) "${account.provider}  \u2022 Default" else account.provider
            row.findViewById<TextView>(R.id.tvSelectWalletBalance).text = taka(account.balance)

            val btnEdit = row.findViewById<ImageButton>(R.id.btnSelectWalletEdit)
            val btnDelete = row.findViewById<ImageButton>(R.id.btnSelectWalletDelete)
            btnEdit.isVisible = !isDefault
            btnEdit.setImageResource(android.R.drawable.btn_star_big_off)
            btnEdit.contentDescription = "Set as default"
            btnEdit.setOnClickListener {
                vm.setDefaultProvider(account.provider) { ok ->
                    Toast.makeText(requireContext(), if (ok) "Default wallet updated" else "Failed to update default", Toast.LENGTH_SHORT).show()
                }
            }
            btnDelete.isVisible = true
            btnDelete.setOnClickListener { confirmRemoveWallet(account.provider) }

            layoutList.addView(row)
        }
    }

    private fun confirmRemoveWallet(providerName: String) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Remove $providerName?")
            .setMessage("This deletes its whole deposit and payment history. This can't be undone.")
            .setPositiveButton("Remove") { _, _ ->
                vm.removeProvider(providerName) { ok ->
                    Toast.makeText(requireContext(), if (ok) "Wallet removed" else "Failed to remove wallet", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddWalletDialog() {
        val existingChannels = (vm.state.value as? CashManagementState.Success)?.accounts?.map { it.provider }.orEmpty()
        val padding = dp(20)
        val channelOptions = listOf("Select channel") + (listOf("Rocket", "bKash") + existingChannels).distinct() + listOf("Other")

        val spinner = Spinner(requireContext())
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, channelOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val customNameInput = EditText(requireContext()).apply {
            hint = "Channel name"
            isVisible = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                customNameInput.isVisible = channelOptions[position] == "Other"
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(spinner)
            addView(customNameInput)
        }

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Add Wallet")
            .setView(wrapper)
            .setPositiveButton("Add") { _, _ ->
                val selected = spinner.selectedItem?.toString().orEmpty()
                val name = if (selected == "Other") customNameInput.text.toString().trim() else selected
                if (selected == "Select channel" || name.isBlank()) {
                    Toast.makeText(requireContext(), "Select or enter a channel", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                vm.addProvider(name) { ok ->
                    Toast.makeText(requireContext(), if (ok) "Wallet added" else "Failed to add wallet", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
