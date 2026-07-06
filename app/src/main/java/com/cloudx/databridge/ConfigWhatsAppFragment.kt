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
 * 📱 WhatsApp Templates Config Tab
 *
 * Features:
 *  - List all WhatsApp message templates (name + body preview)
 *  - Create / edit / delete templates
 *  - Body supports placeholders: {name} {phone} {address} {cod} {consignmentId} {hub}
 *    (filled in with real parcel data when a linked Remark is used — see ConfigRemarksFragment)
 *
 * Firebase: config/whatsappTemplates/{id}/{id, name, body}
 */
class ConfigWhatsAppFragment : Fragment() {

    private val db = FirebaseDatabase.getInstance()

    private lateinit var templateListContainer: LinearLayout
    private lateinit var tvEmpty:                TextView
    private lateinit var btnOpenCreate:          TextView
    private lateinit var busyOverlay:            View
    private lateinit var tvBusy:                 TextView

    private val placeholderHint =
        "ব্যবহারযোগ্য placeholder: {name} {phone} {address} {cod} {consignmentId} {hub}"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_config_whatsapp, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        templateListContainer = view.findViewById(R.id.templateListContainer)
        tvEmpty       = view.findViewById(R.id.tvTemplateEmpty)
        btnOpenCreate = view.findViewById(R.id.btnOpenCreateTemplate)
        busyOverlay   = view.findViewById(R.id.whatsappBusyOverlay)
        tvBusy        = view.findViewById(R.id.tvWhatsappBusy)

        loadFromFirebase()
        btnOpenCreate.setOnClickListener { openCreateDialog() }
    }

    // ── Firebase load ─────────────────────────────────────────────────────────
    private fun loadFromFirebase() {
        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Loading...")
            val loaded = reloadConfig()
            if (!loaded) Toast.makeText(requireContext(), "Template load failed", Toast.LENGTH_LONG).show()
            if (isAdded) {
                bindList()
                setBusy(false)
            }
        }
    }

    private suspend fun reloadConfig(): Boolean =
        try {
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
            true
        } catch (e: Exception) {
            Log.e("ConfigWhatsApp", "Failed to load templates", e)
            false
        }

    // ── Bind list ─────────────────────────────────────────────────────────────
    private fun bindList() {
        templateListContainer.removeAllViews()
        val templates = ConfigState.whatsappTemplates.values.sortedBy { it.name }
        tvEmpty.visibility = if (templates.isEmpty()) View.VISIBLE else View.GONE
        templates.forEach { t ->
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_whatsapp_template, templateListContainer, false)

            row.findViewById<TextView>(R.id.tvTemplateName).text = t.name.ifBlank { "(নাম নেই)" }
            row.findViewById<TextView>(R.id.tvTemplateBody).text = t.body

            row.findViewById<View>(R.id.btnEditTemplate).setOnClickListener { openEditDialog(t) }
            row.findViewById<View>(R.id.btnDeleteTemplate).setOnClickListener { openDeleteDialog(t) }

            templateListContainer.addView(row)
        }
    }

    // ── Create dialog ─────────────────────────────────────────────────────────
    private fun openCreateDialog() {
        val ctx = requireContext()
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }

        val etName = EditText(ctx).apply {
            hint = "Template নাম (e.g. Delivery Confirmation)"
            textSize = 13f
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = resources.getDrawable(R.drawable.bg_input_rounded, ctx.theme)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val tvHint = TextView(ctx).apply {
            text = placeholderHint
            textSize = 10f
            setTextColor(ctx.getColor(R.color.theme_text_muted))
            setPadding(0, 0, 0, dp(6))
        }

        val etBody = EditText(ctx).apply {
            hint = "যেমন: প্রিয় {name}, আপনার পার্সেল {consignmentId} ডেলিভারির জন্য প্রস্তুত।"
            textSize = 13f
            minLines = 4
            gravity = android.view.Gravity.TOP
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = resources.getDrawable(R.drawable.bg_input_rounded, ctx.theme)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        layout.addView(etName)
        layout.addView(tvHint)
        layout.addView(etBody)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("নতুন WhatsApp Template")
            .setView(layout)
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("তৈরি করুন", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text.toString().trim()
                val body = etBody.text.toString().trim()
                if (name.isEmpty() || body.isEmpty()) {
                    Toast.makeText(ctx, "নাম ও message body দুটোই দিন", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                createTemplate(name, body)
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    // ── Edit dialog ────────────────────────────────────────────────────────────
    private fun openEditDialog(template: ConfigState.WhatsAppTemplate) {
        val ctx = requireContext()
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }

        val etName = EditText(ctx).apply {
            setText(template.name)
            textSize = 13f
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = resources.getDrawable(R.drawable.bg_input_rounded, ctx.theme)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val tvHint = TextView(ctx).apply {
            text = placeholderHint
            textSize = 10f
            setTextColor(ctx.getColor(R.color.theme_text_muted))
            setPadding(0, 0, 0, dp(6))
        }

        val etBody = EditText(ctx).apply {
            setText(template.body)
            textSize = 13f
            minLines = 4
            gravity = android.view.Gravity.TOP
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = resources.getDrawable(R.drawable.bg_input_rounded, ctx.theme)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        layout.addView(etName)
        layout.addView(tvHint)
        layout.addView(etBody)

        AlertDialog.Builder(ctx)
            .setTitle("Template Edit করুন")
            .setView(layout)
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                val body = etBody.text.toString().trim()
                if (name.isEmpty() || body.isEmpty()) {
                    Toast.makeText(ctx, "নাম ও message body দুটোই দিন", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                updateTemplate(template.id, name, body)
            }
            .show()
    }

    private fun openDeleteDialog(template: ConfigState.WhatsAppTemplate) {
        AlertDialog.Builder(requireContext())
            .setTitle("Template Delete করবেন?")
            .setMessage("\"${template.name}\" — এই template কোনো remark-এর সাথে link করা থাকলে সেই link-ও কাজ করবে না।")
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("Delete") { _, _ -> deleteTemplate(template.id) }
            .show()
    }

    // ── Actions ───────────────────────────────────────────────────────────────
    private fun createTemplate(name: String, body: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Creating...")
            try {
                val id = db.reference.child("config/whatsappTemplates").push().key
                    ?: throw Exception("no push key")
                db.reference.child("config/whatsappTemplates/$id").setValue(
                    mapOf("id" to id, "name" to name, "body" to body)
                ).await()
                reloadConfig()
                bindList()
                setBusy(false)
                Toast.makeText(requireContext(), "✅ Created", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("ConfigWhatsApp", "create failed", e)
                setBusy(false)
                Toast.makeText(requireContext(), "Template create failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateTemplate(id: String, name: String, body: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Saving...")
            try {
                db.reference.child("config/whatsappTemplates/$id").updateChildren(
                    mapOf("name" to name, "body" to body)
                ).await()
                reloadConfig()
                bindList()
                setBusy(false)
                Toast.makeText(requireContext(), "✅ Updated", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("ConfigWhatsApp", "update failed", e)
                setBusy(false)
                Toast.makeText(requireContext(), "Update failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteTemplate(id: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true, "Deleting...")
            try {
                db.reference.child("config/whatsappTemplates/$id").removeValue().await()
                reloadConfig()
                bindList()
                setBusy(false)
                Toast.makeText(requireContext(), "🗑 Deleted", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("ConfigWhatsApp", "delete failed", e)
                setBusy(false)
                Toast.makeText(requireContext(), "Delete failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setBusy(show: Boolean, text: String = "Loading...") {
        if (!::busyOverlay.isInitialized) return
        tvBusy.text = text
        busyOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }
}
