package com.cloudx.databridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 📱 WhatsAppHelper — opens WhatsApp with a pre-filled message via the wa.me deep link.
 * No API key or Business account needed; the user still taps Send manually inside WhatsApp.
 *
 * Template placeholders supported: {name} {phone} {address} {cod} {consignmentId} {hub}
 */
object WhatsAppHelper {

    /** Same normalization used elsewhere in the app (880-prefixed, digits only). */
    private fun normalizePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return when {
            digits.isBlank()         -> ""
            digits.startsWith("880") -> digits
            digits.startsWith("0")   -> "88" + digits
            else                     -> "88" + digits
        }
    }

    /** Fills {placeholders} in the template body with actual parcel values. */
    fun fillTemplate(
        body: String,
        name: String = "",
        phone: String = "",
        address: String = "",
        cod: String = "",
        consignmentId: String = "",
        hub: String = "",
    ): String = body
        .replace("{name}", name)
        .replace("{phone}", phone)
        .replace("{address}", address)
        .replace("{cod}", cod)
        .replace("{consignmentId}", consignmentId)
        .replace("{hub}", hub)

    /**
     * Opens WhatsApp (app if installed, else web) with [message] pre-filled for [phone].
     * Returns true if the intent was launched, false if it failed (e.g. no browser/WhatsApp
     * available) — caller can show a toast in that case.
     */
    fun send(context: Context, phone: String, message: String): Boolean {
        val normalized = normalizePhone(phone)
        if (normalized.isBlank()) {
            Toast.makeText(context, "⚠ Phone number নেই — WhatsApp message পাঠানো যায়নি", Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val encoded = Uri.encode(message)
            val uri = Uri.parse("https://wa.me/$normalized?text=$encoded")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(context, "⚠ WhatsApp খোলা যায়নি", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
