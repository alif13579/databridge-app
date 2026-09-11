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
import java.util.Date

data class FunnelAgentOption(val systemId: String, val name: String)

data class VerifyDeliveryFunnelState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val scopeName: String = "",
    val totalAssign: Int = 0,
    val verifyRequest: Int = 0,
    val holdReturn: Int = 0,
    val deliveryRequest: Int = 0,
    val confirmed: Int = 0,
    val delivered: Int = 0,
    val pending: Int = 0,
    val agentOptions: List<FunnelAgentOption> = emptyList(),
)

/**
 * "Verify & Delivery Request" funnel, two scopes:
 *
 * SELF (workers — [teamView] = false): only the logged-in user's own runs
 *   (run_{yyyyMMdd}_{systemId}, date-range scoped, distinct consignments =
 *   assigned qty; other agents' runs are never read) and own requests (MY worker
 *   VERIFY_REQUEST rows, distinct per Dhaka-day per consignment: same
 *   consignment twice in one day counts once).
 *
 * TEAM (admin/manager/supervisor — [teamView] = true): [selectedAgentSystemId]
 *   null = All Agents (legacy funnel: any author's WORKER VERIFY_REQUEST,
 *   consignment-distinct), or one agent's system_id = that agent's own funnel
 *   (same self semantics, scoped to them).
 *
 * Downstream of requests in both scopes: Hold/Return vs Delivery Request (LAST
 * CC resolution per consignment: hold_verified / return_verified /
 * delivery_request) -> Confirmed / Delivered / Pending (first remark after
 * delivery_request, or no further remark at all = Pending).
 *
 * Total Assign comes from Firebase in two stages (same as CallCenterFragment):
 * runs_by_branchId/{branchId}/{runType} gives run-ID keys in the date range
 * (self scope keeps only the own run_{date}_{systemId} suffix) — then
 * courier/run_routes/{runType}/{runId} gives consignments. Run keys are
 * lexicographically sortable by their date prefix, so the whole date range is one
 * startAt/endAt query per (branch, runType) pair rather than one query per day.
 *
 * Everything past Total Assign (remark classification) comes from Supabase's
 * validations table.
 */
class VerifyDeliveryDashboardViewModel : ViewModel() {

    private val _state = MutableLiveData(VerifyDeliveryFunnelState())
    val state: LiveData<VerifyDeliveryFunnelState> = _state

    private val dhaka = java.time.ZoneId.of("Asia/Dhaka")

    // Cached across loads within this ViewModel's lifetime -- same session, same names.
    private var systemIdToName: Map<String, String> = emptyMap()

    fun load(rangeStartMs: Long, rangeEndMs: Long, teamView: Boolean, selectedAgentSystemId: String?) {
        viewModelScope.launch {
            _state.value = (_state.value ?: VerifyDeliveryFunnelState()).copy(isLoading = true, error = null)
            try {
                val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                val ownSystemId = ownSystemId(uid)
                if (ownSystemId.isBlank()) {
                    _state.value = (_state.value ?: VerifyDeliveryFunnelState()).copy(
                        isLoading = false,
                        error = "system_id not found — contact your admin"
                    )
                    return@launch
                }

                // Effective scope: self always for workers; supervisor's pick (or all) for team view.
                val scopeSid: String? = if (teamView) selectedAgentSystemId?.takeIf { it.isNotBlank() } else ownSystemId

                val entries = fetchRunEntries(rangeStartMs, rangeEndMs, scopeSid)

                val agentSystemIds = entries.map { it.agentSystemId }.distinct()
                if (systemIdToName.keys.intersect(agentSystemIds.toSet()).size < agentSystemIds.size) {
                    systemIdToName = systemIdToName + resolveAgentNames(agentSystemIds)
                }
                val agentOptions = agentSystemIds
                    .map { FunnelAgentOption(it, systemIdToName[it] ?: it) }
                    .sortedBy { it.name }

                val scopeName = when {
                    !teamView -> systemIdToName[ownSystemId].orEmpty()
                    scopeSid.isNullOrBlank() -> "All Agents"
                    else -> systemIdToName[scopeSid].orEmpty().ifBlank { scopeSid }
                }

                val consignmentIds = entries.flatMap { it.consignmentIds }.toSet()
                val totalAssign = consignmentIds.size

                if (consignmentIds.isEmpty()) {
                    _state.value = VerifyDeliveryFunnelState(
                        isLoading = false, scopeName = scopeName, agentOptions = agentOptions
                    )
                    return@launch
                }

                val remarkRows = fetchRemarksForConsignments(consignmentIds.toList())
                val counts = classify(remarkRows, scopeSid)

                _state.value = VerifyDeliveryFunnelState(
                    isLoading = false,
                    scopeName = scopeName,
                    totalAssign = totalAssign,
                    verifyRequest = counts.verifyRequest,
                    holdReturn = counts.holdReturn,
                    deliveryRequest = counts.deliveryRequest,
                    confirmed = counts.confirmed,
                    delivered = counts.delivered,
                    pending = counts.pending,
                    agentOptions = agentOptions,
                )
            } catch (e: Exception) {
                _state.value = (_state.value ?: VerifyDeliveryFunnelState()).copy(
                    isLoading = false, error = e.message ?: "Load failed"
                )
                FirebaseErrorLogger.log("VerifyDeliveryDashboardViewModel", "load_failed", e.message ?: "")
            }
        }
    }

    /** This device user's system_id (Firebase profile) — the self scope. Blank
     *  for guests / non-onboarded accounts. */
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

    /** Dhaka calendar day (yyyy-MM-dd) for per-day distinct counting. */
    private fun dayKey(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms).atZone(dhaka).toLocalDate().toString()

    // ── Firebase: runs in the date range (scope-filtered) ───────────────────

    private data class RunEntry(val agentSystemId: String, val consignmentIds: Set<String>)

    private suspend fun fetchRunEntries(
        rangeStartMs: Long, rangeEndMs: Long, scopeSid: String?
    ): List<RunEntry> = coroutineScope {
        val db = com.google.firebase.database.FirebaseDatabase.getInstance()
        // Dhaka day keys (GMT+6 pinned) — run IDs are Dhaka-date keyed.
        val fmt = DhakaTime.sdf("yyyyMMdd")
        val startKey = fmt.format(Date(rangeStartMs))
        val endKey = fmt.format(Date(rangeEndMs))
        val branchIds = RbacManager.current.branchIds
        val ownSuffix = if (scopeSid.isNullOrBlank()) null else "_$scopeSid"

        // Stage 1 — index gives (runType, runId) KEYS in range. A set scope keeps
        // only the run_{date}_{systemId} suffix, so foreign runs are skipped
        // before any run node is even read. Null scope (team All) keeps everything.
        // (Index values are plain status strings, NOT run nodes — agent/
        // consignments must come from courier/run_routes below.)
        val runKeys: List<Pair<String, String>> = branchIds.map { branchId ->
            async(Dispatchers.IO) {
                val keys = mutableListOf<Pair<String, String>>()
                val runTypesSnap = runCatching {
                    db.reference.child("courier/runs_by_branchId/$branchId").get().await()
                }.getOrNull() ?: return@async keys
                val runTypes = runTypesSnap.children.mapNotNull { it.key }

                runTypes.forEach { runType ->
                    val rangeSnap = runCatching {
                        db.reference.child("courier/runs_by_branchId/$branchId/$runType")
                            .orderByKey()
                            .startAt("run_${startKey}_")
                            .endAt("run_${endKey}_\uf8ff")
                            .get().await()
                    }.getOrNull() ?: return@forEach

                    rangeSnap.children.forEach { runSnap ->
                        val runId = runSnap.key?.trim().orEmpty()
                        if (runId.isBlank()) return@forEach
                        if (ownSuffix != null && !runId.endsWith(ownSuffix)) return@forEach
                        keys.add(runType to runId)
                    }
                }
                keys
            }
        }.awaitAll().flatten().distinct()
        if (runKeys.isEmpty()) return@coroutineScope emptyList()

        // Stage 2 — actual run nodes carry agentSystemId + consignments map.
        // A set scope double-checks the agent (runId suffix parse as fallback)
        // so a foreign run can never leak in.
        runKeys.map { (runType, runId) ->
            async(Dispatchers.IO) {
                val snap = runCatching {
                    db.reference.child("courier/run_routes/$runType/$runId").get().await()
                }.getOrNull() ?: return@async null
                if (!snap.exists()) return@async null
                var agentSystemId = snap.child("agentSystemId").getValue(String::class.java)?.trim().orEmpty()
                if (agentSystemId.isBlank()) {
                    // Fallback: runId is run_{yyyyMMdd}_{systemId} by construction
                    // (WorkerSpaceFragment.computeTodayRunId), so the suffix parses.
                    val parts = runId.split("_")
                    if (parts.size >= 3) agentSystemId = parts.drop(2).joinToString("_").trim()
                }
                if (agentSystemId.isBlank()) return@async null
                if (scopeSid != null && !agentSystemId.equals(scopeSid, ignoreCase = true)) return@async null
                val consignmentIds = snap.child("consignments").children.mapNotNull { it.key }.toSet()
                if (consignmentIds.isEmpty()) return@async null
                RunEntry(agentSystemId, consignmentIds)
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun resolveAgentNames(systemIds: List<String>): Map<String, String> {
        if (systemIds.isEmpty()) return emptyMap()
        // Supabase users first (source of truth — survives admin renames);
        // Firebase below stays as fallback for cross-branch RLS gaps / legacy rows.
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
                            db.reference.child("users/$uid/profile/name").get().await().getValue(String::class.java)
                        }.getOrNull()?.trim()
                        sysId to name
                    }
                }.awaitAll()
            }.filter { !it.second.isNullOrBlank() }.associate { it.first to it.second!! }
                .let { out.putAll(it); out }
        } catch (e: Exception) {
            FirebaseErrorLogger.log("VerifyDeliveryDashboardViewModel", "resolve_agent_names_failed", e.message ?: "")
            out
        }
    }

    // ── Supabase: remark rows for the assigned consignments ──────────────────

    private suspend fun fetchRemarksForConsignments(ids: List<String>): List<JSONObject> = coroutineScope {
        ids.chunked(200).map { chunk ->
            async(Dispatchers.IO) {
                SupabaseClientManager.fetchValidations(
                    "VerifyDeliveryDashboardViewModel", "fetch_funnel_remarks", listOf(
                        "consignment" to "in.(${chunk.joinToString(",")})",
                        "order" to "created_at.asc",
                    )
                )
            }
        }.awaitAll().flatten()
    }

    // ── Classification ────────────────────────────────────────────────────────

    private data class FunnelCounts(
        val verifyRequest: Int, val holdReturn: Int, val deliveryRequest: Int,
        val confirmed: Int, val delivered: Int, val pending: Int,
    )

    private enum class Outcome { CONFIRMED, DELIVERED, PENDING }

    /**
     * [scopeSid] null = team All (legacy: any author's WORKER VERIFY_REQUEST,
     * consignment-distinct). Set = one agent's funnel (their WORKER
     * VERIFY_REQUEST rows only, distinct per Dhaka-day per consignment).
     */
    private fun classify(rows: List<JSONObject>, scopeSid: String?): FunnelCounts {
        var verifyRequest = 0
        var holdReturn = 0
        var deliveryRequest = 0
        var confirmed = 0
        var delivered = 0
        var pending = 0

        rows.groupBy { it.optString("consignment") }.forEach { (_, group) ->
            val sorted = group.sortedBy { SupabaseRemarkValidationWriter.parseCreatedAtMillis(it.optString("created_at")) }

            // Requested units: per-day-distinct (Dhaka) for an agent scope,
            // consignment-distinct (1) for team All.
            val requestUnits: Set<String> = if (scopeSid.isNullOrBlank()) {
                val hasWorkerVerifyRequest = sorted.any {
                    it.optString("source").equals("WORKER", ignoreCase = true) &&
                        it.optString("remarks_status").equals("VERIFY_REQUEST", ignoreCase = true)
                }
                if (!hasWorkerVerifyRequest) return@forEach
                setOf("all")
            } else {
                val myDays = sorted.mapNotNull { r ->
                    if (!r.optString("source").equals("WORKER", ignoreCase = true)) return@mapNotNull null
                    if (!r.optString("remarks_status").equals("VERIFY_REQUEST", ignoreCase = true)) return@mapNotNull null
                    if (!r.optString("author_system_id").trim().equals(scopeSid, ignoreCase = true)) return@mapNotNull null
                    val ms = SupabaseRemarkValidationWriter.parseCreatedAtMillis(r.optString("created_at"))
                    if (ms <= 0L) null else dayKey(ms)
                }.toSet()
                if (myDays.isEmpty()) return@forEach
                myDays
            }

            // Journey of this consignment: last CC resolution + first remark after it.
            val ccResolution = sorted.lastOrNull {
                it.optString("source").equals("CC", ignoreCase = true) &&
                    it.optString("remarks_status").lowercase() in setOf("hold_verified", "return_verified", "delivery_request")
            }
            val kind = when (ccResolution?.optString("remarks_status")?.lowercase()) {
                "hold_verified", "return_verified" -> "hold"
                "delivery_request" -> "delivery"
                else -> null
            }
            val outcome = if (kind == "delivery" && ccResolution != null) {
                val resolvedAt = SupabaseRemarkValidationWriter.parseCreatedAtMillis(ccResolution.optString("created_at"))
                val nextRemark = sorted.firstOrNull {
                    SupabaseRemarkValidationWriter.parseCreatedAtMillis(it.optString("created_at")) > resolvedAt
                }
                when {
                    nextRemark == null -> Outcome.PENDING
                    nextRemark.optString("remarks_status").equals("CONFIRMED", ignoreCase = true) -> Outcome.CONFIRMED
                    nextRemark.optString("remarks_status").equals("DELIVERED", ignoreCase = true) -> Outcome.DELIVERED
                    else -> Outcome.PENDING
                }
            } else null

            // Every requested unit of this consignment flows through the same journey.
            requestUnits.forEach { _ ->
                verifyRequest++
                when (kind) {
                    "hold" -> holdReturn++
                    "delivery" -> {
                        deliveryRequest++
                        when (outcome) {
                            Outcome.CONFIRMED -> confirmed++
                            Outcome.DELIVERED -> delivered++
                            else -> pending++
                        }
                    }
                    else -> Unit
                }
            }
        }

        return FunnelCounts(verifyRequest, holdReturn, deliveryRequest, confirmed, delivered, pending)
    }
}
