package com.cloudx.databridge

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

// NOTE: branch-wise role assignment for the petty cash approval chain
// (Team Aligned / Petty Cash POC / Accounts) lives on Branch.kt as
// team_aligned_uid/name/role, petty_cash_poc_uid/name, and the pre-existing
// accountant_uid/name/role — NOT a separate petty_cash_roles/{branchId}
// node. This follows the manager_uid/accountant_uid pattern so branch role
// assignment stays in one place (BranchEditFragment/BranchCreateFragment).
