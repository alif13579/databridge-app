package com.cloudx.databridge

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/** One-time, idempotent migration from petty_cash/{branch}/requests. */
class ClaimsMigration(
    private val db: FirebaseDatabase = FirebaseDatabase.getInstance(),
    private val repository: ClaimsRepository = ClaimsRepository(db)
) {
    suspend fun migrateAll(): Int {
        val branches = db.reference.child("petty_cash").get().await()
        var migrated = 0
        for (branchNode in branches.children) {
            val branchId = branchNode.key.orEmpty()
            val branchName = db.reference.child(FirebasePaths.branchName(branchId)).get().await()
                .getValue(String::class.java).orEmpty()
            for (node in branchNode.child("requests").children) {
                val old = node.getValue(PettyCashRequest::class.java) ?: continue
                val employeeId = db.reference.child("users/${old.workerUid}/profile/company_info/employee_id").get().await()
                    .getValue(String::class.java).orEmpty()
                // A legacy record without an employee ID cannot satisfy the
                // required employee index; leave it untouched for correction.
                if (employeeId.isBlank()) continue
                val created = (old.createdAt.takeIf { it >= 1_000_000_000_000L } ?: System.currentTimeMillis())
                var keyTimestamp = created
                var id = ClaimsRepository.claimId(keyTimestamp)
                // Preserve the original createdAt inside info; only the
                // chronological key is bumped if two legacy records share a
                // millisecond (or a non-matching claim already uses it).
                while (repository.get(id)?.claimCode?.let { it != old.requestCode } == true) {
                    id = ClaimsRepository.claimId(++keyTimestamp)
                }
                val info = ClaimInfo(
                    claimId = id, claimCode = old.requestCode.ifBlank { "CLM-${id.takeLast(5)}" },
                    branchId = branchId, branchName = branchName, employeeId = employeeId,
                    employeeName = old.workerName, type = old.category, category = old.category,
                    purpose = old.purpose, consignmentId = old.consignmentId, storeName = old.storeName,
                    requestedAmount = old.amount, approvedAmount = old.approvedAmount, settledAmount = old.settledAmount,
                    paymentMethod = old.settledPaymentMethod, transactionId = old.settledTrxId,
                    status = old.status, requestedAt = old.requestedDate, approvedAt = old.pocApprovedAt,
                    settledAt = old.settledAt, createdAt = created, updatedAt = old.updatedAt,
                    workerUid = old.workerUid, workerRole = old.workerRole, storeId = old.storeId,
                    pickupCount = old.pickupCount, priority = old.priority, attachmentUrl = old.attachmentUrl,
                    attachmentName = old.attachmentName, staffByUid = old.staffByUid, staffByName = old.staffByName,
                    staffAt = old.staffAt, staffComment = old.staffComment, pocApprovedByUid = old.pocApprovedByUid,
                    pocApprovedByName = old.pocApprovedByName, pocComment = old.pocComment,
                    settleInProcessByUid = old.settleInProcessByUid, settleInProcessByName = old.settleInProcessByName,
                    settleInProcessAt = old.settleInProcessAt, settledByUid = old.settledByUid,
                    settledByName = old.settledByName, rejectedByUid = old.rejectedByUid,
                    rejectedByName = old.rejectedByName, rejectedAt = old.rejectedAt, rejectReason = old.rejectReason
                )
                if (repository.importLegacy(info)) migrated++
            }
        }
        return migrated
    }
}
