package com.cloudx.databridge

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Supabase-only reads for the Petty Cash flow — deposits, wallet balance,
 * branch role assignments, and the signed-in user's identity.
 *
 * Replaces the Firebase Realtime Database reads PettyCashViewModel used to do:
 *   petty_cash/{branchId}/wallet/deposits  -> [fetchDeposits]
 *   petty_cash/{branchId}/wallet/balance   -> [fetchWalletBalance]
 *   branches/{branchId}                    -> [fetchBranch]
 *   users/{uid}/profile/...                -> [fetchCurrentUser]
 *
 * Writes for these same tables go through the petty-cash Edge
 * Function's petty_cash_deposit_upsert / petty_cash_wallet_balance_upsert
 * actions (see SupabasePettyCashWriter) — the admin client there bypasses
 * RLS, so writes never depended on read policies. Reads below are direct
 * PostgREST (free, unlimited) and DO need SELECT policies — see
 * supabase/migrations/202609030001_petty_cash_supabase_reads.sql.
 *
 * Failure posture matches SupabaseClaimsReader's live-query methods
 * (getById/search/searchMyClaims): throw on failure, don't swallow to a
 * default — PettyCashViewModel.load() surfaces it as PettyCashState.Error,
 * same contract Firebase's .await() gave callers before. The one exception
 * is a branch with no wallet row yet (never received a deposit): that
 * returns 0.0, not an error — same as Firebase's getValue(Double) ?: 0.0.
 */
object SupabasePettyCashReader {

    data class CurrentUser(val name: String, val systemId: String)

    private fun String.encodeParam(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private fun isoToMillis(value: String): Long =
        SupabaseRemarkValidationWriter.parseDbTimestampMillis(value)

    private fun JSONObject.isoMillis(key: String): Long = isoToMillis(optString(key, ""))

    /** Newest-first deposits for [branchId]. Throws on auth/network/HTTP error. */
    suspend fun fetchDeposits(branchId: String): List<PettyCashDeposit> = withContext(Dispatchers.IO) {
        require(branchId.isNotBlank()) { "A branch is required" }
        val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
        // entered_by_name is NOT a real column (see SupabasePettyCashWriter's
        // doc comment) — select=* and only map columns that exist; the deposit
        // history screen recomputes running balances client-side and never
        // shows the row author, so enteredByName stays blank.
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/petty_cash_deposits" +
            "?select=*&branch_id=eq.${branchId.encodeParam()}&order=created_at.desc"
        val response = SupabaseClientManager.httpClient.newCall(
            Request.Builder().url(url)
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get().build()
        ).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("fetchDeposits HTTP ${it.code}: ${text.take(1_000)}")
            val arr = JSONArray(text)
            List(arr.length()) { i ->
                val row = arr.getJSONObject(i)
                PettyCashDeposit(
                    id = row.optString("id"),
                    amount = row.optDouble("amount", 0.0),
                    source = row.optString("source"),
                    reference = row.optString("reference"),
                    remarks = row.optString("remarks"),
                    balanceAfter = row.optDouble("balance_after", 0.0),
                    timestamp = row.isoMillis("created_at"),
                    enteredByUid = row.optString("entered_by_uid"),
                    enteredByName = ""
                )
            }.sortedByDescending { d -> d.timestamp }
        }
    }

    /** Current wallet balance for [branchId], or 0.0 when the branch has no
     *  wallet row yet (never received a deposit/settlement). Throws on
     *  auth/network/HTTP error — a missing row is not an error, a failed
     *  request is. */
    suspend fun fetchWalletBalance(branchId: String): Double = withContext(Dispatchers.IO) {
        require(branchId.isNotBlank()) { "A branch is required" }
        val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/petty_cash_wallet_balance" +
            "?select=balance&branch_id=eq.${branchId.encodeParam()}&limit=1"
        val response = SupabaseClientManager.httpClient.newCall(
            Request.Builder().url(url)
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get().build()
        ).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("fetchWalletBalance HTTP ${it.code}: ${text.take(1_000)}")
            val arr = JSONArray(text)
            if (arr.length() == 0) 0.0 else arr.getJSONObject(0).optDouble("balance", 0.0)
        }
    }

    /** Branch row for role resolution (staff/poc/accountant assignments).
     *  select=* so a Supabase column that doesn't exist yet degrades to a
     *  blank field instead of failing the whole query with HTTP 400 —
     *  created_at/updated_at arrive as ISO strings (parsed to millis) while
     *  Branch.kt models them as Long; updated_log has no Supabase
     *  counterpart and stays empty (role resolution never reads it). */
    suspend fun fetchBranch(branchId: String): Branch = withContext(Dispatchers.IO) {
        require(branchId.isNotBlank()) { "A branch is required" }
        val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/branches" +
            "?select=*&branch_id=eq.${branchId.encodeParam()}&limit=1"
        val response = SupabaseClientManager.httpClient.newCall(
            Request.Builder().url(url)
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get().build()
        ).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("fetchBranch HTTP ${it.code}: ${text.take(1_000)}")
            val arr = JSONArray(text)
            if (arr.length() == 0) error("Branch not found")
            val row = arr.getJSONObject(0)
            Branch(
                branch_id = row.optString("branch_id").ifBlank { branchId },
                branch_code = row.optString("branch_code"),
                name = row.optString("name"),
                branch_type = row.optString("branch_type"),
                address = row.optString("address"),
                latitude = row.optDouble("latitude", 0.0),
                longitude = row.optDouble("longitude", 0.0),
                email = row.optString("email"),
                phone = row.optString("phone"),
                manager_uid = row.optString("manager_uid"),
                manager_name = row.optString("manager_name"),
                accountant_uid = row.optString("accountant_uid"),
                accountant_name = row.optString("accountant_name"),
                accountant_role = row.optString("accountant_role"),
                petty_cash_poc_uid = row.optString("petty_cash_poc_uid"),
                petty_cash_poc_name = row.optString("petty_cash_poc_name"),
                staff_uid = row.optString("staff_uid"),
                staff_name = row.optString("staff_name"),
                staff_role = row.optString("staff_role"),
                parent_branch_id = row.optString("parent_branch_id"),
                status = row.optString("status").ifBlank { "active" },
                image_url = row.optString("image_url"),
                created_by = row.optString("created_by"),
                created_at = row.isoMillis("created_at"),
                updated_at = row.isoMillis("updated_at")
            )
        }
    }

    /** Signed-in user's display name + system_id from public.users (keyed by
     *  Firebase uid — the same identity the Edge Function's upsertUser keeps
     *  current on every sync_profile/write). A missing users row returns a
     *  blank systemId (callers' require() then fails with the same "system ID
     *  is missing" message the old Firebase read produced) and falls back to
     *  the FirebaseAuth display name, same as before. Throws on
     *  auth/network/HTTP error. */
    suspend fun fetchCurrentUser(): CurrentUser = withContext(Dispatchers.IO) {
        val firebaseUser = FirebaseAuth.getInstance().currentUser ?: error("Not signed in")
        val uid = firebaseUser.uid
        val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/users" +
            "?select=name,system_id&firebase_id=eq.${uid.encodeParam()}&limit=1"
        val response = SupabaseClientManager.httpClient.newCall(
            Request.Builder().url(url)
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get().build()
        ).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("fetchCurrentUser HTTP ${it.code}: ${text.take(1_000)}")
            val arr = JSONArray(text)
            if (arr.length() == 0) {
                CurrentUser(
                    name = firebaseUser.displayName.orEmpty(),
                    systemId = ""
                )
            } else {
                val row = arr.getJSONObject(0)
                CurrentUser(
                    name = row.optString("name").ifBlank { firebaseUser.displayName.orEmpty() },
                    systemId = row.optString("system_id").trim()
                )
            }
        }
    }
}
