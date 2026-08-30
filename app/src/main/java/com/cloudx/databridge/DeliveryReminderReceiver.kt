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
 * the signed-in worker still has any parcel with an outstanding CC delivery-request
 * (consignment's latest validations row has source='CC' — see
 * SupabaseRemarkValidationWriter.fetchPendingDeliveryRequestsForWorker()). If so, shows
 * DeliveryReminderOverlay (SYSTEM_ALERT_WINDOW granted) or a plain notification (not
 * granted) for the oldest one, then reschedules itself for another 10 minutes. Stops
 * rescheduling entirely once nothing is pending — armed again the next time
 * WorkerSpaceFragment loads (see its loadData()).
 */
class DeliveryReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) return // signed out — don't reschedule, arm() will re-arm on next sign-in load

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val systemId = FirebaseDatabase.getInstance().reference
                    .child("users/$uid/profile/company_info/system_id")
                    .get().await().getValue(String::class.java)?.trim().orEmpty()
                if (systemId.isBlank()) return@launch

                val rows = fetchPending(systemId)
                if (rows.isEmpty()) return@launch // nothing pending — don't reschedule

                val oldest = rows.minByOrNull { it.optString("created_at") } ?: rows.first()
                val data = buildReminderData(oldest)
                if (data != null) {
                    if (android.provider.Settings.canDrawOverlays(appContext)) {
                        DeliveryReminderOverlay.show(appContext, data)
                    } else {
                        showNotification(appContext, data)
                    }
                }
                schedule(appContext) // still pending (or just handled one of several) — check again later
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fetchPending(systemId: String): List<JSONObject> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            SupabaseRemarkValidationWriter.fetchPendingDeliveryRequestsForWorker(
                systemId, "DeliveryReminderReceiver"
            ) { rows -> if (cont.isActive) cont.resumeWith(Result.success(rows)) }
        }

    /** Fills in customer name/address/COD from Firebase — validations rows only carry
     *  customer_phone, not the rest (see the extension's Hold Validation Export for the
     *  same gap and why: those columns were never added to public.validations). */
    private suspend fun buildReminderData(row: JSONObject): DeliveryReminderOverlay.Data? {
        val consignmentId = row.optString("consignment")
        if (consignmentId.isBlank()) return null
        val cons = try {
            FirebaseDatabase.getInstance().reference.child("courier/consignments/$consignmentId")
                .get().await().value as? Map<*, *>
        } catch (_: Exception) { null }

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
            ccRemarkAtMs = try {
                java.time.Instant.parse(row.optString("created_at")).toEpochMilli()
            } catch (_: Exception) { 0L },
        )
    }

    private fun showNotification(context: Context, data: DeliveryReminderOverlay.Data) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("notif_parcel_id", data.consignmentId)
            putExtra("notif_scope", "worker")
        }
        val pendingIntent = PendingIntent.getActivity(
            context, data.consignmentId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Delivery update needed — ${data.consignmentId}")
            .setContentText(data.ccRemarkText.ifBlank { "CC পাঠিয়েছে — reply দিন" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(context)
                .notify(data.consignmentId.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "databridge_alerts_channel_v2" // reuse the existing channel

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
    }
}
