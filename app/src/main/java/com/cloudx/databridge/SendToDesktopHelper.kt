package com.cloudx.databridge

import android.content.Context
import android.widget.Toast
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * 🖥️ SendToDesktopHelper — pushes a parcel card's info to the CC agent's desktop
 * DataBridge browser extension(s), where it's auto-copied to the clipboard (if the
 * user has that toggle on in the extension's Settings) so it can be pasted straight
 * into whatever the agent is doing on desktop.
 *
 * Writes to sessions/{extId}/commands/{cmdId} — the extension's background.js polls
 * this node (see pollIncomingCommands() in databridge-extension) and marks each
 * command "done" once processed. Same session node the extension already writes
 * records/ and meta/ under, so no new Firebase path or security rule is needed
 * beyond the commands/ .indexOn added alongside this (see database.rules.json).
 *
 * "Which extension" is never asked for explicitly — it goes to every extension
 * connected to the signed-in account (UserRepository.getConnectedExtensionIds()),
 * same account-scoping WhatsApp templates and other CC-agent features already use.
 */
object SendToDesktopHelper {

    /** Same {placeholder} substitution style as WhatsAppHelper.fillTemplate, reused here
     *  so both share one template-filling convention across the app. */
    private fun fillTemplate(
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

    /** Builds the parcel-info text for a card — same field set/order as the
     *  WhatsApp-to-agent template (minus the remarks section, which is specific
     *  to that message and not relevant to a desktop clipboard paste). */
    fun buildParcelInfoText(item: CallCenterParcelItem): String = fillTemplate(
        body = "📦 Parcel Info\n" +
            "Consignment ID : {consignmentId}\n" +
            "Customer Name : {name}\n" +
            "Phone Number : {phone}\n" +
            "Address : {address}\n" +
            "COD Amount : ৳{cod}\n" +
            "Hub : {hub}",
        name = item.customer,
        phone = item.phone,
        address = item.address,
        cod = item.cod.toString(),
        consignmentId = item.id,
        hub = item.branch
    )

    /** Outcome of a send attempt — kept explicit rather than a bare Int so the caller-facing
     *  toast can distinguish "nothing to send to" from "tried and some/all of it failed",
     *  which a single count can't do on its own (0 successes out of 0 vs. out of some). */
    private sealed class SendOutcome {
        data class Sent(val successCount: Int, val totalCount: Int) : SendOutcome()
        object NoExtensionsConnected : SendOutcome()
        data class LookupFailed(val error: Exception) : SendOutcome()
    }

    /**
     * Sends [text] as a pending command to every extension connected to the
     * signed-in account. Shows a toast on the result (success count, "no extensions
     * connected", or failure) — callers don't need to handle the outcome themselves.
     * Safe to call from a coroutine on any dispatcher; the Firebase/network work
     * itself runs on Dispatchers.IO.
     */
    suspend fun sendToConnectedExtensions(context: Context, text: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "⚠ Login করা নেই — Desktop-এ পাঠানো যায়নি", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val outcome = withContext(Dispatchers.IO) {
            val extIds = try {
                UserRepository(uid).getConnectedExtensionIds()
            } catch (e: Exception) {
                return@withContext SendOutcome.LookupFailed(e)
            }
            if (extIds.isEmpty()) return@withContext SendOutcome.NoExtensionsConnected

            val db = FirebaseDatabase.getInstance()
            var successCount = 0
            // Sequential, not parallel — this list is at most a handful of desktops per
            // agent, and sequential keeps error handling/counting simple (matches the
            // sequential container-then-sessions writes in background.js's own
            // sendToFirebase() on the extension side).
            for (extId in extIds) {
                // Fresh timestamp per extension — these land under different sessions/{extId}
                // paths so collisions aren't possible either way, but a distinct id per write
                // also means each command's created_at accurately reflects when *that* write
                // happened, in case one extension's write is slower/retried.
                val timestamp = System.currentTimeMillis()
                val cmdId = "cmd_$timestamp"
                try {
                    db.getReference("sessions/$extId/commands/$cmdId").setValue(
                        mapOf(
                            "text" to text,
                            "created_at" to timestamp,
                            "status" to "pending"
                        )
                    ).await()
                    successCount++
                } catch (_: Exception) {
                    // One extension failing (e.g. stale/disconnected) shouldn't stop the rest —
                    // continue to the next connected extension.
                }
            }
            SendOutcome.Sent(successCount, extIds.size)
        }

        withContext(Dispatchers.Main) {
            when (outcome) {
                is SendOutcome.NoExtensionsConnected ->
                    Toast.makeText(context, "⚠ কোনো Desktop connected নেই", Toast.LENGTH_SHORT).show()
                is SendOutcome.LookupFailed ->
                    Toast.makeText(context, "❌ Desktop-এ পাঠানো যায়নি", Toast.LENGTH_SHORT).show()
                is SendOutcome.Sent -> when {
                    outcome.successCount == outcome.totalCount ->
                        Toast.makeText(context, "✅ Desktop-এ পাঠানো হয়েছে (${outcome.successCount})", Toast.LENGTH_SHORT).show()
                    outcome.successCount == 0 ->
                        Toast.makeText(context, "❌ Desktop-এ পাঠানো যায়নি", Toast.LENGTH_SHORT).show()
                    else ->
                        Toast.makeText(context, "✅ পাঠানো হয়েছে ${outcome.successCount}/${outcome.totalCount} Desktop-এ", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
