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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Branch-scoped cash reconciliation screen:
 *   Collection (manual entry) -> deposited into one or more MFS channel accounts
 *   (Rocket / bKash / Other, configurable per branch) -> from each channel a
 * "Pay to hub" settles some or all of it back to the branch's central account.
 * Not day-locked: a day's collection can be fully settled same-day, partially, or
 * carried forward if an MFS channel doesn't have full balance ready yet.
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
    private lateinit var tvDateRangeButton: TextView
    private lateinit var btnClearDateRange: ImageButton
    private lateinit var tvStatCollection: TextView
    private lateinit var tvStatCashInHand: TextView
    private lateinit var tvStatMfsBalance: TextView
    private lateinit var tvStatHubPaid: TextView
    private lateinit var layoutMfsBreakdown: LinearLayout
    private lateinit var layoutProviderCards: LinearLayout
    private lateinit var btnAddProvider: Button
    private lateinit var layoutCollectionEntries: LinearLayout
    private lateinit var btnAddCollection: Button
    private lateinit var tabLayoutCash: TabLayout
    private lateinit var layoutCollectionsTab: View
    private lateinit var layoutHandoverTab: View
    private lateinit var layoutPaymentTab: View
    private lateinit var chipGroupPaymentChannels: ChipGroup
    private lateinit var layoutSelectedPaymentChannel: FrameLayout

    private var branchId: String = ""
    private val dateFmt = SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault())
    private val rangeLabelFmt = SimpleDateFormat("dd MMM", Locale.getDefault())
    private val providerOptions = listOf("Select provider", "Rocket", "bKash", "Other")

    private val colorGreen = 0xFF15803D.toInt()
    private val colorRed = 0xFFB91C1C.toInt()
    private val colorNeutral = 0xFF1E293B.toInt()

    // null = show all-time summary. Otherwise [start, endInclusive] in epoch ms,
    // applied only to the summary card (Handover/Payment tabs always show the full
    // live channel list regardless, since you need to see everything to act on it).
    private var selectedDateRange: Pair<Long, Long>? = null
    private var lastSuccessState: CashManagementState.Success? = null

    // Counts provider-picker rows the person has opened via "+ Add channel" but not
    // yet resolved to a real name. Every Success re-render rebuilds the whole card
    // list from Firebase state, so this keeps still-in-progress blank rows from
    // disappearing underneath the person mid-pick.
    private var pendingEmptyRowCount = 0

    // Which channel's card is showing on the Payment tab's chip selector.
    private var selectedPaymentChannel: String? = null

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

        swipeRefresh            = view.findViewById(R.id.swipeRefreshCash)
        pbLoading                = view.findViewById(R.id.pbLoading)
        layoutError              = view.findViewById(R.id.layoutError)
        tvError                  = view.findViewById(R.id.tvError)
        btnRetry                 = view.findViewById(R.id.btnRetry)
        layoutContent            = view.findViewById(R.id.layoutContent)
        tvBranchName             = view.findViewById(R.id.tvBranchName)
        tvToBePaid               = view.findViewById(R.id.tvToBePaid)
        tvAgainstCollection      = view.findViewById(R.id.tvAgainstCollection)
        tvDateRangeButton        = view.findViewById(R.id.tvDateRangeButton)
        btnClearDateRange        = view.findViewById(R.id.btnClearDateRange)
        tvStatCollection         = view.findViewById(R.id.tvStatCollection)
        tvStatCashInHand         = view.findViewById(R.id.tvStatCashInHand)
        tvStatMfsBalance         = view.findViewById(R.id.tvStatMfsBalance)
        tvStatHubPaid            = view.findViewById(R.id.tvStatHubPaid)
        layoutMfsBreakdown       = view.findViewById(R.id.layoutMfsBreakdown)
        layoutProviderCards      = view.findViewById(R.id.layoutProviderCards)
        btnAddProvider           = view.findViewById(R.id.btnAddProvider)
        layoutCollectionEntries  = view.findViewById(R.id.layoutCollectionEntries)
        btnAddCollection         = view.findViewById(R.id.btnAddCollection)
        tabLayoutCash            = view.findViewById(R.id.tabLayoutCash)
        layoutCollectionsTab     = view.findViewById(R.id.layoutCollectionsTab)
        layoutHandoverTab        = view.findViewById(R.id.layoutHandoverTab)
        layoutPaymentTab         = view.findViewById(R.id.layoutPaymentTab)
        chipGroupPaymentChannels = view.findViewById(R.id.chipGroupPaymentChannels)
        layoutSelectedPaymentChannel = view.findViewById(R.id.layoutSelectedPaymentChannel)

        tvBranchName.text = when {
            branchId.isBlank() -> "NO BRANCH ASSIGNED"
            RbacManager.current.branchName.isNotBlank() -> RbacManager.current.branchName
            else -> branchId
        }

        tabLayoutCash.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                layoutCollectionsTab.isVisible = tab.position == 0
                layoutHandoverTab.isVisible = tab.position == 1
                layoutPaymentTab.isVisible = tab.position == 2
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        swipeRefresh.setOnRefreshListener { vm.refresh() }
        btnRetry.setOnClickListener { vm.load(branchId) }
        btnAddProvider.setOnClickListener { addEmptyProviderRow() }
        btnAddCollection.setOnClickListener { showAddCollectionDialog() }
        tvDateRangeButton.setOnClickListener { showDateRangePicker() }
        btnClearDateRange.setOnClickListener {
            selectedDateRange = null
            updateDateRangeButtonLabel()
            lastSuccessState?.let { renderSummary(it) }
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
        lastSuccessState = state
        renderSummary(state)

        val stillEmptyRows = pendingEmptyRowCount
        layoutProviderCards.removeAllViews()
        state.accounts.forEach { account -> layoutProviderCards.addView(buildProviderCard(account.provider, account)) }
        repeat(stillEmptyRows) { layoutProviderCards.addView(buildProviderCard(null, null)) }

        rebuildPaymentTab(state.accounts)

        layoutCollectionEntries.removeAllViews()
        if (state.collections.isEmpty()) {
            layoutCollectionEntries.addView(buildEmptyRow("No collection entries yet."))
        } else {
            state.collections.forEach { entry ->
                layoutCollectionEntries.addView(
                    buildEditableEntryRow(
                        left = dateFmt.format(Date(entry.timestamp)),
                        amount = entry.amount,
                        amountColor = colorNeutral,
                        onEdit = { showEditCollectionDialog(entry) },
                        onDelete = { confirmDeleteCollection(entry) }
                    )
                )
            }
        }
    }

    // Recomputes and renders just the summary card for the current date-range
    // selection (or all-time if none). Handover/Payment tabs are untouched --
    // they always reflect the full live channel list regardless of this filter.
    private fun renderSummary(state: CashManagementState.Success) {
        val range = selectedDateRange
        val collections = if (range == null) state.collections else state.collections.filter { it.timestamp in range.first..range.second }
        val accounts = if (range == null) state.accounts else state.accounts.map { acc ->
            MfsAccountSummary(
                provider = acc.provider,
                handovers = acc.handovers.filter { it.timestamp in range.first..range.second },
                hubPayments = acc.hubPayments.filter { it.timestamp in range.first..range.second },
            )
        }
        val totalCollection = collections.sumOf { it.amount }
        val totalHandover = accounts.sumOf { it.handoverTotal }
        val totalHubPayment = accounts.sumOf { it.hubPaymentTotal }
        val summary = CashManagementSummary(totalCollection, totalHandover, totalHubPayment)

        tvToBePaid.text = taka(summary.toBePaid)
        tvAgainstCollection.text = "against ${taka(summary.totalCollection)} total collection"
        tvStatCollection.text = taka(summary.totalCollection)
        tvStatCashInHand.text = taka(summary.cashInHand)
        tvStatMfsBalance.text = taka(summary.totalMfsBalance)
        tvStatHubPaid.text = taka(summary.totalHubPayment)

        layoutMfsBreakdown.removeAllViews()
        val activeAccounts = accounts.filter { it.hasActivity }
        if (activeAccounts.isEmpty()) {
            layoutMfsBreakdown.addView(buildEmptyRow(if (range == null) "No MFS balance yet." else "No MFS activity in this range."))
        } else {
            activeAccounts.sortedByDescending { it.balance }.forEach { account ->
                layoutMfsBreakdown.addView(buildSimpleEntryRow(account.provider, taka(account.balance)))
            }
        }
    }

    private fun updateDateRangeButtonLabel() {
        val range = selectedDateRange
        tvDateRangeButton.text = if (range == null) {
            "\uD83D\uDCC5  All time"
        } else {
            "\uD83D\uDCC5  ${rangeLabelFmt.format(Date(range.first))} \u2013 ${rangeLabelFmt.format(Date(range.second))}"
        }
        btnClearDateRange.isVisible = range != null
    }

    private fun showDateRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select date range")
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val start = selection.first ?: return@addOnPositiveButtonClickListener
            val end = selection.second ?: start
            // MaterialDatePicker gives UTC midnight for both ends; push the end
            // out to the last millisecond of that day so entries later that day
            // aren't excluded.
            val endOfDay = end + 24 * 60 * 60 * 1000L - 1
            selectedDateRange = start to endOfDay
            updateDateRangeButtonLabel()
            lastSuccessState?.let { renderSummary(it) }
        }
        picker.show(childFragmentManager, "cash_date_range_picker")
    }

    // ── Single-date picker helper, used by every add/deposit/payment popup ──────

    // MaterialDatePicker's single-date result is UTC midnight of the picked day.
    // Combine that day with the current local time-of-day so a "Today" pick keeps
    // showing roughly when it was actually entered, and a backdated pick doesn't
    // look like it happened at midnight.
    private fun combineDateKeepingTimeOfDay(pickedUtcMidnight: Long): Long {
        val datePart = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = pickedUtcMidnight }
        val combined = Calendar.getInstance()
        combined.set(Calendar.YEAR, datePart.get(Calendar.YEAR))
        combined.set(Calendar.MONTH, datePart.get(Calendar.MONTH))
        combined.set(Calendar.DAY_OF_MONTH, datePart.get(Calendar.DAY_OF_MONTH))
        return combined.timeInMillis
    }

    private fun isSameLocalDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    // Wires a "date button" inside a dialog to open a single-date MaterialDatePicker
    // and update its own label. Returns a getter for whatever is currently picked
    // (defaults to now, so an untouched button still yields a sensible timestamp).
    private fun wireDatePickerButton(button: TextView, initial: Long): () -> Long {
        var selected = initial
        fun updateLabel() {
            button.text = "\uD83D\uDCC5  " + if (isSameLocalDay(selected, System.currentTimeMillis())) "Today" else rangeLabelFmt.format(Date(selected))
        }
        updateLabel()
        button.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Select date")
                .setSelection(selected)
                .build()
            picker.addOnPositiveButtonClickListener { pickedMillis ->
                selected = combineDateKeepingTimeOfDay(pickedMillis)
                updateLabel()
            }
            picker.show(childFragmentManager, "cash_single_date_picker")
        }
        return { selected }
    }

    // ── Collection ───────────────────────────────────────────────────────────

    private fun showAddCollectionDialog() {
        val padding = dp(20)
        val amountInput = EditText(requireContext()).apply {
            hint = "Amount"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            typeface = Typeface.MONOSPACE
        }
        val dateButton = TextView(requireContext()).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF0F766E.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundResource(R.drawable.bg_dashed_button)
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        val getDate = wireDatePickerButton(dateButton, System.currentTimeMillis())

        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(amountInput)
            addView(dateButton)
        }

        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("Add collection")
            .setView(wrapper)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val btnPositive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            val btnNegative = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
            btnPositive.setOnClickListener {
                val amt = amountInput.text.toString().toDoubleOrNull()
                if (amt == null || amt <= 0.0) {
                    Toast.makeText(requireContext(), "Enter a valid amount", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                btnPositive.isEnabled = false
                btnNegative.isEnabled = false
                btnPositive.text = "Saving..."
                vm.addCollection(amt, getDate()) { ok ->
                    if (ok) {
                        dialog.dismiss()
                        Toast.makeText(requireContext(), "Collection saved", Toast.LENGTH_SHORT).show()
                    } else {
                        btnPositive.isEnabled = true
                        btnNegative.isEnabled = true
                        btnPositive.text = "Save"
                        Toast.makeText(requireContext(), "Failed to save collection", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showEditCollectionDialog(entry: CashCollectionEntry) {
        val padding = dp(20)
        val input = EditText(requireContext()).apply {
            hint = "Amount"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            typeface = Typeface.MONOSPACE
            setText(Math.round(entry.amount).toString())
            setSelection(text.length)
        }
        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(input)
        }
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("Edit collection")
            .setView(wrapper)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val btnPositive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            val btnNegative = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
            btnPositive.setOnClickListener {
                val amt = input.text.toString().toDoubleOrNull()
                if (amt == null || amt <= 0.0) {
                    Toast.makeText(requireContext(), "Enter a valid amount", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                btnPositive.isEnabled = false
                btnNegative.isEnabled = false
                btnPositive.text = "Saving..."
                vm.updateCollection(entry.id, amt) { ok ->
                    if (ok) {
                        dialog.dismiss()
                        Toast.makeText(requireContext(), "Collection updated", Toast.LENGTH_SHORT).show()
                    } else {
                        btnPositive.isEnabled = true
                        btnNegative.isEnabled = true
                        btnPositive.text = "Save"
                        Toast.makeText(requireContext(), "Failed to update collection", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteCollection(entry: CashCollectionEntry) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete collection entry?")
            .setMessage("${taka(entry.amount)} \u00B7 ${dateFmt.format(Date(entry.timestamp))}. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                vm.deleteCollection(entry.id) { ok ->
                    Toast.makeText(
                        requireContext(),
                        if (ok) "Collection deleted" else "Failed to delete collection",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Handover tab ─────────────────────────────────────────────────────────

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
        val btnDeposit            = card.findViewById<Button>(R.id.btnDeposit)
        val layoutLedgerEntries   = card.findViewById<LinearLayout>(R.id.layoutLedgerEntries)

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, providerOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

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

            btnDeposit.setOnClickListener { showDepositDialog(name) }

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
                    val trxLabel = if (entry.trxId.isBlank()) "$arrow no trx id" else "$arrow #${entry.trxId}"
                    val color = if (type == LEDGER_TYPE_HANDOVER) colorGreen else colorRed
                    layoutLedgerEntries.addView(
                        buildEditableEntryRow(
                            left = trxLabel,
                            amount = entry.amount,
                            amountColor = color,
                            onEdit = { showEditLedgerEntryDialog(name, type, entry, account) },
                            onDelete = { confirmDeleteLedgerEntry(name, type, entry) }
                        )
                    )
                }
            }
        }

        refreshLedgerUi()

        // Spinner.setSelection() posts its selection notification rather than firing
        // it synchronously, so it still reaches onItemSelected below even though the
        // listener gets attached *after* this call. Without this guard, restoring an
        // already-existing channel's selection on every rebuild would look like a
        // fresh pick, call vm.addProvider() again, trigger refresh(), rebuild the
        // cards, restore selection again... an infinite reload loop.
        var isRestoringSelection = false

        if (!initialProvider.isNullOrBlank()) {
            isRestoringSelection = true
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
                if (isRestoringSelection) {
                    isRestoringSelection = false
                    refreshLedgerUi()
                    return
                }
                if (value != "Select provider" && value != "Other") {
                    pendingEmptyRowCount = (pendingEmptyRowCount - 1).coerceAtLeast(0)
                    vm.addProvider(value) { ok ->
                        Toast.makeText(
                            requireContext(),
                            if (ok) "Channel added" else "Failed to add channel",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
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
                    vm.addProvider(name) { ok ->
                        Toast.makeText(
                            requireContext(),
                            if (ok) "Channel added" else "Failed to add channel",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        btnRemove.setOnClickListener {
            val name = resolvedName()
            if (name.isNotBlank()) {
                vm.removeProvider(name) { ok ->
                    Toast.makeText(
                        requireContext(),
                        if (ok) "Channel removed" else "Failed to remove channel",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                pendingEmptyRowCount = (pendingEmptyRowCount - 1).coerceAtLeast(0)
                layoutProviderCards.removeView(card)
            }
        }

        return card
    }

    private fun showDepositDialog(providerName: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_cash_payment, null)
        val tvInfo     = dialogView.findViewById<TextView>(R.id.tvDialogChannelInfo)
        val etAmount   = dialogView.findViewById<EditText>(R.id.etDialogAmount)
        val etTrxId    = dialogView.findViewById<EditText>(R.id.etDialogTrxId)
        val dateButton = dialogView.findViewById<TextView>(R.id.tvDialogDateButton)

        tvInfo.text = "Deposit into $providerName"
        val getDate = wireDatePickerButton(dateButton, System.currentTimeMillis())

        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("Deposit")
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val btnPositive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            val btnNegative = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
            btnPositive.setOnClickListener {
                val amt = etAmount.text.toString().toDoubleOrNull()
                val trxId = etTrxId.text.toString().trim()
                if (amt == null || amt <= 0.0) {
                    Toast.makeText(requireContext(), "Enter a valid amount", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                btnPositive.isEnabled = false
                btnNegative.isEnabled = false
                btnPositive.text = "Saving..."
                vm.addLedgerEntry(providerName, LEDGER_TYPE_HANDOVER, amt, trxId, getDate()) { ok ->
                    if (ok) {
                        dialog.dismiss()
                        Toast.makeText(requireContext(), "Deposit saved", Toast.LENGTH_SHORT).show()
                    } else {
                        btnPositive.isEnabled = true
                        btnNegative.isEnabled = true
                        btnPositive.text = "Save"
                        Toast.makeText(requireContext(), "Failed to save deposit", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showEditLedgerEntryDialog(providerName: String, type: String, entry: CashLedgerEntry, account: MfsAccountSummary?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_cash_payment, null)
        val tvInfo   = dialogView.findViewById<TextView>(R.id.tvDialogChannelInfo)
        val etAmount = dialogView.findViewById<EditText>(R.id.etDialogAmount)
        val etTrxId  = dialogView.findViewById<EditText>(R.id.etDialogTrxId)
        val dateRow  = dialogView.findViewById<TextView>(R.id.tvDialogDateButton)
        dateRow.isVisible = false // editing keeps the original date; only amount/trxid change here

        val kindLabel = if (type == LEDGER_TYPE_HANDOVER) "Deposit" else "Hub payment"
        val maxAllowed = if (type == LEDGER_TYPE_HUB_PAYMENT && account != null) account.balance + entry.amount else null
        tvInfo.text = if (maxAllowed != null) "$providerName \u00B7 $kindLabel \u00B7 max ${taka(maxAllowed)}" else "$providerName \u00B7 $kindLabel"
        etAmount.setText(Math.round(entry.amount).toString())
        etTrxId.setText(entry.trxId)

        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("Edit ${kindLabel.lowercase()}")
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val btnPositive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            val btnNegative = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
            btnPositive.setOnClickListener {
                val amt = etAmount.text.toString().toDoubleOrNull()
                val trxId = etTrxId.text.toString().trim()
                if (amt == null || amt <= 0.0) {
                    Toast.makeText(requireContext(), "Enter a valid amount", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (maxAllowed != null && amt > maxAllowed) {
                    Toast.makeText(requireContext(), "Can't exceed ${taka(maxAllowed)}", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                btnPositive.isEnabled = false
                btnNegative.isEnabled = false
                btnPositive.text = "Saving..."
                vm.updateLedgerEntry(providerName, type, entry.id, amt, trxId) { ok ->
                    if (ok) {
                        dialog.dismiss()
                        Toast.makeText(requireContext(), "Entry updated", Toast.LENGTH_SHORT).show()
                    } else {
                        btnPositive.isEnabled = true
                        btnNegative.isEnabled = true
                        btnPositive.text = "Save"
                        Toast.makeText(requireContext(), "Failed to update entry", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteLedgerEntry(providerName: String, type: String, entry: CashLedgerEntry) {
        val kindLabel = if (type == LEDGER_TYPE_HANDOVER) "deposit" else "hub payment"
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete this $kindLabel?")
            .setMessage("${taka(entry.amount)}. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                vm.deleteLedgerEntry(providerName, type, entry.id) { ok ->
                    Toast.makeText(
                        requireContext(),
                        if (ok) "Entry deleted" else "Failed to delete entry",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Payment tab (chip selector + single channel detail) ────────────────────

    private fun rebuildPaymentTab(accounts: List<MfsAccountSummary>) {
        chipGroupPaymentChannels.removeAllViews()
        if (accounts.isEmpty()) {
            selectedPaymentChannel = null
            layoutSelectedPaymentChannel.removeAllViews()
            layoutSelectedPaymentChannel.addView(buildEmptyRow("No channels yet -- add one from the Handover tab."))
            return
        }
        if (selectedPaymentChannel == null || accounts.none { it.provider == selectedPaymentChannel }) {
            selectedPaymentChannel = accounts.first().provider
        }
        accounts.forEach { account ->
            val chip = Chip(requireContext()).apply {
                text = account.provider
                isCheckable = true
                isCheckedIconVisible = false
                chipBackgroundColor = ContextCompat.getColorStateList(requireContext(), R.color.chip_selector)
                isChecked = account.provider == selectedPaymentChannel
                setOnClickListener {
                    selectedPaymentChannel = account.provider
                    renderSelectedPaymentChannel(accounts)
                }
            }
            chipGroupPaymentChannels.addView(chip)
        }
        renderSelectedPaymentChannel(accounts)
    }

    private fun renderSelectedPaymentChannel(accounts: List<MfsAccountSummary>) {
        layoutSelectedPaymentChannel.removeAllViews()
        val account = accounts.find { it.provider == selectedPaymentChannel } ?: return
        layoutSelectedPaymentChannel.addView(buildPaymentChannelDetailView(account))
    }

    private fun buildPaymentChannelDetailView(account: MfsAccountSummary): View {
        val card = layoutInflater.inflate(R.layout.item_cash_payment_channel, layoutSelectedPaymentChannel, false)

        val tvChannelName = card.findViewById<TextView>(R.id.tvChannelName)
        val tvBalance      = card.findViewById<TextView>(R.id.tvBalance)
        val tvBadge        = card.findViewById<TextView>(R.id.tvBadge)
        val btnPayToHub    = card.findViewById<Button>(R.id.btnPayToHub)

        tvChannelName.text = account.provider
        tvBalance.text = taka(account.balance)
        when {
            !account.hasActivity -> {
                tvBadge.text = "No activity"
                tvBadge.setBackgroundResource(R.drawable.badge_bg_neutral)
            }
            account.settled -> {
                tvBadge.text = "Settled"
                tvBadge.setBackgroundResource(R.drawable.badge_bg_settled)
            }
            else -> {
                tvBadge.text = "Balance pending"
                tvBadge.setBackgroundResource(R.drawable.badge_bg_pending)
            }
        }

        // Nothing to pay out yet -- hide the action rather than let someone
        // record a payment against a channel with no leftover balance.
        btnPayToHub.isVisible = account.balance > 0.0
        btnPayToHub.setOnClickListener { showPayToHubDialog(account) }

        return card
    }

    private fun showPayToHubDialog(account: MfsAccountSummary) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_cash_payment, null)
        val tvInfo     = dialogView.findViewById<TextView>(R.id.tvDialogChannelInfo)
        val etAmount   = dialogView.findViewById<EditText>(R.id.etDialogAmount)
        val etTrxId    = dialogView.findViewById<EditText>(R.id.etDialogTrxId)
        val dateButton = dialogView.findViewById<TextView>(R.id.tvDialogDateButton)

        tvInfo.text = "${account.provider} \u00B7 ${taka(account.balance)} available to pay"
        etAmount.setText(if (account.balance > 0) Math.round(account.balance).toString() else "")
        val getDate = wireDatePickerButton(dateButton, System.currentTimeMillis())

        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("Pay to hub")
            .setView(dialogView)
            .setPositiveButton("Pay Now", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val btnPositive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            val btnNegative = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
            btnPositive.setOnClickListener {
                val amt = etAmount.text.toString().toDoubleOrNull()
                val trxId = etTrxId.text.toString().trim()
                if (amt == null || amt <= 0.0) {
                    Toast.makeText(requireContext(), "Enter a valid amount", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (amt > account.balance) {
                    Toast.makeText(
                        requireContext(),
                        "Can't exceed available balance (${taka(account.balance)})",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                btnPositive.isEnabled = false
                btnNegative.isEnabled = false
                btnPositive.text = "Paying..."
                vm.addLedgerEntry(account.provider, LEDGER_TYPE_HUB_PAYMENT, amt, trxId, getDate()) { ok ->
                    if (ok) {
                        dialog.dismiss()
                        Toast.makeText(requireContext(), "Payment saved", Toast.LENGTH_SHORT).show()
                    } else {
                        btnPositive.isEnabled = true
                        btnNegative.isEnabled = true
                        btnPositive.text = "Pay Now"
                        Toast.makeText(requireContext(), "Failed to save payment", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    // ── Small shared view builders ──────────────────────────────────────────────

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

    // Same layout as buildSimpleEntryRow but for an actual saved transaction
    // (collection entry or ledger entry) -- adds icon-only Edit/Delete buttons and
    // a caller-chosen amount color (green/red for handover/payment, neutral for
    // collections). Not used for aggregate rows like the MFS-balance-by-channel
    // breakdown, since those aren't a single editable record.
    private fun buildEditableEntryRow(left: String, amount: Double, amountColor: Int, onEdit: () -> Unit, onDelete: () -> Unit): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(requireContext()).apply {
            text = left
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(requireContext()).apply {
            text = taka(amount)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(amountColor)
        })
        row.addView(ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            background = null
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(4) }
            contentDescription = "Edit"
            setOnClickListener { onEdit() }
        })
        row.addView(ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            background = null
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(2) }
            contentDescription = "Delete"
            setOnClickListener { onDelete() }
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
