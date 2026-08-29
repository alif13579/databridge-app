package com.cloudx.databridge

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/**
 * Realtime Database access for the claims v2 schema.
 *
 * Index range reads are intentionally the first read in [search].  We never
 * download a branch's whole index and then trim the date range on-device.
 */
class ClaimsRepository(private val db: FirebaseDatabase = FirebaseDatabase.getInstance()) {

    suspend fun create(info: ClaimInfo, onSupabaseResult: (Boolean) -> Unit = {}): ClaimInfo {
        require(info.branchId.isNotBlank()) { "A branch is required" }
        require(info.employeeId.isNotBlank()) { "An employee ID is required" }
        require(info.agentSystemId.isNotBlank()) { "An agent system ID is required" }
        var timestamp = System.currentTimeMillis()
        var id = claimId(timestamp)
        // A millisecond key is chronological and normally unique.  Guard the
        // extremely rare same-ms collision without using push IDs.
        while (db.reference.child(FirebasePaths.claimInfo(id)).get().await().exists()) {
            timestamp++
            id = claimId(timestamp)
        }
        val claim = info.copy(
            claimId = id,
            // The date here is the submission timestamp, not requestedAt: a
            // requester may submit an expense for an earlier day, while the
            // claim code should make it obvious when this claim was placed.
            // Example: CLM-20260819-a1B2c.
            claimCode = info.claimCode.ifBlank { "CLM-${java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(timestamp))}-${id.takeLast(5)}" },
            createdAt = timestamp,
            updatedAt = timestamp,
            requestedAt = info.requestedAt.takeIf { it > 0 } ?: timestamp
        )
        db.reference.updateChildren(mapOf(
            FirebasePaths.claimInfo(id) to claim,
            "${FirebasePaths.claimsByBranch(claim.branchId)}/$id" to true,
            "${FirebasePaths.claimsBySystemId(claim.agentSystemId)}/$id" to true
        )).await()
        // Firebase is the source of truth and already has the write; Supabase is
        // a best-effort alternative copy — never blocks or fails claim creation.
        SupabaseClaimsWriter.mirror(claim, onSupabaseResult)
        return claim
    }

    suspend fun get(claimId: String): ClaimInfo? =
        db.reference.child(FirebasePaths.claimInfo(claimId)).get().await().getValue(ClaimInfo::class.java)

    /** Idempotently imports a legacy request.  Existing claim IDs are never
     * overwritten, which makes an interrupted migration safe to re-run. */
    suspend fun importLegacy(info: ClaimInfo, onSupabaseResult: (Boolean) -> Unit = {}): Boolean {
        val id = info.claimId
        require(id.matches(Regex("claim_[0-9]{13}"))) { "Invalid migrated claim ID" }
        if (get(id) != null) return false
        val writes = mutableMapOf<String, Any?>(
            FirebasePaths.claimInfo(id) to info,
            "${FirebasePaths.claimsByBranch(info.branchId)}/$id" to true
        )
        // Legacy rows may predate system_id existing on the profile; don't index
        // what we don't have rather than writing a blank-keyed entry.
        if (info.agentSystemId.isNotBlank()) writes["${FirebasePaths.claimsBySystemId(info.agentSystemId)}/$id"] = true
        db.reference.updateChildren(writes).await()
        SupabaseClaimsWriter.mirror(info, onSupabaseResult)
        return true
    }

    suspend fun update(claimId: String, updates: Map<String, Any?>, onSupabaseResult: (Boolean) -> Unit = {}): ClaimInfo {
        val old = get(claimId) ?: error("Claim not found")
        val branchId = updates["branchId"] as? String ?: old.branchId
        val systemId = updates["agentSystemId"] as? String ?: old.agentSystemId
        val all = updates.toMutableMap().apply { put("updatedAt", System.currentTimeMillis()) }
        if (branchId != old.branchId) {
            all["${FirebasePaths.claimsByBranch(old.branchId)}/$claimId"] = null
            all["${FirebasePaths.claimsByBranch(branchId)}/$claimId"] = true
        }
        if (systemId != old.agentSystemId) {
            if (old.agentSystemId.isNotBlank()) all["${FirebasePaths.claimsBySystemId(old.agentSystemId)}/$claimId"] = null
            if (systemId.isNotBlank()) all["${FirebasePaths.claimsBySystemId(systemId)}/$claimId"] = true
        }
        // Field updates must be rooted at the canonical document; index changes
        // above are the only duplicated writes.
        val rooted = all.mapKeys { (key, _) ->
            if (key.startsWith("claims/")) key else "${FirebasePaths.claimInfo(claimId)}/$key"
        }
        db.reference.updateChildren(rooted).await()
        val updated = get(claimId) ?: error("Claim disappeared after update")
        // Same posture as create() above — best-effort, never blocks/fails the update.
        SupabaseClaimsWriter.mirror(updated, onSupabaseResult)
        return updated
    }

    suspend fun search(filter: ClaimsReportFilter): ClaimsReport = coroutineScope {
        require(filter.branchIds.isNotEmpty()) { "Select at least one branch" }
        require(filter.fromMillis in 1..filter.toMillis) { "Invalid date range" }
        val start = claimId(filter.fromMillis)
        val end = claimId(filter.toMillis)
        val ids = filter.branchIds.map { branchId ->
            async {
                db.reference.child(FirebasePaths.claimsByBranch(branchId))
                    .orderByKey().startAt(start).endAt(end).get().await()
                    .children.mapNotNull { it.key }
            }
        }.awaitAll().flatten().distinct()
        val loaded = ids.map { id -> async { get(id) } }.awaitAll().filterNotNull()
        val filtered = loaded.asSequence()
            .filter { filter.systemIds.isEmpty() || it.agentSystemId in filter.systemIds }
            .filter { filter.types.isEmpty() || it.type in filter.types }
            .filter { filter.categories.isEmpty() || it.category in filter.categories }
            .filter { filter.statuses.isEmpty() || it.status in filter.statuses }
            .sortedBy { it.claimId }
            .let { if (filter.newestFirst) it.toList().asReversed() else it.toList() }
        ClaimsReport(filtered, filter)
    }

    suspend fun searchMyClaims(systemId: String, fromMillis: Long, toMillis: Long, newestFirst: Boolean = true): ClaimsReport = coroutineScope {
        require(systemId.isNotBlank()) { "System ID is required" }
        val filter = ClaimsReportFilter(emptySet(), setOf(systemId), fromMillis, toMillis, newestFirst = newestFirst)
        val ids = db.reference.child(FirebasePaths.claimsBySystemId(systemId))
            .orderByKey().startAt(claimId(fromMillis)).endAt(claimId(toMillis)).get().await()
            .children.mapNotNull { it.key }
        val claims = ids.map { id -> async { get(id) } }.awaitAll().filterNotNull()
            .sortedBy { it.claimId }.let { if (newestFirst) it.asReversed() else it }
        ClaimsReport(claims, filter)
    }

    /** One-time backfill: claims_by_employeeId (old, space-unsafe key) -> claims_by_systemId.
     *  dryRun=true writes nothing and only reports what *would* happen — run this first.
     *  Idempotent: safe to re-run (e.g. after a partial/interrupted run) since it always
     *  re-derives the same mapping and re-writes the same values.
     *  The old claims_by_employeeId index is left untouched either way; deleting it is a
     *  separate, deliberate step once the new index has been spot-checked. */
    suspend fun migrateEmployeeIndexToSystemId(dryRun: Boolean): EmployeeIndexMigrationResult {
        val usersSnap = db.reference.child("users").get().await()
        val employeeIdToSystemId = mutableMapOf<String, String>()
        usersSnap.children.forEach { u ->
            val info = u.child("profile/company_info")
            val empId = info.child("employee_id").getValue(String::class.java)?.trim().orEmpty()
            val sysId = info.child("system_id").getValue(String::class.java)?.trim().orEmpty()
            if (empId.isNotBlank() && sysId.isNotBlank()) employeeIdToSystemId[empId] = sysId
        }

        val oldIndexSnap = db.reference.child("claims/indexes/claims_by_employeeId").get().await()
        val updates = mutableMapOf<String, Any?>()
        var matched = 0
        val unresolved = mutableListOf<Pair<String, String>>()
        oldIndexSnap.children.forEach { employeeGroup ->
            val oldEmployeeId = employeeGroup.key.orEmpty()
            val systemId = employeeIdToSystemId[oldEmployeeId]
            employeeGroup.children.forEach { claimEntry ->
                val claimId = claimEntry.key.orEmpty()
                if (systemId.isNullOrBlank()) {
                    unresolved += oldEmployeeId to claimId
                    return@forEach
                }
                matched++
                if (!dryRun) {
                    updates["claims/$claimId/info/agentSystemId"] = systemId
                    updates["${FirebasePaths.claimsBySystemId(systemId)}/$claimId"] = true
                }
            }
        }
        if (!dryRun && updates.isNotEmpty()) db.reference.updateChildren(updates).await()
        return EmployeeIndexMigrationResult(dryRun, matched, unresolved)
    }

    /** Deletes the old claims_by_employeeId index outright. Deliberately a separate call from
     *  migrateEmployeeIndexToSystemId — bundling delete into the migration would mean a bug in
     *  the migration's mapping has no way back. Call this only after spot-checking the new
     *  claims_by_systemId index looks right. */
    suspend fun deleteOldEmployeeIndex() {
        db.reference.child("claims/indexes/claims_by_employeeId").removeValue().await()
    }

    companion object {
        fun claimId(timestamp: Long): String {
            require(timestamp in 1_000_000_000_000L..9_999_999_999_999L) { "Claim timestamp must be 13 digits" }
            return "claim_$timestamp"
        }
    }
}
