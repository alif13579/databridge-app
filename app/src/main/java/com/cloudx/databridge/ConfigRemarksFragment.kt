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

        // Load from Firebase then bind
        loadFromFirebase()

        btnAdd.setOnClickListener { handleAdd() }
    }

    // ── Firebase load ─────────────────────────────────────────────────────────
    private fun loadFromFirebase() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val snap = db.reference.child("config/remarks").get().await()
                if (snap.exists()) {
                    val loaded = mutableMapOf<String, MutableList<ConfigState.Remark>>()
                    snap.children.forEach { statusSnap ->
                        val key = statusSnap.key ?: return@forEach
                        val list = mutableListOf<ConfigState.Remark>()
                        statusSnap.children.forEach { r ->
                            val id          = r.child("id").getValue(String::class.java) ?: uid()
                            val textBn      = r.child("text_bn").getValue(String::class.java) ?: ""
                            val textEn      = r.child("text_en").getValue(String::class.java) ?: ""
                            val targetStatus = r.child("target_status").getValue(String::class.java) ?: key
                            list.add(ConfigState.Remark(id, textBn, textEn, targetStatus))
                        }
                        loaded[key] = list
                    }
                    remarks = loaded
                    ConfigState.remarks = remarks
                }
            } catch (_: Exception) {}
            if (isAdded) bindAll()
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
        // Show chips for statuses that have remarks OR is the active one (mirrors JSX logic)
        sorted.forEach { s ->
            val count = remarks[s]?.size ?: 0
            val meta  = statusMeta[s] ?: return@forEach
            if (count == 0 && s != activeStatus) return@forEach

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
        val labels = sorted.map { statusMeta[it]?.en ?: it }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        spinnerTarget.adapter = adapter
        val idx = sorted.indexOf(activeStatus).coerceAtLeast(0)
        spinnerTarget.setSelection(idx)
    }

    private fun bindRemarksList() {
        remarksList.removeAllViews()
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
        triggerSave()
        bindAll()
        Toast.makeText(requireContext(), "✅ Remark সরানো হয়েছে", Toast.LENGTH_SHORT).show()
    }

    /** handleDelete: remove remark from group */
    private fun handleDelete(group: String, id: String) {
        remarks[group]?.removeAll { it.id == id }
        ConfigState.remarks = remarks
        triggerSave()
        bindAll()
        Toast.makeText(requireContext(), "🗑️ Remark মুছে গেছে", Toast.LENGTH_SHORT).show()
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
        val remark    = ConfigState.Remark(
            id           = uid(),
            text_bn      = bn.ifEmpty { en },
            text_en      = en.ifEmpty { bn },
            target_status = target
        )
        remarks.getOrPut(target) { mutableListOf() }.add(remark)
        ConfigState.remarks = remarks
        triggerSave()
        etBn.setText(""); etEn.setText("")
        activeStatus = target
        bindAll()
        Toast.makeText(requireContext(), "✅ Remark যোগ হয়েছে", Toast.LENGTH_SHORT).show()
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
            try {
                // Build full remarks payload and overwrite entire node atomically.
                // Avoids the removeValue() + updateChildren() race condition.
                val payload = mutableMapOf<String, Any>()
                remarks.forEach { (statusKey, list) ->
                    if (list.isEmpty()) {
                        // Keep empty list as empty map so the key still exists
                        payload[statusKey] = emptyMap<String, Any>()
                    } else {
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
            } catch (_: Exception) {}
        }
    }
}
