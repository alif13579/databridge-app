package com.cloudx.databridge

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * 🔀 MigrationEngine.kt
 * ✅ sessions/{extId}/records → container/container_{uid}/records অ্যাটমিক ট্রান্সফার
 */
object MigrationEngine {
    private const val TAG = "MigrationEngine"

    suspend fun migrateSessionToContainer(extId: String, authUid: String, repository: CallRepository) {
        val sessionRef = FirebaseDatabase.getInstance().getReference("sessions/$extId/records")
        val containerPath = "container/container_$authUid/records"
        val containerRef = FirebaseDatabase.getInstance().getReference(containerPath)

        val snapshot = sessionRef.get().await()
        if (!snapshot.exists()) return

        val updates = mutableMapOf<String, Any?>()
        val roomUpdates = mutableListOf<CallRecord>()

        for (child in snapshot.children) {
            val rid = child.key ?: continue
            val data = child.value as? Map<*, *> ?: continue

            // ✅ কন্টেইনার পাথে কপি
            updates["$containerPath/$rid"] = data
            // ✅ রুম DB-এ source/container_id আপডেট
            roomUpdates.add(
                CallRecord(
                    id = rid, text = data["text"] as? String ?: "", cleaned = data["cleaned"] as? String ?: "",
                    type = data["type"] as? String ?: "text", received_at = (data["received_at"] as? Number)?.toLong() ?: 0L,
                    actions = ActionsJson.fromAny(data["actions"]), source = "container",
                    container_id = "container_$authUid", extension_id = extId
                )
            )
        }

        // ✅ অ্যাটমিক রাইট + মেটা আপডেট
        updates["container/container_$authUid/meta/updated_at"] = System.currentTimeMillis()
        try {
            FirebaseDatabase.getInstance().reference.updateChildren(updates).await()
            // ✅ রুম আপডেট
            roomUpdates.forEach { repository.updateCall(it) }
            // ✅ সেশন ক্লিনআপ
            sessionRef.removeValue().await()
            Log.d(TAG, "✅ Migrated ${roomUpdates.size} records to container & cleaned sessions/$extId")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Migration failed: ${e.message}")
            throw e
        }
    }
}