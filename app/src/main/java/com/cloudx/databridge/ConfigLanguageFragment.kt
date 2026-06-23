package com.cloudx.databridge

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
 * 🌐 Language Config Tab
 * JSX equivalent: LanguageConfig component
 *
 * Two cards:
 *   📦 Worker Fragment (Delivery Agent)
 *   📞 Call Center Fragment (CC Agent)
 * Each card: dropdown with 4 language options + a live preview of 2 sample remarks
 *
 * Firebase: config/language/workerLang, config/language/ccLang
 */
class ConfigLanguageFragment : Fragment() {

    private val db = FirebaseDatabase.getInstance()

    private lateinit var spinnerWorker:  Spinner
    private lateinit var spinnerCC:      Spinner
    private lateinit var previewWorker:  LinearLayout
    private lateinit var previewCC:      LinearLayout

    private val langOptions = ConfigState.LANG_OPTIONS

    // Sample remarks for preview (mirrors JSX Preview component)
    private val SAMPLES = listOf(
        Triple("গ্রাহক নেই",            "Customer absent",        "RETURN"),
        Triple("সঠিকভাবে পৌঁছেছে",      "Delivered successfully", "DELIVERED"),
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_config_language, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        spinnerWorker = view.findViewById(R.id.spinnerWorkerLang)
        spinnerCC     = view.findViewById(R.id.spinnerCCLang)
        previewWorker = view.findViewById(R.id.previewContainerWorker)
        previewCC     = view.findViewById(R.id.previewContainerCC)

        val labels = langOptions.map { it.label }
        val adapterW = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        val adapterC = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        spinnerWorker.adapter = adapterW
        spinnerCC.adapter     = adapterC

        // Restore from ConfigState
        spinnerWorker.setSelection(langOptions.indexOfFirst { it.value == ConfigState.workerLang }.coerceAtLeast(0))
        spinnerCC    .setSelection(langOptions.indexOfFirst { it.value == ConfigState.ccLang     }.coerceAtLeast(0))

        spinnerWorker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val val_ = langOptions.getOrNull(pos)?.value ?: return
                ConfigState.workerLang = val_
                renderPreview(previewWorker, val_)
                triggerSave()
                Toast.makeText(requireContext(), "✅ Worker language saved", Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spinnerCC.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val val_ = langOptions.getOrNull(pos)?.value ?: return
                ConfigState.ccLang = val_
                renderPreview(previewCC, val_)
                triggerSave()
                Toast.makeText(requireContext(), "✅ CC language saved", Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        renderPreview(previewWorker, ConfigState.workerLang)
        renderPreview(previewCC,     ConfigState.ccLang)

        // Load persisted values from Firebase
        loadFromFirebase()
    }

    // ── renderPreview: show 2 sample rows with remark+status in selected lang ──
    private fun renderPreview(container: LinearLayout, langVal: String) {
        container.removeAllViews()
        val parts = langVal.split("_")
        val remarkLang = parts.getOrNull(0) ?: "bn"
        val statusLang = parts.getOrNull(1) ?: "bn"

        SAMPLES.forEach { (bn, en, statusKey) ->
            val meta = ConfigState.statusMeta[statusKey]
            val row  = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_lang_preview_row, container, false)
            row.findViewById<TextView>(R.id.tvPreviewRemark).text =
                if (remarkLang == "bn") bn else en
            val tvStatus = row.findViewById<TextView>(R.id.tvPreviewStatus)
            tvStatus.text = if (statusLang == "bn") meta?.bn ?: statusKey else meta?.en ?: statusKey
            if (meta != null) {
                tvStatus.setTextColor(android.graphics.Color.parseColor(meta.color))
                tvStatus.setBackgroundColor(android.graphics.Color.parseColor(meta.bg))
            }
            container.addView(row)
        }
    }

    // ── Firebase ──────────────────────────────────────────────────────────────
    private fun loadFromFirebase() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val snap = db.reference.child("config/language").get().await()
                val wl = snap.child("workerLang").getValue(String::class.java)
                val cl = snap.child("ccLang").getValue(String::class.java)
                if (!wl.isNullOrBlank()) {
                    ConfigState.workerLang = wl
                    if (isAdded) {
                        spinnerWorker.setSelection(langOptions.indexOfFirst { it.value == wl }.coerceAtLeast(0))
                        renderPreview(previewWorker, wl)
                    }
                }
                if (!cl.isNullOrBlank()) {
                    ConfigState.ccLang = cl
                    if (isAdded) {
                        spinnerCC.setSelection(langOptions.indexOfFirst { it.value == cl }.coerceAtLeast(0))
                        renderPreview(previewCC, cl)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun triggerSave() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                db.reference.child("config/language").updateChildren(
                    mapOf(
                        "workerLang" to ConfigState.workerLang,
                        "ccLang"     to ConfigState.ccLang,
                    )
                ).await()
            } catch (_: Exception) {}
        }
    }
}
