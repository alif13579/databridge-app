package com.cloudx.databridge

import com.google.firebase.auth.FirebaseAuth
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Best-effort mirror of Petty Cash claims into Supabase's public.claims table, alongside
 * (never instead of) the existing Firebase write in [ClaimsRepository]. Firebase remains the
 * source of truth for now — public.claims was created "table structure only, ahead of the
 * actual data/write-flow migration off Firebase" (202608260001). This is that mirror, added
 * ahead of the actual cutover so the table stays populated and can be spot-checked against
 * Firebase before Firebase is ever removed.
 *
 * A Supabase failure here must never surface to the caller or affect the Firebase write that
 * already succeeded — same fire-and-forget-on-failure convention [SupabaseRemarkValidationWriter]
 * already uses for its own non-critical side effects (e.g. sendRemarkPush()).
 */
object SupabaseClaimsWriter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** [onResult] fires once the network call actually completes (true = HTTP
     *  success), on a background thread — callers that touch UI must switch
     *  threads themselves. Never fires synchronously. Added alongside the
     *  Firebase/Supabase confirmation toasts (PettyCashViewModel's write
     *  methods) — mirror() itself is unchanged otherwise. */
    fun mirror(claim: ClaimInfo, onResult: (Boolean) -> Unit = {}) {
        if (!SupabaseConfig.isConfigured) {
            FirebaseErrorLogger.log(
                "SupabaseClaimsWriter", "mirror_skip_not_configured",
                "SUPABASE_URL/SUPABASE_PUBLISHABLE_KEY are not configured",
                mapOf("claimId" to claim.claimId)
            )
            onResult(false); return
        }
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            FirebaseErrorLogger.log(
                "SupabaseClaimsWriter", "mirror_skip_not_signed_in", "No Firebase user",
                mapOf("claimId" to claim.claimId)
            )
            onResult(false); return
        }
        user.getIdToken(false).addOnCompleteListener { tokenTask ->
            val token = tokenTask.result?.token
            if (!tokenTask.isSuccessful || token.isNullOrBlank()) {
                FirebaseErrorLogger.log(
                    "SupabaseClaimsWriter", "mirror_token_error",
                    tokenTask.exception?.message ?: "No Firebase ID token",
                    mapOf("claimId" to claim.claimId)
                )
                onResult(false); return@addOnCompleteListener
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
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    FirebaseErrorLogger.log(
                        "SupabaseClaimsWriter", "mirror_network_error",
                        e.message ?: "Network error", mapOf("claimId" to claim.claimId)
                    )
                    onResult(false)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.isSuccessful) {
                            onResult(true)
                        } else {
                            val text = it.body?.string().orEmpty()
                            FirebaseErrorLogger.log(
                                "SupabaseClaimsWriter", "mirror_http_error",
                                "HTTP ${it.code}: ${text.take(500)}",
                                mapOf("claimId" to claim.claimId)
                            )
                            onResult(false)
                        }
                    }
                }
            })
        }
    }

    /** Maps ClaimInfo's camelCase fields to public.claims' snake_case columns
     *  (see SCHEMA_HISTORY.md's "public.claims — now live" entry). *Name fields
     *  (branchName, employeeName, staffByName, etc.) are deliberately NOT sent —
     *  those are joins against users/branches at read time on the Supabase side,
     *  not stored columns, so nothing is lost by omitting them here. Millis
     *  timestamps convert to ISO-8601; a 0L/not-yet-reached-that-stage timestamp
     *  (staffAt, approvedAt, etc.) is sent as null, matching the column's
     *  nullable timestamptz type.
     *
     *  placed_date IS sent (unlike the omitted columns above) — it's a NOT NULL
     *  date column (see SCHEMA_HISTORY.md), derived from requestedAt (the same
     *  "requested date" reviewers can now correct — see PettyCashRequest.
     *  requestedDate / the requested-date-correction feature) rather than a
     *  literal ClaimInfo field of that name. Falls back to today when
     *  requestedAt is 0L (not yet reached that stage) so the insert never
     *  violates the NOT NULL constraint. */
    private fun ClaimInfo.toSupabaseJson(): JSONObject {
        fun millisToIso(millis: Long): Any =
            if (millis > 0L) java.time.Instant.ofEpochMilli(millis).toString() else JSONObject.NULL
        fun millisToIsoDate(millis: Long): String {
            val instant = if (millis > 0L) java.time.Instant.ofEpochMilli(millis) else java.time.Instant.now()
            return instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        }

        return JSONObject().apply {
            put("id", claimId)
            put("claim_code", claimCode)
            put("branch_id", branchId)
            put("employee_id", employeeId)
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
            put("placed_date", millisToIsoDate(requestedAt))
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
            put("staff_at", millisToIso(staffAt))
            put("staff_comment", staffComment)
            put("poc_approved_by_uid", pocApprovedByUid)
            put("poc_comment", pocComment)
            put("settle_in_process_by_uid", settleInProcessByUid)
            put("settle_in_process_at", millisToIso(settleInProcessAt))
            put("settled_by_uid", settledByUid)
            put("rejected_by_uid", rejectedByUid)
            put("rejected_at", millisToIso(rejectedAt))
            put("reject_reason", rejectReason)
        }
    }
}
