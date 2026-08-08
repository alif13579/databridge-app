package com.cloudx.databridge

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.util.Calendar

/**
 * Petty Cash Management — Filter / Search (mockup screen 10).
 *
 * Date pickers, status checkboxes (mapped to real PC_STATUS_* constants),
 * category/worker-category selectors, Reset/Apply. Apply packages the
 * selection into a PettyCashFilterState and sends it back to whichever
 * screen opened this one via the Fragment Result API — Pending Settlement,
 * Deposit History, Settlement History, and All Requests all route their
 * filter icon here and listen for PettyCashFilterState.FRAGMENT_RESULT_KEY.
 */
class PettyCashFilterFragment : Fragment() {

    private var branchId: String = ""

    // Checkbox labels map 1:1 to PC_STATUS_* constants — label shown to the
    // person, value is what actually gets matched against request.status.
    private val statusOptions = listOf(
        "Pending Approval" to setOf(PC_STATUS_PENDING_TEAM_ALIGN, PC_STATUS_PENDING_POC),
        "Approved (Waiting Settlement)" to setOf(PC_STATUS_APPROVED),
        "Settled" to setOf(PC_STATUS_SETTLED),
        "Rejected" to setOf(PC_STATUS_REJECTED)
    )
    private val categoryOptions = listOf("All Categories", "Travel Expense", "Fuel Expense", "Stationery", "Office Supplies")
    private val workerCategoryOptions = listOf("All Categories", "Delivery Agent", "Office Staff", "Call Center Agent")

    private var dateFromMillis: Long = 0L
    private var dateToMillis: Long = 0L
    private var selectedCategory = categoryOptions.first()
    private var selectedWorkerCategory = workerCategoryOptions.first()
    private val checkedStatusGroups = mutableSetOf<Int>() // indices into statusOptions

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

        statusOptions.indices.forEach { checkedStatusGroups.add(it) } // all checked by default, matches mockup

        tvDateFrom.setOnClickListener { pickDate(isFrom = true) }
        tvDateTo.setOnClickListener { pickDate(isFrom = false) }
        tvDateFrom.text = "Any"
        tvDateTo.text = "Any"

        buildStatusCheckboxes(view)

        view.findViewById<View>(R.id.layoutPcFilterCategory).setOnClickListener { pickCategory() }
        view.findViewById<View>(R.id.layoutPcFilterWorkerCategory).setOnClickListener { pickWorkerCategory() }

        view.findViewById<View>(R.id.btnPcFilterReset).setOnClickListener { resetFilters(view) }
        view.findViewById<View>(R.id.btnPcFilterApply).setOnClickListener { applyFilters() }
    }

    private fun formatDate(millis: Long): String {
        if (millis == 0L) return "Any"
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
        val base = if (isFrom) dateFromMillis else dateToMillis
        val cal = Calendar.getInstance().apply { if (base != 0L) timeInMillis = base }
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
        statusOptions.forEachIndexed { index, (label, _) ->
            val row = layoutInflater.inflate(R.layout.item_petty_cash_filter_checkbox, container, false)
            val cb = row.findViewById<CheckBox>(R.id.cbFilterOption)
            row.findViewById<TextView>(R.id.tvFilterOptionLabel).text = label
            cb.isChecked = index in checkedStatusGroups
            val toggle = {
                if (index in checkedStatusGroups) checkedStatusGroups.remove(index) else checkedStatusGroups.add(index)
                cb.isChecked = index in checkedStatusGroups
            }
            row.setOnClickListener { toggle() }
            cb.setOnClickListener { toggle() }
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
        dateFromMillis = 0L
        dateToMillis = 0L
        tvDateFrom.text = "Any"
        tvDateTo.text = "Any"

        selectedCategory = categoryOptions.first()
        selectedWorkerCategory = workerCategoryOptions.first()
        tvCategorySelected.text = selectedCategory
        tvWorkerCategorySelected.text = selectedWorkerCategory

        checkedStatusGroups.clear()
        statusOptions.indices.forEach { checkedStatusGroups.add(it) }
        buildStatusCheckboxes(root)
    }

    private fun applyFilters() {
        // If every status group is checked, that's equivalent to "no status
        // filter" — don't narrow the result to a redundant full-coverage set.
        val allChecked = checkedStatusGroups.size == statusOptions.size
        val resolvedStatuses: Set<String> = if (allChecked) emptySet()
        else checkedStatusGroups.flatMap { statusOptions[it].second }.toSet()

        val filterState = PettyCashFilterState(
            dateFromMillis = dateFromMillis,
            dateToMillis = dateToMillis,
            statuses = resolvedStatuses,
            category = selectedCategory,
            workerCategory = selectedWorkerCategory
        )

        parentFragmentManager.setFragmentResult(
            PettyCashFilterState.FRAGMENT_RESULT_KEY,
            Bundle().apply { putBundle(PettyCashFilterState.BUNDLE_KEY_STATE, PettyCashFilterState.toBundle(filterState)) }
        )
        parentFragmentManager.popBackStack()
    }
}
