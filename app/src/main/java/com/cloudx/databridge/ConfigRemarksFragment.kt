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
import org.json.JSONObject

/**
 * 💬 Remarks Config Tab
 * JSX equivalent: RemarksConfig component
 *
 * Features:
 *  - Status chips (filtered by priority, only show statuses that have remarks + the active one)
 *  - List of remarks per status with target-status reassign + delete
 *  - Add new remark form (বাংলা + English + target group)
 *
 * Remark options themselves live in Supabase (public.validation_remarks, one row
 * per option, source='WORKER'/'CC') — reached only through the remark-validations
 * Edge Function's admin_list_remarks/admin_upsert_remark/admin_delete_remark
 * actions (see SupabaseRemarkValidationWriter), which check the caller's
 * canAccessConfig permission server-side before writing. This moved off
 * Firebase's config/remarks_worker|remarks_call_center nodes; status metadata
 * (statuses/statusMeta) and WhatsApp templates are unrelated and still load
 * from Firebase via [db] below.
 */
class ConfigRemarksFragment : Fragment() {

    private val db = FirebaseDatabase.getInstance()

    // ── State (mirrors JSX useState) ──────────────────────────────────────────
    private var statuses: List<String>                        = ConfigState.statuses
    private var statusMeta: Map<String, ConfigState.StatusMeta> = ConfigState.statusMeta
    private var remarks: MutableMap<String, MutableList<ConfigState.Remark>> = ConfigState.remarks
    private var activeStatus: String = statuses.firstOrNull { (remarks[it]?.size ?: 0) > 0 } ?: statuses.firstOrNull() ?: "DELIVERED"

    // Which app's remarks are being managed — Worker and Call Center have separate,
    // independent remark lists (source='WORKER' vs source='CC' in validation_remarks)
    // so status-changing actions available to one role never leak into the other's picker.
    private enum class RemarkScope(val source: String) {
        WORKER("WORKER"),
        CALL_CENTER("CC")
    }
    private var activeScope: RemarkScope = RemarkScope.WORKER

    // Guard: true while we're programmatically setting spinner selection
    private var isProgrammaticSelection = false

    // ── Root views ────────────────────────────────────────────────────────────
    private lateinit var chipGroup:      LinearLayout
    private lateinit var remarksList:    LinearLayout
    private lateinit var tvEmpty:        TextView
    private lateinit var etBn:           EditText
    private lateinit var etEn:           EditText
    private lateinit var spinnerTarget:  Spinner
    private lateinit var spinnerInstructionType: Spinner
    private lateinit var etInstructionText: EditText
    private lateinit var btnAdd:         Button
    private lateinit var btnOpenCreate:  TextView
    private lateinit var tabWorker:      TextView
    private lateinit var tabCallCenter:  TextView
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
        spinnerInstructionType = view.findViewById(R.id.spinnerInstructionType)
        etInstructionText = view.findViewById(R.id.etInstructionText)
        btnAdd        = view.findViewById(R.id.btnAddRemark)
        btnOpenCreate = view.findViewById(R.id.btnOpenCreateRemark)
        tabWorker     = view.findViewById(R.id.tabRemarksWorker)
        tabCallCenter = view.findViewById(R.id.tabRemarksCallCenter)
        busyOverlay   = view.findViewById(R.id.remarksBusyOverlay)
        tvBusy        = view.findViewById(R.id.tvRemarksBusy)

        updateScopeTabStyles()
        tabWorker.setOnClickListener { switchScope(RemarkScope.WORKER) }
        tabCallCenter.setOnClickListener { switchScope(RemarkScope.CALL_CENTER) }

        setupInstructionTypeSpinner()

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
            loadWhatsAppTemplates()
            true
        } catch (e: Exception) {
            Log.e("ConfigRemarks", "Failed to load config", e)
            false
        }

    private suspend fun loadWhatsAppTemplates() {
        val snap = db.reference.child("config/whatsappTemplates").get().await()
        val loaded = mutableMapOf<String, ConfigState.WhatsAppTemplate>()
        if (snap.exists()) {
            snap.children.forEach { t ->
                val id   = t.key ?: return@forEach
                val name = t.child("name").getValue(String::class.java) ?: ""
                val body = t.child("body").getValue(String::class.java) ?: ""
                loaded[id] = ConfigState.WhatsAppTemplate(id, name, body)
            }
        }
        ConfigState.whatsappTemplates = loaded
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
                val sortOrder = s.child("sortOrder").getValue(Int::class.java) ?: 0
                loaded[key] = ConfigState.StatusMeta(bn, en, color, bg, pri, sortOrder, false)
                loadedStatuses.add(key)
            }
        }
        ConfigState.statuses = loadedStatuses
        ConfigState.statusMeta = loaded
        statuses = ConfigState.statuses
        statusMeta = ConfigState.statusMeta
    }

    private suspend fun loadRemarks() {
        val loaded = mutableMapOf<String, MutableList<ConfigState.Remark>>()
        when (val result = SupabaseRemarkValidationWriter.adminListRemarks(activeScope.source)) {
            is SupabaseRemarkValidationWriter.AdminResult.Ok -> {
                val arr = result.body.optJSONArray("remarks") ?: org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    val targetStatus = r.optString("target_status")
                    val remark = ConfigState.Remark(
                        id = r.optString("id"),
                        text_bn = r.optString("remarks_bn"),
                        text_en = r.optString("remarks_en"),
                        target_status = targetStatus,
                        template_id = r.optString("template_id"),
                        priority = r.optInt("priority", 0),
                        instruction_type = r.optString("instruction_type"),
                        instruction_text = r.optString("instruction_text"),
                        is_active = r.optBoolean("is_active", true),
                        category = r.optString("category"),
                    )
                    // Grouped by target_status for the status-chip picker, same shape
                    // bindStatusChips()/bindRemarksList() already expect — validation_remarks
                    // has no separate grouping concept (unlike Firebase's nested
                    // config/remarks_*/{statusKey}[] structure), target_status IS the group.
                    if (targetStatus.isNotBlank()) loaded.getOrPut(targetStatus) { mutableListOf() }.add(remark)
                }
            }
            is SupabaseRemarkValidationWriter.AdminResult.Err ->
                throw Exception(result.message)
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

    /** Switches between managing Worker vs Call Center remarks — reloads from the
     *  corresponding scoped Firebase node and re-binds the whole screen. */
    private fun switchScope(scope: RemarkScope) {
        if (scope == activeScope) return
        activeScope = scope
        updateScopeTabStyles()
        loadFromFirebase()
    }

    private fun updateScopeTabStyles() {
        if (!::tabWorker.isInitialized) return
        val ctx = requireContext()
        val activeBg   = ctx.getColor(R.color.theme_bg_card)
        val inactiveBg = android.graphics.Color.TRANSPARENT
        val activeText   = android.graphics.Color.parseColor("#E8380D")
        val inactiveText = ctx.getColor(R.color.theme_text_muted)

        tabWorker.setBackgroundColor(if (activeScope == RemarkScope.WORKER) activeBg else inactiveBg)
        tabWorker.setTextColor(if (activeScope == RemarkScope.WORKER) activeText else inactiveText)
        tabCallCenter.setBackgroundColor(if (activeScope == RemarkScope.CALL_CENTER) activeBg else inactiveBg)
        tabCallCenter.setTextColor(if (activeScope == RemarkScope.CALL_CENTER) activeText else inactiveText)
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
            chip.setBackgroundColor(if (isActive) parseColor(meta.bg)  else requireContext().getColor(R.color.theme_bg_inner))
            chip.setTextColor     (if (isActive) parseColor(meta.color) else requireContext().getColor(R.color.theme_text_muted))
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

    /** Static "None / On Hold / Return" instruction-type dropdown for the main add form —
     *  set up once (not re-bound in bindAll(), unlike spinnerTarget, since its option list
     *  never changes and re-binding would reset whatever the admin had selected mid-entry).
     *  Index 0 ("None") means no instruction attached; showing/hiding etInstructionText
     *  tracks whether anything other than None is selected. */
    private fun setupInstructionTypeSpinner() {
        val labels = listOf("None") + ConfigState.INSTRUCTION_TYPES.map { ConfigState.instructionTypeLabel(it) }
        spinnerInstructionType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        spinnerInstructionType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                etInstructionText.visibility = if (position == 0) View.GONE else View.VISIBLE
                if (position == 0) etInstructionText.setText("")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** selectedInstructionType(): "" for the "None" entry (index 0), else the
     *  ConfigState.INSTRUCTION_TYPES value at (position - 1). */
    private fun selectedInstructionType(): String {
        val position = spinnerInstructionType.selectedItemPosition
        if (position <= 0) return ""
        return ConfigState.INSTRUCTION_TYPES.getOrElse(position - 1) { "" }
    }

    private fun bindRemarksList() {
        remarksList.removeAllViews()
        val hasAnyRemarks = remarks.values.any { it.isNotEmpty() }
        if (!hasAnyRemarks) {
            tvEmpty.text = "No remarks found yet, please create."
            tvEmpty.visibility = View.VISIBLE
            return
        }
        // Sort by priority descending (higher number = higher priority = top)
        val list = (remarks[activeStatus] ?: emptyList()).sortedByDescending { it.priority }
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        list.forEach { r ->
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_remark_card, remarksList, false)

            row.findViewById<TextView>(R.id.tvRemarkBn).text = r.text_bn
            row.findViewById<TextView>(R.id.tvRemarkEn).text = r.text_en

            // Priority badge — show only when priority != 0
            val tvPriority = row.findViewById<TextView>(R.id.tvRemarkPriority)
            if (r.priority != 0) {
                tvPriority.text = "P${r.priority}"
                tvPriority.visibility = View.VISIBLE
            } else {
                tvPriority.visibility = View.GONE
            }

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
        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Changing...")
            val remark = JSONObject().put("target_status", newTarget)
            when (val result = SupabaseRemarkValidationWriter.adminUpsertRemark(activeScope.source, id, remark)) {
                is SupabaseRemarkValidationWriter.AdminResult.Ok -> {
                    activeStatus = newTarget
                    reloadConfig()
                    bindAll()
                    setBusy(false)
                    Toast.makeText(requireContext(), "Changed", Toast.LENGTH_SHORT).show()
                }
                is SupabaseRemarkValidationWriter.AdminResult.Err -> {
                    setBusy(false)
                    Toast.makeText(requireContext(), "Remark move failed: ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** handleDelete: remove remark from group */
    private fun handleDelete(group: String, id: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Deleting...")
            when (val result = SupabaseRemarkValidationWriter.adminDeleteRemark(id)) {
                is SupabaseRemarkValidationWriter.AdminResult.Ok -> {
                    reloadConfig()
                    bindAll()
                    setBusy(false)
                    Toast.makeText(requireContext(), "Deleted", Toast.LENGTH_SHORT).show()
                }
                is SupabaseRemarkValidationWriter.AdminResult.Err -> {
                    setBusy(false)
                    Toast.makeText(requireContext(), "Remark delete failed: ${result.message}", Toast.LENGTH_LONG).show()
                }
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
        val instructionType = selectedInstructionType()
        val instructionText = if (instructionType.isNotBlank()) etInstructionText.text.toString().trim() else ""
        addRemark(bn, en, target, instructionType = instructionType, instructionText = instructionText)
        etBn.setText(""); etEn.setText("")
        etInstructionText.setText("")
        spinnerInstructionType.setSelection(0)
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
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val etPriorityEdit = android.widget.EditText(ctx).apply {
            hint = "Priority (বেশি = উপরে, default 0)"
            setText(if (remark.priority != 0) remark.priority.toString() else "")
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            textSize = 13f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        // Status (target_status) — same dropdown as the create dialog (openCreateDialog).
        // Missing here before: the edit dialog never showed or sent target_status at
        // all, so the Edge Function's admin_upsert_remark fell back to '' on every
        // edit (see that action's `typeof row.target_status === 'string' ? ... : ''`),
        // silently wiping out this field on every single edit regardless of what it
        // had been before.
        val sortedEdit = sortedStatuses()
        val currentStatusIdx = sortedEdit.indexOf(remark.target_status)
        val statusSpinnerEdit = Spinner(ctx).apply {
            minimumHeight = dp(46)
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            setPadding(dp(8), 0, dp(8), 0)
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, sortedEdit.map { statusMeta[it]?.en ?: it })
            setSelection(currentStatusIdx.coerceAtLeast(0))
        }

        val tvTemplateLabel = TextView(ctx).apply {
            text = "WhatsApp Template (ঐচ্ছিক)"
            textSize = 10f
            setTextColor(ctx.getColor(R.color.theme_text_muted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(5))
        }
        val templates = ConfigState.whatsappTemplates.values.sortedBy { it.name }
        val templateOptions = listOf("— কোনো Template না —") + templates.map { it.name }
        val currentTemplateIdx = templates.indexOfFirst { it.id == remark.template_id }
        val templateSpinner = Spinner(ctx).apply {
            minimumHeight = dp(46)
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            setPadding(dp(8), 0, dp(8), 0)
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, templateOptions)
            setSelection(if (currentTemplateIdx >= 0) currentTemplateIdx + 1 else 0)
        }

        layout.addView(etBnEdit)
        layout.addView(etEnEdit)
        layout.addView(android.widget.TextView(ctx).apply {
            text = "Sheet Verdict (খালি = sheet-এ লিখবে না)"
            textSize = 10f
            setTextColor(ctx.getColor(R.color.theme_text_muted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(5))
        })
        val etVerdictEdit = android.widget.EditText(ctx).apply {
            hint = "যেমন: Delivered / Failed"
            setText(remark.category)
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            textSize = 13f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        layout.addView(etVerdictEdit)
        layout.addView(android.widget.TextView(ctx).apply {
            text = "Priority (বেশি = উপরে)"
            textSize = 10f
            setTextColor(ctx.getColor(R.color.theme_text_muted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(5))
        })
        layout.addView(etPriorityEdit)
        layout.addView(android.widget.TextView(ctx).apply {
            text = "Status"
            textSize = 10f
            setTextColor(ctx.getColor(R.color.theme_text_muted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(5))
        })
        layout.addView(statusSpinnerEdit)
        layout.addView(tvTemplateLabel)
        layout.addView(templateSpinner)

        // Instruction: same "None / On Hold / Return" fixed dropdown as the main add
        // form, plus a free-text field the admin writes per-remark — see ConfigState.Remark's
        // instruction_type/instruction_text doc comment for the full rationale.
        val instructionLabels = listOf("None") + ConfigState.INSTRUCTION_TYPES.map { ConfigState.instructionTypeLabel(it) }
        val currentInstructionIdx = ConfigState.INSTRUCTION_TYPES.indexOf(remark.instruction_type)
        val instructionSpinnerEdit = Spinner(ctx).apply {
            minimumHeight = dp(46)
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            setPadding(dp(8), 0, dp(8), 0)
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, instructionLabels)
            setSelection(if (currentInstructionIdx >= 0) currentInstructionIdx + 1 else 0)
        }
        val etInstructionEdit = android.widget.EditText(ctx).apply {
            hint = "Instruction for the delivery agent..."
            setText(remark.instruction_text)
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            textSize = 13f
            visibility = if (currentInstructionIdx >= 0) View.VISIBLE else View.GONE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        instructionSpinnerEdit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                etInstructionEdit.visibility = if (position == 0) View.GONE else View.VISIBLE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        layout.addView(android.widget.TextView(ctx).apply {
            text = "Instruction"
            textSize = 10f
            setTextColor(ctx.getColor(R.color.theme_text_muted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(5))
        })
        layout.addView(instructionSpinnerEdit)
        layout.addView(etInstructionEdit)

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
                val newTemplateId = templates.getOrNull(templateSpinner.selectedItemPosition - 1)?.id ?: ""
                val newPriority = etPriorityEdit.text.toString().trim().toIntOrNull() ?: 0
                val newTargetStatus = sortedEdit.getOrElse(statusSpinnerEdit.selectedItemPosition) { remark.target_status }
                val newInstructionPos = instructionSpinnerEdit.selectedItemPosition
                val newInstructionType = if (newInstructionPos <= 0) "" else ConfigState.INSTRUCTION_TYPES.getOrElse(newInstructionPos - 1) { "" }
                val newInstructionText = if (newInstructionType.isNotBlank()) etInstructionEdit.text.toString().trim() else ""
                val newCategory = etVerdictEdit.text.toString().trim()
                handleEdit(group, remark.id, newBn, newEn, newTemplateId, newTargetStatus, newPriority, newInstructionType, newInstructionText, newCategory)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleEdit(
        group: String, id: String, newBn: String, newEn: String, newTemplateId: String, newTargetStatus: String,
        newPriority: Int = 0, newInstructionType: String = "", newInstructionText: String = "",
        newCategory: String = "",
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Saving...")
            val remark = JSONObject()
                .put("remarks_bn", newBn).put("remarks_en", newEn)
                .put("target_status", newTargetStatus)
                .put("template_id", newTemplateId).put("priority", newPriority)
                .put("instruction_type", newInstructionType).put("instruction_text", newInstructionText)
                .put("category", newCategory)
            when (val result = SupabaseRemarkValidationWriter.adminUpsertRemark(activeScope.source, id, remark)) {
                is SupabaseRemarkValidationWriter.AdminResult.Ok -> {
                    reloadConfig(); bindAll(); setBusy(false)
                    Toast.makeText(requireContext(), "✅ Updated", Toast.LENGTH_SHORT).show()
                }
                is SupabaseRemarkValidationWriter.AdminResult.Err -> {
                    setBusy(false)
                    Toast.makeText(requireContext(), "Update failed: ${result.message}", Toast.LENGTH_LONG).show()
                }
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
            setTextColor(ctx.getColor(R.color.theme_text_muted))
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

        val priorityInput = EditText(ctx).apply {
            hint = "Priority (বেশি সংখ্যা = উপরে, default 0)"
            textSize = 13f
            minHeight = dp(46)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = resources.getDrawable(R.drawable.bg_input_rounded, ctx.theme)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(2) }
        }
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

        // WhatsApp template picker (optional — "কোনো Template না" = no auto-message)
        val templates = ConfigState.whatsappTemplates.values.sortedBy { it.name }
        val templateOptions = listOf("— কোনো Template না —") + templates.map { it.name }
        val templateSpinner = Spinner(ctx).apply {
            minimumHeight = dp(46)
            background = resources.getDrawable(R.drawable.bg_input_rounded, ctx.theme)
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, templateOptions)
        }

        content.addView(label("বাংলা"))
        content.addView(bnInput)
        content.addView(label("English"))
        content.addView(enInput)
        content.addView(label("Sheet Verdict (খালি = sheet-এ লিখবে না)"))
        val verdictInput = input("যেমন: Delivered / Failed")
        content.addView(verdictInput)
        content.addView(label("Priority (বেশি = উপরে)"))
        content.addView(priorityInput)
        content.addView(label("Group"))
        content.addView(spinner)
        content.addView(label("WhatsApp Template (ঐচ্ছিক)"))
        content.addView(templateSpinner)

        // Instruction: same "None / On Hold / Return" fixed dropdown as the main add
        // form and the edit dialog — see ConfigState.Remark's instruction_type/
        // instruction_text doc comment for the full rationale.
        val instructionLabels = listOf("None") + ConfigState.INSTRUCTION_TYPES.map { ConfigState.instructionTypeLabel(it) }
        val instructionSpinnerCreate = Spinner(ctx).apply {
            minimumHeight = dp(46)
            background = resources.getDrawable(R.drawable.bg_input_rounded, ctx.theme)
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, instructionLabels)
        }
        val instructionInputCreate = input("Instruction for the delivery agent...").apply { visibility = View.GONE }
        instructionSpinnerCreate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                instructionInputCreate.visibility = if (position == 0) View.GONE else View.VISIBLE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        content.addView(label("Instruction"))
        content.addView(instructionSpinnerCreate)
        content.addView(instructionInputCreate)

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
                    val selectedTemplateId = templates.getOrNull(templateSpinner.selectedItemPosition - 1)?.id ?: ""
                    val priority = priorityInput.text.toString().trim().toIntOrNull() ?: 0
                    val instructionPos = instructionSpinnerCreate.selectedItemPosition
                    val instructionType = if (instructionPos <= 0) "" else ConfigState.INSTRUCTION_TYPES.getOrElse(instructionPos - 1) { "" }
                    val instructionText = if (instructionType.isNotBlank()) instructionInputCreate.text.toString().trim() else ""
                    val verdict = verdictInput.text.toString().trim()
                    addRemark(bn, en, target, selectedTemplateId, priority, instructionType, instructionText, verdict)
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
        templateId: String = "",
        priority: Int = 0,
        instructionType: String = "",
        instructionText: String = "",
        category: String = "",
        onSuccess: () -> Unit = {},
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Creating...")
            val remark = JSONObject()
                .put("remarks_bn", bn.ifEmpty { en })
                .put("remarks_en", en.ifEmpty { bn })
                .put("target_status", target)
                .put("template_id", templateId)
                .put("priority", priority)
                .put("instruction_type", instructionType)
                .put("instruction_text", instructionText)
                .put("category", category)
            when (val result = SupabaseRemarkValidationWriter.adminUpsertRemark(activeScope.source, "", remark)) {
                is SupabaseRemarkValidationWriter.AdminResult.Ok -> {
                    activeStatus = target
                    reloadConfig()
                    bindAll()
                    setBusy(false)
                    Toast.makeText(requireContext(), "Created", Toast.LENGTH_SHORT).show()
                    onSuccess()
                }
                is SupabaseRemarkValidationWriter.AdminResult.Err -> {
                    setBusy(false)
                    Toast.makeText(requireContext(), "Remark create failed: ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** sortedStatuses: sort by priority desc (mirrors JSX sortedStatuses) */
    private fun sortedStatuses(): List<String> =
        statuses.sortedByDescending { statusMeta[it]?.priority ?: 0 }

    private fun parseColor(hex: String): Int = android.graphics.Color.parseColor(hex)

    private fun setBusy(show: Boolean, text: String = "Loading...") {
        if (!::busyOverlay.isInitialized) return
        tvBusy.text = text
        busyOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }
}
