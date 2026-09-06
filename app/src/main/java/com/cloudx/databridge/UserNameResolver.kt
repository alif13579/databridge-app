package com.cloudx.databridge

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * 🔹 Shared utility for resolving Firebase UIDs to display names.
 * ✅ Eliminates duplicate resolveUserName() code in WorkerSpaceFragment & CallCenterFragment
 * ✅ Thread-safe caching — same UID never fetched twice per session
 * ✅ Auto-clears on pull-to-refresh via clearCache()
 */
object UserNameResolver {

    private val nameCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val photoCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val db = FirebaseDatabase.getInstance()

    /**
     * Resolves a Firebase UID to a human-readable display name.
     * Falls back to the raw UID if the profile is missing or fetch fails.
     */
    suspend fun resolveName(uid: String): String {
        if (uid.isBlank()) return "Agent"
        nameCache[uid]?.let { return it }

        val snap = withContext(Dispatchers.IO) {
            runCatching { db.reference.child("users/$uid/profile").get().await() }.getOrNull()
        }
        val name = snap?.child("name")?.getValue(String::class.java)
            ?.trim()?.takeIf { it.isNotBlank() } ?: uid

        nameCache[uid] = name
        photoCache[uid] = snap?.child("photo_url")?.getValue(String::class.java)?.trim().orEmpty()
        return name
    }

    /**
     * Resolves a Firebase UID to a photo URL (if available).
     */
    suspend fun resolvePhotoUrl(uid: String): String {
        if (uid.isBlank()) return ""
        photoCache[uid]?.let { return it }
        resolveName(uid) // triggers cache population
        return photoCache[uid].orEmpty()
    }

    /**
     * Own validator name for sheet writes: users profile name via lookup
     * (cached per uid), Firebase displayName fallback, "CC Agent" last resort
     * — so sheets show the employee name, not the Gmail name.
     */
    suspend fun resolveOwnValidatorName(): String {
        val user = try {
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        } catch (_: Exception) { null }
        val fallback = user?.displayName?.trim().orEmpty().ifBlank { "CC Agent" }
        val uid = user?.uid.orEmpty()
        if (uid.isBlank()) return fallback
        return try {
            resolveName(uid).trim().takeIf { it.isNotBlank() && it != uid } ?: fallback
        } catch (_: Exception) { fallback }
    }

    /** Clears all caches — call on pull-to-refresh or session reset. */
    fun clearCache() {
        nameCache.clear()
        photoCache.clear()
    }

    /** Returns current cache size for debugging. */
    fun cacheSize(): Int = nameCache.size
}
