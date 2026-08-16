package com.cloudx.databridge

import com.google.firebase.database.IgnoreExtraProperties

/**
 * Leave Management — data models.
 *
 * Flow: Requester submits a leave request -> any Incharge (same branch)
 * acknowledges it -> any Shift Lead (same branch) approves/rejects it.
 *
 * Unlike Petty Cash's Team Aligned / Cash POC, the acknowledger and
 * approver here are NOT a single fixed per-branch UID stored on Branch.kt.
 * They're role-based queues: every active Incharge in the branch sees the
 * pending request, and whichever one acts first resolves it for everyone
 * else (same for Shift Lead at the approval stage). This mirrors how
 * PettyCashDashboardFragment queues work for a role, just without a
 * Branch-level "the" assignee field, since the requirement here is
 * "any of them can act," not "this one specific person."
 *
 * Firebase structure (see FirebasePaths.leaveManagement*):
 *   leave_management/{branchId}/requests/{requestId} -> LeaveRequest
 */

// Request lifecycle status
const val LM_STATUS_PENDING = "pending"             // just submitted by the Requester
const val LM_STATUS_ACKNOWLEDGED = "acknowledged"   // an Incharge has acknowledged it
const val LM_STATUS_APPROVED = "approved"           // a Shift Lead has approved it
const val LM_STATUS_REJECTED = "rejected"

const val LEAVE_TYPE_EXCHANGE = "Exchange"
const val LEAVE_TYPE_UNPAID = "Unpaid Leave"

// Role-name matching for the acknowledge/approve queues. Matched against
// roles/{roleId}/name (case-insensitive), same way EmployeeFragment already
// resolves role display names from the roles/ table — there's no separate
// "is this role an Incharge" flag in Firebase, just the role's configured
// name, so the check is a name comparison rather than a fixed roleId.
const val LEAVE_ACKNOWLEDGER_ROLE_NAME = "Incharge"
const val LEAVE_APPROVER_ROLE_NAME = "Shift Lead"

@IgnoreExtraProperties
data class LeaveApprovalStep(
    val stepName: String = "",      // "Request Submitted" | "Incharge Acknowledged" | "Shift Lead Approval"
    val status: String = "",        // "done" | "pending" | "rejected"
    val byUid: String = "",
    val byName: String = "",
    val at: Long = 0L,
    val note: String = ""
)

@IgnoreExtraProperties
data class LeaveRequest(
    val id: String = "",
    val branchId: String = "",
    val requestCode: String = "",          // e.g. LV-2401
    val workerUid: String = "",
    val workerName: String = "",
    val workerRole: String = "",           // e.g. "Delivery Agent" — also used to scope the reliever list
    val leaveType: String = "",            // Exchange, Unpaid Leave
    val leaveDateMillis: Long = 0L,
    val dutyDateMillis: Long = 0L,         // only set when leaveType == Exchange
    val relieverUid: String = "",          // optional — no reliever available is allowed
    val relieverName: String = "",
    val reason: String = "",
    val status: String = LM_STATUS_PENDING,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val acknowledgedByUid: String = "",
    val acknowledgedByName: String = "",
    val acknowledgedAt: Long = 0L,
    val approvedByUid: String = "",
    val approvedByName: String = "",
    val approvedAt: Long = 0L,
    val rejectedByUid: String = "",
    val rejectedByName: String = "",
    val rejectedAt: Long = 0L,
    val rejectReason: String = "",
    val steps: List<LeaveApprovalStep> = emptyList()
)
