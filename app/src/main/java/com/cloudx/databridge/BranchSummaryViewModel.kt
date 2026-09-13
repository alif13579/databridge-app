package com.cloudx.databridge

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

/** One run's summary row (run-wise table). */
data class BranchRunRow(
    val runType: String,
    val runId: String,
    val dateKey: String,
    val agentSystemId: String,
    val agentName: String,
    val total: Int,
    val verifyRequested: Int,
    val validated: Int,
    val verified: Int,
    val verifiedStrict: Int = 0,
    val verifiedNonStrict: Int = 0,
    val verifiedReturn: Int = 0,
    val verifiedUnset: Int = 0,
    val deliveryRequest: Int,
    val achievement: Int,
    val notDelivered: Int,
    val carried: Int,
    val noRequest: Int,
    val seenOther: Int = 0,
)

data class BranchSummaryState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val scopeName: String = "",
    val selfScope: Boolean = false,
    val totalRuns: Int = 0,
    val totalParcels: Int = 0,
    val verifyRequested: Int = 0,
    val validated: Int = 0,
    val verified: Int = 0,
    val verifiedStrict: Int = 0,
    val verifiedNonStrict: Int = 0,
    val verifiedReturn: Int = 0,
    val verifiedUnset: Int = 0,
    val deliveryRequest: Int = 0,
    val achievement: Int = 0,
    val notDelivered: Int = 0,
    val carried: Int = 0,
    val noRequest: Int = 0,
    val seenOther: Int = 0,
    val pending: Int = 0,
    val runs: List<BranchRunRow> = emptyList(),
    val truncated: Boolean = false,
)

/**
 * Branch Summary — date-range run + validation funnel per branch.
 *
 * Scope: workers see only their own runs (run_{date}_{ownSystemId} + agent
 * check); everyone else sees the whole selected branch. Same 2-stage Firebase
 * read as VerifyDeliveryDashboardViewModel (runs_by_branchId index keys, then
 * run_routes nodes for consignments + agent).
 *
 * Remark classification mirrors the DataBridge-Extension xcheck logic
 * (scan-receive-helper.js xcheckClassify): latest CC row per consignment wins;
 * delivery_request + delivered-type run status = achievement; delivery_request
 * + anything else = not-delivered warning; older undelivered delivery_request
 * = carried (previous days). Only the date window differs — the extension is
 * today-scoped (Dhaka), here it is the picked [rangeStartMs, rangeEndMs].
 *
 * Supabase source: public.validations (SupabaseClientManager.fetchValidations).
 */
class BranchSummaryViewModel : ViewModel() {

    companion object {
        /** Extension parity: XCHECK_DELIVERY (scan-receive-helper.js). */
        val DELIVERED_STATUSES = setOf("delivered", "partial delivery", "partial", "paid return", "exchange")
        private const val CARRY_DAYS = 7L
        private const val MAX_RUNS = 300
    }

    private val _state = MutableLiveData(BranchSummaryState())
    val state: LiveData<BranchSummaryState> = _state

    private var systemIdToName: Map<String, String> = emptyMap()

    fun load(branchId: String, rangeStartMs: Long, rangeEndMs: Long) {
        viewModelScope.launch {
            _state.value = BranchSummaryState(isLoading = true)
            try {
                if (branchId.isBlank()) {
                    _state.value = BranchSummaryState(isLoading = false, error = "No branch assigned to this account")
                    return@launch
                }
                val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                val ownSystemId = ownSystemId(uid)
                val selfScope = RbacManager.current.roleId == "worker"
                if (selfScope && ownSystemId.isBlank()) {
                    _state.value = BranchSummaryState(isLoading = false, error = "system_id not found — contact your admin")
                    return@launch
                }
                val scopeSid: String? = if (selfScope) ownSystemId else null

                val entries = fetchRunEntries(branchId, rangeStartMs, rangeEndMs, scopeSid)
                if (entries.isEmpty()) {
                    _state.value = BranchSummaryState(
                        isLoading = false,
                        scopeName = if (selfScope) (systemIdToName[ownSystemId] ?: ownSystemId) else "Branch",
                        selfScope = selfScope,
                    )
                    return@launch
                }
                val truncated = entries.size > MAX_RUNS
                val used = if (truncated) entries.take(MAX_RUNS) else entries

                val agentIds = used.map { it.agentSystemId }.distinct()
                if (systemIdToName.keys.intersect(agentIds.toSet()).size < agentIds.size) {
                    systemIdToName = systemIdToName + resolveAgentNames(agentIds)
                }

                val allIds = used.flatMap { it.statuses.keys }.toSet().toList()
                val ccGteMs = rangeStartMs - CARRY_DAYS * 24L * 60L * 60L * 1000L
                val rows = fetchValidationRows(allIds, ccGteMs)
                // Hold class lives only in the validation_remarks catalog
                // (validations rows never carry it) — map winning remark text
                // to its class for the strict/non-strict split below.
                val holdClassOf: Map<String, String> = runCatching {
                    SupabaseClientManager.fetchRemarkOptions("BranchSummaryViewModel", "CC")
                        .filter { it.textEn.isNotBlank() }
                        .associate { it.textEn.trim().lowercase() to it.holdClass.trim().lowercase() }
                }.getOrDefault(emptyMap())
                val ccByConsignment = rows.filter {
                    it.optString("source").equals("CC", ignoreCase = true)
                }.groupBy { it.optString("consignment") }
                val workerReqByConsignment = rows.filter {
                    it.optString("source").equals("WORKER", ignoreCase = true) &&
                        it.optString("remarks_status").equals("VERIFY_REQUEST", ignoreCase = true)
                }.groupBy { it.optString("consignment") }

                val runRows = used.map { e ->
                    classifyRun(e, ccByConsignment, workerReqByConsignment, rangeStartMs, rangeEndMs, scopeSid, holdClassOf)
                }.sortedWith(compareBy({ it.dateKey }, { it.runId }))

                _state.value = BranchSummaryState(
                    isLoading = false,
                    scopeName = if (selfScope) {
                        systemIdToName[ownSystemId].orEmpty().ifBlank { ownSystemId }
                    } else "Branch",
                    selfScope = selfScope,
                    totalRuns = runRows.size,
                    totalParcels = runRows.sumOf { it.total },
                    verifyRequested = runRows.sumOf { it.verifyRequested },
                    validated = runRows.sumOf { it.validated },
                    verified = runRows.sumOf { it.verified },
                    verifiedStrict = runRows.sumOf { it.verifiedStrict },
                    verifiedNonStrict = runRows.sumOf { it.verifiedNonStrict },
                    verifiedReturn = runRows.sumOf { it.verifiedReturn },
                    verifiedUnset = runRows.sumOf { it.verifiedUnset },
                    deliveryRequest = runRows.sumOf { it.deliveryRequest },
                    achievement = runRows.sumOf { it.achievement },
                    notDelivered = runRows.sumOf { it.notDelivered },
                    carried = runRows.sumOf { it.carried },
                    noRequest = runRows.sumOf { it.noRequest },
                    seenOther = runRows.sumOf { it.seenOther },
                    pending = (runRows.sumOf { it.verifyRequested } - runRows.sumOf { it.validated }).coerceAtLeast(0),
                    runs = runRows,
                    truncated = truncated,
                )
            } catch (e: Exception) {
                _state.value = BranchSummaryState(isLoading = false, error = e.message ?: "Load failed")
                FirebaseErrorLogger.log("BranchSummaryViewModel", "load_failed", e.message ?: "")
            }
        }
    }

    private suspend fun ownSystemId(uid: String): String {
        if (uid.isBlank()) return ""
        return try {
            withContext(Dispatchers.IO) {
                com.google.firebase.database.FirebaseDatabase.getInstance().reference
                    .child("users/$uid/profile/company_info/system_id").get().await()
                    .getValue(String::class.java)
            }?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private data class RunEntry(
        val runType: String,
        val runId: String,
        val dateKey: String,
        val agentSystemId: String,
        val statuses: Map<String, String>,
    )

    private suspend fun fetchRunEntries(
        branchId: String, rangeStartMs: Long, rangeEndMs: Long, scopeSid: String?
    ): List<RunEntry> = coroutineScope {
        val db = com.google.firebase.database.FirebaseDatabase.getInstance()
        val fmt = DhakaTime.sdf("yyyyMMdd")
        val startKey = fmt.format(Date(rangeStartMs))
        val endKey = fmt.format(Date(rangeEndMs))
        val ownSuffix = if (scopeSid.isNullOrBlank()) null else "_$scopeSid"

        val runTypesSnap = runCatching {
            withContext(Dispatchers.IO) {
                db.reference.child("courier/runs_by_branchId/$branchId").get().await()
            }
        }.getOrNull() ?: return@coroutineScope emptyList()
        val runTypes = runTypesSnap.children.mapNotNull { it.key }
        val runKeys = mutableListOf<Pair<String, String>>()
        for (runType in runTypes) {
            val rangeSnap = runCatching {
                withContext(Dispatchers.IO) {
                    db.reference.child("courier/runs_by_branchId/$branchId/$runType")
                        .orderByKey()
                        .startAt("run_${startKey}_")
                        .endAt("run_${endKey}_\uf8ff")
                        .get().await()
                }
            }.getOrNull() ?: continue
            rangeSnap.children.forEach { runSnap ->
                val runId = runSnap.key?.trim().orEmpty()
                if (runId.isBlank()) return@forEach
                if (ownSuffix != null && !runId.endsWith(ownSuffix)) return@forEach
                runKeys.add(runType to runId)
            }
        }
        if (runKeys.isEmpty()) return@coroutineScope emptyList()

        runKeys.map { (runType, runId) ->
            async(Dispatchers.IO) {
                val snap = runCatching {
                    db.reference.child("courier/run_routes/$runType/$runId").get().await()
                }.getOrNull() ?: return@async null
                if (!snap.exists()) return@async null
                var agentSystemId = snap.child("agentSystemId").getValue(String::class.java)?.trim().orEmpty()
                if (agentSystemId.isBlank()) {
                    val parts = runId.split("_")
                    if (parts.size >= 3) agentSystemId = parts.drop(2).joinToString("_").trim()
                }
                if (agentSystemId.isBlank()) return@async null
                if (scopeSid != null && !agentSystemId.equals(scopeSid, ignoreCase = true)) return@async null
                val statuses = snap.child("consignments").children.mapNotNull { c ->
                    val cid = c.key?.trim().orEmpty()
                    if (cid.isBlank()) null
                    else cid to (c.getValue(String::class.java)?.trim().orEmpty().ifBlank { "pending" })
                }.toMap()
                if (statuses.isEmpty()) return@async null
                val dateKey = runCatching { runId.split("_")[1] }.getOrDefault("")
                RunEntry(runType, runId, dateKey, agentSystemId, statuses)
            }
        }.awaitAll().filterNotNull().distinctBy { it.runType to it.runId }
    }

    private suspend fun fetchValidationRows(ids: List<String>, gteMs: Long): List<JSONObject> = coroutineScope {
        if (ids.isEmpty()) return@coroutineScope emptyList()
        val gteIso = Instant.ofEpochMilli(gteMs).atOffset(ZoneOffset.ofHours(6)).toString()
        ids.chunked(200).map { chunk ->
            async(Dispatchers.IO) {
                SupabaseClientManager.fetchValidations(
                    "BranchSummaryViewModel", "fetch_range_rows", listOf(
                        "consignment" to "in.(${chunk.joinToString(",")})",
                        "created_at" to "gte.$gteIso",
                        "order" to "created_at.desc",
                    )
                )
            }
        }.awaitAll().flatten()
    }

    private suspend fun resolveAgentNames(systemIds: List<String>): Map<String, String> {
        if (systemIds.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, String>()
        systemIds.distinct().forEach { sysId ->
            runCatching { UserNameResolver.resolveNameBySystemId(sysId) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { out[sysId] = it }
        }
        val missing = systemIds.filter { it !in out }
        if (missing.isEmpty()) return out
        return try {
            val db = com.google.firebase.database.FirebaseDatabase.getInstance()
            val indexSnap = withContext(Dispatchers.IO) {
                db.reference.child("users_by_systemId").get().await()
            }
            val sysIdToUid = mutableMapOf<String, String>()
            indexSnap.children.forEach { child ->
                val sysId = child.key?.trim()
                val uid = child.child("uid").getValue(String::class.java)?.trim()
                if (!sysId.isNullOrBlank() && sysId in systemIds && !uid.isNullOrBlank()) sysIdToUid[sysId] = uid
            }
            coroutineScope {
                sysIdToUid.map { (sysId, uid) ->
                    async(Dispatchers.IO) {
                        val name = runCatching {
                            db.reference.child("users/$uid/profile/name").get().await()
                                .getValue(String::class.java)
                        }.getOrNull()?.trim()
                        sysId to name
                    }
                }.awaitAll()
            }.filter { !it.second.isNullOrBlank() }.associate { it.first to it.second!! }
                .let { out.putAll(it); out }
        } catch (e: Exception) {
            FirebaseErrorLogger.log("BranchSummaryViewModel", "resolve_agent_names_failed", e.message ?: "")
            out
        }
    }

    private fun createdMs(row: JSONObject): Long =
        SupabaseRemarkValidationWriter.parseCreatedAtMillis(row.optString("created_at"))

    /** Extension xcheckClassify, date-range scoped (see class doc). */
    private fun classifyRun(
        entry: RunEntry,
        ccByConsignment: Map<String, List<JSONObject>>,
        workerReqByConsignment: Map<String, List<JSONObject>>,
        rangeStartMs: Long,
        rangeEndMs: Long,
        scopeSid: String?,
        holdClassOf: Map<String, String> = emptyMap(),
    ): BranchRunRow {
        var verifyRequested = 0
        var verified = 0
        var verifiedStrict = 0
        var verifiedNonStrict = 0
        var verifiedReturn = 0
        var verifiedUnset = 0
        var achievement = 0
        var notDelivered = 0
        var carried = 0
        var noRequest = 0
        var seenOther = 0

        entry.statuses.forEach { (cid, runStatusRaw) ->
            val runDelivered = runStatusRaw.trim().lowercase() in DELIVERED_STATUSES

            val reqRows = (workerReqByConsignment[cid] ?: emptyList()).filter { r ->
                val ms = createdMs(r)
                ms in rangeStartMs..rangeEndMs &&
                    (scopeSid.isNullOrBlank() ||
                        r.optString("author_system_id").trim().equals(scopeSid, ignoreCase = true))
            }
            if (reqRows.isNotEmpty()) verifyRequested++

            val ccRows = (ccByConsignment[cid] ?: emptyList())
            if (ccRows.isEmpty()) {
                noRequest++
                return@forEach
            }
            val latest = ccRows.maxByOrNull { createdMs(it) } ?: return@forEach
            val latestMs = createdMs(latest)
            val inRange = latestMs in rangeStartMs..rangeEndMs
            when (latest.optString("remarks_status").trim().lowercase()) {
                "hold_verified", "return_verified" -> if (inRange) {
                    verified++
                    if (latest.optString("remarks_status").trim().equals("return_verified", ignoreCase = true)) {
                        verifiedReturn++
                    } else {
                        when (holdClassOf[latest.optString("remarks").trim().lowercase()].orEmpty()) {
                            ConfigState.HOLD_CLASS_STRICT -> verifiedStrict++
                            ConfigState.HOLD_CLASS_NON_STRICT -> verifiedNonStrict++
                            else -> verifiedUnset++
                        }
                    }
                } else {
                    // CC answer outside window — not counted as verified in this range.
                    noRequest++
                }
                "delivery_request" -> when {
                    runDelivered && inRange -> achievement++
                    inRange -> notDelivered++
                    latestMs < rangeStartMs && !runDelivered -> carried++
                    // Delivered before the range, or undelivered-but-delivered
                    // edge: counts as neither achievement nor warning.
                    runDelivered -> achievement++
                    else -> carried++
                }
                // Any other CC remark in range still means the parcel was seen.
                else -> if (!inRange) noRequest++ else seenOther++
            }
        }

        val deliveryRequest = achievement + notDelivered
        val validated = verified + achievement + notDelivered + seenOther
        return BranchRunRow(
            runType = entry.runType,
            runId = entry.runId,
            dateKey = entry.dateKey,
            agentSystemId = entry.agentSystemId,
            agentName = systemIdToName[entry.agentSystemId] ?: entry.agentSystemId,
            total = entry.statuses.size,
            verifyRequested = verifyRequested,
            validated = validated,
            verified = verified,
            verifiedStrict = verifiedStrict,
            verifiedNonStrict = verifiedNonStrict,
            verifiedReturn = verifiedReturn,
            verifiedUnset = verifiedUnset,
            deliveryRequest = deliveryRequest,
            achievement = achievement,
            notDelivered = notDelivered,
            carried = carried,
            noRequest = noRequest,
            seenOther = seenOther,
        )
    }
}
