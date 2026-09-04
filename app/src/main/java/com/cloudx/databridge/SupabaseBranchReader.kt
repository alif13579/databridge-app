package com.cloudx.databridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray

/**
 * Supabase-only reads for the branch directory (public.branches) — backs
 * BranchListFragment / BranchDetailFragment / BranchEditFragment and the
 * parent-branch pickers since the branch cutover off Firebase
 * `branches/{id}`.
 *
 * Free, unlimited PostgREST reads (branches_read_all is a public
 * using(true) policy for anon+authenticated). Throws on auth/network/HTTP
 * error — screens wrap calls in runCatching and show their own error state.
 *
 * public.branches has NO name columns (manager_name etc. were Firebase-only
 * denormalized copies) — callers resolve holder names from Firebase user
 * profiles by uid, same targeted per-uid pattern BranchListFragment already
 * used for missing names.
 */
object SupabaseBranchReader {

    data class BranchRow(
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
        val pettyCashLimit: Double,
        val staffUid: String,
        val staffRole: String,
        val parentBranchId: String,
        val region: String,
        val status: String,
        val imageUrl: String,
        val createdAt: Long
    )

    private const val SELECT = "branch_id,branch_code,name,branch_type,address,latitude,longitude," +
        "email,phone,manager_uid,accountant_uid,accountant_role,petty_cash_poc_uid,petty_cash_limit," +
        "staff_uid,staff_role,parent_branch_id,region,status,image_url,created_at"

    private fun String.encodeParam(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private fun org.json.JSONObject.toBranchRow(): BranchRow {
        fun isoMillis(key: String): Long {
            val v = optString(key, "")
            return if (v.isBlank()) 0L
            else runCatching { java.time.Instant.parse(v).toEpochMilli() }.getOrDefault(0L)
        }
        return BranchRow(
            branchId = optString("branch_id"),
            branchCode = optString("branch_code"),
            name = optString("name"),
            branchType = optString("branch_type"),
            address = optString("address"),
            latitude = optDouble("latitude", 0.0),
            longitude = optDouble("longitude", 0.0),
            email = optString("email"),
            phone = optString("phone"),
            managerUid = optString("manager_uid"),
            accountantUid = optString("accountant_uid"),
            accountantRole = optString("accountant_role"),
            pettyCashPocUid = optString("petty_cash_poc_uid"),
            pettyCashLimit = optDouble("petty_cash_limit", 0.0),
            staffUid = optString("staff_uid"),
            staffRole = optString("staff_role"),
            parentBranchId = optString("parent_branch_id"),
            region = optString("region"),
            status = optString("status").ifBlank { "active" },
            imageUrl = optString("image_url"),
            createdAt = isoMillis("created_at")
        )
    }

    suspend fun listBranches(): List<BranchRow> = withContext(Dispatchers.IO) {
        val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/branches" +
            "?select=${SELECT.encodeParam()}&order=name.asc"
        val response = SupabaseClientManager.httpClient.newCall(
            Request.Builder().url(url)
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get().build()
        ).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("listBranches HTTP ${it.code}: ${text.take(1_000)}")
            val arr = JSONArray(text)
            List(arr.length()) { i -> arr.getJSONObject(i).toBranchRow() }
        }
    }

    suspend fun getBranch(branchId: String): BranchRow = withContext(Dispatchers.IO) {
        require(branchId.isNotBlank()) { "A branch ID is required" }
        val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/branches" +
            "?select=${SELECT.encodeParam()}&branch_id=eq.${branchId.encodeParam()}&limit=1"
        val response = SupabaseClientManager.httpClient.newCall(
            Request.Builder().url(url)
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get().build()
        ).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("getBranch HTTP ${it.code}: ${text.take(1_000)}")
            val arr = JSONArray(text)
            if (arr.length() == 0) error("Branch not found")
            arr.getJSONObject(0).toBranchRow()
        }
    }
}
