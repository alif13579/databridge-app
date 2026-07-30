package com.cloudx.databridge

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.android.material.tabs.TabLayout
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Branch-scoped cash reconciliation screen:
 *   Collection (manual entry) -> handed over to one or more MFS provider accounts
 *   (Rocket / bKash / Other, configurable per branch) -> from each MFS account a
 * "Hub Payment" settles some or all of it back to the branch's central account.
 * Not day-locked: a day's collection can be fully settled same-day, partially, or
 * carried forward if an MFS provider doesn't have full balance ready yet.
 */
class CashManagementFragment : Fragment() {

    private val vm: CashManagementViewModel by viewModels()
    private val auth = FirebaseAuth.getInstance()

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var pbLoading: ProgressBar
    private lateinit var layoutError: View
    private lateinit var tvError: TextView
    private lateinit var btnRetry: Button
    private lateinit var layoutContent: View
    private lateinit var tvBranchName: TextView
    private lateinit var tvToBePaid: TextView
    private lateinit var tvAgainstCollection: TextView
    private lateinit var tvStatCollection: TextView
    private lateinit var tvStatCashInHand: TextView
    private lateinit var tvStatHubPaid: TextView
    private lateinit var layoutProviderCards: LinearLayout
    private lateinit var btnAddProvider: Button
    private lateinit var layoutCollectionEntries: LinearLayout
    private lateinit var layoutAddCollectionForm: View
    private lateinit var etCollectionAmount: EditText
    private lateinit var btnSaveCollection: Button
    private lateinit var btnAddCollection: Button
    private lateinit var tabLayoutCash: TabLayout
    private lateinit var layoutCollectionsTab: View
    private lateinit var layoutHandoverTab: View

    private var branchId: String = ""
    private val dateFmt = SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault())
    private val providerOptions = listOf("Select provider", "Rocket", "bKash", "Other")

    // Counts provider-picker rows the person has opened via "+ Add provider" but not
    // yet resolved to a real name. Every Success re-render rebuilds the whole card
    // list from Firebase state, so this keeps still-in-progress blank rows from
    // disappearing underneath the person mid-pick.
    // Known limitation: if a *different* card's write completes while a blank row is
    // mid-edit (e.g. partway through typing an "Other" name), that row's rebuild will
    // still reset to empty -- acceptable for this first pass, worth revisiting if it
    // turns out to bite in practice.
    private var pendingEmptyRowCount = 0

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        fun newInstance(branchId: String): CashManagementFragment {
            val f = CashManagementFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_cash_management, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        swipeRefresh           = view.findViewById(R.id.swipeRefreshCash)
        pbLoading               = view.findViewById(R.id.pbLoading)
        layoutError             = view.findViewById(R.id.layoutError)
        tvError                 = view.findViewById(R.id.tvError)
        btnRetry                = view.findViewById(R.id.btnRetry)
        layoutContent           = view.findViewById(R.id.layoutContent)
        tvBranchName            = view.findViewById(R.id.tvBranchName)
        tvToBePaid              = view.findViewById(R.id.tvToBePaid)
        tvAgainstCollection     = view.findViewById(R.id.tvAgainstCollection)
        tvStatCollection        = view.findViewById(R.id.tvStatCollection)
        tvStatCashInHand        = view.findViewById(R.id.tvStatCashInHand)
        tvStatHubPaid           = view.findViewById(R.id.tvStatHubPaid)
        layoutProviderCards     = view.findViewById(R.id.layoutProviderCards)
        btnAddProvider          = view.findViewById(R.id.btnAddProvider)
        layoutCollectionEntries = view.findViewById(R.id.layoutCollectionEntries)
        layoutAddCollectionForm = view.findViewById(R.id.layoutAddCollectionForm)
        etCollectionAmount      = view.findViewById(R.id.etCollectionAmount)
        btnSaveCollection       = view.findViewById(R.id.btnSaveCollection)
        btnAddCollection        = view.findViewById(R.id.btnAddCollection)
        tabLayoutCash           = view.findViewById(R.id.tabLayoutCash)
        layoutCollectionsTab    = view.findViewById(R.id.layoutCollectionsTab)
        layoutHandoverTab       = view.findViewById(R.id.layoutHandoverTab)

        tabLayoutCash.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                layoutCollectionsTab.isVisible = tab.position == 0
                layoutHandoverTab.isVisible = tab.position == 1
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        tvBranchName.text = branchId.ifBlank { "NO BRANCH ASSIGNED" }

        swipeRefresh.setOnRefreshListener { vm.refresh() }
        btnRetry.setOnClickListener { vm.load(branchId) }
        btnAddProvider.setOnClickListener { addEmptyProviderRow() }

        btnAddCollection.setOnClickListener {
            layoutAddCollectionForm.isVisible = true
            btnAddCollection.isVisible = false
            etCollectionAmount.requestFocus()
        }
        btnSaveCollection.setOnClickListener {
            val amt = etCollectionAmount.text.toString().toDoubleOrNull()
            if (amt == null || amt <= 0.0) {
                Toast.makeText(requireContext(), "Enter a valid amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.addCollection(amt) { ok ->
                if (ok) {
                    etCollectionAmount.setText("")
                    layoutAddCollectionForm.isVisible = false
                    btnAddCollection.isVisible = true
                } else {
                    Toast.makeText(requireContext(), "Failed to save collection", Toast.LENGTH_SHORT).show()
                }
            }
        }

        vm.state.observe(viewLifecycleOwner) { state -> render(state) }

        if (branchId.isBlank()) {
            render(CashManagementState.Error("No branch assigned to this account."))
        } else {
            vm.load(branchId)
        }
    }

    private fun render(state: CashManagementState) {
        swipeRefresh.isRefreshing = false
        when (state) {
            is CashManagementState.Loading -> {
                pbLoading.isVisible = true
                layoutError.isVisible = false
                layoutContent.isVisible = false
            }
            is CashManagementState.Error -> {
                pbLoading.isVisible = false
                layoutError.isVisible = true
                layoutContent.isVisible = false
                tvError.text = state.message
            }
            is CashManagementState.Success -> {
                pbLoading.isVisible = false
                layoutError.isVisible = false
                layoutContent.isVisible = true
                showSuccess(state)
            }
        }
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        val sign = if (whole < 0) "\u2212" else ""
        val formatted = NumberFormat.getNumberInstance(Locale.US).format(Math.abs(whole))
        return "$sign\u09F3$formatted"
    }

    private fun showSuccess(state: CashManagementState.Success) {
        tvToBePaid.text = taka(state.summary.toBePaid)
        tvAgainstCollection.text = "against ${taka(state.summary.totalCollection)} total collection"
        tvStatCollection.text = taka(state.summary.totalCollection)
        tvStatCashInHand.text = taka(state.summary.cashInHand)
        tvStatHubPaid.text = taka(state.summary.totalHubPayment)

        val stillEmptyRows = pendingEmptyRowCount
        layoutProviderCards.removeAllViews()
        state.accounts.forEach { account -> layoutProviderCards.addView(buildProviderCard(account.provider, account)) }
        repeat(stillEmptyRows) { layoutProviderCards.addView(buildProviderCard(null, null)) }

        layoutCollectionEntries.removeAllViews()
        if (state.collections.isEmpty()) {
            layoutCollectionEntries.addView(buildEmptyRow("No collection entries yet."))
        } else {
            state.collections.forEach { entry ->
                layoutCollectionEntries.addView(buildSimpleEntryRow(dateFmt.format(Date(entry.timestamp)), taka(entry.amount)))
            }
        }
    }

    private fun addEmptyProviderRow() {
        pendingEmptyRowCount++
        layoutProviderCards.addView(buildProviderCard(null, null))
    }

    private fun buildProviderCard(initialProvider: String?, account: MfsAccountSummary?): View {
        val card = layoutInflater.inflate(R.layout.item_cash_provider_card, layoutProviderCards, false)

        val spinner              = card.findViewById<Spinner>(R.id.spinnerProvider)
        val etCustomName          = card.findViewById<EditText>(R.id.etCustomProviderName)
        val btnRemove             = card.findViewById<ImageButton>(R.id.btnRemoveProvider)
        val layoutLedgerContent   = card.findViewById<View>(R.id.layoutLedgerContent)
        val tvBalance             = card.findViewById<TextView>(R.id.tvBalance)
        val tvBadge               = card.findViewById<TextView>(R.id.tvBadge)
        val btnToggleHandover     = card.findViewById<Button>(R.id.btnToggleHandover)
        val btnToggleHubPayment   = card.findViewById<Button>(R.id.btnToggleHubPayment)
        val layoutInlineForm      = card.findViewById<View>(R.id.layoutInlineForm)
        val etAmount              = card.findViewById<EditText>(R.id.etEntryAmount)
        val etTrxId               = card.findViewById<EditText>(R.id.etEntryTrxId)
        val tvEnteredByPreview    = card.findViewById<TextView>(R.id.tvEnteredByPreview)
        val btnSaveEntry          = card.findViewById<Button>(R.id.btnSaveEntry)
        val layoutLedgerEntries   = card.findViewById<LinearLayout>(R.id.layoutLedgerEntries)

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, providerOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        var openFormType: String? = null // LEDGER_TYPE_HANDOVER | LEDGER_TYPE_HUB_PAYMENT | null

        fun resolvedName(): String = when (spinner.selectedItem?.toString()) {
            "Other" -> etCustomName.text.toString().trim()
            "Select provider", null -> ""
            else -> spinner.selectedItem.toString()
        }

        fun refreshLedgerUi() {
            val name = resolvedName()
            if (name.isBlank()) {
                layoutLedgerContent.isVisible = false
                return
            }
            layoutLedgerContent.isVisible = true
            val hasActivity = account?.hasActivity ?: false
            val balance = account?.balance ?: 0.0
            val settled = account?.settled ?: false

            tvBalance.text = taka(balance)
            when {
                !hasActivity -> {
                    tvBadge.text = "No activity"
                    tvBadge.setBackgroundResource(R.drawable.badge_bg_neutral)
                }
                settled -> {
                    tvBadge.text = "Settled"
                    tvBadge.setBackgroundResource(R.drawable.badge_bg_settled)
                }
                else -> {
                    tvBadge.text = "Balance pending"
                    tvBadge.setBackgroundResource(R.drawable.badge_bg_pending)
                }
            }

            layoutLedgerEntries.removeAllViews()
            val entries = (account?.handovers.orEmpty().map { it to LEDGER_TYPE_HANDOVER } +
                    account?.hubPayments.orEmpty().map { it to LEDGER_TYPE_HUB_PAYMENT })
                .sortedByDescending { it.first.timestamp }
                .take(4)
            if (entries.isEmpty()) {
                layoutLedgerEntries.isVisible = false
            } else {
                layoutLedgerEntries.isVisible = true
                entries.forEach { (entry, type) ->
                    val arrow = if (type == LEDGER_TYPE_HANDOVER) "\u2193" else "\u2191"
                    layoutLedgerEntries.addView(buildSimpleEntryRow("$arrow #${entry.trxId}", taka(entry.amount)))
                }
            }
        }

        refreshLedgerUi()

        if (!initialProvider.isNullOrBlank()) {
            val presetIndex = providerOptions.indexOf(initialProvider)
            if (presetIndex >= 0) {
                spinner.setSelection(presetIndex)
            } else {
                spinner.setSelection(providerOptions.indexOf("Other"))
                etCustomName.isVisible = true
                etCustomName.setText(initialProvider)
            }
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val value = providerOptions[position]
                etCustomName.isVisible = value == "Other"
                if (value != "Select provider" && value != "Other") {
                    pendingEmptyRowCount = (pendingEmptyRowCount - 1).coerceAtLeast(0)
                    vm.addProvider(value)
                }
                refreshLedgerUi()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        etCustomName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val name = etCustomName.text.toString().trim()
                if (name.isNotBlank()) {
                    pendingEmptyRowCount = (pendingEmptyRowCount - 1).coerceAtLeast(0)
                    vm.addProvider(name)
                }
            }
        }

        btnRemove.setOnClickListener {
            val name = resolvedName()
            if (name.isNotBlank()) {
                vm.removeProvider(name)
            } else {
                pendingEmptyRowCount = (pendingEmptyRowCount - 1).coerceAtLeast(0)
                layoutProviderCards.removeView(card)
            }
        }

        fun openForm(type: String) {
            openFormType = type
            layoutInlineForm.isVisible = true
            etAmount.setText("")
            etTrxId.setText("")
            tvEnteredByPreview.text = "By $currentUserDisplayName \u00B7 now"
            etAmount.requestFocus()
        }

        btnToggleHandover.setOnClickListener {
            if (openFormType == LEDGER_TYPE_HANDOVER) { layoutInlineForm.isVisible = false; openFormType = null }
            else openForm(LEDGER_TYPE_HANDOVER)
        }
        btnToggleHubPayment.setOnClickListener {
            if (openFormType == LEDGER_TYPE_HUB_PAYMENT) { layoutInlineForm.isVisible = false; openFormType = null }
            else openForm(LEDGER_TYPE_HUB_PAYMENT)
        }

        btnSaveEntry.setOnClickListener {
            val type = openFormType ?: return@setOnClickListener
            val amt = etAmount.text.toString().toDoubleOrNull()
            val trxId = etTrxId.text.toString().trim()
            if (amt == null || amt <= 0.0) {
                Toast.makeText(requireContext(), "Enter a valid amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (trxId.isBlank()) {
                Toast.makeText(requireContext(), "Enter a transaction ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.addLedgerEntry(resolvedName(), type, amt, trxId) { ok ->
                if (ok) {
                    layoutInlineForm.isVisible = false
                    openFormType = null
                } else {
                    Toast.makeText(requireContext(), "Failed to save entry", Toast.LENGTH_SHORT).show()
                }
            }
        }

        return card
    }

    private val currentUserDisplayName: String
        get() {
            val u = auth.currentUser
            return u?.displayName?.takeIf { it.isNotBlank() } ?: u?.email?.takeIf { it.isNotBlank() } ?: "you"
        }

    private fun buildSimpleEntryRow(left: String, right: String): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(requireContext()).apply {
            text = left
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(requireContext()).apply {
            text = right
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF1E293B.toInt())
        })
        return row
    }

    private fun buildEmptyRow(message: String): View {
        return TextView(requireContext()).apply {
            text = message
            textSize = 13f
            setTextColor(0xFF94A3B8.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
