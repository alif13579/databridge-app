package com.cloudx.databridge

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.datepicker.MaterialDatePicker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Petty Cash Management -- Filter screen (mockup screen 10 of 10). Screens are being
 * built back-to-front (10 -> 1) while screens 1-3 are being built separately, so this
 * is the first screen of the feature; see PettyCashModels.kt for the shared filter
 * contract every list screen (Pending Settlement, Deposit/Settlement History, All
 * Requests) will consume once they're built.
 *
 * Pure UI + local state, no Firebase reads. Opened by a list screen via newInstance(),
 * returns the chosen SettlementFilterCriteria through the Fragment Result API under
 * REQUEST_KEY -- avoids needing a shared/activity-scoped ViewModel between this and
 * whatever the calling screen ends up using internally.
 */
class PettyCashFilterFragment : Fragment() {

    companion object {
        const val REQUEST_KEY = "petty_cash_filter_result"
        const val RESULT_FROM_MILLIS = "from_millis"
        const val RESULT_TO_MILLIS = "to_millis"
        const val RESULT_STATUSES = "statuses"
        const val RESULT_CATEGORY = "category"
        const val RESULT_WORKER_CATEGORY = "worker_category"

        /**
         * [initial] pre-fills the screen with the caller's currently-applied criteria,
         * so re-opening Filter doesn't lose prior selections. Omit for a fresh
         * "last 30 days, every status, all categories" default (matches the mockup).
         */
        fun newInstance(initial: SettlementFilterCriteria? = null): PettyCashFilterFragment {
            val f = PettyCashFilterFragment()
            if (initial != null) f.arguments = initial.toBundle()
            return f
        }
    }

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    private var fromMillis: Long = 0L
    private var toMillis: Long = 0L
    private var category: String? = null
    private var workerCategory: String? = null

    private lateinit var tvFromDate: TextView
    private lateinit var tvToDate: TextView
    private lateinit var tvCategoryValue: TextView
    private lateinit var tvWorkerCategoryValue: TextView
    private lateinit var cbStatusPending: CheckBox
    private lateinit var cbStatusApproved: CheckBox
    private lateinit var cbStatusSettled: CheckBox
    private lateinit var cbStatusRejected: CheckBox

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_filter, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvFromDate = view.findViewById(R.id.tvFromDate)
        tvToDate = view.findViewById(R.id.tvToDate)
        tvCategoryValue = view.findViewById(R.id.tvCategoryValue)
        tvWorkerCategoryValue = view.findViewById(R.id.tvWorkerCategoryValue)
        cbStatusPending = view.findViewById(R.id.cbStatusPending)
        cbStatusApproved = view.findViewById(R.id.cbStatusApproved)
        cbStatusSettled = view.findViewById(R.id.cbStatusSettled)
        cbStatusRejected = view.findViewById(R.id.cbStatusRejected)

        val initial = arguments?.let { SettlementFilterCriteria.fromBundle(it) }
        applyToUi(initial ?: defaultCriteria())

        view.findViewById<View>(R.id.btnFilterBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btnFromDate).setOnClickListener { showDateRangePicker() }
        view.findViewById<View>(R.id.btnToDate).setOnClickListener { showDateRangePicker() }
        view.findViewById<View>(R.id.btnCategory).setOnClickListener {
            showPickerDialog("Category", PettyCashCategories.EXPENSE_CATEGORIES, category) { picked ->
                category = picked
                tvCategoryValue.text = picked ?: "All Categories"
            }
        }
        view.findViewById<View>(R.id.btnWorkerCategory).setOnClickListener {
            showPickerDialog("Worker Category", PettyCashCategories.WORKER_CATEGORIES, workerCategory) { picked ->
                workerCategory = picked
                tvWorkerCategoryValue.text = picked ?: "All Categories"
            }
        }
        view.findViewById<View>(R.id.btnFilterReset).setOnClickListener {
            applyToUi(defaultCriteria())
        }
        view.findViewById<View>(R.id.btnFilterApply).setOnClickListener {
            parentFragmentManager.setFragmentResult(REQUEST_KEY, currentCriteria().toBundle())
            parentFragmentManager.popBackStack()
        }
    }

    /** Fresh-open default: last 30 days, every status, all categories -- matches the mockup. */
    private fun defaultCriteria(): SettlementFilterCriteria {
        val to = System.currentTimeMillis()
        val from = Calendar.getInstance().apply { timeInMillis = to; add(Calendar.DAY_OF_YEAR, -30) }.timeInMillis
        return SettlementFilterCriteria(fromDateMillis = from, toDateMillis = to)
    }

    private fun applyToUi(criteria: SettlementFilterCriteria) {
        val fallback = defaultCriteria()
        fromMillis = criteria.fromDateMillis ?: fallback.fromDateMillis!!
        toMillis = criteria.toDateMillis ?: fallback.toDateMillis!!
        tvFromDate.text = dateFmt.format(Date(fromMillis))
        tvToDate.text = dateFmt.format(Date(toMillis))
        category = criteria.category
        workerCategory = criteria.workerCategory
        tvCategoryValue.text = category ?: "All Categories"
        tvWorkerCategoryValue.text = workerCategory ?: "All Categories"
        cbStatusPending.isChecked = SETTLEMENT_STATUS_PENDING in criteria.statuses
        cbStatusApproved.isChecked = SETTLEMENT_STATUS_APPROVED in criteria.statuses
        cbStatusSettled.isChecked = SETTLEMENT_STATUS_SETTLED in criteria.statuses
        cbStatusRejected.isChecked = SETTLEMENT_STATUS_REJECTED in criteria.statuses
    }

    private fun currentCriteria(): SettlementFilterCriteria {
        val statuses = buildSet {
            if (cbStatusPending.isChecked) add(SETTLEMENT_STATUS_PENDING)
            if (cbStatusApproved.isChecked) add(SETTLEMENT_STATUS_APPROVED)
            if (cbStatusSettled.isChecked) add(SETTLEMENT_STATUS_SETTLED)
            if (cbStatusRejected.isChecked) add(SETTLEMENT_STATUS_REJECTED)
        }
        return SettlementFilterCriteria(fromMillis, toMillis, statuses, category, workerCategory)
    }

    private fun showDateRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select date range")
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val start = selection.first ?: return@addOnPositiveButtonClickListener
            val end = selection.second ?: start
            fromMillis = start
            toMillis = end
            tvFromDate.text = dateFmt.format(Date(start))
            tvToDate.text = dateFmt.format(Date(end))
        }
        picker.show(childFragmentManager, "petty_cash_filter_date_range")
    }

    private fun showPickerDialog(title: String, options: List<String>, current: String?, onPicked: (String?) -> Unit) {
        val allLabel = "All Categories"
        val items = (listOf(allLabel) + options).toTypedArray()
        val currentIndex = if (current == null) 0 else (options.indexOf(current) + 1)
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(items, currentIndex) { dialog, index ->
                onPicked(if (index == 0) null else items[index])
                dialog.dismiss()
            }
            .show()
    }
}
