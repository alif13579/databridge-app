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
 * claims.requester_system_id both carry a real FK (added manually via SQL Editor —
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
        val agentSystemId: String get() = raw.optString("requester_system_id")
        val type: String get() = raw.optString("type")
        val category: String get() = raw.optString("category")
        val purpose: String get() = raw.optString("remarks")
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
        val placedDate: String get() = raw.optString("requested_at").take(10) // yyyy-MM-dd derived from requested_at
        val status: String get() = raw.optString("status")

        // Embedded via the FK — PostgREST nests these as single objects (not arrays)
        // for a many-to-one embed like claims->branches / claims->users.
        private val branch: JSONObject? get() = raw.optJSONObject("branches")
        private val agent: JSONObject? get() = raw.optJSONObject("users")
        val branchName: String get() = branch?.optString("name").orEmpty()
        val branchRegion: String get() = branch?.optString("region").orEmpty()
        val branchPettyCashLimit: Double get() = branch?.optDouble("petty_cash_limit", 0.0) ?: 0.0
        val agentName: String get() = agent?.optString("name").orEmpty()
        val agentEmployeeId: String get() = agent?.optString("employee_id").orEmpty()
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
     * Fetches claims rows for [branchId] with requested_at in [fromDateIso,
     * toDateIso] (both "yyyy-MM-dd", inclusive), embedding branches(name,region,
     * petty_cash_limit) and users(name,phone,designation,employee_id) in the same request.
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
        val select = "*,branches(name,region,petty_cash_limit),users(name,phone,designation,employee_id)"
        val urlBuilder = StringBuilder("${SupabaseConfig.PROJECT_URL}/rest/v1/claims")
            .append("?select=").append(select.encodeParam())
            .append("&branch_id=eq.").append(branchId.encodeParam())
            .append("&requested_at=gte.").append(fromDateIso.encodeParam())
            .append("&requested_at=lte.").append((toDateIso + "T23:59:59Z").encodeParam())
            .append("&order=requested_at.asc")
        // PostgREST in.(...) filter — comma-separated, each value individually
        // percent-encoded. Same pattern SupabaseClientManager.fetchRemarkLabels
        // already uses for this.
        if (agentSystemIds.isNotEmpty()) {
            urlBuilder.append("&requester_system_id=in.(").append(agentSystemIds.joinToString(",") { it.encodeParam() }).append(")")
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

    private fun Long.toIsoInstant(): String = java.time.Instant.ofEpochMilli(this).toString()

    /** Inverse of SupabaseClaimsWriter's ClaimInfo.toSupabaseJson() — reconstructs
     *  a full ClaimInfo from one public.claims row. A plain select=* is enough,
     *  no embed needed: every field, including every actor name, is now a
     *  stored column (see SCHEMA_HISTORY.md) — unlike [ClaimRow] above, which
     *  stays report-only and keeps relying on the branches/users embed for
     *  just branchName/agentName. Used by [getById]/[search]/[searchMyClaims]
     *  below, which back ClaimsRepository's live get()/search()/
     *  searchMyClaims() post-cutover. */
    /** Extracts `name` from a PostgREST aliased embed object, e.g.
     *  `staff_user:staff_by_system_id(name)` → `{"staff_user":{"name":"..."}}`
     *  Returns blank when the actor hasn't acted yet (null embed = no FK match). */
    private fun JSONObject.embedName(alias: String): String =
        optJSONObject(alias)?.optString("name").orEmpty()

    private fun JSONObject.toClaimInfo(): ClaimInfo {
        fun isoMillis(key: String): Long {
            val v = optString(key, "")
            return if (v.isBlank()) 0L else runCatching { java.time.Instant.parse(v).toEpochMilli() }.getOrDefault(0L)
        }
        // Actor names are joined via PostgREST embed (see ACTOR_SELECT constant) —
        // not stored as columns. Requester name comes from the `requester` embed
        // (agent_system_id → users); branch name from the `branch` embed
        // (branch_id → branches). Actor system_ids are plain columns.
        return ClaimInfo(
            claimId = optString("id"),
            claimCode = optString("claim_code"),
            branchId = optString("branch_id"),
            branchName = embedName("branch"),
            employeeName = embedName("requester"),
            agentSystemId = optString("agent_system_id"),
            type = optString("type"),
            category = optString("category"),
            purpose = optString("purpose"),
            consignmentId = optString("consignment_id"),
            storeName = optString("store_name"),
            vehicle = optString("vehicle"),
            fromArea = optString("from_area"),
            toArea = optString("to_area"),
            attemptQuantity = optInt("attempt_quantity", 0),
            deliveredQuantity = optInt("delivered_quantity", 0),
            cidOrMerchant = optString("cid_or_merchant"),
            requestedAmount = optDouble("requested_amount", 0.0),
            approvedAmount = optDouble("approved_amount", 0.0),
            settledAmount = optDouble("settled_amount", 0.0),
            paymentMethod = optString("payment_method"),
            transactionId = optString("transaction_id"),
            status = optString("status").ifBlank { PC_STATUS_PENDING },
            requestedAt = isoMillis("requested_at"),
            approvedAt = isoMillis("approved_at"),
            settledAt = isoMillis("settled_at"),
            createdAt = isoMillis("created_at"),
            updatedAt = isoMillis("updated_at"),
            workerUid = optString("worker_uid"),
            workerRole = optString("worker_role"),
            storeId = optString("store_id"),
            pickupCount = optInt("pickup_count", 0),
            priority = optString("priority").ifBlank { PC_PRIORITY_NORMAL },
            attachmentUrl = optString("attachment_url"),
            attachmentName = optString("attachment_name"),
            staffByUid = optString("staff_by_uid"),
            staffBySystemId = optString("staff_by_system_id"),
            staffByName = embedName("staff_user"),
            staffAt = isoMillis("staff_at"), staffComment = optString("staff_comment"),
            pocApprovedByUid = optString("poc_approved_by_uid"),
            pocApprovedBySystemId = optString("poc_approved_by_system_id"),
            pocApprovedByName = embedName("poc_user"),
            pocComment = optString("poc_comment"),
            settleInProcessByUid = optString("settle_in_process_by_uid"),
            settleInProcessBySystemId = optString("settle_in_process_by_system_id"),
            settleInProcessByName = embedName("settle_user"),
            settleInProcessAt = isoMillis("settle_in_process_at"),
            settledByUid = optString("settled_by_uid"),
            settledBySystemId = optString("settled_by_system_id"),
            settledByName = embedName("settled_user"),
            rejectedByUid = optString("rejected_by_uid"),
            rejectedBySystemId = optString("rejected_by_system_id"),
            rejectedByName = embedName("rejected_user"),
            rejectedAt = isoMillis("rejected_at"), rejectReason = optString("reject_reason")
        )
    }

    /** PostgREST select that joins names for all actor system_id FKs.
     *  Each alias maps to a `users` row via FK on that column:
     *  requester=agent_system_id, staff_user=staff_by_system_id, etc.
     *  branch joins branches via branch_id FK.
     *  Used by getById/search/searchMyClaims — report methods keep their own
     *  separate select (fetchClaimsForReport) unchanged. */
    private const val ACTOR_SELECT =
        "*," +
        "branch:branch_id(name)," +
        "requester:agent_system_id(name)," +
        "staff_user:staff_by_system_id(name)," +
        "poc_user:poc_approved_by_system_id(name)," +
        "settle_user:settle_in_process_by_system_id(name)," +
        "settled_user:settled_by_system_id(name)," +
        "rejected_user:rejected_by_system_id(name)"

    /** Single claim by id, or null if it doesn't exist. Unlike the report
     *  methods above, this throws on failure (auth/network/HTTP error) rather
     *  than swallowing to a default — it backs ClaimsRepository.get(), which
     *  callers across PettyCashViewModel already wrap in their own
     *  runCatching {} and treat a thrown exception as a real failure, same
     *  contract Firebase's .await() gave them before the cutover. */
    suspend fun getById(claimId: String): ClaimInfo? = withContext(Dispatchers.IO) {
        if (claimId.isBlank()) return@withContext null
        val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/claims?select=${ACTOR_SELECT.encodeParam()}&id=eq.${claimId.encodeParam()}&limit=1"
        val response = SupabaseClientManager.httpClient.newCall(
            Request.Builder().url(url)
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get().build()
        ).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("getById claim HTTP ${it.code}: ${text.take(1_000)}")
            val arr = JSONArray(text)
            if (arr.length() == 0) null else arr.getJSONObject(0).toClaimInfo()
        }
    }

    /** Full-fidelity replacement for the old Firebase-based
     *  ClaimsRepository.search() — backs PettyCashViewModel's load() (the
     *  branch's whole request list: dashboard, My Requests, etc.), not just
     *  the Claims Report screen. Throws on failure, unlike fetchClaimsForReport
     *  above: a network hiccup here should surface as a real load error, not
     *  silently show "no requests".
     *
     *  fromMillis/toMillis filter on created_at (the claim's creation instant)
     *  — the same value the old Firebase claimId (`claim_<createdAtMillis>`)
     *  range-query encoded, so this preserves identical range semantics.
     *  branchIds (usually one, but a set) are pushed down as a single
     *  branch_id=in.(...) query instead of Firebase's old per-branch parallel
     *  fetches — one round trip instead of N. */
    suspend fun search(filter: ClaimsReportFilter): ClaimsReport = withContext(Dispatchers.IO) {
        require(filter.branchIds.isNotEmpty()) { "Select at least one branch" }
        require(filter.fromMillis in 1..filter.toMillis) { "Invalid date range" }
        val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
        val urlBuilder = StringBuilder("${SupabaseConfig.PROJECT_URL}/rest/v1/claims")
            .append("?select=").append(ACTOR_SELECT.encodeParam())
            .append("&branch_id=in.(").append(filter.branchIds.joinToString(",") { it.encodeParam() }).append(")")
            .append("&created_at=gte.").append(filter.fromMillis.toIsoInstant().encodeParam())
            .append("&created_at=lte.").append(filter.toMillis.toIsoInstant().encodeParam())
            .append("&order=created_at.").append(if (filter.newestFirst) "desc" else "asc")
        if (filter.systemIds.isNotEmpty()) {
            urlBuilder.append("&agent_system_id=in.(").append(filter.systemIds.joinToString(",") { it.encodeParam() }).append(")")
        }
        if (filter.types.isNotEmpty()) {
            urlBuilder.append("&type=in.(").append(filter.types.joinToString(",") { it.encodeParam() }).append(")")
        }
        if (filter.categories.isNotEmpty()) {
            urlBuilder.append("&category=in.(").append(filter.categories.joinToString(",") { it.encodeParam() }).append(")")
        }
        if (filter.statuses.isNotEmpty()) {
            urlBuilder.append("&status=in.(").append(filter.statuses.joinToString(",") { it.encodeParam() }).append(")")
        }
        val response = SupabaseClientManager.httpClient.newCall(
            Request.Builder().url(urlBuilder.toString())
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get().build()
        ).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("search claims HTTP ${it.code}: ${text.take(1_000)}")
            val arr = JSONArray(text)
            ClaimsReport(List(arr.length()) { i -> arr.getJSONObject(i).toClaimInfo() }, filter)
        }
    }

    /** Full-fidelity replacement for the old Firebase-based
     *  ClaimsRepository.searchMyClaims() — one system_id, no branch
     *  requirement (unlike [search]), matching what the old
     *  claims_by_systemId-only index lookup did. Throws on failure, same
     *  posture as [search] and [getById] above. */
    suspend fun searchMyClaims(systemId: String, fromMillis: Long, toMillis: Long, newestFirst: Boolean = true): ClaimsReport = withContext(Dispatchers.IO) {
        require(systemId.isNotBlank()) { "System ID is required" }
        val filter = ClaimsReportFilter(emptySet(), setOf(systemId), fromMillis, toMillis, newestFirst = newestFirst)
        val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
        val url = StringBuilder("${SupabaseConfig.PROJECT_URL}/rest/v1/claims")
            .append("?select=").append(ACTOR_SELECT.encodeParam())
            .append("&agent_system_id=eq.").append(systemId.encodeParam())
            .append("&created_at=gte.").append(fromMillis.toIsoInstant().encodeParam())
            .append("&created_at=lte.").append(toMillis.toIsoInstant().encodeParam())
            .append("&order=created_at.").append(if (newestFirst) "desc" else "asc")
            .toString()
        val response = SupabaseClientManager.httpClient.newCall(
            Request.Builder().url(url)
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get().build()
        ).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("searchMyClaims HTTP ${it.code}: ${text.take(1_000)}")
            val arr = JSONArray(text)
            ClaimsReport(List(arr.length()) { i -> arr.getJSONObject(i).toClaimInfo() }, filter)
        }
    }
}

