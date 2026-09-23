package com.cloudx.databridge

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Business-facing Petty Cash / Claims report. SEARCH & GENERATE REPORT queries
 * public.claims (via SupabaseClaimsReader) and, on success, immediately builds
 * the full "Top Sheet For Petty Cash Expense" multi-page PDF (via
 * PettyCashTopSheetPdfWriter) plus an .xlsx Excel export of the same rows (via
 * CashExportWriter) — one search, both files downloadable.
 */
class ClaimsReportFragment : Fragment() {
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).apply { timeZone = BdTime.ZONE }
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = BdTime.ZONE }

    // Single-select branch (see the discussion that settled on this over
    // multi-select: pick freely from the full branch list, but only one
    // active at a time — the Top Sheet PDF is inherently one-branch-per-report,
    // "Hub Name: <one branch>").
    private var selectedBranchId: String = ""
    private var selectedBranchName: String = ""
    private var branchOptions: List<SupabaseClaimsReader.BranchOption> = emptyList()

    // Employee/category/status are all optional narrowing multiselects — an
    // empty selected-set means "All" (no filter on that dimension), matching
    // SupabaseClaimsReader.fetchClaimsForReport's empty-list-means-no-filter
    // convention.
    private var employeeOptions: List<ClaimsEmployeeOption> = emptyList()
    private var selectedEmployeeSystemIds = linkedSetOf<String>()
    private var categoryOptions: List<String> = emptyList()
    private var selectedCategories = linkedSetOf<String>()
    private var statusOptions: List<String> = emptyList()
    private var selectedStatuses = linkedSetOf<String>()

    private var from = startOfMonth()
    private var to = endOfToday()

    // Last successful search — powers the Excel export so it downloads exactly
    // the rows the PDF was generated from (no re-query, no filter drift).
private var lastClaims: List<SupabaseClaimsReader.ClaimRow> = emptyList()
private var lastFromIso: String = ""
private var lastToIso: String = ""
private var lastPdf: File? = null

    /** systemId is the actual filter/index key (claims.agent_system_id); employeeId is
     *  kept purely for display ("Mehedi (EMP001)") — mirrors CallCenterFragment.AgentOption. */
    private data class ClaimsEmployeeOption(val systemId: String, val employeeId: String, val name: String)

    // Pre-Q (Android ≤ 9) Downloads writes need WRITE_EXTERNAL_STORAGE at
    // runtime — without it the copy fails with "Permission denied". Q+
    // goes through MediaStore (no permission needed). A deferred download
    // waits here while the permission dialog is up.
    private var pendingDownload: (() -> Unit)? = null
    private val storagePermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingDownload?.invoke()
        else toast("Storage permission denied — cannot save to Downloads (Share still works)")
        pendingDownload = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_claims_report, container, false)

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)

        v.findViewById<View>(R.id.btnClaimsReportBack).setOnClickListener { parentFragmentManager.popBackStack() }

        updateDateLabels(v)
        v.findViewById<TextView>(R.id.btnClaimsFrom).setOnClickListener {
            pickDate(from) { picked -> from = startOfDay(picked); updateDateLabels(v) }
        }
        v.findViewById<TextView>(R.id.btnClaimsTo).setOnClickListener {
            pickDate(to) { picked -> to = endOfDay(picked); updateDateLabels(v) }
        }

        v.findViewById<TextView>(R.id.btnClaimsBranches).setOnClickListener { showBranchDialog(v) }
        v.findViewById<TextView>(R.id.btnClaimsEmployees).setOnClickListener { showEmployeeDialog(v) }
        v.findViewById<TextView>(R.id.btnClaimsCategories).setOnClickListener { showMultiselectDialog(
            title = "Select Categories", options = categoryOptions, selected = selectedCategories,
            allLabel = "All Categories", labelView = v.findViewById(R.id.btnClaimsCategories),
        ) }
        v.findViewById<TextView>(R.id.btnClaimsStatuses).setOnClickListener { showMultiselectDialog(
            title = "Select Statuses", options = statusOptions, selected = selectedStatuses,
            allLabel = "All Statuses", labelView = v.findViewById(R.id.btnClaimsStatuses),
        ) }

        v.findViewById<Button>(R.id.btnClaimsSearch).setOnClickListener { searchAndGenerate(v) }
        v.findViewById<Button>(R.id.btnClaimsExcel).setOnClickListener { exportExcelChooser() }

        lifecycleScope.launch { loadBranches(v) }
    }

    // ── Branch loading + selection ──────────────────────────────────────────

    private suspend fun loadBranches(v: View) {
        branchOptions = SupabaseClaimsReader.fetchBranches()
        if (branchOptions.size == 1) {
            selectedBranchId = branchOptions.first().branchId
            selectedBranchName = branchOptions.first().name
            v.findViewById<TextView>(R.id.btnClaimsBranches).text = selectedBranchName
            onBranchChanged(v)
        }
    }

    private fun showBranchDialog(v: View) {
        if (branchOptions.isEmpty()) return toast("No branches available")
        val names = branchOptions.map { it.name }.toTypedArray()
        val currentIndex = branchOptions.indexOfFirst { it.branchId == selectedBranchId }
        AlertDialog.Builder(requireContext())
            .setTitle("Select Branch")
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                val picked = branchOptions[which]
                selectedBranchId = picked.branchId
                selectedBranchName = picked.name
                v.findViewById<TextView>(R.id.btnClaimsBranches).text = selectedBranchName
                dialog.dismiss()
                onBranchChanged(v)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Re-loads employee/category/status option lists whenever the branch changes —
     *  all three are scoped to the selected branch (a category/status/employee that
     *  only appears in another branch's claims shouldn't clutter this branch's filters). */
    private fun onBranchChanged(v: View) {
        selectedEmployeeSystemIds.clear()
        selectedCategories.clear()
        selectedStatuses.clear()
        v.findViewById<TextView>(R.id.btnClaimsEmployees).text = "All Employees"
        v.findViewById<TextView>(R.id.btnClaimsCategories).text = "All Categories"
        v.findViewById<TextView>(R.id.btnClaimsStatuses).text = "All Statuses"
        if (selectedBranchId.isBlank()) return
        lifecycleScope.launch {
            val (categories, statuses) = coroutineScope {
                val categoriesDeferred = async { SupabaseClaimsReader.fetchDistinctCategories(selectedBranchId) }
                val statusesDeferred = async { SupabaseClaimsReader.fetchDistinctStatuses(selectedBranchId) }
                categoriesDeferred.await() to statusesDeferred.await()
            }
            categoryOptions = categories
            statusOptions = statuses
            loadEmployeesForBranch()
        }
    }

    private suspend fun loadEmployeesForBranch() {
        // Employee options come from the same claims rows (agent_system_id + the
        // embedded users.name) rather than a separate users-table query — this
        // guarantees the employee list only ever shows people who actually have
        // a claim in this branch, not the full company roster.
        val rows = SupabaseClaimsReader.fetchClaimsForReport(selectedBranchId, "1970-01-01", "2999-12-31")
        employeeOptions = rows.map { ClaimsEmployeeOption(it.agentSystemId, it.agentEmployeeId, it.agentName.ifBlank { it.agentSystemId }) }
            .distinctBy { it.systemId }
            .sortedBy { it.name }
    }

    // ── Generic multiselect dialog (Category/Status) ────────────────────────

    private fun showMultiselectDialog(title: String, options: List<String>, selected: MutableSet<String>, allLabel: String, labelView: TextView) {
        if (options.isEmpty()) return toast("No options available — select a branch first")
        val checked = options.map { selected.isEmpty() || it in selected }.toBooleanArray()
        val working = linkedSetOf<String>().apply { addAll(if (selected.isEmpty()) options else selected) }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMultiChoiceItems(options.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) working.add(options[which]) else working.remove(options[which])
            }
            .setPositiveButton("Apply") { _, _ ->
                selected.clear()
                if (working.size < options.size) selected += working
                labelView.text = if (selected.isEmpty()) allLabel else "${selected.size} selected"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Employee multiselect (needs search — can be a long list) ────────────

    private fun showEmployeeDialog(v: View) {
        if (employeeOptions.isEmpty()) return toast("No employees available — select a branch first")
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_agent_multiselect, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.etAgentSearch)
        val layoutList = dialogView.findViewById<LinearLayout>(R.id.layoutAgentCheckboxes)
        val tvNoResults = dialogView.findViewById<TextView>(R.id.tvAgentNoResults)
        val btnSelectClearAll = dialogView.findViewById<Button>(R.id.btnAgentSelectClearAll)
        val btnApply = dialogView.findViewById<Button>(R.id.btnAgentApply)

        val working = linkedSetOf<String>().apply { addAll(if (selectedEmployeeSystemIds.isEmpty()) employeeOptions.map { it.systemId } else selectedEmployeeSystemIds) }
        val checkboxes = mutableListOf<Pair<ClaimsEmployeeOption, CheckBox>>()

        fun updateToggleLabel() {
            btnSelectClearAll.text = if (working.size >= employeeOptions.size) "Clear All" else "Select All"
        }

        employeeOptions.forEach { option ->
            val cb = CheckBox(ctx).apply {
                text = "${option.name} (${option.employeeId.ifBlank { option.systemId }})"
                isChecked = option.systemId in working
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) working.add(option.systemId) else working.remove(option.systemId)
                    updateToggleLabel()
                }
            }
            layoutList.addView(cb)
            checkboxes.add(option to cb)
        }

        var dialog: AlertDialog? = null
        btnSelectClearAll.setOnClickListener {
            if (working.size >= employeeOptions.size) {
                working.clear(); checkboxes.forEach { (_, cb) -> cb.isChecked = false }
            } else {
                working.clear(); working += employeeOptions.map { it.systemId }
                checkboxes.forEach { (_, cb) -> cb.isChecked = true }
            }
            updateToggleLabel()
        }
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                var anyVisible = false
                checkboxes.forEach { (option, cb) ->
                    val matches = q.isEmpty() || option.name.lowercase().contains(q) || option.employeeId.lowercase().contains(q) || option.systemId.contains(q)
                    cb.visibility = if (matches) View.VISIBLE else View.GONE
                    if (matches) anyVisible = true
                }
                tvNoResults.visibility = if (anyVisible) View.GONE else View.VISIBLE
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        btnApply.setOnClickListener {
            selectedEmployeeSystemIds.clear()
            if (working.size < employeeOptions.size) selectedEmployeeSystemIds += working
            v.findViewById<TextView>(R.id.btnClaimsEmployees).text =
                if (selectedEmployeeSystemIds.isEmpty()) "All Employees" else "${selectedEmployeeSystemIds.size} selected"
            dialog?.dismiss()
        }
        dialog = AlertDialog.Builder(ctx).setTitle("Select Employees").setView(dialogView).create()
        dialog?.setOnShowListener { updateToggleLabel() }
        dialog?.show()
    }

    // ── Search + PDF generation ──────────────────────────────────────────────

    private fun searchAndGenerate(v: View) {
        if (selectedBranchId.isBlank()) return toast("Select a branch")
        val progress = v.findViewById<ProgressBar>(R.id.pbClaimsReport)
        progress.isVisible = true
        lifecycleScope.launch {
            runCatching {
                val fromIso = isoDateFormat.format(Date(from))
                val toIso = isoDateFormat.format(Date(to))
                // Server date-only bounds are UTC midnights — a Dhaka day
                // starts 6h earlier, so fetch ±1 day wide and keep exactly
                // the Dhaka-local days (same basis as every other screen).
                val claims = SupabaseClaimsReader.fetchClaimsForReport(
                    branchId = selectedBranchId,
                    fromDateIso = shiftIsoDays(fromIso, -1),
                    toDateIso = shiftIsoDays(toIso, 1),
                    agentSystemIds = selectedEmployeeSystemIds.toList(),
                    categories = selectedCategories.toList(),
                    statuses = selectedStatuses.toList(),
                ).filter { it.dhakaDate() in fromIso..toIso }
                if (claims.isEmpty()) throw IllegalStateException("No claims found for the selected filters")
                val branchRegion = claims.first().branchRegion
                val pettyCashLimit = claims.first().branchPettyCashLimit
                // Admin-managed category → group map for the report's dynamic
                // sections (empty = writer's legacy fallback covers the known
                // conveyance types; never fails the report).
                val categoryGroups = runCatching { SupabaseClaimsReader.fetchClaimCategories() }
                    .getOrDefault(emptyList()).associate { it.name to it.group }
                // The real branch POC via branches.petty_cash_poc_uid →
                // public.users. Falls back to the first matching claim's own
                // agent details as a "prepared by" stand-in rather than
                // leaving the header blank.
                val poc = runCatching { SupabaseClaimsReader.fetchPocForBranch(selectedBranchId) }.getOrNull()
                val firstAgent = claims.first()
                val pocName = poc?.name?.takeIf { it.isNotBlank() } ?: firstAgent.agentName
                val pocEmployeeId = poc?.employeeId?.takeIf { it.isNotBlank() } ?: firstAgent.agentEmployeeId
                val pocDesignation = poc?.designation?.takeIf { it.isNotBlank() } ?: firstAgent.agentDesignation
                val pocContact = poc?.phone?.takeIf { it.isNotBlank() } ?: firstAgent.agentPhone
                // exports/ subfolder — matches file_paths.xml's <cache-path name="exports"
                // path="exports/" /> declaration (see CashLedgerListFragment/
                // CashManagementHomeFragment/ScannerFragment for the same pattern). A
                // file saved directly at cacheDir's root isn't covered by that
                // declaration and FileProvider.getUriForFile() below would throw.
                val exportsDir = File(requireContext().cacheDir, "exports").apply { mkdirs() }
                val outFile = File(exportsDir, "petty_cash_top_sheet_${System.currentTimeMillis()}.pdf")
                PettyCashTopSheetPdfWriter.generate(
                    outFile = outFile,
                    claims = claims,
                    branchName = selectedBranchName,
                    branchRegion = branchRegion,
                    pettyCashLimit = pettyCashLimit,
                    pocName = pocName,
                    pocEmployeeId = pocEmployeeId,
                    pocDesignation = pocDesignation,
                    pocContact = pocContact,
                    fromDateIso = fromIso,
                    toDateIso = toIso,
                    categoryGroups = categoryGroups,
                    appContext = requireContext(),
                )
                Triple(outFile, claims, fromIso to toIso)
            }.onSuccess { (file, claims, range) ->
                lastClaims = claims
                lastFromIso = range.first
                lastToIso = range.second
                lastPdf = file
                v.findViewById<TextView>(R.id.tvClaimsSummary).apply {
                    isVisible = true
                    text = "Report ready: ${claims.size} claims — tap EXPORT for PDF / Excel / CSV"
                }
                v.findViewById<Button>(R.id.btnClaimsExcel).apply {
                    isVisible = true
                    text = "⬇ EXPORT (${claims.size} ROWS)"
                }
            }.onFailure { toast(it.message ?: "Report generation failed") }
            progress.isVisible = false
        }
    }

    // ── Excel export (.xlsx, same rows as the generated PDF) ──────────────────
    // Reuses CashExportWriter (dependency-free OOXML writer shared with the cash
    // ledger exports) and the same FileProvider/MediaStore share+download flow.

    /** Export format option: PDF / Excel / CSV — then Share vs Download. */
    private fun exportExcelChooser() {
        if (lastClaims.isEmpty()) return toast("Generate the report first")
        AlertDialog.Builder(requireContext())
            .setTitle("Export Format (${lastClaims.size} rows)")
            .setItems(arrayOf("📄 PDF", "📊 Excel (.xlsx)", "📝 CSV")) { _, which ->
                when (which) {
                    0 -> exportTargetChooser("PDF") { share -> exportPdf(share) }
                    1 -> exportTargetChooser("Excel") { share -> exportExcel(share) }
                    else -> exportTargetChooser("CSV") { share -> exportCsv(share) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportTargetChooser(format: String, run: (Boolean) -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle("$format — Share or Download?")
            .setItems(arrayOf("📤 Share", "⬇️ Download to Downloads")) { _, which ->
                run(which == 0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportPdf(share: Boolean) {
        val file = lastPdf
        if (!isAdded || lastClaims.isEmpty() || file == null || !file.exists()) {
            toast("Generate the report first")
            return
        }
        if (share) {
            sharePdf(file)
        } else {
            saveToDownloads(
                file,
                "claims_report_${lastFromIso}_${lastToIso}_${System.currentTimeMillis()}.pdf",
                "application/pdf",
                "✅ PDF saved to Downloads (${lastClaims.size} rows)"
            )
        }
    }

    private fun exportExcel(share: Boolean) {
        if (!isAdded || lastClaims.isEmpty()) return
        val ctx = requireContext()
        val mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        val exportsDir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, "claims_report_${lastFromIso}_${lastToIso}_${System.currentTimeMillis()}.xlsx")
        runCatching {
            // Same column order as the shared claims Excel (Date … Status),
            // Invoice = claim code (there is no separate invoice number).
            val headers = listOf("Date", "From", "To", "Vehicle", "Invoice", "Agent ID", "Agent Name", "Type", "Consignment/Merchant", "LOT ID", "Requested", "Approved", "Settled", "Status")
            val rows = lastClaims.map { c ->
                listOf<Any>(
                    sheetDate(c.placedDate), areaDisplay(c.fromArea), areaDisplay(c.toArea), c.vehicle,
                    c.claimCode, c.agentEmployeeId, c.agentName.ifBlank { c.agentSystemId },
                    c.category, c.cidOrMerchant, c.storeId,
                    c.requestedAmount, c.approvedAmount, c.settledAmount, c.status,
                )
            }
            val widths = listOf(11, 20, 20, 12, 20, 12, 24, 24, 22, 12, 11, 11, 11, 16)
            CashExportWriter.writeXlsx(file, "Claims $lastFromIso", headers, rows, widths)
            file
        }.onSuccess {
            if (share) {
                val uri = runCatching {
                    androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it)
                }.getOrNull() ?: return toast("Could not create file")
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivity(android.content.Intent.createChooser(intent, "Share Excel")) }
                    .onFailure { toast("Share failed: ${it.message}") }
            } else {
                saveToDownloads(it, it.name, mime)
            }
        }.onFailure { toast("Excel export failed: ${it.message}") }
    }

    /** CSV export (.csv, same rows as PDF/Excel) — plain text, opens in
     *  Excel/Sheets, same FileProvider/MediaStore share+download flow. */
    private fun exportCsv(share: Boolean) {
        if (!isAdded || lastClaims.isEmpty()) return
        val ctx = requireContext()
        val mime = "text/csv"
        val exportsDir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, "claims_report_${lastFromIso}_${lastToIso}_${System.currentTimeMillis()}.csv")
        runCatching {
            val headers = listOf("Date", "From", "To", "Vehicle", "Invoice", "Agent ID", "Agent Name", "Type", "Consignment/Merchant", "LOT ID", "Requested", "Approved", "Settled", "Status")
            val sb = StringBuilder()
            sb.appendLine(headers.joinToString(",") { csvCell(it) })
            lastClaims.forEach { c ->
                val cells = listOf(
                    sheetDate(c.placedDate), areaDisplay(c.fromArea), areaDisplay(c.toArea), c.vehicle,
                    c.claimCode, c.agentEmployeeId, c.agentName.ifBlank { c.agentSystemId },
                    c.category, c.cidOrMerchant, c.storeId,
                    c.requestedAmount.toString(), c.approvedAmount.toString(), c.settledAmount.toString(), c.status,
                )
                sb.appendLine(cells.joinToString(",") { csvCell(it) })
            }
            file.writeText(sb.toString(), Charsets.UTF_8)
            file
        }.onSuccess {
            if (share) {
                val uri = runCatching {
                    androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it)
                }.getOrNull() ?: return toast("Could not create file")
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivity(android.content.Intent.createChooser(intent, "Share CSV")) }
                    .onFailure { toast("Share failed: ${it.message}") }
            } else {
                saveToDownloads(it, it.name, mime, "✅ CSV saved to Downloads (${lastClaims.size} rows)")
            }
        }.onFailure { toast("CSV export failed: ${it.message}") }
    }

    private fun csvCell(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"${value.replace("\"", "\"\"")}\"" else value
    }

    /** Sheet date "26-Aug-26" from yyyy-MM-dd; "OFFICE" sentinel → "Office". */
    private fun sheetDate(iso: String): String {
        val p = iso.split("-")
        if (p.size != 3) return iso
        val mon = mapOf("01" to "Jan", "02" to "Feb", "03" to "Mar", "04" to "Apr", "05" to "May", "06" to "Jun", "07" to "Jul", "08" to "Aug", "09" to "Sep", "10" to "Oct", "11" to "Nov", "12" to "Dec")[p[1]] ?: return iso
        return "${p[2]}-$mon-${p[0].takeLast(2)}"
    }

    private fun areaDisplay(value: String): String =
        if (value.trim().equals("OFFICE", ignoreCase = true)) "Office" else value

    /** Copies a cache-dir export into the public Downloads folder (same MediaStore
     *  flow CashLedgerListFragment uses — no storage permission needed on Q+). */
    private fun saveToDownloads(file: File, displayName: String, mimeType: String, doneNote: String? = null) {
        val ctx = requireContext()
        // Pre-Q needs a runtime storage grant first — otherwise EACCES.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = { saveToDownloads(file, displayName, mimeType, doneNote) }
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        runCatching {
            val resolver = ctx.contentResolver
            val uri: android.net.Uri? =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    }
                    resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                } else {
                    @Suppress("DEPRECATION")
                    val outFile = File(
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                        displayName,
                    )
                    android.net.Uri.fromFile(outFile)
                }
            if (uri == null) throw IllegalStateException("Could not create file")
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
                ?: throw IllegalStateException("Could not write file")
            uri
        }.onSuccess {
            toast(doneNote ?: "✅ Excel saved to Downloads (${lastClaims.size} rows)")
        }.onFailure { toast("Download failed: ${it.message}") }
    }

    private fun sharePdf(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { toast("No PDF viewer installed — use Download instead") }
    }

    // ── Date helpers ──────────────────────────────────────────────────────────

    private fun updateDateLabels(v: View) {
        v.findViewById<TextView>(R.id.btnClaimsFrom).text = dateFormat.format(Date(from))
        v.findViewById<TextView>(R.id.btnClaimsTo).text = dateFormat.format(Date(to))
    }

    /** Whole-day shift of a yyyy-MM-dd string (for widening server bounds). */
    private fun shiftIsoDays(iso: String, days: Int): String {
        val millis = runCatching { isoDateFormat.parse(iso)?.time }.getOrNull() ?: return iso
        return isoDateFormat.format(Date(millis + days * 86_400_000L))
    }

    private fun pickDate(initial: Long, done: (Long) -> Unit) { MaterialDatePicker.Builder.datePicker().setSelection(initial).build().also { it.addOnPositiveButtonClickListener { utc -> done(localDay(utc)) }; it.show(parentFragmentManager, "claims_date") } }
    /** Converts the picker's UTC-midnight millis to local-midnight millis for the
     *  same calendar day. NOTE: the UTC fields must be read off the UTC calendar
     *  explicitly — an unqualified get() inside apply{} would resolve to the
     *  local calendar (which is "now") and every pick would silently become today. */
    private fun localDay(utc: Long): Long {
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
        return BdTime.cal().apply {
            set(utcCal.get(Calendar.YEAR), utcCal.get(Calendar.MONTH), utcCal.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun startOfMonth(): Long = BdTime.cal().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfDay(t: Long): Long = BdTime.startOfDay(t)

    private fun endOfDay(t: Long): Long = BdTime.endOfDay(t)
    private fun endOfToday() = endOfDay(System.currentTimeMillis())
    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_LONG).show()

    companion object {
        fun newInstance() = ClaimsReportFragment()
    }
}
