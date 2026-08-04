package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.datepicker.MaterialDatePicker
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CashListMode { COLLECTIONS, DEPOSITS, PAYMENTS }

/**
 * Shared "View all" screen for Collections / Deposits / Payments: search,
 * Date (+ Channel, for Deposits/Payments) filters, and simple client-side
 * pagination -- all of it operating on data the ViewModel already loaded in
 * one shot, matching the rest of this feature's "small dataset" assumption.
 */
class CashLedgerListFragment : Fragment() {

    private val vm: CashManagementViewModel by viewModels()

    private lateinit var btnBack: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var etSearch: EditText
    private lateinit var tvFilterDate: TextView
    private lateinit var tvFilterChannel: TextView
    private lateinit var tvFilterStatus: TextView
    private lateinit var pbLoading: ProgressBar
    private lateinit var layoutItems: LinearLayout
    private lateinit var tvPaginationInfo: TextView
    private lateinit var btnPagePrev: ImageButton
    private lateinit var btnPageNext: ImageButton

    private var branchId: String = ""
    private lateinit var mode: CashListMode
    private val dateFmt = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault())
    private val rangeLabelFmt = SimpleDateFormat("dd MMM", Locale.getDefault())

    private var searchQuery = ""
    private var dateFilter: Pair<Long, Long>? = null
    private var channelFilter: String? = null
    private var currentPage = 0
    private val pageSize = 5
    private var lastSuccessState: CashManagementState.Success? = null

    private data class Row(val timestamp: Long, val amount: Double, val channel: String?, val subDetail: String)

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val ARG_MODE = "mode"
        fun newInstance(branchId: String, mode: CashListMode): CashLedgerListFragment {
            val f = CashLedgerListFragment()
            f.arguments = Bundle().apply {
                putString(ARG_BRANCH_ID, branchId)
                putString(ARG_MODE, mode.name)
            }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_cash_ledger_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()
        mode = CashListMode.valueOf(arguments?.getString(ARG_MODE) ?: CashListMode.COLLECTIONS.name)

        btnBack           = view.findViewById(R.id.btnListBack)
        tvTitle           = view.findViewById(R.id.tvListTitle)
        etSearch          = view.findViewById(R.id.etListSearch)
        tvFilterDate      = view.findViewById(R.id.tvFilterDate)
        tvFilterChannel   = view.findViewById(R.id.tvFilterChannel)
        tvFilterStatus    = view.findViewById(R.id.tvFilterStatus)
        pbLoading         = view.findViewById(R.id.pbListLoading)
        layoutItems       = view.findViewById(R.id.layoutListItems)
        tvPaginationInfo  = view.findViewById(R.id.tvPaginationInfo)
        btnPagePrev       = view.findViewById(R.id.btnPagePrev)
        btnPageNext       = view.findViewById(R.id.btnPageNext)

        tvTitle.text = when (mode) {
            CashListMode.COLLECTIONS -> "Collections"
            CashListMode.DEPOSITS -> "Deposits"
            CashListMode.PAYMENTS -> "Payments"
        }
        tvFilterChannel.isVisible = mode != CashListMode.COLLECTIONS

        btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        etSearch.onSearchTextChanged {
            searchQuery = it
            currentPage = 0
            lastSuccessState?.let { s -> renderList(s) }
        }

        tvFilterDate.setOnClickListener { showDateFilterMenu() }
        tvFilterChannel.setOnClickListener { showChannelFilterMenu() }
        tvFilterStatus.setOnClickListener {
            // Every saved entry is inherently "completed" in this data model --
            // there's no pending/failed state yet, so this is a label for now.
            tvFilterStatus.text = "Status: All"
        }

        btnPagePrev.setOnClickListener {
            if (currentPage > 0) { currentPage--; lastSuccessState?.let { renderList(it) } }
        }
        btnPageNext.setOnClickListener {
            currentPage++; lastSuccessState?.let { renderList(it) }
        }

        vm.state.observe(viewLifecycleOwner) { state -> render(state) }

        if (branchId.isBlank()) {
            render(CashManagementState.Error("No branch assigned to this account."))
        } else {
            vm.load(branchId)
        }
    }

    private fun render(state: CashManagementState) {
        when (state) {
            is CashManagementState.Loading -> {
                pbLoading.isVisible = true
                layoutItems.removeAllViews()
            }
            is CashManagementState.Error -> {
                pbLoading.isVisible = false
                layoutItems.removeAllViews()
                layoutItems.addView(emptyText(state.message))
            }
            is CashManagementState.Success -> {
                pbLoading.isVisible = false
                lastSuccessState = state
                renderList(state)
            }
        }
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        val sign = if (whole < 0) "\u2212" else ""
        val formatted = NumberFormat.getNumberInstance(Locale.US).format(Math.abs(whole))
        return "$sign\u09F3$formatted"
    }

    private fun allRows(state: CashManagementState.Success): List<Row> = when (mode) {
        CashListMode.COLLECTIONS -> state.collections.map {
            Row(it.timestamp, it.amount, null, "Collected by ${it.enteredByName.ifBlank { "someone" }}")
        }
        CashListMode.DEPOSITS -> state.accounts.flatMap { acc ->
            acc.handovers.map { Row(it.timestamp, it.amount, acc.provider, if (it.trxId.isBlank()) "TRX: \u2014" else "TRX: ${it.trxId}") }
        }
        CashListMode.PAYMENTS -> state.accounts.flatMap { acc ->
            acc.hubPayments.map { Row(it.timestamp, it.amount, acc.provider, if (it.trxId.isBlank()) "TRX: \u2014" else "TRX: ${it.trxId}") }
        }
    }

    private fun renderList(state: CashManagementState.Success) {
        var rows = allRows(state).sortedByDescending { it.timestamp }

        dateFilter?.let { range -> rows = rows.filter { it.timestamp in range.first..range.second } }
        channelFilter?.let { ch -> rows = rows.filter { it.channel == ch } }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            rows = rows.filter { it.subDetail.lowercase().contains(q) || (it.channel?.lowercase()?.contains(q) == true) }
        }

        val total = rows.size
        val maxPage = if (total == 0) 0 else (total - 1) / pageSize
        if (currentPage > maxPage) currentPage = maxPage
        val start = currentPage * pageSize
        val end = minOf(start + pageSize, total)
        val pageRows = if (total == 0) emptyList() else rows.subList(start, end)

        layoutItems.removeAllViews()
        if (pageRows.isEmpty()) {
            layoutItems.addView(emptyText("No entries found."))
        } else {
            pageRows.forEach { layoutItems.addView(buildRow(it)) }
        }

        tvPaginationInfo.text = if (total == 0) "Showing 0 of 0" else "Showing ${start + 1} to $end of $total"
        btnPagePrev.isEnabled = currentPage > 0
        btnPagePrev.alpha = if (currentPage > 0) 1f else 0.35f
        btnPageNext.isEnabled = end < total
        btnPageNext.alpha = if (end < total) 1f else 0.35f
    }

    private fun buildRow(row: Row): View {
        val card = layoutInflater.inflate(R.layout.item_cash_list_row, layoutItems, false)
        card.findViewById<TextView>(R.id.tvRowDate).text = dateFmt.format(Date(row.timestamp))
        card.findViewById<TextView>(R.id.tvRowAmount).text = taka(row.amount)
        card.findViewById<TextView>(R.id.tvRowSubDetail).text = row.subDetail
        val channelBadge = card.findViewById<TextView>(R.id.tvRowChannelBadge)
        if (row.channel != null) {
            channelBadge.isVisible = true
            channelBadge.text = row.channel
        } else {
            channelBadge.isVisible = false
        }
        return card
    }

    private fun emptyText(message: String): View {
        return TextView(requireContext()).apply {
            text = message
            textSize = 13f
            setTextColor(0xFF94A3B8.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(dp(8), dp(40), dp(8), dp(40))
        }
    }

    // ── Filters ──────────────────────────────────────────────────────────────────

    private fun showDateFilterMenu() {
        val options = arrayOf("All", "Today", "This Week", "This Month", "Custom range")
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Filter by date")
            .setItems(options) { _, index ->
                val now = System.currentTimeMillis()
                val cal = java.util.Calendar.getInstance()
                when (index) {
                    0 -> { dateFilter = null; tvFilterDate.text = "Date: All" }
                    1 -> {
                        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0)
                        dateFilter = cal.timeInMillis to now
                        tvFilterDate.text = "Date: Today"
                    }
                    2 -> {
                        cal.add(java.util.Calendar.DAY_OF_YEAR, -7)
                        dateFilter = cal.timeInMillis to now
                        tvFilterDate.text = "Date: 7 days"
                    }
                    3 -> {
                        cal.add(java.util.Calendar.DAY_OF_YEAR, -30)
                        dateFilter = cal.timeInMillis to now
                        tvFilterDate.text = "Date: 30 days"
                    }
                    4 -> {
                        val picker = MaterialDatePicker.Builder.dateRangePicker().setTitleText("Select date range").build()
                        picker.addOnPositiveButtonClickListener { selection ->
                            val start = selection.first ?: return@addOnPositiveButtonClickListener
                            val end = (selection.second ?: start) + 24 * 60 * 60 * 1000L - 1
                            dateFilter = start to end
                            tvFilterDate.text = "${rangeLabelFmt.format(Date(start))}\u2013${rangeLabelFmt.format(Date(end))}"
                            currentPage = 0
                            lastSuccessState?.let { renderList(it) }
                        }
                        picker.show(childFragmentManager, "cash_list_date_range_picker")
                        return@setItems
                    }
                }
                currentPage = 0
                lastSuccessState?.let { renderList(it) }
            }
            .show()
    }

    private fun showChannelFilterMenu() {
        val channels = lastSuccessState?.accounts?.map { it.provider }.orEmpty()
        val options = (listOf("All") + channels).toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Filter by channel")
            .setItems(options) { _, index ->
                channelFilter = if (index == 0) null else options[index]
                tvFilterChannel.text = "Channel: ${options[index]}"
                currentPage = 0
                lastSuccessState?.let { renderList(it) }
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private fun EditText.onSearchTextChanged(afterChanged: (String) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { afterChanged(s?.toString().orEmpty()) }
    })
}
