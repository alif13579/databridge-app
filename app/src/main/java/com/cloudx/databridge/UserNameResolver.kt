package com.cloudx.databridge

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray

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
     * Own validator name for sheet writes: Supabase users by firebase_id
     * (source of truth), Firebase profile fallback, Google displayName, then
     * "CC Agent" — so sheets show the employee name, not the Gmail name.
     */
    suspend fun resolveOwnValidatorName(): String {
        val user = try {
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        } catch (_: Exception) { null }
        val fallback = user?.displayName?.trim().orEmpty().ifBlank { "CC Agent" }
        val uid = user?.uid.orEmpty()
        if (uid.isBlank()) return fallback
        runCatching { queryNameByFirebaseId(uid)?.trim() }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            resolveName(uid).trim().takeIf { it.isNotBlank() && it != uid } ?: fallback
        } catch (_: Exception) { fallback }
    }

    private val sysIdNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Name lookup by system_id with Supabase public.users as the source of
     * truth (RLS branch-scoped) and Firebase as fallback (cross-branch RLS
     * gaps / legacy rows). "" when nowhere found — callers decide fallback.
     */
    suspend fun resolveNameBySystemId(systemId: String): String {
        val sid = systemId.trim()
        if (sid.isEmpty()) return ""
        sysIdNameCache[sid]?.let { return it }
        runCatching { queryUsersName(sid)?.trim() }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { sysIdNameCache[sid] = it; return it }
        runCatching { resolveNameBySystemIdFirebase(sid)?.trim() }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { sysIdNameCache[sid] = it; return it }
        return ""
    }

    private suspend fun queryUsersName(systemId: String): String? = withContext(Dispatchers.IO) {
        val token = SupabaseClientManager.getAccessToken() ?: return@withContext null
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/users" +
            "?select=name&system_id=eq.${java.net.URLEncoder.encode(systemId, "UTF-8")}&limit=1"
        val text = SupabaseClientManager.httpClient.newCall(
            okhttp3.Request.Builder().url(url)
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get().build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            resp.body?.string().orEmpty()
        }
        val arr = JSONArray(text)
        if (arr.length() == 0) return@withContext null
        arr.getJSONObject(0).optString("name").takeIf { it.isNotBlank() }
    }

    private suspend fun queryNameByFirebaseId(uid: String): String? = withContext(Dispatchers.IO) {
        val token = SupabaseClientManager.getAccessToken() ?: return@withContext null
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/users" +
            "?select=name&firebase_id=eq.${java.net.URLEncoder.encode(uid, "UTF-8")}&limit=1"
        val text = SupabaseClientManager.httpClient.newCall(
            okhttp3.Request.Builder().url(url)
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get().build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            resp.body?.string().orEmpty()
        }
        val arr = JSONArray(text)
        if (arr.length() == 0) return@withContext null
        arr.getJSONObject(0).optString("name").takeIf { it.isNotBlank() }
    }

    private suspend fun resolveNameBySystemIdFirebase(systemId: String): String? =
        withContext(Dispatchers.IO) {
            val uid = runCatching {
                db.reference.child("users_by_systemId/$systemId/uid").get().await()
                    .getValue(String::class.java)?.trim()
            }.getOrNull()
            if (uid.isNullOrBlank()) return@withContext null
            runCatching {
                db.reference.child("users/$uid/profile/name").get().await()
                    .getValue(String::class.java)?.trim()?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

    /** Clears all caches — call on pull-to-refresh or session reset. */
    fun clearCache() {
        nameCache.clear()
        photoCache.clear()
        sysIdNameCache.clear()
    }

    /** Returns current cache size for debugging. */
    fun cacheSize(): Int = nameCache.size
}
