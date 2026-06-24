package com.cloudx.databridge

import android.app.AlertDialog
import android.os.Bundle
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

        buildColorPicker(colorPickerNew, newColorIdx) { idx ->
            newColorIdx = idx
            updateCreateColorPreview()
        }
        updateCreateColorPreview()
        loadFromFirebase()

        btnCreate.setOnClickListener { handleCreate() }
    }

    // ── Firebase load ─────────────────────────────────────────────────────────
    private fun loadFromFirebase() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val snap = db.reference.child("config/statusMeta").get().await()
                if (snap.exists()) {
                    val loaded = ConfigState.BASE_STATUS_META.toMutableMap()
                    snap.children.forEach { s ->
                        val key = s.key ?: return@forEach
                        val bn   = s.child("bn").getValue(String::class.java)   ?: ""
                        val en   = s.child("en").getValue(String::class.java)   ?: ""
                        val color= s.child("color").getValue(String::class.java) ?: "#6B7280"
                        val bg   = s.child("bg").getValue(String::class.java)   ?: "#F3F4F6"
                        val pri  = s.child("priority").getValue(Int::class.java) ?: 0
                        val bi   = ConfigState.BASE_STATUSES.contains(key)
                        loaded[key] = ConfigState.StatusMeta(bn, en, color, bg, pri, bi)
                        if (!ConfigState.statuses.contains(key)) {
                            ConfigState.statuses = ConfigState.statuses + key
                        }
                    }
                    ConfigState.statusMeta = loaded
                }
            } catch (_: Exception) {}
            if (isAdded) bindStatusList()
        }
    }

    // ── Bind status list ──────────────────────────────────────────────────────
    private fun bindStatusList() {
        statusListContainer.removeAllViews()
        val sorted = sortedStatuses()
        sorted.forEach { key ->
            val meta    = ConfigState.statusMeta[key] ?: return@forEach
            val count   = ConfigState.remarks[key]?.size ?: 0
            val isBuiltIn = ConfigState.BASE_STATUSES.contains(key)

            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_status_row, statusListContainer, false)

            val dot = row.findViewById<View>(R.id.viewStatusDot)
            dot.setBackgroundColor(android.graphics.Color.parseColor(meta.color))

            row.findViewById<TextView>(R.id.tvStatusBn).text = meta.bn
            row.findViewById<TextView>(R.id.tvStatusEn).text = meta.en

            val tvCustom = row.findViewById<TextView>(R.id.tvStatusCustomBadge)
            tvCustom.visibility = if (!isBuiltIn) View.VISIBLE else View.GONE
            if (!isBuiltIn) {
                tvCustom.setTextColor(android.graphics.Color.parseColor(meta.color))
                tvCustom.setBackgroundColor(android.graphics.Color.parseColor(meta.bg))
            }

            row.findViewById<TextView>(R.id.tvStatusSubtitle).text =
                "$key · Priority: ${meta.priority} · $count remark${if (count != 1) "s" else ""}"

            row.findViewById<View>(R.id.btnEditStatus).setOnClickListener { openEditDialog(key) }

            val btnDel = row.findViewById<View>(R.id.btnDeleteStatus)
            // Hide delete only when this is the last status remaining
            btnDel.visibility = if (ConfigState.statuses.size > 1) View.VISIBLE else View.GONE
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

        tvHint.text = "$key ${if (ConfigState.BASE_STATUSES.contains(key)) "· built-in" else "· custom"}"
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
                triggerSave()
                bindStatusList()
                Toast.makeText(ctx, "✅ Status আপডেট হয়েছে", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    // ── Delete dialog (openDelete + confirmDelete in JSX) ─────────────────────
    private fun openDeleteDialog(key: String) {
        val ctx    = requireContext()
        val others = ConfigState.statuses.filter { it != key }
        val toMigrateCount = ConfigState.remarks[key]?.size ?: 0

        if (others.isEmpty()) {
            Toast.makeText(ctx, "কমপক্ষে একটি status রাখতে হবে", Toast.LENGTH_SHORT).show()
            return
        }

        val spinMigrate = Spinner(ctx)
        val adapter     = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
            others.map { ConfigState.statusMeta[it]?.en ?: it })
        spinMigrate.adapter = adapter

        val msg = if (toMigrateCount > 0)
            "$toMigrateCount রিমার্ক অন্য status-এ মাইগ্রেট হবে"
        else "এই status স্থায়ীভাবে মুছে যাবে"

        AlertDialog.Builder(ctx)
            .setTitle("Delete $key?")
            .setMessage(msg)
            .apply { if (toMigrateCount > 0) setView(spinMigrate) }
            .setPositiveButton("Delete") { _, _ ->
                val migrateTarget = if (toMigrateCount > 0)
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

        triggerSave()
        bindStatusList()
        Toast.makeText(requireContext(), "🗑️ Status মুছে গেছে", Toast.LENGTH_SHORT).show()
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

        triggerSave()
        etNewKey.setText(""); etNewBn.setText(""); etNewEn.setText(""); etNewPriority.setText("0")
        newColorIdx = 0; updateCreateColorPreview()
        bindStatusList()
        Toast.makeText(requireContext(), "✅ নতুন status তৈরি হয়েছে", Toast.LENGTH_SHORT).show()
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

    private fun triggerSave() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Build the full statusMeta map and overwrite the entire node.
                // Using setValue() (not updateChildren) so deleted keys are actually removed.
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
            } catch (_: Exception) {}
        }
    }

    // Extension to iterate over LinearLayout children
    private val ViewGroup.children: Sequence<View>
        get() = sequence { for (i in 0 until childCount) yield(getChildAt(i)) }
}
