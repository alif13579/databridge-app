package com.cloudx.databridge

import android.os.Bundle
import com.google.firebase.database.IgnoreExtraProperties

/**
 * Petty Cash / Convenience Bill Management — data models.
 *
 * Flow: Worker submits a request -> Team Aligned (incharge) aligns it ->
 * Cash POC approves it -> Accounts settles it (pays out + deposits fund).
 *
 * Firebase structure (see FirebasePaths.pettyCash*):
 *   petty_cash/{branchId}/requests/{requestId} -> PettyCashRequest
 *   petty_cash/{branchId}/wallet/balance        -> Double
 *   petty_cash/{branchId}/wallet/deposits/{id}  -> PettyCashDeposit
 */

// Request lifecycle status
const val PC_STATUS_PENDING_TEAM_ALIGN = "pending_team_align"
const val PC_STATUS_PENDING_POC = "pending_poc"
const val PC_STATUS_APPROVED = "approved"          // POC approved, waiting settlement
const val PC_STATUS_SETTLED = "settled"
const val PC_STATUS_REJECTED = "rejected"

const val PC_PRIORITY_HIGH = "high"
const val PC_PRIORITY_NORMAL = "normal"

@IgnoreExtraProperties
data class PettyCashApprovalStep(
    val stepName: String = "",      // "Request Submitted" | "Team Aligned Approval" | "POC Approval" | "Accounts Settlement"
    val status: String = "",        // "done" | "pending" | "rejected"
    val byUid: String = "",
    val byName: String = "",
    val at: Long = 0L,
    val note: String = ""
)

@IgnoreExtraProperties
data class PettyCashRequest(
    val id: String = "",
    val branchId: String = "",
    val requestCode: String = "",          // e.g. REQ-2401
    val workerUid: String = "",
    val workerName: String = "",
    val workerRole: String = "",           // e.g. "Delivery Agent"
    val category: String = "",             // Travel Expense, Fuel Expense, Stationery, Office Supplies...
    val purpose: String = "",
    val amount: Double = 0.0,
    val priority: String = PC_PRIORITY_NORMAL,
    val attachmentUrl: String = "",
    val attachmentName: String = "",
    val status: String = PC_STATUS_PENDING_TEAM_ALIGN,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val teamAlignedByUid: String = "",
    val teamAlignedByName: String = "",
    val teamAlignedAt: Long = 0L,
    val pocApprovedByUid: String = "",
    val pocApprovedByName: String = "",
    val pocApprovedAt: Long = 0L,
    val settledByUid: String = "",
    val settledByName: String = "",
    val settledAt: Long = 0L,
    val settledPaymentMethod: String = "",  // Cash, Bank
    val settledTrxId: String = "",
    val rejectedByUid: String = "",
    val rejectedByName: String = "",
    val rejectedAt: Long = 0L,
    val rejectReason: String = "",
    val steps: List<PettyCashApprovalStep> = emptyList()
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
    val workerCategory: String = ""         // "" or "All Categories" = no restriction
) {
    val isActive: Boolean get() =
        dateFromMillis != 0L || dateToMillis != 0L || statuses.isNotEmpty() ||
        (category.isNotBlank() && category != "All Categories") ||
        (workerCategory.isNotBlank() && workerCategory != "All Categories")

    fun matches(request: PettyCashRequest): Boolean {
        if (dateFromMillis != 0L && request.createdAt < dateFromMillis) return false
        if (dateToMillis != 0L && request.createdAt > dateToMillis) return false
        if (statuses.isNotEmpty() && request.status !in statuses) return false
        if (category.isNotBlank() && category != "All Categories" && request.category != category) return false
        if (workerCategory.isNotBlank() && workerCategory != "All Categories" && request.workerRole != workerCategory) return false
        return true
    }

    /** Date-range-only match for deposits, which have no status/category/worker-role. */
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
        }

        fun fromBundle(bundle: Bundle): PettyCashFilterState = PettyCashFilterState(
            dateFromMillis = bundle.getLong("dateFromMillis", 0L),
            dateToMillis = bundle.getLong("dateToMillis", 0L),
            statuses = (bundle.getStringArrayList("statuses") ?: arrayListOf()).toSet(),
            category = bundle.getString("category").orEmpty(),
            workerCategory = bundle.getString("workerCategory").orEmpty()
        )
    }
}

// NOTE: branch-wise role assignment for the petty cash approval chain
// (Team Aligned / Petty Cash POC / Accounts) lives on Branch.kt as
// team_aligned_uid/name/role, petty_cash_poc_uid/name, and the pre-existing
// accountant_uid/name/role — NOT a separate petty_cash_roles/{branchId}
// node. This follows the manager_uid/accountant_uid pattern so branch role
// assignment stays in one place (BranchEditFragment/BranchCreateFragment).
