package com.cloudx.databridge

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.addTextChangedListener
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.appcompat.app.AlertDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MemoryFragment : Fragment() {

    private lateinit var infoFixedContainer: LinearLayout
    private lateinit var tvFixedInfo: TextView
    private lateinit var etParcel: EditText
    private lateinit var etParcelAssigned: EditText
    private lateinit var etDoc: EditText
    private lateinit var etDocAssigned: EditText
    private lateinit var btnSave: Button
    private lateinit var tvTodayTotal: TextView
    private lateinit var tvFilters: TextView
    private lateinit var rvList: RecyclerView
    private lateinit var tvSum: TextView
    private lateinit var tvAvg: TextView
    private lateinit var tvMaxMin: TextView
    private lateinit var tvSuccessAvg: TextView
    private lateinit var tvSuccessMax: TextView
    private lateinit var tvSuccessMin: TextView
    private lateinit var tvDeliveryCount: TextView
    private lateinit var tvPickupCount: TextView
    private lateinit var rowSummaryHeader: LinearLayout
    private lateinit var layoutSummaryContent: LinearLayout
    private lateinit var ivSummaryArrow: ImageView
    private var summaryExpanded = true
    private lateinit var btnDateFrom: Button
    private lateinit var btnDateTo: Button
    private lateinit var btnClearDateFrom: ImageButton
    private lateinit var btnClearDateTo: ImageButton
    private lateinit var btnEntryDate: Button
    private lateinit var tvFormPreview: TextView
    private lateinit var tvError: TextView
    private lateinit var layoutForm: LinearLayout
    private lateinit var btnToggleForm: ImageButton
    private lateinit var btnTogglePickup: ImageButton
    private lateinit var layoutPickupSection: LinearLayout
    private lateinit var etParcelPickup: EditText
    private lateinit var etParcelPickupAssigned: EditText
    private lateinit var etDocPickup: EditText
    private lateinit var etDocPickupAssigned: EditText
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var formDialog: AlertDialog? = null

    private val db = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val entries = mutableListOf<MemoryEntry>()
    private lateinit var adapter: MemoryAdapter

    private var editingEntryId: String? = null
    private var hasSalaryModelConfig: Boolean = true
    private var isFormValid: Boolean = false

    private var salaryType = "variable"
    private var agentType = "delivery_agents"
    private var salaryModel = ""
    private var fixedAmount = ""
    private var modelConfig = SalaryModelConfig()
    private val fmtDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private var filterFrom: Long = 0L
    private var filterTo: Long = 0L
    private var entryDateMillis: Long = 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_memory, container, false)
    }

    private fun pickEntryDateForEntry(showToastOnCancel: Boolean = true) {
        val cal = Calendar.getInstance()
        if (entryDateMillis > 0) cal.timeInMillis = entryDateMillis
        DatePickerDialog(requireContext(), { _, y, m, d ->
            val now = Calendar.getInstance()
            cal.set(y, m, d, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND))
            entryDateMillis = cal.timeInMillis
            btnSave.text = "Save Entry"
            updateEntryDateUi()
            recalcPreview()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun setEntryDateToToday() {
        entryDateMillis = System.currentTimeMillis()
    }

    private fun updateEntryDateUi() {
        val label = if (entryDateMillis > 0) fmtDate.format(Date(entryDateMillis)) else "Pick a date"
        btnEntryDate.text = label
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        infoFixedContainer = view.findViewById(R.id.layoutFixedInfo)
        tvFixedInfo       = view.findViewById(R.id.tvFixedInfo)
        etParcel          = view.findViewById(R.id.etParcelCount)
        etParcelAssigned  = view.findViewById(R.id.etParcelAssigned)
        etDoc             = view.findViewById(R.id.etDocCount)
        etDocAssigned     = view.findViewById(R.id.etDocAssigned)
        etParcelPickup    = view.findViewById(R.id.etParcelPickup)
        etParcelPickupAssigned = view.findViewById(R.id.etParcelPickupAssigned)
        etDocPickup       = view.findViewById(R.id.etDocPickup)
        etDocPickupAssigned = view.findViewById(R.id.etDocPickupAssigned)
        btnSave           = view.findViewById(R.id.btnSaveMemory)
        tvTodayTotal      = view.findViewById(R.id.tvTodayTotal)
        tvFilters         = view.findViewById(R.id.tvFilters)
        rvList            = view.findViewById(R.id.rvMemory)
        tvSum             = view.findViewById(R.id.tvMemorySum)
        tvAvg             = view.findViewById(R.id.tvMemoryAvg)
        tvMaxMin          = view.findViewById(R.id.tvMemoryMaxMin)
        tvSuccessAvg      = view.findViewById(R.id.tvSuccessAvg)
        tvSuccessMax      = view.findViewById(R.id.tvSuccessMax)
        tvSuccessMin      = view.findViewById(R.id.tvSuccessMin)
        tvDeliveryCount   = view.findViewById(R.id.tvMemoryDeliveryCount)
        tvPickupCount     = view.findViewById(R.id.tvMemoryPickupCount)
        rowSummaryHeader  = view.findViewById(R.id.rowSummaryHeader)
        layoutSummaryContent = view.findViewById(R.id.layoutSummaryContent)
        ivSummaryArrow    = view.findViewById(R.id.ivSummaryArrow)
        rowSummaryHeader.setOnClickListener {
            summaryExpanded = !summaryExpanded
            layoutSummaryContent.visibility = if (summaryExpanded) View.VISIBLE else View.GONE
            ivSummaryArrow.rotation = if (summaryExpanded) 0f else 180f
        }
        btnDateFrom       = view.findViewById(R.id.btnDateFrom)
        btnDateTo         = view.findViewById(R.id.btnDateTo)
        btnClearDateFrom  = view.findViewById(R.id.btnClearDateFrom)
        btnClearDateTo    = view.findViewById(R.id.btnClearDateTo)
        btnEntryDate      = view.findViewById(R.id.btnEntryDate)
        tvFormPreview     = view.findViewById(R.id.tvFormPreview)
        tvError           = view.findViewById(R.id.tvMemoryError)
        layoutForm        = view.findViewById(R.id.layoutMemoryForm)
        btnToggleForm     = view.findViewById(R.id.btnToggleForm)
        layoutPickupSection = view.findViewById(R.id.layoutPickupSection)
        btnTogglePickup   = view.findViewById(R.id.btnTogglePickup)
        swipeRefresh      = view.findViewById(R.id.swipeMemory)

        adapter = MemoryAdapter(entries, onDelete = { deleteEntry(it) }, onEdit = { startEditEntry(it) }, onView = { showEntryDetails(it) })
        rvList.layoutManager = LinearLayoutManager(requireContext())
        rvList.adapter = adapter

        btnSave.setOnClickListener { saveEntry() }
        btnDateFrom.setOnClickListener { pickDate(true) }
        btnDateTo.setOnClickListener { pickDate(false) }
        btnClearDateFrom.setOnClickListener {
            filterFrom = 0L
            updateFilterText()
            loadEntries()
        }
        btnClearDateTo.setOnClickListener {
            filterTo = 0L
            updateFilterText()
            loadEntries()
        }
        btnEntryDate.setOnClickListener { pickEntryDateForEntry(showToastOnCancel = false) }
        btnToggleForm.setOnClickListener { toggleFormVisibility() }
        btnTogglePickup.setOnClickListener { togglePickup() }
        swipeRefresh.setOnRefreshListener { loadEntries() }

        loadProfileAndConfig()
        updateFilterText()
        updateEntryDateUi()
        attachPreviewWatchers()
        recalcPreview()
    }

    private fun toggleFormVisibility() {
        // If already showing, close it
        if (formDialog?.isShowing == true) {
            formDialog?.dismiss()
            return
        }

        val parent = layoutForm.parent as? ViewGroup ?: return
        val indexInParent = parent.indexOfChild(layoutForm)
        parent.removeView(layoutForm)
        layoutForm.visibility = View.VISIBLE

        if (entryDateMillis == 0L) setEntryDateToToday()
        updateEntryDateUi()
        if (etDoc.text.toString().isBlank() && hasSalaryModelConfig) {
            etDoc.hint = "Doc delivery (৳${modelConfig.documentDeliveryRate}/each)"
        }
        if (etDocPickup.text.toString().isBlank() && hasSalaryModelConfig) {
            etDocPickup.hint = "Doc pickup (৳${modelConfig.documentPickupRate}/each)"
        }
        // collapse pickup by default when opening
        layoutPickupSection.visibility = View.GONE
        btnTogglePickup.setImageResource(android.R.drawable.ic_input_add)
        recalcPreview()

        val container = ScrollView(requireContext()).apply {
            isFillViewport = true
            addView(layoutForm, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        formDialog = AlertDialog.Builder(requireContext())
            .setTitle(if (editingEntryId == null) "Add Entry" else "Edit Entry")
            .setView(container)
            .setOnDismissListener {
                // restore form to parent hidden
                container.removeView(layoutForm)
                layoutForm.visibility = View.GONE
                if (indexInParent >= 0 && indexInParent <= parent.childCount) parent.addView(layoutForm, indexInParent)
                btnToggleForm.setImageResource(android.R.drawable.ic_menu_add)
                if (editingEntryId == null) {
                    entryDateMillis = 0L
                    btnSave.text = "Save Entry"
                    clearForm()
                }
            }
            .create()
        btnToggleForm.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        formDialog?.show()
    }

    private fun togglePickup() {
        if (layoutPickupSection.visibility == View.VISIBLE) {
            layoutPickupSection.visibility = View.GONE
            btnTogglePickup.setImageResource(android.R.drawable.ic_input_add)
        } else {
            layoutPickupSection.visibility = View.VISIBLE
            btnTogglePickup.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        }
    }

    private fun pickDate(isFrom: Boolean) {
        val cal = Calendar.getInstance()
        DatePickerDialog(requireContext(), { _, y, m, d ->
            cal.set(y, m, d, 0, 0, 0)
            if (isFrom) filterFrom = cal.timeInMillis else filterTo = cal.timeInMillis + 86_399_000
            updateFilterText()
            loadEntries()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun attachPreviewWatchers() {
        val watcher: (android.text.Editable?) -> Unit = { recalcPreview() }
        etParcel.addTextChangedListener(afterTextChanged = watcher)
        etParcelAssigned.addTextChangedListener(afterTextChanged = watcher)
        etDoc.addTextChangedListener(afterTextChanged = watcher)
        etDocAssigned.addTextChangedListener(afterTextChanged = watcher)
    }

    private fun updateFilterText() {
        val from = if (filterFrom > 0) fmtDate.format(Date(filterFrom)) else "Any"
        val to   = if (filterTo > 0) fmtDate.format(Date(filterTo)) else "Any"
        tvFilters.text = "Filters: $from → $to"
        updateDateButtons()
    }

    private fun updateDateButtons() {
        val accentBg = ContextCompat.getColorStateList(requireContext(), R.color.theme_btn_accent_bg)
        val normalBg = ContextCompat.getColorStateList(requireContext(), R.color.theme_bg_card)
        val accentText = ContextCompat.getColor(requireContext(), R.color.theme_btn_accent_text)
        val normalText = ContextCompat.getColor(requireContext(), R.color.theme_text_primary)

        val fromLabel = if (filterFrom > 0) fmtDate.format(Date(filterFrom)) else "From"
        val toLabel   = if (filterTo > 0) fmtDate.format(Date(filterTo)) else "To"

        btnDateFrom.text = fromLabel
        btnDateFrom.backgroundTintList = if (filterFrom > 0) accentBg else normalBg
        btnDateFrom.setTextColor(if (filterFrom > 0) accentText else normalText)
        btnClearDateFrom.visibility = if (filterFrom > 0) View.VISIBLE else View.GONE

        btnDateTo.text = toLabel
        btnDateTo.backgroundTintList = if (filterTo > 0) accentBg else normalBg
        btnDateTo.setTextColor(if (filterTo > 0) accentText else normalText)
        btnClearDateTo.visibility = if (filterTo > 0) View.VISIBLE else View.GONE
    }

    private fun slabRateForDelivered(parcelDelivered: Int): Double {
        if (parcelDelivered <= 0) return 0.0
        val sorted = modelConfig.slabs.sortedBy { it.min }
        val slab = sorted.firstOrNull { s ->
            val max = if (s.max <= 0) Int.MAX_VALUE else s.max
            parcelDelivered in s.min..max
        } ?: sorted.lastOrNull()
        return slab?.rate ?: 0.0
    }

    private fun slabRateForPickup(parcelPicked: Int): Double {
        if (parcelPicked <= 0) return 0.0
        val sorted = modelConfig.pickupSlabs.sortedBy { it.min }
        val slab = sorted.firstOrNull { s ->
            val max = if (s.max <= 0) Int.MAX_VALUE else s.max
            parcelPicked in s.min..max
        } ?: sorted.lastOrNull()
        return slab?.rate ?: 0.0
    }

    private fun recalcPreview() {
        val parcelAssignedStr = etParcelAssigned.text.toString()
        val parcelDeliveredStr = etParcel.text.toString()
        val docAssignedStr = etDocAssigned.text.toString()
        val docDeliveredStr = etDoc.text.toString()
        val parcelPickupAssignedStr = etParcelPickupAssigned.text.toString()
        val parcelPickupStr = etParcelPickup.text.toString()
        val docPickupAssignedStr = etDocPickupAssigned.text.toString()
        val docPickupStr = etDocPickup.text.toString()

        val parcelFilled = parcelAssignedStr.isNotBlank() || parcelDeliveredStr.isNotBlank()
        val docFilled = docAssignedStr.isNotBlank() || docDeliveredStr.isNotBlank()
        val parcelPickupFilled = parcelPickupAssignedStr.isNotBlank() || parcelPickupStr.isNotBlank()
        val docPickupFilled = docPickupAssignedStr.isNotBlank() || docPickupStr.isNotBlank()

        val parcelAssigned = parcelAssignedStr.toIntOrNull()
        val parcelDelivered = parcelDeliveredStr.toIntOrNull()
        val docAssigned = docAssignedStr.toIntOrNull()
        val docDelivered = docDeliveredStr.toIntOrNull()
        val parcelPickupAssigned = parcelPickupAssignedStr.toIntOrNull()
        val parcelPickup = parcelPickupStr.toIntOrNull()
        val docPickupAssigned = docPickupAssignedStr.toIntOrNull()
        val docPickup = docPickupStr.toIntOrNull()

        isFormValid = false

        if (!parcelFilled && !docFilled && !parcelPickupFilled && !docPickupFilled) {
            btnSave.isEnabled = false
            tvFormPreview.text = "Enter delivery or pickup counts"
            etParcel.error = null; etDoc.error = null
            return
        }

        if (parcelFilled && (parcelAssigned == null || parcelDelivered == null)) {
            btnSave.isEnabled = false
            tvFormPreview.text = "Fill both parcel fields"
            etParcel.error = null; etDoc.error = null
            return
        }

        if (docFilled && (docAssigned == null || docDelivered == null)) {
            btnSave.isEnabled = false
            tvFormPreview.text = "Fill both document fields"
            etParcel.error = null; etDoc.error = null
            return
        }

        if (parcelPickupFilled && (parcelPickupAssigned == null || parcelPickup == null)) {
            btnSave.isEnabled = false
            tvFormPreview.text = "Fill both parcel pickup fields"
            etParcel.error = null; etDoc.error = null
            return
        }

        if (docPickupFilled && (docPickupAssigned == null || docPickup == null)) {
            btnSave.isEnabled = false
            tvFormPreview.text = "Fill both document pickup fields"
            etParcel.error = null; etDoc.error = null
            return
        }

        val parcelOk = !parcelFilled || (parcelDelivered!! >= 0 && parcelAssigned!! >= parcelDelivered)
        val docOk = !docFilled || (docDelivered!! >= 0 && docAssigned!! >= docDelivered)
        val parcelPickupOk = !parcelPickupFilled || (parcelPickup!! >= 0 && parcelPickupAssigned!! >= parcelPickup)
        val docPickupOk = !docPickupFilled || (docPickup!! >= 0 && docPickupAssigned!! >= docPickup)
        etParcel.error = if (parcelOk) null else "Delivered > Assigned"
        etDoc.error = if (docOk) null else "Delivered > Assigned"

        isFormValid = (parcelFilled && parcelOk) || (docFilled && docOk) || (parcelPickupFilled && parcelPickupOk) || (docPickupFilled && docPickupOk)
        val allSectionsOk = (!parcelFilled || parcelOk) && (!docFilled || docOk) && (!parcelPickupFilled || parcelPickupOk) && (!docPickupFilled || docPickupOk)
        btnSave.isEnabled = isFormValid && allSectionsOk
        if (!btnSave.isEnabled) {
            tvFormPreview.text = "Fix errors to preview"
            return
        }

        val parcelRate = if (parcelFilled && parcelAssigned!! > 0) parcelDelivered!!.toDouble() / parcelAssigned else 0.0
        val docRate = if (docFilled && docAssigned!! > 0) docDelivered!!.toDouble() / docAssigned else 0.0
        val parcelPickupRate = if (parcelPickupFilled && parcelPickupAssigned!! > 0) parcelPickup!!.toDouble() / parcelPickupAssigned else 0.0
        val docPickupRate = if (docPickupFilled && docPickupAssigned!! > 0) docPickup!!.toDouble() / docPickupAssigned else 0.0
        val parcelSuccessPct = (parcelRate * 100).coerceAtMost(100.0)
        val docSuccessPct = (docRate * 100).coerceAtMost(100.0)
        val parcelPickupSuccessPct = (parcelPickupRate * 100).coerceAtMost(100.0)
        val docPickupSuccessPct = (docPickupRate * 100).coerceAtMost(100.0)

        if (!hasSalaryModelConfig) {
            val preview = buildString {
                append("Parcel: $parcelDelivered/$parcelAssigned • " + parcelSuccessPct.toInt() + "%")
                append("\nDoc: $docDelivered/$docAssigned • " + docSuccessPct.toInt() + "%")
                append("\nCommission unavailable (salary model missing)")
            }
            tvFormPreview.text = preview
            return
        }

        val parcelDeliveredVal = if (parcelFilled) parcelDelivered!! else 0
        val docDeliveredVal = if (docFilled) docDelivered!! else 0
        val parcelPickupVal = if (parcelPickupFilled) parcelPickup!! else 0
        val docPickupVal = if (docPickupFilled) docPickup!! else 0
        
        // For fixed salary, commission is ৳0
        if (salaryType == "fixed") {
            val preview = buildString {
                if (parcelFilled) {
                    append("Parcel: ${parcelDelivered}/${parcelAssigned} • " + parcelSuccessPct.toInt() + "%")
                }
                if (docFilled) {
                    if (parcelFilled) append("\n")
                    append("Doc: ${docDelivered}/${docAssigned} • " + docSuccessPct.toInt() + "%")
                }
                append("\n(Fixed salary - Commission: ৳0)")
            }
            tvFormPreview.text = preview
            return
        }
        
        val parcelRateUsed = slabRateForDelivered(parcelDeliveredVal)
        val parcelPickupRateUsed = slabRateForPickup(parcelPickupVal)
        val parcelCommission = parcelDeliveredVal * parcelRateUsed
        val parcelPickupCommission = parcelPickupVal * parcelPickupRateUsed
        val docCommission = docDeliveredVal * modelConfig.documentDeliveryRate
        val docPickupCommission = docPickupVal * modelConfig.documentPickupRate
        val total = parcelCommission + parcelPickupCommission + docCommission + docPickupCommission

        val preview = buildString {
            if (parcelFilled) {
                append("Parcel Delivery: ${parcelDelivered}/${parcelAssigned} • " + parcelSuccessPct.toInt() + "%")
                if (hasSalaryModelConfig) append(" • ৳$parcelRateUsed/each = ৳" + parcelCommission.toInt())
            }
            if (docFilled) {
                if (parcelFilled) append("\n")
                append("Doc Delivery: ${docDelivered}/${docAssigned} • " + docSuccessPct.toInt() + "%")
                if (hasSalaryModelConfig) append(" • ৳${modelConfig.documentDeliveryRate}/each = ৳" + docCommission.toInt())
            }
            if (parcelPickupFilled || docPickupFilled) {
                if (parcelFilled || docFilled) append("\n")
                if (parcelPickupFilled) {
                    append("Parcel Pickup: ${parcelPickup}/${parcelPickupAssigned} • " + parcelPickupSuccessPct.toInt() + "%")
                    if (hasSalaryModelConfig) append(" • ৳$parcelPickupRateUsed/each = ৳" + parcelPickupCommission.toInt())
                }
                if (docPickupFilled) {
                    if (parcelPickupFilled) append("\n")
                    append("Doc Pickup: ${docPickup}/${docPickupAssigned} • " + docPickupSuccessPct.toInt() + "%")
                    if (hasSalaryModelConfig) append(" • ৳${modelConfig.documentPickupRate}/each = ৳" + docPickupCommission.toInt())
                }
            }
            if (hasSalaryModelConfig) {
                append("\nTotal: ৳" + total.toInt())
            } else {
                append("\nCommission unavailable (salary model missing)")
            }
        }
        tvFormPreview.text = preview
    }

    private fun loadProfileAndConfig() {
        val uid = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            var lastError: String? = null
            hasSalaryModelConfig = true
            try {
                val profileSnap = db.reference.child("users/$uid/profile/company_info").get().await()
                salaryType = profileSnap.child("salary_type").getValue(String::class.java) ?: "variable"
                agentType = profileSnap.child("agent_type").getValue(String::class.java) ?: "delivery_agents"
                salaryModel = profileSnap.child("salary_model").getValue(String::class.java) ?: ""
                if (salaryModel.isBlank()) hasSalaryModelConfig = false
                fixedAmount = profileSnap.child("fixed_amount").getValue(String::class.java) ?: ""
                android.util.Log.d("MemoryFrag", "Loaded profile: type=$salaryType, agent=$agentType, model=$salaryModel")
            } catch (e: Exception) {
                lastError = e.message
                android.util.Log.e("MemoryFrag", "Profile load error: ${e.message}", e)
                hasSalaryModelConfig = false
            }

            if (hasSalaryModelConfig && salaryModel.isNotBlank()) {
                try {
                    val path = "salaries/$agentType/commission_models/$salaryModel"
                    val cfgSnap = db.reference.child(path).get().await()
                    if (cfgSnap.exists()) {
                        modelConfig = cfgSnap.toSalaryModelConfig(salaryModel)
                        android.util.Log.d("MemoryFrag", "Loaded config from $path: ${modelConfig.name}, slabs=${modelConfig.slabs.size}, docDelivery=${modelConfig.documentDeliveryRate}, docPickup=${modelConfig.documentPickupRate}")
                    } else {
                        lastError = "Config path not found: $path (agent=$agentType, model=$salaryModel)"
                        android.util.Log.w("MemoryFrag", lastError)
                        hasSalaryModelConfig = false
                    }
                } catch (e: Exception) {
                    lastError = "Error loading config: ${e.message}"
                    android.util.Log.e("MemoryFrag", lastError, e)
                    hasSalaryModelConfig = false
                }
            } else {
                modelConfig = SalaryModelConfig()
            }

            if (salaryType == "fixed") {
                infoFixedContainer.visibility = View.VISIBLE
                tvFixedInfo.text = "Fixed salary (monthly): ৳${fixedAmount.ifBlank { "0" }}"
                // Keep fields enabled so user can still enter delivery counts (commission will be ৳0)
                etParcel.isEnabled = true
                etDoc.isEnabled = true
            } else {
                infoFixedContainer.visibility = View.GONE
            }

            loadEntries()
            if (!hasSalaryModelConfig) {
                val msg = lastError ?: "Salary model missing; commissions will be 0"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                showError(msg)
            } else if (lastError != null) {
                Toast.makeText(requireContext(), "Some data failed: ${lastError}", Toast.LENGTH_SHORT).show()
                showError(lastError)
            }
        }
    }

    private fun loadEntries() {
        val uid = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                val snap = db.reference.child("memory/$uid/earnings").get().await()
                entries.clear()
                snap.children.forEach { c ->
                    val entry = c.getValue(MemoryEntry::class.java)?.copy(id = c.key ?: "") ?: return@forEach
                    if (passFilter(entry.createdAt)) entries.add(entry)
                }
                entries.sortByDescending { it.createdAt }
                adapter.refresh()
                updateSum()
                showError(null)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Load failed: ${e.message}", Toast.LENGTH_SHORT).show()
                showError(e.message)
            }
            swipeRefresh.isRefreshing = false
        }
    }

    private fun passFilter(ts: Long): Boolean {
        val afterFrom = filterFrom == 0L || ts >= filterFrom
        val beforeTo = filterTo == 0L || ts <= filterTo
        return afterFrom && beforeTo
    }

    private fun saveEntry() {
        val isFixed = salaryType == "fixed"
        val parcel = etParcel.text.toString().toIntOrNull() ?: 0
        val parcelAssigned = etParcelAssigned.text.toString().toIntOrNull() ?: 0
        val doc = etDoc.text.toString().toIntOrNull() ?: 0
        val docAssigned = etDocAssigned.text.toString().toIntOrNull() ?: 0
        val parcelPickup = etParcelPickup.text.toString().toIntOrNull() ?: 0
        val parcelPickupAssigned = etParcelPickupAssigned.text.toString().toIntOrNull() ?: 0
        val docPickup = etDocPickup.text.toString().toIntOrNull() ?: 0
        val docPickupAssigned = etDocPickupAssigned.text.toString().toIntOrNull() ?: 0
        val ts = if (entryDateMillis > 0) entryDateMillis else System.currentTimeMillis()
        val parcelRateUsed = if (!isFixed && hasSalaryModelConfig) slabRateForDelivered(parcel) else 0.0
        val parcelPickupRateUsed = if (!isFixed && hasSalaryModelConfig) slabRateForPickup(parcelPickup) else 0.0
        val parcelComm = if (!isFixed && hasSalaryModelConfig) parcel * parcelRateUsed else 0.0
        val parcelPickupComm = if (!isFixed && hasSalaryModelConfig) parcelPickup * parcelPickupRateUsed else 0.0
        val docComm = if (!isFixed && hasSalaryModelConfig) doc * modelConfig.documentDeliveryRate else 0.0
        val docPickupComm = if (!isFixed && hasSalaryModelConfig) docPickup * modelConfig.documentPickupRate else 0.0
        val parcelRate = if (parcelAssigned > 0) parcel.toDouble() / parcelAssigned else 0.0
        val docRate = if (docAssigned > 0) doc.toDouble() / docAssigned else 0.0
        val parcelPickupRate = if (parcelPickupAssigned > 0) parcelPickup.toDouble() / parcelPickupAssigned else 0.0
        val docPickupRate = if (docPickupAssigned > 0) docPickup.toDouble() / docPickupAssigned else 0.0
        val uid = auth.currentUser?.uid ?: return
        val payload = mapOf(
            "parcelDelivery" to parcel,
            "documentDelivery" to doc,
            "parcelPickup" to parcelPickup,
            "documentPickup" to docPickup,
            "parcelAssigned" to parcelAssigned,
            "documentAssigned" to docAssigned,
            "parcelPickupAssigned" to parcelPickupAssigned,
            "documentPickupAssigned" to docPickupAssigned,
            "parcelCommission" to parcelComm,
            "documentCommission" to docComm,
            "parcelPickupCommission" to parcelPickupComm,
            "documentPickupCommission" to docPickupComm,
            "createdAt" to ts,
            "model" to if (isFixed) "fixed" else salaryModel,
            "parcelSuccessRate" to parcelRate,
            "documentSuccessRate" to docRate,
            "parcelPickupSuccessRate" to parcelPickupRate,
            "documentPickupSuccessRate" to docPickupRate
        )
        lifecycleScope.launch {
            try {
                val targetId = editingEntryId ?: "earning_${System.currentTimeMillis()}"
                db.reference.child("memory/$uid/earnings/$targetId").setValue(payload).await()
                Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show()
                clearForm()
                layoutForm.visibility = View.GONE
                btnToggleForm.setImageResource(android.R.drawable.ic_menu_add)
                entryDateMillis = 0L
                btnSave.text = "Save Entry"
                editingEntryId = null
                tvFormPreview.text = ""
                formDialog?.dismiss()
                loadEntries()
                showError(null)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                showError(e.message)
            }
        }
    }

    private fun startEditEntry(entry: MemoryEntry) {
        layoutForm.visibility = View.VISIBLE
        btnToggleForm.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        editingEntryId = entry.id
        entryDateMillis = entry.createdAt
        etParcel.setText(entry.parcelDelivery.toString())
        etParcelAssigned.setText(entry.parcelAssigned.toString())
        etDoc.setText(entry.documentDelivery.toString())
        etDocAssigned.setText(entry.documentAssigned.toString())
        etParcelPickup.setText(entry.parcelPickup.toString())
        etParcelPickupAssigned.setText(entry.parcelPickupAssigned.toString())
        etDocPickup.setText(entry.documentPickup.toString())
        etDocPickupAssigned.setText(entry.documentPickupAssigned.toString())
        if (entry.parcelPickup > 0 || entry.documentPickup > 0 || entry.parcelPickupAssigned > 0 || entry.documentPickupAssigned > 0) {
            layoutPickupSection.visibility = View.VISIBLE
            btnTogglePickup.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        } else {
            layoutPickupSection.visibility = View.GONE
            btnTogglePickup.setImageResource(android.R.drawable.ic_input_add)
        }
        updateEntryDateUi()
        recalcPreview()
        btnSave.text = "Update Entry"
        if (formDialog?.isShowing != true) toggleFormVisibility()
    }

    private fun clearForm() {
        etParcel.text.clear(); etDoc.text.clear(); etParcelAssigned.text.clear(); etDocAssigned.text.clear()
        etParcelPickup.text.clear(); etParcelPickupAssigned.text.clear(); etDocPickup.text.clear(); etDocPickupAssigned.text.clear()
    }

    private fun deleteEntry(entry: MemoryEntry) {
        val uid = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                db.reference.child("memory/$uid/earnings/${entry.id}").removeValue().await()
                entries.remove(entry)
                adapter.refresh()
                updateSum()
                showError(null)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                showError(e.message)
            }
        }
    }

    private fun showEntryDetails(entry: MemoryEntry) {
        val dateText = SimpleDateFormat("dd-MMM-yy, EEE h:mm a", Locale.getDefault()).format(Date(entry.createdAt))
        val total = entry.parcelCommission + entry.documentCommission + entry.parcelPickupCommission + entry.documentPickupCommission
        val msg = buildString {
            append("Date: $dateText")
            append("\nModel: ${entry.model}")
            append("\nDelivery: Parcel ${entry.parcelDelivery}/${entry.parcelAssigned} (${(entry.parcelSuccessRate * 100).toInt()}%)")
            append("\nDelivery: Doc ${entry.documentDelivery}/${entry.documentAssigned} (${(entry.documentSuccessRate * 100).toInt()}%)")
            append("\nPickup: Parcel ${entry.parcelPickup}/${entry.parcelPickupAssigned} (${(entry.parcelPickupSuccessRate * 100).toInt()}%)")
            append("\nPickup: Doc ${entry.documentPickup}/${entry.documentPickupAssigned} (${(entry.documentPickupSuccessRate * 100).toInt()}%)")
            append("\n\nEarnings")
            append("\nDelivery: ৳${(entry.parcelCommission + entry.documentCommission).toInt()}")
            append("\nPickup: ৳${(entry.parcelPickupCommission + entry.documentPickupCommission).toInt()}")
            append("\nTotal: ৳${total.toInt()}")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Entry details")
            .setMessage(msg)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showError(message: String?) {
        if (message.isNullOrBlank()) {
            tvError.visibility = View.GONE
            tvError.text = ""
        } else {
            tvError.visibility = View.VISIBLE
            tvError.text = message
        }
    }

    private fun updateSum() {
        val sums = entries.map { it.parcelCommission + it.documentCommission + it.parcelPickupCommission + it.documentPickupCommission }
        val sum = sums.sum()
        val avg = if (sums.isNotEmpty()) sum / sums.size else 0.0
        val max = sums.maxOrNull() ?: 0.0
        val min = sums.minOrNull() ?: 0.0

        tvSum.text = "৳${sum.toInt()}"
        tvAvg.text = "৳${avg.toInt()}"
        tvMaxMin.text = "৳${max.toInt()} / ৳${min.toInt()}"

        val successRates = entries.mapNotNull {
            val p = it.parcelSuccessRate.takeIf { it > 0 }
            val d = it.documentSuccessRate.takeIf { it > 0 }
            val pp = it.parcelPickupSuccessRate.takeIf { it > 0 }
            val dp = it.documentPickupSuccessRate.takeIf { it > 0 }
            listOfNotNull(p, d, pp, dp)
        }.flatten()
        val sAvg = if (successRates.isNotEmpty()) successRates.average() * 100 else 0.0
        val sMax = (successRates.maxOrNull() ?: 0.0) * 100
        val sMin = (successRates.minOrNull() ?: 0.0) * 100

        tvSuccessAvg.text = "${sAvg.toInt()}%"
        tvSuccessMax.text = "${sMax.toInt()}%"
        tvSuccessMin.text = "${sMin.toInt()}%"

        val totalDelivery = entries.sumOf { it.parcelDelivery + it.documentDelivery }
        val totalPickup = entries.sumOf { it.parcelPickup + it.documentPickup }
        tvDeliveryCount.text = "$totalDelivery"
        tvPickupCount.text = "$totalPickup"

        val todayKey = fmtDate.format(Date())
        val todaySum = entries.filter { fmtDate.format(Date(it.createdAt)) == todayKey }
            .sumOf { it.parcelCommission + it.documentCommission + it.parcelPickupCommission + it.documentPickupCommission }
        tvTodayTotal.text = "Today: ৳${todaySum.toInt()}"
    }
}

