package com.cloudx.databridge

import com.google.firebase.database.IgnoreExtraProperties

/** Canonical, reportable claim document.  It intentionally lives only once. */
@IgnoreExtraProperties
data class AttachmentRef(
    val key: String = "",
    val name: String = "",
    val sizeBytes: Long = 0
)

@IgnoreExtraProperties
data class ClaimInfo(
    val claimId: String = "",
    val claimCode: String = "",
    val branchId: String = "",
    val employeeName: String = "",
    // Canonical unique filter/index key (users/{uid}/profile/company_info/system_id — digits
    // only). HR employee_id is looked up from public.users via this key when needed instead
    // of being stored on the claim itself. See claims_by_systemId in FirebasePaths.
    val agentSystemId: String = "",
    // type dropped (202609040004) — it always mirrored category.
    val category: String = "",
    val purpose: String = "",
    val consignmentId: String = "",
    // Conveyance fields — populated only when category is Pickup or Bulk Delivery
    // (mirrors public.claims' same 6 columns and the same From='Office'-default/
    // store-area-prefill logic already used for the remark-picker's Vehicle/From/
    // To fields — see PettyCashRequestCreateFragment's conveyance-fields UI).
    // Blank/0 for every other category.
    val vehicle: String = "",
    val fromArea: String = "",
    val toArea: String = "",
    val attemptQuantity: Int = 0,
    val deliveredQuantity: Int = 0,
    val cidOrMerchant: String = "",
    val requestedAmount: Double = 0.0,
    val approvedAmount: Double = 0.0,
    val settledAmount: Double = 0.0,
    val paymentMethod: String = "",
    val transactionId: String = "",
    val status: String = PC_STATUS_PENDING,
    val requestedAt: Long = 0L,
    val approvedAt: Long = 0L,
    val settledAt: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    // Detail/audit fields are not copied to indexes; they remain part of the
    // single canonical info document.
    val workerUid: String = "",
    val workerRole: String = "",
    val storeId: String = "",
    val storeName: String = "",
    val pickupCount: Int = 0,
    val priority: String = PC_PRIORITY_NORMAL,
    val attachmentUrl: String = "",
    val attachmentName: String = "",
    // Multi-attachment (new writes). Legacy single attachment_* above stay
    // for old rows — see allAttachments for the merged view.
    val attachments: List<AttachmentRef> = emptyList(),
    val staffByUid: String = "", val staffBySystemId: String = "", val staffByName: String = "", val staffAt: Long = 0L, val staffComment: String = "",
    val pocApprovedByUid: String = "", val pocApprovedBySystemId: String = "", val pocApprovedByName: String = "", val pocComment: String = "",
    val settleInProcessByUid: String = "", val settleInProcessBySystemId: String = "", val settleInProcessByName: String = "", val settleInProcessAt: Long = 0L,
    val settledByUid: String = "", val settledBySystemId: String = "", val settledByName: String = "",
    val rejectedByUid: String = "", val rejectedBySystemId: String = "", val rejectedByName: String = "", val rejectedAt: Long = 0L, val rejectReason: String = ""
)

/** Result of the one-time claims_by_employeeId -> claims_by_systemId backfill.
 *  unresolved holds (employeeId, claimId) pairs where no current user profile's
 *  company_info/system_id matched that employeeId (e.g. employee no longer exists) —
 *  surfaced rather than silently dropped, so nothing gets lost quietly. */
data class EmployeeIndexMigrationResult(
    val dryRun: Boolean,
    val matched: Int,
    val unresolved: List<Pair<String, String>>
)

/** Merged attachment view: new multi-list plus the legacy single file
 *  (old rows), de-duplicated by key. */
val ClaimInfo.allAttachments: List<AttachmentRef>
    get() = attachments +
        if (attachmentUrl.isNotBlank() && attachments.none { it.key == attachmentUrl })
            listOf(AttachmentRef(attachmentUrl, attachmentName.ifBlank { "attachment" }))
        else emptyList()

/** Same merged view for the UI model (asPettyCashRequest carries both). */
val PettyCashRequest.allAttachments: List<AttachmentRef>
    get() = attachments +
        if (attachmentUrl.isNotBlank() && attachments.none { it.key == attachmentUrl })
            listOf(AttachmentRef(attachmentUrl, attachmentName.ifBlank { "attachment" }))
        else emptyList()

data class ClaimsReportFilter(
    val branchIds: Set<String>,
    val systemIds: Set<String> = emptySet(),
    val fromMillis: Long,
    val toMillis: Long,
    val categories: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet(),
    val newestFirst: Boolean = true
)

data class ClaimsReport(
    val claims: List<ClaimInfo>,
    val filter: ClaimsReportFilter
) {
    val totalRequests get() = claims.size
    val totalRequested get() = claims.sumOf { it.requestedAmount }
    val totalApproved get() = claims.sumOf { it.approvedAmount }
    val totalSettled get() = claims.sumOf { it.settledAmount }
    val totalPending get() = claims.count { it.status == PC_STATUS_PENDING || it.status == PC_STATUS_ACKNOWLEDGED || it.status == PC_STATUS_APPROVED || it.status == PC_STATUS_SETTLE_IN_PROCESS }
    val totalRejected get() = claims.count { it.status == PC_STATUS_REJECTED }
    val totalCancelled get() = claims.count { it.status == PC_STATUS_CANCELLED }
    val byCategory get() = claims.groupBy { it.category.ifBlank { "Other" } }
}

/** Temporary presentation adapter: existing Petty Cash screens can consume
 * v2 claims while the report UI is introduced incrementally. */
fun ClaimInfo.asPettyCashRequest(): PettyCashRequest = PettyCashRequest(
    id = claimId, branchId = branchId, requestCode = claimCode, workerUid = workerUid,
    workerName = employeeName, workerRole = workerRole, category = category,
    consignmentId = consignmentId, storeId = storeId, storeName = storeName,
    pickupCount = pickupCount, vehicle = vehicle, fromArea = fromArea, toArea = toArea,
    attemptQuantity = attemptQuantity, deliveredQuantity = deliveredQuantity, cidOrMerchant = cidOrMerchant,
    purpose = purpose, amount = requestedAmount, priority = priority,
    attachmentUrl = attachmentUrl, attachmentName = attachmentName, attachments = attachments,
    requestedDate = requestedAt,
    status = status, createdAt = createdAt, updatedAt = updatedAt,
    staffByUid = staffByUid, staffByName = staffByName, staffAt = staffAt, staffComment = staffComment,
    pocApprovedByUid = pocApprovedByUid, pocApprovedByName = pocApprovedByName,
    pocApprovedAt = approvedAt, pocComment = pocComment, approvedAmount = approvedAmount,
    settleInProcessByUid = settleInProcessByUid, settleInProcessByName = settleInProcessByName,
    settleInProcessAt = settleInProcessAt, settledAmount = settledAmount,
    settledByUid = settledByUid, settledByName = settledByName, settledAt = settledAt,
    settledPaymentMethod = paymentMethod, settledTrxId = transactionId,
    rejectedByUid = rejectedByUid, rejectedByName = rejectedByName, rejectedAt = rejectedAt,
    rejectReason = rejectReason
)
