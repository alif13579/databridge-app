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
 * Authoritative write for claims into Supabase's public.claims table — the
 * sole persistence layer for claims as of the Supabase-only cutover.
 *
 * Until this cutover, [save] below was mirror(): a best-effort, fire-and-forget
 * copy written alongside (never instead of) the Firebase write in
 * [ClaimsRepository], with Firebase as source of truth. That Firebase write is
 * now commented out (not deleted) in ClaimsRepository rather than the source
 * of truth, and this is the only place a claim actually gets persisted — so
 * unlike the old mirror(), [save] throws on any failure (not configured, not
 * signed in, network error, non-2xx). There is no longer a Firebase write
 * underneath to fall back on; callers must treat a thrown exception as "the
 * claim was not saved" — ClaimsRepository's callers already do, via their
 * existing runCatching { ... } blocks (see PettyCashViewModel.kt), so no
 * caller-side changes were needed for this.
 */
object SupabaseClaimsWriter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun save(claim: ClaimInfo) {
        withContext(Dispatchers.IO) {
            if (!SupabaseConfig.isConfigured) {
                FirebaseErrorLogger.log(
                    "SupabaseClaimsWriter", "save_not_configured",
                    "SUPABASE_URL/SUPABASE_PUBLISHABLE_KEY are not configured",
                    mapOf("claimId" to claim.claimId)
                )
                error("Supabase is not configured")
            }
            val user = FirebaseAuth.getInstance().currentUser ?: run {
                FirebaseErrorLogger.log(
                    "SupabaseClaimsWriter", "save_not_signed_in", "No Firebase user",
                    mapOf("claimId" to claim.claimId)
                )
                error("No signed-in user")
            }
            val token = user.getIdToken(false).await().token ?: run {
                FirebaseErrorLogger.log(
                    "SupabaseClaimsWriter", "save_token_error", "No Firebase ID token",
                    mapOf("claimId" to claim.claimId)
                )
                error("Could not get an ID token")
            }
            val payload = JSONObject()
                .put("action", "claim_upsert")
                .put("claim", claim.toSupabaseJson())
            val request = Request.Builder()
                .url("${SupabaseConfig.PROJECT_URL}/functions/v1/remark-validations")
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    FirebaseErrorLogger.log(
                        "SupabaseClaimsWriter", "save_http_error",
                        "HTTP ${response.code}: ${text.take(500)}",
                        mapOf("claimId" to claim.claimId)
                    )
                    error("Failed to save claim to Supabase: HTTP ${response.code}")
                }
            }
        }
    }

    /** Maps ClaimInfo's camelCase fields to public.claims' snake_case columns
     *  (see SCHEMA_HISTORY.md's "public.claims" entry). Millis timestamps
     *  convert to ISO-8601; a 0L/not-yet-reached-that-stage timestamp
     *  (staffAt, approvedAt, etc.) is sent as null, matching the column's
     *  nullable timestamptz type.
     *
     *  Name fields (branchName, employeeName, staffByName, pocApprovedByName,
     *  settleInProcessByName, settledByName, rejectedByName) ARE sent as of
     *  the Supabase-only cutover — added as real columns for this (see
     *  SCHEMA_HISTORY.md), since staff_by_uid/poc_approved_by_uid/
     *  settle_in_process_by_uid/settled_by_uid/rejected_by_uid have no FK to
     *  join through for a name, unlike branch_id/agent_system_id. Before the
     *  cutover these were deliberately omitted (Firebase was source of truth,
     *  this was only a best-effort mirror; the report screen reconstructed
     *  branch/agent name via a branches/users join instead) — now that
     *  Supabase is the sole persistence layer, every actor name needs to be
     *  directly on the row for ClaimsRepository's get()/search()/
     *  searchMyClaims() to reconstruct a full ClaimInfo. */
    private fun ClaimInfo.toSupabaseJson(): JSONObject {
        fun millisToIso(millis: Long): Any =
            if (millis > 0L) java.time.Instant.ofEpochMilli(millis).toString() else JSONObject.NULL

        return JSONObject().apply {
            put("id", claimId)
            put("claim_code", claimCode)
            put("branch_id", branchId)
            // branch_name and employee_name are not stored — joined at read
            // time via branch_id FK (→ branches) and agent_system_id FK (→ users).
            put("agent_system_id", agentSystemId)
            put("type", type)
            put("category", category)
            put("purpose", purpose)
            put("consignment_id", consignmentId)
            put("store_id", storeId)
            put("store_name", storeName)
            put("vehicle", vehicle)
            put("from_area", fromArea)
            put("to_area", toArea)
            put("attempt_quantity", attemptQuantity)
            put("delivered_quantity", deliveredQuantity)
            put("cid_or_merchant", cidOrMerchant)
            put("pickup_count", pickupCount)
            put("requested_amount", requestedAmount)
            put("approved_amount", approvedAmount)
            put("settled_amount", settledAmount)
            put("payment_method", paymentMethod)
            put("transaction_id", transactionId)
            put("status", status)
            put("priority", priority)
            put("attachment_url", attachmentUrl)
            put("attachment_name", attachmentName)
            put("worker_uid", workerUid)
            put("worker_role", workerRole)
            put("requested_at", millisToIso(requestedAt))
            put("approved_at", millisToIso(approvedAt))
            put("settled_at", millisToIso(settledAt))
            put("created_at", millisToIso(createdAt))
            put("updated_at", millisToIso(updatedAt))
            put("staff_by_uid", staffByUid)
            put("staff_by_system_id", staffBySystemId)
            put("staff_at", millisToIso(staffAt))
            put("staff_comment", staffComment)
            put("poc_approved_by_uid", pocApprovedByUid)
            put("poc_approved_by_system_id", pocApprovedBySystemId)
            put("poc_comment", pocComment)
            put("settle_in_process_by_uid", settleInProcessByUid)
            put("settle_in_process_by_system_id", settleInProcessBySystemId)
            put("settle_in_process_at", millisToIso(settleInProcessAt))
            put("settled_by_uid", settledByUid)
            put("settled_by_system_id", settledBySystemId)
            put("rejected_by_uid", rejectedByUid)
            put("rejected_by_system_id", rejectedBySystemId)
            put("rejected_at", millisToIso(rejectedAt))
            put("reject_reason", rejectReason)
        }
    }
}
