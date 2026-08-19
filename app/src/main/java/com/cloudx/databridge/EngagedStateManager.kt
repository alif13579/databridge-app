package com.cloudx.databridge

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase

/** One agent's engagement entry — mirrors one child of engaged_at/{agentUid}. photoUrl is
 *  resolved separately (via UserNameResolver, cached) since it isn't stored in the entry
 *  itself. */
data class EngagedAgent(
    val uid: String,
    val name: String,
    val timestamp: Long,
    val photoUrl: String = ""
)

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
 */
object EngagedStateManager {

    private const val STALE_AFTER_MS = 5 * 60 * 1000L // 5 minutes

    fun markEngaged(consignmentId: String, agentUid: String, agentName: String, agentRole: String) {
        if (consignmentId.isBlank() || agentUid.isBlank()) return
        val ref = FirebaseDatabase.getInstance()
            .reference.child("courier/consignments/$consignmentId/engaged_at/$agentUid")
        val payload = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "agentName" to agentName,
            "agentRole" to agentRole
        )
        ref.setValue(payload)
    }

    /** Called when that parcel's remarks are submitted, or the card collapses — from either
     *  the Worker or Call Center flow. Removes only [agentUid]'s own entry; other agents
     *  engaged with the same parcel are untouched. If this was the last remaining entry
     *  under engaged_at, the now-empty engaged_at node itself disappears automatically as
     *  part of the same removeValue() call — Firebase Realtime Database prunes empty parent
     *  nodes on write, no separate cleanup step needed. Fire-and-forget; a failed clear here
     *  just means that agent's avatar keeps showing until the 5-minute staleness window
     *  passes, not a broken feature. */
    fun clearEngaged(consignmentId: String, agentUid: String) {
        if (consignmentId.isBlank() || agentUid.isBlank()) return
        FirebaseDatabase.getInstance()
            .reference.child("courier/consignments/$consignmentId/engaged_at/$agentUid")
            .removeValue()
    }

    /** True if [timestamp] represents a still-fresh engagement (within the staleness window).
     *  Both card adapters call this per-entry at bind/listener-fire time — never cache the
     *  result, since "is this still fresh" changes purely with wall-clock time passing, not
     *  with any Firebase event firing again. */
    fun isFresh(timestamp: Long): Boolean {
        if (timestamp <= 0L) return false
        return (System.currentTimeMillis() - timestamp) < STALE_AFTER_MS
    }

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
            EngagedAgent(
                uid = uid,
                name = name,
                timestamp = timestamp,
                photoUrl = UserNameResolver.resolvePhotoUrl(uid)
            )
        }
    }
}
