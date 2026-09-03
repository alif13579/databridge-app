package com.cloudx.databridge

import com.google.firebase.database.FirebaseDatabase
// The imports below are only referenced inside the commented-out Firebase
// blocks; kept so those blocks remain valid Kotlin if ever uncommented.
// import kotlinx.coroutines.async
// import kotlinx.coroutines.awaitAll
// import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/**
 * Claims v2 schema — Supabase's public.claims is now the sole persistence
 * layer for both save and read (full cutover, both directions, in one push —
 * a partial cutover would have broken PettyCashViewModel's get()/search()
 * calls, since they'd have kept reading an index that new writes no longer
 * populate). Every FirebaseDatabase-based implementation below is commented
 * out with `//`, not deleted, so it can be restored by uncommenting if ever
 * needed — see SupabaseClaimsWriter.save() / SupabaseClaimsReader's
 * getById()/search()/searchMyClaims() for the replacements.
 *
 * [db] stays as a constructor param — PettyCashViewModel still constructs
 * this as ClaimsRepository(db), and migrateEmployeeIndexToSystemId()/
 * deleteOldEmployeeIndex() at the bottom are still Firebase-only (they
 * migrate/clean up an old Firebase-only index, unrelated to claim
 * save/read, so out of scope for this cutover).
 */
class ClaimsRepository(private val db: FirebaseDatabase = FirebaseDatabase.getInstance()) {

    suspend fun create(info: ClaimInfo, onSupabaseResult: (Boolean) -> Unit = {}): ClaimInfo {
        require(info.branchId.isNotBlank()) { "A branch is required" }
        require(info.agentSystemId.isNotBlank()) { "An agent system ID is required" }
        var timestamp = System.currentTimeMillis()
        var id = claimId(timestamp)
        // A millisecond key is chronological and normally unique. Guard the
        // extremely rare same-ms collision — checked against Supabase now
        // (post-cutover); see the commented Firebase version just below.
        while (SupabaseClaimsReader.getById(id) != null) {
            timestamp++
            id = claimId(timestamp)
        }
        // --- FIREBASE (pre-cutover; commented, not deleted) ---
        // while (db.reference.child(FirebasePaths.claimInfo(id)).get().await().exists()) {
        //     timestamp++
        //     id = claimId(timestamp)
        // }
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
        // Supabase is now the sole persistence layer — save() throws on
        // failure, so callers' existing runCatching {} (see PettyCashViewModel)
        // already surfaces it correctly; onSupabaseResult now only ever fires
        // true, since a failure throws before reaching it.
        SupabaseClaimsWriter.save(claim)
        onSupabaseResult(true)
        // --- FIREBASE (pre-cutover; commented, not deleted) ---
        // db.reference.updateChildren(mapOf(
        //     FirebasePaths.claimInfo(id) to claim,
        //     "${FirebasePaths.claimsByBranch(claim.branchId)}/$id" to true,
        //     "${FirebasePaths.claimsBySystemId(claim.agentSystemId)}/$id" to true
        // )).await()
        // // Firebase is the source of truth and already has the write; Supabase is
        // // a best-effort alternative copy — never blocks or fails claim creation.
        // SupabaseClaimsWriter.mirror(claim, onSupabaseResult)
        return claim
    }

    suspend fun get(claimId: String): ClaimInfo? {
        return SupabaseClaimsReader.getById(claimId)
        // --- FIREBASE (pre-cutover; commented, not deleted) ---
        // return db.reference.child(FirebasePaths.claimInfo(claimId)).get().await().getValue(ClaimInfo::class.java)
    }

    /** Idempotently imports a legacy request.  Existing claim IDs are never
     * overwritten, which makes an interrupted migration safe to re-run. */
    suspend fun importLegacy(info: ClaimInfo, onSupabaseResult: (Boolean) -> Unit = {}): Boolean {
        val id = info.claimId
        require(id.matches(Regex("claim_[0-9]{13}"))) { "Invalid migrated claim ID" }
        if (get(id) != null) return false
        SupabaseClaimsWriter.save(info)
        onSupabaseResult(true)
        // --- FIREBASE (pre-cutover; commented, not deleted) ---
        // val writes = mutableMapOf<String, Any?>(
        //     FirebasePaths.claimInfo(id) to info,
        //     "${FirebasePaths.claimsByBranch(info.branchId)}/$id" to true
        // )
        // // Legacy rows may predate system_id existing on the profile; don't index
        // // what we don't have rather than writing a blank-keyed entry.
        // if (info.agentSystemId.isNotBlank()) writes["${FirebasePaths.claimsBySystemId(info.agentSystemId)}/$id"] = true
        // db.reference.updateChildren(writes).await()
        // SupabaseClaimsWriter.mirror(info, onSupabaseResult)
        return true
    }

    suspend fun update(claimId: String, updates: Map<String, Any?>, onSupabaseResult: (Boolean) -> Unit = {}): ClaimInfo {
        val old = get(claimId) ?: error("Claim not found")
        // public.claims is written by full-row upsert (see SupabaseClaimsWriter.
        // save() / claim_upsert in the Edge Function), not a partial patch like
        // Firebase's updateChildren() — so the caller's partial map is applied
        // onto the already-loaded full claim first (applyUpdates), then the
        // whole row is saved. updatedAt is always refreshed to "now" after,
        // regardless of whether the caller's map included it — same as
        // Firebase's version always did (see the commented block below).
        val updated = applyUpdates(old, updates).copy(updatedAt = System.currentTimeMillis())
        SupabaseClaimsWriter.save(updated)
        onSupabaseResult(true)
        // --- FIREBASE (pre-cutover; commented, not deleted) ---
        // val branchId = updates["branchId"] as? String ?: old.branchId
        // val systemId = updates["agentSystemId"] as? String ?: old.agentSystemId
        // val all = updates.toMutableMap().apply { put("updatedAt", System.currentTimeMillis()) }
        // if (branchId != old.branchId) {
        //     all["${FirebasePaths.claimsByBranch(old.branchId)}/$claimId"] = null
        //     all["${FirebasePaths.claimsByBranch(branchId)}/$claimId"] = true
        // }
        // if (systemId != old.agentSystemId) {
        //     if (old.agentSystemId.isNotBlank()) all["${FirebasePaths.claimsBySystemId(old.agentSystemId)}/$claimId"] = null
        //     if (systemId.isNotBlank()) all["${FirebasePaths.claimsBySystemId(systemId)}/$claimId"] = true
        // }
        // // Field updates must be rooted at the canonical document; index changes
        // // above are the only duplicated writes.
        // val rooted = all.mapKeys { (key, _) ->
        //     if (key.startsWith("claims/")) key else "${FirebasePaths.claimInfo(claimId)}/$key"
        // }
        // db.reference.updateChildren(rooted).await()
        // val updatedFromFirebase = get(claimId) ?: error("Claim disappeared after update")
        // // Same posture as create() above — best-effort, never blocks/fails the update.
        // SupabaseClaimsWriter.mirror(updatedFromFirebase, onSupabaseResult)
        return updated
    }

    suspend fun search(filter: ClaimsReportFilter): ClaimsReport {
        return SupabaseClaimsReader.search(filter)
        // --- FIREBASE (pre-cutover; commented, not deleted) ---
        // return coroutineScope {
        //     require(filter.branchIds.isNotEmpty()) { "Select at least one branch" }
        //     require(filter.fromMillis in 1..filter.toMillis) { "Invalid date range" }
        //     val start = claimId(filter.fromMillis)
        //     val end = claimId(filter.toMillis)
        //     val ids = filter.branchIds.map { branchId ->
        //         async {
        //             db.reference.child(FirebasePaths.claimsByBranch(branchId))
        //                 .orderByKey().startAt(start).endAt(end).get().await()
        //                 .children.mapNotNull { it.key }
        //         }
        //     }.awaitAll().flatten().distinct()
        //     val loaded = ids.map { id -> async { get(id) } }.awaitAll().filterNotNull()
        //     val filtered = loaded.asSequence()
        //         .filter { filter.systemIds.isEmpty() || it.agentSystemId in filter.systemIds }
        //         .filter { filter.types.isEmpty() || it.type in filter.types }
        //         .filter { filter.categories.isEmpty() || it.category in filter.categories }
        //         .filter { filter.statuses.isEmpty() || it.status in filter.statuses }
        //         .sortedBy { it.claimId }
        //         .let { if (filter.newestFirst) it.toList().asReversed() else it.toList() }
        //     ClaimsReport(filtered, filter)
        // }
    }

    suspend fun searchMyClaims(systemId: String, fromMillis: Long, toMillis: Long, newestFirst: Boolean = true): ClaimsReport {
        return SupabaseClaimsReader.searchMyClaims(systemId, fromMillis, toMillis, newestFirst)
        // --- FIREBASE (pre-cutover; commented, not deleted) ---
        // return coroutineScope {
        //     require(systemId.isNotBlank()) { "System ID is required" }
        //     val filter = ClaimsReportFilter(emptySet(), setOf(systemId), fromMillis, toMillis, newestFirst = newestFirst)
        //     val ids = db.reference.child(FirebasePaths.claimsBySystemId(systemId))
        //         .orderByKey().startAt(claimId(fromMillis)).endAt(claimId(toMillis)).get().await()
        //         .children.mapNotNull { it.key }
        //     val claims = ids.map { id -> async { get(id) } }.awaitAll().filterNotNull()
        //         .sortedBy { it.claimId }.let { if (newestFirst) it.asReversed() else it }
        //     ClaimsReport(claims, filter)
        // }
    }

    /** Applies a Firebase-style partial update map onto a full ClaimInfo,
     *  producing the complete row Supabase's full-row upsert needs (see
     *  ClaimsRepository.update() / SupabaseClaimsWriter.save() — a claim_upsert
     *  always writes every column, so a partial claim would blank out
     *  whatever's missing). Covers every key ClaimsRepository.update() is
     *  actually called with across the app (PettyCashViewModel) as of this
     *  cutover; a key not listed here is ignored, same as it would have been
     *  under the old Firebase updateChildren() path if it weren't a real
     *  ClaimInfo field. */
    @Suppress("UNCHECKED_CAST")
    private fun applyUpdates(old: ClaimInfo, updates: Map<String, Any?>): ClaimInfo {
        var c = old
        updates["branchId"]?.let { c = c.copy(branchId = it as String) }
        updates["agentSystemId"]?.let { c = c.copy(agentSystemId = it as String) }
        updates["status"]?.let { c = c.copy(status = it as String) }
        updates["category"]?.let { c = c.copy(category = it as String) }
        updates["purpose"]?.let { c = c.copy(purpose = it as String) }
        updates["consignmentId"]?.let { c = c.copy(consignmentId = it as String) }
        updates["storeId"]?.let { c = c.copy(storeId = it as String) }
        updates["storeName"]?.let { c = c.copy(storeName = it as String) }
        updates["pickupCount"]?.let { c = c.copy(pickupCount = it as Int) }
        updates["vehicle"]?.let { c = c.copy(vehicle = it as String) }
        updates["fromArea"]?.let { c = c.copy(fromArea = it as String) }
        updates["toArea"]?.let { c = c.copy(toArea = it as String) }
        updates["attemptQuantity"]?.let { c = c.copy(attemptQuantity = it as Int) }
        updates["deliveredQuantity"]?.let { c = c.copy(deliveredQuantity = it as Int) }
        updates["cidOrMerchant"]?.let { c = c.copy(cidOrMerchant = it as String) }
        updates["requestedAmount"]?.let { c = c.copy(requestedAmount = it as Double) }
        updates["approvedAmount"]?.let { c = c.copy(approvedAmount = it as Double) }
        updates["settledAmount"]?.let { c = c.copy(settledAmount = it as Double) }
        updates["paymentMethod"]?.let { c = c.copy(paymentMethod = it as String) }
        updates["transactionId"]?.let { c = c.copy(transactionId = it as String) }
        updates["requestedAt"]?.let { c = c.copy(requestedAt = it as Long) }
        updates["approvedAt"]?.let { c = c.copy(approvedAt = it as Long) }
        updates["settledAt"]?.let { c = c.copy(settledAt = it as Long) }
        updates["staffByUid"]?.let { c = c.copy(staffByUid = it as String) }
        updates["staffBySystemId"]?.let { c = c.copy(staffBySystemId = it as String) }
        updates["staffByName"]?.let { c = c.copy(staffByName = it as String) }
        updates["staffAt"]?.let { c = c.copy(staffAt = it as Long) }
        updates["staffComment"]?.let { c = c.copy(staffComment = it as String) }
        updates["pocApprovedByUid"]?.let { c = c.copy(pocApprovedByUid = it as String) }
        updates["pocApprovedBySystemId"]?.let { c = c.copy(pocApprovedBySystemId = it as String) }
        updates["pocApprovedByName"]?.let { c = c.copy(pocApprovedByName = it as String) }
        updates["pocComment"]?.let { c = c.copy(pocComment = it as String) }
        updates["settleInProcessByUid"]?.let { c = c.copy(settleInProcessByUid = it as String) }
        updates["settleInProcessBySystemId"]?.let { c = c.copy(settleInProcessBySystemId = it as String) }
        updates["settleInProcessByName"]?.let { c = c.copy(settleInProcessByName = it as String) }
        updates["settleInProcessAt"]?.let { c = c.copy(settleInProcessAt = it as Long) }
        updates["settledByUid"]?.let { c = c.copy(settledByUid = it as String) }
        updates["settledBySystemId"]?.let { c = c.copy(settledBySystemId = it as String) }
        updates["settledByName"]?.let { c = c.copy(settledByName = it as String) }
        updates["rejectedByUid"]?.let { c = c.copy(rejectedByUid = it as String) }
        updates["rejectedBySystemId"]?.let { c = c.copy(rejectedBySystemId = it as String) }
        updates["rejectedByName"]?.let { c = c.copy(rejectedByName = it as String) }
        updates["rejectedAt"]?.let { c = c.copy(rejectedAt = it as Long) }
        updates["rejectReason"]?.let { c = c.copy(rejectReason = it as String) }
        return c
    }

    // ── Below: unchanged, Firebase-only utilities for the old
    // claims_by_employeeId index — unrelated to claim save/read (they migrate
    // and clean up an old Firebase-only index), so out of scope for the
    // Supabase cutover above; still Firebase since that IS the index being
    // migrated. ──────────────────────────────────────────────────────────────

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
