package com.cloudx.databridge

import com.google.firebase.database.IgnoreExtraProperties

/**
 * NULL-safe string read for PostgREST/Supabase JSON. org.json's optString()
 * returns the literal string "null" for an explicit JSON null (only a MISSING
 * key yields "") — that "null" then round-trips into DB writes (Edge fk()
 * treats it as a real id → 23503) and into UI text. Use this everywhere a
 * nullable text column is read. (Numbers are unaffected: optDouble/optInt/
 * optLong fall back to their default on NULL.)
 */
fun org.json.JSONObject.optStr(key: String): String =
    if (isNull(key)) "" else optString(key, "")

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
    val requesterUid: String = "",
    val requesterRole: String = "",
    val storeId: String = "",
    val storeName: String = "",
    val pickupCount: Int = 0,
    // Single attachment_url/name + priority columns dropped (202609040006).
    val attachments: List<AttachmentRef> = emptyList(),
    val verifiedByUid: String = "", val verifiedBySystemId: String = "", val verifiedByName: String = "", val verifiedAt: Long = 0L, val verifiedComment: String = "",
    val approvedByUid: String = "", val approvedBySystemId: String = "", val approvedByName: String = "", val approvedComment: String = "",
    val readyToSettleByUid: String = "", val readyToSettleBySystemId: String = "", val readyToSettleByName: String = "", val readyToSettleAt: Long = 0L,
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

/** Attachment list accessor (kept as extension so call sites read uniformly). */
val ClaimInfo.allAttachments: List<AttachmentRef>
    get() = attachments

/** Same view for the UI model (asPettyCashRequest carries the list). */
val PettyCashRequest.allAttachments: List<AttachmentRef>
    get() = attachments

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
    id = claimId, branchId = branchId, requestCode = claimCode, requesterUid = requesterUid,
    requesterName = employeeName, requesterRole = requesterRole, category = category,
    consignmentId = consignmentId, storeId = storeId, storeName = storeName,
    pickupCount = pickupCount, vehicle = vehicle, fromArea = fromArea, toArea = toArea,
    attemptQuantity = attemptQuantity, deliveredQuantity = deliveredQuantity, cidOrMerchant = cidOrMerchant,
    purpose = purpose, amount = requestedAmount,
    attachments = attachments,
    requestedDate = requestedAt,
    status = status, createdAt = createdAt, updatedAt = updatedAt,
    verifiedByUid = verifiedByUid, verifiedByName = verifiedByName, verifiedAt = verifiedAt, verifiedComment = verifiedComment,
    approvedByUid = approvedByUid, approvedByName = approvedByName,
    approvedAt = this.approvedAt, approvedComment = approvedComment, approvedAmount = approvedAmount,
    readyToSettleByUid = readyToSettleByUid, readyToSettleByName = readyToSettleByName,
    readyToSettleAt = readyToSettleAt, settledAmount = settledAmount,
    settledByUid = settledByUid, settledByName = settledByName, settledAt = settledAt,
    settledPaymentMethod = paymentMethod, settledTrxId = transactionId,
    rejectedByUid = rejectedByUid, rejectedByName = rejectedByName, rejectedAt = rejectedAt,
    rejectReason = rejectReason
)
