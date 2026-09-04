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
 * Authoritative branch writes into Supabase's public.branches — the sole
 * persistence layer for the branch directory since the branch cutover
 * (BranchCreateFragment / BranchEditFragment / BranchListFragment no longer
 * write Firebase `branches/{id}` at all).
 *
 * Same contract as SupabaseClaimsWriter.save(): throws on any failure (not
 * configured, not signed in, network error, non-2xx) — callers wrap in
 * runCatching. Writes go through the directory Edge Function's
 * `branch_upsert` / `branch_delete` actions (service-role admin client,
 * bypasses RLS) so no write policy is needed. The Edge Function also gates
 * on admin/manager role server-side and keeps the assignees' Supabase
 * users.branch_ids (RLS membership) in sync.
 *
 * Firebase `users/.../branch_ids` membership writes stay app-side alongside
 * these calls (see the fragments) — Firebase profiles still feed other
 * Firebase-gated reads and the next sync_profile.
 */
object SupabaseBranchWriter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Full branch form payload. *_name fields are intentionally absent —
     * public.branches has no name columns (names resolve via users join /
     * Firebase profiles). [removedUids] are Firebase uids just unassigned
     * from every role on this branch (edit flow) so the Edge Function can
     * strip the branch from their RLS membership.
     */
    data class BranchPayload(
        val branchId: String,
        val branchCode: String,
        val name: String,
        val branchType: String,
        val address: String,
        val latitude: Double,
        val longitude: Double,
        val email: String,
        val phone: String,
        val managerUid: String,
        val accountantUid: String,
        val accountantRole: String,
        val pettyCashPocUid: String,
        val pettyCashLimit: Double = 0.0,
        val staffUid: String,
        val staffRole: String,
        val parentBranchId: String,
        val region: String = "",
        val status: String,
        val imageUrl: String,
        val removedUids: List<String> = emptyList()
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("branch_id", branchId)
            .put("branch_code", branchCode)
            .put("name", name)
            .put("branch_type", branchType)
            .put("address", address)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("email", email)
            .put("phone", phone)
            .put("manager_uid", managerUid)
            .put("accountant_uid", accountantUid)
            .put("accountant_role", accountantRole)
            .put("petty_cash_poc_uid", pettyCashPocUid)
            .put("petty_cash_limit", pettyCashLimit)
            .put("staff_uid", staffUid)
            .put("staff_role", staffRole)
            .put("parent_branch_id", parentBranchId)
            .put("region", region)
            .put("status", status)
            .put("image_url", imageUrl)
            .put("removed_uids", org.json.JSONArray(removedUids))
    }

    suspend fun save(payload: BranchPayload) {
        require(payload.branchId.isNotBlank()) { "A branch ID is required" }
        require(payload.name.isNotBlank()) { "Branch Name required" }
        require(payload.branchCode.isNotBlank()) { "Branch Code required" }
        postAction("branch_upsert", JSONObject().put("branch", payload.toJson()), payload.branchId)
    }

    /** Refuses server-side (409) when claims still reference the branch. */
    suspend fun delete(branchId: String) {
        require(branchId.isNotBlank()) { "A branch ID is required" }
        postAction("branch_delete", JSONObject().put("branch_id", branchId), branchId)
    }

    private suspend fun postAction(action: String, body: JSONObject, idForLog: String) {
        withContext(Dispatchers.IO) {
            if (!SupabaseConfig.isConfigured) {
                FirebaseErrorLogger.log(
                    "SupabaseBranchWriter", "${action}_not_configured",
                    "SUPABASE_URL/SUPABASE_PUBLISHABLE_KEY are not configured",
                    mapOf("id" to idForLog)
                )
                error("Supabase is not configured")
            }
            val user = FirebaseAuth.getInstance().currentUser ?: run {
                FirebaseErrorLogger.log(
                    "SupabaseBranchWriter", "${action}_not_signed_in", "No Firebase user",
                    mapOf("id" to idForLog)
                )
                error("No signed-in user")
            }
            val token = user.getIdToken(false).await().token ?: run {
                FirebaseErrorLogger.log(
                    "SupabaseBranchWriter", "${action}_token_error", "No Firebase ID token",
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
                        "SupabaseBranchWriter", "${action}_http_error",
                        "HTTP ${response.code}: ${text.take(500)}", mapOf("id" to idForLog)
                    )
                    // Surface the server's reason (e.g. the claims-blocked
                    // 409 or the admin/manager 403) instead of a bare code.
                    val reason = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    error(reason?.takeIf { it.isNotBlank() } ?: "Failed to save branch: HTTP ${response.code}")
                }
            }
        }
    }
}
