package com.cloudx.databridge

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * 🔹 কন্টেইনার ম্যানেজার: মার্জ-বেসড মাইগ্রেশন + অ্যাটমিক আপডেট
 * ✅ পুরনো records ডিলিট করবে না (merge করবে)
 * ✅ updated_at সর্বদা আপডেট করবে
 * ✅ repository দেওয়া থাকলে local Room DB-ও একই কলে, নিশ্চিতভাবে আপডেট করে।
 *
 *    আগে এই Room-update দায়িত্বটা সম্পূর্ণ আলাদা একটা সিস্টেম (DisconnectHandler +
 *    MigrationEngine, DataBridgeService.onDestroy() থেকে ট্রিগার হতো) আলাদাভাবে পালন
 *    করত — যেটা disconnectExtension()-এর নিজের migrate কলের সাথে প্রায় একই মুহূর্তে
 *    রেস করত (দুটোই sessions/{extId}/records একসাথে পড়ার চেষ্টা করত)। কে আগে পড়ে
 *    ফেলত তার উপর নির্ভর করে Room DB আপডেট কখনো হতো, কখনো silently skip হয়ে যেত
 *    (data হারাত না — শুধু local cache-এর source/container_id ট্যাগ stale থেকে যেত)।
 *    এখন একটাই কল, একটাই সিস্টেম — race করার মতো দ্বিতীয় কেউ নেই।
 */
object FirebaseContainerManager {
    private const val TAG = "ContainerManager"
    private val db = FirebaseDatabase.getInstance()

    suspend fun verifyAndMigrate(extensionId: String, uid: String, repository: CallRepository? = null): Boolean {
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
                val roomUpdates = mutableListOf<CallRecord>()

                // ✅ সবসময় updated_at আপডেট করবে (সাকসেস/ফেইল নির্বিশেষে)
                updates["container/$containerId/meta/updated_at"] = now

                // ✅ পুরনো ডাটা ডিলিট না করে মার্জ করবে
                if (!sourceRecords.isNullOrEmpty()) {
                    sourceRecords.forEach { (key, value) ->
                        val rid = key as? String ?: return@forEach
                        updates["container/$containerId/records/$rid"] = value

                        // repository দেওয়া থাকলে Room DB-এর জন্যও একই record রেডি করে রাখা
                        if (repository != null) {
                            val data = value as? Map<*, *>
                            if (data != null) {
                                roomUpdates.add(
                                    CallRecord(
                                        id = rid,
                                        text = data["text"] as? String ?: "",
                                        cleaned = data["cleaned"] as? String ?: "",
                                        type = data["type"] as? String ?: "text",
                                        received_at = (data["received_at"] as? Number)?.toLong() ?: 0L,
                                        actions = ActionsJson.fromAny(data["actions"]),
                                        source = "container",
                                        container_id = containerId,
                                        extension_id = extensionId
                                    )
                                )
                            }
                        }
                    }
                    Log.d(TAG, "📦 Merging ${sourceRecords.size} records")
                }

                // ✅ সেশন নোড ডিলিট (null = Firebase ডিলিট কমান্ড) — একই অ্যাটমিক
                // multi-path আপডেটের অংশ হিসেবে, merge-এর সাথে একই মুহূর্তে
                updates["sessions/$extensionId"] = null

                // ✅ অ্যাটমিক আপডেট (একসাথে সব প্যাচ হবে)
                db.reference.updateChildren(updates).await()

                // ✅ Firebase write সফল হওয়ার পর Room DB — reliably, race ছাড়াই
                if (repository != null && roomUpdates.isNotEmpty()) {
                    roomUpdates.forEach { repository.updateCall(it) }
                    Log.d(TAG, "💾 Room DB updated for ${roomUpdates.size} records")
                }

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
