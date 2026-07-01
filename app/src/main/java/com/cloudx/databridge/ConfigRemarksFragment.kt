package com.cloudx.databridge

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ktx.getValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 💬 Remarks Config Tab
 * JSX equivalent: RemarksConfig component
 *
 * Features:
 *  - Status chips (filtered by priority, only show statuses that have remarks + the active one)
 *  - List of remarks per status with target-status reassign + delete
 *  - Add new remark form (বাংলা + English + target group)
 *  - Auto-save to Firebase: config/remarks/{statusKey}[]/
 */
class ConfigRemarksFragment : Fragment() {

    private val db = FirebaseDatabase.getInstance()

    // ── State (mirrors JSX useState) ──────────────────────────────────────────
    private var statuses: List<String>                        = ConfigState.statuses
    private var statusMeta: Map<String, ConfigState.StatusMeta> = ConfigState.statusMeta
    private var remarks: MutableMap<String, MutableList<ConfigState.Remark>> = ConfigState.remarks
    private var activeStatus: String = statuses.firstOrNull { (remarks[it]?.size ?: 0) > 0 } ?: statuses.firstOrNull() ?: "DELIVERED"

    // Guard: true while we're programmatically setting spinner selection
    private var isProgrammaticSelection = false

    // ── Root views ────────────────────────────────────────────────────────────
    private lateinit var chipGroup:      LinearLayout
    private lateinit var remarksList:    LinearLayout
    private lateinit var tvEmpty:        TextView
    private lateinit var etBn:           EditText
    private lateinit var etEn:           EditText
    private lateinit var spinnerTarget:  Spinner
    private lateinit var btnAdd:         Button
    private lateinit var btnOpenCreate:  TextView
    private lateinit var busyOverlay:    View
    private lateinit var tvBusy:         TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_config_remarks, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chipGroup     = view.findViewById(R.id.chipGroupStatuses)
        remarksList   = view.findViewById(R.id.remarksListContainer)
        tvEmpty       = view.findViewById(R.id.tvRemarksEmpty)
        etBn          = view.findViewById(R.id.etRemarkBn)
        etEn          = view.findViewById(R.id.etRemarkEn)
        spinnerTarget = view.findViewById(R.id.spinnerTargetStatus)
        btnAdd        = view.findViewById(R.id.btnAddRemark)
        btnOpenCreate = view.findViewById(R.id.btnOpenCreateRemark)
        busyOverlay   = view.findViewById(R.id.remarksBusyOverlay)
        tvBusy        = view.findViewById(R.id.tvRemarksBusy)

        // Load from Firebase then bind
        loadFromFirebase()

        btnAdd.setOnClickListener { handleAdd() }
        btnOpenCreate.setOnClickListener { openCreateDialog() }
    }

    // ── Firebase load ─────────────────────────────────────────────────────────
    private fun loadFromFirebase() {
        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Loading...")
            val loaded = reloadConfig()
            if (!loaded) Toast.makeText(requireContext(), "Remarks load failed", Toast.LENGTH_LONG).show()
            if (isAdded) {
                bindAll()
                setBusy(false)
            }
        }
    }

    private suspend fun reloadConfig(): Boolean =
        try {
            loadStatusMeta()
            loadRemarks()
            true
        } catch (e: Exception) {
            Log.e("ConfigRemarks", "Failed to load config", e)
            false
        }

    private suspend fun loadStatusMeta() {
        val snap = db.reference.child("config/statusMeta").get().await()
        val loaded = mutableMapOf<String, ConfigState.StatusMeta>()
        val loadedStatuses = mutableListOf<String>()
        if (snap.exists()) {
            snap.children.forEach { s ->
                val key = s.key ?: return@forEach
                val bn = s.child("bn").getValue(String::class.java) ?: ""
                val en = s.child("en").getValue(String::class.java) ?: ""
                val color = s.child("color").getValue(String::class.java) ?: "#6B7280"
                val bg = s.child("bg").getValue(String::class.java) ?: "#F3F4F6"
                val pri = s.child("priority").getValue(Int::class.java) ?: 0
                loaded[key] = ConfigState.StatusMeta(bn, en, color, bg, pri, false)
                loadedStatuses.add(key)
            }
        }
        ConfigState.statuses = loadedStatuses
        ConfigState.statusMeta = loaded
        statuses = ConfigState.statuses
        statusMeta = ConfigState.statusMeta
    }

    private suspend fun loadRemarks() {
        val snap = db.reference.child("config/remarks").get().await()
        val loaded = mutableMapOf<String, MutableList<ConfigState.Remark>>()
        if (snap.exists()) {
            snap.children.forEach { statusSnap ->
                val key = statusSnap.key ?: return@forEach
                val list = mutableListOf<ConfigState.Remark>()
                statusSnap.children.forEach { r ->
                    val id = r.child("id").getValue(String::class.java) ?: uid()
                    val textBn = r.child("text_bn").getValue(String::class.java) ?: ""
                    val textEn = r.child("text_en").getValue(String::class.java) ?: ""
                    val targetStatus = r.child("target_status").getValue(String::class.java) ?: key
                    list.add(ConfigState.Remark(id, textBn, textEn, targetStatus))
                }
                if (list.isNotEmpty()) loaded[key] = list
            }
        }
        remarks = loaded
        ConfigState.remarks = remarks
        val firstWithRemarks = sortedStatuses().firstOrNull { (remarks[it]?.size ?: 0) > 0 }
        activeStatus = when {
            firstWithRemarks != null -> firstWithRemarks
            statuses.contains(activeStatus) -> activeStatus
            else -> ""
        }
    }

    // ── Bind UI ───────────────────────────────────────────────────────────────
    private fun bindAll() {
        bindStatusChips()
        bindSpinnerTarget()
        bindRemarksList()
    }

    private fun bindStatusChips() {
        chipGroup.removeAllViews()
        val sorted = sortedStatuses()
        sorted.forEach { s ->
            val count = remarks[s]?.size ?: 0
            val meta  = statusMeta[s] ?: return@forEach
            if (count == 0) return@forEach

            val chip = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_status_chip, chipGroup, false) as TextView
            chip.text  = "${meta.en} $count"
            val isActive = s == activeStatus
            chip.setBackgroundColor(if (isActive) parseColor(meta.bg)  else parseColor("#F9FAFB"))
            chip.setTextColor     (if (isActive) parseColor(meta.color) else parseColor("#9CA3AF"))
            chip.tag = s
            chip.setOnClickListener {
                activeStatus = s
                bindAll()
            }
            chipGroup.addView(chip)
        }
    }

    private fun bindSpinnerTarget() {
        val sorted = sortedStatuses()
        if (sorted.isEmpty()) {
            spinnerTarget.adapter = null
            return
        }
        val labels = sorted.map { statusMeta[it]?.en ?: it }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        spinnerTarget.adapter = adapter
        val idx = sorted.indexOf(activeStatus).coerceAtLeast(0)
        spinnerTarget.setSelection(idx)
    }

    private fun bindRemarksList() {
        remarksList.removeAllViews()
        val hasAnyRemarks = remarks.values.any { it.isNotEmpty() }
        if (!hasAnyRemarks) {
            tvEmpty.text = "No remarks found yet, please create."
            tvEmpty.visibility = View.VISIBLE
            return
        }
        val list = remarks[activeStatus] ?: emptyList()
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        list.forEach { r ->
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_remark_card, remarksList, false)

            row.findViewById<TextView>(R.id.tvRemarkBn).text = r.text_bn
            row.findViewById<TextView>(R.id.tvRemarkEn).text = r.text_en

            // Target status spinner on the card
            val sorted = sortedStatuses()
            val spinCard = row.findViewById<Spinner>(R.id.spinnerRemarkTarget)
            val labels   = sorted.map { statusMeta[it]?.en ?: it }
            spinCard.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
            val idx = sorted.indexOf(r.target_status).coerceAtLeast(0)
            spinCard.setSelection(idx)
            isProgrammaticSelection = true
            spinCard.setSelection(idx)
            spinCard.post { isProgrammaticSelection = false }
            spinCard.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (isProgrammaticSelection) return
                    val newTarget = sorted.getOrElse(pos) { r.target_status }
                    if (newTarget != r.target_status) handleTargetChange(activeStatus, r.id, newTarget)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

            row.findViewById<View>(R.id.btnEditRemark).setOnClickListener {
                openEditDialog(activeStatus, r)
            }

            row.findViewById<View>(R.id.btnDeleteRemark).setOnClickListener {
                handleDelete(activeStatus, r.id)
            }
            remarksList.addView(row)
        }
    }

    // ── Actions (mirrors JSX functions) ───────────────────────────────────────

    /** handleTargetChange: move remark to new target group */
    private fun handleTargetChange(group: String, id: String, newTarget: String) {
        val moved = remarks[group]?.find { it.id == id } ?: return
        remarks[group]?.removeAll { it.id == id }
        val updated = moved.copy(target_status = newTarget)
        remarks.getOrPut(newTarget) { mutableListOf() }.add(updated)
        ConfigState.remarks = remarks
        bindAll()
        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Changing...")
            if (saveRemarks()) {
                reloadConfig()
                bindAll()
                setBusy(false)
                Toast.makeText(requireContext(), "Changed", Toast.LENGTH_SHORT).show()
            } else {
                setBusy(false)
                Toast.makeText(requireContext(), "Remark move failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** handleDelete: remove remark from group */
    private fun handleDelete(group: String, id: String) {
        remarks[group]?.removeAll { it.id == id }
        ConfigState.remarks = remarks
        bindAll()
        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Deleting...")
            if (saveRemarks()) {
                reloadConfig()
                bindAll()
                setBusy(false)
                Toast.makeText(requireContext(), "Deleted", Toast.LENGTH_SHORT).show()
            } else {
                setBusy(false)
                Toast.makeText(requireContext(), "Remark delete failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** handleAdd: create new remark from form inputs */
    private fun handleAdd() {
        val bn = etBn.text.toString().trim()
        val en = etEn.text.toString().trim()
        if (bn.isEmpty() && en.isEmpty()) {
            Toast.makeText(requireContext(), "বাংলা বা English রিমার্ক দিন", Toast.LENGTH_SHORT).show()
            return
        }
        val sorted    = sortedStatuses()
        val targetIdx = spinnerTarget.selectedItemPosition
        val target    = sorted.getOrElse(targetIdx) { activeStatus }
        addRemark(bn, en, target)
        etBn.setText(""); etEn.setText("")
    }

    private fun openEditDialog(group: String, remark: com.cloudx.databridge.ConfigState.Remark) {
        val ctx = requireContext()
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        val etBnEdit = android.widget.EditText(ctx).apply {
            hint = "বাংলা রিমার্ক"
            setText(remark.text_bn)
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            textSize = 13f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val etEnEdit = android.widget.EditText(ctx).apply {
            hint = "English remark"
            setText(remark.text_en)
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            textSize = 13f
        }

        layout.addView(etBnEdit)
        layout.addView(etEnEdit)

        android.app.AlertDialog.Builder(ctx)
            .setTitle("Remark Edit করুন")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newBn = etBnEdit.text.toString().trim()
                val newEn = etEnEdit.text.toString().trim()
                if (newBn.isEmpty() && newEn.isEmpty()) {
                    Toast.makeText(ctx, "বাংলা বা English রিমার্ক দিন", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                handleEdit(group, remark.id, newBn, newEn)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleEdit(group: String, id: String, newBn: String, newEn: String) {
        val list = remarks[group] ?: return
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(text_bn = newBn, text_en = newEn)
        ConfigState.remarks = remarks
        bindAll()
        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Saving...")
            if (saveRemarks()) {
                reloadConfig(); bindAll(); setBusy(false)
                Toast.makeText(requireContext(), "✅ Updated", Toast.LENGTH_SHORT).show()
            } else {
                setBusy(false)
                Toast.makeText(requireContext(), "Update failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openCreateDialog() {
        val ctx = requireContext()
        fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
        val sorted = sortedStatuses()
        if (sorted.isEmpty()) {
            Toast.makeText(ctx, "আগে একটি status তৈরি করুন", Toast.LENGTH_SHORT).show()
            return
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
        }

        fun label(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 10f
            setTextColor(android.graphics.Color.parseColor("#9CA3AF"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        fun input(hint: String) = EditText(ctx).apply {
            this.hint = hint
            textSize = 13f
            minHeight = dp(46)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = resources.getDrawable(R.drawable.bg_input_rounded, ctx.theme)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(2) }
        }

        val bnInput = input("বাংলা টেক্সট...")
        val enInput = input("English text...")
        val spinner = Spinner(ctx).apply {
            minimumHeight = dp(46)
            background = resources.getDrawable(R.drawable.bg_input_rounded, ctx.theme)
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        spinner.adapter = ArrayAdapter(
            ctx,
            android.R.layout.simple_spinner_dropdown_item,
            sorted.map { statusMeta[it]?.en ?: it },
        )
        spinner.setSelection(sorted.indexOf(activeStatus).coerceAtLeast(0))

        content.addView(label("বাংলা"))
        content.addView(bnInput)
        content.addView(label("English"))
        content.addView(enInput)
        content.addView(label("Group"))
        content.addView(spinner)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("নতুন Remark")
            .setView(content)
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("Create", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val bn = bnInput.text.toString().trim()
                val en = enInput.text.toString().trim()
                val target = sorted.getOrElse(spinner.selectedItemPosition) { activeStatus }
                if (bn.isEmpty() && en.isEmpty()) {
                    bnInput.error = "বাংলা বা English রিমার্ক দিন"
                    enInput.error = "বাংলা বা English রিমার্ক দিন"
                } else {
                    dialog.dismiss()
                    addRemark(bn, en, target)
                }
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun addRemark(
        bn: String,
        en: String,
        target: String,
        onSuccess: () -> Unit = {},
    ) {
        val remark = ConfigState.Remark(
            id = uid(),
            text_bn = bn.ifEmpty { en },
            text_en = en.ifEmpty { bn },
            target_status = target,
        )
        remarks.getOrPut(target) { mutableListOf() }.add(remark)
        ConfigState.remarks = remarks
        activeStatus = target
        bindAll()

        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Creating...")
            if (saveRemarks()) {
                reloadConfig()
                bindAll()
                setBusy(false)
                Toast.makeText(requireContext(), "Created", Toast.LENGTH_SHORT).show()
                onSuccess()
            } else {
                remarks[target]?.removeAll { it.id == remark.id }
                ConfigState.remarks = remarks
                bindAll()
                setBusy(false)
                Toast.makeText(requireContext(), "Remark create failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** sortedStatuses: sort by priority desc (mirrors JSX sortedStatuses) */
    private fun sortedStatuses(): List<String> =
        statuses.sortedByDescending { statusMeta[it]?.priority ?: 0 }

    /** uid: random 6-char id (mirrors JSX uid()) */
    private fun uid(): String = (Math.random() * 36 * 36 * 36 * 36 * 36 * 36).toLong()
        .toString(36).padStart(6, '0').takeLast(6)

    private fun parseColor(hex: String): Int = android.graphics.Color.parseColor(hex)

    /** triggerSave: write remarks to Firebase */
    private fun triggerSave() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!saveRemarks()) {
                Toast.makeText(requireContext(), "Remark save failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun saveRemarks(): Boolean =
        try {
            val payload = mutableMapOf<String, Any>()
            remarks.forEach { (statusKey, list) ->
                if (list.isNotEmpty()) {
                    val rows = mutableMapOf<String, Any>()
                    list.forEachIndexed { i, r ->
                        rows["$i"] = mapOf(
                            "id"            to r.id,
                            "text_bn"       to r.text_bn,
                            "text_en"       to r.text_en,
                            "target_status" to r.target_status,
                        )
                    }
                    payload[statusKey] = rows
                }
            }
            db.reference.child("config/remarks").setValue(payload).await()
            true
        } catch (e: Exception) {
            Log.e("ConfigRemarks", "Failed to save remarks", e)
            false
        }

    private fun setBusy(show: Boolean, text: String = "Loading...") {
        if (!::busyOverlay.isInitialized) return
        tvBusy.text = text
        busyOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }
}
