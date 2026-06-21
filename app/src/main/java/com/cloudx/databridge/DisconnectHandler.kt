package com.cloudx.databridge

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * 🔌 DisconnectHandler.kt
 * ✅ ডিসকানেক্ট ইভেন্টে: গেস্ট হলে ডিলিট, লগইনড হলে মাইগ্রেশন
 */
object DisconnectHandler {
    private const val TAG = "DisconnectHandler"

    suspend fun handleDisconnect(stateManager: SessionStateManager, repository: CallRepository) {
        if (!stateManager.isMigrationPending()) {
            Log.w(TAG, "⚠️ No migration pending. Skipping disconnect handler.")
            return
        }
        val extId = stateManager.getPendingExtId()!!
        val authUid = stateManager.getPendingAuthUid()!!

        try {
            MigrationEngine.migrateSessionToContainer(extId, authUid, repository)
            stateManager.updateAuth(null) // Reset flag
        } catch (e: Exception) {
            Log.e(TAG, "❌ Disconnect migration failed: ${e.message}")
            // ফিউচারে: ব্যাকগ্রাউন্ড রিট্রাই কিউতে পুশ
        }
    }
}