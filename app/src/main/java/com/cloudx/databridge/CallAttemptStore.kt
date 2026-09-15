package com.cloudx.databridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 📞 CallAttemptStore — per-dial evidence for true-vs-fake dial tracking.
 *
 * Every app-initiated dial (worker/CC manual tap, swipe, auto-call) is recorded with
 * its CallLog talk duration + wall-clock elapsed time. A remark save later attaches
 * TODAY's summary for that number ([summarizeToday]), which syncs to Supabase
 * (validations.call_count/call_talk_sec/call_max_talk_sec/call_cut) so supervisors
 * can see, per remark, whether the agent actually talked:
 *   talk > 0          → real conversation
 *   0s + long elapsed → rang out (genuine, customer didn't pick)
 *   0s + tiny elapsed → instant-cut fake (or dialer opened and closed)
 *   no entry at all   → dialer never engaged
 *
 * Flow: [beginDial] at tap time stashes a pending dial; [resolvePending] (fragment
 * onResume + app start) matches it against the call log once the OS has written the
 * entry. Without READ_CALL_LOG, elapsed-only evidence is recorded instead (an
 * instant return is still an instant return). Pending entries older than
 * [PENDING_TIMEOUT_MIN] without a log match are dropped — never verifiable.
 * Records prune past [RETAIN_DAYS] days, capped at [MAX_RECORDS].
 */
object CallAttemptStore {

    const val KIND_MANUAL = "manual"
    const val KIND_AUTO = "auto"
    const val ROLE_WORKER = "worker"
    const val ROLE_CC = "cc"

    /** 0-sec dial returning faster than this = instant cut (fake pattern). */
    const val CUT_ELAPSED_MAX_SEC = 10
    private const val PENDING_TIMEOUT_MIN = 10L
    private const val RETAIN_DAYS = 7
    private const val MAX_RECORDS = 1000

    private const val PREFS = "call_attempts"
    private const val KEY_RECORDS = "records"
    private const val KEY_PENDING = "pending"

    private const val TAG = "CallAttemptStore"

    data class Summary(
        val count: Int,
        val talkSec: Int,
        val maxTalkSec: Int,
        val cut: Int
    )

    private data class Pending(
        val cid: String,
        val phone: String,
        val startMs: Long,
        val kind: String,
        val role: String
    )

    private data class Attempt(
        val cid: String,
        val phoneNorm: String,
        val atMs: Long,
        val durationSec: Int?,
        val elapsedSec: Int,
        val kind: String,
        val role: String
    )

    @Volatile
    private var appContext: Context? = null

    /** Call once from DataBridgeApplication.onCreate. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── pending dials ────────────────────────────────────────────────────────

    /** Stashes a dial started now; returns the start timestamp. Never throws. */
    fun beginDial(cid: String, phone: String, kind: String, role: String): Long {
        val start = System.currentTimeMillis()
        try {
            if (cid.isBlank() || phone.isBlank()) return start
            val list = loadPending().toMutableList()
            list.add(Pending(cid, phone, start, kind, role))
            savePending(list.takeLast(200))
        } catch (e: Exception) {
            Log.w(TAG, "beginDial failed: ${e.message}")
        }
        return start
    }

    /**
     * Matches pending dials against the call log (call from onResume + app start).
     * Verified matches are recorded; permission-less quick returns are recorded as
     * unverified elapsed-only evidence; unmatchable olds are dropped.
     */
    suspend fun resolvePending() = withContext(Dispatchers.IO) {
        try {
            val ctx = appContext ?: return@withContext
            val pend = loadPending()
            if (pend.isEmpty()) return@withContext
            val now = System.currentTimeMillis()
            val perm = CallLogHelper.hasPermission(ctx)
            val keep = mutableListOf<Pending>()
            pend.forEach { p ->
                val elapsedSec = ((now - p.startMs) / 1000).toInt().coerceAtLeast(0)
                if (perm) {
                    val dur = CallLogHelper.getLastCallDurationSeconds(ctx, p.phone, p.startMs)
                    if (dur != null) {
                        addRecord(Attempt(p.cid, norm(p.phone), p.startMs, dur, elapsedSec, p.kind, p.role))
                        return@forEach
                    }
                } else {
                    // No log access: elapsed alone still proves an instant cut.
                    addRecord(Attempt(p.cid, norm(p.phone), p.startMs, null, elapsedSec, p.kind, p.role))
                    return@forEach
                }
                if (now - p.startMs < PENDING_TIMEOUT_MIN * 60_000L) keep.add(p)
            }
            savePending(keep.takeLast(200))
        } catch (e: Exception) {
            Log.w(TAG, "resolvePending failed: ${e.message}")
        }
    }

    /** Direct record for flows that already measured talk+elapsed (auto-call).
     *  Null duration = log entry never appeared (treated as unknown talk). */
    fun recordVerified(
        cid: String, phone: String, startMs: Long,
        durationSec: Int?, elapsedSec: Int, kind: String, role: String
    ) {
        try {
            if (cid.isBlank() || phone.isBlank()) return
            addRecord(Attempt(cid, norm(phone), startMs, durationSec, elapsedSec.coerceAtLeast(0), kind, role))
        } catch (e: Exception) {
            Log.w(TAG, "recordVerified failed: ${e.message}")
        }
    }

    // ── summary for remark-save payloads ─────────────────────────────────────

    /**
     * TODAY's (device-local midnight) dial summary for [phone], or null when unknown
     * (no READ_CALL_LOG and no locally recorded attempts). Zero-count with permission
     * is a KNOWN zero ("saved remark with 0 dials") — not null.
     */
    fun summarizeToday(phone: String): Summary? {
        return try {
            val ctx = appContext ?: return null
            val target = norm(phone)
            if (target.isBlank()) return null
            val dayStart = dayStartMs()
            val recs = loadRecords().filter { it.phoneNorm == target && it.atMs >= dayStart }
            if (recs.isEmpty()) {
                return if (CallLogHelper.hasPermission(ctx)) Summary(0, 0, 0, 0) else null
            }
            Summary(
                count = recs.size,
                talkSec = recs.sumOf { it.durationSec ?: 0 },
                maxTalkSec = recs.maxOfOrNull { it.durationSec ?: 0 } ?: 0,
                cut = recs.count { (it.durationSec ?: 0) == 0 && it.elapsedSec < CUT_ELAPSED_MAX_SEC }
            )
        } catch (e: Exception) {
            Log.w(TAG, "summarizeToday failed: ${e.message}")
            null
        }
    }

    // ── storage ──────────────────────────────────────────────────────────────

    private fun norm(phone: String): String {
        return try {
            AutoDialHelper.normalizeBdPhone(phone)
        } catch (_: Exception) {
            phone.filter { it.isDigit() }.takeLast(11)
        }
    }

    private fun dayStartMs(): Long {
        return java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun loadRecords(): List<Attempt> {
        val out = mutableListOf<Attempt>()
        try {
            val raw = prefs()?.getString(KEY_RECORDS, null) ?: return out
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Attempt(
                        cid = o.optString("cid"),
                        phoneNorm = o.optString("phone"),
                        atMs = o.optLong("at"),
                        durationSec = if (o.isNull("dur")) null else o.optInt("dur"),
                        elapsedSec = o.optInt("elap"),
                        kind = o.optString("kind"),
                        role = o.optString("role")
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadRecords failed: ${e.message}")
        }
        return out
    }

    private fun addRecord(a: Attempt) {
        try {
            val cutoff = System.currentTimeMillis() - RETAIN_DAYS * 24 * 60 * 60 * 1000L
            val list = (loadRecords() + a)
                .filter { it.atMs >= cutoff }
                .takeLast(MAX_RECORDS)
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject()
                    .put("cid", it.cid).put("phone", it.phoneNorm).put("at", it.atMs)
                    .put("dur", it.durationSec ?: JSONObject.NULL).put("elap", it.elapsedSec)
                    .put("kind", it.kind).put("role", it.role))
            }
            prefs()?.edit()?.putString(KEY_RECORDS, arr.toString())?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "addRecord failed: ${e.message}")
        }
    }

    private fun loadPending(): List<Pending> {
        val out = mutableListOf<Pending>()
        try {
            val raw = prefs()?.getString(KEY_PENDING, null) ?: return out
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Pending(
                        cid = o.optString("cid"),
                        phone = o.optString("phone"),
                        startMs = o.optLong("start"),
                        kind = o.optString("kind"),
                        role = o.optString("role")
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadPending failed: ${e.message}")
        }
        return out
    }

    private fun savePending(list: List<Pending>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject()
                    .put("cid", it.cid).put("phone", it.phone).put("start", it.startMs)
                    .put("kind", it.kind).put("role", it.role))
            }
            prefs()?.edit()?.putString(KEY_PENDING, arr.toString())?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "savePending failed: ${e.message}")
        }
    }
}
