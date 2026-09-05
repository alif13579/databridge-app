package com.cloudx.databridge

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Supabase-FIRST employee create/edit (user-sync Edge Function's user_upsert).
 *
 * The users row is authoritative here — every identity field comes from the
 * admin UI — then Firebase gets a best-effort backup mirror server-side
 * (profile + system-id index). Throws on any Supabase failure, in which case
 * the caller must not touch Firebase at all.
 *
 * Firebase-only extras (salary model, reports_to chain, branch employee
 * indexes, email) have no Supabase columns yet, so the caller still writes
 * those to Firebase directly after this succeeds.
 */
object SupabaseUserWriter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun save(
        firebaseUid: String,
        systemId: String,
        employeeId: String = "",
        name: String,
        branchIds: List<String>,
        role: String = "",
        phone: String = "",
        designation: String = "",
        status: String = "active",
        previousSystemId: String = "",
    ) {
        require(firebaseUid.isNotBlank()) { "A Firebase uid is required" }
        require(systemId.isNotBlank()) { "A system ID is required" }
        require(name.isNotBlank()) { "Employee name is required" }
        withContext(Dispatchers.IO) {
            if (!SupabaseConfig.isConfigured) {
                FirebaseErrorLogger.log(
                    "SupabaseUserWriter", "save_not_configured",
                    "SUPABASE_URL/SUPABASE_PUBLISHABLE_KEY are not configured",
                    mapOf("id" to systemId)
                )
                error("Supabase is not configured")
            }
            val user = FirebaseAuth.getInstance().currentUser ?: run {
                FirebaseErrorLogger.log(
                    "SupabaseUserWriter", "save_not_signed_in", "No Firebase user",
                    mapOf("id" to systemId)
                )
                error("No signed-in user")
            }
            val token = user.getIdToken(false).await().token ?: run {
                FirebaseErrorLogger.log(
                    "SupabaseUserWriter", "save_token_error", "No Firebase ID token",
                    mapOf("id" to systemId)
                )
                error("Could not get an ID token")
            }
            val payload = JSONObject()
                .put("action", "user_upsert")
                .put("user", JSONObject()
                    .put("firebase_uid", firebaseUid)
                    .put("system_id", systemId)
                    .put("employee_id", employeeId)
                    .put("name", name)
                    .put("branch_ids", JSONArray(branchIds))
                    .put("role", role)
                    .put("phone", phone)
                    .put("designation", designation)
                    .put("status", status)
                    .put("previous_system_id", previousSystemId))
            val request = Request.Builder()
                .url("${SupabaseConfig.PROJECT_URL}/functions/v1/user-sync")
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    FirebaseErrorLogger.log(
                        "SupabaseUserWriter", "save_http_error",
                        "HTTP ${response.code}: ${text.take(500)}", mapOf("id" to systemId)
                    )
                    val reason = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    error(reason?.takeIf { it.isNotBlank() } ?: "Failed to save user: HTTP ${response.code}")
                }
            }
        }
    }
}
