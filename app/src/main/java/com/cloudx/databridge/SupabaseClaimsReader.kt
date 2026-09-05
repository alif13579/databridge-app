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
        val id: String get() = raw.optStr("id")
        val claimCode: String get() = raw.optStr("claim_code")
        val branchId: String get() = raw.optStr("branch_id")
        val agentSystemId: String get() = raw.optStr("requester_system_id")
        val category: String get() = raw.optStr("category")
        val purpose: String get() = raw.optStr("purpose")
        val settledAmount: Double get() = raw.optDouble("settled_amount", 0.0)
        val vehicle: String get() = raw.optStr("vehicle")
        val fromArea: String get() = raw.optStr("from_area")
        val toArea: String get() = raw.optStr("to_area")
        val attemptQuantity: Int get() = raw.optInt("attempted_qty", 0)
        val deliveredQuantity: Int get() = raw.optInt("succeeded_qty", 0)
        val cidOrMerchant: String get() = raw.optStr("cid_or_merchant")
        // The date the claim/expense request was placed — mandatory on every claim
        // (any category, not just conveyance), user-editable. Renamed from
        // expense_date; see SCHEMA_HISTORY.md's "public.claims — now live" entry
        // for why (it was originally conveyance-only, blank on other categories).
        val placedDate: String get() = raw.optStr("requested_at").take(10) // yyyy-MM-dd derived from requested_at
        val status: String get() = raw.optStr("status")

        // Embedded via the FK — PostgREST nests these as single objects (not arrays)
        // for a many-to-one embed like claims->branches / claims->users.
        private val branch: JSONObject? get() = raw.optJSONObject("branches")
        private val agent: JSONObject? get() = raw.optJSONObject("users")
        val branchName: String get() = branch?.optStr("name").orEmpty()
        val branchRegion: String get() = branch?.optStr("region").orEmpty()
        val branchPettyCashLimit: Double get() = branch?.optDouble("petty_cash_limit", 0.0) ?: 0.0
        val agentName: String get() = agent?.optStr("name").orEmpty()
        val agentEmployeeId: String get() = agent?.optStr("employee_id").orEmpty()
        val agentPhone: String get() = agent?.optStr("phone").orEmpty()
        val agentDesignation: String get() = agent?.optStr("designation").orEmpty()
    }

    /** A branch entry for the report's branch-selector dropdown. */
    data class BranchOption(val branchId: String, val name: String)

    /** One admin-managed claim category (see
     *  supabase/migrations/202609030002_claim_categories.sql). [group] is one
     *  of conveyance / operation / office / utilities and drives the Top
     *  Sheet report grouping + the request form's field layout. */
    data class ClaimCategory(val name: String, val group: String, val sortOrder: Int)

    /** The branch's real Petty Cash POC, resolved via
     *  branches.petty_cash_poc_uid (a Firebase uid) → public.users
     *  (firebase_id). Null when the branch has no POC assigned or the users
     *  row is missing — callers fall back to their previous stand-in. */
    data class PocInfo(val name: String, val employeeId: String, val designation: String, val phone: String)

    /**
     * Lists active claim categories for the request form's picker, in admin
     * order. Empty on any failure — callers fall back to the built-in
     * Pickup/Bulk Delivery pair so the form never breaks offline. Free
     * PostgREST read, no Edge Function.
     */
    suspend fun fetchClaimCategories(): List<ClaimCategory> = withContext(Dispatchers.IO) {
        val token = SupabaseClientManager.getAccessToken()
        if (token == null) {
            Log.e(TAG, "fetchClaimCategories skipped: no Firebase bearer token")
            return@withContext emptyList()
        }
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/claim_categories" +
            "?select=name,category_group,sort_order&is_active=eq.true&order=sort_order.asc"
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
                    Log.e(TAG, "fetchClaimCategories HTTP ${it.code}: ${text.take(1_000)}")
                    return@withContext emptyList()
                }
                val arr = JSONArray(text)
                List(arr.length()) { i ->
                    val row = arr.getJSONObject(i)
                    ClaimCategory(
                        name = row.optStr("name"),
                        group = row.optStr("category_group").ifBlank { "operation" },
                        sortOrder = row.optInt("sort_order", 0)
                    )
                }.filter { it.name.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchClaimCategories failed", e)
            emptyList()
        }
    }

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
                    BranchOption(row.optStr("branch_id"), row.optStr("name"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchBranches failed", e)
            emptyList()
        }
    }

    /**
     * The branch's real Petty Cash POC for the report header — resolved via
     * the branch row's petty_cash_poc_uid (a Firebase uid) against
     * public.users (firebase_id). Null when the branch has no POC assigned
     * or the users row is missing; callers keep their previous fallback.
     * Throws on auth/network/HTTP error (a failed request is not the same
     * as "no POC assigned").
     */
    suspend fun fetchPocForBranch(branchId: String): PocInfo? = withContext(Dispatchers.IO) {
        if (branchId.isBlank()) return@withContext null
        val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
        val branchUrl = "${SupabaseConfig.PROJECT_URL}/rest/v1/branches" +
            "?select=petty_cash_poc_uid&branch_id=eq.${branchId.encodeParam()}&limit=1"
        val pocUid = SupabaseClientManager.httpClient.newCall(
            Request.Builder().url(branchUrl)
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get().build()
        ).execute().use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("fetchPocForBranch (branch) HTTP ${it.code}: ${text.take(1_000)}")
            val arr = JSONArray(text)
            if (arr.length() == 0) error("Branch not found")
            arr.getJSONObject(0).optStr("petty_cash_poc_uid").trim()
        }
        if (pocUid.isBlank()) return@withContext null
        val userUrl = "${SupabaseConfig.PROJECT_URL}/rest/v1/users" +
            "?select=name,employee_id,designation,phone&firebase_id=eq.${pocUid.encodeParam()}&limit=1"
        SupabaseClientManager.httpClient.newCall(
            Request.Builder().url(userUrl)
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get().build()
        ).execute().use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("fetchPocForBranch (user) HTTP ${it.code}: ${text.take(1_000)}")
            val arr = JSONArray(text)
            if (arr.length() == 0) return@withContext null
            val row = arr.getJSONObject(0)
            PocInfo(
                name = row.optStr("name"),
                employeeId = row.optStr("employee_id"),
                designation = row.optStr("designation"),
                phone = row.optStr("phone")
            )
        }
    }

    suspend fun fetchStores(): List<Store> = withContext(Dispatchers.IO) {        val token = SupabaseClientManager.getAccessToken()
        if (token == null) {
            Log.e(TAG, "fetchStores skipped: no Firebase bearer token")
            return@withContext emptyList()
        }
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/stores?select=store_id,name,address,area_id,area_name,phone,conveyance_amount&order=name.asc"
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
                    Log.e(TAG, "fetchStores HTTP ${it.code}: ${text.take(1_000)}")
                    return@withContext emptyList()
                }
                val arr = JSONArray(text)
                List(arr.length()) { i ->
                    val row = arr.getJSONObject(i)
                    Store(
                        id = row.optStr("store_id"),
                        storeId = row.optStr("store_id"),
                        name = row.optStr("name"),
                        address = row.optStr("address"),
                        areaId = row.optStr("area_id"),
                        areaName = row.optStr("area_name"),
                        phone = row.optStr("phone"),
                        conveyanceAmount = row.optDouble("conveyance_amount", 0.0)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchStores failed", e)
            emptyList()
        }
    }

    /**
     * Branch-wise area directory (public.areas) for claim From/To pickers,
     * store form area pickers, and the Config Areas tab. Empty [branchIds] =
     * all branches; empty [usages] = both pickup+delivery. Usage matching is
     * client-side (a 'both' row serves either picker) — see Area.matchesUsage.
     */
    suspend fun fetchAreas(
        branchIds: List<String> = emptyList(),
        usages: List<String> = emptyList(),
    ): List<Area> = withContext(Dispatchers.IO) {
        val token = SupabaseClientManager.getAccessToken()
        if (token == null) {
            Log.e(TAG, "fetchAreas skipped: no Firebase bearer token")
            return@withContext emptyList()
        }
        val url = buildString {
            append("${SupabaseConfig.PROJECT_URL}/rest/v1/areas")
            append("?select=branch_id,area_id,name,area_type,zone&order=branch_id.asc,name.asc")
            if (branchIds.isNotEmpty()) {
                append("&branch_id=in.(")
                append(branchIds.joinToString(",") { it.encodeParam() })
                append(")")
            }
        }
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
                    Log.e(TAG, "fetchAreas HTTP ${it.code}: ${text.take(1_000)}")
                    return@withContext emptyList()
                }
                val arr = JSONArray(text)
                List(arr.length()) { i ->
                    val row = arr.getJSONObject(i)
                    val type = row.optStr("area_type").ifBlank { "both" }
                    Area(
                        id = row.optStr("branch_id") + "::" + row.optStr("area_id"),
                        areaId = row.optStr("area_id"),
                        name = row.optStr("name"),
                        branchId = row.optStr("branch_id"),
                        areaType = type,
                        zone = row.optStr("zone"),
                    )
                }.filter { a ->
                    a.areaId.isNotBlank() && a.name.isNotBlank() &&
                        (usages.isEmpty() || usages.any { a.matchesUsage(it) })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchAreas failed", e)
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
                    val v = arr.getJSONObject(i).optStr(column).trim()
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
        val select = "*,branches(name,region,petty_cash_limit)," +
            // users(...) alone is ambiguous — claims has 6 FKs into users
            // (requester/staff/poc/settle/settled/rejected), so PostgREST
            // answers HTTP 300 PGRST201. The alias:fk_column form (same as
            // ACTOR_SELECT below) pins the requester FK while keeping the
            // "users" response key ClaimRow parses.
            "users:requester_system_id(name,phone,designation,employee_id)"
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
     *  `staff_user:verified_by_system_id(name)` → `{"staff_user":{"name":"..."}}`
     *  Returns blank when the actor hasn't acted yet (null embed = no FK match). */
    private fun JSONObject.embedName(alias: String): String =
        optJSONObject(alias)?.optStr("name").orEmpty()

    private fun JSONObject.toClaimInfo(): ClaimInfo {
        fun isoMillis(key: String): Long {
            // Central tolerant parser — see optStr below on NULL handling.
            return SupabaseRemarkValidationWriter.parseDbTimestampMillis(optStr(key))
        }
        // Actor names are joined via PostgREST embed (see ACTOR_SELECT constant) —
        // not stored as columns. Requester name comes from the `requester` embed
        // (agent_system_id → users); branch name from the `branch` embed
        // (branch_id → branches). Actor system_ids are plain columns.
        return ClaimInfo(
            claimId = optStr("id"),
            claimCode = optStr("claim_code"),
            branchId = optStr("branch_id"),
            employeeName = embedName("requester"),
            agentSystemId = optStr("requester_system_id"),
            category = optStr("category"),
            purpose = optStr("purpose"),
            consignmentId = optStr("consignment_id"),
            vehicle = optStr("vehicle"),
            fromArea = optStr("from_area"),
            toArea = optStr("to_area"),
            attemptQuantity = optInt("attempted_qty", 0),
            deliveredQuantity = optInt("succeeded_qty", 0),
            cidOrMerchant = optStr("cid_or_merchant"),
            requestedAmount = optDouble("requested_amount", 0.0),
            approvedAmount = optDouble("approved_amount", 0.0),
            settledAmount = optDouble("settled_amount", 0.0),
            paymentMethod = optStr("payment_method"),
            transactionId = optStr("transaction_id"),
            status = optStr("status").ifBlank { PC_STATUS_PENDING },
            requestedAt = isoMillis("requested_at"),
            approvedAt = isoMillis("approved_at"),
            settledAt = isoMillis("settled_at"),
            createdAt = isoMillis("created_at"),
            updatedAt = isoMillis("updated_at"),
            requesterUid = optStr("requester_uid"),
            requesterRole = optStr("requester_role"),
            storeId = optStr("store_id"),
            storeName = optStr("store_name"),
            pickupCount = optInt("pickup_count", 0),
            attachments = optJSONArray("attachments")?.let { arr ->
                List(arr.length()) { i ->
                    val o = arr.optJSONObject(i)
                    AttachmentRef(
                        key = o?.optStr("key").orEmpty(),
                        name = o?.optStr("name").orEmpty(),
                        sizeBytes = o?.optLong("size", 0L) ?: 0L,
                    )
                }.filter { it.key.isNotBlank() }
            } ?: emptyList(),
            verifiedByUid = optStr("verified_by_uid"),
            verifiedBySystemId = optStr("verified_by_system_id"),
            verifiedByName = embedName("staff_user"),
            verifiedAt = isoMillis("verified_at"), verifiedComment = optStr("verified_comment"),
            approvedByUid = optStr("approved_by_uid"),
            approvedBySystemId = optStr("approved_by_system_id"),
            approvedByName = embedName("poc_user"),
            approvedComment = optStr("approved_comment"),
            settleInProcessByUid = optStr("settle_in_process_by_uid"),
            settleInProcessBySystemId = optStr("settle_in_process_by_system_id"),
            settleInProcessByName = embedName("settle_user"),
            settleInProcessAt = isoMillis("settle_in_process_at"),
            settledByUid = optStr("settled_by_uid"),
            settledBySystemId = optStr("settled_by_system_id"),
            settledByName = embedName("settled_user"),
            rejectedByUid = optStr("rejected_by_uid"),
            rejectedBySystemId = optStr("rejected_by_system_id"),
            rejectedByName = embedName("rejected_user"),
            rejectedAt = isoMillis("rejected_at"), rejectReason = optStr("reject_reason")
        )
    }

    /** PostgREST select that joins names for all actor system_id FKs.
     *  Each alias maps to a `users` row via FK on that column:
     *  requester=agent_system_id, staff_user=verified_by_system_id, etc.
     *  branch joins branches via branch_id FK.
     *  Used by getById/search/searchMyClaims — report methods keep their own
     *  separate select (fetchClaimsForReport) unchanged. */
    private const val ACTOR_SELECT =
        "*," +
        "branch:branch_id(name)," +
        "requester:requester_system_id(name)," +
        "staff_user:verified_by_system_id(name)," +
        "poc_user:approved_by_system_id(name)," +
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
            urlBuilder.append("&requester_system_id=in.(").append(filter.systemIds.joinToString(",") { it.encodeParam() }).append(")")
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
        val filter = ClaimsReportFilter(
            branchIds = emptySet(), systemIds = setOf(systemId),
            fromMillis = fromMillis, toMillis = toMillis, newestFirst = newestFirst)
        val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
        val url = StringBuilder("${SupabaseConfig.PROJECT_URL}/rest/v1/claims")
            .append("?select=").append(ACTOR_SELECT.encodeParam())
            .append("&requester_system_id=eq.").append(systemId.encodeParam())
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

