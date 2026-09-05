package com.cloudx.databridge

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * One-way directory drain: Firebase → Supabase public.branches /
 * public.stores / public.areas.
 *
 * Petty Cash reads these directories ONLY from Supabase now, so
 * empty tables surface in-app as "Branch not found" / "No stores available"
 * / "No areas configured" and block the whole flow — including claim submit
 * (claims.branch_id is a real FK). Run from Reports → "Sync directory",
 * re-run after any Firebase directory edit. Idempotent.
 *
 * Same no-client-content posture as FirebaseClaimsMigrator.backfillUsers:
 * the app sends only the action trigger; every row comes from the Edge
 * Function's own server-side Firebase read, so a caller can only copy the
 * truthful directory, never inject rows.
 */
object FirebaseDirectorySync {

    data class SyncResult(val synced: Int, val failed: List<String>)

    private suspend fun call(action: String): SyncResult = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) error("Supabase is not configured")
        val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            ?: error("Not signed in")
        val payload = JSONObject().put("action", action)
        val request = Request.Builder()
            .url("${SupabaseConfig.PROJECT_URL}/functions/v1/directory")
            .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        SupabaseClientManager.httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("$action HTTP ${response.code}: ${text.take(300)}")
            val json = JSONObject(text)
            if (!json.optBoolean("ok")) error(json.optString("error").ifBlank { "$action failed" })
            SyncResult(
                synced = json.optInt("synced", 0),
                failed = json.optJSONArray("failed")?.let { arr ->
                    List(arr.length()) { i -> arr.optString(i) }.filter { it.isNotBlank() }
                } ?: emptyList()
            )
        }
    }

    suspend fun syncBranches(): SyncResult = call("backfill_branches")

    suspend fun syncStores(): SyncResult = call("backfill_stores")

    suspend fun syncAreas(): SyncResult = call("backfill_areas")
}
