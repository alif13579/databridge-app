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
 * Best-effort mirror of Petty Cash deposits + wallet balance into Supabase's
 * public.petty_cash_deposits / public.petty_cash_wallet_balance tables,
 * alongside (never instead of) the existing Firebase writes in
 * [PettyCashViewModel.depositFund] / [PettyCashViewModel.settleRequest].
 * Same posture as [SupabaseClaimsWriter] right above these two tables in
 * SCHEMA_HISTORY.md: created "table structure only" (202608260001), Firebase
 * remains the source of truth, a Supabase failure here must never surface to
 * the caller or affect a Firebase write that already succeeded.
 *
 * Column names verified 2026-08-30 against a live information_schema.columns
 * dump of both tables (this file's first version guessed at them, following
 * claim_upsert's naming convention, since there was no existing Edge Function
 * action to confirm against the way claims/validations/users had) — see
 * toSupabaseJson()'s doc comment for the two things that dump caught.
 */
object SupabasePettyCashWriter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** [onResult] fires once the network call actually completes (true = HTTP
     *  success), on a background thread — callers that touch UI must switch
     *  threads themselves (see PettyCashSettlementDetailsFragment.runAction's
     *  activity?.runOnUiThread { if (isAdded) ... } guard for the pattern
     *  this project already uses). Never fires synchronously. */
    fun mirrorDeposit(branchId: String, deposit: PettyCashDeposit, onResult: (Boolean) -> Unit = {}) {
        postAction(
            action = "petty_cash_deposit_upsert",
            logTag = "mirror_deposit",
            idForLog = deposit.id,
            body = JSONObject().put("deposit", deposit.toSupabaseJson(branchId)),
            onResult = onResult
        )
    }

    /** Same one-action-per-Firebase-write posture as mirrorDeposit — called
     *  separately from both depositFund() (+amount) and settleRequest()
     *  (-settledAmount), since either can change the balance independently
     *  and neither creates a deposit row on its own. */
    fun mirrorWalletBalance(branchId: String, balance: Double, onResult: (Boolean) -> Unit = {}) {
        postAction(
            action = "petty_cash_wallet_balance_upsert",
            logTag = "mirror_wallet_balance",
            idForLog = branchId,
            body = JSONObject().put("branch_id", branchId).put("balance", balance),
            onResult = onResult
        )
    }

    private fun postAction(action: String, logTag: String, idForLog: String, body: JSONObject, onResult: (Boolean) -> Unit) {
        if (!SupabaseConfig.isConfigured) {
            FirebaseErrorLogger.log(
                "SupabasePettyCashWriter", "${logTag}_skip_not_configured",
                "SUPABASE_URL/SUPABASE_PUBLISHABLE_KEY are not configured",
                mapOf("id" to idForLog)
            )
            onResult(false); return
        }
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            FirebaseErrorLogger.log(
                "SupabasePettyCashWriter", "${logTag}_skip_not_signed_in", "No Firebase user",
                mapOf("id" to idForLog)
            )
            onResult(false); return
        }
        user.getIdToken(false).addOnCompleteListener { tokenTask ->
            val token = tokenTask.result?.token
            if (!tokenTask.isSuccessful || token.isNullOrBlank()) {
                FirebaseErrorLogger.log(
                    "SupabasePettyCashWriter", "${logTag}_token_error",
                    tokenTask.exception?.message ?: "No Firebase ID token",
                    mapOf("id" to idForLog)
                )
                onResult(false); return@addOnCompleteListener
            }
            val payload = JSONObject().put("action", action)
            body.keys().forEach { key -> payload.put(key, body.get(key)) }
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
                        "SupabasePettyCashWriter", "${logTag}_network_error",
                        e.message ?: "Network error", mapOf("id" to idForLog)
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
                                "SupabasePettyCashWriter", "${logTag}_http_error",
                                "HTTP ${it.code}: ${text.take(500)}", mapOf("id" to idForLog)
                            )
                            onResult(false)
                        }
                    }
                }
            })
        }
    }

    /** Maps PettyCashDeposit's camelCase fields to petty_cash_deposits' columns —
     *  verified 2026-08-30 against a live information_schema.columns dump (see
     *  git history for this file's earlier, unverified version). Two things
     *  that dump caught: entered_by_name isn't a real column (dropped below,
     *  do not re-add without also adding the column), and id is `uuid not null
     *  default gen_random_uuid()` — NOT text — so Firebase's push-id string
     *  (e.g. "-NxAbC...") can't be sent as-is, it'd fail Postgres' uuid type
     *  coercion. Converting it via UUID.nameUUIDFromBytes() (MD5-based, so the
     *  same Firebase id always maps to the same UUID) keeps upsert-on-id
     *  idempotent across retries without needing a schema change for a raw
     *  firebase-id column. branchId isn't a PettyCashDeposit field (it's a
     *  parent key in the Firebase path, FirebasePaths.pettyCashDeposits(branchId))
     *  so it's threaded through as a separate parameter here rather than
     *  expected on the model. */
    private fun PettyCashDeposit.toSupabaseJson(branchId: String): JSONObject =
        JSONObject().apply {
            put("id", java.util.UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8)).toString())
            put("branch_id", branchId)
            put("amount", amount)
            put("source", source)
            put("reference", reference)
            put("remarks", remarks)
            put("balance_after", balanceAfter)
            put("entered_by_uid", enteredByUid)
            put("created_at", if (timestamp > 0L) java.time.Instant.ofEpochMilli(timestamp).toString() else JSONObject.NULL)
        }
}
