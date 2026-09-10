package com.cloudx.databridge

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * 🔌 Scanner's socket: bind one sheet library to scanner data.
 *
 * Admin picks an existing library (branch-wise) and maps, per column,
 * WHICH scan data flows where:
 * - Lookup columns → Employee ID / Scan text (ALL must match one row)
 * - Write columns  → Scan text / Scan time / Employee ID / Agent name
 *
 * One binding per library per branch (save upserts). Users never see
 * this — approval-time writes ([ScanApprovalViewModel]) resolve bindings
 * silently. Opened from ScannerFragment's header socket icon (admin-only).
 */
object ScannerSheetBindingDialog {

    fun show(
        ctx: Context,
        scope: CoroutineScope,
        onSaved: () -> Unit = {},
    ) {
        val pad = (ctx.resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        fun label(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.theme_text_secondary))
            setPadding(0, 12, 0, 4)
        }
        fun spinner(): Spinner = Spinner(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val branchSpinner = spinner()
        val librarySpinner = spinner()
        val statusView = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ctx.getColor(R.color.theme_text_secondary))
            setPadding(0, 8, 0, 0)
        }
        val mappingBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val summaryView = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(ctx.getColor(R.color.theme_text_primary))
            setPadding(0, 12, 0, 0)
        }
        // Ignore rules (exclusion): match korle row skip — write path-e.
        // ANY: ekta milllei skip; ALL: sob millei skip.
        val ignoreLogicSpinner = Spinner(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val ignoreBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val ignoreSummaryView = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ctx.getColor(R.color.theme_text_secondary))
            setPadding(0, 8, 0, 0)
        }
        fun stepLabel(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.theme_text_primary))
            setPadding(0, 18, 0, 2)
        }
        root.addView(stepLabel("① Sheet"))
        root.addView(label("BRANCH"))
        root.addView(branchSpinner)
        root.addView(label("SHEET LIBRARY (Config → Connectors)"))
        root.addView(librarySpinner)
        root.addView(statusView)
        root.addView(stepLabel("② Mapping (scan save)"))
        root.addView(mappingBox)
        root.addView(summaryView)
        root.addView(stepLabel("③ ইগনোর (row বাদ — write)"))
        root.addView(label("লজিক"))
        root.addView(ignoreLogicSpinner)
        root.addView(ignoreBox)
        root.addView(ignoreSummaryView)
        val scroll = ScrollView(ctx).apply { addView(root) }

        var dialog: android.app.AlertDialog? = null
        var branches: List<Pair<String, String>> = emptyList() // (id, name)
        var libraries: List<SheetLibrary> = emptyList()
        var bindings: List<ScannerBinding> = emptyList()
        var currentBinding: ScannerBinding? = null
        fun fieldAdapter(fields: List<String>): ArrayAdapter<String> {
            val labels = listOf("— field বেছে নিন —") + fields.map { ScannerField.label(it) }
            return ArrayAdapter(ctx, android.R.layout.simple_spinner_item, labels)
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }

        // ── Dynamic mapping rows: [+ Add] diye joto khushi lookup/write ──
        // Column dropdown-e range letter + header ("B — Consignment").
        data class MapRow(val colPos: () -> Int, val fieldPos: () -> Int)
        val lookupMapRows = mutableListOf<MapRow>()
        val writeMapRows = mutableListOf<MapRow>()
        var mapCols: List<SheetColRef> = emptyList()
        var currentLib: SheetLibrary? = null

        fun selectedBranchId(): String {
            val pos = branchSpinner.selectedItemPosition
            return branches.getOrNull(pos)?.first.orEmpty()
        }
        fun selectedLibrary(): SheetLibrary? {
            val pos = librarySpinner.selectedItemPosition
            return libraries.getOrNull(pos)
        }
        fun colLabels(): List<String> {
            val lib = currentLib
            return mapCols.map { lib?.colLabel(it.colRef) ?: it.colRef.trim() }
        }


        fun mapSummary(
            list: List<MapRow>, fields: List<String>, joiner: String,
        ): String {
            val cols = mapCols
            val lib = currentLib
            return list.mapNotNull { r ->
                val c = cols.getOrNull(r.colPos()) ?: return@mapNotNull null
                val field = (listOf("") + fields).getOrNull(r.fieldPos()).orEmpty()
                if (field.isBlank()) null
                else "${lib?.colLabel(c.colRef) ?: c.colRef.trim()}=${ScannerField.label(field)}"
            }.joinToString(joiner)
        }

        fun refreshSummary() {
            val l = mapSummary(lookupMapRows, ScannerField.LOOKUP_FIELDS, " + ")
            val w = mapSummary(writeMapRows, ScannerField.WRITE_FIELDS, ", ")
            summaryView.text = when {
                l.isBlank() || w.isBlank() -> "↳ + Add diye lookup + write row যোগ করুন।"
                else -> "✅ $l মিলিয়ে row খুঁজে $w বসবে।"
            }
        }

        // Lookup + write section containers (rows added dynamically).
        val lookupMapBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val writeMapBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        /** Same cross-disable as CC dialog: lookup-used cols inactive in
         *  write dropdown and vice versa. */
        open class UsedAdapter(
            private val c: Context,
            private val items: List<String>,
        ) : ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, items) {
            var used: Set<Int> = emptySet()
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            override fun isEnabled(position: Int) = !used.contains(position)
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                if (used.contains(position)) {
                    v.setTextColor(0xFF9CA3AF.toInt())
                    v.text = "${items[position]} (used)"
                } else {
                    v.setTextColor(c.getColor(R.color.theme_text_primary))
                    v.text = items[position]
                }
                return v
            }
        }
        var lookupColAdapter: UsedAdapter? = null
        var writeColAdapter: UsedAdapter? = null

        fun updateMappingUsed() {
            val la = lookupColAdapter ?: return
            val wa = writeColAdapter ?: return
            la.used = writeMapRows.map { it.colPos() }.toSet()
            wa.used = lookupMapRows.map { it.colPos() }.toSet()
            la.notifyDataSetChanged()
            wa.notifyDataSetChanged()
        }
        fun addMapRow(
            box: LinearLayout, list: MutableList<MapRow>,
            fields: List<String>, preCol: String, preField: String,
            colAdapter: UsedAdapter?,
        ) {
            val cols = mapCols
            if (cols.isEmpty()) return
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card_rounded)
                val p = (ctx.resources.displayMetrics.density * 10).toInt()
                setPadding(p, p / 2, p, p / 2)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = p / 2
                }
            }
            val tv = TextView(ctx).apply {
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ctx.getColor(R.color.theme_text_primary))
            }
            val spCol = Spinner(ctx).apply {
                if (colAdapter != null) adapter = colAdapter
                else {
                    adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, colLabels())
                        .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            spCol.setSelection(cols.indexOfFirst {
                it.colRef.trim().equals(preCol.trim(), ignoreCase = true)
            }.takeIf { it >= 0 }?.coerceIn(cols.indices) ?: 0)
            val spField = Spinner(ctx).apply {
                adapter = fieldAdapter(fields)
                val all = listOf("") + fields
                setSelection(all.indexOf(preField).takeIf { it >= 0 } ?: 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            fun syncLabel() {
                val c = cols.getOrNull(spCol.selectedItemPosition)
                val all = listOf("") + fields
                val f = all.getOrNull(spField.selectedItemPosition).orEmpty()
                val lib = currentLib
                tv.text = "${if (c != null) colLabels().getOrNull(cols.indexOf(c)) ?: c.colRef.trim() else "?"}  →  " +
                    (ScannerField.label(f).takeIf { f.isNotBlank() } ?: "?")
            }
            val selListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    syncLabel()
                    refreshSummary()
                    updateMappingUsed()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            spCol.onItemSelectedListener = selListener
            spField.onItemSelectedListener = selListener
            val del = TextView(ctx).apply {
                text = "✕ Remove"
                textSize = 12f
                setTextColor(ctx.getColor(R.color.theme_text_secondary))
                setPadding(0, 8, 0, 4)
            }
            val mr = MapRow(
                colPos = { spCol.selectedItemPosition },
                fieldPos = { spField.selectedItemPosition },
            )
            del.setOnClickListener {
                box.removeView(row)
                list.remove(mr)
                refreshSummary()
                updateMappingUsed()
            }
            list.add(mr)
            row.addView(tv); row.addView(spCol); row.addView(spField); row.addView(del)
            box.addView(row)
            syncLabel()
            updateMappingUsed()
        }

        fun collectMaps(
            list: List<MapRow>, fields: List<String>,
        ): List<ScannerFieldMap> {
            val cols = mapCols
            return list.mapNotNull { r ->
                val c = cols.getOrNull(r.colPos()) ?: return@mapNotNull null
                val field = (listOf("") + fields).getOrNull(r.fieldPos()).orEmpty()
                if (field.isBlank()) return@mapNotNull null
                ScannerFieldMap(colRef = c.colRef.trim(), mode = SheetColMode.INDEX, field = field)
            }
        }



        // ── Ignore helpers ─────────────────────────────────────────────
        data class IgnoreRow(
            val colPos: () -> Int,
            val opPos: () -> Int,
            val typePos: () -> Int,
            val valueText: () -> String,
        )
        val ignoreRows = mutableListOf<IgnoreRow>()

        fun allLibCols(): List<Pair<String, SheetColRef>> {
            val lib = currentLib
            return mapCols.map { col ->
                (lib?.colLabel(col.colRef) ?: col.colRef.trim()) to col
            }
        }

        fun refreshIgnoreSummary() {
            val n = ignoreRows.size
            val logic = if (ignoreLogicSpinner.selectedItemPosition == 1) "ALL" else "ANY"
            ignoreSummaryView.text = if (n == 0) "⛔ Ignore rule nei — sob matched row cholbe"
            else "⛔ $n rule • $logic — match korle row skip"
        }

        fun pickFilterDate(target: EditText) {
            try {
                val cal = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("Asia/Dhaka"))
                runCatching {
                    val parts = target.text?.toString()?.trim()?.split("-")
                    if (parts != null && parts.size == 3) {
                        cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                    }
                }
                android.app.DatePickerDialog(
                    ctx,
                    { _, y, m, d -> target.setText("%04d-%02d-%02d".format(y, m + 1, d)) },
                    cal.get(java.util.Calendar.YEAR),
                    cal.get(java.util.Calendar.MONTH),
                    cal.get(java.util.Calendar.DAY_OF_MONTH),
                ).show()
            } catch (_: Exception) { }
        }

        fun addIgnoreRow(preselected: CcFetchFilter?) {
            val cols = allLibCols()
            if (cols.isEmpty()) return
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card_rounded)
                val p = (ctx.resources.displayMetrics.density * 10).toInt()
                setPadding(p, p / 2, p, p / 2)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = p / 2
                }
            }
            val spCol = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                    cols.map { it.first })
                    .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            spCol.setSelection(cols.indexOfFirst {
                it.second.colRef.trim().equals(preselected?.colRef?.trim().orEmpty(), ignoreCase = true)
            }.takeIf { it >= 0 }?.coerceIn(cols.indices) ?: 0)
            val spOp = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                    CcFilterOp.ALL.map { CcFilterOp.label(it) })
                    .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            spOp.setSelection(CcFilterOp.ALL.indexOf(preselected?.op).takeIf { it >= 0 } ?: 0)
            val spType = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                    CcValueType.ALL.map { CcValueType.label(it) })
                    .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            spType.setSelection(
                CcValueType.ALL.indexOf(preselected?.valueType).takeIf { it >= 0 } ?: 0)
            val etVal = EditText(ctx).apply {
                hint = "value"
                setText(preselected?.value.orEmpty())
                textSize = 13f
                setSingleLine()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) = refreshIgnoreSummary()
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            val autoLbl = TextView(ctx).apply {
                text = "📅 আজকের তারিখ (auto)"
                textSize = 12f
                setTextColor(ctx.getColor(R.color.theme_text_secondary))
                setPadding(0, 4, 0, 4)
            }
            val btnDate = Button(ctx).apply {
                text = "📅 তারিখ বাছুন"
                setOnClickListener { pickFilterDate(etVal) }
            }
            fun currentType() = CcValueType.ALL.getOrNull(spType.selectedItemPosition)
                ?: CcValueType.TEXT
            fun syncValVisibility() {
                val op = CcFilterOp.ALL.getOrNull(spOp.selectedItemPosition).orEmpty()
                val t = currentType()
                val need = CcFilterOp.needsValue(op)
                etVal.visibility = if (need && t != CcValueType.TODAY) View.VISIBLE else View.GONE
                btnDate.visibility =
                    if (need && t == CcValueType.DATE) View.VISIBLE else View.GONE
                autoLbl.visibility =
                    if (need && t == CcValueType.TODAY) View.VISIBLE else View.GONE
            }
            val selListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (currentType() == CcValueType.NUMBER) {
                        etVal.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                            android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    } else {
                        etVal.inputType = android.text.InputType.TYPE_CLASS_TEXT
                    }
                    syncValVisibility()
                    refreshIgnoreSummary()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            spCol.onItemSelectedListener = selListener
            spOp.onItemSelectedListener = selListener
            spType.onItemSelectedListener = selListener
            val del = TextView(ctx).apply {
                text = "✕ Remove"
                textSize = 12f
                setTextColor(ctx.getColor(R.color.theme_text_secondary))
                setPadding(0, 8, 0, 4)
            }
            val fr = IgnoreRow(
                colPos = { spCol.selectedItemPosition },
                opPos = { spOp.selectedItemPosition },
                typePos = { spType.selectedItemPosition },
                valueText = { etVal.text?.toString().orEmpty() },
            )
            del.setOnClickListener {
                ignoreBox.removeView(row)
                ignoreRows.remove(fr)
                refreshIgnoreSummary()
            }
            ignoreRows.add(fr)
            row.addView(spCol); row.addView(spOp); row.addView(spType)
            row.addView(etVal); row.addView(autoLbl); row.addView(btnDate); row.addView(del)
            ignoreBox.addView(row)
            syncValVisibility()
            refreshIgnoreSummary()
        }

        /** Calendar → EditText (yyyy-MM-dd), Dhaka today preselected. */

        fun renderIgnoreSection() {
            ignoreBox.removeAllViews()
            ignoreRows.clear()
            ignoreLogicSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                listOf("ANY — একটা মিললেই বাদ", "ALL — সব মিললে বাদ"))
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            ignoreLogicSpinner.setSelection(
                if (currentBinding?.ignoreLogic == CcFilterLogic.AND) 1 else 0)
            ignoreLogicSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshIgnoreSummary()
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            (currentBinding?.ignoreRules.orEmpty()).forEach { addIgnoreRow(it) }
            refreshIgnoreSummary()
        }

        fun collectIgnore(): Pair<String, List<CcFetchFilter>> {
            val cols = allLibCols()
            val logic = if (ignoreLogicSpinner.selectedItemPosition == 1) CcFilterLogic.AND else CcFilterLogic.OR
            val rules = ignoreRows.mapNotNull { r ->
                val c = cols.getOrNull(r.colPos())?.second ?: return@mapNotNull null
                val op = CcFilterOp.ALL.getOrNull(r.opPos()).orEmpty()
                if (op.isBlank()) return@mapNotNull null
                val t = CcValueType.ALL.getOrNull(r.typePos()) ?: CcValueType.TEXT
                CcFetchFilter(colRef = c.colRef.trim(), mode = c.mode, op = op,
                    value = r.valueText().trim(), valueType = t)
            }
            return logic to rules
        }

        fun renderMapping() {
            mappingBox.removeAllViews()
            lookupMapRows.clear()
            writeMapRows.clear()
            val lib = selectedLibrary()
            if (lib == null) {
                statusView.text = "এই branch-এ কোনো sheet library নেই — আগে Config → Connectors থেকে বানান।"
                summaryView.text = ""
                mapCols = emptyList()
                currentLib = null
                currentBinding = null
                renderIgnoreSection()
                dialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
                return
            }
            currentBinding = bindings.firstOrNull { it.libraryId == lib.libraryId && it.enabled }
                ?: bindings.firstOrNull { it.libraryId == lib.libraryId }
            currentLib = lib
            // Columns come from the library RANGE + saved headers.
            mapCols = lib.columnLetters().map { SheetColRef(it, SheetColMode.INDEX) }
            if (mapCols.isEmpty()) {
                statusView.text = "“${lib.nickname.ifBlank { lib.sheetName }}”-এ column range nei — library edit করে range দিন।"
                summaryView.text = ""
                renderIgnoreSection()
                dialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
                return
            }
            val boundTxt = currentBinding?.let { "\n🔌 Bound: ${it.summary()}" }.orEmpty()
            statusView.text = "“${lib.nickname.ifBlank { lib.sheetName }}” • Tab: ${lib.tabPattern}$boundTxt"
            lookupColAdapter = UsedAdapter(ctx, colLabels())
            writeColAdapter = UsedAdapter(ctx, colLabels())
            mappingBox.addView(label("LOOKUP — + Add diye multiple criteria (sobgulo milte hobe)"))
            val lookupRowsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            mappingBox.addView(lookupRowsBox)
            (currentBinding?.lookups.orEmpty()).forEach { m ->
                addMapRow(lookupRowsBox, lookupMapRows, ScannerField.LOOKUP_FIELDS, m.colRef, m.field, lookupColAdapter)
            }
            mappingBox.addView(Button(ctx).apply {
                text = "+ Add lookup"
                setOnClickListener { addMapRow(lookupRowsBox, lookupMapRows, ScannerField.LOOKUP_FIELDS, "", "", lookupColAdapter) }
            })
            mappingBox.addView(label("WRITE — single ba multiple column"))
            val writeRowsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            mappingBox.addView(writeRowsBox)
            (currentBinding?.writes.orEmpty()).forEach { m ->
                addMapRow(writeRowsBox, writeMapRows, ScannerField.WRITE_FIELDS, m.colRef, m.field, writeColAdapter)
            }
            mappingBox.addView(Button(ctx).apply {
                text = "+ Add write"
                setOnClickListener { addMapRow(writeRowsBox, writeMapRows, ScannerField.WRITE_FIELDS, "", "", writeColAdapter) }
            })
            dialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
            refreshSummary()
            updateMappingUsed()
            renderIgnoreSection()
        }
        fun loadLibrariesAndBindings() {
            val branchId = selectedBranchId()
            if (branchId.isBlank()) return
            statusView.text = "⏳ Library আসছে..."
            mappingBox.removeAllViews()
            summaryView.text = ""
            ignoreBox.removeAllViews()
            ignoreRows.clear()
            ignoreSummaryView.text = ""
            scope.launch {
                val libs = SheetLibraryRepository.loadLibraries(branchId)
                val binds = SheetLibraryRepository.loadScannerBindings(branchId)
                withContext(Dispatchers.Main) {
                    libraries = libs
                    bindings = binds
                    val labels = libs.map {
                        val base = it.nickname.ifBlank { it.sheetName.ifBlank { "(নাম নেই)" } }
                        if (it.enabled) "$base • ${it.sheetName}" else "$base • disabled"
                    }
                    librarySpinner.adapter = ArrayAdapter(
                        ctx, android.R.layout.simple_spinner_item,
                        labels.ifEmpty { listOf("— কোনো library নেই —") }
                    ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    renderMapping()
                }
            }
        }

        branchSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = loadLibrariesAndBindings()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        librarySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = renderMapping()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        scope.launch {
            val ids = RbacManager.current.branchIds
            val supaNames = runCatching { SupabaseBranchReader.listBranches() }
                .getOrNull().orEmpty().associate { it.branchId to it.name }
            branches = ids.map { id ->
                id to (supaNames[id]?.takeIf { it.isNotBlank() } ?: id)
            }
            withContext(Dispatchers.Main) {
                if (branches.isEmpty()) {
                    statusView.text = "কোনো branch assigned নেই।"
                    return@withContext
                }
                branchSpinner.adapter = ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_item, branches.map { it.second }
                ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                loadLibrariesAndBindings()
            }
        }

        dialog = android.app.AlertDialog.Builder(ctx)
            .setTitle("🔌 Scanner ↔ Sheet binding")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNeutralButton("Delete", null)
            .setNegativeButton("Cancel", null)
            .create()
        // "+ Add ignore" lives right above the ignore summary (declared up
        // front it can't see addIgnoreRow yet — local funs resolve in order).
        root.addView(Button(ctx).apply {
            text = "+ Add ignore"
            setOnClickListener { addIgnoreRow(null) }
        }, root.indexOfChild(ignoreSummaryView))
        dialog?.setOnShowListener {
            val saveBtn = dialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            saveBtn?.setOnClickListener {
                val branchId = selectedBranchId()
                val lib = selectedLibrary()
                if (branchId.isBlank() || lib == null) return@setOnClickListener
                fun collect(
                    rows: List<MapRow>, fields: List<String>,
                ): List<ScannerFieldMap> {
                    val cols = mapCols
                    return rows.mapNotNull { r ->
                        val c = cols.getOrNull(r.colPos()) ?: return@mapNotNull null
                        val field = (listOf("") + fields).getOrNull(r.fieldPos()).orEmpty()
                        if (field.isBlank()) return@mapNotNull null
                        ScannerFieldMap(colRef = c.colRef.trim(), mode = SheetColMode.INDEX, field = field)
                    }
                }
                val lookups = collect(lookupMapRows, ScannerField.LOOKUP_FIELDS)
                val writes = collect(writeMapRows, ScannerField.WRITE_FIELDS)
                if (lookups.isEmpty() || writes.isEmpty()) {
                    Toast.makeText(ctx, "Lookup + Write অন্তত 1টা করে row যোগ করুন (+ Add)", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                saveBtn.isEnabled = false
                scope.launch {
                    try {
                        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                        val actingName = withContext(Dispatchers.IO) {
                            runCatching {
                                FirebaseDatabase.getInstance()
                                    .reference.child("users/$uid/profile/name")
                                    .get().await().getValue(String::class.java)
                            }.getOrNull().orEmpty()
                        }
                        SheetLibraryRepository.saveScannerBinding(
                            ScannerBinding(
                                bindingId = currentBinding?.bindingId.orEmpty(),
                                libraryId = lib.libraryId,
                                branchId = branchId,
                                lookups = lookups,
                                writes = writes,
                                enabled = true,
                                ignoreLogic = collectIgnore().first,
                                ignoreRules = collectIgnore().second,
                            ),
                            uid, actingName,
                        )
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "✅ Scanner binding saved", Toast.LENGTH_SHORT).show()
                            dialog?.dismiss()
                            onSaved()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                            saveBtn.isEnabled = true
                        }
                    }
                }
            }
            dialog?.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                val existing = currentBinding
                if (existing == null || existing.bindingId.isBlank()) {
                    Toast.makeText(ctx, "মুছার মতো binding নেই", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                scope.launch {
                    try {
                        SheetLibraryRepository.deleteScannerBinding(selectedBranchId(), existing.bindingId)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "🗑 Binding deleted", Toast.LENGTH_SHORT).show()
                            dialog?.dismiss()
                            onSaved()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialog?.show()
    }
}
