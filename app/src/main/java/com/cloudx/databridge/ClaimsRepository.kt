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

    suspend fun create(info: ClaimInfo): ClaimInfo {
        require(info.branchId.isNotBlank()) { "A branch is required" }
        require(info.employeeId.isNotBlank()) { "An employee ID is required" }
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
            "${FirebasePaths.claimsByEmployee(claim.employeeId)}/$id" to true
        )).await()
        return claim
    }

    suspend fun get(claimId: String): ClaimInfo? =
        db.reference.child(FirebasePaths.claimInfo(claimId)).get().await().getValue(ClaimInfo::class.java)

    /** Idempotently imports a legacy request.  Existing claim IDs are never
     * overwritten, which makes an interrupted migration safe to re-run. */
    suspend fun importLegacy(info: ClaimInfo): Boolean {
        val id = info.claimId
        require(id.matches(Regex("claim_[0-9]{13}"))) { "Invalid migrated claim ID" }
        if (get(id) != null) return false
        db.reference.updateChildren(mapOf(
            FirebasePaths.claimInfo(id) to info,
            "${FirebasePaths.claimsByBranch(info.branchId)}/$id" to true,
            "${FirebasePaths.claimsByEmployee(info.employeeId)}/$id" to true
        )).await()
        return true
    }

    suspend fun update(claimId: String, updates: Map<String, Any?>): ClaimInfo {
        val old = get(claimId) ?: error("Claim not found")
        val branchId = updates["branchId"] as? String ?: old.branchId
        val employeeId = updates["employeeId"] as? String ?: old.employeeId
        val all = updates.toMutableMap().apply { put("updatedAt", System.currentTimeMillis()) }
        if (branchId != old.branchId) {
            all["${FirebasePaths.claimsByBranch(old.branchId)}/$claimId"] = null
            all["${FirebasePaths.claimsByBranch(branchId)}/$claimId"] = true
        }
        if (employeeId != old.employeeId) {
            all["${FirebasePaths.claimsByEmployee(old.employeeId)}/$claimId"] = null
            all["${FirebasePaths.claimsByEmployee(employeeId)}/$claimId"] = true
        }
        // Field updates must be rooted at the canonical document; index changes
        // above are the only duplicated writes.
        val rooted = all.mapKeys { (key, _) ->
            if (key.startsWith("claims/")) key else "${FirebasePaths.claimInfo(claimId)}/$key"
        }
        db.reference.updateChildren(rooted).await()
        return get(claimId) ?: error("Claim disappeared after update")
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
            .filter { filter.employeeIds.isEmpty() || it.employeeId in filter.employeeIds }
            .filter { filter.types.isEmpty() || it.type in filter.types }
            .filter { filter.categories.isEmpty() || it.category in filter.categories }
            .filter { filter.statuses.isEmpty() || it.status in filter.statuses }
            .sortedBy { it.claimId }
            .let { if (filter.newestFirst) it.toList().asReversed() else it.toList() }
        ClaimsReport(filtered, filter)
    }

    suspend fun searchMyClaims(employeeId: String, fromMillis: Long, toMillis: Long, newestFirst: Boolean = true): ClaimsReport = coroutineScope {
        require(employeeId.isNotBlank()) { "Employee ID is required" }
        val filter = ClaimsReportFilter(emptySet(), setOf(employeeId), fromMillis, toMillis, newestFirst = newestFirst)
        val ids = db.reference.child(FirebasePaths.claimsByEmployee(employeeId))
            .orderByKey().startAt(claimId(fromMillis)).endAt(claimId(toMillis)).get().await()
            .children.mapNotNull { it.key }
        val claims = ids.map { id -> async { get(id) } }.awaitAll().filterNotNull()
            .sortedBy { it.claimId }.let { if (newestFirst) it.asReversed() else it }
        ClaimsReport(claims, filter)
    }

    companion object {
        fun claimId(timestamp: Long): String {
            require(timestamp in 1_000_000_000_000L..9_999_999_999_999L) { "Claim timestamp must be 13 digits" }
            return "claim_$timestamp"
        }
    }
}
