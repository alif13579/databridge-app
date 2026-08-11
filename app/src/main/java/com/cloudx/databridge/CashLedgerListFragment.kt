package com.cloudx.databridge

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.datepicker.MaterialDatePicker
import java.io.File
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
    private lateinit var btnExportCsv: ImageButton
    private lateinit var layoutTabs: LinearLayout
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

    private data class Row(
        val id: String,
        val timestamp: Long,
        val amount: Double,
        val channel: String?,
        val subDetail: String,
        val remarks: String,
        val trxId: String,
        val isEdited: Boolean,
        val enteredByName: String,
    )

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
        btnExportCsv      = view.findViewById(R.id.btnExportCsv)
        layoutTabs        = view.findViewById(R.id.layoutCashModeTabs)
        etSearch          = view.findViewById(R.id.etListSearch)
        tvFilterDate      = view.findViewById(R.id.tvFilterDate)
        tvFilterChannel   = view.findViewById(R.id.tvFilterChannel)
        tvFilterStatus    = view.findViewById(R.id.tvFilterStatus)
        pbLoading         = view.findViewById(R.id.pbListLoading)
        layoutItems       = view.findViewById(R.id.layoutListItems)
        tvPaginationInfo  = view.findViewById(R.id.tvPaginationInfo)
        btnPagePrev       = view.findViewById(R.id.btnPagePrev)
        btnPageNext       = view.findViewById(R.id.btnPageNext)

        applyModeChrome()
        buildTabs()

        btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        btnExportCsv.setOnClickListener { exportData() }

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

    // ── Mode tabs: Collections / Deposits / Payments, in one page ────────────────
    // Was a popup that opened a separate single-mode screen per pick; now this one
    // screen owns all three and switching is just a tab tap + re-render, since the
    // ViewModel already loaded every mode's data together (see allRows()/render()).
    private fun modeLabel(m: CashListMode): String = when (m) {
        CashListMode.COLLECTIONS -> "Collections"
        CashListMode.DEPOSITS -> "Deposits"
        CashListMode.PAYMENTS -> "Payments"
    }

    private fun applyModeChrome() {
        tvTitle.text = modeLabel(mode)
        tvFilterChannel.isVisible = mode != CashListMode.COLLECTIONS
    }

    private fun buildTabs() {
        layoutTabs.removeAllViews()
        CashListMode.entries.forEach { m ->
            val tab = layoutInflater.inflate(R.layout.item_petty_cash_filter_tab, layoutTabs, false) as TextView
            tab.text = modeLabel(m)
            tab.setOnClickListener { switchMode(m) }
            styleTab(tab, m == mode)
            layoutTabs.addView(tab)
        }
    }

    private fun styleTab(tab: TextView, active: Boolean) {
        if (active) {
            tab.setTextColor(Color.parseColor("#0099b8"))
            tab.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_cash_tab_active)
        } else {
            tab.setTextColor(Color.parseColor("#64748B"))
            tab.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_cash_tab_inactive)
        }
    }

    private fun switchMode(newMode: CashListMode) {
        if (newMode == mode) return
        mode = newMode
        applyModeChrome()
        // Search/channel are mode-specific (a TRX search or bKash filter from
        // Deposits doesn't carry meaning on Collections), so those reset. Date
        // range stays -- picking "This Month" once and checking all three tabs
        // against it is the point of combining them into one page.
        searchQuery = ""
        etSearch.setText("")
        channelFilter = null
        tvFilterChannel.text = "Channel: All"
        currentPage = 0
        buildTabs()
        lastSuccessState?.let { renderList(it) }
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
            Row(it.id, it.timestamp, it.amount, null, "Collected by ${it.enteredByName.ifBlank { "someone" }}", it.remarks, "", it.isEdited, it.enteredByName)
        }
        CashListMode.DEPOSITS -> state.accounts.flatMap { acc ->
            acc.handovers.map {
                Row(it.id, it.timestamp, it.amount, acc.provider, if (it.trxId.isBlank()) "TRX: \u2014" else "TRX: ${it.trxId}", it.remarks, it.trxId, it.isEdited, it.enteredByName)
            }
        }
        CashListMode.PAYMENTS -> state.accounts.flatMap { acc ->
            acc.hubPayments.map {
                Row(it.id, it.timestamp, it.amount, acc.provider, if (it.trxId.isBlank()) "TRX: \u2014" else "TRX: ${it.trxId}", it.remarks, it.trxId, it.isEdited, it.enteredByName)
            }
        }
    }

    private fun filteredRows(state: CashManagementState.Success): List<Row> {
        var rows = allRows(state).sortedByDescending { it.timestamp }
        dateFilter?.let { range -> rows = rows.filter { it.timestamp in range.first..range.second } }
        channelFilter?.let { ch -> rows = rows.filter { it.channel == ch } }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            rows = rows.filter { it.subDetail.lowercase().contains(q) || (it.channel?.lowercase()?.contains(q) == true) }
        }
        return rows
    }

    private fun renderList(state: CashManagementState.Success) {
        val rows = filteredRows(state)

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
        card.findViewById<TextView>(R.id.tvRowEditedBadge).isVisible = row.isEdited
        val channelBadge = card.findViewById<TextView>(R.id.tvRowChannelBadge)
        if (row.channel != null) {
            channelBadge.isVisible = true
            channelBadge.text = row.channel
        } else {
            channelBadge.isVisible = false
        }

        card.findViewById<ImageButton>(R.id.btnRowMenu).setOnClickListener { anchor ->
            val popup = PopupMenu(anchor.context, anchor)
            popup.menu.add(0, 1, 0, "Edit")
            popup.menu.add(0, 2, 1, "Delete")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { showEditDialog(row); true }
                    2 -> { confirmDelete(row); true }
                    else -> false
                }
            }
            popup.show()
        }
        card.setOnLongClickListener { showHistoryDialog(row); true }

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

    // ── CSV export ──────────────────────────────────────────────────────────────
    // Exports whatever is currently on screen -- same date/channel/search filters
    // renderList() applies -- so "export by date range" is just "set the date
    // filter, then export", reusing the filter UI that already exists above.

    private enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
        CSV("csv", "text/csv", "CSV"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel (.xlsx)"),
        PDF("pdf", "application/pdf", "PDF"),
    }

    private fun exportData() {
        val formats = ExportFormat.entries.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Export format")
            .setItems(formats.map { it.label }.toTypedArray()) { _, i -> showShareOrDownloadChooser(formats[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showShareOrDownloadChooser(format: ExportFormat) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("${format.label} Export")
            .setItems(arrayOf("📤 Share করুন", "⬇️ Download করুন")) { _, which ->
                if (which == 0) exportAndShare(format) else exportAndDownload(format)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Builds CSV for the currently filtered rows (all pages, not just the visible one). Null if empty. */
    private fun buildCsvContent(): Pair<String, Int>? {
        val state = lastSuccessState ?: return null
        val rows = filteredRows(state)
        if (rows.isEmpty()) return null

        val csvDateFmt = SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.getDefault())
        fun esc(s: String) = "\"${s.replace("\"", "\"\"")}\""

        val csv = StringBuilder()
        if (mode == CashListMode.COLLECTIONS) {
            csv.append("Date,Amount,Collected By,Remarks\n")
            rows.forEach {
                csv.append("${csvDateFmt.format(Date(it.timestamp))},${amountForCsv(it.amount)},${esc(it.enteredByName)},${esc(it.remarks)}\n")
            }
        } else {
            csv.append("Date,Amount,Channel,Transaction ID,Entered By,Remarks\n")
            rows.forEach {
                csv.append("${csvDateFmt.format(Date(it.timestamp))},${amountForCsv(it.amount)},${esc(it.channel.orEmpty())},${esc(it.trxId)},${esc(it.enteredByName)},${esc(it.remarks)}\n")
            }
        }
        return csv.toString() to rows.size
    }

    private fun amountForCsv(amount: Double): String =
        if (amount == Math.floor(amount)) amount.toLong().toString() else String.format(Locale.US, "%.2f", amount)

    private val exportDateFmt = SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.getDefault())

    /** Same columns as buildCsvContent(), just as generic (headers, rows) for the Excel/PDF writer. */
    private fun tableData(rows: List<Row>): Pair<List<String>, List<List<Any>>> {
        return if (mode == CashListMode.COLLECTIONS) {
            val headers = listOf("Date", "Amount", "Collected By", "Remarks")
            val table = rows.map { listOf<Any>(exportDateFmt.format(Date(it.timestamp)), it.amount, it.enteredByName, it.remarks) }
            headers to table
        } else {
            val headers = listOf("Date", "Amount", "Channel", "Transaction ID", "Entered By", "Remarks")
            val table = rows.map {
                listOf<Any>(exportDateFmt.format(Date(it.timestamp)), it.amount, it.channel.orEmpty(), it.trxId, it.enteredByName, it.remarks)
            }
            headers to table
        }
    }

    private fun xlsxColWidths(): List<Int> =
        if (mode == CashListMode.COLLECTIONS) listOf(20, 13, 18, 34) else listOf(20, 12, 11, 16, 16, 34)

    private fun pdfColWeights(): List<Float> =
        if (mode == CashListMode.COLLECTIONS) listOf(2.2f, 1.1f, 1.8f, 3.2f) else listOf(2.0f, 1.0f, 1.0f, 1.5f, 1.5f, 2.8f)

    private fun pdfSubtitle(): String {
        val range = dateFilter?.let { "${exportDateFmt.format(Date(it.first))} \u2013 ${exportDateFmt.format(Date(it.second))}" } ?: "All time"
        return "Range: $range  |  Generated: ${exportDateFmt.format(Date())}"
    }

    private fun exportFileName(extension: String): String {
        val label = mode.name.lowercase().replaceFirstChar { it.uppercase() }
        return "DataBridge_Cash_${label}_${System.currentTimeMillis()}.$extension"
    }

    /** Writes the export in [format] to the app cache dir. Null if there's nothing to export. */
    private fun buildExportFile(format: ExportFormat): Pair<File, Int>? {
        val state = lastSuccessState ?: return null
        val rows = filteredRows(state)
        if (rows.isEmpty()) return null

        val cacheDir = File(requireContext().cacheDir, "exports").apply { mkdirs() }
        val file = File(cacheDir, exportFileName(format.extension))

        when (format) {
            ExportFormat.CSV -> {
                val (csvContent, _) = buildCsvContent() ?: return null
                file.writeText(csvContent)
            }
            ExportFormat.XLSX -> {
                val (headers, tableRows) = tableData(rows)
                CashExportWriter.writeXlsx(file, modeLabel(mode), headers, tableRows, xlsxColWidths())
            }
            ExportFormat.PDF -> {
                val (headers, tableRows) = tableData(rows)
                val totalAmount = rows.sumOf { it.amount }
                CashExportWriter.writePdf(
                    outFile = file,
                    title = "DataBridge \u2014 ${modeLabel(mode)}",
                    subtitle = pdfSubtitle(),
                    summaryCards = listOf(
                        CashExportWriter.PdfSummaryCard(
                            "Total ${modeLabel(mode)}",
                            "Tk " + NumberFormat.getNumberInstance(Locale.US).format(totalAmount)
                        )
                    ),
                    headers = headers,
                    rows = tableRows,
                    colWeights = pdfColWeights(),
                )
            }
        }
        return file to rows.size
    }

    private fun fileProviderUri(file: File): android.net.Uri? = try {
        androidx.core.content.FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
    } catch (e: Exception) {
        null
    }

    private fun exportAndShare(format: ExportFormat) {
        val (file, count) = buildExportFile(format) ?: run {
            Toast.makeText(requireContext(), "⚠ Export করার মতো কোনো entry নেই", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = fileProviderUri(file) ?: run {
            Toast.makeText(requireContext(), "⚠ File তৈরি করা যায়নি", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = format.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "${format.label} শেয়ার করুন").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(chooser)
            Toast.makeText(requireContext(), "📤 ${format.label} পাঠানো হচ্ছে ($count rows)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "⚠ Share করা যায়নি: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportAndDownload(format: ExportFormat) {
        val (file, count) = buildExportFile(format) ?: run {
            Toast.makeText(requireContext(), "⚠ Export করার মতো কোনো entry নেই", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val resolver = requireContext().contentResolver
            val uri: android.net.Uri?
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, format.mimeType)
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val outFile = File(downloadsDir, file.name)
                uri = android.net.Uri.fromFile(outFile)
            }
            if (uri == null) {
                Toast.makeText(requireContext(), "⚠ File তৈরি করা যায়নি", Toast.LENGTH_SHORT).show()
                return
            }
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
            Toast.makeText(requireContext(), "✅ ${format.label} Downloads এ সেভ হয়েছে ($count rows)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "⚠ Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Row menu: Edit / Delete ──────────────────────────────────────────────────

    private fun ledgerTypeForMode(): String = if (mode == CashListMode.DEPOSITS) LEDGER_TYPE_HANDOVER else LEDGER_TYPE_HUB_PAYMENT

    private fun showEditDialog(row: Row) {
        if (mode == CashListMode.COLLECTIONS) showEditCollectionDialog(row) else showEditLedgerDialog(row)
    }

    private fun confirmDelete(row: Row) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete entry?")
            .setMessage("This will permanently remove this ${taka(row.amount)} entry. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val onDone: (Boolean) -> Unit = { ok ->
                    Toast.makeText(requireContext(), if (ok) "Deleted" else "Failed to delete", Toast.LENGTH_SHORT).show()
                }
                if (mode == CashListMode.COLLECTIONS) {
                    vm.deleteCollection(row.id, onDone)
                } else {
                    vm.deleteLedgerEntry(row.channel.orEmpty(), ledgerTypeForMode(), row.id, onDone)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Date-picker helpers for the edit dialogs (mirrors CashManagementHomeFragment's
    // Add-Collection/Deposit dialogs, so editing feels the same as creating) ──────────

    private fun combineDateKeepingTimeOfDay(pickedUtcMidnight: Long): Long {
        val datePart = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = pickedUtcMidnight }
        val combined = java.util.Calendar.getInstance()
        combined.set(java.util.Calendar.YEAR, datePart.get(java.util.Calendar.YEAR))
        combined.set(java.util.Calendar.MONTH, datePart.get(java.util.Calendar.MONTH))
        combined.set(java.util.Calendar.DAY_OF_MONTH, datePart.get(java.util.Calendar.DAY_OF_MONTH))
        return combined.timeInMillis
    }

    private fun isSameLocalDay(a: Long, b: Long): Boolean {
        val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
        val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) && ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
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
            picker.show(childFragmentManager, "cash_edit_date_picker")
        }
        return { selected }
    }

    private fun showEditCollectionDialog(row: Row) {
        val padding = dp(20)
        val amountInput = EditText(requireContext()).apply {
            hint = "Amount"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            typeface = Typeface.MONOSPACE
            setText(if (row.amount == Math.floor(row.amount)) row.amount.toLong().toString() else row.amount.toString())
        }
        val remarksInput = EditText(requireContext()).apply {
            hint = "Remarks (optional)"
            setText(row.remarks)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        val dateButton = styledDateButton()
        val getDate = wireDatePickerButton(dateButton, row.timestamp)

        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(amountInput)
            addView(remarksInput)
            addView(dateButton)
        }

        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("Edit Collection")
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
                vm.updateCollection(row.id, amt, remarksInput.text.toString(), getDate()) { ok ->
                    if (ok) {
                        dialog.dismiss()
                        Toast.makeText(requireContext(), "Collection updated", Toast.LENGTH_SHORT).show()
                    } else {
                        btnPositive.isEnabled = true
                        btnNegative.isEnabled = true
                        btnPositive.text = "Save"
                        Toast.makeText(requireContext(), "Failed to update", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showEditLedgerDialog(row: Row) {
        val padding = dp(20)
        val amountInput = EditText(requireContext()).apply {
            hint = "Amount"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            typeface = Typeface.MONOSPACE
            setText(if (row.amount == Math.floor(row.amount)) row.amount.toLong().toString() else row.amount.toString())
        }
        val trxIdInput = EditText(requireContext()).apply {
            hint = "Transaction ID (optional)"
            setText(row.trxId)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        val remarksInput = EditText(requireContext()).apply {
            hint = "Remarks (optional)"
            setText(row.remarks)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        val dateButton = styledDateButton()
        val getDate = wireDatePickerButton(dateButton, row.timestamp)

        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(amountInput)
            addView(trxIdInput)
            addView(remarksInput)
            addView(dateButton)
        }

        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(if (mode == CashListMode.DEPOSITS) "Edit Deposit" else "Edit Payment")
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
                vm.updateLedgerEntry(
                    row.channel.orEmpty(), ledgerTypeForMode(), row.id, amt,
                    trxIdInput.text.toString(), remarksInput.text.toString(), getDate()
                ) { ok ->
                    if (ok) {
                        dialog.dismiss()
                        Toast.makeText(requireContext(), "Updated", Toast.LENGTH_SHORT).show()
                    } else {
                        btnPositive.isEnabled = true
                        btnNegative.isEnabled = true
                        btnPositive.text = "Save"
                        Toast.makeText(requireContext(), "Failed to update", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    // ── History (tap and hold) ───────────────────────────────────────────────────

    private fun showHistoryDialog(row: Row) {
        val sheet = BottomSheetDialog(requireContext())
        val fullFmt = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault())

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        content.addView(TextView(requireContext()).apply {
            text = "History"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF0F172A.toInt())
        })
        content.addView(TextView(requireContext()).apply {
            text = "${taka(row.amount)} \u00B7 ${row.subDetail}"
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
            setPadding(0, dp(2), 0, dp(16))
        })

        fun entryRow(action: String, byName: String, atMillis: Long, detail: String?): View =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(TextView(requireContext()).apply {
                    text = "$action \u00B7 $byName"
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(0xFF0F172A.toInt())
                })
                addView(TextView(requireContext()).apply {
                    text = fullFmt.format(Date(atMillis))
                    textSize = 11f
                    setTextColor(0xFF94A3B8.toInt())
                })
                if (!detail.isNullOrBlank()) {
                    addView(TextView(requireContext()).apply {
                        text = detail
                        textSize = 12f
                        setTextColor(0xFF334155.toInt())
                        setPadding(0, dp(2), 0, 0)
                    })
                }
            }

        // "Created" is always first (oldest) -- synthesized from the entry's own fields,
        // same approach as this app's existing parcel Journey Log, so there's no need to
        // write a duplicate "created" history node on every single entry.
        content.addView(entryRow("Created", row.enteredByName.ifBlank { "Unknown" }, row.timestamp, null))

        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(4); bottomMargin = dp(4) }
            setBackgroundColor(0xFFE2E8F0.toInt())
        }
        content.addView(divider)

        val pbLoad = ProgressBar(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.CENTER
                topMargin = dp(12)
            }
        }
        content.addView(pbLoad)

        sheet.setContentView(content)
        sheet.show()

        val path = if (mode == CashListMode.COLLECTIONS) vm.collectionEntryPath(row.id)
                   else vm.ledgerEntryPath(row.channel.orEmpty(), ledgerTypeForMode(), row.id)

        vm.loadHistory(path) { entries ->
            content.removeView(pbLoad)
            if (entries.isEmpty()) {
                content.removeView(divider)
            } else {
                entries.forEach { h ->
                    content.addView(entryRow("Edited", h.changedByName.ifBlank { "Unknown" }, h.changedAt, h.summary))
                }
            }
        }
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
