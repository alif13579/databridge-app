package com.cloudx.databridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * 📲 WhatsAppSender — opens WhatsApp with a pre-filled, variable-substituted message so the
 * agent just has to tap Send.
 *
 * IMPORTANT: regular consumer WhatsApp has no API for a third-party Android app to send a
 * message silently in the background. That's only possible via the official WhatsApp Business
 * Cloud API, called from a server/Cloud Function. This is the Intent-based approach the user
 * explicitly chose instead: WhatsApp opens with the message ready, one tap to send.
 */
object WhatsAppSender {
    private const val PREFS = "databridge_toggles"
    private const val KEY_ENABLED = "whatsapp_auto_send"
    private const val KEY_TEMPLATE = "whatsapp_template"

    const val DEFAULT_TEMPLATE =
        "হ্যালো {customer_name}, আপনার একটি পার্সেল (মূল্য ৳{parcel_value}) আজ ডেলিভারির জন্য পাঠানো হয়েছে।\n" +
        "ঠিকানা: {address}\n" +
        "যেকোনো প্রয়োজনে কল করুন: {agent_phone}\n" +
        "ধন্যবাদ।"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getTemplate(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TEMPLATE, null)?.ifBlank { null }
            ?: DEFAULT_TEMPLATE

    fun setTemplate(context: Context, template: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TEMPLATE, template).apply()
    }

    /**
     * Opens WhatsApp with the configured template (variables substituted) for [rawPhone].
     * No-op if the "Automatic WhatsApp Sender" setting is off, or the phone looks invalid.
     *
     * [variables] keys are matched against `{key}` placeholders in the template, e.g.
     * mapOf("customer_name" to "Alif", "parcel_value" to "150", "address" to "...").
     */
    fun sendIfEnabled(fragment: Fragment, rawPhone: String, variables: Map<String, String>) {
        val ctx = fragment.context ?: return
        if (!isEnabled(ctx)) return

        val local = AutoDialHelper.normalizeBdPhone(rawPhone) // -> 01XXXXXXXXX
        if (local.length < 11 || !local.startsWith("0")) return
        val international = "880" + local.removePrefix("0")  // -> 8801XXXXXXXXX (wa.me format)

        var message = getTemplate(ctx)
        variables.forEach { (key, value) ->
            if (key == "agent_phone" && value.isBlank()) {
                // Remove the whole line containing {agent_phone} so no empty line appears
                message = message.lines()
                    .filter { !it.contains("{agent_phone}") }
                    .joinToString("\n")
            } else {
                message = message.replace("{$key}", value)
            }
        }
        // Clean up any remaining unresolved placeholders
        message = message.replace(Regex("\\{agent_phone\\}"), "")

        try {
            val uri = Uri.parse("https://wa.me/$international?text=${Uri.encode(message)}")
            fragment.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(ctx, "WhatsApp খোলা যায়নি", Toast.LENGTH_SHORT).show()
        }
    }
}
