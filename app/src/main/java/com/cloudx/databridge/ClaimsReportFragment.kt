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

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View = i.inflate(R.layout.fragment_claims_report, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        selectedBranches += arguments?.getString(ARG_BRANCH_ID).orEmpty().takeIf { it.isNotBlank() }.orEmpty()
        val branch = v.findViewById<TextView>(R.id.btnClaimsBranches)
        val fromView = v.findViewById<TextView>(R.id.btnClaimsFrom)
        val toView = v.findViewById<TextView>(R.id.btnClaimsTo)
        fun dates() { fromView.text = "From: ${dateFormat.format(Date(from))}"; toView.text = "To: ${dateFormat.format(Date(to))}" }
        dates(); refreshBranches(branch)
        v.findViewById<View>(R.id.btnClaimsReportBack).setOnClickListener { parentFragmentManager.popBackStack() }
        branch.setOnClickListener { chooseBranches(branch) }
        fromView.setOnClickListener { pickDate(from) { from = it; if (to < from) to = endOfDay(it); dates() } }
        toView.setOnClickListener { pickDate(to) { to = endOfDay(it); if (from > to) from = startOfDay(it); dates() } }
        v.findViewById<Button>(R.id.btnClaimsSearch).setOnClickListener { search(v) }
        v.findViewById<Button>(R.id.btnClaimsExcel).setOnClickListener { export("xlsx") }
        v.findViewById<Button>(R.id.btnClaimsPdf).setOnClickListener { export("pdf") }
    }

    private fun chooseBranches(label: TextView) = lifecycleScope.launch {
        val branches = db.reference.child("branches").get().await().children.map { it.key.orEmpty() to (it.child("name").getValue(String::class.java).orEmpty()) }
        if (branches.isEmpty()) return@launch toast("No branches found")
        val checked = BooleanArray(branches.size) { branches[it].first in selectedBranches }
        AlertDialog.Builder(requireContext()).setTitle("Select branches")
            .setMultiChoiceItems(branches.map { (id, name) -> "$name ($id)" }.toTypedArray(), checked) { _, which, yes -> if (yes) selectedBranches += branches[which].first else selectedBranches -= branches[which].first }
            .setPositiveButton("Done") { _, _ -> refreshBranches(label) }.show()
    }

    private fun refreshBranches(label: TextView) { label.text = if (selectedBranches.isEmpty()) "Select branches" else "${selectedBranches.size} branch${if (selectedBranches.size == 1) "" else "es"} selected" }

    private fun search(v: View) {
        if (selectedBranches.isEmpty()) return toast("Select at least one branch")
        val employeeIds = v.findViewById<EditText>(R.id.etClaimsEmployees).text.toString().split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val progress = v.findViewById<ProgressBar>(R.id.pbClaimsReport)
        progress.isVisible = true
        lifecycleScope.launch {
            runCatching { repo.search(ClaimsReportFilter(selectedBranches, employeeIds, from, to)) }
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
            text = "Total Claims  ${data.totalRequests}\nRequested  ৳${money.format(data.totalRequested)}   Approved  ৳${money.format(data.totalApproved)}\nSettled  ৳${money.format(data.totalSettled)}   Pending ${data.totalPending}   Rejected ${data.totalRejected}   Cancelled ${data.totalCancelled}\n\nType-wise: $types\nCategory-wise: $categories"
        }
        val rows = v.findViewById<LinearLayout>(R.id.layoutClaimsRows); rows.removeAllViews()
        data.claims.forEach { claim ->
            val row = TextView(requireContext()).apply {
                setPadding(dp(14), dp(12), dp(14), dp(12)); setTextColor(0xFF0F172A.toInt()); textSize = 13f
                setBackgroundResource(R.drawable.bg_card_rounded)
                text = "${claim.claimCode}  •  ${dateFormat.format(Date(claim.requestedAt))}\n${claim.employeeName} (${claim.employeeId})\n${claim.type} · ${claim.category} · ${claim.status}\nRequested ৳${money.format(claim.requestedAmount)} | Approved ৳${money.format(claim.approvedAmount)} | Settled ৳${money.format(claim.settledAmount)}"
            }
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        v.findViewById<View>(R.id.layoutClaimsExport).isVisible = data.claims.isNotEmpty()
    }

    private fun export(format: String) {
        val data = report ?: return toast("Search a report first")
        if (data.claims.isEmpty()) return toast("No claims to export")
        val headers = listOf("Claim Code", "Date", "Employee", "Type", "Category", "Purpose", "Consignment", "Store", "Requested", "Approved", "Settled", "Payment", "Transaction", "Status")
        val rows = data.claims.map { c -> listOf<Any>(c.claimCode, dateFormat.format(Date(c.requestedAt)), "${c.employeeName} (${c.employeeId})", c.type, c.category, c.purpose, c.consignmentId, c.storeName, c.requestedAmount, c.approvedAmount, c.settledAmount, c.paymentMethod, c.transactionId, c.status) }
        val file = File(File(requireContext().cacheDir, "exports").apply { mkdirs() }, "Claims_${System.currentTimeMillis()}.$format")
        if (format == "xlsx") CashExportWriter.writeXlsx(file, "Claims", headers, rows, listOf(16,15,23,14,18,28,16,18,12,12,12,13,16,13))
        else CashExportWriter.writePdf(file, "DataBridge — Claims Report", "Branches: ${selectedBranches.joinToString()} | ${dateFormat.format(Date(from))} – ${dateFormat.format(Date(to))} | Generated: ${SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date())}", listOf(CashExportWriter.PdfSummaryCard("Claims", data.totalRequests.toString()), CashExportWriter.PdfSummaryCard("Requested", "৳${data.totalRequested}"), CashExportWriter.PdfSummaryCard("Settled", "৳${data.totalSettled}")), headers, rows, List(headers.size) { 1f })
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
    companion object { private const val ARG_BRANCH_ID = "branch_id"; fun newInstance(branchId: String) = ClaimsReportFragment().apply { arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) } } }
}
