package com.cloudx.databridge

/**
 * Claims v2 — Supabase's public.claims is the sole persistence layer for
 * both save and read. See SupabaseClaimsWriter.save() for writes and
 * SupabaseClaimsReader's getById()/search()/searchMyClaims() for reads.
 *
 * The one-time Firebase employee-index migration tool lives separately in
 * FirebaseClaimsIndexMigration (an admin utility, not part of the live
 * claim flow).
 */
class ClaimsRepository {

    suspend fun create(info: ClaimInfo, onSupabaseResult: (Boolean) -> Unit = {}): ClaimInfo {
        require(info.branchId.isNotBlank()) { "A branch is required" }
        require(info.agentSystemId.isNotBlank()) { "An agent system ID is required" }
        var timestamp = System.currentTimeMillis()
        var id = claimId(timestamp)
        // A millisecond key is chronological and normally unique. Guard the
        // extremely rare same-ms collision against Supabase.
        while (SupabaseClaimsReader.getById(id) != null) {
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
        // save() throws on failure, so callers' existing runCatching {} (see
        // PettyCashViewModel) already surfaces it correctly;
        // onSupabaseResult only ever fires true, since a failure throws
        // before reaching it.
        SupabaseClaimsWriter.save(claim)
        onSupabaseResult(true)
        return claim
    }

    suspend fun get(claimId: String): ClaimInfo? {
        return SupabaseClaimsReader.getById(claimId)
    }

    /** Idempotently imports a legacy request.  Existing claim IDs are never
     * overwritten, which makes an interrupted migration safe to re-run. */
    suspend fun importLegacy(info: ClaimInfo, onSupabaseResult: (Boolean) -> Unit = {}): Boolean {
        val id = info.claimId
        require(id.matches(Regex("claim_[0-9]{13}"))) { "Invalid migrated claim ID" }
        if (get(id) != null) return false
        SupabaseClaimsWriter.save(info)
        onSupabaseResult(true)
        return true
    }

    suspend fun update(claimId: String, updates: Map<String, Any?>, onSupabaseResult: (Boolean) -> Unit = {}): ClaimInfo {        val old = get(claimId) ?: error("Claim not found")
        // public.claims is written by full-row upsert (see SupabaseClaimsWriter.
        // save() / claim_upsert in the Edge Function), not a partial patch —
        // so the caller's partial map is applied onto the already-loaded full
        // claim first (applyUpdates), then the whole row is saved. updatedAt
        // is always refreshed to "now" after, regardless of whether the
        // caller's map included it.
        val updated = applyUpdates(old, updates).copy(updatedAt = System.currentTimeMillis())
        SupabaseClaimsWriter.save(updated)
        onSupabaseResult(true)
        return updated
    }

    /** Hard delete: the Supabase row goes away entirely (claim_delete, owner +
     *  pending gated server-side), then every R2 attachment object is purged
     *  best-effort. R2 first so a row-delete failure never strands files with
     *  no row pointing at them unnoticed — a row failure still throws (caller
     *  retries; R2 deletes are idempotent). Returns the purged attachment keys. */
    suspend fun delete(claimId: String, onSupabaseResult: (Boolean) -> Unit = {}): List<String> {
        val existing = get(claimId) ?: error("Request not found")
        val keys = existing.attachments.map { it.key }.filter { it.isNotBlank() }
        keys.forEach { key ->
            runCatching { AttachmentUploader.deleteObject(key) }
        }
        SupabaseClaimsWriter.delete(claimId)
        onSupabaseResult(true)
        return keys
    }

    suspend fun search(filter: ClaimsReportFilter): ClaimsReport {
        return SupabaseClaimsReader.search(filter)
    }

    suspend fun searchMyClaims(systemId: String, fromMillis: Long, toMillis: Long, newestFirst: Boolean = true): ClaimsReport {
        return SupabaseClaimsReader.searchMyClaims(systemId, fromMillis, toMillis, newestFirst)
    }

    /** Applies a partial update map onto a full ClaimInfo, producing the
     *  complete row Supabase's full-row upsert needs (see update() /
     *  SupabaseClaimsWriter.save() — a claim_upsert always writes every
     *  column, so a partial claim would blank out whatever's missing).
     *  Covers every key update() is actually called with across the app
     *  (PettyCashViewModel); a key not listed here is ignored. */
    @Suppress("UNCHECKED_CAST")
    private fun applyUpdates(old: ClaimInfo, updates: Map<String, Any?>): ClaimInfo {
        var c = old
        updates["branchId"]?.let { c = c.copy(branchId = it as String) }
        updates["agentSystemId"]?.let { c = c.copy(agentSystemId = it as String) }
        updates["status"]?.let { c = c.copy(status = it as String) }
        updates["category"]?.let { c = c.copy(category = it as String) }
        updates["consignmentId"]?.let { c = c.copy(consignmentId = it as String) }
        updates["storeId"]?.let { c = c.copy(storeId = it as String) }
        updates["storeName"]?.let { c = c.copy(storeName = it as String) }
        updates["pickupCount"]?.let { c = c.copy(pickupCount = it as Int) }
        updates["purpose"]?.let { c = c.copy(purpose = it as String) }
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
        updates["verifiedByUid"]?.let { c = c.copy(verifiedByUid = it as String) }
        updates["verifiedBySystemId"]?.let { c = c.copy(verifiedBySystemId = it as String) }
        updates["verifiedByName"]?.let { c = c.copy(verifiedByName = it as String) }
        updates["verifiedAt"]?.let { c = c.copy(verifiedAt = it as Long) }
        updates["verifiedComment"]?.let { c = c.copy(verifiedComment = it as String) }
        updates["approvedByUid"]?.let { c = c.copy(approvedByUid = it as String) }
        updates["approvedBySystemId"]?.let { c = c.copy(approvedBySystemId = it as String) }
        updates["approvedByName"]?.let { c = c.copy(approvedByName = it as String) }
        updates["approvedComment"]?.let { c = c.copy(approvedComment = it as String) }
        updates["readyToSettleByUid"]?.let { c = c.copy(readyToSettleByUid = it as String) }
        updates["readyToSettleBySystemId"]?.let { c = c.copy(readyToSettleBySystemId = it as String) }
        updates["readyToSettleByName"]?.let { c = c.copy(readyToSettleByName = it as String) }
        updates["readyToSettleAt"]?.let { c = c.copy(readyToSettleAt = it as Long) }
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

    companion object {
        fun claimId(timestamp: Long): String {
            require(timestamp in 1_000_000_000_000L..9_999_999_999_999L) { "Claim timestamp must be 13 digits" }
            return "claim_$timestamp"
        }
    }
}
