package com.cloudx.databridge

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * 🔹 SyncManager: Cloud → Local ডাটা পুল করে
 * ✅ কন্টেইনার + অ্যাক্টিভ সেশন থেকে ডাটা ফেচ → মার্জ → Newest→Oldest সর্ট
 */
class SyncManager(
    private val repository: CallRepository,
    private val appPrefs: AppPreferences,
    private val context: Context
) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseDatabase.getInstance()

    companion object {
        private const val TAG = "SyncManager"
    }

    /**
     * 🔹 মেইন সিঙ্ক ফাংশন
     */
    suspend fun startSync(uid: String) {
        Log.d(TAG, "⬇️ Pulling cloud data for: $uid")
        try {
            val containerId = "container_$uid"
            val userRepo = UserRepository(uid)
            val activeExtensions = userRepo.getConnectedExtensionIds()
            val allRecords = mutableListOf<CallRecord>()

            // ✅ ১. কন্টেইনার থেকে পার্মানেন্ট রেকর্ড ফেচ
            val containerSnap = db.getReference("container/$containerId/records").get().await()
            (containerSnap.value as? Map<*, *>)?.forEach { (key, value) ->
                (value as? Map<*, *>)?.let {
                    allRecords.add(mapToRecord(key.toString(), it))
                }
            }

            // ✅ ২. অ্যাক্টিভ সেশন থেকে লাইভ রেকর্ড ফেচ
            activeExtensions.forEach { extId ->
                try {
                    val sessionSnap = db.getReference("sessions/$extId/records").get().await()
                    (sessionSnap.value as? Map<*, *>)?.forEach { (key, value) ->
                        (value as? Map<*, *>)?.let {
                            if (allRecords.none { r -> r.id == key.toString() }) {
                                allRecords.add(mapToRecord(key.toString(), it))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Session $extId fetch skipped: ${e.message}")
                }
            }

            // ✅ ৩. Newest → Oldest সর্ট (UI Priority)
            allRecords.sortByDescending { it.received_at }

            // ✅ ৪. Room-এ ইনসার্ট
            repository.insertAllCalls(allRecords)
            Log.d(TAG, "✅ Synced ${allRecords.size} records")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Sync failed: ${e.message}", e)
        }
    }

    /**
     * 🔹 হেলপার: Firebase Map → CallRecord
     * ✅ ফিক্সড: প্যারামিটার নাম 'data' সহ
     */
    private fun mapToRecord(id: String, data: Map<*, *>): CallRecord {
        return CallRecord(
            id = id,
            text = data["text"] as? String ?: "",
            cleaned = data["cleaned"] as? String ?: "",
            type = data["type"] as? String ?: "text",
            received_at = (data["received_at"] as? Long) ?: 0,
            actions = ActionsJson.fromRecordData(data)
        )
    }
}