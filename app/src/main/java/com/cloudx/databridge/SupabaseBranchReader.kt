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
        val managerUids: List<String>,
        val managerRoles: List<String>,
        val accountantUid: String,
        val accountantRole: String,
        val accountantUids: List<String>,
        val accountantRoles: List<String>,
        val pettyCashPocUid: String,
        val pettyCashPocUids: List<String>,
        val pettyCashPocRoles: List<String>,
        val pettyCashLimit: Double,
        val staffUid: String,
        val staffRole: String,
        val staffUids: List<String>,
        val staffRoles: List<String>,
        val parentBranchId: String,
        val region: String,
        val status: String,
        val imageUrl: String,
        val createdAt: Long
    )

    private const val SELECT = "branch_id,branch_code,name,branch_type,address,latitude,longitude," +
        "email,phone,manager_uid,manager_uids,manager_roles,accountant_uid,accountant_role," +
        "accountant_uids,accountant_roles,petty_cash_poc_uid,petty_cash_poc_uids,petty_cash_poc_roles," +
        "petty_cash_limit,staff_uid,staff_role,staff_uids,staff_roles,parent_branch_id,region,status," +
        "image_url,created_at"

    private fun String.encodeParam(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private fun org.json.JSONObject.toBranchRow(): BranchRow {
        fun isoMillis(key: String): Long {
            return SupabaseRemarkValidationWriter.parseDbTimestampMillis(optStr(key))
        }
        fun strList(key: String): List<String> {
            val a = optJSONArray(key) ?: return emptyList()
            return List(a.length()) { a.optString(it) }
                .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        }
        fun withLegacy(list: List<String>, legacy: String): List<String> {
            val l = legacy.trim()
            return if (l.isBlank() || l in list) list else list + l
        }
        return BranchRow(
            branchId = optStr("branch_id"),
            branchCode = optStr("branch_code"),
            name = optStr("name"),
            branchType = optStr("branch_type"),
            address = optStr("address"),
            latitude = optDouble("latitude", 0.0),
            longitude = optDouble("longitude", 0.0),
            email = optStr("email"),
            phone = optStr("phone"),
            managerUid = optStr("manager_uid"),
            managerUids = withLegacy(strList("manager_uids"), optStr("manager_uid")),
            managerRoles = strList("manager_roles"),
            accountantUid = optStr("accountant_uid"),
            accountantRole = optStr("accountant_role"),
            accountantUids = withLegacy(strList("accountant_uids"), optStr("accountant_uid")),
            accountantRoles = withLegacy(strList("accountant_roles"), optStr("accountant_role")),
            pettyCashPocUid = optStr("petty_cash_poc_uid"),
            pettyCashPocUids = withLegacy(strList("petty_cash_poc_uids"), optStr("petty_cash_poc_uid")),
            pettyCashPocRoles = strList("petty_cash_poc_roles"),
            pettyCashLimit = optDouble("petty_cash_limit", 0.0),
            staffUid = optStr("staff_uid"),
            staffRole = optStr("staff_role"),
            staffUids = withLegacy(strList("staff_uids"), optStr("staff_uid")),
            staffRoles = withLegacy(strList("staff_roles"), optStr("staff_role")),
            parentBranchId = optStr("parent_branch_id"),
            region = optStr("region"),
            status = optStr("status").ifBlank { "active" },
            imageUrl = optStr("image_url"),
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

    /**
     * Branch-ID rescue: legacy Firebase data sometimes carries a branch NAME
     * where an ID belongs (deliveryHub="Madanpur", old resolvedBranchIds) —
     * saving that into validations.branch_id breaks branch filtering/reporting
     * (RLS + report queries match on branch_id). Pure function so hot paths
     * (card builds) stay synchronous:
     * - known ID → kept as-is
     * - known name (ignore-case exact, e.g. "Madanpur Hub") → its ID
     * - unique first-word match (e.g. "Madanpur" → "Madanpur Hub") → its ID
     * - anything else → returned unchanged (callers must not invent IDs;
     *   ambiguous multi-match also stays unchanged).
     */
    fun canonicalBranchIdLocal(token: String, idToName: Map<String, String>): String {
        val t = token.trim()
        if (t.isEmpty() || idToName.containsKey(t)) return t
        val byName = idToName.entries.firstOrNull { it.value.equals(t, ignoreCase = true) }?.key
        if (byName != null) return byName
        val prefixHits = idToName.entries
            .filter { it.value.lowercase().startsWith(t.lowercase() + " ") }
            .map { it.key }
            .distinct()
        return if (prefixHits.size == 1) prefixHits.first() else t
    }

    @Volatile private var canonicalDir: Map<String, String>? = null
    @Volatile private var canonicalDirAt: Long = 0

    /** Network version (10-min cached directory) for save paths without a
     *  warm id→name map (e.g. the caller-ID overlay). Never throws — on any
     *  failure the original token comes back unchanged. */
    suspend fun canonicalBranchId(token: String): String {
        val t = token.trim()
        if (t.isEmpty()) return t
        return try {
            val now = System.currentTimeMillis()
            var dir = canonicalDir
            if (dir == null || now - canonicalDirAt > 10 * 60 * 1000L) {
                dir = listBranches().associate { it.branchId to it.name }
                canonicalDir = dir
                canonicalDirAt = now
            }
            canonicalBranchIdLocal(t, dir ?: emptyMap())
        } catch (_: Exception) { t }
    }
}
