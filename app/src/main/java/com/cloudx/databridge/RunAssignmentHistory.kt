package com.cloudx.databridge

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Parcel journey "assigned to" entries — which agent held a parcel on which day.
 *
 * Assignment lives only on the run nodes: `courier/runs_by_consignmentId/{cid}`
 * gives `(runType, runId)` pairs, and `courier/run_routes/{runType}/{runId}`
 * carries `agentSystemId` (+ `consignments` map + `created_at`). The
 * consignment node itself carries no agent, so the journey log (Call Center +
 * Worker tap-and-hold) merges one system entry per (Dhaka day, agent) into
 * the timeline, date-wise with the remarks.
 *
 * Best-effort: any failure yields whatever resolved so far (possibly empty),
 * and the journey dialog renders exactly as before when there is nothing.
 */
object RunAssignmentHistory {

    data class RunAssignment(
        val runType: String,
        val runId: String,
        val agentSystemId: String,
        /** Dhaka millis when the parcel landed on this run (run created_at, else runId date at noon). */
        val assignedAt: Long,
    )

    suspend fun fetch(consignmentId: String): List<RunAssignment> = withContext(Dispatchers.IO) {
        if (consignmentId.isBlank()) return@withContext emptyList()
        val db = FirebaseDatabase.getInstance()
        val indexSnap = runCatching {
            db.reference.child("courier/runs_by_consignmentId/$consignmentId").get().await()
        }.getOrNull() ?: return@withContext emptyList()
        if (!indexSnap.exists()) return@withContext emptyList()
        // (runType, runId) pairs — stale pointers are verified against the
        // run node below, never trusted blindly.
        val pairs = mutableSetOf<Pair<String, String>>()
        indexSnap.children.forEach { typeSnap ->
            val runType = typeSnap.key?.trim().orEmpty()
            if (runType.isBlank()) return@forEach
            typeSnap.children.forEach { runSnap ->
                val runId = runSnap.key?.trim().orEmpty()
                if (runId.isNotBlank()) pairs.add(runType to runId)
            }
        }
        if (pairs.isEmpty()) return@withContext emptyList()
        coroutineScope {
            pairs.map { (runType, runId) ->
                async {
                    val snap = runCatching {
                        db.reference.child("courier/run_routes/$runType/$runId").get().await()
                    }.getOrNull() ?: return@async null
                    if (!snap.exists()) return@async null
                    // Stale-pointer guard: the run must still list this parcel.
                    if (!snap.child("consignments").hasChild(consignmentId)) return@async null
                    var agent = snap.child("agentSystemId").getValue(String::class.java)?.trim().orEmpty()
                    if (agent.isBlank()) {
                        // runId is run_{yyyyMMdd}_{systemId} by construction.
                        val parts = runId.split("_")
                        if (parts.size >= 3) agent = parts.drop(2).joinToString("_").trim()
                    }
                    if (agent.isBlank()) return@async null
                    RunAssignment(runType, runId, agent, assignedAtOf(snap, runId))
                }
            }.awaitAll().filterNotNull()
        }
            // One entry per (Dhaka day, agent): same-day re-runs collapse into
            // one row, while different agents on one day (planned move) each
            // keep their row — so the timeline reads "who held it which day".
            .groupBy { DhakaTime.dayKey(it.assignedAt) to it.agentSystemId }
            .mapNotNull { (_, group) -> group.minByOrNull { it.assignedAt } }
            .sortedBy { it.assignedAt }
    }

    /** Assignments → system timeline entries ("Assigned to {agent}"). */
    fun toHistoryEntries(
        assignments: List<RunAssignment>,
        labelOf: (String) -> String = { it },
    ): List<HistoryEntry> = assignments.map { a ->
        val label = labelOf(a.agentSystemId).ifBlank { a.agentSystemId }
        HistoryEntry(
            action = "ASSIGNED",
            remark = "Assigned to $label",
            time = JourneyLogUi.formatEpochFull(a.assignedAt),
            author = "System",
            authorRole = "system",
            createdAt = a.assignedAt,
        )
    }

    private fun assignedAtOf(runSnap: DataSnapshot, runId: String): Long {
        when (val raw = runSnap.child("created_at").value) {
            is Long -> if (raw > 0L) return raw
            is Number -> if (raw.toLong() > 0L) return raw.toLong()
            is String -> raw.trim().toLongOrNull()?.takeIf { it > 0L }?.let { return it }
            else -> {}
        }
        return runIdDayMillis(runId)
    }

    /** `run_yyyyMMdd_...` → that Dhaka day at noon (noon, not midnight, so the
     *  millis can never spill into a neighbouring day in any zone). */
    fun runIdDayMillis(runId: String): Long {
        val date = runId.split("_").getOrNull(1)?.trim().orEmpty()
        if (date.length != 8 || !date.all { it.isDigit() }) return 0L
        return runCatching {
            val y = date.substring(0, 4).toInt()
            val m = date.substring(4, 6).toInt()
            val d = date.substring(6, 8).toInt()
            DhakaTime.calendar().apply {
                set(y, m - 1, d, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }.getOrDefault(0L)
    }
}
