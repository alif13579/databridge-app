package com.cloudx.databridge

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * One-way drain: Firebase `claims/...` → Supabase `public.claims`.
 *
 * For every Firebase claim found under `claims/{id}/info`:
 *  1. Save the full row to Supabase (SupabaseClaimsWriter.save — upsert on
 *     id, so re-running never duplicates).
 *  2. Read it straight back (SupabaseClaimsReader.getById) and compare
 *     FIELD BY FIELD ([diffClaims]). Only a 100% match proceeds.
 *  3. Delete the Firebase original: the `claims/{id}` node plus its
 *     branch/systemId/legacy-employee index entries.
 *
 * Anything that fails to copy, or copies but differs on ANY field, is NEVER
 * deleted — it stays in Firebase and is reported (see [MigrateResult]).
 * The common mismatch cause is an actor name: Supabase reconstructs names
 * via the public.users join, so a claim whose actor has no users row yet
 * (never logged into the Supabase-backed app) reads back with a blank name.
 * Fix = that user logs in once (sync_profile creates the row), then re-run —
 * each run only processes what Firebase still holds, so Firebase drains to
 * zero over successive runs.
 *
 * Run from the Accounts-gated "Migrate Firebase Claims" row in
 * PettyCashReportsFragment. Migrates ALL branches (Firebase holds claims
 * globally); safe to re-run any time.
 */
object FirebaseClaimsMigrator {

    data class FieldMismatch(val claimId: String, val fields: List<String>)

    data class MigrateResult(
        val totalFirebase: Int,
        val copied: Int,
        val verified: Int,
        val deleted: Int,
        val mismatched: List<FieldMismatch>,
        val errors: List<String>,
    )

    /**
     * Runs the full drain. [onProgress] fires on a background thread after
     * each claim (done, total) — callers must post to the UI thread
     * themselves.
     */
    suspend fun migrateAll(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): MigrateResult {
        val db = FirebaseDatabase.getInstance().reference
        val root = db.child("claims").get().await()

        // Legacy employee index → claimId to employee-key map, so the old
        // index entries can be cleaned too. Best-effort: a missing index
        // just means nothing to clean there.
        val legacyIndex = mutableMapOf<String, String>()
        root.child("indexes/claims_by_employeeId").children.forEach { group ->
            val empKey = group.key.orEmpty()
            group.children.forEach { entry ->
                entry.key?.let { legacyIndex[it] = empKey }
            }
        }

        val infos = root.children
            .filter { it.key != "indexes" }
            .mapNotNull { snap ->
                val key = snap.key ?: return@mapNotNull null
                val info = runCatching { snap.child("info").getValue(ClaimInfo::class.java) }.getOrNull()
                    ?: return@mapNotNull null
                key to if (info.claimId.isBlank()) info.copy(claimId = key) else info
            }

        var copied = 0
        var verified = 0
        var deleted = 0
        val mismatched = mutableListOf<FieldMismatch>()
        val errors = mutableListOf<String>()

        infos.forEachIndexed { index, (key, info) ->
            try {
                SupabaseClaimsWriter.save(info)
                copied++
                val back = SupabaseClaimsReader.getById(info.claimId)
                    ?: throw IllegalStateException("saved but could not be read back")
                val diff = diffClaims(info, back)
                if (diff.isEmpty()) {
                    verified++
                    runCatching { deleteFirebaseClaim(db, key, info, legacyIndex[info.claimId]) }
                        .onSuccess { deleted++ }
                        .onFailure { errors += "$key: delete failed — ${it.message}" }
                } else {
                    mismatched += FieldMismatch(info.claimId, diff)
                }
            } catch (e: Exception) {
                errors += "$key: ${e.message}"
            }
            onProgress(index + 1, infos.size)
        }
        return MigrateResult(infos.size, copied, verified, deleted, mismatched, errors)
    }

    /** Deletes one Firebase claim and every index entry pointing at it. */
    private suspend fun deleteFirebaseClaim(
        db: com.google.firebase.database.DatabaseReference,
        nodeKey: String,
        info: ClaimInfo,
        legacyEmployeeKey: String?,
    ) {
        val deletes = mutableMapOf<String, Any?>(
            "claims/$nodeKey" to null,
            "${FirebasePaths.claimsByBranch(info.branchId)}/$nodeKey" to null,
        )
        if (info.agentSystemId.isNotBlank()) {
            deletes["${FirebasePaths.claimsBySystemId(info.agentSystemId)}/$nodeKey"] = null
        }
        if (!legacyEmployeeKey.isNullOrBlank()) {
            deletes["${FirebasePaths.claimsByEmployee(legacyEmployeeKey)}/$nodeKey"] = null
        }
        db.updateChildren(deletes).await()
    }

    /**
     * Field-by-field comparison — EVERY ClaimInfo field. Doubles compare
     * exact (Supabase numeric round-trips decimal input losslessly);
     * timestamps compare as millis (ISO-8601 keeps millis precision).
     * Returns the names of differing fields, empty = 100% match.
     */
    fun diffClaims(a: ClaimInfo, b: ClaimInfo): List<String> = buildList {
        if (a.claimId != b.claimId) add("claimId")
        if (a.claimCode != b.claimCode) add("claimCode")
        if (a.branchId != b.branchId) add("branchId")
        if (a.employeeName != b.employeeName) add("employeeName")
        if (a.agentSystemId != b.agentSystemId) add("agentSystemId")
        if (a.type != b.type) add("type")
        if (a.category != b.category) add("category")
        if (a.purpose != b.purpose) add("purpose")
        if (a.consignmentId != b.consignmentId) add("consignmentId")
        if (a.vehicle != b.vehicle) add("vehicle")
        if (a.fromArea != b.fromArea) add("fromArea")
        if (a.toArea != b.toArea) add("toArea")
        if (a.attemptQuantity != b.attemptQuantity) add("attemptQuantity")
        if (a.deliveredQuantity != b.deliveredQuantity) add("deliveredQuantity")
        if (a.cidOrMerchant != b.cidOrMerchant) add("cidOrMerchant")
        if (a.requestedAmount != b.requestedAmount) add("requestedAmount")
        if (a.approvedAmount != b.approvedAmount) add("approvedAmount")
        if (a.settledAmount != b.settledAmount) add("settledAmount")
        if (a.paymentMethod != b.paymentMethod) add("paymentMethod")
        if (a.transactionId != b.transactionId) add("transactionId")
        if (a.status != b.status) add("status")
        if (a.requestedAt != b.requestedAt) add("requestedAt")
        if (a.approvedAt != b.approvedAt) add("approvedAt")
        if (a.settledAt != b.settledAt) add("settledAt")
        if (a.createdAt != b.createdAt) add("createdAt")
        if (a.updatedAt != b.updatedAt) add("updatedAt")
        if (a.workerUid != b.workerUid) add("workerUid")
        if (a.workerRole != b.workerRole) add("workerRole")
        if (a.storeId != b.storeId) add("storeId")
        if (a.storeName != b.storeName) add("storeName")
        if (a.pickupCount != b.pickupCount) add("pickupCount")
        if (a.priority != b.priority) add("priority")
        if (a.attachmentUrl != b.attachmentUrl) add("attachmentUrl")
        if (a.attachmentName != b.attachmentName) add("attachmentName")
        if (a.staffByUid != b.staffByUid) add("staffByUid")
        if (a.staffBySystemId != b.staffBySystemId) add("staffBySystemId")
        if (a.staffByName != b.staffByName) add("staffByName")
        if (a.staffAt != b.staffAt) add("staffAt")
        if (a.staffComment != b.staffComment) add("staffComment")
        if (a.pocApprovedByUid != b.pocApprovedByUid) add("pocApprovedByUid")
        if (a.pocApprovedBySystemId != b.pocApprovedBySystemId) add("pocApprovedBySystemId")
        if (a.pocApprovedByName != b.pocApprovedByName) add("pocApprovedByName")
        if (a.pocComment != b.pocComment) add("pocComment")
        if (a.settleInProcessByUid != b.settleInProcessByUid) add("settleInProcessByUid")
        if (a.settleInProcessBySystemId != b.settleInProcessBySystemId) add("settleInProcessBySystemId")
        if (a.settleInProcessByName != b.settleInProcessByName) add("settleInProcessByName")
        if (a.settleInProcessAt != b.settleInProcessAt) add("settleInProcessAt")
        if (a.settledByUid != b.settledByUid) add("settledByUid")
        if (a.settledBySystemId != b.settledBySystemId) add("settledBySystemId")
        if (a.settledByName != b.settledByName) add("settledByName")
        if (a.rejectedByUid != b.rejectedByUid) add("rejectedByUid")
        if (a.rejectedBySystemId != b.rejectedBySystemId) add("rejectedBySystemId")
        if (a.rejectedByName != b.rejectedByName) add("rejectedByName")
        if (a.rejectedAt != b.rejectedAt) add("rejectedAt")
        if (a.rejectReason != b.rejectReason) add("rejectReason")
    }
}
