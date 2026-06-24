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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 🏷️ Statuses Config Tab
 * JSX equivalent: StatusConfig component
 *
 * Features:
 *  - List all statuses sorted by priority
 *  - Edit built-in & custom statuses (bn name, en name, color, priority)
 *  - Create new custom status
 *  - Delete custom status with remark migration
 *  - Color picker (10 preset colors from STATUS_COLORS)
 *
 * Firebase: config/statusMeta/{key}/...
 */
class ConfigStatusesFragment : Fragment() {

    private val db = FirebaseDatabase.getInstance()

    private lateinit var statusListContainer: LinearLayout
    private lateinit var etNewKey:            EditText
    private lateinit var etNewBn:             EditText
    private lateinit var etNewEn:             EditText
    private lateinit var etNewPriority:       EditText
    private lateinit var colorPickerNew:      LinearLayout
    private lateinit var tvColorPreviewNew:   TextView
    private lateinit var tvCreateError:       TextView
    private lateinit var btnCreate:           Button
    private lateinit var btnOpenCreate:       TextView
    private lateinit var tvEmpty:             TextView
    private lateinit var busyOverlay:         View
    private lateinit var tvBusy:              TextView

    // selected color for new status form
    private var newColorIdx: Int = 0
    private val statusColors = ConfigState.STATUS_COLORS

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_config_statuses, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusListContainer = view.findViewById(R.id.statusListContainer)
        etNewKey            = view.findViewById(R.id.etNewStatusKey)
        etNewBn             = view.findViewById(R.id.etNewStatusBn)
        etNewEn             = view.findViewById(R.id.etNewStatusEn)
        etNewPriority       = view.findViewById(R.id.etNewStatusPriority)
        colorPickerNew      = view.findViewById(R.id.colorPickerNew)
        tvColorPreviewNew   = view.findViewById(R.id.tvColorPreviewNew)
        tvCreateError       = view.findViewById(R.id.tvCreateError)
        btnCreate           = view.findViewById(R.id.btnCreateStatus)
        btnOpenCreate       = view.findViewById(R.id.btnOpenCreateStatus)
        tvEmpty             = view.findViewById(R.id.tvStatusEmpty)
        busyOverlay         = view.findViewById(R.id.statusBusyOverlay)
        tvBusy              = view.findViewById(R.id.tvStatusBusy)

        buildColorPicker(colorPickerNew, newColorIdx) { idx ->
            newColorIdx = idx
            updateCreateColorPreview()
        }
        updateCreateColorPreview()
        loadFromFirebase()

        btnCreate.setOnClickListener { handleCreate() }
        btnOpenCreate.setOnClickListener { openCreateDialog() }
    }

    // ── Firebase load ─────────────────────────────────────────────────────────
    private fun loadFromFirebase() {
        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Loading...")
            val loaded = reloadConfig()
            if (!loaded) Toast.makeText(requireContext(), "Status load failed", Toast.LENGTH_LONG).show()
            if (isAdded) {
                bindStatusList()
                setBusy(false)
            }
        }
    }

    private suspend fun reloadConfig(): Boolean =
        try {
            val statusSnap = db.reference.child("config/statusMeta").get().await()
            val loadedMeta = mutableMapOf<String, ConfigState.StatusMeta>()
            val loadedStatuses = mutableListOf<String>()
            if (statusSnap.exists()) {
                statusSnap.children.forEach { s ->
                    val key = s.key ?: return@forEach
                    val bn = s.child("bn").getValue(String::class.java) ?: ""
                    val en = s.child("en").getValue(String::class.java) ?: ""
                    val color = s.child("color").getValue(String::class.java) ?: "#6B7280"
                    val bg = s.child("bg").getValue(String::class.java) ?: "#F3F4F6"
                    val pri = s.child("priority").getValue(Int::class.java) ?: 0
                    loadedMeta[key] = ConfigState.StatusMeta(bn, en, color, bg, pri, false)
                    loadedStatuses.add(key)
                }
            }
            ConfigState.statusMeta = loadedMeta
            ConfigState.statuses = loadedStatuses
            loadRemarks()
            true
        } catch (e: Exception) {
            Log.e("ConfigStatuses", "Failed to load config", e)
            false
        }

    private suspend fun loadRemarks() {
        val snap = db.reference.child("config/remarks").get().await()
        if (!snap.exists()) {
            ConfigState.remarks = mutableMapOf()
            return
        }

        val loaded = mutableMapOf<String, MutableList<ConfigState.Remark>>()
        snap.children.forEach { statusSnap ->
            val key = statusSnap.key ?: return@forEach
            val list = mutableListOf<ConfigState.Remark>()
            statusSnap.children.forEach { r ->
                val id = r.child("id").getValue(String::class.java) ?: return@forEach
                val textBn = r.child("text_bn").getValue(String::class.java) ?: ""
                val textEn = r.child("text_en").getValue(String::class.java) ?: ""
                val targetStatus = r.child("target_status").getValue(String::class.java) ?: key
                list.add(ConfigState.Remark(id, textBn, textEn, targetStatus))
            }
            loaded[key] = list
        }
        ConfigState.remarks = loaded
    }

    // ── Bind status list ──────────────────────────────────────────────────────
    private fun bindStatusList() {
        statusListContainer.removeAllViews()
        val sorted = sortedStatuses()
        tvEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        sorted.forEach { key ->
            val meta    = ConfigState.statusMeta[key] ?: return@forEach
            val count   = ConfigState.remarks[key]?.size ?: 0

            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_status_row, statusListContainer, false)

            val dot = row.findViewById<View>(R.id.viewStatusDot)
            dot.setBackgroundColor(android.graphics.Color.parseColor(meta.color))

            row.findViewById<TextView>(R.id.tvStatusBn).text = meta.bn
            row.findViewById<TextView>(R.id.tvStatusEn).text = meta.en

            val tvCustom = row.findViewById<TextView>(R.id.tvStatusCustomBadge)
            tvCustom.visibility = View.GONE

            row.findViewById<TextView>(R.id.tvStatusSubtitle).text =
                "$key · Priority: ${meta.priority} · $count remark${if (count != 1) "s" else ""}"

            row.findViewById<View>(R.id.btnEditStatus).setOnClickListener { openEditDialog(key) }

            val btnDel = row.findViewById<View>(R.id.btnDeleteStatus)
            btnDel.visibility = View.VISIBLE
            btnDel.setOnClickListener { openDeleteDialog(key) }

            statusListContainer.addView(row)
        }
    }

    // ── Edit dialog (openEdit + saveEdit in JSX) ──────────────────────────────
    private fun openEditDialog(key: String) {
        val meta = ConfigState.statusMeta[key] ?: return
        val ctx  = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_status, null)

        val etBn   = view.findViewById<EditText>(R.id.etEditStatusBn)
        val etEn   = view.findViewById<EditText>(R.id.etEditStatusEn)
        val etPri  = view.findViewById<EditText>(R.id.etEditStatusPriority)
        val picker = view.findViewById<LinearLayout>(R.id.colorPickerEdit)
        val tvPrev = view.findViewById<TextView>(R.id.tvEditColorPreview)
        val tvHint = view.findViewById<TextView>(R.id.tvEditStatusKeyHint)

        tvHint.text = key
        etBn.setText(meta.bn)
        etEn.setText(meta.en)
        etPri.setText(meta.priority.toString())

        // Find matching color index
        var editColorIdx = statusColors.indexOfFirst { it.first == meta.color }.coerceAtLeast(0)
        buildColorPicker(picker, editColorIdx) { idx ->
            editColorIdx = idx
            val (c, bg) = statusColors[idx]
            tvPrev.text = etBn.text.toString().ifEmpty { etEn.text.toString().ifEmpty { key } }
            tvPrev.setTextColor(android.graphics.Color.parseColor(c))
            tvPrev.setBackgroundColor(android.graphics.Color.parseColor(bg))
        }
        val (c0, bg0) = statusColors[editColorIdx]
        tvPrev.text = meta.bn
        tvPrev.setTextColor(android.graphics.Color.parseColor(c0))
        tvPrev.setBackgroundColor(android.graphics.Color.parseColor(bg0))

        AlertDialog.Builder(ctx)
            .setTitle("Edit Status")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val newBn  = etBn.text.toString().trim().ifEmpty { meta.bn }
                val newEn  = etEn.text.toString().trim().ifEmpty { meta.en }
                val newPri = etPri.text.toString().toIntOrNull() ?: meta.priority
                val (nc, nb) = statusColors[editColorIdx]
                val updated = meta.copy(bn = newBn, en = newEn, color = nc, bg = nb, priority = newPri)
                val newMeta = ConfigState.statusMeta.toMutableMap()
                newMeta[key] = updated
                ConfigState.statusMeta = newMeta
                viewLifecycleOwner.lifecycleScope.launch {
                    setBusy(true, "Saving...")
                    if (saveStatusMeta()) {
                        reloadConfig()
                        bindStatusList()
                        setBusy(false)
                        Toast.makeText(ctx, "Changed", Toast.LENGTH_SHORT).show()
                    } else {
                        setBusy(false)
                        Toast.makeText(ctx, "Status save failed", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    // ── Delete dialog (openDelete + confirmDelete in JSX) ─────────────────────
    private fun openDeleteDialog(key: String) {
        val ctx    = requireContext()
        val others = ConfigState.statuses.filter { it != key }
        val toMigrateCount = ConfigState.remarks[key]?.size ?: 0

        val spinMigrate = Spinner(ctx)
        val adapter     = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
            others.map { ConfigState.statusMeta[it]?.en ?: it })
        spinMigrate.adapter = adapter

        val msg = if (toMigrateCount > 0)
            if (others.isEmpty()) "$toMigrateCount রিমার্ক মুছে যাবে"
            else "$toMigrateCount রিমার্ক অন্য status-এ মাইগ্রেট হবে"
        else "এই status স্থায়ীভাবে মুছে যাবে"

        AlertDialog.Builder(ctx)
            .setTitle("Delete $key?")
            .setMessage(msg)
            .apply { if (toMigrateCount > 0 && others.isNotEmpty()) setView(spinMigrate) }
            .setPositiveButton("Delete") { _, _ ->
                val migrateTarget = if (toMigrateCount > 0 && others.isNotEmpty())
                    others.getOrElse(spinMigrate.selectedItemPosition) { others.first() }
                else null
                confirmDelete(key, migrateTarget)
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun confirmDelete(key: String, migrateTarget: String?) {
        // Migrate remarks (mirrors JSX confirmDelete)
        val toMigrate = ConfigState.remarks[key] ?: emptyList()
        if (migrateTarget != null && toMigrate.isNotEmpty()) {
            val updated = toMigrate.map { it.copy(target_status = migrateTarget) }
            ConfigState.remarks.getOrPut(migrateTarget) { mutableListOf() }.addAll(updated)
        }
        ConfigState.remarks.remove(key)

        ConfigState.statuses = ConfigState.statuses.filter { it != key }
        val newMeta = ConfigState.statusMeta.toMutableMap()
        newMeta.remove(key)
        ConfigState.statusMeta = newMeta

        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Deleting...")
            if (saveStatusMeta() && saveRemarks()) {
                reloadConfig()
                bindStatusList()
                setBusy(false)
                Toast.makeText(requireContext(), "Deleted", Toast.LENGTH_SHORT).show()
            } else {
                setBusy(false)
                Toast.makeText(requireContext(), "Status delete save failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Create new status ─────────────────────────────────────────────────────
    private fun handleCreate() {
        tvCreateError.visibility = View.GONE
        val rawKey = etNewKey.text.toString().trim().uppercase().replace("\\s+".toRegex(), "_")
        val bn     = etNewBn.text.toString().trim()
        val en     = etNewEn.text.toString().trim()
        val pri    = etNewPriority.text.toString().toIntOrNull() ?: 0

        if (rawKey.isEmpty()) { showError("Status key দিন"); return }
        if (ConfigState.statuses.contains(rawKey)) { showError("এই key ইতিমধ্যে আছে"); return }
        if (bn.isEmpty() && en.isEmpty()) { showError("নাম দিন"); return }

        val (nc, nb) = statusColors[newColorIdx]
        val newMeta  = ConfigState.statusMeta.toMutableMap()
        newMeta[rawKey] = ConfigState.StatusMeta(
            bn       = bn.ifEmpty { en },
            en       = en.ifEmpty { bn },
            color    = nc,
            bg       = nb,
            priority = pri,
            builtIn  = false,
        )
        ConfigState.statusMeta = newMeta
        ConfigState.statuses   = ConfigState.statuses + rawKey
        ConfigState.remarks.getOrPut(rawKey) { mutableListOf() }

        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Creating...")
            if (saveStatusMeta()) {
                reloadConfig()
                bindStatusList()
                setBusy(false)
                Toast.makeText(requireContext(), "Created", Toast.LENGTH_SHORT).show()
            } else {
                setBusy(false)
                Toast.makeText(requireContext(), "Status create failed", Toast.LENGTH_LONG).show()
            }
        }
        etNewKey.setText(""); etNewBn.setText(""); etNewEn.setText(""); etNewPriority.setText("0")
        newColorIdx = 0; updateCreateColorPreview()
        bindStatusList()
    }

    private fun openCreateDialog() {
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 12, 32, 0)
        }

        fun label(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 10f
            setTextColor(android.graphics.Color.parseColor("#9CA3AF"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 10, 0, 4)
        }

        fun input(hint: String, inputType: Int = android.text.InputType.TYPE_CLASS_TEXT) =
            EditText(ctx).apply {
                this.hint = hint
                textSize = 13f
                setPadding(16, 10, 16, 10)
                background = resources.getDrawable(R.drawable.bg_input_rounded, ctx.theme)
                this.inputType = inputType
            }

        val keyInput = input("e.g. PARTIAL", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val bnInput = input("বাংলা...")
        val enInput = input("English...")
        val priorityInput = input("0", android.text.InputType.TYPE_CLASS_NUMBER)
        val picker = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val preview = TextView(ctx).apply {
            text = "Preview"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(10, 3, 10, 3)
        }
        var selectedColorIdx = 0
        fun updatePreview() {
            val (c, bg) = statusColors[selectedColorIdx]
            preview.text = bnInput.text.toString().ifEmpty { enInput.text.toString().ifEmpty { "Preview" } }
            preview.setTextColor(android.graphics.Color.parseColor(c))
            preview.setBackgroundColor(android.graphics.Color.parseColor(bg))
        }
        buildColorPicker(picker, selectedColorIdx) { idx ->
            selectedColorIdx = idx
            updatePreview()
        }
        updatePreview()

        content.addView(label("KEY"))
        content.addView(keyInput)
        content.addView(label("বাংলা"))
        content.addView(bnInput)
        content.addView(label("English"))
        content.addView(enInput)
        content.addView(label("Priority"))
        content.addView(priorityInput)
        content.addView(label("Color"))
        content.addView(picker)
        content.addView(preview)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("নতুন Status")
            .setView(content)
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("Create", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rawKey = keyInput.text.toString().trim().uppercase().replace("\\s+".toRegex(), "_")
                val bn = bnInput.text.toString().trim()
                val en = enInput.text.toString().trim()
                val pri = priorityInput.text.toString().toIntOrNull() ?: 0

                when {
                    rawKey.isEmpty() -> keyInput.error = "Status key দিন"
                    ConfigState.statuses.contains(rawKey) -> keyInput.error = "এই key ইতিমধ্যে আছে"
                    bn.isEmpty() && en.isEmpty() -> {
                        bnInput.error = "নাম দিন"
                        enInput.error = "নাম দিন"
                    }
                    else -> {
                        val (color, bg) = statusColors[selectedColorIdx]
                        dialog.dismiss()
                        createStatus(rawKey, bn, en, pri, color, bg)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun createStatus(
        key: String,
        bn: String,
        en: String,
        priority: Int,
        color: String,
        bg: String,
        onSuccess: () -> Unit = {},
    ) {
        val newMeta = ConfigState.statusMeta.toMutableMap()
        newMeta[key] = ConfigState.StatusMeta(
            bn = bn.ifEmpty { en },
            en = en.ifEmpty { bn },
            color = color,
            bg = bg,
            priority = priority,
            builtIn = false,
        )
        ConfigState.statusMeta = newMeta
        ConfigState.statuses = ConfigState.statuses + key
        ConfigState.remarks.getOrPut(key) { mutableListOf() }

        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Creating...")
            if (saveStatusMeta()) {
                reloadConfig()
                bindStatusList()
                setBusy(false)
                Toast.makeText(requireContext(), "Created", Toast.LENGTH_SHORT).show()
                onSuccess()
            } else {
                ConfigState.statuses = ConfigState.statuses.filter { it != key }
                val rolledBack = ConfigState.statusMeta.toMutableMap()
                rolledBack.remove(key)
                ConfigState.statusMeta = rolledBack
                ConfigState.remarks.remove(key)
                bindStatusList()
                setBusy(false)
                Toast.makeText(requireContext(), "Status create failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showError(msg: String) {
        tvCreateError.text = "⚠ $msg"
        tvCreateError.visibility = View.VISIBLE
    }

    // ── Color picker helper ───────────────────────────────────────────────────
    private fun buildColorPicker(container: LinearLayout, selectedIdx: Int, onPick: (Int) -> Unit) {
        container.removeAllViews()
        statusColors.forEachIndexed { i, (color, _) ->
            val dot = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(60, 60).apply { setMargins(6, 0, 6, 0) }
                setBackgroundColor(android.graphics.Color.parseColor(color))
            }
            if (i == selectedIdx) dot.alpha = 1f else dot.alpha = 0.4f
            dot.setOnClickListener {
                container.children.forEachIndexed { idx, v -> v.alpha = if (idx == i) 1f else 0.4f }
                onPick(i)
            }
            container.addView(dot)
        }
    }

    private fun updateCreateColorPreview() {
        val (c, bg) = statusColors[newColorIdx]
        val text = etNewBn.text.toString().ifEmpty { etNewEn.text.toString().ifEmpty { "Preview" } }
        tvColorPreviewNew.text = text
        tvColorPreviewNew.setTextColor(android.graphics.Color.parseColor(c))
        tvColorPreviewNew.setBackgroundColor(android.graphics.Color.parseColor(bg))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun sortedStatuses(): List<String> =
        ConfigState.statuses.sortedByDescending { ConfigState.statusMeta[it]?.priority ?: 0 }

    private suspend fun saveStatusMeta(): Boolean =
        try {
            val payload = mutableMapOf<String, Any>()
            ConfigState.statusMeta.forEach { (key, m) ->
                payload[key] = mapOf(
                    "bn"       to m.bn,
                    "en"       to m.en,
                    "color"    to m.color,
                    "bg"       to m.bg,
                    "priority" to m.priority,
                )
            }
            db.reference.child("config/statusMeta").setValue(payload).await()
            true
        } catch (e: Exception) {
            Log.e("ConfigStatuses", "Failed to save statusMeta", e)
            false
        }

    private suspend fun saveRemarks(): Boolean =
        try {
            val payload = mutableMapOf<String, Any>()
            ConfigState.remarks.forEach { (statusKey, list) ->
                if (list.isNotEmpty()) {
                    payload[statusKey] = list.mapIndexed { i, r ->
                        "$i" to mapOf(
                            "id"            to r.id,
                            "text_bn"       to r.text_bn,
                            "text_en"       to r.text_en,
                            "target_status" to r.target_status,
                        )
                    }.toMap()
                }
            }
            db.reference.child("config/remarks").setValue(payload).await()
            true
        } catch (e: Exception) {
            Log.e("ConfigStatuses", "Failed to save remarks", e)
            false
        }

    private fun setBusy(show: Boolean, text: String = "Loading...") {
        if (!::busyOverlay.isInitialized) return
        tvBusy.text = text
        busyOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    // Extension to iterate over LinearLayout children
    private val ViewGroup.children: Sequence<View>
        get() = sequence { for (i in 0 until childCount) yield(getChildAt(i)) }
}
