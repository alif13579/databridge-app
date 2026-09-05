package com.cloudx.databridge

import android.os.Bundle
import com.google.firebase.database.IgnoreExtraProperties

/**
 * Petty Cash / Convenience Bill Management — data models.
 *
 * Flow: Requester submits a request -> Team Aligned acknowledges it ->
 * Cash POC approves it -> Accounts marks it ready to settle -> Accounts
 * hands over cash and marks it settled.
 *
 * Firebase structure is legacy — the live flow is Supabase-only:
 *   public.claims                 -> ClaimInfo (rendered into this legacy UI model)
 *   public.petty_cash_wallet_balance (one row/branch) -> Double
 *   public.petty_cash_deposits    -> PettyCashDeposit
 */

// Request lifecycle status
const val PC_STATUS_PENDING = "pending"                       // just submitted by the Requester
const val PC_STATUS_ACKNOWLEDGED = "verified"                 // Staff has verified it
const val PC_STATUS_APPROVED = "approved"                      // Cash POC has approved it
const val PC_STATUS_SETTLE_IN_PROCESS = "ready_to_settle"      // Accounts has queued it for cash handover
const val PC_STATUS_SETTLED = "settled"                        // cash handed over, done
const val PC_STATUS_REJECTED = "rejected"
const val PC_STATUS_CANCELLED = "cancelled"

/** Single source of truth for a status's display label — screens should
 *  derive from this instead of hardcoding the label, so a future rename
 *  only needs to change one place. (PettyCashSettlementDetailsFragment's
 *  approval-step titles were hardcoded and missed the acknowledged->verified
 *  rename that every other screen picked up; this is the fix.) */
fun pettyCashStatusLabel(status: String): String = when (status) {
    PC_STATUS_PENDING -> "Pending"
    PC_STATUS_ACKNOWLEDGED -> "Verified"
    PC_STATUS_APPROVED -> "Approved"
    PC_STATUS_SETTLE_IN_PROCESS -> "Ready to Settle"
    PC_STATUS_SETTLED -> "Settled"
    PC_STATUS_REJECTED -> "Rejected"
    PC_STATUS_CANCELLED -> "Cancelled"
    else -> status
}

const val PC_CATEGORY_BULK_DELIVERY = "Bulk Delivery"
const val PC_CATEGORY_PICKUP = "Pickup"

@IgnoreExtraProperties
data class PettyCashRequest(
    val id: String = "",
    val branchId: String = "",
    val requestCode: String = "",          // e.g. REQ-2401
    val workerUid: String = "",
    val workerName: String = "",
    val workerRole: String = "",           // e.g. "Delivery Agent"
    val category: String = "",             // Bulk Delivery, Pickup
    val consignmentId: String = "",        // set when category == Bulk Delivery
    val storeId: String = "",              // set when category == Pickup
    val storeName: String = "",            // set when category == Pickup
    val pickupCount: Int = 0,              // set when category == Pickup — how many pickups this claim covers
    // Conveyance fields — set when category is Pickup or Bulk Delivery. See
    // ClaimInfo's same-named fields (the canonical source — this is a display
    // copy) and PettyCashRequestCreateFragment's groupPcRequestConveyance for
    // the actual entry UI + Office-default/store-area-prefill logic.
    val vehicle: String = "",
    val fromArea: String = "",
    val toArea: String = "",
    val attemptQuantity: Int = 0,
    val deliveredQuantity: Int = 0,
    val cidOrMerchant: String = "",
    val purpose: String = "",
    val amount: Double = 0.0,
    // R2 *object keys* (e.g. "petty_cash_attachments/<uid>/...jpg"), not
    // URLs — the R2 bucket is private, so there's no standing public URL.
    // See AttachmentUploader.getDownloadUrl() for turning a key into a
    // short-lived presigned URL when someone needs to actually view it.
    // Multi-attachment (max 5).
    val attachments: List<AttachmentRef> = emptyList(),
    val requestedDate: Long = 0L,           // date the expense was incurred, set by the Requester — separate from createdAt (submission time)
    val status: String = PC_STATUS_PENDING,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val staffByUid: String = "",
    val staffByName: String = "",
    val staffAt: Long = 0L,
    val staffComment: String = "",
    val pocApprovedByUid: String = "",
    val pocApprovedByName: String = "",
    val pocApprovedAt: Long = 0L,
    val pocComment: String = "",
    val approvedAmount: Double = 0.0,        // set by Cash POC at approval time; may differ from `amount` (partial approval) — 0.0 until approved
    val settleInProcessByUid: String = "",
    val settleInProcessByName: String = "",
    val settleInProcessAt: Long = 0L,
    val settledAmount: Double = 0.0,        // set by Accounts at the Settle step; defaults to
                                             // approvedAmount but Accounts can adjust it once more —
                                             // the true final figure actually paid out. 0.0 until settled.
    val settledByUid: String = "",
    val settledByName: String = "",
    val settledAt: Long = 0L,
    val settledPaymentMethod: String = "",  // Cash, Bank
    val settledTrxId: String = "",
    val rejectedByUid: String = "",
    val rejectedByName: String = "",
    val rejectedAt: Long = 0L,
    val rejectReason: String = ""
)

/**
 * The amount settlement should actually move -- the Cash POC's approved
 * amount, set at the Approve step.
 */
val PettyCashRequest.settlementAmount: Double
    get() = approvedAmount

@IgnoreExtraProperties
data class Store(
    val id: String = "",
    val storeId: String = "",
    val name: String = "",
    val address: String = "",
    val areaId: String = "",
    val areaName: String = "",
    val phone: String = "",
    // Fixed pickup conveyance payout. 0/null = not set (request form keeps
    // old behavior: amount hidden + 0). Set = prefills requested amount,
    // non-editable.
    val conveyanceAmount: Double = 0.0
)

@IgnoreExtraProperties
data class PettyCashDeposit(
    val id: String = "",
    val amount: Double = 0.0,
    val source: String = "",       // Cash, Bank, Adjustment
    val reference: String = "",
    val remarks: String = "",
    val balanceAfter: Double = 0.0,
    val timestamp: Long = 0L,
    val enteredByUid: String = "",
    val enteredByName: String = ""
)

data class PettyCashWalletSummary(
    val availableBalance: Double = 0.0,
    val pendingApprovalTotal: Double = 0.0,
    val approvedWaitingSettlementTotal: Double = 0.0,
    val settledThisMonthTotal: Double = 0.0,
    val totalFund: Double = 0.0
)

/**
 * Shared filter state produced by PettyCashFilterFragment and consumed by
 * every screen that routes its filter icon there (Pending Settlement,
 * Deposit History, Settlement History, All Requests). Passed via the
 * Fragment Result API (see FRAGMENT_RESULT_KEY) rather than a shared
 * ViewModel scope, since the filter screen and its caller aren't always
 * in the same back-stack entry group.
 *
 * All fields are "no restriction" when at their default — an empty
 * statuses set, blank category, 0L dates all mean "don't filter on this".
 */
data class PettyCashFilterState(
    val dateFromMillis: Long = 0L,
    val dateToMillis: Long = 0L,
    val statuses: Set<String> = emptySet(), // subset of PC_STATUS_* constants
    val category: String = "",              // "" or "All Categories" = no restriction
    val workerCategory: String = "",        // "" or "All Categories" = no restriction
    val requesterName: String = ""          // free-text, matched case-insensitively against workerName
) {
    val isActive: Boolean get() =
        dateFromMillis != 0L || dateToMillis != 0L || statuses.isNotEmpty() ||
        (category.isNotBlank() && category != "All Categories") ||
        (workerCategory.isNotBlank() && workerCategory != "All Categories") ||
        requesterName.isNotBlank()

    fun matches(request: PettyCashRequest): Boolean {
        // requestedDate is when the expense actually happened; createdAt is
        // just submission time. Filter (and the list showing it) by
        // requestedDate so backdated requests filter/display consistently —
        // falls back to createdAt for older requests submitted before this
        // field existed (requestedDate == 0).
        val reqDate = if (request.requestedDate != 0L) request.requestedDate else request.createdAt
        if (dateFromMillis != 0L && reqDate < dateFromMillis) return false
        if (dateToMillis != 0L && reqDate > dateToMillis) return false
        if (statuses.isNotEmpty() && request.status !in statuses) return false
        if (category.isNotBlank() && category != "All Categories" && request.category != category) return false
        if (workerCategory.isNotBlank() && workerCategory != "All Categories" && request.workerRole != workerCategory) return false
        if (requesterName.isNotBlank() && !request.workerName.contains(requesterName.trim(), ignoreCase = true)) return false
        return true
    }

    /** Date-range-only match for deposits, which have no status/category/worker-role/requester. */
    fun matches(deposit: PettyCashDeposit): Boolean {
        if (dateFromMillis != 0L && deposit.timestamp < dateFromMillis) return false
        if (dateToMillis != 0L && deposit.timestamp > dateToMillis) return false
        return true
    }

    companion object {
        const val FRAGMENT_RESULT_KEY = "petty_cash_filter_result"
        const val BUNDLE_KEY_STATE = "petty_cash_filter_state"

        fun toBundle(state: PettyCashFilterState): Bundle = Bundle().apply {
            putLong("dateFromMillis", state.dateFromMillis)
            putLong("dateToMillis", state.dateToMillis)
            putStringArrayList("statuses", ArrayList(state.statuses))
            putString("category", state.category)
            putString("workerCategory", state.workerCategory)
            putString("requesterName", state.requesterName)
        }

        fun fromBundle(bundle: Bundle): PettyCashFilterState = PettyCashFilterState(
            dateFromMillis = bundle.getLong("dateFromMillis", 0L),
            dateToMillis = bundle.getLong("dateToMillis", 0L),
            statuses = (bundle.getStringArrayList("statuses") ?: arrayListOf()).toSet(),
            category = bundle.getString("category").orEmpty(),
            workerCategory = bundle.getString("workerCategory").orEmpty(),
            requesterName = bundle.getString("requesterName").orEmpty()
        )
    }
}

// NOTE: branch-wise role assignment for the petty cash approval chain
// (Staff / Petty Cash POC / Accounts) lives on Branch.kt as
// staff_uid/name/role, petty_cash_poc_uid/name, and the pre-existing
// accountant_uid/name/role — NOT a separate petty_cash_roles/{branchId}
// node. This follows the manager_uid/accountant_uid pattern so branch role
// assignment stays in one place (BranchEditFragment/BranchCreateFragment).
// "Staff" was formerly named "Team Aligned" -- fully renamed (both display
// label and field names) since no production data existed under the old
// names yet.
