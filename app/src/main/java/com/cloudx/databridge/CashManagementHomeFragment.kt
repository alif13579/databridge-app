package com.cloudx.databridge

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Home/dashboard entry point for Cash Management: hero "need to pay" card,
 * 4-step Collection -> Deposit -> Ready to Pay -> Paid to Hub flow, wallet
 * balances, recent activity, and quick actions (Add Collection / Deposit /
 * Pay to Hub) via a speed-dial FAB.
 *
 * "View all" opens a Collections/Deposits/Payments picker leading into
 * CashLedgerListFragment; "Manage" opens ManageWalletsFragment.
 */
class CashManagementHomeFragment : Fragment() {

    private val vm: CashManagementViewModel by viewModels()

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var pbLoading: ProgressBar
    private lateinit var layoutError: View
    private lateinit var tvError: TextView
    private lateinit var btnRetry: Button
    private lateinit var layoutContent: View
    private lateinit var tvHomeBranchName: TextView
    private lateinit var tvHomeNeedToPay: TextView
    private lateinit var tvHomeAgainstCollection: TextView
    private lateinit var tvHomeDateRange: TextView
    private lateinit var statCollected: View
    private lateinit var statCashInHand: View
    private lateinit var statMfsBalance: View
    private lateinit var statPaidToHub: View
    private lateinit var layoutStepFlow: LinearLayout
    private lateinit var tvManageWallets: TextView
    private lateinit var layoutWalletCards: LinearLayout
    private lateinit var tvViewAllActivity: TextView
    private lateinit var layoutRecentActivity: LinearLayout
    private lateinit var btnFab: Button
    private lateinit var layoutSpeedDial: View
    private lateinit var layoutSpeedDialItems: LinearLayout

    private var branchId: String = ""
    private val db = FirebaseDatabase.getInstance()
    private var branchNames: Map<String, String> = emptyMap()
    private val dateFmt = SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault())
    private val rangeLabelFmt = SimpleDateFormat("dd MMM", Locale.getDefault())
    private var lastSuccessState: CashManagementState.Success? = null
    private var selectedDateRange: Pair<Long, Long>? = null

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        fun newInstance(branchId: String): CashManagementHomeFragment {
            val f = CashManagementHomeFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_cash_management_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        swipeRefresh            = view.findViewById(R.id.swipeRefreshHome)
        pbLoading                = view.findViewById(R.id.pbHomeLoading)
        layoutError              = view.findViewById(R.id.layoutHomeError)
        tvError                  = view.findViewById(R.id.tvHomeError)
        btnRetry                 = view.findViewById(R.id.btnHomeRetry)
        layoutContent            = view.findViewById(R.id.layoutHomeContent)
        tvHomeBranchName         = view.findViewById(R.id.tvHomeBranchName)
        tvHomeNeedToPay          = view.findViewById(R.id.tvHomeNeedToPay)
        tvHomeAgainstCollection  = view.findViewById(R.id.tvHomeAgainstCollection)
        tvHomeDateRange          = view.findViewById(R.id.tvHomeDateRange)
        statCollected            = view.findViewById(R.id.statCollected)
        statCashInHand           = view.findViewById(R.id.statCashInHand)
        statMfsBalance           = view.findViewById(R.id.statMfsBalance)
        statPaidToHub            = view.findViewById(R.id.statPaidToHub)
        layoutStepFlow           = view.findViewById(R.id.layoutStepFlow)
        tvManageWallets          = view.findViewById(R.id.tvManageWallets)
        layoutWalletCards        = view.findViewById(R.id.layoutWalletCards)
        tvViewAllActivity        = view.findViewById(R.id.tvViewAllActivity)
        layoutRecentActivity     = view.findViewById(R.id.layoutRecentActivity)
        btnFab                   = view.findViewById(R.id.btnHomeFab)
        layoutSpeedDial          = view.findViewById(R.id.layoutSpeedDial)
        layoutSpeedDialItems     = view.findViewById(R.id.layoutSpeedDialItems)

        swipeRefresh.setOnRefreshListener { vm.refresh() }
        btnRetry.setOnClickListener { vm.load(branchId) }
        tvHomeDateRange.setOnClickListener { showDateRangePicker() }

        btnFab.setOnClickListener { toggleSpeedDial() }
        layoutSpeedDial.setOnClickListener { toggleSpeedDial() }
        tvManageWallets.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, ManageWalletsFragment.newInstance(branchId))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        tvViewAllActivity.setOnClickListener { showViewAllPicker() }

        setupBranchSwitcher()

        vm.state.observe(viewLifecycleOwner) { state -> render(state) }

        if (branchId.isBlank()) {
            render(CashManagementState.Error("No branch assigned to this account."))
        } else {
            vm.load(branchId)
        }
    }

    private fun showViewAllPicker() {
        val options = arrayOf("Collections", "Deposits", "Payments")
        val modes = arrayOf(CashListMode.COLLECTIONS, CashListMode.DEPOSITS, CashListMode.PAYMENTS)
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("View all")
            .setItems(options) { _, index -> openLedgerList(modes[index]) }
            .show()
    }

    private fun openLedgerList(mode: CashListMode) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, CashLedgerListFragment.newInstance(branchId, mode))
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }

    // ── Branch switcher ──────────────────────────────────────────────────────────
    // Lets a user with more than one assigned branch (RbacManager.current.branchIds)
    // pick which branch's Cash Management data to view. Hidden entirely for the
    // common single-branch case. Starts on branchIds.firstOrNull(), same branch
    // MainActivity already passed into newInstance(); switching here just reloads
    // this screen against the new branchId, which also carries into any downstream
    // screen opened afterwards (View all, Manage Wallets, Pay to Hub) since they
    // all read this fragment's branchId field at click time.
    private fun setupBranchSwitcher() {
        val branchIds = RbacManager.current.branchIds
        if (branchIds.size <= 1) return

        val arrow = ContextCompat.getDrawable(requireContext(), R.drawable.ic_arrow_drop_down_white)?.mutate()
        arrow?.setTint(Color.parseColor("#0F172A"))
        tvHomeBranchName.setCompoundDrawablesWithIntrinsicBounds(null, null, arrow, null)
        tvHomeBranchName.isVisible = true
        tvHomeBranchName.setOnClickListener { showBranchPicker(branchIds) }

        // Seed with the primary branch's name, already resolved by RbacManager at
        // login, so there's no flash of a raw branch id while the rest load below.
        val primaryId = branchIds.first()
        if (RbacManager.current.branchName.isNotBlank()) {
            branchNames = mapOf(primaryId to RbacManager.current.branchName)
        }
        tvHomeBranchName.text = branchNames[branchId] ?: "Branch"

        viewLifecycleOwner.lifecycleScope.launch {
            val resolved = coroutineScope {
                branchIds.filter { it !in branchNames }.associateWith { id ->
                    async {
                        runCatching {
                            db.reference.child("branches/$id/name").get().await().getValue(String::class.java)
                        }.getOrNull()
                    }
                }.mapValues { (id, deferred) -> deferred.await()?.takeIf { it.isNotBlank() } ?: id }
            }
            branchNames = branchNames + resolved
            tvHomeBranchName.text = branchNames[branchId] ?: branchId
        }
    }

    private fun showBranchPicker(branchIds: List<String>) {
        val labels = branchIds.map { branchNames[it] ?: it }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Switch branch")
            .setItems(labels) { _, index ->
                val newBranchId = branchIds[index]
                if (newBranchId != branchId) {
                    branchId = newBranchId
                    tvHomeBranchName.text = branchNames[branchId] ?: branchId
                    vm.load(branchId)
                }
            }
            .show()
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
                lastSuccessState = state
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

        tvHomeNeedToPay.text = taka(summary.toBePaid)
        tvHomeAgainstCollection.text = "against ${taka(summary.totalCollection)} total collection"
        tvHomeDateRange.text = if (range == null) "All time" else "${rangeLabelFmt.format(Date(range.first))} \u2013 ${rangeLabelFmt.format(Date(range.second))}"

        bindStatRow(statCollected, "#DBEAFE", "#1D4ED8", "\u09F3", "Collected", taka(summary.totalCollection))
        bindStatRow(statCashInHand, "#CFFAFE", "#0E7490", "\u09F3", "Cash in Hand", taka(summary.cashInHand))
        bindStatRow(statMfsBalance, "#EDE9FE", "#6D28D9", "\u21C4", "MFS Balance", taka(summary.totalMfsBalance))
        bindStatRow(statPaidToHub, "#D1FAE5", "#047857", "\u2713", "Paid to Hub", taka(summary.totalHubPayment))

        buildStepFlow(summary, totalHandover)
        buildWalletCards(state.accounts)
        buildRecentActivity(state)
    }

    private fun bindStatRow(row: View, bg: String, fg: String, glyph: String, label: String, value: String) {
        val tvDot = row.findViewById<TextView>(R.id.tvStatIconDot)
        val tvLabel = row.findViewById<TextView>(R.id.tvStatLabel)
        val tvValue = row.findViewById<TextView>(R.id.tvStatValue)
        tvDot.text = glyph
        tvDot.setTextColor(android.graphics.Color.parseColor(fg))
        tvDot.background = roundedDrawable(bg, dp(6))
        tvLabel.text = label
        tvValue.text = value
    }

    private fun roundedDrawable(hexColor: String, radiusPx: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor(hexColor))
            cornerRadius = radiusPx.toFloat()
        }
    }

    private fun buildStepFlow(summary: CashManagementSummary, totalHandover: Double) {
        layoutStepFlow.removeAllViews()
        layoutStepFlow.addView(buildStepItem(1, "#2563EB", "Collection", taka(summary.totalCollection)))
        layoutStepFlow.addView(buildStepArrow())
        layoutStepFlow.addView(buildStepItem(2, "#EA580C", "Deposit", taka(totalHandover)))
        layoutStepFlow.addView(buildStepArrow())
        layoutStepFlow.addView(buildStepItem(3, "#7C3AED", "Ready to Pay", taka(summary.toBePaid)))
        layoutStepFlow.addView(buildStepArrow())
        layoutStepFlow.addView(buildStepItem(4, "#059669", "Paid to Hub", taka(summary.totalHubPayment)))
    }

    private fun buildStepItem(n: Int, colorHex: String, label: String, value: String): View {
        val col = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(requireContext()).apply {
            text = n.toString()
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor(colorHex))
            }
        })
        col.addView(TextView(requireContext()).apply {
            text = label
            textSize = 9f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) }
        })
        col.addView(TextView(requireContext()).apply {
            text = value
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
        })
        return col
    }

    private fun buildStepArrow(): View {
        return TextView(requireContext()).apply {
            text = "\u203A"
            textSize = 16f
            setTextColor(0xFFCBD5E1.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
    }

    private fun channelColor(name: String): Pair<String, String> = when (name.lowercase()) {
        "rocket" -> "#EDE9FE" to "#6D28D9"
        "bkash" -> "#FCE7F3" to "#BE185D"
        "nagad" -> "#FFEDD5" to "#C2410C"
        "upay" -> "#FEF3C7" to "#B45309"
        else -> "#F1F5F9" to "#475569"
    }

    private fun buildWalletCards(accounts: List<MfsAccountSummary>) {
        layoutWalletCards.removeAllViews()
        if (accounts.isEmpty()) {
            layoutWalletCards.addView(TextView(requireContext()).apply {
                text = "No wallets yet -- add one from the speed dial."
                textSize = 12f
                setTextColor(0xFF94A3B8.toInt())
                setPadding(dp(4), dp(10), dp(4), dp(10))
            })
            return
        }
        accounts.sortedByDescending { it.balance }.forEach { account ->
            val name = account.provider
            val (bg, fg) = channelColor(name)
            val card = layoutInflater.inflate(R.layout.item_wallet_card, layoutWalletCards, false)
            val tvIcon = card.findViewById<TextView>(R.id.tvWalletIcon)
            val tvName = card.findViewById<TextView>(R.id.tvWalletName)
            val tvBalance = card.findViewById<TextView>(R.id.tvWalletBalance)
            tvIcon.text = name.take(1).uppercase()
            tvIcon.setTextColor(android.graphics.Color.parseColor(fg))
            tvIcon.background = roundedDrawable(bg, dp(17))
            tvName.text = name
            tvBalance.text = taka(account.balance)
            card.setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, ManageWalletsFragment.newInstance(branchId))
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }
            layoutWalletCards.addView(card)
        }
    }

    private fun buildRecentActivity(state: CashManagementState.Success) {
        layoutRecentActivity.removeAllViews()
        val items = mutableListOf<Triple<String, Long, Pair<String, Double>>>()
        state.collections.forEach { items.add(Triple("Collection", it.timestamp, "#DBEAFE" to it.amount)) }
        state.accounts.forEach { acc ->
            acc.handovers.forEach { items.add(Triple("Deposit \u00B7 ${acc.provider}", it.timestamp, "#FFEDD5" to it.amount)) }
            acc.hubPayments.forEach { items.add(Triple("Payment \u00B7 ${acc.provider}", it.timestamp, "#D1FAE5" to it.amount)) }
        }
        val recent = items.sortedByDescending { it.second }.take(5)
        if (recent.isEmpty()) {
            layoutRecentActivity.addView(TextView(requireContext()).apply {
                text = "No activity yet."
                textSize = 13f
                setTextColor(0xFF94A3B8.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(20), dp(8), dp(20))
            })
            return
        }
        recent.forEachIndexed { index, (label, ts, amountInfo) ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            row.addView(TextView(requireContext()).apply {
                text = label.take(1)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(0xFF334155.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                background = roundedDrawable(amountInfo.first, dp(10))
            })
            val textCol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) }
            }
            textCol.addView(TextView(requireContext()).apply {
                text = label
                textSize = 13f
                setTextColor(0xFF1E293B.toInt())
            })
            textCol.addView(TextView(requireContext()).apply {
                text = dateFmt.format(Date(ts))
                textSize = 11f
                setTextColor(0xFF94A3B8.toInt())
            })
            row.addView(textCol)
            row.addView(TextView(requireContext()).apply {
                text = taka(amountInfo.second)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                typeface = Typeface.MONOSPACE
                setTextColor(0xFF1E293B.toInt())
            })
            layoutRecentActivity.addView(row)
            if (index != recent.lastIndex) {
                layoutRecentActivity.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    setBackgroundColor(0xFFF1F5F9.toInt())
                })
            }
        }
    }

    // ── Date range (same behavior as the old summary card) ──────────────────────

    private fun showDateRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select date range")
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val start = selection.first ?: return@addOnPositiveButtonClickListener
            val end = selection.second ?: start
            val endOfDay = end + 24 * 60 * 60 * 1000L - 1
            selectedDateRange = start to endOfDay
            lastSuccessState?.let { showSuccess(it) }
        }
        picker.show(childFragmentManager, "cash_home_date_range_picker")
    }

    // ── Speed dial ───────────────────────────────────────────────────────────────

    private fun toggleSpeedDial() {
        if (layoutSpeedDial.isVisible) closeSpeedDial() else openSpeedDial()
    }

    private fun openSpeedDial() {
        layoutSpeedDialItems.removeAllViews()
        layoutSpeedDialItems.addView(buildSpeedDialItem("Add Collection") { closeSpeedDial(); showAddCollectionDialog() })
        layoutSpeedDialItems.addView(buildSpeedDialItem("Deposit") { closeSpeedDial(); showDepositDialog() })
        layoutSpeedDialItems.addView(buildSpeedDialItem("Pay to Hub") { closeSpeedDial(); startPayToHubFlow() })
        layoutSpeedDial.isVisible = true
        btnFab.text = "\u2715"
    }

    private fun closeSpeedDial() {
        layoutSpeedDial.isVisible = false
        btnFab.text = "+"
    }

    private fun buildSpeedDialItem(label: String, onClick: () -> Unit): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = roundedDrawable("#FFFFFF", dp(24))
            elevation = dp(4).toFloat()
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(requireContext()).apply {
            text = label
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF334155.toInt())
        })
        return row
    }

    // ── Single-date picker helper ────────────────────────────────────────────────

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
            picker.show(childFragmentManager, "cash_home_single_date_picker")
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

    // ── Add Collection popup ──────────────────────────────────────────────────

    private fun showAddCollectionDialog() {
        val padding = dp(20)

        val amountInput = EditText(requireContext()).apply {
            hint = "Amount"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            typeface = Typeface.MONOSPACE
        }

        val remarksInput = EditText(requireContext()).apply {
            hint = "Remarks (optional)"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        val dateButton = styledDateButton()
        val getDate = wireDatePickerButton(dateButton, System.currentTimeMillis())

        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(amountInput)
            addView(remarksInput)
            addView(dateButton)
        }

        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("Add Collection")
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
                vm.addCollection(amt, COLLECTION_TYPE_CASH, remarksInput.text.toString(), getDate()) { ok ->
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

    // ── Deposit popup ────────────────────────────────────────────────────────────

    private fun showDepositDialog() {
        val padding = dp(20)
        val existingChannels = lastSuccessState?.accounts?.map { it.provider }.orEmpty()
        val channelOptions = listOf("Select channel") + (listOf("Rocket", "bKash") + existingChannels).distinct() + listOf("Other")

        val spinner = android.widget.Spinner(requireContext())
        val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, channelOptions)
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

        val amountInput = EditText(requireContext()).apply {
            hint = "Amount"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        val trxIdInput = EditText(requireContext()).apply {
            hint = "Transaction ID (optional)"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        val remarksInput = EditText(requireContext()).apply {
            hint = "Remarks (optional)"
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
            addView(remarksInput)
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
                val remarks = remarksInput.text.toString()
                val dateMillis = getDate()

                btnPositive.isEnabled = false
                btnNegative.isEnabled = false
                btnPositive.text = "Saving..."

                fun saveDeposit() {
                    vm.addLedgerEntry(channelName, LEDGER_TYPE_HANDOVER, amt, trxId, remarks, dateMillis) { ok ->
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

    // ── Pay to Hub: pick a wallet with balance, then the amount form ────────────
    // Full two-screen flow lands in a later phase; this is a minimal bridge so the
    // button/speed-dial entry works today.

    private fun startPayToHubFlow() {
        val accountsWithBalance = lastSuccessState?.accounts?.filter { it.balance > 0.0 }.orEmpty()
        if (accountsWithBalance.isEmpty()) {
            Toast.makeText(requireContext(), "No channel has a balance to pay out yet", Toast.LENGTH_SHORT).show()
            return
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, PayToHubSelectWalletFragment.newInstance(branchId))
            .addToBackStack(BACK_STACK_PAY_TO_HUB_FLOW)
            .commitAllowingStateLoss()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
