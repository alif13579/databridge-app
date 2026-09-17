package com.cloudx.databridge

import android.content.Context
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/** One agent's engagement entry — mirrors one child of engaged_at/{agentUid}. photoUrl is
 *  resolved separately (via UserNameResolver, cached) since it isn't stored in the entry
 *  itself. [state] is "calling" while that agent has an active phone call with this
 *  customer's number (outgoing tap or incoming), else "viewing" (card expanded). Old
 *  entries without the field read as "viewing" — display treats both as engaged. */
data class EngagedAgent(
    val uid: String,
    val name: String,
    val timestamp: Long,
    val photoUrl: String = "",
    val state: String = EngagedStateManager.STATE_VIEWING
) {
    val isCalling: Boolean get() = state == EngagedStateManager.STATE_CALLING
}

/**
 * Tracks "someone has this parcel's card open right now" across both Worker Space and Call
 * Center fragments — shared so a worker expanding a card and a CC agent seeing that on their
 * own device (and vice versa) both read/write the exact same Firebase path and apply the
 * exact same staleness rule, rather than each fragment re-implementing this independently and
 * risking the two sides disagreeing about when a ring should show.
 *
 * Multi-person aware: several agents can be engaged with the same parcel at once — most
 * commonly because same-phone-number parcels are marked/cleared together (one phone call
 * verifies every parcel tied to that number), so more than one agent legitimately ends up
 * engaged with several shared parcels simultaneously. Each agent gets their own keyed entry
 * instead of one agent's mark overwriting another's.
 *
 * Path: courier/consignments/{consignmentId}/engaged_at/{agentUid}
 *   timestamp : Long   — epoch millis when this agent's card was expanded
 *   agentName : String
 *   agentRole : "worker" | "cc"
 *
 * Moved here from courier/remarks_by_consignment/{consignmentId}/engaged_at — that parent
 * node's sibling remark data (remarks_{timestamp} entries) moved to Supabase (see
 * SupabaseRemarkValidationWriter's doc comment), so engaged_at now lives directly under the
 * consignment's own Firebase node instead of a node that otherwise has nothing left under it.
 *
 * Lifecycle (per explicit product decision — revised, collapse now clears too):
 *   START : card is expanded — writes/refreshes this agent's own entry only.
 *   CLEAR : card is collapsed (including switching straight to a different card), OR
 *           that parcel's remarks are submitted (either side) — whichever happens first.
 *           Submitting remarks collapses the card in practice, but both paths clear
 *           independently so neither depends on the other actually firing. Only removes
 *           the calling agent's own entry — other agents engaged with the same parcel
 *           (e.g. via the same-phone-group fan-out) are untouched. Once the LAST engaged
 *           agent's entry is removed, engaged_at itself disappears automatically — Firebase
 *           Realtime Database has no concept of an "empty" node, so no explicit cleanup
 *           call is needed here beyond removing the one entry that was actually cleared.
 *   SAFETY NET : a 5-minute staleness window, checked at DISPLAY time (isFresh()) — covers
 *           the case where an agent expands a card, then the app crashes or is killed
 *           before either clear path runs, which would otherwise leave that agent's avatar
 *           showing forever with no way to clear it except a manual Firebase edit.
 *
 * LEAK GUARDS (engaged_at entries must never outlive the engagement — each one also
 * costs Firebase storage + per-read bandwidth on every parcel fetch):
 *   1. onDisconnect per entry (markEngaged arms it): the Firebase server removes the
 *      entry when this client's socket drops — crash, force-stop, kill, network loss.
 *      Same pattern sessions/presence already use (ConnectFragment, DataBridgeService).
 *   2. Tracked-set sweep: every mark is recorded locally (memory + SharedPreferences,
 *      per source "card"/"overlay"). App background (AppLifecycleObserver.onStop) clears
 *      this device's "card" entries; logout (AuthManager.signOut) clears all of them.
 *      Card entries are re-marked on fragment resume while still expanded, so the
 *      dialer-out-and-back flow restores the ring instead of losing it.
 *   3. Passive sweeper (sweepStaleEntries): every engaged_at read deletes entries older
 *      than 30 min (6x the display window — absorbs cross-device clock skew), capped per
 *      read. Browsing garbage-collects other agents'/devices' leftovers over time.
 *   4. Collapse/navigate holes closed at the call sites: adapter collapse paths that
 *      used to drop the callback (filter refresh, missing previous item) now clear by id,
 *      and both fragments clear the expanded card on destroy + re-mark on resume.
 */
object EngagedStateManager {

    const val SOURCE_CARD = "card"
    const val SOURCE_OVERLAY = "overlay"
    /** Active phone call with this customer's number (outgoing tap or incoming).
     *  Like "overlay", spared by the app-background sweep (which only clears "card"),
     *  so dialer-out / call-screen time keeps the ring visible to other agents. */
    const val SOURCE_CALL = "call"

    const val STATE_VIEWING = "viewing"
    const val STATE_CALLING = "calling"

    private const val ENGAGED_NODE = "engaged_at"
    private const val STALE_AFTER_MS = 5 * 60 * 1000L // 5 minutes
    private const val SWEEP_AFTER_MS = 30 * 60 * 1000L // 30 minutes (conservative vs clock skew)
    private const val SWEEP_MAX_PER_READ = 5
    private const val TRACKED_MAX = 500
    private const val PREFS = "engaged_state"
    private const val KEY_TRACKED = "tracked" // Set<String> of "consignmentId|source"

    private const val TAG = "EngagedStateManager"

    @Volatile
    private var appContext: Context? = null
    private val lock = Any()
    // consignmentId -> sources that marked it on this device. LinkedHashMap = oldest first.
    private val tracked = LinkedHashMap<String, MutableSet<String>>()

    /** Call once from DataBridgeApplication.onCreate — enables the tracked-set guards. */
    fun init(context: Context) {
        appContext = context.applicationContext
        loadTracked()
    }

    private fun prefs() =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadTracked() {
        try {
            val saved = prefs()?.getStringSet(KEY_TRACKED, emptySet()).orEmpty()
            synchronized(lock) {
                tracked.clear()
                saved.forEach { entry ->
                    val cid = entry.substringBefore("|")
                    val src = entry.substringAfter("|", SOURCE_CARD)
                    if (cid.isNotBlank()) tracked.getOrPut(cid) { mutableSetOf() }.add(src)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadTracked failed: ${e.message}")
        }
    }

    private fun persistTracked() {
        try {
            val flat = synchronized(lock) {
                tracked.flatMap { (cid, sources) -> sources.map { "$cid|$it" } }.toSet()
            }
            prefs()?.edit()?.putStringSet(KEY_TRACKED, flat)?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "persistTracked failed: ${e.message}")
        }
    }

    private fun track(cid: String, source: String) {
        synchronized(lock) {
            tracked.getOrPut(cid) { mutableSetOf() }.add(source)
            while (tracked.size > TRACKED_MAX) {
                tracked.entries.iterator().let { it.next(); it.remove() }
            }
        }
        persistTracked()
    }

    private fun untrack(cid: String, source: String? = null) {
        synchronized(lock) {
            if (source == null) tracked.remove(cid)
            else {
                tracked[cid]?.remove(source)
                if (tracked[cid].isNullOrEmpty()) tracked.remove(cid)
            }
        }
        persistTracked()
    }

    fun nodePath(consignmentId: String): String = "courier/consignments/$consignmentId/$ENGAGED_NODE"

    fun markEngaged(consignmentId: String, agentUid: String, agentName: String, agentRole: String, source: String = SOURCE_CARD, state: String = STATE_VIEWING) {
        if (consignmentId.isBlank() || agentUid.isBlank()) return
        track(consignmentId, source)
        val ref = FirebaseDatabase.getInstance()
            .reference.child("${nodePath(consignmentId)}/$agentUid")
        val payload = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "agentName" to agentName,
            "agentRole" to agentRole,
            "state" to state
        )
        ref.setValue(payload)
        // Guard 1: server removes this entry when our socket drops (crash/kill/blip).
        // Re-armed on every mark, so each refresh extends it. Best-effort — a failed
        // arm just falls back to the tracked-set sweep + staleness window.
        try {
            ref.onDisconnect().removeValue()
        } catch (e: Exception) {
            Log.w(TAG, "onDisconnect arm failed for $consignmentId: ${e.message}")
        }
    }

    /** Called when that parcel's remarks are submitted, or the card collapses — from either
     *  the Worker or Call Center flow. Removes only [agentUid]'s own entry; other agents
     *  engaged with the same parcel are untouched. If this was the last remaining entry
     *  under engaged_at, the now-empty engaged_at node itself disappears automatically as
     *  part of the same removeValue() call — Firebase Realtime Database prunes empty parent
     *  nodes on write, no separate cleanup step needed. Fire-and-forget; a failed clear here
     *  just means that agent's avatar keeps showing until the 5-minute staleness window
     *  passes, not a broken feature.
     *
     *  [source] narrows the clear to one local source ("card"/"overlay"/"call"): the
     *  Firebase entry is removed ONLY when no other source on this device still holds
     *  this parcel. Card collapse passes SOURCE_CARD so an active call's ring survives;
     *  remark-save / logout pass null to clear everything. */
    fun clearEngaged(consignmentId: String, agentUid: String, source: String? = null) {
        if (consignmentId.isBlank() || agentUid.isBlank()) return
        untrack(consignmentId, source)
        if (source != null && hasTracked(consignmentId)) {
            // Another local source (e.g. an active call) still holds this parcel —
            // keep the Firebase ring up instead of deleting it out from under the call.
            return
        }
        FirebaseDatabase.getInstance()
            .reference.child("${nodePath(consignmentId)}/$agentUid")
            .removeValue()
    }

    /** True if this device still tracks [consignmentId] under any (or the given) source. */
    fun hasTracked(consignmentId: String, source: String? = null): Boolean {
        synchronized(lock) {
            val sources = tracked[consignmentId] ?: return false
            return if (source == null) sources.isNotEmpty() else source in sources
        }
    }

    /**
     * Guard 2: clears every entry THIS device marked (tracked set), optionally limited
     * to one [onlySource] ("card" for app-background — overlay entries stay while the
     * call popup is up and refreshing). Logout passes null to clear everything.
     * Best-effort per entry; onDisconnect + sweeper cover whatever fails here.
     */
    suspend fun clearAllTrackedNow(agentUid: String, onlySource: String? = null) {
        if (agentUid.isBlank()) return
        val ids = synchronized(lock) {
            tracked.filter { (_, sources) -> onlySource == null || onlySource in sources }
                .keys.toList()
        }.take(300)
        val db = FirebaseDatabase.getInstance()
        ids.forEach { cid ->
            untrack(cid, onlySource)
            // Don't delete the Firebase ring when another local source (e.g. an active
            // call) still holds this parcel — the background sweep only clears "card".
            if (onlySource != null && hasTracked(cid)) return@forEach
            try {
                db.reference.child("${nodePath(cid)}/$agentUid").removeValue().await()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Guard 3: passive garbage collection — deletes entries older than [olderThanMs]
     * (default 30 min, far beyond the 5-min display window to absorb clock skew),
     * capped at [max] per read so one historic node can't cause a write storm.
     * Safe to call on every engaged_at snapshot: fresh entries (including our own)
     * never match, and deletions only remove provably-dead presence.
     */
    fun sweepStaleEntries(
        consignmentId: String,
        engagedAtSnapshot: DataSnapshot,
        olderThanMs: Long = SWEEP_AFTER_MS,
        max: Int = SWEEP_MAX_PER_READ
    ) {
        if (consignmentId.isBlank()) return
        try {
            val now = System.currentTimeMillis()
            val db = FirebaseDatabase.getInstance()
            engagedAtSnapshot.children.mapNotNull { child ->
                val ts = child.child("timestamp").getValue(Long::class.java) ?: return@mapNotNull null
                if (ts > 0 && now - ts > olderThanMs) child.key else null
            }.take(max).forEach { key ->
                db.reference.child("${nodePath(consignmentId)}/$key").removeValue()
            }
        } catch (e: Exception) {
            Log.w(TAG, "sweep failed for $consignmentId: ${e.message}")
        }
    }

    /** True if [timestamp] represents a still-fresh engagement (within the staleness window).
     *  Both card adapters call this per-entry at bind/listener-fire time — never cache the
     *  result, since "is this still fresh" changes purely with wall-clock time passing, not
     *  with any Firebase event firing again. */
    fun isFresh(timestamp: Long): Boolean {
        if (timestamp <= 0L) return false
        return (System.currentTimeMillis() - timestamp) < STALE_AFTER_MS
    }

    /**
     * Agents with a live phone call on this parcel right now (ActiveCallEngagement's
     * SOURCE_CALL + state="calling"), freshest first. Powers the 📞 calling badge on
     * both card adapters — same freshness rule as the engaged ring, so the badge
     * auto-hides on call end (explicit clear), remark save, timeout, or staleness.
     */
    fun callingAgents(agents: List<EngagedAgent>): List<EngagedAgent> =
        agents.filter { it.isCalling && isFresh(it.timestamp) }.sortedByDescending { it.timestamp }

    /** Parses an engaged_at snapshot (the node containing one child per engaged agentUid)
     *  into a list of EngagedAgent, resolving each one's photo via UserNameResolver's cache.
     *  Shared by both fragments' batch-load and live-listener parsing paths so there's one
     *  place that knows this shape, instead of four copies. Does NOT filter by isFresh() —
     *  callers/adapters still do that at bind/render time, same as before. */
    suspend fun parseEngagedAgents(engagedAtSnapshot: DataSnapshot): List<EngagedAgent> {
        return engagedAtSnapshot.children.mapNotNull { child ->
            val uid = child.key ?: return@mapNotNull null
            val timestamp = child.child("timestamp").getValue(Long::class.java) ?: return@mapNotNull null
            val name = child.child("agentName").getValue(String::class.java).orEmpty()
            val state = child.child("state").getValue(String::class.java)
                ?.takeIf { it == STATE_CALLING || it == STATE_VIEWING } ?: STATE_VIEWING
            EngagedAgent(
                uid = uid,
                name = name,
                timestamp = timestamp,
                photoUrl = UserNameResolver.resolvePhotoUrl(uid),
                state = state
            )
        }
    }
}
