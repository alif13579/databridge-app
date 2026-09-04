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
 * Authoritative writes for Petty Cash deposits + wallet balance into
 * Supabase's public.petty_cash_deposits / public.petty_cash_wallet_balance
 * tables — the sole persistence layer for these since the Full Petty Cash
 * cutover (previously a best-effort mirror alongside Firebase writes in
 * PettyCashViewModel.depositFund()/settleRequest(), with Firebase as source
 * of truth; those Firebase writes are now removed).
 *
 * Same contract as SupabaseClaimsWriter.save(): throws on any failure (not
 * configured, not signed in, network error, non-2xx). There is no Firebase
 * write underneath to fall back on; callers must treat a thrown exception
 * as "the write did not happen" — PettyCashViewModel's callers already do,
 * via their existing runCatching { ... } blocks, so no caller-side changes
 * were needed for this.
 *
 * Balance concurrency note: Firebase used a server-side transaction for the
 * read-modify-write (atomic against concurrent settlements). The Edge
 * Function's petty_cash_wallet_balance_upsert is a plain last-write-wins
 * upsert — two simultaneous settle/deposit calls can race (both read N,
 * one writes N+a, the other N-b, losing one delta). In practice settlements
 * are human-paced per branch, so this window is negligible; a future
 * petty_cash_wallet_adjust(delta) RPC action would close it properly.
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

    /** Authoritative deposit write — throws on failure (see file doc). */
    suspend fun saveDeposit(branchId: String, deposit: PettyCashDeposit) {
        postAction(
            action = "petty_cash_deposit_upsert",
            logTag = "save_deposit",
            idForLog = deposit.id,
            body = JSONObject().put("deposit", deposit.toSupabaseJson(branchId))
        )
    }

    /** Authoritative wallet-balance write — throws on failure (see file doc).
     *  Called separately from both depositFund() (+amount) and
     *  settleRequest() (-settledAmount), since either can change the balance
     *  independently and neither creates a deposit row on its own. */
    suspend fun saveWalletBalance(branchId: String, balance: Double) {
        postAction(
            action = "petty_cash_wallet_balance_upsert",
            logTag = "save_wallet_balance",
            idForLog = branchId,
            body = JSONObject().put("branch_id", branchId).put("balance", balance)
        )
    }

    private suspend fun postAction(action: String, logTag: String, idForLog: String, body: JSONObject) {
        withContext(Dispatchers.IO) {
            if (!SupabaseConfig.isConfigured) {
                FirebaseErrorLogger.log(
                    "SupabasePettyCashWriter", "${logTag}_not_configured",
                    "SUPABASE_URL/SUPABASE_PUBLISHABLE_KEY are not configured",
                    mapOf("id" to idForLog)
                )
                error("Supabase is not configured")
            }
            val user = FirebaseAuth.getInstance().currentUser ?: run {
                FirebaseErrorLogger.log(
                    "SupabasePettyCashWriter", "${logTag}_not_signed_in", "No Firebase user",
                    mapOf("id" to idForLog)
                )
                error("No signed-in user")
            }
            val token = user.getIdToken(false).await().token ?: run {
                FirebaseErrorLogger.log(
                    "SupabasePettyCashWriter", "${logTag}_token_error", "No Firebase ID token",
                    mapOf("id" to idForLog)
                )
                error("Could not get an ID token")
            }
            val payload = JSONObject().put("action", action)
            body.keys().forEach { key -> payload.put(key, body.get(key)) }
            val request = Request.Builder()
                .url("${SupabaseConfig.PROJECT_URL}/functions/v1/petty-cash")
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    FirebaseErrorLogger.log(
                        "SupabasePettyCashWriter", "${logTag}_http_error",
                        "HTTP ${response.code}: ${text.take(500)}", mapOf("id" to idForLog)
                    )
                    error("Failed to save to Supabase: HTTP ${response.code}")
                }
            }
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
