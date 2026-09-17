package com.cloudx.databridge

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Live "who is on the phone with this customer right now" presence.
 *
 * Problem it fixes: tapping Call opens the dialer, the app backgrounds, and
 * AppLifecycleObserver's sweep deletes the card's engaged_at entry — so while
 * Agent A is actually talking, other CC agents / workers see the parcel as free
 * and double-call the same customer.
 *
 * How it works:
 *  - Outgoing tap (CC/Worker) or incoming ring -> mark EVERY parcel sharing that
 *    phone number (same-phone fan-out) with [EngagedStateManager.SOURCE_CALL] +
 *    state="calling". Same Firebase path the card ring already reads
 *    (courier/consignments/{cid}/engaged_at/{uid}), so both fragments + workers
 *    see it with zero listener changes. Outgoing vs incoming doesn't matter.
 *  - SOURCE_CALL survives the background sweep (which only clears "card") and is
 *    refreshed every 2 min, inside the 5-min display window, for the whole call.
 *  - Cleared on real call end (OFFHOOK->IDLE via CallStateWatcher), remark save,
 *    timeout, or crash (onDisconnect armed by markEngaged). Card collapse never
 *    kills it (source-aware clear in EngagedStateManager).
 */
object ActiveCallEngagement {

    private const val TAG = "ActiveCallEngagement"
    private const val REFRESH_MS = 120_000L // inside the 5-min isFresh window
    private const val OUTGOING_TIMEOUT_MS = 15 * 60 * 1000L
    private const val INCOMING_TIMEOUT_MS = 10 * 60 * 1000L
    private const val NO_PERMISSION_FALLBACK_MS = 7 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    // phoneNorm -> session (one call per number; re-tap merges ids + restarts watch)
    private val sessions = mutableMapOf<String, CallSession>()
    // cid -> phoneNorm reverse index for stopIds()
    private val cidToPhone = mutableMapOf<String, String>()

    private data class CallSession(
        val phoneNorm: String,
        val ids: MutableSet<String>,
        val uid: String,
        var refreshJob: Job? = null,
        var watchJob: Job? = null
    )

    /** Outgoing tap from CC/Worker — [consignmentIds] should already be the same-phone group. */
    fun startOutgoing(context: Context, phone: String, consignmentIds: List<String>) {
        val ids = consignmentIds.filter { it.isNotBlank() }.toSet()
        if (ids.isEmpty() || phone.isBlank()) return
        startInternal(context.applicationContext, phone, ids, isIncoming = false)
    }

    /** Phone-only entry (History / dial sites without a loaded parcel group) —
     *  resolves sibling ids via the phone index, then tracks like an outgoing call. */
    fun startForPhone(context: Context, phone: String, isIncoming: Boolean = false) {
        if (phone.isBlank()) return
        val appCtx = context.applicationContext
        scope.launch {
            val ids = try {
                siblingIdsForPhone(phone, "")
            } catch (_: Exception) {
                emptySet()
            }
            if (ids.isEmpty()) return@launch
            startInternal(appCtx, phone, ids, isIncoming)
        }
    }

    /** Incoming ring — resolves ALL sibling parcel ids for [rawPhone] itself so the ring
     *  shows even when the overlay popup is gated off (no permission / toggle off). */
    fun startIncoming(context: Context, rawPhone: String, primaryId: String = "") {
        if (rawPhone.isBlank()) return
        val appCtx = context.applicationContext
        scope.launch {
            val ids = try {
                siblingIdsForPhone(rawPhone, primaryId)
            } catch (_: Exception) {
                emptySet()
            }
            val finalIds = if (ids.isEmpty() && primaryId.isNotBlank()) setOf(primaryId) else ids
            if (finalIds.isEmpty()) return@launch
            startInternal(appCtx, rawPhone, finalIds, isIncoming = true)
        }
    }

    /** Remark saved / call over for these parcels — clears only the CALL source. */
    fun stopIds(consignmentIds: Collection<String>) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (uid.isBlank()) return
        synchronized(lock) {
            consignmentIds.forEach { cid ->
                val phone = cidToPhone.remove(cid)
                val session = phone?.let { sessions[it] }
                session?.ids?.remove(cid)
                try {
                    EngagedStateManager.clearEngaged(cid, uid, EngagedStateManager.SOURCE_CALL)
                } catch (_: Exception) {
                }
                if (session != null && session.ids.isEmpty()) {
                    session.refreshJob?.cancel()
                    session.watchJob?.cancel()
                    sessions.remove(phone)
                }
            }
        }
    }

    /** True if this device currently holds [consignmentId] as an active call. */
    fun isCalling(consignmentId: String): Boolean {
        synchronized(lock) { return cidToPhone.containsKey(consignmentId) }
    }

    /** Logout / full reset — cancels heartbeats and clears CALL entries. */
    fun stopAll() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val ids: List<String>
        synchronized(lock) {
            ids = cidToPhone.keys.toList()
            sessions.values.forEach {
                it.refreshJob?.cancel()
                it.watchJob?.cancel()
            }
            sessions.clear()
            cidToPhone.clear()
        }
        if (uid.isBlank()) return
        scope.launch {
            ids.forEach {
                try {
                    EngagedStateManager.clearEngaged(it, uid, EngagedStateManager.SOURCE_CALL)
                } catch (_: Exception) {
                }
            }
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun startInternal(appCtx: Context, phone: String, ids: Set<String>, isIncoming: Boolean) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val uid = user.uid
        if (uid.isBlank()) return
        val phoneNorm = norm(phone)
        if (phoneNorm.isBlank()) return
        val name = user.displayName?.trim().orEmpty().ifBlank {
            if (RbacManager.hasPermission("nav_call_center")) "CC Agent" else "Worker"
        }
        val role = if (RbacManager.hasPermission("nav_call_center")) "cc" else "worker"

        // Immediate mark — other agents see the ring before the dialer even opens.
        ids.forEach {
            try {
                EngagedStateManager.markEngaged(
                    it, uid, name, role,
                    EngagedStateManager.SOURCE_CALL, EngagedStateManager.STATE_CALLING
                )
            } catch (e: Exception) {
                Log.w(TAG, "mark call failed for $it: ${e.message}")
            }
        }

        val oldSession: CallSession?
        synchronized(lock) {
            oldSession = sessions[phoneNorm]
            oldSession?.refreshJob?.cancel()
            oldSession?.watchJob?.cancel()
            val merged = (oldSession?.ids ?: mutableSetOf()) + ids
            val session = CallSession(phoneNorm, merged.toMutableSet(), uid)
            sessions[phoneNorm] = session
            merged.forEach { cidToPhone[it] = phoneNorm }

            session.refreshJob = scope.launch {
                while (true) {
                    delay(REFRESH_MS)
                    val live: Set<String>
                    synchronized(lock) { live = sessions[phoneNorm]?.ids?.toSet() ?: emptySet() }
                    if (live.isEmpty()) return@launch
                    live.forEach {
                        try {
                            EngagedStateManager.markEngaged(
                                it, uid, name, role,
                                EngagedStateManager.SOURCE_CALL, EngagedStateManager.STATE_CALLING
                            )
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            session.watchJob = scope.launch {
                val timeout = if (isIncoming) INCOMING_TIMEOUT_MS else OUTGOING_TIMEOUT_MS
                if (CallStateWatcher.hasPermission(appCtx)) {
                    // Outgoing: require OFFHOOK first (dialer-opened-then-closed must not
                    // fake-clear). Incoming: also ends on missed/rejected (never OFFHOOK).
                    val ended = try {
                        CallStateWatcher.awaitCallEnd(appCtx, timeout, requireOffhookFirst = !isIncoming)
                    } catch (_: Exception) {
                        false
                    }
                    if (ended) {
                        stopPhone(phoneNorm)
                    } else {
                        // Timeout — never let a leaked entry outlive the 30-min sweeper.
                        delay(60_000L)
                        stopPhone(phoneNorm)
                    }
                } else {
                    // No telephony signal — time-boxed presence only. The 5-min display
                    // window hides it anyway; the sweeper deletes the leftover by 30 min.
                    delay(NO_PERMISSION_FALLBACK_MS)
                    stopPhone(phoneNorm)
                }
            }
        }
    }

    private fun stopPhone(phoneNorm: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val ids: Set<String>
        synchronized(lock) {
            val session = sessions.remove(phoneNorm) ?: return
            session.refreshJob?.cancel()
            // Don't cancel ourselves if we're the watch job — harmless either way.
            ids = session.ids.toSet()
            ids.forEach { cidToPhone.remove(it) }
        }
        if (uid.isBlank()) return
        ids.forEach {
            try {
                EngagedStateManager.clearEngaged(it, uid, EngagedStateManager.SOURCE_CALL)
            } catch (_: Exception) {
            }
        }
        Log.d(TAG, "call presence cleared for $phoneNorm (${ids.size} parcels)")
    }

    private suspend fun siblingIdsForPhone(rawPhone: String, primaryId: String): Set<String> =
        withContext(Dispatchers.IO) {
            try {
                val normalized = ConfigSheetParseUtil.normalizePhone(rawPhone)
                if (normalized.isBlank()) return@withContext emptySet()
                val snap = FirebaseDatabase.getInstance().reference
                    .child("courier/consignments_by_phone/$normalized").get().await()
                val ids = snap.children.mapNotNull { it.key }.filter { it.isNotBlank() }.toMutableSet()
                if (primaryId.isNotBlank()) ids.add(primaryId)
                ids
            } catch (_: Exception) {
                if (primaryId.isNotBlank()) setOf(primaryId) else emptySet()
            }
        }

    private fun norm(phone: String): String {
        return try {
            AutoDialHelper.normalizeBdPhone(phone)
        } catch (_: Exception) {
            phone.filter { it.isDigit() }.takeLast(11)
        }
    }

    /** Test-only reset. */
    fun clearForTests() {
        synchronized(lock) {
            sessions.values.forEach {
                it.refreshJob?.cancel()
                it.watchJob?.cancel()
            }
            sessions.clear()
            cidToPhone.clear()
        }
    }
}
