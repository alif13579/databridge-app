package com.cloudx.databridge

import com.google.firebase.auth.FirebaseAuth
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Mirrors ClaimInfo into Supabase's public.claims table as an alternative store,
 * alongside Firebase (which stays the single source of truth). Called by
 * ClaimsRepository.create()/update() only after the Firebase write already
 * succeeded — this is a best-effort copy, never a gate on the Firebase
 * operation: sync() never throws and every failure just logs, same posture as
 * SupabaseRemarkValidationWriter for the validations table.
 *
 * Covers claims only for now — petty_cash_deposits/petty_cash_wallet_balance/
 * branches (see 202608260001_create_petty_cash_claims_tables.sql) are not
 * wired up yet.
 */
object SupabaseClaimsWriter {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val jsonMediaType = "application/json".toMediaType()

    /** Fire-and-forget: mirrors [claim]'s current full state to Supabase. Safe to call
     *  after both a brand-new claim and any status-transition update — the Edge
     *  Function upserts on claim id either way.
     *  [onResult] fires once the network call actually completes (true = HTTP success),
     *  on a background thread — callers that touch UI must switch threads themselves.
     *  Never fires synchronously, so it always lands after the caller's own return. */
    fun sync(claim: ClaimInfo, onResult: (Boolean) -> Unit = {}) {
        if (claim.claimId.isBlank() || claim.branchId.isBlank() || claim.agentSystemId.isBlank()) {
            log("claims_sync_skip_missing_ids", "Missing claimId/branchId/agentSystemId", claim.claimId)
            onResult(false); return
        }
        val payload = JSONObject().put("action", "upsert_claim").put("claim", JSONObject().apply {
            put("claimId", claim.claimId); put("claimCode", claim.claimCode)
            put("branchId", claim.branchId); put("employeeId", claim.employeeId)
            put("agentSystemId", claim.agentSystemId)
            put("type", claim.type); put("category", claim.category); put("purpose", claim.purpose)
            put("consignmentId", claim.consignmentId)
            put("storeId", claim.storeId); put("storeName", claim.storeName)
            put("pickupCount", claim.pickupCount)
            put("requestedAmount", claim.requestedAmount)
            put("approvedAmount", claim.approvedAmount)
            put("settledAmount", claim.settledAmount)
            put("paymentMethod", claim.paymentMethod); put("transactionId", claim.transactionId)
            put("status", claim.status); put("priority", claim.priority)
            put("attachmentUrl", claim.attachmentUrl); put("attachmentName", claim.attachmentName)
            put("workerUid", claim.workerUid); put("workerRole", claim.workerRole)
            put("requestedAt", claim.requestedAt); put("approvedAt", claim.approvedAt)
            put("settledAt", claim.settledAt)
            put("createdAt", claim.createdAt); put("updatedAt", claim.updatedAt)
            put("staffByUid", claim.staffByUid); put("staffAt", claim.staffAt)
            put("staffComment", claim.staffComment)
            put("pocApprovedByUid", claim.pocApprovedByUid); put("pocComment", claim.pocComment)
            put("settleInProcessByUid", claim.settleInProcessByUid)
            put("settleInProcessAt", claim.settleInProcessAt)
            put("settledByUid", claim.settledByUid)
            put("rejectedByUid", claim.rejectedByUid); put("rejectedAt", claim.rejectedAt)
            put("rejectReason", claim.rejectReason)
        })
        invoke(payload, claim.claimId, onResult)
    }

    private fun invoke(payload: JSONObject, claimId: String, onResult: (Boolean) -> Unit) {
        if (!SupabaseConfig.isConfigured) {
            log("claims_sync_skip_not_configured", "SUPABASE_URL/SUPABASE_PUBLISHABLE_KEY are not configured", claimId)
            onResult(false); return
        }
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) { log("claims_sync_skip_not_signed_in", "No Firebase user", claimId); onResult(false); return }
        user.getIdToken(false).addOnCompleteListener { tokenTask ->
            val token = tokenTask.result?.token
            if (!tokenTask.isSuccessful || token.isNullOrBlank()) {
                log("claims_sync_token_error", tokenTask.exception?.message ?: "No Firebase ID token", claimId)
                onResult(false); return@addOnCompleteListener
            }
            val request = Request.Builder().url("${SupabaseConfig.PROJECT_URL}/functions/v1/claims-sync")
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY).addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json").post(payload.toString().toRequestBody(jsonMediaType)).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    log("claims_sync_network_error", e.message ?: "Network error", claimId)
                    onResult(false)
                }
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use {
                        if (it.isSuccessful) {
                            onResult(true)
                        } else {
                            log("claims_sync_http_error", "HTTP ${it.code}: ${it.body?.string()?.take(500)}", claimId)
                            onResult(false)
                        }
                    }
                }
            })
        }
    }

    private fun log(action: String, error: String, claimId: String) = FirebaseErrorLogger.log(
        screen = "ClaimsRepository", action = action, errorMessage = error,
        extra = if (claimId.isBlank()) emptyMap() else mapOf("claimId" to claimId)
    )
}
