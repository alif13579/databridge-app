package com.cloudx.databridge

import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/**
 * ConfigSheetFragment's column-mapping builder — Step 5 ("Fields") of the Sheet Connect
 * wizard. Lets the user add/edit/remove field mappings between a Firebase node's keys
 * and Google Sheet columns, configure object-type (nested map) fields with their own
 * key/value column pairs, and build the primary-key spec used to match a sheet row to
 * a Firebase record.
 *
 * Extracted from ConfigSheetFragment.kt as part of breaking that ~4500-line file into
 * modules. Written as extension functions on ConfigSheetFragment (not a separate class
 * holding its own state) for the same reason as ConfigSheetNodePicker.kt: this section's
 * state (pendingMapping, pendingObjectMapping, pendingPkParts, customMappingFields,
 * fetchedNodeKeys, sheetHeaders, etc.) is read/written from OTHER wizard sections too
 * (e.g. the node picker's fetchNodeKeys() populates fetchedNodeKeys and calls
 * renderMappingStep() directly). Keeping that state on the fragment and moving only
 * these functions here keeps behavior identical while organizing the code.
 */
internal fun ConfigSheetFragment.showAddFieldDialog(editField: String? = null) {
    val ctx = context ?: return
    val dp = resources.displayMetrics.density
    fun Int.dp() = (this * dp).toInt()

    val isEdit = editField != null
    val existingIsObject = editField != null && editField in objectTypeFields
    val headerLetters = sheetHeaders.keys.toList()
    // Mark headers already used by OTHER flat (Key-type) fields — visual hint only, not
    // enforced here since Object-type key/value may legitimately reuse the same header.
    val usedByFlatFields = pendingMapping
        .filterKeys { it != editField }
        .values.map { it.col }
        .toSet()
    val headerOptions = sheetHeaders.map { (letter, text) ->
        if (letter in usedByFlatFields) "✓ $letter: $text  (ব্যবহৃত)" else "$letter: $text"
    }

    val root = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(24.dp(), 16.dp(), 24.dp(), 8.dp())
    }

    // Type dropdown
    val tvTypeLabel = TextView(ctx).apply {
        text = "Type"; textSize = 11f
        setTextColor(ctx.getColor(R.color.theme_text_muted))
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 4.dp() }
    }
    val spinnerType = Spinner(ctx).apply {
        background = resources.getDrawable(R.drawable.bg_input_rounded, null)
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 44.dp()
        ).apply { bottomMargin = 12.dp() }
        adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
            listOf("Key  (single value)", "Object  (key-value pair)"))
        setSelection(if (existingIsObject) 1 else 0)
    }

    // Field name
    val tvNameLabel = TextView(ctx).apply {
        text = "Field name"; textSize = 11f
        setTextColor(ctx.getColor(R.color.theme_text_muted))
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 4.dp() }
    }
    val etFieldName = EditText(ctx).apply {
        hint = "e.g. recipientName / consignments"
        background = resources.getDrawable(R.drawable.bg_input_rounded, null)
        setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        textSize = 13f
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 12.dp() }
        editField?.let { setText(it) }
    }

    // Container for dynamic content (Key column dropdown OR Object key/value rows)
    val dynamicContainer = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
    }

    root.addView(tvTypeLabel)
    root.addView(spinnerType)
    root.addView(tvNameLabel)
    root.addView(etFieldName)
    root.addView(dynamicContainer)

    // ── Sub-builders ────────────────────────────────────────────
    fun labeledSection(title: String) = TextView(ctx).apply {
        text = title; textSize = 11f
        setTextColor(ctx.getColor(R.color.theme_text_muted))
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 6.dp(); bottomMargin = 4.dp() }
    }

    // Key type dropdown UI — single column dropdown
    var keyColSpinner: Spinner? = null
    fun buildKeyTypeUI() {
        dynamicContainer.removeAllViews()
        val tvCol = labeledSection("Column")
        keyColSpinner = Spinner(ctx).apply {
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 44.dp())
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("— Skip —") + headerOptions)
            val existingLetter = editField?.let { pendingMapping[it]?.col }
            val idx = existingLetter?.let { headerLetters.indexOf(it) + 1 } ?: 0
            setSelection(idx.coerceAtLeast(0))
        }
        dynamicContainer.addView(tvCol)
        dynamicContainer.addView(keyColSpinner)
    }

    // Object type dropdown UI — Key section + Value section, each with Fixed/Column choice
    var keySourceSpinner: Spinner? = null
    var keyColDropdown: Spinner? = null
    var keyFixedInput: EditText? = null
    var valueSourceSpinner: Spinner? = null
    var valueColDropdown: Spinner? = null
    var valueFixedInput: EditText? = null

    fun buildSourceRow(
        label: String,
        existingSpec: String?
    ): Triple<Spinner, Spinner, EditText> {
        val isFixed = existingSpec?.startsWith("fixed:") == true
        val existingCol = existingSpec?.takeIf { it.startsWith("col:") }?.removePrefix("col:")
        val existingFixedVal = existingSpec?.takeIf { it.startsWith("fixed:") }?.removePrefix("fixed:") ?: ""

        dynamicContainer.addView(labeledSection(label))

        val sourceSpinner = Spinner(ctx).apply {
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 40.dp()
            ).apply { bottomMargin = 4.dp() }
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("Column (dynamic)", "Fixed text"))
            setSelection(if (isFixed) 1 else 0)
        }
        dynamicContainer.addView(sourceSpinner)

        val colDropdown = Spinner(ctx).apply {
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 42.dp())
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, headerOptions)
            val idx = existingCol?.let { headerLetters.indexOf(it) } ?: 0
            setSelection(idx.coerceAtLeast(0))
            visibility = if (isFixed) View.GONE else View.VISIBLE
        }
        dynamicContainer.addView(colDropdown)

        val fixedInput = EditText(ctx).apply {
            hint = "Fixed value লিখুন"
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            textSize = 13f
            setText(existingFixedVal)
            visibility = if (isFixed) View.VISIBLE else View.GONE
        }
        dynamicContainer.addView(fixedInput)

        sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                colDropdown.visibility   = if (pos == 1) View.GONE else View.VISIBLE
                fixedInput.visibility    = if (pos == 1) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        return Triple(sourceSpinner, colDropdown, fixedInput)
    }

    fun buildObjectTypeUI() {
        dynamicContainer.removeAllViews()
        val existing = editField?.let { pendingObjectMapping[it] }
        val (ks, kc, kf) = buildSourceRow("Key", existing?.keyCol)
        keySourceSpinner = ks; keyColDropdown = kc; keyFixedInput = kf
        val (vs, vc, vf) = buildSourceRow("Value", existing?.valueCol)
        valueSourceSpinner = vs; valueColDropdown = vc; valueFixedInput = vf
    }

    // Initial render based on spinnerType selection
    if (existingIsObject) buildObjectTypeUI() else buildKeyTypeUI()

    spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
            if (pos == 0) buildKeyTypeUI() else buildObjectTypeUI()
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    val scrollWrap = android.widget.ScrollView(ctx).apply { addView(root) }

    android.app.AlertDialog.Builder(ctx)
        .setTitle(if (isEdit) "Field Edit করুন" else "New Field যোগ করুন")
        .setView(scrollWrap)
        .setPositiveButton("Save") { _, _ ->
            val name = etFieldName.text.toString().trim()
            if (name.isBlank()) return@setPositiveButton

            val isObjectType = spinnerType.selectedItemPosition == 1

            if (!isEdit) {
                if (fetchedNodeKeys.contains(name) || customMappingFields.any { it.first == name }) {
                    toast("⚠ এই field আগে থেকেই আছে")
                    return@setPositiveButton
                }
                customMappingFields.add(name to name)
            }

            if (isObjectType) {
                objectTypeFields.add(name)
                pendingMapping.remove(name)

                val keySpecStr = if (keySourceSpinner?.selectedItemPosition == 1) {
                    "fixed:${keyFixedInput?.text?.toString()?.trim() ?: ""}"
                } else {
                    "col:${headerLetters.getOrElse(keyColDropdown?.selectedItemPosition ?: 0) { "" }}"
                }
                val valueSpecStr = if (valueSourceSpinner?.selectedItemPosition == 1) {
                    "fixed:${valueFixedInput?.text?.toString()?.trim() ?: ""}"
                } else {
                    "col:${headerLetters.getOrElse(valueColDropdown?.selectedItemPosition ?: 0) { "" }}"
                }
                val keyLetter   = keySpecStr.removePrefix("col:")
                val valueLetter = valueSpecStr.removePrefix("col:")
                pendingObjectMapping[name] = ObjectColMapping(
                    keyCol      = if (keySpecStr.startsWith("fixed:")) keySpecStr else keyLetter,
                    keyHeader   = sheetHeaders[keyLetter] ?: "",
                    valueCol    = if (valueSpecStr.startsWith("fixed:")) valueSpecStr else valueLetter,
                    valueHeader = sheetHeaders[valueLetter] ?: "",
                )
            } else {
                val idx = keyColSpinner?.selectedItemPosition ?: 0
                if (idx > 0) {
                    val letter = headerLetters.getOrElse(idx - 1) { "" }
                    if (letter.isNotBlank()) {
                        val usedElsewhere = pendingMapping.filterKeys { it != name }.values.map { it.col }.toSet()
                        if (letter in usedElsewhere) {
                            toast("⚠ এই column আগে থেকেই অন্য field-এ ব্যবহৃত হয়েছে")
                            return@setPositiveButton
                        }
                        val headerText = sheetHeaders[letter] ?: ""
                        objectTypeFields.remove(name)
                        pendingObjectMapping.remove(name)
                        pendingMapping[name] = ColMapping(col = letter, header = headerText)
                    } else {
                        objectTypeFields.remove(name)
                        pendingObjectMapping.remove(name)
                        pendingMapping.remove(name)
                    }
                } else {
                    objectTypeFields.remove(name)
                    pendingObjectMapping.remove(name)
                    pendingMapping.remove(name)
                }
            }
            renderMappingStep()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

/** Renders a summary card for an "object" type field — tap to edit via unified dialog */
internal fun ConfigSheetFragment.renderObjectFieldRow(
    ctx: android.content.Context,
    container: android.widget.LinearLayout,
    field: String,
    label: String,
    headerOptions: List<String>,
    headerLetters: List<String>
) {
    val dp = resources.displayMetrics.density
    fun Int.dp() = (this * dp).toInt()

    fun specLabel(spec: String?): String = when {
        spec == null -> "— not set —"
        spec.startsWith("col:")   -> {
            val letter = spec.removePrefix("col:")
            val text   = sheetHeaders[letter] ?: letter
            "Column [$letter: $text]"
        }
        spec.startsWith("fixed:") -> "Fixed \"${spec.removePrefix("fixed:")}\""
        else -> spec
    }

    val existing = pendingObjectMapping[field]

    val card = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        background  = resources.getDrawable(R.drawable.bg_input_rounded, null)
        setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
        isClickable = true
        isFocusable = true
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 10.dp() }
        setOnClickListener { showAddFieldDialog(editField = field) }
    }

    val headerRow = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity     = android.view.Gravity.CENTER_VERTICAL
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 6.dp() }
    }
    val tvLabel = TextView(ctx).apply {
        text     = "$label  {}"
        textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(android.graphics.Color.parseColor("#3B82F6"))
        layoutParams = android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val btnDelete = TextView(ctx).apply {
        text     = "✕"
        textSize = 13f
        setTextColor(android.graphics.Color.parseColor("#EF4444"))
        setPadding(8.dp(), 0, 0, 0)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            customMappingFields.removeAll { it.first == field }
            pendingObjectMapping.remove(field)
            objectTypeFields.remove(field)
            renderMappingStep()
        }
    }
    headerRow.addView(tvLabel)
    headerRow.addView(btnDelete)
    card.addView(headerRow)

    val tvKey = TextView(ctx).apply {
        text = "Key: ${specLabel(existing?.keySpec())}"
        textSize = 11f
        setTextColor(ctx.getColor(R.color.theme_text_secondary))
    }
    val tvValue = TextView(ctx).apply {
        text = "Value: ${specLabel(existing?.valueSpec())}"
        textSize = 11f
        setTextColor(ctx.getColor(R.color.theme_text_secondary))
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 2.dp() }
    }
    card.addView(tvKey)
    card.addView(tvValue)

    container.addView(card)
}

/** Renders the composite primary-key builder: an ordered list of Prefix/Column parts. */
internal fun ConfigSheetFragment.renderPkBuilder() {
    val ctx = context ?: return
    val container = containerPkBuilder ?: return
    container.removeAllViews()
    val dp = resources.displayMetrics.density
    fun Int.dp() = (this * dp).toInt()

    if (pendingPkParts.isEmpty()) {
        val tvEmpty = TextView(ctx).apply {
            text = "\"+ Add Part\" দিয়ে prefix/column যোগ করুন"
            textSize = 11f
            setTextColor(ctx.getColor(R.color.theme_text_muted))
            setPadding(0, 4.dp(), 0, 4.dp())
        }
        container.addView(tvEmpty)
        updatePkPreview()
        return
    }

    val headerOptions = sheetHeaders.map { (letter, text) -> "$letter: $text" }
    val headerLetters = sheetHeaders.keys.toList()

    pendingPkParts.forEachIndexed { index, part ->
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity     = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
        }

        val typeSpinner = Spinner(ctx).apply {
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            layoutParams = android.widget.LinearLayout.LayoutParams(92.dp(), 40.dp())
                .apply { marginEnd = 6.dp() }
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("Prefix", "Column", "Date"))
            setSelection(when (part.type) { "col" -> 1; "date" -> 2; else -> 0 })
        }

        val valueInput = EditText(ctx).apply {
            hint = "e.g. run_"
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
            textSize = 12f
            setText(part.value)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, 40.dp(), 1f)
                .apply { marginEnd = 6.dp() }
            visibility = if (part.type == "fixed") View.VISIBLE else View.GONE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (index < pendingPkParts.size && pendingPkParts[index].type == "fixed") {
                        pendingPkParts[index] = pendingPkParts[index].copy(value = s?.toString() ?: "")
                        updatePkPreview()
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        val colSpinner = Spinner(ctx).apply {
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, 40.dp(), 1f)
                .apply { marginEnd = 6.dp() }
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                if (headerOptions.isEmpty()) listOf("— কোনো column নেই —") else headerOptions)
            val idx = headerLetters.indexOf(part.value).coerceAtLeast(0)
            setSelection(idx)
            visibility = if (part.type == "col" || part.type == "date") View.VISIBLE else View.GONE
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (index < pendingPkParts.size && headerLetters.isNotEmpty() &&
                        (pendingPkParts[index].type == "col" || pendingPkParts[index].type == "date")) {
                        val letter = headerLetters.getOrElse(pos) { "" }
                        val hdr = sheetHeaders[letter] ?: ""
                        pendingPkParts[index] = pendingPkParts[index].copy(value = letter, header = hdr)
                        updatePkPreview()
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val newType = when (pos) { 1 -> "col"; 2 -> "date"; else -> "fixed" }
                if (index < pendingPkParts.size && pendingPkParts[index].type != newType) {
                    val cur = pendingPkParts[index]
                    val newValue = if (newType == "col" || newType == "date") {
                        // keep the same column letter if we're switching between col ↔ date
                        if (cur.type == "col" || cur.type == "date") cur.value
                        else headerLetters.firstOrNull() ?: ""
                    } else ""
                    val newHeader = if ((newType == "col" || newType == "date") && newValue.isNotBlank())
                        sheetHeaders[newValue] ?: cur.header
                    else ""
                    pendingPkParts[index] = PkPart(newType, newValue, newHeader)
                    renderPkBuilder()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val btnDelete = TextView(ctx).apply {
            text = "✕"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#EF4444"))
            setPadding(6.dp(), 0, 0, 0)
            isClickable = true; isFocusable = true
            setOnClickListener {
                if (index < pendingPkParts.size) pendingPkParts.removeAt(index)
                renderPkBuilder()
            }
        }

        row.addView(typeSpinner)
        row.addView(valueInput)
        row.addView(colSpinner)
        row.addView(btnDelete)
        container.addView(row)
    }

    updatePkPreview()
}

internal fun ConfigSheetFragment.updatePkPreview() {
    if (pendingPkParts.isEmpty()) {
        tvPkPreview?.text = "⚠ কমপক্ষে একটা part যোগ করুন"
        tvPkPreview?.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
        updateConnectButtonState()   // keep Exit/Save button in sync with pk edits
        return
    }
    val todayDdMmYy = java.text.SimpleDateFormat("ddMMyy", java.util.Locale.US).format(java.util.Date())
    val preview = pendingPkParts.joinToString("") { part ->
        when (part.type) {
            "fixed" -> part.value
            "col"   -> {
                if (part.value.isBlank()) "{?}"
                else sampleSheetRow[part.value]?.takeIf { it.isNotBlank() }
                    ?: "{${sheetHeaders[part.value] ?: part.value}}"
            }
            "date"  -> todayDdMmYy
            else    -> ""
        }
    }
    val usingRealData = sampleSheetRow.isNotEmpty()
    tvPkPreview?.text = if (usingRealData) "Preview (1st row): $preview" else "Preview: $preview"
    tvPkPreview?.setTextColor(context?.getColor(R.color.theme_text_secondary) ?: android.graphics.Color.DKGRAY)
    updateConnectButtonState()   // keep Exit/Save button in sync with pk edits
}

internal fun ConfigSheetFragment.renderMappingStep() {
    val ctx = context ?: return
    val container = containerMapping ?: return
    container.removeAllViews()

    // NOTE: intentionally no auto-fill of etTargetNode from the `targetNode` default here.
    // `targetNode` always holds *some* value ("courier/consignments" fallback for a brand
    // new connection, or the real saved path once prefillConnectForm() runs for an existing
    // one) — auto-filling on blank used to make new connections look "already chosen" and
    // incorrectly lock the picker. prefillConnectForm() already sets etTargetNode directly
    // for existing connections, so nothing needs to happen here for that case.

    val dp = resources.displayMetrics.density
    fun Int.dp() = (this * dp).toInt()

    // Fields / Primary Key are only shown once the target node has been explicitly
    // confirmed in the picker above (tree-preview "Yes", true-leaf auto-commit, "+ Create
    // New", or the manual Fetch Fields button) — avoids showing a stale/empty section
    // while the user is still choosing a node, and avoids duplicating the tree preview
    // that's already shown live during node picking.
    if (!nodeMappingConfirmed) {
        btnAddMappingField?.visibility = View.GONE
        containerPkBuilder?.removeAllViews()
        btnAddPkPart?.visibility = View.GONE
        tvPkPreview?.text = ""
        val tvWaiting = TextView(ctx).apply {
            text      = "⬆ উপরে node পিক করে \"Yes, confirm করো\" চাপুন — তারপর এখানে Primary Key ও Field mapping দেখাবে"
            textSize  = 12f
            setTextColor(context!!.getColor(R.color.theme_text_muted))
            gravity   = android.view.Gravity.CENTER
            setPadding(16.dp(), 20.dp(), 16.dp(), 20.dp())
        }
        container.addView(tvWaiting)
        return
    }
    btnAddMappingField?.visibility = View.VISIBLE
    btnAddPkPart?.visibility = View.VISIBLE

    // Primary key builder renders independently of fetched/custom fields state
    renderPkBuilder()

    // Ensure every already-mapped field (from a previously saved connection) shows up
    // as a row, even if the latest node-fetch sample didn't happen to include that key
    // as a child (e.g. right after reconnecting — prefillConnectForm() restores
    // pendingMapping, but a subsequent fetch clears customMappingFields and only
    // re-populates it from whatever the live sample record contains).
    pendingMapping.keys.forEach { key ->
        if (fetchedNodeKeys.none { it == key } && customMappingFields.none { it.first == key }) {
            customMappingFields.add(key to key)
        }
    }

    // ── Empty state ───────────────────────────────────────────────
    // De-duplicate: a key can legitimately appear in BOTH fetchedNodeKeys (live sample) and
    // customMappingFields (restored on reconnect / re-added above). Without distinctBy the
    // same field/object renders twice — this was the "objects double hoye jacche" bug.
    val allFields = (fetchedNodeKeys.map { it to it } + customMappingFields).distinctBy { it.first }
    if (allFields.isEmpty()) {
        val tvEmpty = TextView(ctx).apply {
            text      = "Node fetch করুন অথবা নিচে manually field add করুন"
            textSize  = 12f
            setTextColor(context!!.getColor(R.color.theme_text_muted))
            gravity   = android.view.Gravity.CENTER
            setPadding(0, 16.dp(), 0, 16.dp())
        }
        container.addView(tvEmpty)
        return
    }

    val headerOptions = listOf("— Select node key column —") + sheetHeaders.map { (letter, text) ->
        "$letter: $text"
    }
    val headerLetters = listOf("") + sheetHeaders.keys.toList()

    // All fields already set above
    val allFields2 = allFields

    allFields2.forEach { (field, label) ->
        val isCustom = customMappingFields.any { it.first == field }
        val isObjectField = field in objectTypeFields

        if (isObjectField) {
            renderObjectFieldRow(ctx, container, field, label, headerOptions, headerLetters)
            return@forEach
        }

        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity     = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp() }
        }

        // Field label
        val tvLabel = TextView(ctx).apply {
            text     = label
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.NORMAL)
            setTextColor(if (isCustom) android.graphics.Color.parseColor("#3B82F6") else context!!.getColor(R.color.theme_text_primary))
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Auto-matched indicator
        val isAutoMatched = pendingMapping.containsKey(field)
        val tvStatus = TextView(ctx).apply {
            text      = if (isAutoMatched) "✓" else ""
            textSize  = 13f
            setTextColor(android.graphics.Color.parseColor("#16A34A"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                20.dp(), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 6.dp() }
        }

        // Spinner
        val spinner = Spinner(ctx).apply {
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, 44.dp(), 1.2f)
        }

        /** Rebuilds the adapter, marking headers already used by OTHER flat fields. */
        fun refreshSpinnerAdapter() {
            val usedElsewhere = pendingMapping
                .filterKeys { it != field }
                .values.map { it.col }
                .toSet()
            val displayLabels = headerOptions.mapIndexed { idx, label ->
                val letter = headerLetters.getOrElse(idx) { "" }
                if (letter.isNotBlank() && letter in usedElsewhere) "✓ $label" else label
            }
            val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_dropdown_item, displayLabels) {
                override fun isEnabled(position: Int): Boolean {
                    val letter = headerLetters.getOrElse(position) { "" }
                    return letter.isBlank() || letter !in usedElsewhere
                }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getDropDownView(position, convertView, parent) as TextView
                    val letter = headerLetters.getOrElse(position) { "" }
                    val disabled = letter.isNotBlank() && letter in usedElsewhere
                    v.setTextColor(android.graphics.Color.parseColor(if (disabled) "#9CA3AF" else "#111827"))
                    return v
                }
            }
            spinner.adapter = adapter

            // Resolve which dropdown position to select for this field's saved mapping.
            // 1) Saved column letter still points at the same header — fastest, most common.
            // 2) Letter drifted (sheet reordered) but the exact saved header text still
            //    exists somewhere in the current headers — relocate to it.
            // 3) Neither — try a case/space-insensitive fuzzy match on header text
            //    (e.g. saved "Delivery Status" vs current "deliveryStatus").
            // 4) Nothing matches at all — leave blank, nothing to auto-fill.
            val saved = pendingMapping[field]
            fun normalize(s: String) = s.replace(" ", "").lowercase()
            val selIdx = when {
                saved == null -> 0
                saved.col.isNotBlank() && headerLetters.contains(saved.col) &&
                    sheetHeaders[saved.col] == saved.header ->
                    headerLetters.indexOf(saved.col)
                saved.header.isNotBlank() -> {
                    val exactIdx = headerLetters.indexOfFirst { letter ->
                        letter.isNotBlank() && sheetHeaders[letter] == saved.header
                    }
                    val relocatedIdx = if (exactIdx >= 0) exactIdx else {
                        val normSaved = normalize(saved.header)
                        headerLetters.indexOfFirst { letter ->
                            letter.isNotBlank() && normalize(sheetHeaders[letter] ?: "") == normSaved
                        }
                    }
                    // Header moved to a different column — keep pendingMapping in sync
                    // with what's actually being shown/selected, so Save uses the
                    // relocated letter instead of the stale saved one.
                    if (relocatedIdx > 0) {
                        val newLetter = headerLetters[relocatedIdx]
                        if (newLetter != saved.col) {
                            pendingMapping[field] = ColMapping(col = newLetter, header = saved.header)
                        }
                    }
                    relocatedIdx.coerceAtLeast(0)
                }
                saved.col.isNotBlank() && headerLetters.contains(saved.col) ->
                    headerLetters.indexOf(saved.col)
                else -> 0
            }
            spinner.setSelection(selIdx)
        }
        refreshSpinnerAdapter()

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val letter = headerLetters.getOrElse(pos) { "" }
                if (letter.isBlank()) {
                    if (!pendingMapping.containsKey(field)) return
                    pendingMapping.remove(field)
                } else {
                    // Guard against duplicate selection sneaking through (e.g. programmatic set)
                    val usedElsewhere = pendingMapping.filterKeys { it != field }.values.map { it.col }.toSet()
                    if (letter in usedElsewhere) {
                        toast("⚠ এই column আগে থেকেই অন্য field-এ ব্যবহৃত হয়েছে")
                        refreshSpinnerAdapter() // revert visual selection
                        return
                    }
                    val headerText = sheetHeaders[letter] ?: ""
                    if (pendingMapping[field]?.col == letter) return
                    pendingMapping[field] = ColMapping(col = letter, header = headerText)
                }
                // Re-render so every OTHER flat-field dropdown refreshes which columns
                // are now taken — a single row's local adapter has no way to know
                // about a sibling row's selection otherwise (this was the bug: a
                // header selected in one dropdown stayed selectable in others).
                renderMappingStep()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Delete button for custom fields
        if (isCustom) {
            val btnDelete = TextView(ctx).apply {
                text     = "✕"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#EF4444"))
                setPadding(8.dp(), 0, 0, 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    customMappingFields.removeAll { it.first == field }
                    pendingMapping.remove(field)
                    pendingObjectMapping.remove(field)
                    objectTypeFields.remove(field)
                    renderMappingStep()
                }
            }
            row.addView(tvLabel)
            row.addView(tvStatus)
            row.addView(spinner)
            row.addView(btnDelete)
        } else {
            row.addView(tvLabel)
            row.addView(tvStatus)
            row.addView(spinner)
        }

        container.addView(row)
    }

    updateConnectButtonState()
}

/** Strict lookup of the connection being edited — no firstOrNull fallback (unlike activeConn),
 *  so a brand-new "+ New Sheet" flow (activeConnectionId == "") is never mistaken for an edit. */
internal fun ConfigSheetFragment.editingConn(): SheetConn? =
    connections[activeBranch]?.find { it.connectionId == activeConnectionId }

internal fun ConfigSheetFragment.isEditingExistingConn(): Boolean = editingConn() != null
