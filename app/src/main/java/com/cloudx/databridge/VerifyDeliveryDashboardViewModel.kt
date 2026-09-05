package com.cloudx.databridge

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FunnelAgentOption(val systemId: String, val name: String)

data class VerifyDeliveryFunnelState(
    val isLoading: Boolean = true,
    val error: String? = null,
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
 * Loads the "Verify & Delivery Request" funnel:
 *
 *   Total Assign (Firebase runs, date-range scoped)
 *     -> Verify Request (consignments with a WORKER remark, status VERIFY_REQUEST)
 *       -> Hold/Return (CC resolves with hold_verified / return_verified)
 *       -> Delivery Request (CC resolves with delivery_request)
 *         -> Confirmed / Delivered / Pending (worker's next remark after delivery_request,
 *            or no further remark at all = Pending)
 *
 * Total Assign comes from Firebase in two stages (same as CallCenterFragment):
 * runs_by_branchId/{branchId}/{runType} gives run-ID keys in the date range
 * (index values are plain status strings), then courier/run_routes/{runType}/
 * {runId} gives agentSystemId + consignments — run keys are
 * lexicographically sortable by their date prefix, so the whole date range is one
 * startAt/endAt query per (branch, runType) pair rather than one query per day.
 *
 * Everything past Total Assign (remark classification) comes from Supabase's
 * validations table, per the confirmed status values:
 *   - Worker remark: source=WORKER, remarks_status=VERIFY_REQUEST
 *   - CC resolution: source=CC, remarks_status in [hold_verified, return_verified, delivery_request]
 *   - Worker outcome: remarks_status in [CONFIRMED, DELIVERED] after a delivery_request
 */
class VerifyDeliveryDashboardViewModel : ViewModel() {

    private val _state = MutableLiveData(VerifyDeliveryFunnelState())
    val state: LiveData<VerifyDeliveryFunnelState> = _state

    // Cached across loads within this ViewModel's lifetime -- same session, same names.
    private var systemIdToName: Map<String, String> = emptyMap()

    fun load(rangeStartMs: Long, rangeEndMs: Long, selectedAgentSystemId: String?) {
        viewModelScope.launch {
            _state.value = (_state.value ?: VerifyDeliveryFunnelState()).copy(isLoading = true, error = null)
            try {
                val entries = fetchRunEntries(rangeStartMs, rangeEndMs)

                val agentSystemIds = entries.map { it.agentSystemId }.distinct()
                if (systemIdToName.keys.intersect(agentSystemIds.toSet()).size < agentSystemIds.size) {
                    systemIdToName = systemIdToName + resolveAgentNames(agentSystemIds)
                }
                val agentOptions = agentSystemIds
                    .map { FunnelAgentOption(it, systemIdToName[it] ?: it) }
                    .sortedBy { it.name }

                val filtered = if (selectedAgentSystemId.isNullOrBlank()) entries
                    else entries.filter { it.agentSystemId == selectedAgentSystemId }

                val consignmentIds = filtered.flatMap { it.consignmentIds }.toSet()
                val totalAssign = consignmentIds.size

                if (consignmentIds.isEmpty()) {
                    _state.value = VerifyDeliveryFunnelState(isLoading = false, agentOptions = agentOptions)
                    return@launch
                }

                val remarkRows = fetchRemarksForConsignments(consignmentIds.toList())
                val counts = classify(remarkRows)

                _state.value = VerifyDeliveryFunnelState(
                    isLoading = false,
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

    // ── Firebase: runs in the date range ──────────────────────────────────────

    private data class RunEntry(val agentSystemId: String, val consignmentIds: Set<String>)

    private suspend fun fetchRunEntries(rangeStartMs: Long, rangeEndMs: Long): List<RunEntry> = coroutineScope {
        val db = com.google.firebase.database.FirebaseDatabase.getInstance()
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.ENGLISH)
        val startKey = fmt.format(Date(rangeStartMs))
        val endKey = fmt.format(Date(rangeEndMs))
        val branchIds = RbacManager.current.branchIds

        // Stage 1 — index only gives (runType, runId) KEYS in range. The index
        // value itself is just a status string (written by ConfigSheetWizardSteps),
        // NOT a run node — so agentSystemId/consignments must come from
        // courier/run_routes below (same two-stage pattern CallCenterFragment uses).
        // Reading them off the index snapshot always yields blank/empty, i.e. a
        // permanently-zero dashboard — the bug this fixes.
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
                        if (runId.isNotBlank()) keys.add(runType to runId)
                    }
                }
                keys
            }
        }.awaitAll().flatten().distinct()
        if (runKeys.isEmpty()) return@coroutineScope emptyList()

        // Stage 2 — actual run nodes carry agentSystemId + consignments map.
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
                val consignmentIds = snap.child("consignments").children.mapNotNull { it.key }.toSet()
                if (consignmentIds.isEmpty()) return@async null
                RunEntry(agentSystemId, consignmentIds)
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun resolveAgentNames(systemIds: List<String>): Map<String, String> {
        if (systemIds.isEmpty()) return emptyMap()
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
        } catch (e: Exception) {
            FirebaseErrorLogger.log("VerifyDeliveryDashboardViewModel", "resolve_agent_names_failed", e.message ?: "")
            emptyMap()
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

    private fun classify(rows: List<JSONObject>): FunnelCounts {
        var verifyRequest = 0
        var holdReturn = 0
        var deliveryRequest = 0
        var confirmed = 0
        var delivered = 0
        var pending = 0

        rows.groupBy { it.optString("consignment") }.forEach { (_, group) ->
            val sorted = group.sortedBy { SupabaseRemarkValidationWriter.parseCreatedAtMillis(it.optString("created_at")) }

            val hasWorkerVerifyRequest = sorted.any {
                it.optString("source").equals("WORKER", ignoreCase = true) &&
                    it.optString("remarks_status").equals("VERIFY_REQUEST", ignoreCase = true)
            }
            if (!hasWorkerVerifyRequest) return@forEach
            verifyRequest++

            val ccResolution = sorted.lastOrNull {
                it.optString("source").equals("CC", ignoreCase = true) &&
                    it.optString("remarks_status").lowercase() in setOf("hold_verified", "return_verified", "delivery_request")
            } ?: return@forEach

            when (ccResolution.optString("remarks_status").lowercase()) {
                "hold_verified", "return_verified" -> holdReturn++
                "delivery_request" -> {
                    deliveryRequest++
                    val resolvedAt = SupabaseRemarkValidationWriter.parseCreatedAtMillis(ccResolution.optString("created_at"))
                    val nextRemark = sorted.firstOrNull {
                        SupabaseRemarkValidationWriter.parseCreatedAtMillis(it.optString("created_at")) > resolvedAt
                    }
                    when {
                        nextRemark == null -> pending++
                        nextRemark.optString("remarks_status").equals("CONFIRMED", ignoreCase = true) -> confirmed++
                        nextRemark.optString("remarks_status").equals("DELIVERED", ignoreCase = true) -> delivered++
                        else -> pending++
                    }
                }
            }
        }

        return FunnelCounts(verifyRequest, holdReturn, deliveryRequest, confirmed, delivered, pending)
    }
}
