package com.cloudx.databridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/**
 * Last-attempt reminder: today's parcels of the signed-in worker whose
 * consignment `attempt` is 3+ get periodic RANDOM nudges — "ajkei delivery
 * korun, na hole merchant-ke janiye return korun" — with customer info, a
 * call button, and Delivered / Returned / Ekhono-na confirmations.
 *
 * Confirmations persist ONLY on-device (SharedPreferences, date-stamped —
 * anything not stamped today counts as expired at midnight, no cleanup job).
 * Confirmed parcels rest for the day; unconfirmed (incl. explicit "ekhono
 * na") stay eligible and come up again on a later cycle. Purely advisory:
 * nothing here writes server state — the real delivery/return still flows
 * through the normal remark flow.
 *
 * Self-rescheduling AlarmManager chain like DeliveryReminderReceiver (which
 * covers CC delivery-requests; this one covers attempt>=3). Armed from
 * WorkerSpaceFragment.loadData() and boot; stops when signed out.
 */
class LastAttemptReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            ACTION_CONFIRM -> {
                val cid = intent.getStringExtra(EXTRA_CID).orEmpty()
                val outcome = intent.getStringExtra(EXTRA_OUTCOME).orEmpty()
                if (cid.isNotBlank() && (outcome == OUTCOME_DELIVERED || outcome == OUTCOME_RETURNED)) {
                    ConfirmStore.save(appContext, cid, outcome)
                }
                NotificationManagerCompat.from(appContext).cancel(NOTIF_TAG, cid.hashCode())
                // Stay armed — other parcels may still need nudging.
                schedule(appContext, jitteredDelay())
                return
            }
            ACTION_SNOOZE -> {
                // "Ekhono na" — explicit defer, nothing saved, stays eligible.
                val cid = intent.getStringExtra(EXTRA_CID).orEmpty()
                NotificationManagerCompat.from(appContext).cancel(NOTIF_TAG, cid.hashCode())
                schedule(appContext, jitteredDelay())
                return
            }
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) return // signed out — don't reschedule

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val systemId = FirebaseDatabase.getInstance().reference
                    .child("users/$uid/profile/company_info/system_id")
                    .get().await().getValue(String::class.java)?.trim().orEmpty()
                if (systemId.isBlank()) {
                    schedule(appContext, IDLE_DELAY_MS)
                    return@launch
                }
                val candidates = findCandidates(appContext, systemId)
                if (candidates.isEmpty()) {
                    schedule(appContext, IDLE_DELAY_MS) // attempts accrue through the day — keep watching
                    return@launch
                }
                val pick = candidates.shuffled().first()
                showNotification(appContext, pick, candidates.size - 1)
                schedule(appContext, jitteredDelay())
            } finally {
                pending.finish()
            }
        }
    }

    private data class Candidate(
        val consignmentId: String,
        val attempt: Int,
        val customerName: String,
        val customerPhone: String,
        val address: String,
        val cod: Double,
        val status: String,
    )

    /** Today's run consignments at attempt>=3, terminal statuses and
     *  today-confirmed ones excluded. */
    private suspend fun findCandidates(appContext: Context, systemId: String): List<Candidate> {
        val db = FirebaseDatabase.getInstance()
        // Dhaka day (GMT+6 pinned) — run IDs are Dhaka-date keyed.
        val today = DhakaTime.todayKey()
        val indexSnap = try {
            db.reference.child("courier/runs_by_agentSystemId/$systemId").get().await()
        } catch (_: Exception) { return emptyList() }
        if (!indexSnap.exists()) return emptyList()

        // (runType, runId) for today only — runIds are run_{yyyyMMdd}_{systemId}.
        val todayRuns = mutableSetOf<Pair<String, String>>()
        indexSnap.children.forEach { typeSnap ->
            val runType = typeSnap.key?.trim().orEmpty()
            if (runType.isBlank()) return@forEach
            typeSnap.children.mapNotNull { it.key?.trim()?.takeIf { k -> k.isNotBlank() } }
                .filter { it.startsWith("run_${today}_") }
                .forEach { todayRuns.add(runType to it) }
        }
        if (todayRuns.isEmpty()) return emptyList()

        val out = mutableListOf<Candidate>()
        for ((runType, runId) in todayRuns) {
            val runSnap = try {
                db.reference.child("courier/run_routes/$runType/$runId").get().await()
            } catch (_: Exception) { continue }
            val cids = runSnap.child("consignments").children
                .mapNotNull { it.key?.trim()?.takeIf { k -> k.isNotBlank() } }
            for (cid in cids) {
                if (ConfirmStore.isConfirmedToday(appContext, cid)) continue
                val detail = try {
                    db.reference.child("courier/consignments/$cid").get().await()
                } catch (_: Exception) { continue }
                if (!detail.exists()) continue
                val attempt = readAttempt(detail)
                if (attempt < 3) continue
                val status = detail.child("status").getValue(String::class.java)?.trim().orEmpty()
                if (isTerminal(status)) continue
                out.add(
                    Candidate(
                        consignmentId = cid,
                        attempt = attempt,
                        customerName = detail.child("recipientName").getValue(String::class.java).orEmpty(),
                        customerPhone = detail.child("recipientPhone").getValue(String::class.java).orEmpty(),
                        address = detail.child("recipientAddress").getValue(String::class.java).orEmpty(),
                        cod = detail.child("collectableAmount").getValue(String::class.java)?.toDoubleOrNull()
                            ?: detail.child("collectableAmount").getValue(Long::class.java)?.toDouble() ?: 0.0,
                        status = status,
                    )
                )
            }
        }
        return out.distinctBy { it.consignmentId }
    }

    private fun readAttempt(snap: com.google.firebase.database.DataSnapshot): Int {
        return snap.child("attempt").getValue(String::class.java)
            ?.toDoubleOrNull()?.toInt()
            ?: snap.child("attempt").getValue(Long::class.java)?.toInt()
            ?: snap.child("attempt").getValue(Double::class.java)?.toInt()
            ?: 0
    }

    /** Statuses are admin-configured (config/statusMeta), not a fixed enum —
     *  match English terminal roots case-insensitively. A parcel already
     *  delivered/returned/cancelled needs no last-attempt nudge. */
    private fun isTerminal(status: String): Boolean {
        val s = status.lowercase(Locale.ENGLISH)
        return s.contains("deliver") || s.contains("return") ||
            s.contains("cancel") || s.contains("complet") || s.contains("success")
    }

    private fun taka(amount: Double): String {
        return "৳${NumberFormat.getNumberInstance(Locale.US).format(Math.round(amount))}"
    }

    private fun showNotification(context: Context, c: Candidate, otherCount: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return

        val infoLine = listOf(
            c.customerName.ifBlank { c.consignmentId },
            c.customerPhone,
            c.address.take(40),
            taka(c.cod),
        ).filter { it.isNotBlank() }.joinToString(" • ")
        val body = "আজ last attempt (${c.attempt}) — আজই delivery করুন, না হলে merchant-কে জানিয়ে return করুন।\n$infoLine" +
            if (otherCount > 0) "\n(+ আরও $otherCount টা বাকি)" else ""

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("notif_parcel_id", c.consignmentId)
            putExtra("notif_scope", "worker")
        }
        val openPi = PendingIntent.getActivity(
            context, c.consignmentId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val callPi = PendingIntent.getActivity(
            context, c.consignmentId.hashCode() + 1,
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.customerPhone}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun actionPi(outcome: String?, snooze: Boolean, code: Int): PendingIntent {
            val i = Intent(context, LastAttemptReminderReceiver::class.java).apply {
                action = if (snooze) ACTION_SNOOZE else ACTION_CONFIRM
                putExtra(EXTRA_CID, c.consignmentId)
                if (outcome != null) putExtra(EXTRA_OUTCOME, outcome)
            }
            return PendingIntent.getBroadcast(
                context, c.consignmentId.hashCode() + code, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("⚠ Last attempt আজ — ${c.consignmentId}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_call, "📞 Call", callPi)
            .addAction(android.R.drawable.ic_menu_send, "✓ Delivered", actionPi(OUTCOME_DELIVERED, false, 10))
            .addAction(android.R.drawable.ic_menu_revert, "↩ Returned", actionPi(OUTCOME_RETURNED, false, 20))
            .addAction(android.R.drawable.ic_menu_recent_history, "⏳ Not Yet", actionPi(null, true, 30))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_TAG, c.consignmentId.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "databridge_alerts_channel_v2" // reuse the existing channel
        private const val NOTIF_TAG = "last_attempt"
        private const val ACTION_CONFIRM = "com.cloudx.databridge.LAST_ATTEMPT_CONFIRM"
        private const val ACTION_SNOOZE = "com.cloudx.databridge.LAST_ATTEMPT_SNOOZE"
        private const val EXTRA_CID = "cid"
        private const val EXTRA_OUTCOME = "outcome"
        private const val OUTCOME_DELIVERED = "delivered"
        private const val OUTCOME_RETURNED = "returned"
        private const val IDLE_DELAY_MS = 60 * 60_000L // nothing qualifying — look again in an hour
        private const val PREFS = "last_attempt_confirm"

        /** Next nudge in 20–40 min, randomized so it doesn't feel robotic. */
        private fun jitteredDelay(): Long = (20 + Math.random() * 20).toLong() * 60_000L

        /** Dhaka day key (GMT+6 pinned) — run IDs are Dhaka-date keyed. */
        private fun todayKey(): String = DhakaTime.todayKey()

        /** Called from WorkerSpaceFragment.loadData() (beside the CC-request
         *  reminder arm) and boot — cheap no-op when nothing qualifies. */
        fun arm(context: Context) = schedule(context.applicationContext, 5 * 60_000L)

        fun schedule(context: Context, delayMs: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, Intent(context, LastAttemptReminderReceiver::class.java),
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
                context, 0, Intent(context, LastAttemptReminderReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }

    /** On-device confirmations: cid → "outcome|yyyyMMdd". Anything not stamped
     *  today counts as expired (midnight expiry, no cleanup job — stale rows
     *  are overwritten lazily and purged opportunistically on read). */
    private object ConfirmStore {
        fun save(context: Context, cid: String, outcome: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(cid, "$outcome|${todayKey()}").apply()
        }

        fun isConfirmedToday(context: Context, cid: String): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(cid, null) ?: return false
            if (raw.substringAfter("|", "") != todayKey()) {
                // Expired (yesterday or older) — purge lazily.
                prefs.edit().remove(cid).apply()
                return false
            }
            return true
        }
    }
}
