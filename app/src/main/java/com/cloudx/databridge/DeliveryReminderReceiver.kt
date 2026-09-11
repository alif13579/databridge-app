package com.cloudx.databridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Fires on a self-rescheduling AlarmManager alarm (see schedule() / arm()) to check whether
 * the signed-in worker still has any of TODAY's parcels with an outstanding CC
 * delivery-request (see SupabaseRemarkValidationWriter.fetchTodayDeliveryRequestsForWorker).
 * Older parcels never nag, no matter how long they stay unanswered.
 *
 * If so, shows DeliveryReminderOverlay (SYSTEM_ALERT_WINDOW granted) or a plain
 * notification (not granted) for the oldest ACTIONABLE one, then reschedules itself
 * for another 10 minutes. Tapping the notification opens the same overlay popup
 * (parcel details + 3 Bangla options) via [DeliveryReminderTapReceiver].
 *
 * Two guards stop the "barbar notification" loop:
 * 1. One nag per CC remark — an already-shown (consignment + remark time) never
 *    re-fires; a NEW CC remark nags again; "পরে জানাচ্ছি" ([snooze]) re-arms
 *    that parcel after [SNOOZE_DELAY_MS].
 * 2. Closed parcels are skipped — a parcel already delivered/returned needs no
 *    reply, but its latest row stays source='CC' forever.
 *
 * Stops rescheduling entirely once nothing actionable remains — armed again the
 * next time WorkerSpaceFragment loads or an FCM push arrives (see its loadData()
 * and DataBridgeMessagingService).
 */
class DeliveryReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) return // signed out — don't reschedule, arm() will re-arm on next sign-in load

        // "পরে জানাচ্ছি" snooze wake-up: clear this parcel's nagged keys so it
        // can fire again, then run the normal check below (it re-picks the
        // oldest actionable parcel, usually this same one if still pending).
        if (intent.action == ACTION_SNOOZE_WAKE) {
            val snoozedId = intent.getStringExtra(EXTRA_CONSIGNMENT).orEmpty()
            if (snoozedId.isNotBlank()) {
                try {
                    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val nagged = prefs.getStringSet(PREFS_KEY, emptySet())?.toMutableSet()
                        ?: mutableSetOf()
                    if (nagged.removeAll { it.substringBefore("|") == snoozedId }) {
                        prefs.edit().putStringSet(PREFS_KEY, nagged).apply()
                    }
                } catch (_: Exception) { }
            }
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val systemId = FirebaseDatabase.getInstance().reference
                    .child("users/$uid/profile/company_info/system_id")
                    .get().await().getValue(String::class.java)?.trim().orEmpty()
                if (systemId.isBlank()) return@launch

                val rows = fetchPending(systemId)
                if (rows.isEmpty()) return@launch // nothing pending — don't reschedule

                // Oldest-first, skipping already-nagged remarks and closed
                // parcels (see guards in the class doc). Cap Firebase status
                // reads; an unchecked remainder reschedules for next round.
                val sorted = rows.sortedBy { it.optString("created_at") }
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val nagged = prefs.getStringSet(PREFS_KEY, emptySet())?.toMutableSet()
                    ?: mutableSetOf()
                var picked: JSONObject? = null
                var pickedCons: Map<*, *>? = null
                var checks = 0
                var hitCap = false
                for (row in sorted) {
                    val key = nagKey(row)
                    if (nagged.contains(key)) continue
                    if (checks >= MAX_STATUS_CHECKS) { hitCap = true; break }
                    checks++
                    val cons = fetchConsignment(row.optString("consignment"))
                    if (isClosedStatus(cons?.get("status") as? String)) continue
                    picked = row
                    pickedCons = cons
                    break
                }
                if (picked == null) {
                    // All nagged or all closed — nothing actionable. Reschedule
                    // only if rows remain unchecked; otherwise stop the chain
                    // (re-armed by WorkerSpace load / FCM push when new mail arrives).
                    if (hitCap) schedule(appContext)
                    return@launch
                }
                val othersUnnagged = sorted.count {
                    it.optString("consignment") != picked.optString("consignment") &&
                        !nagged.contains(nagKey(it))
                }
                val data = buildReminderData(picked, pickedCons, otherPendingCount = othersUnnagged)
                if (data != null) {
                    if (android.provider.Settings.canDrawOverlays(appContext)) {
                        DeliveryReminderOverlay.show(appContext, data)
                    } else {
                        showNotification(appContext, data)
                    }
                    markNagged(prefs, nagged, nagKey(picked))
                }
                schedule(appContext) // still pending (or just handled one of several) — check again later
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fetchPending(systemId: String): List<JSONObject> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            SupabaseRemarkValidationWriter.fetchTodayDeliveryRequestsForWorker(
                systemId, "DeliveryReminderReceiver"
            ) { rows -> if (cont.isActive) cont.resumeWith(Result.success(rows)) }
        }

    /** Fills in customer name/address/COD from Firebase — validations rows only carry
     *  customer_phone, not the rest (see the extension's Hold Validation Export for the
     *  same gap and why: those columns were never added to public.validations).
     *  [cons] is the pre-fetched courier/consignments node (reused from the
     *  closed-status check above so we don't read it twice). */
    private suspend fun buildReminderData(
        row: JSONObject,
        cons: Map<*, *>?,
        otherPendingCount: Int,
    ): DeliveryReminderOverlay.Data? {
        val consignmentId = row.optString("consignment")
        if (consignmentId.isBlank()) return null

        val author = row.optJSONObject("author")
        return DeliveryReminderOverlay.Data(
            consignmentId = consignmentId,
            branchId = row.optString("branch_id"),
            assignedAgentSystemId = row.optString("assigned_to_system_id"),
            customerName = (cons?.get("recipientName") as? String).orEmpty(),
            customerPhone = row.optString("customer_phone").ifBlank {
                (cons?.get("recipientPhone") as? String).orEmpty()
            },
            address = (cons?.get("recipientAddress") as? String).orEmpty(),
            status = (cons?.get("status") as? String).orEmpty(),
            cod = (cons?.get("collectableAmount") as? Number)?.toDouble() ?: 0.0,
            ccRemarkText = row.optString("remarks_bn").ifBlank { row.optString("remarks") },
            ccAuthorName = author?.optString("name").orEmpty(),
            ccRemarkAtMs = SupabaseRemarkValidationWriter.parseDbTimestampMillis(row.optString("created_at")),
            otherPendingCount = otherPendingCount,
        )
    }

    private suspend fun fetchConsignment(consignmentId: String): Map<*, *>? {
        if (consignmentId.isBlank()) return null
        return try {
            FirebaseDatabase.getInstance().reference.child("courier/consignments/$consignmentId")
                .get().await().value as? Map<*, *>
        } catch (_: Exception) { null }
    }

    /** Closed = reply is moot (delivered/returned families). Unknown or blank
     *  status stays actionable — never silence a parcel we can't read. */
    private fun isClosedStatus(status: String?): Boolean {
        if (status.isNullOrBlank()) return false
        return status.trim().lowercase() in CLOSED_STATUSES
    }

    /** One nag per CC remark: consignment + the remark's created_at. A new
     *  remark (new timestamp) nags again — same parcel, new key. */
    private fun nagKey(row: JSONObject): String =
        row.optString("consignment") + "|" + row.optString("created_at")

    private fun markNagged(
        prefs: android.content.SharedPreferences,
        nagged: MutableSet<String>,
        key: String,
    ) {
        // Cap + prune keys older than a few days (today-scoped remarks, so
        // anything old here is stale) so the set can't grow without bound.
        val cutoff = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Dhaka"))
            .minusDays(3).toString() // yyyy-MM-dd, lexicographically comparable
        nagged.add(key)
        val pruned = nagged.filter { k ->
            val ts = k.substringAfter("|", "")
            ts >= cutoff || !ts.contains("T")
        }.toMutableSet()
        while (pruned.size > MAX_NAGGED_KEYS) pruned.remove(pruned.first())
        try {
            prefs.edit().putStringSet(PREFS_KEY, pruned).apply()
        } catch (_: Exception) { }
    }

    private fun showNotification(context: Context, data: DeliveryReminderOverlay.Data) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return

        // Tap opens the overlay popup (parcel details + 3 Bangla options).
        // No overlay permission → fall back to the app's parcel screen.
        val tapIntent: PendingIntent =
            if (android.provider.Settings.canDrawOverlays(context)) {
                val tap = Intent(context, DeliveryReminderTapReceiver::class.java).apply {
                    putExtra(EXTRA_CONSIGNMENT, data.consignmentId)
                    putExtra("branch_id", data.branchId)
                    putExtra("assigned_agent", data.assignedAgentSystemId)
                    putExtra("customer_name", data.customerName)
                    putExtra("customer_phone", data.customerPhone)
                    putExtra("address", data.address)
                    putExtra("status", data.status)
                    putExtra("cod", data.cod)
                    putExtra("cc_remark", data.ccRemarkText)
                    putExtra("cc_author", data.ccAuthorName)
                    putExtra("cc_at_ms", data.ccRemarkAtMs)
                    putExtra("other_pending", data.otherPendingCount)
                }
                PendingIntent.getBroadcast(
                    context, data.consignmentId.hashCode(), tap,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("notif_parcel_id", data.consignmentId)
                    putExtra("notif_scope", "worker")
                }
                PendingIntent.getActivity(
                    context, data.consignmentId.hashCode(), openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("ডেলিভারি আপডেট দরকার — ${data.consignmentId}")
            .setContentText(
                data.ccRemarkText.ifBlank { "CC পাঠিয়েছে — reply দিন" } +
                    if (data.otherPendingCount > 0) " (+ আরও ${data.otherPendingCount}টা)" else ""
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(context)
                .notify(data.consignmentId.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "databridge_alerts_channel_v2" // reuse the existing channel
        private const val PREFS_NAME = "delivery_reminder"
        private const val PREFS_KEY = "nagged_remarks"
        private const val MAX_NAGGED_KEYS = 100
        private const val MAX_STATUS_CHECKS = 10
        /** "পরে জানাচ্ছি" snooze: this long before the parcel may nag again. */
        private const val SNOOZE_DELAY_MS = 2 * 60 * 60_000L
        const val ACTION_SNOOZE_WAKE = "com.cloudx.databridge.DELIVERY_SNOOZE_WAKE"
        const val EXTRA_CONSIGNMENT = "consignment_id"
        /** Delivered + return families (Hermes run statuses, lowercase) —
         *  a CC delivery-request on these needs no worker reply. */
        private val CLOSED_STATUSES = setOf(
            "delivered", "partial delivery", "partial", "exchange", "paid return",
            "return",
        )

        /** Called from WorkerSpaceFragment.loadData() whenever it finishes a load — cheap
         *  no-op if nothing is pending (this alarm fires once, finds nothing, and simply
         *  doesn't reschedule itself, rather than the fragment needing to know the answer
         *  up front). */
        fun arm(context: Context) = schedule(context.applicationContext)

        private fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val delayMs = 10 * 60_000L // every 10 minutes
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, Intent(context, DeliveryReminderReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pendingIntent
                )
            } catch (_: Exception) {
            }
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, Intent(context, DeliveryReminderReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }

        /** "পরে জানাচ্ছি": dismiss now, re-arm this parcel once after
         *  [SNOOZE_DELAY_MS] as a one-shot reminder (a normal check runs then,
         *  so a worker reply in the meantime still silences it). */
        fun snooze(context: Context, consignmentId: String) {
            if (consignmentId.isBlank()) return
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return
            val wake = Intent(appContext, DeliveryReminderReceiver::class.java).apply {
                action = ACTION_SNOOZE_WAKE
                putExtra(EXTRA_CONSIGNMENT, consignmentId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appContext, (consignmentId + "|snooze").hashCode(), wake,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + SNOOZE_DELAY_MS, pendingIntent
                )
            } catch (_: Exception) {
            }
        }
    }
}
