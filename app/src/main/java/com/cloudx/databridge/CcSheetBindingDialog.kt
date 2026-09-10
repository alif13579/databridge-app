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
 * ☎️ Call Center's socket: bind one sheet library to remark data.
 *
 * Admin picks an existing library (branch-wise) and maps, per column,
 * WHICH remark data flows where:
 * - Lookup columns → Consignment ID / Today / Feedback / ... (ALL must match)
 * - Write columns  → Feedback / Validation / Validator name (mirror never appends)
 *
 * One binding per library per branch (save upserts). Users never see
 * this — remark saves ([RemarkSheetMirror]) resolve bindings silently.
 * Opened from CallCenterFragment's 🔌 button (admin-only).
 */
object CcSheetBindingDialog {

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
        fun stepLabel(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.theme_text_primary))
            setPadding(0, 18, 0, 2)
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
        // Step 3: fetch criteria (Live ID list) — kon column theke ID +
        // kon columns filter (AND/OR). Na set korle default: 1st lookup col
        // theke ID, 1st write col blank filter.
        val fetchColSpinner = spinner()
        val filterLogicSpinner = spinner()
        val filterBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val fetchSummaryView = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(ctx.getColor(R.color.theme_text_primary))
            setPadding(0, 10, 0, 0)
        }
        // Step 4: ignore rules (exclusion) — match korle row skip, fetch +
        // write dujagay. ANY: ekta milllei skip; ALL: sob millei skip.
        val ignoreLogicSpinner = spinner()
        val ignoreBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val ignoreSummaryView = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ctx.getColor(R.color.theme_text_secondary))
            setPadding(0, 8, 0, 0)
        }
        root.addView(stepLabel("① Sheet"))
        root.addView(label("BRANCH"))
        root.addView(branchSpinner)
        root.addView(label("SHEET LIBRARY (Config → Connectors)"))
        root.addView(librarySpinner)
        root.addView(statusView)
        root.addView(stepLabel("② Mapping (remark save)"))
        root.addView(mappingBox)
        root.addView(summaryView)
        root.addView(stepLabel("③ Fetch criteria (Live list)"))
        root.addView(label("ID KON COLUMN THEKE ASBE"))
        root.addView(fetchColSpinner)
        root.addView(label("FILTER LOGIC"))
        root.addView(filterLogicSpinner)
        root.addView(filterBox)
        root.addView(fetchSummaryView)
        root.addView(stepLabel("④ Ignore (skip row — fetch + write)"))
        root.addView(label("LOGIC"))
        root.addView(ignoreLogicSpinner)
        root.addView(ignoreBox)
        root.addView(ignoreSummaryView)
        val scroll = ScrollView(ctx).apply { addView(root) }

        var dialog: android.app.AlertDialog? = null
        var branches: List<Pair<String, String>> = emptyList() // (id, name)
        var libraries: List<SheetLibrary> = emptyList()
        var bindings: List<CcBinding> = emptyList()
        var currentBinding: CcBinding? = null
        // Parallel lists with mappingBox children.
        val lookupFieldSpinners = mutableListOf<Spinner>()
        val writeFieldSpinners = mutableListOf<Spinner>()
        var lookupCols: List<SheetColRef> = emptyList()
        var writeCols: List<SheetColRef> = emptyList()

        fun selectedBranchId(): String {
            val pos = branchSpinner.selectedItemPosition
            return branches.getOrNull(pos)?.first.orEmpty()
        }
        fun selectedLibrary(): SheetLibrary? {
            val pos = librarySpinner.selectedItemPosition
            return libraries.getOrNull(pos)
        }
        fun refreshSummary() {
            val lib = selectedLibrary()
            if (lib == null) { summaryView.text = ""; return }
            fun picks(spinners: List<Spinner>, cols: List<SheetColRef>, fields: List<String>): String {
                return cols.mapIndexedNotNull { i, col ->
                    val pos = spinners.getOrNull(i)?.selectedItemPosition ?: 0
                    val field = fields.getOrNull(pos) ?: ""
                    if (field.isBlank()) null
                    else "${col.colRef.trim()}=${CcField.label(field)}"
                }.joinToString(" + ")
            }
            val l = picks(lookupFieldSpinners, lookupCols, listOf("") + CcField.LOOKUP_FIELDS)
            val w = picks(writeFieldSpinners, writeCols, listOf("") + CcField.WRITE_FIELDS)
            summaryView.text = when {
                l.isBlank() || w.isBlank() -> "↳ প্রতিটা column-এর পাশে কোন remark data বসবে বেছে দিন।"
                else -> "✅ $l মিলিয়ে row খুঁজে $w বসবে।"
            }
        }
        fun fieldAdapter(fields: List<String>): ArrayAdapter<String> {
            val labels = listOf("— field বেছে নিন —") + fields.map { CcField.label(it) }
            return ArrayAdapter(ctx, android.R.layout.simple_spinner_item, labels)
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        fun mapRow(col: SheetColRef, fields: List<String>, preselected: String): Spinner {
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
                text = "Column ${col.colRef.trim()}  →  ?"
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ctx.getColor(R.color.theme_text_primary))
            }
            val sp = Spinner(ctx).apply {
                adapter = fieldAdapter(fields)
                val all = listOf("") + fields
                setSelection(all.indexOf(preselected).takeIf { it >= 0 } ?: 0)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        val f = all.getOrNull(pos).orEmpty()
                        tv.text = "Column ${col.colRef.trim()}  →  " +
                            (CcField.label(f).takeIf { f.isNotBlank() } ?: "?")
                        refreshSummary()
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            row.addView(tv); row.addView(sp)
            mappingBox.addView(row)
            // Fire once so label + summary seed correctly.
            sp.onItemSelectedListener?.onItemSelected(null, null, sp.selectedItemPosition, 0)
            return sp
        }
        // ── Step 3 helpers ─────────────────────────────────────────────
        // Parallel option list for the fetch-column spinner (null = default).
        var fetchColOptions: List<SheetColRef?> = emptyList()
        data class FilterRow(
            val colPos: () -> Int,
            val opPos: () -> Int,
            val valueText: () -> String,
        )
        val filterRows = mutableListOf<FilterRow>()

        fun allLibCols(): List<Pair<String, SheetColRef>> {
            val lookups = (lookupCols).map { "🔍 ${it.colRef.trim()}" to it }
            val writes = (writeCols).map { "✏️ ${it.colRef.trim()}" to it }
            return lookups + writes
        }

        fun refreshFetchSummary() {
            val cols = allLibCols()
            val pos = fetchColSpinner.selectedItemPosition
            val colTxt = if (pos <= 0) "default (1st lookup)"
            else cols.getOrNull(pos - 1)?.first ?: "?"
            val logic = if (filterLogicSpinner.selectedItemPosition == 1) "OR" else "AND"
            val rules = filterRows.mapNotNull { r ->
                val entry = cols.getOrNull(r.colPos()) ?: return@mapNotNull null
                val op = CcFilterOp.ALL.getOrNull(r.opPos()).orEmpty()
                if (op.isBlank()) return@mapNotNull null
                val v = if (CcFilterOp.needsValue(op)) " “${r.valueText().trim()}”" else ""
                "${entry.first} ${CcFilterOp.label(op)}$v"
            }
            fetchSummaryView.text = when {
                rules.isEmpty() -> "📡 Live: $colTxt theke ID • filter nei (sob row)"
                else -> "📡 Live: $colTxt theke ID • ${rules.joinToString(if (logic == "OR") " OR " else " + ")}" +
                    if (rules.size > 1) " [${CcFilterLogic.label(logic)}]" else ""
            }
        }

        fun addFilterRow(preselected: CcFetchFilter?) {
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
            val preColPos = cols.indexOfFirst {
                it.second.colRef.trim().equals(preselected?.colRef?.trim().orEmpty(), ignoreCase = true)
            }
            spCol.setSelection((preColPos.takeIf { it >= 0 } ?: 0).coerceIn(cols.indices))
            val spOp = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                    CcFilterOp.ALL.map { CcFilterOp.label(it) })
                    .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            spOp.setSelection(CcFilterOp.ALL.indexOf(preselected?.op).takeIf { it >= 0 } ?: 0)
            val etVal = EditText(ctx).apply {
                hint = "value (equals/not-equals)"
                setText(preselected?.value.orEmpty())
                textSize = 13f
                setSingleLine()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) = refreshFetchSummary()
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            fun syncValVisibility() {
                val op = CcFilterOp.ALL.getOrNull(spOp.selectedItemPosition).orEmpty()
                etVal.visibility = if (CcFilterOp.needsValue(op)) View.VISIBLE else View.GONE
            }
            val selListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    syncValVisibility()
                    refreshFetchSummary()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            spCol.onItemSelectedListener = selListener
            spOp.onItemSelectedListener = selListener
            val del = TextView(ctx).apply {
                text = "✕ Remove filter"
                textSize = 12f
                setTextColor(ctx.getColor(R.color.theme_text_secondary))
                setPadding(0, 8, 0, 4)
            }
            val fr = FilterRow(
                colPos = { spCol.selectedItemPosition },
                opPos = { spOp.selectedItemPosition },
                valueText = { etVal.text?.toString().orEmpty() },
            )
            del.setOnClickListener {
                filterBox.removeView(row)
                filterRows.remove(fr)
                refreshFetchSummary()
            }
            filterRows.add(fr)
            row.addView(spCol); row.addView(spOp); row.addView(etVal); row.addView(del)
            filterBox.addView(row)
            syncValVisibility()
            refreshFetchSummary()
        }

        fun renderFetchSection() {
            filterBox.removeAllViews()
            filterRows.clear()
            val cols = allLibCols()
            val colLabels = listOf("(default: 1st lookup column)") + cols.map { it.first }
            fetchColSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, colLabels)
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val b = currentBinding
            val preFetch = b?.fetchColRef?.trim().orEmpty()
            val prePos = if (preFetch.isBlank()) 0
            else cols.indexOfFirst { it.second.colRef.trim().equals(preFetch, ignoreCase = true) }
                .takeIf { it >= 0 }?.plus(1) ?: 0
            fetchColSpinner.setSelection(prePos.coerceIn(colLabels.indices))
            fetchColSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshFetchSummary()
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            filterLogicSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                CcFilterLogic.ALL.map { CcFilterLogic.label(it) })
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            filterLogicSpinner.setSelection(if (b?.filterLogic == CcFilterLogic.OR) 1 else 0)
            filterLogicSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshFetchSummary()
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            (b?.filters.orEmpty()).forEach { addFilterRow(it) }
            refreshFetchSummary()
        }

        fun collectFetch(): Triple<SheetColRef?, String, List<CcFetchFilter>> {
            val cols = allLibCols()
            val pos = fetchColSpinner.selectedItemPosition
            val col = if (pos <= 0) null else cols.getOrNull(pos - 1)?.second
            val logic = if (filterLogicSpinner.selectedItemPosition == 1) CcFilterLogic.OR else CcFilterLogic.AND
            val filters = filterRows.mapNotNull { r ->
                val c = cols.getOrNull(r.colPos())?.second ?: return@mapNotNull null
                val op = CcFilterOp.ALL.getOrNull(r.opPos()).orEmpty()
                if (op.isBlank()) return@mapNotNull null
                CcFetchFilter(colRef = c.colRef.trim(), mode = c.mode, op = op, value = r.valueText().trim())
            }
            return Triple(col, logic, filters)
        }

        // ── Step 4 helpers (ignore rules — same row shape as filters) ──
        val ignoreFilterRows = mutableListOf<FilterRow>()

        fun refreshIgnoreSummary() {
            val n = ignoreFilterRows.size
            val logic = if (ignoreLogicSpinner.selectedItemPosition == 1) "ALL" else "ANY"
            ignoreSummaryView.text = if (n == 0) "⛔ Ignore rule nei — sob matched row cholbe"
            else "⛔ $n rule • $logic — match korle row skip (fetch + write)"
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
            val etVal = EditText(ctx).apply {
                hint = "value (equals / > / < …)"
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
            fun syncValVisibility() {
                val op = CcFilterOp.ALL.getOrNull(spOp.selectedItemPosition).orEmpty()
                etVal.visibility = if (CcFilterOp.needsValue(op)) View.VISIBLE else View.GONE
            }
            val selListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    syncValVisibility()
                    refreshIgnoreSummary()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            spCol.onItemSelectedListener = selListener
            spOp.onItemSelectedListener = selListener
            val del = TextView(ctx).apply {
                text = "✕ Remove"
                textSize = 12f
                setTextColor(ctx.getColor(R.color.theme_text_secondary))
                setPadding(0, 8, 0, 4)
            }
            val fr = FilterRow(
                colPos = { spCol.selectedItemPosition },
                opPos = { spOp.selectedItemPosition },
                valueText = { etVal.text?.toString().orEmpty() },
            )
            del.setOnClickListener {
                ignoreBox.removeView(row)
                ignoreFilterRows.remove(fr)
                refreshIgnoreSummary()
            }
            ignoreFilterRows.add(fr)
            row.addView(spCol); row.addView(spOp); row.addView(etVal); row.addView(del)
            ignoreBox.addView(row)
            syncValVisibility()
            refreshIgnoreSummary()
        }

        fun renderIgnoreSection() {
            ignoreBox.removeAllViews()
            ignoreFilterRows.clear()
            ignoreLogicSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                listOf("ANY — ekta milllei skip", "ALL — sob millei skip"))
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            // Stored: OR = ANY (pos 0), AND = ALL (pos 1).
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
            val rules = ignoreFilterRows.mapNotNull { r ->
                val c = cols.getOrNull(r.colPos())?.second ?: return@mapNotNull null
                val op = CcFilterOp.ALL.getOrNull(r.opPos()).orEmpty()
                if (op.isBlank()) return@mapNotNull null
                CcFetchFilter(colRef = c.colRef.trim(), mode = c.mode, op = op, value = r.valueText().trim())
            }
            return logic to rules
        }

        fun renderMapping() {
            mappingBox.removeAllViews()
            lookupFieldSpinners.clear()
            writeFieldSpinners.clear()
            val lib = selectedLibrary()
            if (lib == null) {
                statusView.text = "এই branch-এ কোনো sheet library নেই — আগে Config → Connectors থেকে বানান।"
                summaryView.text = ""
                lookupCols = emptyList()
                writeCols = emptyList()
                currentBinding = null
                renderFetchSection()
                renderIgnoreSection()
                dialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
                return
            }
            currentBinding = bindings.firstOrNull { it.libraryId == lib.libraryId && it.enabled }
                ?: bindings.firstOrNull { it.libraryId == lib.libraryId }
            // Columns come from the library RANGE (letters) — mapping (which
            // column holds what) is defined here, per fragment.
            val rangeLetters = lib.columnLetters()
            lookupCols = rangeLetters.map { SheetColRef(it, SheetColMode.INDEX) }
            writeCols = rangeLetters.map { SheetColRef(it, SheetColMode.INDEX) }
            if (lookupCols.isEmpty() || writeCols.isEmpty()) {
                statusView.text = "“${lib.nickname.ifBlank { lib.sheetName }}”-এ lookup/write column নেই — library edit করে column দিন।"
                summaryView.text = ""
                renderFetchSection()
                renderIgnoreSection()
                dialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
                return
            }
            val boundTxt = currentBinding?.let { "\n☎️ Bound: ${it.summary()}" }.orEmpty()
            statusView.text = "“${lib.nickname.ifBlank { lib.sheetName }}” • Tab: ${lib.tabPattern}$boundTxt"
            mappingBox.addView(label("LOOKUP — সবগুলো মিললে row পাবে"))
            lookupCols.forEach { col ->
                val pre = currentBinding?.lookups
                    ?.firstOrNull { it.colRef.trim().equals(col.colRef.trim(), ignoreCase = true) }
                    ?.field.orEmpty()
                lookupFieldSpinners.add(mapRow(col, CcField.LOOKUP_FIELDS, pre))
            }
            mappingBox.addView(label("WRITE — matched row-এর খালি ঘরে বসবে"))
            writeCols.forEach { col ->
                val pre = currentBinding?.writes
                    ?.firstOrNull { it.colRef.trim().equals(col.colRef.trim(), ignoreCase = true) }
                    ?.field.orEmpty()
                writeFieldSpinners.add(mapRow(col, CcField.WRITE_FIELDS, pre))
            }
            dialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
            refreshSummary()
            renderFetchSection()
            renderIgnoreSection()
        }
        fun loadLibrariesAndBindings() {
            val branchId = selectedBranchId()
            if (branchId.isBlank()) return
            statusView.text = "⏳ Library আসছে..."
            mappingBox.removeAllViews()
            summaryView.text = ""
            filterBox.removeAllViews()
            filterRows.clear()
            fetchSummaryView.text = ""
            ignoreBox.removeAllViews()
            ignoreFilterRows.clear()
            ignoreSummaryView.text = ""
            scope.launch {
                val libs = SheetLibraryRepository.loadLibraries(branchId)
                val binds = SheetLibraryRepository.loadCcBindings(branchId)
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

        val btnAddFilter = Button(ctx).apply {
            text = "+ Add filter"
            setOnClickListener { addFilterRow(null) }
        }
        root.addView(btnAddFilter, root.indexOfChild(fetchSummaryView))
        val btnAddIgnore = Button(ctx).apply {
            text = "+ Add ignore"
            setOnClickListener { addIgnoreRow(null) }
        }
        root.addView(btnAddIgnore, root.indexOfChild(ignoreSummaryView))

        dialog = android.app.AlertDialog.Builder(ctx)
            .setTitle("☎️ CC ↔ Sheet binding")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNeutralButton("Delete", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog?.setOnShowListener {
            val saveBtn = dialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            saveBtn?.setOnClickListener {
                val branchId = selectedBranchId()
                val lib = selectedLibrary()
                if (branchId.isBlank() || lib == null) return@setOnClickListener
                fun collect(
                    spinners: List<Spinner>, cols: List<SheetColRef>, fields: List<String>,
                ): List<CcFieldMap> {
                    return cols.mapIndexedNotNull { i, col ->
                        val field = fields.getOrNull(spinners.getOrNull(i)?.selectedItemPosition ?: 0).orEmpty()
                        if (field.isBlank()) null
                        else CcFieldMap(colRef = col.colRef.trim(), mode = col.mode, field = field)
                    }
                }
                val lookups = collect(lookupFieldSpinners, lookupCols, listOf("") + CcField.LOOKUP_FIELDS)
                val writes = collect(writeFieldSpinners, writeCols, listOf("") + CcField.WRITE_FIELDS)
                if (lookups.isEmpty() || writes.isEmpty()) {
                    Toast.makeText(ctx, "Lookup + Write অন্তত 1টা করে field বেছে দিন", Toast.LENGTH_SHORT).show()
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
                        SheetLibraryRepository.saveCcBinding(
                            CcBinding(
                                bindingId = currentBinding?.bindingId.orEmpty(),
                                libraryId = lib.libraryId,
                                branchId = branchId,
                                lookups = lookups,
                                writes = writes,
                                enabled = true,
                                fetchColRef = collectFetch().first?.colRef.orEmpty(),
                                fetchColMode = collectFetch().first?.mode ?: SheetColMode.INDEX,
                                filterLogic = collectFetch().second,
                                filters = collectFetch().third,
                                ignoreLogic = collectIgnore().first,
                                ignoreRules = collectIgnore().second,
                            ),
                            uid, actingName,
                        )
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "✅ CC binding saved", Toast.LENGTH_SHORT).show()
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
                        SheetLibraryRepository.deleteCcBinding(selectedBranchId(), existing.bindingId)
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
