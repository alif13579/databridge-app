package com.cloudx.databridge

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

/**
 * Logs write failures to Firebase itself (error_logs/{uid}/{pushId}) instead of only to the
 * device's local Logcat — which nobody ever sees after the fact. This way an admin can open
 * Firebase Console and read exactly what failed and why, or paste the entry to an AI for help.
 *
 * ⚠️ NEW FIREBASE PATH — needs this rule added (top-level, sibling of "courier"/"users"/etc.)
 * before this will actually write anything:
 *
 *   "error_logs": {
 *     "$uid": {
 *       ".read":  "auth != null",
 *       ".write": "auth != null"
 *     }
 *   }
 *
 * Deliberately just "auth != null" (no role check) so logging never fails for the same
 * reason the original write failed.
 */
object FirebaseErrorLogger {
    fun log(
        screen: String,
        action: String,
        errorMessage: String,
        extra: Map<String, Any?> = emptyMap()
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "unknown"
        val ref = FirebaseDatabase.getInstance().reference.child("error_logs/$uid").push()
        val entry = mutableMapOf<String, Any?>(
            "timestamp"    to System.currentTimeMillis(),
            "screen"       to screen,
            "action"       to action,
            "errorMessage" to errorMessage
        )
        entry.putAll(extra)
        // Best-effort: if even this fails (e.g. rule not added yet), there's nowhere further
        // to report it, so we just let it fail silently rather than throwing/crashing.
        ref.setValue(entry)
    }
}
