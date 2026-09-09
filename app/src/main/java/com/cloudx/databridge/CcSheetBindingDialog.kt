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
        root.addView(label("BRANCH"))
        root.addView(branchSpinner)
        root.addView(label("SHEET LIBRARY (Config → Connectors)"))
        root.addView(librarySpinner)
        root.addView(statusView)
        root.addView(mappingBox)
        root.addView(summaryView)
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
                setPadding(0, 6, 0, 2)
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
        fun renderMapping() {
            mappingBox.removeAllViews()
            lookupFieldSpinners.clear()
            writeFieldSpinners.clear()
            val lib = selectedLibrary()
            if (lib == null) {
                statusView.text = "এই branch-এ কোনো sheet library নেই — আগে Config → Connectors থেকে বানান।"
                summaryView.text = ""
                dialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
                return
            }
            currentBinding = bindings.firstOrNull { it.libraryId == lib.libraryId && it.enabled }
                ?: bindings.firstOrNull { it.libraryId == lib.libraryId }
            lookupCols = lib.effectiveLookupCols()
            writeCols = lib.effectiveWriteCols()
            if (lookupCols.isEmpty() || writeCols.isEmpty()) {
                statusView.text = "“${lib.nickname.ifBlank { lib.sheetName }}”-এ lookup/write column নেই — library edit করে column দিন।"
                summaryView.text = ""
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
        }
        fun loadLibrariesAndBindings() {
            val branchId = selectedBranchId()
            if (branchId.isBlank()) return
            statusView.text = "⏳ Library আসছে..."
            mappingBox.removeAllViews()
            summaryView.text = ""
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
