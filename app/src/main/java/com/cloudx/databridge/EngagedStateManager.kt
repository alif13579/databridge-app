package com.cloudx.databridge

import com.google.firebase.database.FirebaseDatabase

/**
 * Tracks "someone has this parcel's card open right now" across both Worker Space and Call
 * Center fragments — shared so a worker expanding a card and a CC agent seeing that on their
 * own device (and vice versa) both read/write the exact same Firebase path and apply the
 * exact same staleness rule, rather than each fragment re-implementing this independently and
 * risking the two sides disagreeing about when a ring should show.
 *
 * Path: courier/remarks_by_consignment/{consignmentId}/engaged_at
 *   timestamp : Long   — epoch millis when the card was expanded
 *   agentUid  : String
 *   agentName : String
 *   agentRole : "worker" | "cc"
 *
 * Lifecycle (per explicit product decision):
 *   START : card is expanded (collapsing does NOT clear it — the agent may still be on the
 *           call after collapsing the card to scroll/look at something else)
 *   CLEAR : that parcel's remarks are submitted (either side)
 *   SAFETY NET : a 5-minute staleness window, checked at DISPLAY time (isFresh()) — covers
 *           the case where an agent expands a card, then the app crashes or they abandon the
 *           call without ever submitting remarks, which would otherwise leave the ring
 *           spinning forever with no way to clear it except a manual Firebase edit.
 */
object EngagedStateManager {

    private const val STALE_AFTER_MS = 5 * 60 * 1000L // 5 minutes

    fun markEngaged(consignmentId: String, agentUid: String, agentName: String, agentRole: String) {
        if (consignmentId.isBlank()) return
        val ref = FirebaseDatabase.getInstance()
            .reference.child("courier/remarks_by_consignment/$consignmentId/engaged_at")
        val payload = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "agentUid"  to agentUid,
            "agentName" to agentName,
            "agentRole" to agentRole
        )
        ref.setValue(payload)
    }

    /** Called when that parcel's remarks are submitted — from either the Worker or Call
     *  Center remarks-submit flow. Fire-and-forget; a failed clear here just means the ring
     *  keeps showing until the 5-minute staleness window passes, not a broken feature. */
    fun clearEngaged(consignmentId: String) {
        if (consignmentId.isBlank()) return
        FirebaseDatabase.getInstance()
            .reference.child("courier/remarks_by_consignment/$consignmentId/engaged_at")
            .removeValue()
    }

    /** True if [timestamp] represents a still-fresh engagement (within the staleness window).
     *  Both card adapters call this at bind/listener-fire time — never cache the result,
     *  since "is this still fresh" changes purely with wall-clock time passing, not with any
     *  Firebase event firing again. */
    fun isFresh(timestamp: Long): Boolean {
        if (timestamp <= 0L) return false
        return (System.currentTimeMillis() - timestamp) < STALE_AFTER_MS
    }
}
