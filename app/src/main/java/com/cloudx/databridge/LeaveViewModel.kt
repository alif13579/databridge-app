package com.cloudx.databridge

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Leave Management — central Firebase read/write + role resolution.
 *
 * Firebase structure:
 *   leave_management/{branchId}/requests/{requestId} -> LeaveRequest
 *   users/{uid}/profile/company_info/{role_id,branch_ids,status} -> used both
 *     to resolve the signed-in user's Incharge/Shift Lead standing (by role
 *     NAME, see below) and to build the reliever candidate list.
 *
 * Role resolution differs from Petty Cash: there's no branch-level fixed
 * "the Incharge" / "the Shift Lead" uid field on Branch.kt. Every active
 * user whose role NAME (roles/{role_id}/name) matches "Incharge" or "Shift
 * Lead" AND whose branch_ids contains this branch is a valid actor — the
 * queue is shared, and whoever acts first resolves it. isIncharge/
 * isShiftLead below tell a screen whether the *signed-in* user is one of
 * those valid actors, so it can decide whether to show the acknowledge/
 * approve queue at all.
 *
 * Status flow: pending -> acknowledged -> approved (or rejected, from
 * pending/acknowledged only).
 */

data class LeaveUserRoles(
    val isIncharge: Boolean = false,
    val isShiftLead: Boolean = false
) {
    val isAnyApprover get() = isIncharge || isShiftLead
}

sealed class LeaveState {
    object Loading : LeaveState()
    data class Success(
        val requests: List<LeaveRequest>,
        val roles: LeaveUserRoles
    ) : LeaveState() {
        val myRequests: List<LeaveRequest> get() {
            val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
            return requests.filter { it.workerUid == uid }
        }
        /** Requests any Incharge at this branch can act on right now. */
        val inchargeQueue: List<LeaveRequest> get() =
            requests.filter { it.status == LM_STATUS_PENDING }.sortedByDescending { it.createdAt }
        /** Requests any Shift Lead at this branch can act on right now. */
        val shiftLeadQueue: List<LeaveRequest> get() =
            requests.filter { it.status == LM_STATUS_ACKNOWLEDGED }.sortedByDescending { it.acknowledgedAt }
    }
    data class Error(val message: String) : LeaveState()
}

/** A candidate reliever: same branch, same role, active, not the requester. */
data class RelieverCandidate(
    val uid: String,
    val name: String
)

class LeaveViewModel : ViewModel() {

    private val db = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val _state = MutableLiveData<LeaveState>(LeaveState.Loading)
    val state: LiveData<LeaveState> = _state

    private var branchId: String = ""

    // ── Load ─────────────────────────────────────────────────────────────────

    fun load(branchId: String) {
        this.branchId = branchId
        _state.value = LeaveState.Loading
        viewModelScope.launch {
            try {
                val (requestsSnap, roles) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val requestsDeferred = async { db.reference.child(FirebasePaths.leaveManagementRequests(branchId)).get().await() }
                        val rolesDeferred = async { resolveRoles(branchId) }
                        Pair(requestsDeferred.await(), rolesDeferred.await())
                    }
                }

                val requests = requestsSnap.children
                    .mapNotNull { snap -> snap.getValue(LeaveRequest::class.java)?.copy(id = snap.key.orEmpty()) }
                    .sortedByDescending { it.createdAt }

                _state.value = LeaveState.Success(requests, roles)
            } catch (e: Exception) {
                _state.value = LeaveState.Error(e.message ?: "Failed to load leave requests")
            }
        }
    }

    /**
     * Resolves whether the signed-in user is an Incharge and/or Shift Lead
     * AT THIS BRANCH: their profile's branch_ids must contain [branchId],
     * and their role's configured name (roles/{role_id}/name) must match
     * the constant, case-insensitively (admin-typed role names can vary in
     * case; this is the same tolerant match style used when displaying role
     * names elsewhere in the app).
     */
    private suspend fun resolveRoles(branchId: String): LeaveUserRoles {
        val uid = auth.currentUser?.uid.orEmpty()
        if (uid.isBlank()) return LeaveUserRoles()

        val companyInfoSnap = db.reference.child(FirebasePaths.userCompanyInfo(uid)).get().await()
        val roleId = companyInfoSnap.child("role_id").getValue(String::class.java).orEmpty()
        val branchIds = companyInfoSnap.child("branch_ids").children.mapNotNull { it.getValue(String::class.java) }
        if (roleId.isBlank() || branchId !in branchIds) return LeaveUserRoles()

        val roleName = db.reference.child(FirebasePaths.role(roleId)).child("name")
            .get().await().getValue(String::class.java).orEmpty().trim()

        return LeaveUserRoles(
            isIncharge = roleName.equals(LEAVE_ACKNOWLEDGER_ROLE_NAME, ignoreCase = true),
            isShiftLead = roleName.equals(LEAVE_APPROVER_ROLE_NAME, ignoreCase = true)
        )
    }

    /** Fetches the signed-in user's real display name from their profile. */
    private suspend fun currentUserName(): String {
        val uid = auth.currentUser?.uid.orEmpty()
        if (uid.isBlank()) return ""
        return runCatching {
            db.reference.child("users/$uid/profile/name").get().await().getValue(String::class.java)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: auth.currentUser?.displayName.orEmpty()
    }

    // ── Reliever candidates: same branch, same role, active, excluding self ──

    suspend fun loadRelieverCandidates(branchId: String, workerRoleId: String): List<RelieverCandidate> {
        val selfUid = auth.currentUser?.uid.orEmpty()
        if (workerRoleId.isBlank()) return emptyList()
        val usersSnap = db.reference.child("users").get().await()
        return usersSnap.children.mapNotNull { child ->
            val uid = child.key ?: return@mapNotNull null
            if (uid == selfUid) return@mapNotNull null
            val info = child.child("profile/company_info")
            val roleId = info.child("role_id").getValue(String::class.java).orEmpty()
            val status = info.child("status").getValue(String::class.java).orEmpty()
            val branchIds = info.child("branch_ids").children.mapNotNull { it.getValue(String::class.java) }
            if (roleId != workerRoleId) return@mapNotNull null
            if (status != "active") return@mapNotNull null
            if (branchId !in branchIds) return@mapNotNull null
            val name = child.child("profile/name").getValue(String::class.java)
                ?: child.child("name").getValue(String::class.java)
                ?: return@mapNotNull null
            RelieverCandidate(uid = uid, name = name)
        }.sortedBy { it.name.lowercase() }
    }

    // ── Requester: submit a new leave request ───────────────────────────────

    suspend fun submitRequest(
        branchId: String,
        leaveType: String,
        leaveDateMillis: Long,
        dutyDateMillis: Long,
        relieverUid: String,
        relieverName: String,
        reason: String,
        workerRole: String = ""
    ): Result<String> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Requester" }
        val ref = db.reference.child(FirebasePaths.leaveManagementRequests(branchId)).push()
        val requestId = ref.key ?: throw IllegalStateException("Could not generate request id")
        val now = System.currentTimeMillis()
        val requestCode = "LV-${now.toString().takeLast(4)}"

        val request = LeaveRequest(
            id = requestId,
            branchId = branchId,
            requestCode = requestCode,
            workerUid = uid,
            workerName = name,
            workerRole = workerRole,
            leaveType = leaveType,
            leaveDateMillis = leaveDateMillis,
            dutyDateMillis = if (leaveType == LEAVE_TYPE_EXCHANGE) dutyDateMillis else 0L,
            relieverUid = relieverUid,
            relieverName = relieverName,
            reason = reason,
            status = LM_STATUS_PENDING,
            createdAt = now,
            updatedAt = now,
            steps = listOf(
                LeaveApprovalStep(stepName = "Request Submitted", status = "done", byUid = uid, byName = name, at = now)
            )
        )
        ref.setValue(request).await()
        requestCode
    }

    // ── Requester: edit a request (only while status == pending) ────────────

    suspend fun updateRequest(
        branchId: String,
        requestId: String,
        leaveType: String,
        leaveDateMillis: Long,
        dutyDateMillis: Long,
        relieverUid: String,
        relieverName: String,
        reason: String
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val ref = db.reference.child(FirebasePaths.leaveManagementRequest(branchId, requestId))
        val snap = ref.get().await()
        val existing = snap.getValue(LeaveRequest::class.java) ?: throw IllegalStateException("Request not found")

        if (existing.workerUid != uid) throw IllegalStateException("You can only edit your own requests")
        if (existing.status != LM_STATUS_PENDING) throw IllegalStateException("This request can no longer be edited")

        ref.updateChildren(
            mapOf(
                "leaveType" to leaveType,
                "leaveDateMillis" to leaveDateMillis,
                "dutyDateMillis" to if (leaveType == LEAVE_TYPE_EXCHANGE) dutyDateMillis else 0L,
                "relieverUid" to relieverUid,
                "relieverName" to relieverName,
                "reason" to reason,
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    // ── Requester: delete a request (only while status == pending) ──────────

    suspend fun deleteRequest(branchId: String, requestId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val ref = db.reference.child(FirebasePaths.leaveManagementRequest(branchId, requestId))
        val snap = ref.get().await()
        val existing = snap.getValue(LeaveRequest::class.java) ?: throw IllegalStateException("Request not found")

        if (existing.workerUid != uid) throw IllegalStateException("You can only delete your own requests")
        if (existing.status != LM_STATUS_PENDING) throw IllegalStateException("This request can no longer be deleted")

        ref.removeValue().await()
    }

    // ── Incharge: acknowledge a request (1st approval) ───────────────────────

    suspend fun acknowledgeRequest(branchId: String, requestId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Incharge" }
        val now = System.currentTimeMillis()
        val ref = db.reference.child(FirebasePaths.leaveManagementRequest(branchId, requestId))
        val snap = ref.get().await()
        val existing = snap.getValue(LeaveRequest::class.java) ?: throw IllegalStateException("Request not found")

        if (existing.status != LM_STATUS_PENDING) throw IllegalStateException("This request has already been acted on")

        val updatedSteps = existing.steps + LeaveApprovalStep(
            stepName = "Incharge Acknowledged", status = "done", byUid = uid, byName = name, at = now
        )
        ref.updateChildren(
            mapOf(
                "status" to LM_STATUS_ACKNOWLEDGED,
                "acknowledgedByUid" to uid,
                "acknowledgedByName" to name,
                "acknowledgedAt" to now,
                "updatedAt" to now,
                "steps" to updatedSteps
            )
        ).await()
    }

    // ── Shift Lead: approve a request (final step) ───────────────────────────

    suspend fun approveRequest(branchId: String, requestId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Shift Lead" }
        val now = System.currentTimeMillis()
        val ref = db.reference.child(FirebasePaths.leaveManagementRequest(branchId, requestId))
        val snap = ref.get().await()
        val existing = snap.getValue(LeaveRequest::class.java) ?: throw IllegalStateException("Request not found")

        if (existing.status != LM_STATUS_ACKNOWLEDGED) throw IllegalStateException("This request isn't ready for approval yet")

        val updatedSteps = existing.steps + LeaveApprovalStep(
            stepName = "Shift Lead Approval", status = "done", byUid = uid, byName = name, at = now
        )
        ref.updateChildren(
            mapOf(
                "status" to LM_STATUS_APPROVED,
                "approvedByUid" to uid,
                "approvedByName" to name,
                "approvedAt" to now,
                "updatedAt" to now,
                "steps" to updatedSteps
            )
        ).await()
    }

    // ── Reject (can happen at Pending or Acknowledged stage only) ───────────

    suspend fun rejectRequest(branchId: String, requestId: String, reason: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName()
        val now = System.currentTimeMillis()
        val ref = db.reference.child(FirebasePaths.leaveManagementRequest(branchId, requestId))
        val snap = ref.get().await()
        val existing = snap.getValue(LeaveRequest::class.java) ?: throw IllegalStateException("Request not found")

        if (existing.status != LM_STATUS_PENDING && existing.status != LM_STATUS_ACKNOWLEDGED) {
            throw IllegalStateException("This request can no longer be rejected")
        }

        val updatedSteps = existing.steps + LeaveApprovalStep(
            stepName = "Rejected", status = "rejected", byUid = uid, byName = name, at = now, note = reason
        )
        ref.updateChildren(
            mapOf(
                "status" to LM_STATUS_REJECTED,
                "rejectedByUid" to uid,
                "rejectedByName" to name,
                "rejectedAt" to now,
                "rejectReason" to reason,
                "updatedAt" to now,
                "steps" to updatedSteps
            )
        ).await()
    }
}
