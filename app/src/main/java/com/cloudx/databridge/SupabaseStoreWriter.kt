package com.cloudx.databridge

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Authoritative store writes into Supabase's public.stores — the sole
 * persistence layer for the store directory since the stores cutover
 * (ConfigStoresFragment no longer writes Firebase `courier/stores` at all).
 *
 * Same contract as SupabaseBranchWriter: throws on any failure, writes go
 * through the directory Edge Function's `store_upsert` / `store_delete`
 * actions (service-role admin client, admin/manager-gated server-side).
 */
object SupabaseStoreWriter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun save(store: Store) {
        require(store.storeId.isNotBlank()) { "A store ID is required" }
        require(store.name.isNotBlank()) { "Store name is required" }
        postAction(
            "store_upsert",
            JSONObject().put("store", JSONObject()
                .put("store_id", store.storeId)
                .put("name", store.name)
                .put("address", store.address)
                .put("area_id", store.areaId)
                .put("area_name", store.areaName)
                .put("phone", store.phone)
                .put("conveyance_amount", store.conveyanceAmount)),
            store.storeId
        )
    }

    suspend fun delete(storeId: String) {
        require(storeId.isNotBlank()) { "A store ID is required" }
        postAction("store_delete", JSONObject().put("store_id", storeId), storeId)
    }

    private suspend fun postAction(action: String, body: JSONObject, idForLog: String) {
        withContext(Dispatchers.IO) {
            if (!SupabaseConfig.isConfigured) {
                FirebaseErrorLogger.log(
                    "SupabaseStoreWriter", "${action}_not_configured",
                    "SUPABASE_URL/SUPABASE_PUBLISHABLE_KEY are not configured",
                    mapOf("id" to idForLog)
                )
                error("Supabase is not configured")
            }
            val user = FirebaseAuth.getInstance().currentUser ?: run {
                FirebaseErrorLogger.log(
                    "SupabaseStoreWriter", "${action}_not_signed_in", "No Firebase user",
                    mapOf("id" to idForLog)
                )
                error("No signed-in user")
            }
            val token = user.getIdToken(false).await().token ?: run {
                FirebaseErrorLogger.log(
                    "SupabaseStoreWriter", "${action}_token_error", "No Firebase ID token",
                    mapOf("id" to idForLog)
                )
                error("Could not get an ID token")
            }
            val payload = JSONObject().put("action", action)
            body.keys().forEach { key -> payload.put(key, body.get(key)) }
            val request = Request.Builder()
                .url("${SupabaseConfig.PROJECT_URL}/functions/v1/directory")
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    FirebaseErrorLogger.log(
                        "SupabaseStoreWriter", "${action}_http_error",
                        "HTTP ${response.code}: ${text.take(500)}", mapOf("id" to idForLog)
                    )
                    val reason = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    error(reason?.takeIf { it.isNotBlank() } ?: "Failed to save store: HTTP ${response.code}")
                }
            }
        }
    }
}
