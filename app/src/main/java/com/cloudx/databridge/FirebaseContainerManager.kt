package com.cloudx.databridge

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * 🔹 কন্টেইনার ম্যানেজার: মার্জ-বেসড মাইগ্রেশন + অ্যাটমিক আপডেট
 * ✅ পুরনো records ডিলিট করবে না (merge করবে)
 * ✅ updated_at সর্বদা আপডেট করবে
 */
object FirebaseContainerManager {
    private const val TAG = "ContainerManager"
    private val db = FirebaseDatabase.getInstance()

    suspend fun verifyAndMigrate(extensionId: String, uid: String): Boolean {
        return try {
            val containerId = "container_$uid"
            val now = System.currentTimeMillis()
            val metaRef = db.getReference("container/$containerId/meta")

            // ✅ ১. মেটা ইনিশিয়ালাইজ (শুধু যদি না থাকে)
            val currentMeta = metaRef.get().await().value
            if (currentMeta == null) {
                metaRef.setValue(mapOf(
                    "owner_uid" to uid,
                    "status" to "active",
                    "created_at" to now,
                    "updated_at" to now
                )).await()
            }

            // ✅ ২. মাইগ্রেশন (যদি extensionId দেওয়া থাকে)
            if (extensionId.isNotEmpty()) {
                val sourceRef = db.getReference("sessions/$extensionId/records")
                val sourceRecords = sourceRef.get().await().value as? Map<*, *>

                val updates = mutableMapOf<String, Any?>()

                // ✅ সবসময় updated_at আপডেট করবে (সাকসেস/ফেইল নির্বিশেষে)
                updates["container/$containerId/meta/updated_at"] = now

                // ✅ পুরনো ডাটা ডিলিট না করে মার্জ করবে
                if (!sourceRecords.isNullOrEmpty()) {
                    sourceRecords.forEach { (key, value) ->
                        updates["container/$containerId/records/$key"] = value
                    }
                    Log.d(TAG, "📦 Merging ${sourceRecords.size} records")
                }

                // ✅ সেশন নোড ডিলিট (null = Firebase ডিলিট কমান্ড)
                updates["sessions/$extensionId"] = null

                // ✅ অ্যাটমিক আপডেট (একসাথে সব প্যাচ হবে)
                db.reference.updateChildren(updates).await()

                // ✅ কানেকশন ট্র্যাকিং রিমুভ
                UserRepository(uid).removeExtensionConnection(extensionId)
                Log.d(TAG, "🎉 Migration complete. Records merged, updated_at set.")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Migration failed: ${e.message}", e)
            false
        }
    }
}