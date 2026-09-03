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
 * PettyCashTopSheetPdfWriter) — one action, no separate export step (per the
 * discussion that settled on this over a two-step search-then-export flow).
 *
 * FirebaseClaimsIndexMigration (Firebase-only, one-time) is kept ONLY for the
 * one-time employee-index migration tools below
 * (btnClaimsMigrateIndex/btnClaimsDeleteOldIndex) — those migrate Firebase's
 * own claims_by_employeeId->claims_by_systemId index and have nothing to do
 * with the Supabase-backed report itself; they stay Firebase-based on
 * purpose (see their doc comments) and get removed once no longer needed.
 */
class ClaimsReportFragment : Fragment() {
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

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

    /** systemId is the actual filter/index key (claims.agent_system_id); employeeId is
     *  kept purely for display ("Mehedi (EMP001)") — mirrors CallCenterFragment.AgentOption. */
    private data class ClaimsEmployeeOption(val systemId: String, val employeeId: String, val name: String)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_claims_report, container, false)

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)

        v.findViewById<View>(R.id.btnClaimsReportBack).setOnClickListener { parentFragmentManager.popBackStack() }

        v.findViewById<Button>(R.id.btnClaimsMigrateIndex).setOnClickListener { showMigrationDialog() }
        v.findViewById<Button>(R.id.btnClaimsDeleteOldIndex).setOnClickListener { showDeleteOldIndexDialog() }

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
                val claims = SupabaseClaimsReader.fetchClaimsForReport(
                    branchId = selectedBranchId,
                    fromDateIso = fromIso,
                    toDateIso = toIso,
                    agentSystemIds = selectedEmployeeSystemIds.toList(),
                    categories = selectedCategories.toList(),
                    statuses = selectedStatuses.toList(),
                )
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
                )
                outFile
            }.onSuccess { file ->
                v.findViewById<TextView>(R.id.tvClaimsSummary).apply {
                    isVisible = true
                    text = "Report generated: ${file.name}"
                }
                sharePdf(file)
            }.onFailure { toast(it.message ?: "Report generation failed") }
            progress.isVisible = false
        }
    }

    private fun sharePdf(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }

    // ── One-time Firebase employee-index migration tools (unrelated to the report above) ──

    /** One-time trigger for FirebaseClaimsIndexMigration.migrateEmployeeIndexToSystemId. Always dry-runs
     *  first — real writes only happen from a second, explicit tap on that result dialog.
     *  Remove btnClaimsMigrateIndex (and this) once the migration has been run and spot-checked. */
    private fun showMigrationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Migrate employee index")
            .setMessage("Backfills old claims onto the system_id-based index. Starts with a dry run — writes nothing, just reports what would happen.")
            .setPositiveButton("Run Dry Run") { _, _ -> runMigration(dryRun = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runMigration(dryRun: Boolean) {
        toast(if (dryRun) "Running dry run…" else "Migrating…")
        lifecycleScope.launch {
            runCatching { FirebaseClaimsIndexMigration.migrateEmployeeIndexToSystemId(dryRun) }
                .onSuccess { showMigrationResult(it) }
                .onFailure { toast(it.message ?: "Migration failed") }
        }
    }

    private fun showMigrationResult(result: EmployeeIndexMigrationResult) {
        val preview = result.unresolved.take(10).joinToString("\n") { (empId, claimId) -> "• $empId → $claimId" }
        val more = (result.unresolved.size - 10).let { if (it > 0) "\n…and $it more" else "" }
        val body = buildString {
            append(if (result.dryRun) "DRY RUN — nothing written yet.\n\n" else "Done — written.\n\n")
            append("Matched: ${result.matched}\nUnresolved: ${result.unresolved.size}")
            if (result.unresolved.isNotEmpty()) append("\n\n$preview$more")
        }
        val builder = AlertDialog.Builder(requireContext()).setTitle("Migration result").setMessage(body)
        if (result.dryRun && result.matched > 0) {
            builder.setPositiveButton("Run For Real") { _, _ -> runMigration(dryRun = false) }
            builder.setNegativeButton("Close", null)
        } else {
            builder.setPositiveButton("OK", null)
        }
        builder.show()
    }

    private fun showDeleteOldIndexDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete old employee_id index?")
            .setMessage("Permanently deletes claims/indexes/claims_by_employeeId. Only do this after you've run the migration and spot-checked a few claims in the new system_id index — this cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    runCatching { FirebaseClaimsIndexMigration.deleteOldEmployeeIndex() }
                        .onSuccess { toast("Old index deleted") }
                        .onFailure { toast(it.message ?: "Delete failed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Date helpers ──────────────────────────────────────────────────────────

    private fun updateDateLabels(v: View) {
        v.findViewById<TextView>(R.id.btnClaimsFrom).text = dateFormat.format(Date(from))
        v.findViewById<TextView>(R.id.btnClaimsTo).text = dateFormat.format(Date(to))
    }

    private fun pickDate(initial: Long, done: (Long) -> Unit) { MaterialDatePicker.Builder.datePicker().setSelection(initial).build().also { it.addOnPositiveButtonClickListener { utc -> done(localDay(utc)) }; it.show(childFragmentManager, "claims_date") } }
    private fun localDay(utc: Long): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
        timeInMillis = utc
        Calendar.getInstance().apply {
            set(get(Calendar.YEAR), get(Calendar.MONTH), get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun startOfMonth(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun endOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
    }.timeInMillis
    private fun endOfToday() = endOfDay(System.currentTimeMillis())
    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_LONG).show()

    companion object {
        fun newInstance() = ClaimsReportFragment()
    }
}
