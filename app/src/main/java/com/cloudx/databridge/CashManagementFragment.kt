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
 *   Collection (manual entry) -> deposited into one or more MFS channels
 *   (a channel is just a name that accumulates a balance -- created the moment
 *   it's first deposited into) -> from each channel a "Pay to hub" settles some
 * or all of it back to the branch's central account. Not day-locked: a day's
 * collection can be fully settled same-day, partially, or carried forward.
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
    private lateinit var btnAddDeposit: Button
    private lateinit var layoutDepositEntries: LinearLayout
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

    private val colorGreen = 0xFF15803D.toInt()
    private val colorRed = 0xFFB91C1C.toInt()
    private val colorNeutral = 0xFF1E293B.toInt()

    // null = show all-time summary. Otherwise [start, endInclusive] in epoch ms,
    // applied only to the summary card (Deposit/Payment tabs always show the full
    // live channel list regardless, since you need to see everything to act on it).
    private var selectedDateRange: Pair<Long, Long>? = null
    private var lastSuccessState: CashManagementState.Success? = null

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
        btnAddDeposit            = view.findViewById(R.id.btnAddDeposit)
        layoutDepositEntries     = view.findViewById(R.id.layoutDepositEntries)
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
        btnAddCollection.setOnClickListener { showAddCollectionDialog() }
        btnAddDeposit.setOnClickListener { showDepositDialog() }
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

        // Flat deposit log: every handover across every channel, newest first.
        layoutDepositEntries.removeAllViews()
        val allDeposits = state.accounts.flatMap { acc -> acc.handovers.map { acc.provider to it } }
            .sortedByDescending { it.second.timestamp }
        if (allDeposits.isEmpty()) {
            layoutDepositEntries.addView(buildEmptyRow("No deposits yet."))
        } else {
            allDeposits.forEach { (provider, entry) ->
                layoutDepositEntries.addView(
                    buildEditableEntryRow(
                        title = provider,
                        subtitle = entryMetaLine(entry),
                        amount = entry.amount,
                        rowColor = colorGreen,
                        onEdit = { showEditLedgerEntryDialog(provider, LEDGER_TYPE_HANDOVER, entry, state.accounts.find { it.provider == provider }) },
                        onDelete = { confirmDeleteLedgerEntry(provider, LEDGER_TYPE_HANDOVER, entry) }
                    )
                )
            }
        }

        rebuildPaymentTab(state.accounts)

        layoutCollectionEntries.removeAllViews()
        if (state.collections.isEmpty()) {
            layoutCollectionEntries.addView(buildEmptyRow("No collection entries yet."))
        } else {
            state.collections.forEach { entry ->
                layoutCollectionEntries.addView(
                    buildEditableEntryRow(
                        title = dateFmt.format(Date(entry.timestamp)),
                        subtitle = null,
                        amount = entry.amount,
                        rowColor = colorNeutral,
                        onEdit = { showEditCollectionDialog(entry) },
                        onDelete = { confirmDeleteCollection(entry) }
                    )
                )
            }
        }
    }

    private fun entryMetaLine(entry: CashLedgerEntry): String {
        val trx = if (entry.trxId.isBlank()) "no trx id" else "#${entry.trxId}"
        return "$trx \u00B7 ${dateFmt.format(Date(entry.timestamp))}"
    }

    // Recomputes and renders just the summary card for the current date-range
    // selection (or all-time if none). Deposit/Payment tabs are untouched --
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
            val endOfDay = end + 24 * 60 * 60 * 1000L - 1
            selectedDateRange = start to endOfDay
            updateDateRangeButtonLabel()
            lastSuccessState?.let { renderSummary(it) }
        }
        picker.show(childFragmentManager, "cash_date_range_picker")
    }

    // ── Single-date picker helper, used by every add/deposit/payment popup ──────

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

    private fun styledDateButton(): TextView = TextView(requireContext()).apply {
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        setTextColor(0xFF0F766E.toInt())
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setBackgroundResource(R.drawable.bg_dashed_button)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    // ── Collection ───────────────────────────────────────────────────────────

    private fun showAddCollectionDialog() {
        val padding = dp(20)
        val amountInput = EditText(requireContext()).apply {
            hint = "Amount"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            typeface = Typeface.MONOSPACE
        }
        val dateButton = styledDateButton()
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

    // ── Deposit (formerly Handover) tab ─────────────────────────────────────────
    // One global Deposit button rather than a per-channel card: a channel is
    // just a name that accumulates balance, so picking a not-yet-used name in
    // this same popup both creates it and records its first deposit in one step.

    private fun showDepositDialog() {
        val padding = dp(20)
        val existingChannels = lastSuccessState?.accounts?.map { it.provider }.orEmpty()
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
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                customNameInput.isVisible = channelOptions[position] == "Other"
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val amountInput = EditText(requireContext()).apply {
            hint = "Amount"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        val trxIdInput = EditText(requireContext()).apply {
            hint = "Transaction ID (optional)"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        val dateButton = styledDateButton()
        val getDate = wireDatePickerButton(dateButton, System.currentTimeMillis())

        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(spinner)
            addView(customNameInput)
            addView(amountInput)
            addView(trxIdInput)
            addView(dateButton)
        }

        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("Deposit")
            .setView(wrapper)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val btnPositive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            val btnNegative = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
            btnPositive.setOnClickListener {
                val selectedOption = spinner.selectedItem?.toString().orEmpty()
                val channelName = if (selectedOption == "Other") customNameInput.text.toString().trim() else selectedOption
                if (selectedOption == "Select channel" || channelName.isBlank()) {
                    Toast.makeText(requireContext(), "Select or enter a channel", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val amt = amountInput.text.toString().toDoubleOrNull()
                if (amt == null || amt <= 0.0) {
                    Toast.makeText(requireContext(), "Enter a valid amount", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val trxId = trxIdInput.text.toString().trim()
                val dateMillis = getDate()

                btnPositive.isEnabled = false
                btnNegative.isEnabled = false
                btnPositive.text = "Saving..."

                fun saveDeposit() {
                    vm.addLedgerEntry(channelName, LEDGER_TYPE_HANDOVER, amt, trxId, dateMillis) { ok ->
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

                if (existingChannels.none { it == channelName }) {
                    vm.addProvider(channelName) { addOk ->
                        if (addOk) saveDeposit() else {
                            btnPositive.isEnabled = true
                            btnNegative.isEnabled = true
                            btnPositive.text = "Save"
                            Toast.makeText(requireContext(), "Failed to create channel", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    saveDeposit()
                }
            }
        }
        dialog.show()
    }

    private fun showEditLedgerEntryDialog(providerName: String, type: String, entry: CashLedgerEntry, account: MfsAccountSummary?) {
        val padding = dp(20)
        val kindLabel = if (type == LEDGER_TYPE_HANDOVER) "Deposit" else "Hub payment"
        val maxAllowed = if (type == LEDGER_TYPE_HUB_PAYMENT && account != null) account.balance + entry.amount else null

        val infoText = TextView(requireContext()).apply {
            text = if (maxAllowed != null) "$providerName \u00B7 $kindLabel \u00B7 max ${taka(maxAllowed)}" else "$providerName \u00B7 $kindLabel"
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
        }
        val amountInput = EditText(requireContext()).apply {
            hint = "Amount"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            typeface = Typeface.MONOSPACE
            setText(Math.round(entry.amount).toString())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        val trxIdInput = EditText(requireContext()).apply {
            hint = "Transaction ID (optional)"
            setText(entry.trxId)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }

        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(infoText)
            addView(amountInput)
            addView(trxIdInput)
        }

        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("Edit ${kindLabel.lowercase()}")
            .setView(wrapper)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val btnPositive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            val btnNegative = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
            btnPositive.setOnClickListener {
                val amt = amountInput.text.toString().toDoubleOrNull()
                val trxId = trxIdInput.text.toString().trim()
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
            layoutSelectedPaymentChannel.addView(buildEmptyRow("No channels yet -- deposit into one from the Deposit tab."))
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

        val tvChannelName       = card.findViewById<TextView>(R.id.tvChannelName)
        val tvBalance            = card.findViewById<TextView>(R.id.tvBalance)
        val tvBadge              = card.findViewById<TextView>(R.id.tvBadge)
        val btnRemoveChannel     = card.findViewById<ImageButton>(R.id.btnRemoveChannel)
        val btnPayToHub          = card.findViewById<Button>(R.id.btnPayToHub)
        val layoutPaymentHistory = card.findViewById<LinearLayout>(R.id.layoutPaymentHistory)

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

        btnPayToHub.isVisible = account.balance > 0.0
        btnPayToHub.setOnClickListener { showPayToHubDialog(account) }

        btnRemoveChannel.setOnClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Remove ${account.provider}?")
                .setMessage("This deletes its whole deposit and payment history. This can't be undone.")
                .setPositiveButton("Remove") { _, _ ->
                    vm.removeProvider(account.provider) { ok ->
                        Toast.makeText(
                            requireContext(),
                            if (ok) "Channel removed" else "Failed to remove channel",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val payments = account.hubPayments.sortedByDescending { it.timestamp }.take(6)
        if (payments.isEmpty()) {
            layoutPaymentHistory.addView(buildEmptyRow("No payments yet."))
        } else {
            payments.forEach { entry ->
                layoutPaymentHistory.addView(
                    buildEditableEntryRow(
                        title = account.provider,
                        subtitle = entryMetaLine(entry),
                        amount = entry.amount,
                        rowColor = colorRed,
                        onEdit = { showEditLedgerEntryDialog(account.provider, LEDGER_TYPE_HUB_PAYMENT, entry, account) },
                        onDelete = { confirmDeleteLedgerEntry(account.provider, LEDGER_TYPE_HUB_PAYMENT, entry) }
                    )
                )
            }
        }

        return card
    }

    private fun showPayToHubDialog(account: MfsAccountSummary) {
        val padding = dp(20)
        val infoText = TextView(requireContext()).apply {
            text = "${account.provider} \u00B7 ${taka(account.balance)} available to pay"
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
        }
        val amountInput = EditText(requireContext()).apply {
            hint = "Amount"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            typeface = Typeface.MONOSPACE
            setText(if (account.balance > 0) Math.round(account.balance).toString() else "")
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        val trxIdInput = EditText(requireContext()).apply {
            hint = "Transaction ID (optional)"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        val dateButton = styledDateButton()
        val getDate = wireDatePickerButton(dateButton, System.currentTimeMillis())

        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(infoText)
            addView(amountInput)
            addView(trxIdInput)
            addView(dateButton)
        }

        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("Pay to hub")
            .setView(wrapper)
            .setPositiveButton("Pay Now", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val btnPositive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            val btnNegative = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
            btnPositive.setOnClickListener {
                val amt = amountInput.text.toString().toDoubleOrNull()
                val trxId = trxIdInput.text.toString().trim()
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

    // A saved transaction row (deposit, hub payment, or collection): title +
    // optional subtitle on the left (both tinted rowColor so the whole row reads
    // as one color-coded unit, not just the amount), amount on the right, small
    // modern Edit/Delete icon buttons at the end.
    private fun buildEditableEntryRow(title: String, subtitle: String?, amount: Double, rowColor: Int, onEdit: () -> Unit, onDelete: () -> Unit): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(8), dp(6), dp(4), dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        val textColumn = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(requireContext()).apply {
            text = title
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(rowColor)
        })
        if (subtitle != null) {
            textColumn.addView(TextView(requireContext()).apply {
                text = subtitle
                textSize = 11f
                setTextColor(rowColor)
            })
        }
        row.addView(textColumn)
        row.addView(TextView(requireContext()).apply {
            text = taka(amount)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(rowColor)
        })
        row.addView(ImageButton(requireContext()).apply {
            setImageResource(R.drawable.ic_edit_modern)
            background = null
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(6) }
            contentDescription = "Edit"
            setOnClickListener { onEdit() }
        })
        row.addView(ImageButton(requireContext()).apply {
            setImageResource(R.drawable.ic_delete_modern)
            background = null
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(2) }
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
