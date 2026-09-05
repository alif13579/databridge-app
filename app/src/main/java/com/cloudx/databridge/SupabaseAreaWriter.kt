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
 * Authoritative area writes into Supabase's public.areas — the branch-wise
 * area directory (Config Areas tab). Same contract as SupabaseStoreWriter:
 * throws on any failure, writes go through the directory Edge Function's
 * `area_upsert` / `area_delete` actions (service-role admin client,
 * admin/manager-gated server-side, Firebase backup mirror included).
 */
object SupabaseAreaWriter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun save(branchId: String, areaId: String, name: String, areaType: String, zone: String) {
        require(branchId.isNotBlank()) { "A branch is required" }
        require(areaId.isNotBlank()) { "An area ID is required" }
        require(name.isNotBlank()) { "Area name is required" }
        postAction(
            "area_upsert",
            JSONObject().put("area", JSONObject()
                .put("branch_id", branchId)
                .put("area_id", areaId)
                .put("name", name)
                .put("area_type", areaType.ifBlank { "both" })
                .put("zone", zone)),
            "$branchId/$areaId"
        )
    }

    suspend fun delete(branchId: String, areaId: String) {
        require(branchId.isNotBlank()) { "A branch is required" }
        require(areaId.isNotBlank()) { "An area ID is required" }
        postAction(
            "area_delete",
            JSONObject().put("branch_id", branchId).put("area_id", areaId),
            "$branchId/$areaId"
        )
    }

    private suspend fun postAction(action: String, body: JSONObject, idForLog: String) {
        withContext(Dispatchers.IO) {
            if (!SupabaseConfig.isConfigured) {
                FirebaseErrorLogger.log(
                    "SupabaseAreaWriter", "${action}_not_configured",
                    "SUPABASE_URL/SUPABASE_PUBLISHABLE_KEY are not configured",
                    mapOf("id" to idForLog)
                )
                error("Supabase is not configured")
            }
            val user = FirebaseAuth.getInstance().currentUser ?: run {
                FirebaseErrorLogger.log(
                    "SupabaseAreaWriter", "${action}_not_signed_in", "No Firebase user",
                    mapOf("id" to idForLog)
                )
                error("No signed-in user")
            }
            val token = user.getIdToken(false).await().token ?: run {
                FirebaseErrorLogger.log(
                    "SupabaseAreaWriter", "${action}_token_error", "No Firebase ID token",
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
                        "SupabaseAreaWriter", "${action}_http_error",
                        "HTTP ${response.code}: ${text.take(500)}", mapOf("id" to idForLog)
                    )
                    val reason = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    error(reason?.takeIf { it.isNotBlank() } ?: "Failed to save area: HTTP ${response.code}")
                }
            }
        }
    }
}
