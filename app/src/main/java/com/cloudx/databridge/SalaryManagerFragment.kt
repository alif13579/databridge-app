package com.cloudx.databridge

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SalaryManagerFragment : Fragment() {

    private data class AgentTypeConfig(
        val id: String,
        val name: String,
        val defaultFixedSalary: Double,
        val models: MutableList<SalaryModelConfig>
    )

    private lateinit var btnAdd: Button
    private lateinit var chips: LinearLayout
    private lateinit var tvSummary: TextView
    private lateinit var btnAddModel: View
    private lateinit var btnEditAgent: View
    private lateinit var btnDeleteAgent: View
    private lateinit var tvEmpty: TextView
    private lateinit var modelList: LinearLayout
    private lateinit var editor: LinearLayout
    private lateinit var etModelName: EditText
    private lateinit var etModelSlug: EditText
    private lateinit var spinnerType: Spinner
    private lateinit var etDocumentDeliveryRate: EditText
    private lateinit var etDocumentPickupRate: EditText
    private lateinit var slabRows: LinearLayout
    private lateinit var pickupSlabRows: LinearLayout
    private lateinit var tvSlabWarning: TextView
    private lateinit var tvPickupSlabWarning: TextView
    private lateinit var btnAddSlab: Button
    private lateinit var btnAddPickupSlab: Button
    private lateinit var tvPreview: TextView
    private lateinit var btnCancel: Button
    private lateinit var btnSave: Button
    private var editorDialog: AlertDialog? = null

    private val db = FirebaseDatabase.getInstance()
    private val agents = mutableListOf<AgentTypeConfig>()
    private var selectedAgentId: String? = null
    private var editingOriginalSlug: String? = null
    private var editingModel = SalaryModelConfig()
    private var suppressEvents = false

    private val salariesRef get() = db.reference.child("salaries")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_salary_manager, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        btnAdd = view.findViewById(R.id.btnSalaryAdd)
        chips = view.findViewById(R.id.layoutAgentTypeChips)
        tvSummary = view.findViewById(R.id.tvAgentSummary)
        btnAddModel = view.findViewById(R.id.btnAddSalaryModel)
        btnEditAgent = view.findViewById(R.id.btnEditAgentType)
        btnDeleteAgent = view.findViewById(R.id.btnDeleteAgentType)
        tvEmpty = view.findViewById(R.id.tvSalaryEmpty)
        modelList = view.findViewById(R.id.layoutSalaryModelList)
        editor = view.findViewById(R.id.layoutSalaryEditor)
        etModelName = view.findViewById(R.id.etSalaryModelName)
        etModelSlug = view.findViewById(R.id.etSalaryModelSlug)
        spinnerType = view.findViewById(R.id.spinnerCommissionType)
        etDocumentDeliveryRate = view.findViewById(R.id.etDocumentRate)
        etDocumentPickupRate = view.findViewById(R.id.etDocumentPickupRate)
        slabRows = view.findViewById(R.id.layoutSlabRows)
        pickupSlabRows = view.findViewById(R.id.layoutPickupSlabRows)
        tvSlabWarning = view.findViewById(R.id.tvSlabWarning)
        tvPickupSlabWarning = view.findViewById(R.id.tvPickupSlabWarning)
        btnAddSlab = view.findViewById(R.id.btnAddSlab)
        btnAddPickupSlab = view.findViewById(R.id.btnAddPickupSlab)
        tvPreview = view.findViewById(R.id.tvPreviewResult)
        btnCancel = view.findViewById(R.id.btnCancelSalaryModel)
        btnSave = view.findViewById(R.id.btnSaveSalaryModel)

        spinnerType.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            listOf("Flat rate", "Tiered cumulative", "Percent-based")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        btnAdd.setOnClickListener { showAddAgentDialog() }
        btnAddModel.setOnClickListener {
            val agent = selectedAgent()
            if (agent != null) showAddModelDialog(agent.id)
            else Toast.makeText(requireContext(), "Pick an agent type first", Toast.LENGTH_SHORT).show()
        }
        btnEditAgent.setOnClickListener { selectedAgent()?.let { showEditAgentDialog(it) } }
        btnDeleteAgent.setOnClickListener { selectedAgent()?.let { confirmDeleteAgent(it) } }
        btnAddSlab.setOnClickListener { addEditorSlab(isPickup = false) }
        btnAddPickupSlab.setOnClickListener { addEditorSlab(isPickup = true) }
        btnCancel.setOnClickListener {
            editorDialog?.dismiss()
            closeEditor()
        }
        btnSave.setOnClickListener {
            saveEditorModel()
            editorDialog?.dismiss()
        }
        attachEditorListeners()
        loadSalaryConfig()
    }

    private fun attachEditorListeners() {
        etModelName.addTextChangedListener(afterTextChanged = { text ->
            if (!suppressEvents) {
                editingModel = editingModel.copy(name = text.toString())
                updatePreview()
            }
        })
        etModelSlug.addTextChangedListener(afterTextChanged = { text ->
            if (!suppressEvents) editingModel = editingModel.copy(id = slugify(text.toString()))
        })
        etDocumentDeliveryRate.addTextChangedListener(afterTextChanged = { text ->
            if (!suppressEvents) {
                editingModel = editingModel.copy(documentDeliveryRate = text.toString().toDoubleOrNull() ?: 0.0)
                updatePreview()
            }
        })
        etDocumentPickupRate.addTextChangedListener(afterTextChanged = { text ->
            if (!suppressEvents) {
                editingModel = editingModel.copy(documentPickupRate = text.toString().toDoubleOrNull() ?: 0.0)
                updatePreview()
            }
        })
        spinnerType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                if (!suppressEvents) {
                    val type = when (position) {
                        1 -> SalaryModelConfig.TYPE_TIERED
                        2 -> SalaryModelConfig.TYPE_PERCENT
                        else -> SalaryModelConfig.TYPE_FLAT
                    }
                    editingModel = editingModel.copy(type = type)
                    updatePreview()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }
    }

    private fun showModelPreviewDialog(model: SalaryModelConfig) {
        AlertDialog.Builder(requireContext())
            .setTitle(model.name)
            .setMessage(commissionSummaryText(model))
            .setPositiveButton("Close", null)
            .show()
    }

    private fun loadSalaryConfig() {
        lifecycleScope.launch {
            try {
                val snap = salariesRef.get().await()
                agents.clear()
                snap.children.forEach { agent ->
                    val id = agent.key ?: return@forEach
                    val name = agent.child("name").getValue(String::class.java)?.takeIf { it.isNotBlank() }
                        ?: labelFromSlug(id)
                    val fixed = agent.child("default_fixed_salary").numberValueOrZero()
                    val models = agent.child("commission_models").children.mapNotNull { model ->
                        model.toSalaryModelConfig(model.key)
                    }.sortedBy { it.name.lowercase() }.toMutableList()
                    agents.add(AgentTypeConfig(id, name, fixed, models))
                }
                agents.sortBy { it.name.lowercase() }
                selectedAgentId = selectedAgentId?.takeIf { id -> agents.any { it.id == id } } ?: agents.firstOrNull()?.id
                renderAll()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Load failed: ${e.message}", Toast.LENGTH_SHORT).show()
                renderAll()
            }
        }
    }

    private fun renderAll() {
        renderChips()
        renderSummary()
        renderModels()
    }

    private fun renderChips() {
        chips.removeAllViews()
        agents.forEach { agent ->
            chips.addView(TextView(requireContext()).apply {
                text = agent.name
                setTextColor(if (agent.id == selectedAgentId) color(R.color.theme_btn_accent_text) else color(R.color.theme_text_primary))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = chipBackground(agent.id == selectedAgentId)
                setOnClickListener {
                    selectedAgentId = agent.id
                    closeEditor()
                    renderAll()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }
    }

    private fun renderSummary() {
        val agent = selectedAgent()
        if (agent == null) {
            tvSummary.text = "No agent types yet. Use + to add one."
            btnAddModel.visibility = View.GONE
            btnEditAgent.visibility = View.GONE
            btnDeleteAgent.visibility = View.GONE
            return
        }
        btnAddModel.visibility = View.VISIBLE
        btnEditAgent.visibility = View.VISIBLE
        btnDeleteAgent.visibility = View.VISIBLE
        val typeCounts = agent.models.groupingBy { it.type }.eachCount()
        val typeSummary = if (typeCounts.isEmpty()) "No commission models" else {
            typeCounts.entries.joinToString(" - ") { "${it.value} ${it.key}" }
        }
        val docRates = agent.models.map { it.documentDeliveryRate }.distinct().sorted()
        val docPickupRates = agent.models.map { it.documentPickupRate }.distinct().sorted()
        val docSummary = buildString {
            append(if (docRates.isEmpty()) "Doc delivery: none" else "Doc delivery: ${docRates.joinToString(", ") { "৳${formatMoneyPlain(it)}" }}")
            append(" | ")
            append(if (docPickupRates.isEmpty()) "Doc pickup: none" else "Doc pickup: ${docPickupRates.joinToString(", ") { "৳${formatMoneyPlain(it)}" }}")
        }
        tvSummary.text = buildString {
            append(agent.name)
            append("\nSlug: ${agent.id}")
            append("\nDefault fixed salary: ৳${formatMoneyPlain(agent.defaultFixedSalary)}")
            append("\nModels: ${agent.models.size}")
            append("\n$typeSummary")
            append("\n$docSummary")
        }
    }

    private fun renderModels() {
        val agent = selectedAgent()
        val models = agent?.models.orEmpty()
        modelList.removeAllViews()
        tvEmpty.visibility = if (models.isEmpty()) View.VISIBLE else View.GONE
        models.forEach { model ->
            modelList.addView(modelCard(model))
        }
    }

    private fun modelCard(model: SalaryModelConfig): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground(model.id == editingOriginalSlug)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showModelPreviewDialog(model)
            }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply {
                text = model.name
                setTextColor(color(R.color.theme_text_primary))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(ctx).apply {
                val typeLabel = when (model.type) {
                    SalaryModelConfig.TYPE_TIERED -> "Tiered"
                    SalaryModelConfig.TYPE_PERCENT -> "Percent"
                    else -> "Flat"
                }
                text = "${model.slabs.size} delivery / ${model.pickupSlabs.size} pickup - doc ৳${formatMoneyPlain(model.documentDeliveryRate)} / ৳${formatMoneyPlain(model.documentPickupRate)} - $typeLabel"
                setTextColor(color(R.color.theme_text_muted))
                textSize = 12f
                setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(iconButton(R.drawable.ic_edit_24, color(R.color.theme_text_secondary)) { openEditor(model) })
        row.addView(iconButton(R.drawable.ic_delete_24, color(R.color.theme_red)) { confirmDeleteModel(model) })
        card.addView(row)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
        return card
    }

    private fun showAddAgentDialog() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(4))
        }
        val etName = addDialogInput(layout, "Agent Type Name *", "Input agent type (ex: Delivery Agent)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val etSlug = addDialogInput(layout, "Slug *", "Auto generated from agent type", InputType.TYPE_CLASS_TEXT)
        val etFixed = addDialogInput(layout, "Default Fixed Salary *", "Input fixed salary (ex: 13000)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        var autoSlug = true
        var suppressSlugWatcher = false
        etName.addTextChangedListener(afterTextChanged = {
            if (autoSlug) {
                suppressSlugWatcher = true
                etSlug.setText(slugify(it.toString()))
                etSlug.setSelection(etSlug.text.length)
                suppressSlugWatcher = false
            }
        })
        etSlug.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppressSlugWatcher) autoSlug = false
            }
        })
        AlertDialog.Builder(ctx)
            .setTitle("Add Agent Type")
            .setView(layout)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = etName.text.toString().trim()
                        val slug = slugify(etSlug.text.toString())
                        val fixedSalary = etFixed.text.toString().trim().toDoubleOrNull()
                        if (name.isBlank()) {
                            etName.error = "Required"
                            return@setOnClickListener
                        }
                        if (slug.isBlank()) {
                            etSlug.error = "Required"
                            return@setOnClickListener
                        }
                        if (fixedSalary == null) {
                            etFixed.error = "Required"
                            return@setOnClickListener
                        }
                        if (agents.any { it.id == slug }) {
                            Toast.makeText(ctx, "Slug already exists", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        lifecycleScope.launch {
                            salariesRef.child(slug).setValue(
                                mapOf(
                                    "name" to name,
                                    "default_fixed_salary" to fixedSalary,
                                    "commission_models" to mapOf<String, Any>()
                                )
                            ).await()
                            selectedAgentId = slug
                            dialog.dismiss()
                            loadSalaryConfig()
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun showAddModelDialog(prefilledAgentId: String? = null) {
        val ctx = requireContext()
        if (agents.isEmpty()) {
            Toast.makeText(ctx, "Add an agent type first", Toast.LENGTH_SHORT).show()
            return
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(4))
        }
        layout.addView(dialogLabel("Agent Type *"))
        val spinner = Spinner(ctx)
        spinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, agents.map { it.name })
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val selectedIndex = agents.indexOfFirst { it.id == (prefilledAgentId ?: selectedAgentId) }.coerceAtLeast(0)
        spinner.setSelection(selectedIndex)
        layout.addView(spinner, dialogControlParams())
        val etName = addDialogInput(layout, "Salary Model Name *", "Input salary model (ex: Super Commission)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val etSlug = addDialogInput(layout, "Slug *", "Auto generated from salary model", InputType.TYPE_CLASS_TEXT)
        var autoSlug = true
        var suppressSlugWatcher = false
        etName.addTextChangedListener(afterTextChanged = {
            if (autoSlug) {
                suppressSlugWatcher = true
                etSlug.setText(slugify(it.toString()))
                etSlug.setSelection(etSlug.text.length)
                suppressSlugWatcher = false
            }
        })
        etSlug.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppressSlugWatcher) autoSlug = false
            }
        })
        AlertDialog.Builder(ctx)
            .setTitle("Add Salary Model")
            .setView(layout)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val agent = agents.getOrNull(spinner.selectedItemPosition) ?: return@setOnClickListener
                        val name = etName.text.toString().trim()
                        val slug = slugify(etSlug.text.toString())
                        if (name.isBlank() || slug.isBlank()) {
                            Toast.makeText(ctx, "Name and slug required", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (agent.models.any { it.id == slug }) {
                            Toast.makeText(ctx, "Slug already exists", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val model = SalaryModelConfig(
                            id = slug,
                            name = name,
                            type = SalaryModelConfig.TYPE_FLAT,
                            documentDeliveryRate = 0.0,
                            documentPickupRate = 0.0,
                            slabs = listOf(SalarySlab(1, 0, 0.0))
                        )
                        lifecycleScope.launch {
                            salariesRef.child("${agent.id}/commission_models/$slug").setValue(model.toFirebaseMap()).await()
                            selectedAgentId = agent.id
                            dialog.dismiss()
                            loadSalaryConfig()
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun showEditAgentDialog(agent: AgentTypeConfig) {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(4))
        }
        val etName = addDialogInput(layout, "Agent Type Name *", "Delivery Agent", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, agent.name)
        val etSlug = addDialogInput(layout, "Slug *", "delivery_agent", InputType.TYPE_CLASS_TEXT, agent.id)
        val etFixed = addDialogInput(
            layout,
            "Default Fixed Salary *",
            "13000",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            formatMoneyPlain(agent.defaultFixedSalary)
        )
        AlertDialog.Builder(ctx)
            .setTitle("Edit Agent Type")
            .setView(layout)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = etName.text.toString().trim()
                        val slug = slugify(etSlug.text.toString())
                        val fixedSalary = etFixed.text.toString().trim().toDoubleOrNull()
                        if (name.isBlank()) {
                            etName.error = "Required"
                            return@setOnClickListener
                        }
                        if (slug.isBlank()) {
                            etSlug.error = "Required"
                            return@setOnClickListener
                        }
                        if (fixedSalary == null) {
                            etFixed.error = "Required"
                            return@setOnClickListener
                        }
                        if (slug != agent.id && agents.any { it.id == slug }) {
                            Toast.makeText(ctx, "Slug already exists", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val payload = mapOf(
                            "name" to name,
                            "default_fixed_salary" to fixedSalary,
                            "commission_models" to agent.models.associate { it.id to it.toFirebaseMap() }
                        )
                        lifecycleScope.launch {
                            salariesRef.child(slug).setValue(payload).await()
                            if (slug != agent.id) salariesRef.child(agent.id).removeValue().await()
                            selectedAgentId = slug
                            dialog.dismiss()
                            loadSalaryConfig()
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun confirmDeleteAgent(agent: AgentTypeConfig) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete ${agent.name}?")
            .setMessage("This will remove the agent type and all salary models under it.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    salariesRef.child(agent.id).removeValue().await()
                    if (selectedAgentId == agent.id) selectedAgentId = null
                    closeEditor()
                    loadSalaryConfig()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openEditor(model: SalaryModelConfig) {
        val parent = editor.parent as? ViewGroup ?: return
        val indexInParent = parent.indexOfChild(editor)
        editingOriginalSlug = model.id
        editingModel = displaySortedSlabs(model.copy(slabs = model.slabs.ifEmpty { listOf(SalarySlab(1, 0, 0.0)) }))
        suppressEvents = true
        etModelName.setText(editingModel.name)
        etModelSlug.setText(editingModel.id)
        etDocumentDeliveryRate.setText(formatMoneyPlain(editingModel.documentDeliveryRate))
        etDocumentPickupRate.setText(formatMoneyPlain(editingModel.documentPickupRate))
        spinnerType.setSelection(
            when (editingModel.type) {
                SalaryModelConfig.TYPE_TIERED -> 1
                SalaryModelConfig.TYPE_PERCENT -> 2
                else -> 0
            }
        )
        suppressEvents = false
        updateSlabWarning(emptyList(), tvSlabWarning)
        updateSlabWarning(emptyList(), tvPickupSlabWarning)
        renderSlabs()
        updatePreview()
        parent.removeView(editor)
        editor.visibility = View.VISIBLE
        val scrollContainer = ScrollView(requireContext()).apply {
            isFillViewport = true
            addView(editor, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        editorDialog = AlertDialog.Builder(requireContext())
            .setTitle("Edit Salary Model")
            .setView(scrollContainer)
            .setOnDismissListener {
                scrollContainer.removeView(editor)
                (editor.parent as? ViewGroup)?.removeView(editor)
                if (editor.parent == null) parent.addView(editor, indexInParent)
                editor.visibility = View.GONE
                editingOriginalSlug = null
                tvSlabWarning.visibility = View.GONE
                renderModels()
            }
            .create()
        editorDialog?.show()
    }

    private fun closeEditor() {
        editor.visibility = View.GONE
        editingOriginalSlug = null
        tvSlabWarning.visibility = View.GONE
        tvPickupSlabWarning.visibility = View.GONE
        renderModels()
    }

    private fun renderSlabs() {
        renderSlabSection(
            container = slabRows,
            slabs = editingModel.slabs,
            isPickup = false,
            warningView = tvSlabWarning
        ) { updated -> editingModel = editingModel.copy(slabs = updated) }

        renderSlabSection(
            container = pickupSlabRows,
            slabs = editingModel.pickupSlabs,
            isPickup = true,
            warningView = tvPickupSlabWarning
        ) { updated -> editingModel = editingModel.copy(pickupSlabs = updated) }
    }

    private fun renderSlabSection(
        container: LinearLayout,
        slabs: List<SalarySlab>,
        isPickup: Boolean,
        warningView: TextView,
        onUpdate: (List<SalarySlab>) -> Unit
    ) {
        container.removeAllViews()
        val normalized = normalizeEditorSlabs(slabs, baseMinForType(editingModel.type))
        onUpdate(normalized)
        updateSlabWarning(slabWarnings(normalized), warningView)
        normalized.forEachIndexed { index, slab ->
            container.addView(slabRow(index, slab, index == normalized.lastIndex, isPickup, onUpdate))
        }
    }

    private fun slabRow(index: Int, slab: SalarySlab, isLast: Boolean, isPickup: Boolean, onUpdate: (List<SalarySlab>) -> Unit): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val isPercent = editingModel.type == SalaryModelConfig.TYPE_PERCENT
        val etMin = slabInput(
            if (slab.min > 0) slab.min.toString() else "",
            if (isPercent) "Min (%)" else "Min"
        ).apply {
            isEnabled = false
        }
        val etMax = slabInput(
            if (isLast) "∞" else if (slab.max > 0) slab.max.toString() else "",
            if (isPercent) "Max (%)" else "Max"
        ).apply {
            isEnabled = !isLast
            isFocusable = !isLast
            isFocusableInTouchMode = !isLast
            if (isLast) keyListener = null
            if (!isLast) inputType = InputType.TYPE_CLASS_NUMBER
        }
        val etRate = slabInput(
            if (slab.rate > 0) formatMoneyPlain(slab.rate) else if (isPickup) "0" else "",
            if (isPercent) "Rate (৳)" else "Rate"
        )

        fun commitSlabs(updated: List<SalarySlab>, reRender: Boolean = false) {
            val normalized = normalizeEditorSlabs(updated, baseMinForType(editingModel.type))
            onUpdate(normalized)
            updateSlabWarning(slabWarnings(normalized), if (isPickup) tvPickupSlabWarning else tvSlabWarning)
            if (reRender) renderSlabs()
            updatePreview()
        }

        fun handleMaxChange() {
            if (isLast) return
            val container = row.parent as? LinearLayout
            val current = if (isPickup) editingModel.pickupSlabs else editingModel.slabs
            val target = current.getOrNull(index) ?: return
            val newMax = etMax.text.toString().toIntOrNull() ?: return
            if (newMax <= target.min) return // keep valid contiguous slabs
            val next = current.toMutableList()
            next[index] = target.copy(max = newMax)
            commitSlabs(next, reRender = true)
            container?.post {
                val newRow = container.getChildAt(index) as? LinearLayout
                val maxInput = newRow?.getChildAt(1) as? EditText
                maxInput?.let { input ->
                    input.requestFocus()
                    input.setSelection(input.text.length)
                }
            }
        }

        fun handleRateChange() {
            val next = (if (isPickup) editingModel.pickupSlabs else editingModel.slabs).toMutableList()
            if (index !in next.indices) return
            val newRate = etRate.text.toString().toDoubleOrNull() ?: 0.0
            next[index] = next[index].copy(rate = newRate)
            commitSlabs(next, reRender = false)
        }

        etMax.addTextChangedListener(SimpleAfterTextWatcher { handleMaxChange() })
        etRate.addTextChangedListener(SimpleAfterTextWatcher { handleRateChange() })
        etRate.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) etRate.post { updatePreview() } }

        row.addView(etMin, inputParams())
        row.addView(etMax, inputParams())
        row.addView(etRate, inputParams())
        row.addView(ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            background = null
            setColorFilter(color(R.color.theme_red))
            val size = if (isPickup) editingModel.pickupSlabs.size else editingModel.slabs.size
            isEnabled = size > 1
            alpha = if (isEnabled) 1f else 0.35f
            setOnClickListener {
                val next = (if (isPickup) editingModel.pickupSlabs else editingModel.slabs).toMutableList()
                if (next.size > 1 && index in next.indices) {
                    next.removeAt(index)
                    commitSlabs(next, reRender = true)
                }
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) }
        return row
    }

    private fun addEditorSlab(isPickup: Boolean) {
        val source = if (isPickup) editingModel.pickupSlabs else editingModel.slabs
        val baseMin = baseMinForType(editingModel.type)
        val normalized = normalizeEditorSlabs(source, baseMin).toMutableList()
        val last = normalized.lastOrNull()
        if (last != null && last.max <= 0) {
            // If last was open-ended, give it a default finite max to allow the next slab to start
            normalized[normalized.lastIndex] = last.copy(max = last.min + 1)
        }
        val newLast = normalized.lastOrNull()
        val startMin = maxOf(baseMin, (newLast?.max ?: baseMin - 1) + 1)
        normalized.add(SalarySlab(startMin, startMin + 1, newLast?.rate ?: 0.0))
        val clean = normalizeEditorSlabs(normalized, baseMin)
        editingModel = if (isPickup) editingModel.copy(pickupSlabs = clean) else editingModel.copy(slabs = clean)
        updateSlabWarning(slabWarnings(clean), if (isPickup) tvPickupSlabWarning else tvSlabWarning)
        renderSlabs()
        updatePreview()
    }

    private fun saveEditorModel() {
        val agent = selectedAgent() ?: return
        val originalSlug = editingOriginalSlug ?: return
        val raw = editingModel.copy(
                id = slugify(etModelSlug.text.toString()),
                name = etModelName.text.toString().trim()
        )
        val rawError = validateRawModel(raw)
        if (rawError != null) {
            Toast.makeText(requireContext(), rawError, Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = normalizeModel(raw)
        val error = validateModel(normalized)
        if (error != null) {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            return
        }
        if (normalized.id != originalSlug && agent.models.any { it.id == normalized.id }) {
            Toast.makeText(requireContext(), "Slug already exists", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val base = salariesRef.child("${agent.id}/commission_models")
            base.child(normalized.id).setValue(normalized.toFirebaseMap()).await()
            if (normalized.id != originalSlug) base.child(originalSlug).removeValue().await()
            closeEditor()
            loadSalaryConfig()
            Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteModel(model: SalaryModelConfig) {
        val agent = selectedAgent() ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("Delete ${model.name}?")
            .setMessage("This model will be removed from ${agent.name}.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    salariesRef.child("${agent.id}/commission_models/${model.id}").removeValue().await()
                    if (editingOriginalSlug == model.id) closeEditor()
                    loadSalaryConfig()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updatePreview() {
        tvPreview.text = commissionSummaryText(editingModel)
    }

    private fun commissionSummaryText(model: SalaryModelConfig): String {
        val display = displaySortedSlabs(model)
        val validSlabs = display.slabs.filter { it.min > 0 }
        val validPickup = display.pickupSlabs.filter { it.min > 0 }
        val rangeLabel = if (display.type == SalaryModelConfig.TYPE_PERCENT) "Success %" else "Delivery range"
        val pickupRangeLabel = if (display.type == SalaryModelConfig.TYPE_PERCENT) "Success %" else "Pickup range"
        return buildString {
            append("Parcel\n")
            append("Slab | $rangeLabel | Per parcel\n")
            if (validSlabs.isEmpty()) {
                append("- | Minimum required | -\n")
            } else {
                validSlabs.forEachIndexed { index, slab ->
                    val range = if (display.type == SalaryModelConfig.TYPE_PERCENT && slab.max > 0) {
                        "${slab.min}% - ${slab.max}%"
                    } else {
                        "${slab.min}-${formatRangeMax(slab.max)}"
                    }
                    append("${index + 1} | $range | ৳${formatMoneyPlain(slab.rate)}\n")
                }
            }
            val pending = display.slabs.count { it.min <= 0 }
            if (pending > 0) {
                append("Pending | Minimum required | not saved\n")
            }
            append("\nPickup\n")
            append("Slab | $pickupRangeLabel | Per pickup\n")
            if (validPickup.isEmpty()) {
                append("- | Minimum required | -\n")
            } else {
                validPickup.forEachIndexed { index, slab ->
                    val range = if (display.type == SalaryModelConfig.TYPE_PERCENT && slab.max > 0) {
                        "${slab.min}% - ${slab.max}%"
                    } else {
                        "${slab.min}-${formatRangeMax(slab.max)}"
                    }
                    append("${index + 1} | $range | ৳${formatMoneyPlain(slab.rate)}\n")
                }
            }
            val pendingPickup = display.pickupSlabs.count { it.min <= 0 }
            if (pendingPickup > 0) {
                append("Pending | Minimum required | not saved\n")
            }
            append("\nDocument\n")
            append("Type | Rate\n")
            append("Document delivery | ৳${formatMoneyPlain(display.documentDeliveryRate)}")
            append("\nDocument pickup | ৳${formatMoneyPlain(display.documentPickupRate)}")
        }
    }

    private fun normalizeModel(model: SalaryModelConfig): SalaryModelConfig {
        val baseMin = baseMinForType(model.type)
        val cleanSlabs = normalizeEditorSlabs(model.slabs, baseMin).filter { it.min >= baseMin }
        val cleanPickup = normalizeEditorSlabs(model.pickupSlabs, baseMin).filter { it.min >= baseMin }
        return model.copy(
            id = slugify(model.id),
            name = model.name.trim(),
            documentDeliveryRate = model.documentDeliveryRate.coerceAtLeast(0.0),
            documentPickupRate = model.documentPickupRate.coerceAtLeast(0.0),
            slabs = cleanSlabs,
            pickupSlabs = cleanPickup
        )
    }

    private fun validateModel(model: SalaryModelConfig): String? {
        if (model.name.isBlank()) return "Model name required"
        if (model.id.isBlank()) return "Slug required"
        if (model.documentDeliveryRate < 0.0) return "Document delivery rate must be ≥ 0"
        if (model.documentPickupRate < 0.0) return "Document pickup rate must be ≥ 0"
        val baseMin = baseMinForType(model.type)
        fun validateList(slabs: List<SalarySlab>, label: String): String? {
            if (slabs.isEmpty()) return "$label: At least one slab required"
            if (slabs.first().min != baseMin) return "$label: First slab min must be $baseMin"
            if (slabs.last().max != 0) return "$label: Last slab max must be ∞"
            val mins = mutableSetOf<Int>()
            slabs.forEachIndexed { i, slab ->
                if (slab.min < baseMin) return "$label: Slab ${i + 1}: min invalid"
                if (!mins.add(slab.min)) return "$label: Duplicate slab min"
                val isLast = i == slabs.lastIndex
                if (!isLast) {
                    if (slab.max <= slab.min) return "$label: Slab ${i + 1}: max must be greater than min"
                    if (slab.max == 0) return "$label: Slab ${i + 1}: max cannot be ∞ (only last slab)"
                    val nextMin = slabs[i + 1].min
                    if (nextMin != slab.max + 1) return "$label: Slab ${i + 1}: gap/overlap before next slab"
                } else {
                    if (slab.max != 0 && slab.max <= slab.min) return "$label: Last slab max must be > min or ∞"
                }
                if (slab.rate < 0.0) return "$label: Slab ${i + 1}: rate must be ≥ 0"
            }
            return null
        }
        val delivery = normalizeEditorSlabs(model.slabs, baseMin)
        val pickup = normalizeEditorSlabs(model.pickupSlabs, baseMin)
        validateList(delivery, "Delivery")?.let { return it }
        validateList(pickup, "Pickup")?.let { return it }
        return null
    }

    private fun validateRawModel(model: SalaryModelConfig): String? = null

    private fun normalizeEditorSlabs(slabs: List<SalarySlab>, baseMin: Int = 1): List<SalarySlab> {
        if (slabs.isEmpty()) return listOf(SalarySlab(baseMin, 0, 0.0))
        val list = slabs.map { it.copy() }.toMutableList()
        list[0] = list[0].copy(min = baseMin)
        for (i in 1 until list.size) {
            val prev = list[i - 1]
            val nextMin = if (prev.max > 0) prev.max + 1 else prev.min + 1
            val bumpedMax = list[i].max
            val newMax = if (bumpedMax > 0 && bumpedMax < nextMin) nextMin else bumpedMax
            list[i] = list[i].copy(min = nextMin, max = newMax)
        }
        // ensure last slab is open-ended (∞) to satisfy validation/saving
        val lastIndex = list.lastIndex
        if (lastIndex >= 0) list[lastIndex] = list[lastIndex].copy(max = 0)
        return list
    }

    private fun baseMinForType(type: String): Int = 1

    private fun slabWarnings(slabs: List<SalarySlab>): List<String> {
        val baseMin = baseMinForType(editingModel.type)
        val warns = mutableListOf<String>()
        if (slabs.isEmpty()) return warns
        if (slabs.first().min != baseMin) warns.add("First slab min must be $baseMin")
        if (slabs.last().max == 0) warns.add("Last slab max will be ∞")
        slabs.forEachIndexed { i, slab ->
            val isLast = i == slabs.lastIndex
            if (!isLast) {
                if (slab.max <= slab.min) warns.add("Slab ${i + 1} max must be > min")
                val nextMin = slabs[i + 1].min
                if (nextMin != slab.max + 1) warns.add("Slab ${i + 1} to ${i + 2} gap/overlap")
            }
            if (slab.rate < 0.0) warns.add("Slab ${i + 1} rate must be ≥ 0")
        }
        return warns.distinct()
    }

    private fun displaySortedSlabs(model: SalaryModelConfig): SalaryModelConfig {
        val baseMin = baseMinForType(model.type)
        return model.copy(
            slabs = normalizeEditorSlabs(model.slabs, baseMin),
            pickupSlabs = normalizeEditorSlabs(model.pickupSlabs, baseMin)
        )
    }

    private fun updateSlabWarning(warnings: List<String>, target: TextView) {
        if (warnings.isEmpty()) {
            target.visibility = View.GONE
        } else {
            target.visibility = View.VISIBLE
            target.text = "Now save korle:\n${warnings.distinct().joinToString("\n")}"
        }
    }

    private fun selectedAgent(): AgentTypeConfig? = agents.firstOrNull { it.id == selectedAgentId }

    private fun addDialogInput(
        parent: LinearLayout,
        label: String,
        hint: String,
        inputTypeValue: Int,
        value: String = ""
    ): EditText {
        parent.addView(dialogLabel(label))
        return EditText(requireContext()).apply {
            this.hint = hint
            setText(value)
            if (value.isNotBlank()) setSelection(text.length)
            inputType = inputTypeValue
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_salary_dialog_input)
            setTextColor(color(R.color.theme_text_primary))
            setHintTextColor(color(R.color.theme_text_secondary))
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            parent.addView(this, dialogControlParams())
        }
    }

    private fun dialogLabel(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextColor(color(R.color.theme_text_secondary))
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(2), dp(8), dp(2), dp(6))
    }

    private fun dialogControlParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
            bottomMargin = dp(10)
        }

    private fun slabInput(value: String, hint: String): EditText = EditText(requireContext()).apply {
        setText(value)
        this.hint = hint
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        setTextColor(color(R.color.theme_text_primary))
        setHintTextColor(color(R.color.theme_text_muted))
        textSize = 13f
        setPadding(dp(8), 0, dp(8), 0)
        background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_salary_dialog_input)
    }

    private fun smallButton(text: String, onClick: () -> Unit): Button = Button(requireContext()).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun iconButton(iconRes: Int, tint: Int, onClick: () -> Unit): ImageButton =
        ImageButton(requireContext()).apply {
            setImageResource(iconRes)
            setColorFilter(tint)
            background = null
            contentDescription = null
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginStart = dp(4)
            }
        }

    private fun chipBackground(active: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(if (active) color(R.color.theme_btn_accent_bg) else color(R.color.theme_bg_card))
        cornerRadius = dp(18).toFloat()
        setStroke(dp(1), if (active) color(R.color.theme_btn_accent_bg) else color(R.color.theme_border))
    }

    private fun cardBackground(active: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(color(if (active) R.color.theme_bg_inner else R.color.theme_bg_card))
        cornerRadius = dp(8).toFloat()
        setStroke(dp(if (active) 2 else 1), if (active) color(R.color.theme_text_accent) else color(R.color.theme_border))
    }

    private fun inputParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) }

    private fun slugify(text: String): String = text.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

    private fun labelFromSlug(slug: String): String = slug.split("_")
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun formatRangeMax(max: Int): String = if (max <= 0) "∞" else max.toString()

    private fun DataSnapshot.numberValueOrZero(): Double {
        return getValue(Double::class.java)
            ?: getValue(Long::class.java)?.toDouble()
            ?: getValue(Int::class.java)?.toDouble()
            ?: 0.0
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class SimpleAfterTextWatcher(private val block: () -> Unit) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = block()
    }
}
