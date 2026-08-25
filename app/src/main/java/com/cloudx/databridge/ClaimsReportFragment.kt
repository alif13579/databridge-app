package com.cloudx.databridge

import android.content.Intent
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

/** Business-facing report. Its single [report] instance powers both the list
 * and exports, so export can never silently query a different data set. */
class ClaimsReportFragment : Fragment() {
    private val repo = ClaimsRepository()
    private val db = FirebaseDatabase.getInstance()
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private var selectedBranches = linkedSetOf<String>()
    private var from = startOfMonth()
    private var to = endOfToday()
    private var report: ClaimsReport? = null
    private val lockedToBranch get() = arguments?.getBoolean(ARG_LOCK_TO_BRANCH, false) == true
    private val allowedBranchIds get() = arguments?.getStringArrayList(ARG_ALLOWED_BRANCH_IDS)?.filter { it.isNotBlank() }.orEmpty()
    private var allowedBranchNames: Map<String, String> = emptyMap()

    /** employeeId here is always users/{uid}/profile/company_info/employee_id — the same
     *  HR-assigned ID ClaimInfo.employeeId is stamped with on submit (see
     *  PettyCashViewModel.currentEmployeeId()), so it's usable directly as the filter key. */
    private data class ClaimsEmployeeOption(val employeeId: String, val name: String) {
        val display: String get() = "$name ($employeeId)"
    }
    private var claimsEmployeeOptions: List<ClaimsEmployeeOption> = emptyList()
    private val selectedEmployeeIds: MutableSet<String> = mutableSetOf() // empty = all employees

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View = i.inflate(R.layout.fragment_claims_report, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        val suppliedBranchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()
        selectedBranches += suppliedBranchId.takeIf { it.isNotBlank() }.orEmpty()
        val branch = v.findViewById<TextView>(R.id.btnClaimsBranches)
        val employees = v.findViewById<TextView>(R.id.btnClaimsEmployees)
        val fromView = v.findViewById<TextView>(R.id.btnClaimsFrom)
        val toView = v.findViewById<TextView>(R.id.btnClaimsTo)
        fun dates() { fromView.text = "From: ${dateFormat.format(Date(from))}"; toView.text = "To: ${dateFormat.format(Date(to))}" }
        dates(); refreshBranches(branch); rebuildClaimsEmployeeRoster(employees)
        if (lockedToBranch && allowedBranchIds.size <= 1) {
            // A dashboard report must stay within the eligible branch scope.
            // With only one eligible branch there is nothing to pick, so keep
            // the selector out of the way.
            v.findViewById<View>(R.id.layoutClaimsBranchSelector).isVisible = false
        }
        v.findViewById<View>(R.id.btnClaimsReportBack).setOnClickListener { parentFragmentManager.popBackStack() }
        when {
            lockedToBranch && allowedBranchIds.size > 1 -> {
                loadAllowedBranchNames(branch)
                branch.setOnClickListener { chooseAllowedBranch(branch, employees, v) }
            }
            !lockedToBranch -> branch.setOnClickListener { chooseBranches(branch, employees) }
        }
        employees.setOnClickListener { showClaimsEmployeesDropdown(employees) }
        fromView.setOnClickListener { pickDate(from) { from = it; if (to < from) to = endOfDay(it); dates() } }
        toView.setOnClickListener { pickDate(to) { to = endOfDay(it); if (from > to) from = startOfDay(it); dates() } }
        v.findViewById<Button>(R.id.btnClaimsSearch).setOnClickListener { search(v) }
        v.findViewById<Button>(R.id.btnClaimsExcel).setOnClickListener { export("xlsx") }
        v.findViewById<Button>(R.id.btnClaimsPdf).setOnClickListener { export("pdf") }
        // Open with a useful current-month report immediately; changing either
        // date still requires an explicit Search so the user controls refreshes.
        if (lockedToBranch && selectedBranches.isNotEmpty()) search(v)
    }

    /** Picker for a dashboard user's eligible branches. Unlike the generic
     * multi-branch report picker, this is deliberately single-select: a report
     * and its exports always represent one branch at a time. */
    private fun loadAllowedBranchNames(label: TextView) = lifecycleScope.launch {
        allowedBranchNames = allowedBranchIds.associateWith { id ->
            runCatching { db.reference.child("branches/$id/name").get().await().getValue(String::class.java) }
                .getOrNull().orEmpty().ifBlank { id }
        }
        refreshBranches(label)
    }

    private fun chooseAllowedBranch(label: TextView, employees: TextView, root: View) {
        if (allowedBranchIds.isEmpty()) return
        val current = selectedBranches.firstOrNull()
        val selected = allowedBranchIds.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle("Report branch")
            .setSingleChoiceItems(allowedBranchIds.map { allowedBranchNames[it] ?: it }.toTypedArray(), selected) { dialog, which ->
                selectedBranches.clear()
                selectedBranches += allowedBranchIds[which]
                report = null
                refreshBranches(label)
                rebuildClaimsEmployeeRoster(employees)
                dialog.dismiss()
                search(root)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseBranches(label: TextView, employees: TextView) = lifecycleScope.launch {
        val branches = db.reference.child("branches").get().await().children.map { it.key.orEmpty() to (it.child("name").getValue(String::class.java).orEmpty()) }
        if (branches.isEmpty()) return@launch toast("No branches found")
        val checked = BooleanArray(branches.size) { branches[it].first in selectedBranches }
        AlertDialog.Builder(requireContext()).setTitle("Select branches")
            .setMultiChoiceItems(branches.map { (id, name) -> "$name ($id)" }.toTypedArray(), checked) { _, which, yes -> if (yes) selectedBranches += branches[which].first else selectedBranches -= branches[which].first }
            .setPositiveButton("Done") { _, _ -> refreshBranches(label); rebuildClaimsEmployeeRoster(employees) }.show()
    }

    private fun refreshBranches(label: TextView) {
        label.text = when {
            selectedBranches.isEmpty() -> "Select branches"
            lockedToBranch && selectedBranches.size == 1 -> "Branch: ${allowedBranchNames[selectedBranches.first()] ?: selectedBranches.first()}"
            else -> "${selectedBranches.size} branch${if (selectedBranches.size == 1) "" else "es"} selected"
        }
    }

    /** Roster = employees belonging to the currently selected branch(es), via
     *  branches/{id}/employees (same index BranchEditFragment's person picker reads). Rebuilt
     *  whenever branch selection changes so the dropdown never offers an employee who can't
     *  actually have a claim in scope. Previously-selected employees who fall out of the new
     *  roster are dropped, same as CallCenterFragment.rebuildCcAgentRoster(). */
    private fun rebuildClaimsEmployeeRoster(employees: TextView) = lifecycleScope.launch {
        if (selectedBranches.isEmpty()) {
            claimsEmployeeOptions = emptyList()
            selectedEmployeeIds.clear()
            updateClaimsEmployeesLabel(employees)
            return@launch
        }
        val uids = linkedSetOf<String>()
        selectedBranches.forEach { branchId ->
            runCatching { db.reference.child("branches/$branchId/employees").get().await() }
                .getOrNull()?.children?.forEach { e -> e.key?.let { uids += it } }
        }
        val options = coroutineScope {
            uids.map { uid ->
                async {
                    val profile = runCatching { db.reference.child("users/$uid/profile").get().await() }.getOrNull()
                    val empId = profile?.child("company_info/employee_id")?.getValue(String::class.java).orEmpty().trim()
                    if (empId.isBlank()) return@async null
                    val name = profile?.child("name")?.getValue(String::class.java)?.trim().orEmpty().ifBlank { empId }
                    ClaimsEmployeeOption(empId, name)
                }
            }.awaitAll().filterNotNull()
        }.sortedBy { it.name }
        claimsEmployeeOptions = options
        selectedEmployeeIds.retainAll(options.map { it.employeeId }.toSet())
        updateClaimsEmployeesLabel(employees)
    }

    private fun updateClaimsEmployeesLabel(employees: TextView) {
        val selected = claimsEmployeeOptions.filter { it.employeeId in selectedEmployeeIds }
        employees.text = when {
            selected.isEmpty() -> "All Employees"
            selected.size == 1 -> selected.first().name
            selected.size == 2 -> "${selected[0].name} & ${selected[1].name}"
            else -> "${selected[0].name}, ${selected[1].name} & ${selected.size - 2} more"
        }
    }

    /** Searchable multiselect, same dialog + interaction CallCenterFragment.showAgentDropdown()
     *  uses for its agent filter: check any number of employees, Select All/Clear All, Apply.
     *  A fully-checked (or fully-unchecked) result both collapse to "All Employees", matching
     *  ClaimsReportFilter's existing empty-set-means-no-filter semantics. */
    private fun showClaimsEmployeesDropdown(employees: TextView) {
        if (selectedBranches.isEmpty()) return toast("Select branch(es) first")
        if (claimsEmployeeOptions.isEmpty()) return toast("এই branch-এ কোনো employee পাওয়া যায়নি")
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_agent_multiselect, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.etAgentSearch)
        val layoutCheckboxes = dialogView.findViewById<LinearLayout>(R.id.layoutAgentCheckboxes)
        val tvNoResults = dialogView.findViewById<TextView>(R.id.tvAgentNoResults)
        val btnSelectClearAll = dialogView.findViewById<Button>(R.id.btnAgentSelectClearAll)
        val btnApply = dialogView.findViewById<Button>(R.id.btnAgentApply)

        val working: MutableSet<String> = if (selectedEmployeeIds.isEmpty())
            claimsEmployeeOptions.map { it.employeeId }.toMutableSet() else selectedEmployeeIds.toMutableSet()

        val checkboxes = claimsEmployeeOptions.map { option ->
            val cb = LayoutInflater.from(ctx).inflate(R.layout.item_agent_checkbox, layoutCheckboxes, false) as CheckBox
            cb.text = option.display
            cb.isChecked = option.employeeId in working
            layoutCheckboxes.addView(cb)
            option to cb
        }

        var dialog: AlertDialog? = null
        fun updateToggleLabel() { btnSelectClearAll.text = if (working.size >= claimsEmployeeOptions.size) "Clear All" else "Select All" }

        checkboxes.forEach { (option, cb) ->
            cb.setOnCheckedChangeListener { _, checked ->
                if (checked) working += option.employeeId else working -= option.employeeId
                updateToggleLabel()
            }
        }
        btnSelectClearAll.setOnClickListener {
            if (working.size >= claimsEmployeeOptions.size) {
                working.clear(); checkboxes.forEach { (_, cb) -> cb.isChecked = false }
            } else {
                working.clear(); working += claimsEmployeeOptions.map { it.employeeId }
                checkboxes.forEach { (_, cb) -> cb.isChecked = true }
            }
            updateToggleLabel()
        }
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                var anyVisible = false
                checkboxes.forEach { (option, cb) ->
                    val matches = q.isEmpty() || option.name.lowercase().contains(q) || option.employeeId.lowercase().contains(q)
                    cb.visibility = if (matches) View.VISIBLE else View.GONE
                    if (matches) anyVisible = true
                }
                tvNoResults.visibility = if (anyVisible) View.GONE else View.VISIBLE
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        btnApply.setOnClickListener {
            selectedEmployeeIds.clear()
            if (working.size < claimsEmployeeOptions.size) selectedEmployeeIds += working
            updateClaimsEmployeesLabel(employees)
            dialog?.dismiss()
        }
        dialog = AlertDialog.Builder(ctx).setTitle("Select Employees").setView(dialogView).create()
        dialog?.setOnShowListener { updateToggleLabel() }
        dialog?.show()
    }

    private fun search(v: View) {
        if (selectedBranches.isEmpty()) return toast("Select at least one branch")
        val progress = v.findViewById<ProgressBar>(R.id.pbClaimsReport)
        progress.isVisible = true
        lifecycleScope.launch {
            runCatching { repo.search(ClaimsReportFilter(selectedBranches, selectedEmployeeIds, from, to)) }
                .onSuccess { report = it; render(v, it) }.onFailure { toast(it.message ?: "Report search failed") }
            progress.isVisible = false
        }
    }

    private fun render(v: View, data: ClaimsReport) {
        val money = NumberFormat.getNumberInstance(Locale.US)
        v.findViewById<TextView>(R.id.tvClaimsSummary).apply {
            isVisible = true
            val types = data.byType.entries.joinToString(" · ") { "${it.key}: ${it.value.size}" }
            val categories = data.byCategory.entries.joinToString(" · ") { "${it.key}: ${it.value.size}" }
            val statusSummary = data.claims.groupingBy { pettyCashStatusLabel(it.status) }
                .eachCount().entries.joinToString(" · ") { "${it.key}: ${it.value}" }
            text = "Total Claims  ${data.totalRequests}\nRequested  ৳${money.format(data.totalRequested)}   Approved  ৳${money.format(data.totalApproved)}\nSettled  ৳${money.format(data.totalSettled)}\n\nStatus: $statusSummary\nType-wise: $types\nCategory-wise: $categories"
        }
        val rows = v.findViewById<LinearLayout>(R.id.layoutClaimsRows); rows.removeAllViews()
        data.claims.forEach { claim ->
            val row = TextView(requireContext()).apply {
                setPadding(dp(14), dp(12), dp(14), dp(12)); setTextColor(0xFF0F172A.toInt()); textSize = 13f
                setBackgroundResource(R.drawable.bg_card_rounded)
                text = "${claim.claimCode}  •  Placed ${dateFormat.format(Date(claim.placedAt))}\n${claim.employeeName} (${claim.employeeId})\n${claim.type} · ${claim.category} · ${pettyCashStatusLabel(claim.status)}\nRequested ৳${money.format(claim.requestedAmount)} | Approved ৳${money.format(claim.approvedAmount)} | Settled ৳${money.format(claim.settledAmount)}"
            }
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        v.findViewById<View>(R.id.layoutClaimsExport).isVisible = data.claims.isNotEmpty()
    }

    private fun export(format: String) {
        val data = report ?: return toast("Search a report first")
        if (data.claims.isEmpty()) return toast("No claims to export")
        val headers = listOf("Claim Code", "Placed Date", "Employee", "Type", "Category", "Purpose", "Consignment", "Store", "Requested", "Approved", "Settled", "Verified By", "POC Approved By", "Settled By", "Payment", "Transaction", "Status")
        val rows = data.claims.map { c -> listOf<Any>(c.claimCode, dateFormat.format(Date(c.placedAt)), "${c.employeeName} (${c.employeeId})", c.type, c.category, c.purpose, c.consignmentId, c.storeName, c.requestedAmount, c.approvedAmount, c.settledAmount, c.staffByName, c.pocApprovedByName, c.settledByName, c.paymentMethod, c.transactionId, pettyCashStatusLabel(c.status)) }
        val file = File(File(requireContext().cacheDir, "exports").apply { mkdirs() }, "Claims_${System.currentTimeMillis()}.$format")
        if (format == "xlsx") CashExportWriter.writeXlsx(file, "Claims", headers, rows, listOf(16,15,23,14,18,28,16,18,12,12,12,18,18,18,13,16,13))
        else CashExportWriter.writePdf(file, "DataBridge — Petty Cash Report", "Branches: ${selectedBranches.joinToString()} | Placed ${dateFormat.format(Date(from))} – ${dateFormat.format(Date(to))} | Generated: ${SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date())}", listOf(CashExportWriter.PdfSummaryCard("Claims", data.totalRequests.toString()), CashExportWriter.PdfSummaryCard("Requested", "৳${data.totalRequested}"), CashExportWriter.PdfSummaryCard("Approved", "৳${data.totalApproved}"), CashExportWriter.PdfSummaryCard("Settled", "৳${data.totalSettled}")), headers, rows, List(headers.size) { 1f })
        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if (format == "xlsx") "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" else "application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export Claims"))
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
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_LONG).show()
    private val ClaimInfo.placedAt: Long get() = createdAt.takeIf { it > 0L } ?: requestedAt

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val ARG_LOCK_TO_BRANCH = "lock_to_branch"
        private const val ARG_ALLOWED_BRANCH_IDS = "allowed_branch_ids"
        fun newInstance(branchId: String, lockToBranch: Boolean = false, allowedBranchIds: List<String> = emptyList()) = ClaimsReportFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_BRANCH_ID, branchId)
                putBoolean(ARG_LOCK_TO_BRANCH, lockToBranch)
                putStringArrayList(ARG_ALLOWED_BRANCH_IDS, ArrayList(allowedBranchIds))
            }
        }
    }
}
