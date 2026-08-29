package com.cloudx.databridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads public.claims for the "Top Sheet For Petty Cash Expense" report, joined
 * against branches and users via PostgREST embedding — claims.branch_id and
 * claims.agent_system_id both carry a real FK (added manually via SQL Editor —
 * see SCHEMA_HISTORY.md's "public.claims — now live" entry), so a single
 * embedded-select query returns branch/employee details alongside each claim
 * row without a second round-trip.
 *
 * Free, unlimited PostgREST reads — same pattern as SupabaseClientManager's
 * fetchValidations()/fetchRemarkOptions(), no Edge Function invocation.
 */
object SupabaseClaimsReader {
    private const val TAG = "SupabaseClaimsReader"

    /** One claims row with its embedded branch/employee fields flattened in —
     *  callers read this instead of re-parsing the nested embed shape each time. */
    data class ClaimRow(val raw: JSONObject) {
        val id: String get() = raw.optString("id")
        val claimCode: String get() = raw.optString("claim_code")
        val branchId: String get() = raw.optString("branch_id")
        val agentSystemId: String get() = raw.optString("agent_system_id")
        val employeeId: String get() = raw.optString("employee_id")
        val type: String get() = raw.optString("type")
        val category: String get() = raw.optString("category")
        val purpose: String get() = raw.optString("purpose")
        val settledAmount: Double get() = raw.optDouble("settled_amount", 0.0)
        val vehicle: String get() = raw.optString("vehicle")
        val fromArea: String get() = raw.optString("from_area")
        val toArea: String get() = raw.optString("to_area")
        val attemptQuantity: Int get() = raw.optInt("attempt_quantity", 0)
        val deliveredQuantity: Int get() = raw.optInt("delivered_quantity", 0)
        val cidOrMerchant: String get() = raw.optString("cid_or_merchant")
        // The date the claim/expense request was placed — mandatory on every claim
        // (any category, not just conveyance), user-editable. Renamed from
        // expense_date; see SCHEMA_HISTORY.md's "public.claims — now live" entry
        // for why (it was originally conveyance-only, blank on other categories).
        val placedDate: String get() = raw.optString("placed_date") // yyyy-MM-dd
        val status: String get() = raw.optString("status")

        // Embedded via the FK — PostgREST nests these as single objects (not arrays)
        // for a many-to-one embed like claims->branches / claims->users.
        private val branch: JSONObject? get() = raw.optJSONObject("branches")
        private val agent: JSONObject? get() = raw.optJSONObject("users")
        val branchName: String get() = branch?.optString("name").orEmpty()
        val branchRegion: String get() = branch?.optString("region").orEmpty()
        val branchPettyCashLimit: Double get() = branch?.optDouble("petty_cash_limit", 0.0) ?: 0.0
        val agentName: String get() = agent?.optString("name").orEmpty()
        val agentPhone: String get() = agent?.optString("phone").orEmpty()
        val agentDesignation: String get() = agent?.optString("designation").orEmpty()
    }

    /** A branch entry for the report's branch-selector dropdown. */
    data class BranchOption(val branchId: String, val name: String)

    /**
     * Lists every branch, for the report's single-select branch dropdown — the
     * full list, so any branch can be chosen (see the discussion that settled on
     * single-select over multi-select: pick freely from the full list, but only
     * one active at a time). Free PostgREST read, no Edge Function.
     */
    suspend fun fetchBranches(): List<BranchOption> = withContext(Dispatchers.IO) {
        val token = SupabaseClientManager.getAccessToken()
        if (token == null) {
            Log.e(TAG, "fetchBranches skipped: no Firebase bearer token")
            return@withContext emptyList()
        }
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/branches?select=branch_id,name&order=name.asc"
        try {
            val response = SupabaseClientManager.httpClient.newCall(
                Request.Builder().url(url)
                    .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/json")
                    .get().build()
            ).execute()
            response.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    Log.e(TAG, "fetchBranches HTTP ${it.code}: ${text.take(1_000)}")
                    return@withContext emptyList()
                }
                val arr = JSONArray(text)
                List(arr.length()) { i ->
                    val row = arr.getJSONObject(i)
                    BranchOption(row.optString("branch_id"), row.optString("name"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchBranches failed", e)
            emptyList()
        }
    }

    /**
     * Distinct non-blank values for [column] ('category' or 'status') across
     * every claims row for [branchId] — used to populate the report's dynamic
     * Category/Status multiselect dropdowns (per the discussion that chose a
     * database-driven list over a hardcoded one, so the dropdown always
     * reflects what's actually in the data for this branch, not a stale fixed
     * list). PostgREST doesn't have a DISTINCT-only projection, so this fetches
     * every value for the column and de-duplicates client-side — fine at this
     * scale (a branch's claim volume, not a full-table scan).
     */
    private suspend fun fetchDistinctColumnValues(branchId: String, column: String): List<String> = withContext(Dispatchers.IO) {
        if (branchId.isBlank()) return@withContext emptyList()
        val token = SupabaseClientManager.getAccessToken()
        if (token == null) {
            Log.e(TAG, "fetchDistinctColumnValues($column) skipped: no Firebase bearer token")
            return@withContext emptyList()
        }
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/claims" +
            "?select=${column.encodeParam()}&branch_id=eq.${branchId.encodeParam()}"
        try {
            val response = SupabaseClientManager.httpClient.newCall(
                Request.Builder().url(url)
                    .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/json")
                    .get().build()
            ).execute()
            response.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    Log.e(TAG, "fetchDistinctColumnValues($column) HTTP ${it.code}: ${text.take(1_000)}")
                    return@withContext emptyList()
                }
                val arr = JSONArray(text)
                val values = LinkedHashSet<String>()
                for (i in 0 until arr.length()) {
                    val v = arr.getJSONObject(i).optString(column).trim()
                    if (v.isNotBlank()) values.add(v)
                }
                values.sorted()
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchDistinctColumnValues($column) failed", e)
            emptyList()
        }
    }

    suspend fun fetchDistinctCategories(branchId: String): List<String> = fetchDistinctColumnValues(branchId, "category")
    suspend fun fetchDistinctStatuses(branchId: String): List<String> = fetchDistinctColumnValues(branchId, "status")

    /**
     * Fetches claims rows for [branchId] with placed_date in [fromDateIso,
     * toDateIso] (both "yyyy-MM-dd", inclusive), embedding branches(name,region,
     * petty_cash_limit) and users(name,phone,designation) in the same request.
     *
     * [agentSystemIds]/[categories]/[statuses] are optional narrowing filters —
     * an empty list for any of them means "no filter on that dimension" (matches
     * everything), not "match nothing"; this is what the report's "All
     * Employees"/"All Categories"/"All Statuses" default states map to.
     *
     * Returns an empty list on any failure (auth, network, HTTP error) — callers
     * should treat that the same as "no claims found" for report purposes, since
     * a partial/broken report is worse than a clear "no data" state; errors are
     * logged for diagnosis but not surfaced as a distinct UI state here.
     */
    suspend fun fetchClaimsForReport(
        branchId: String,
        fromDateIso: String,
        toDateIso: String,
        agentSystemIds: List<String> = emptyList(),
        categories: List<String> = emptyList(),
        statuses: List<String> = emptyList(),
    ): List<ClaimRow> = withContext(Dispatchers.IO) {
        if (branchId.isBlank()) return@withContext emptyList()
        val token = SupabaseClientManager.getAccessToken()
        if (token == null) {
            Log.e(TAG, "fetchClaimsForReport skipped: no Firebase bearer token")
            return@withContext emptyList()
        }
        val select = "*,branches(name,region,petty_cash_limit),users(name,phone,designation)"
        val urlBuilder = StringBuilder("${SupabaseConfig.PROJECT_URL}/rest/v1/claims")
            .append("?select=").append(select.encodeParam())
            .append("&branch_id=eq.").append(branchId.encodeParam())
            .append("&placed_date=gte.").append(fromDateIso.encodeParam())
            .append("&placed_date=lte.").append(toDateIso.encodeParam())
            .append("&order=placed_date.asc")
        // PostgREST in.(...) filter — comma-separated, each value individually
        // percent-encoded. Same pattern SupabaseClientManager.fetchRemarkLabels
        // already uses for this.
        if (agentSystemIds.isNotEmpty()) {
            urlBuilder.append("&agent_system_id=in.(").append(agentSystemIds.joinToString(",") { it.encodeParam() }).append(")")
        }
        if (categories.isNotEmpty()) {
            urlBuilder.append("&category=in.(").append(categories.joinToString(",") { it.encodeParam() }).append(")")
        }
        if (statuses.isNotEmpty()) {
            urlBuilder.append("&status=in.(").append(statuses.joinToString(",") { it.encodeParam() }).append(")")
        }
        val url = urlBuilder.toString()
        try {
            val response = SupabaseClientManager.httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()
            ).execute()
            response.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    Log.e(TAG, "fetchClaimsForReport HTTP ${it.code}: ${text.take(1_000)}")
                    return@withContext emptyList()
                }
                val arr = JSONArray(text)
                List(arr.length()) { i -> ClaimRow(arr.getJSONObject(i)) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchClaimsForReport failed", e)
            emptyList()
        }
    }

    private fun String.encodeParam(): String = java.net.URLEncoder.encode(this, "UTF-8")
}

