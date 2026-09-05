package com.cloudx.databridge

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class CallerMatch(
    val consignmentId: String,
    val name: String,
    val phone: String,
    val address: String,
    val cod: Int,
    val status: String,
    val statusLabel: String,
    val updatedAt: Long,
)

data class CallerLookupResult(
    val primary: CallerMatch,
    val otherCount: Int,
)

/** Today's assigned agent for one parcel (resolved from today's run nodes). */
data class TodayAssignee(
    val systemId: String,
    val name: String,
)

/**
 * Reverse phone -> consignment lookup for the incoming-call popup. Reuses the app's
 * existing courier/consignments_by_phone index (ConfigSheetWizardSteps writes it on
 * every consignment insert, keyed by ConfigSheetParseUtil.normalizePhone -- same
 * normalization used here so the key we read matches the key that's actually there)
 * rather than a fresh parallel index.
 */
object IncomingCallerLookup {
    private val db by lazy { FirebaseDatabase.getInstance() }

    /**
     * Looks up every consignment for [rawPhone]. When a customer has more than one,
     * picks the one still awaiting action (isVerifyRequestStatus) as primary, falling
     * back to the most recently updated -- the rest are just counted for "+N more".
     * Returns null on no match or any read failure; callers should treat that as
     * "don't show a popup" rather than retrying.
     */
    suspend fun lookup(rawPhone: String): CallerLookupResult? = withContext(Dispatchers.IO) {
        try {
            val normalized = ConfigSheetParseUtil.normalizePhone(rawPhone)
            if (normalized.isBlank()) return@withContext null

            val idsSnap = db.reference.child("courier/consignments_by_phone/$normalized").get().await()
            if (!idsSnap.exists()) return@withContext null
            val ids = idsSnap.children.mapNotNull { it.key }
            if (ids.isEmpty()) return@withContext null

            // Best-effort; a stale/empty label cache still leaves us the raw status string.
            runCatching { StatusMetaCache.refresh() }

            val matches = coroutineScope {
                ids.map { id ->
                    async {
                        val snap = db.reference.child("courier/consignments/$id").get().await()
                        if (!snap.exists()) return@async null
                        val status = snap.child("status").getValue(String::class.java).orEmpty()
                        CallerMatch(
                            consignmentId = id,
                            name = snap.child("recipientName").getValue(String::class.java).orEmpty(),
                            phone = snap.child("recipientPhone").getValue(String::class.java).orEmpty(),
                            address = snap.child("recipientAddress").getValue(String::class.java).orEmpty(),
                            cod = snap.child("collectableAmount").getValue(String::class.java)?.toDoubleOrNull()?.toInt()
                                ?: snap.child("collectableAmount").getValue(Long::class.java)?.toInt() ?: 0,
                            status = status,
                            statusLabel = StatusMetaCache.labelOrNull(status, "en") ?: status.ifBlank { "Unknown" },
                            updatedAt = snap.child("updatedAt").getValue(Long::class.java) ?: 0L,
                        )
                    }
                }.mapNotNull { it.await() }
            }
            if (matches.isEmpty()) return@withContext null

            val primary = matches.firstOrNull { isVerifyRequestStatus(it.status) }
                ?: matches.maxByOrNull { it.updatedAt }
                ?: matches.first()

            CallerLookupResult(primary = primary, otherCount = matches.size - 1)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Which agent each of [consignmentIds] is assigned to in TODAY's runs.
     *
     * Assignment lives only on the run nodes (courier/run_routes/{runType}/
     * {runId} → agentSystemId + consignments map) — the consignment node
     * itself carries no agent, and Supabase validations only has a row once
     * someone already saved a remark. So: today's date prefix → run IDs via
     * the runs_by_branchId index (own branches only, same two-stage pattern
     * CallCenterFragment uses), then invert consignments → agent.
     *
     * Returns only parcels found in some run today; a missing key means "not
     * assigned in any run today" (parcel exists, assignment doesn't).
     * Best-effort: any failure yields whatever resolved so far (possibly empty).
     */
    suspend fun resolveTodayAssignees(consignmentIds: List<String>): Map<String, TodayAssignee> =
        withContext(Dispatchers.IO) {
            try {
                val wanted = consignmentIds.filter { it.isNotBlank() }.toSet()
                if (wanted.isEmpty()) return@withContext emptyMap()
                val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ENGLISH)
                    .format(java.util.Date())
                val branchIds = RbacManager.current.branchIds
                if (branchIds.isEmpty()) return@withContext emptyMap()

                // Stage 1 — today's run IDs from the branch index (values are plain
                // status strings; only the keys matter here).
                val runKeys = coroutineScope {
                    branchIds.map { branchId ->
                        async {
                            val keys = mutableListOf<Pair<String, String>>()
                            val typesSnap = runCatching {
                                db.reference.child("courier/runs_by_branchId/$branchId").get().await()
                            }.getOrNull() ?: return@async keys
                            typesSnap.children.mapNotNull { it.key }.forEach { runType ->
                                val rangeSnap = runCatching {
                                    db.reference.child("courier/runs_by_branchId/$branchId/$runType")
                                        .orderByKey()
                                        .startAt("run_${today}_")
                                        .endAt("run_${today}_\uf8ff")
                                        .get().await()
                                }.getOrNull() ?: return@forEach
                                rangeSnap.children.mapNotNull { it.key?.trim()?.takeIf { k -> k.isNotBlank() } }
                                    .forEach { runId -> keys.add(runType to runId) }
                            }
                            keys
                        }
                    }.awaitAll().flatten().distinct()
                }
                if (runKeys.isEmpty()) return@withContext emptyMap()

                // Stage 2 — run nodes: agent + consignments, inverted to cid → agent.
                val cidToAgent = mutableMapOf<String, String>()
                coroutineScope {
                    runKeys.map { (runType, runId) ->
                        async {
                            val snap = runCatching {
                                db.reference.child("courier/run_routes/$runType/$runId").get().await()
                            }.getOrNull() ?: return@async null
                            if (!snap.exists()) return@async null
                            var agent = snap.child("agentSystemId").getValue(String::class.java)?.trim().orEmpty()
                            if (agent.isBlank()) {
                                // runId is run_{yyyyMMdd}_{systemId} by construction.
                                val parts = runId.split("_")
                                if (parts.size >= 3) agent = parts.drop(2).joinToString("_").trim()
                            }
                            if (agent.isBlank()) return@async null
                            val cids = snap.child("consignments").children.mapNotNull { it.key }
                                .filter { it in wanted }
                            if (cids.isEmpty()) return@async null
                            agent to cids
                        }
                    }.awaitAll().filterNotNull()
                }.forEach { (agent, cids) ->
                    cids.forEach { cidToAgent.putIfAbsent(it, agent) }
                }
                if (cidToAgent.isEmpty()) return@withContext emptyMap()

                val names = resolveAgentNames(cidToAgent.values.distinct())
                cidToAgent.mapValues { (_, sys) -> TodayAssignee(sys, names[sys] ?: sys) }
            } catch (_: Exception) {
                emptyMap()
            }
        }

    private suspend fun resolveAgentNames(systemIds: List<String>): Map<String, String> {
        if (systemIds.isEmpty()) return emptyMap()
        return try {
            val indexSnap = db.reference.child("users_by_systemId").get().await()
            val sysIdToUid = mutableMapOf<String, String>()
            indexSnap.children.forEach { child ->
                val sysId = child.key?.trim()
                val uid = child.child("uid").getValue(String::class.java)?.trim()
                if (!sysId.isNullOrBlank() && sysId in systemIds && !uid.isNullOrBlank()) {
                    sysIdToUid[sysId] = uid
                }
            }
            coroutineScope {
                sysIdToUid.map { (sysId, uid) ->
                    async {
                        val name = runCatching {
                            db.reference.child("users/$uid/profile/name").get().await()
                                .getValue(String::class.java)
                        }.getOrNull()?.trim()
                        sysId to name
                    }
                }.awaitAll()
            }.filter { !it.second.isNullOrBlank() }.associate { it.first to it.second!! }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
