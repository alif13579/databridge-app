package com.cloudx.databridge

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.util.Calendar

/**
 * Petty Cash Management — Filter / Search (mockup screen 10).
 *
 * Phase 10 (final phase) of the Petty Cash feature build: layout + working
 * date pickers, status checkboxes, category/worker-category selectors,
 * Reset/Apply. This is a standalone filter form for now — wiring it to
 * actually filter Pending Settlement / All Requests / History lists (via
 * a shared filter state passed back through the fragment result API) is a
 * TODO for the ViewModel phase, same as the rest of the Firebase wiring.
 */
class PettyCashFilterFragment : Fragment() {

    private var branchId: String = ""

    private val statusOptions = listOf("Pending Approval", "Approved (Waiting Settlement)", "Settled", "Rejected")
    private val categoryOptions = listOf("All Categories", "Travel Expense", "Fuel Expense", "Stationery", "Office Supplies")
    private val workerCategoryOptions = listOf("All Categories", "Delivery Agent", "Office Staff", "Call Center Agent")

    private var dateFromMillis: Long = 0L
    private var dateToMillis: Long = 0L
    private var selectedCategory = categoryOptions.first()
    private var selectedWorkerCategory = workerCategoryOptions.first()

    private lateinit var tvDateFrom: TextView
    private lateinit var tvDateTo: TextView
    private lateinit var tvCategorySelected: TextView
    private lateinit var tvWorkerCategorySelected: TextView

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        fun newInstance(branchId: String): PettyCashFilterFragment {
            val f = PettyCashFilterFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_filter, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        tvDateFrom = view.findViewById(R.id.tvPcFilterDateFrom)
        tvDateTo = view.findViewById(R.id.tvPcFilterDateTo)
        tvCategorySelected = view.findViewById(R.id.tvPcFilterCategorySelected)
        tvWorkerCategorySelected = view.findViewById(R.id.tvPcFilterWorkerCategorySelected)

        view.findViewById<View>(R.id.btnPcFilterBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Default range: last 30 days, matching the mockup's pre-filled dates.
        val cal = Calendar.getInstance()
        dateToMillis = cal.timeInMillis
        tvDateTo.text = formatDate(dateToMillis)
        cal.add(Calendar.DAY_OF_MONTH, -30)
        dateFromMillis = cal.timeInMillis
        tvDateFrom.text = formatDate(dateFromMillis)

        tvDateFrom.setOnClickListener { pickDate(isFrom = true) }
        tvDateTo.setOnClickListener { pickDate(isFrom = false) }

        buildStatusCheckboxes(view)

        view.findViewById<View>(R.id.layoutPcFilterCategory).setOnClickListener { pickCategory() }
        view.findViewById<View>(R.id.layoutPcFilterWorkerCategory).setOnClickListener { pickWorkerCategory() }

        view.findViewById<View>(R.id.btnPcFilterReset).setOnClickListener { resetFilters(view) }
        view.findViewById<View>(R.id.btnPcFilterApply).setOnClickListener { applyFilters() }
    }

    private fun formatDate(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val month = cal.get(Calendar.MONTH) + 1
        val year = cal.get(Calendar.YEAR)
        return "%02d %s %d".format(day, monthName(month), year)
    }

    private fun monthName(month: Int): String {
        val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return names.getOrElse(month - 1) { "" }
    }

    private fun pickDate(isFrom: Boolean) {
        val cal = Calendar.getInstance().apply { timeInMillis = if (isFrom) dateFromMillis else dateToMillis }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply { set(year, month, day) }.timeInMillis
                if (isFrom) {
                    dateFromMillis = picked
                    tvDateFrom.text = formatDate(picked)
                } else {
                    dateToMillis = picked
                    tvDateTo.text = formatDate(picked)
                }
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun buildStatusCheckboxes(root: View) {
        val container = root.findViewById<android.widget.LinearLayout>(R.id.layoutPcFilterStatusOptions)
        container.removeAllViews()
        statusOptions.forEach { label ->
            val row = layoutInflater.inflate(R.layout.item_petty_cash_filter_checkbox, container, false)
            val cb = row.findViewById<CheckBox>(R.id.cbFilterOption)
            row.findViewById<TextView>(R.id.tvFilterOptionLabel).text = label
            cb.isChecked = true // all checked by default, matches mockup
            row.setOnClickListener { cb.isChecked = !cb.isChecked }
            container.addView(row)
        }
    }

    private fun pickCategory() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Category")
            .setItems(categoryOptions.toTypedArray()) { _, index ->
                selectedCategory = categoryOptions[index]
                tvCategorySelected.text = selectedCategory
            }
            .show()
    }

    private fun pickWorkerCategory() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Worker Category")
            .setItems(workerCategoryOptions.toTypedArray()) { _, index ->
                selectedWorkerCategory = workerCategoryOptions[index]
                tvWorkerCategorySelected.text = selectedWorkerCategory
            }
            .show()
    }

    private fun resetFilters(root: View) {
        val cal = Calendar.getInstance()
        dateToMillis = cal.timeInMillis
        tvDateTo.text = formatDate(dateToMillis)
        cal.add(Calendar.DAY_OF_MONTH, -30)
        dateFromMillis = cal.timeInMillis
        tvDateFrom.text = formatDate(dateFromMillis)

        selectedCategory = categoryOptions.first()
        selectedWorkerCategory = workerCategoryOptions.first()
        tvCategorySelected.text = selectedCategory
        tvWorkerCategorySelected.text = selectedWorkerCategory

        buildStatusCheckboxes(root)
    }

    private fun applyFilters() {
        // TODO(ViewModel phase): pass the selected filter state back to the
        // screen that opened this (Pending Settlement / Deposit History /
        // Settlement History / All Requests) via setFragmentResult, and have
        // that screen re-query Firebase with the filter applied.
        Toast.makeText(requireContext(), "Filters applied", Toast.LENGTH_SHORT).show()
        parentFragmentManager.popBackStack()
    }
}
