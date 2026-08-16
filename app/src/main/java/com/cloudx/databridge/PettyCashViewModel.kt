package com.cloudx.databridge

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Petty Cash Management — central Firebase read/write + role resolution.
 *
 * Firebase structure:
 *   petty_cash/{branchId}/requests/{requestId}       -> PettyCashRequest
 *   petty_cash/{branchId}/wallet/balance              -> Double
 *   petty_cash/{branchId}/wallet/deposits/{depositId} -> PettyCashDeposit
 *   branches/{branchId}/team_aligned_uid / petty_cash_poc_uid / accountant_uid
 *     -> branch-level role assignment (see Branch.kt), resolved against
 *        the signed-in user's uid or role_id (role_id assignment means
 *        "everyone with this role at this branch", same convention as
 *        the existing accountant_role field).
 *
 * Role resolution: a person's petty cash role at a given branch is derived
 * by comparing their uid/role_id against the branch's assigned uids/roles.
 * A person can hold more than one role (e.g. be both Cash POC and
 * Accounts) — [PettyCashUserRoles] exposes all of them so a screen can
 * show the union of what someone is allowed to do.
 *
 * Status flow: pending -> acknowledged -> approved -> settle_in_process ->
 * settled (or rejected, from pending/acknowledged only).
 */

data class PettyCashUserRoles(
    val isTeamAligned: Boolean = false,
    val isCashPoc: Boolean = false,
    val isAccounts: Boolean = false
) {
    val isAnyApprover get() = isTeamAligned || isCashPoc || isAccounts
}

sealed class PettyCashState {
    object Loading : PettyCashState()
    data class Success(
        val requests: List<PettyCashRequest>,
        val deposits: List<PettyCashDeposit>,
        val walletBalance: Double,
        val roles: PettyCashUserRoles
    ) : PettyCashState() {
        // Derived aggregates, computed once here so every screen (Dashboard,
        // Requests, Wallet Summary, All Requests) reads the same numbers
        // instead of each recomputing its own slightly different sum.
        val totalFund: Double get() = deposits.sumOf { it.amount }
        val pendingApprovalTotal: Double get() =
            requests.filter { it.status == PC_STATUS_PENDING || it.status == PC_STATUS_ACKNOWLEDGED }.sumOf { it.amount }
        val approvedWaitingSettlementTotal: Double get() =
            requests.filter { it.status == PC_STATUS_APPROVED || it.status == PC_STATUS_SETTLE_IN_PROCESS }.sumOf { it.amount }
        val settledThisMonthTotal: Double get() {
            val cal = java.util.Calendar.getInstance()
            val currentMonth = cal.get(java.util.Calendar.MONTH)
            val currentYear = cal.get(java.util.Calendar.YEAR)
            return requests.filter {
                if (it.status != PC_STATUS_SETTLED || it.settledAt == 0L) return@filter false
                cal.timeInMillis = it.settledAt
                cal.get(java.util.Calendar.MONTH) == currentMonth && cal.get(java.util.Calendar.YEAR) == currentYear
            }.sumOf { it.amount }
        }
        /** Requests Accounts can act on right now — either mark ready-to-settle (approved) or hand over cash (settle_in_process). */
        val pendingSettlementQueue: List<PettyCashRequest> get() =
            requests.filter { it.status == PC_STATUS_APPROVED || it.status == PC_STATUS_SETTLE_IN_PROCESS }
                .sortedByDescending { if (it.status == PC_STATUS_SETTLE_IN_PROCESS) it.settleInProcessAt else it.pocApprovedAt }
    }
    data class Error(val message: String) : PettyCashState()
}

class PettyCashViewModel : ViewModel() {

    private val db = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val _state = MutableLiveData<PettyCashState>(PettyCashState.Loading)
    val state: LiveData<PettyCashState> = _state

    private var branchId: String = ""

    // ── Load ─────────────────────────────────────────────────────────────────

    fun load(branchId: String) {
        this.branchId = branchId
        _state.value = PettyCashState.Loading
        viewModelScope.launch {
            try {
                val (requestsSnap, depositsSnap, balanceSnap, branchSnap) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val requestsDeferred = async { db.reference.child(FirebasePaths.pettyCashRequests(branchId)).get().await() }
                        val depositsDeferred = async { db.reference.child(FirebasePaths.pettyCashDeposits(branchId)).get().await() }
                        val balanceDeferred  = async { db.reference.child(FirebasePaths.pettyCashWalletBalance(branchId)).get().await() }
                        val branchDeferred   = async { db.reference.child("branches/$branchId").get().await() }
                        listOf(requestsDeferred.await(), depositsDeferred.await(), balanceDeferred.await(), branchDeferred.await())
                    }
                }

                val requests = requestsSnap.children
                    .mapNotNull { snap -> snap.getValue(PettyCashRequest::class.java)?.copy(id = snap.key.orEmpty()) }
                    .sortedByDescending { it.createdAt }

                val deposits = depositsSnap.children
                    .mapNotNull { snap -> snap.getValue(PettyCashDeposit::class.java)?.copy(id = snap.key.orEmpty()) }
                    .sortedByDescending { it.timestamp }

                val walletBalance = balanceSnap.getValue(Double::class.java) ?: 0.0

                val branch = branchSnap.getValue(Branch::class.java) ?: Branch()
                val roles = resolveRoles(branch)

                _state.value = PettyCashState.Success(requests, deposits, walletBalance, roles)
            } catch (e: Exception) {
                _state.value = PettyCashState.Error(e.message ?: "Failed to load petty cash data")
            }
        }
    }

    /**
     * Resolves the signed-in user's petty cash roles at [branch] by comparing
     * their uid and role_id against the branch's assigned uid/role fields —
     * same convention as accountant_uid/accountant_role: a uid match means
     * "this specific person", a role match means "everyone with this role
     * at this branch".
     */
    private fun resolveRoles(branch: Branch): PettyCashUserRoles {
        val uid = auth.currentUser?.uid.orEmpty()
        val roleId = RbacManager.current.roleId

        fun matches(assignedUid: String, assignedRole: String): Boolean {
            if (assignedUid.isNotBlank()) return assignedUid == uid
            if (assignedRole.isNotBlank()) return assignedRole == roleId
            return false
        }

        return PettyCashUserRoles(
            isTeamAligned = matches(branch.team_aligned_uid, branch.team_aligned_role),
            isCashPoc = branch.petty_cash_poc_uid.isNotBlank() && branch.petty_cash_poc_uid == uid,
            isAccounts = matches(branch.accountant_uid, branch.accountant_role)
        )
    }

    /** Fetches the signed-in user's real display name from their profile — role
     *  names ("Manager", "Cash POC") are not person names and shouldn't be
     *  stored as the actor on an approval step. */
    private suspend fun currentUserName(): String {
        val uid = auth.currentUser?.uid.orEmpty()
        if (uid.isBlank()) return ""
        return runCatching {
            db.reference.child("users/$uid/profile/name").get().await().getValue(String::class.java)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: auth.currentUser?.displayName.orEmpty()
    }

    // ── Requester: submit a new request ─────────────────────────────────────

    suspend fun submitRequest(
        branchId: String,
        category: String,
        purpose: String,
        amount: Double,
        priority: String,
        attachmentUrl: String,
        attachmentName: String,
        workerRole: String = "",
        consignmentId: String = "",
        merchantName: String = "",
        requestedDate: Long = 0L
    ): Result<String> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Requester" }
        val ref = db.reference.child(FirebasePaths.pettyCashRequests(branchId)).push()
        val requestId = ref.key ?: throw IllegalStateException("Could not generate request id")
        val now = System.currentTimeMillis()
        val requestCode = "REQ-${now.toString().takeLast(4)}"

        val request = PettyCashRequest(
            id = requestId,
            branchId = branchId,
            requestCode = requestCode,
            workerUid = uid,
            workerName = name,
            workerRole = workerRole,
            category = category,
            consignmentId = consignmentId,
            merchantName = merchantName,
            purpose = purpose,
            amount = amount,
            priority = priority,
            attachmentUrl = attachmentUrl,
            attachmentName = attachmentName,
            requestedDate = if (requestedDate != 0L) requestedDate else now,
            status = PC_STATUS_PENDING,
            createdAt = now,
            updatedAt = now,
            steps = listOf(
                PettyCashApprovalStep(stepName = "Request Submitted", status = "done", byUid = uid, byName = name, at = now)
            )
        )
        ref.setValue(request).await()
        requestCode
    }

    // ── Requester: edit a request (only while status == pending) ────────────

    suspend fun updateRequest(
        branchId: String,
        requestId: String,
        category: String,
        purpose: String,
        amount: Double,
        consignmentId: String = "",
        merchantName: String = "",
        requestedDate: Long = 0L
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val ref = db.reference.child(FirebasePaths.pettyCashRequest(branchId, requestId))
        val snap = ref.get().await()
        val existing = snap.getValue(PettyCashRequest::class.java) ?: throw IllegalStateException("Request not found")

        if (existing.workerUid != uid) throw IllegalStateException("You can only edit your own requests")
        if (existing.status != PC_STATUS_PENDING) throw IllegalStateException("This request can no longer be edited")

        ref.updateChildren(
            mapOf(
                "category" to category,
                "consignmentId" to consignmentId,
                "merchantName" to merchantName,
                "purpose" to purpose,
                "amount" to amount,
                "requestedDate" to (if (requestedDate != 0L) requestedDate else existing.requestedDate),
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    // ── Requester: delete a request (only while status == pending) ──────────

    suspend fun deleteRequest(branchId: String, requestId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val ref = db.reference.child(FirebasePaths.pettyCashRequest(branchId, requestId))
        val snap = ref.get().await()
        val existing = snap.getValue(PettyCashRequest::class.java) ?: throw IllegalStateException("Request not found")

        if (existing.workerUid != uid) throw IllegalStateException("You can only delete your own requests")
        if (existing.status != PC_STATUS_PENDING) throw IllegalStateException("This request can no longer be deleted")

        ref.removeValue().await()
    }

    // ── Team Aligned: acknowledge a request (1st approval) ──────────────────

    suspend fun acknowledgeRequest(branchId: String, requestId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Team Aligned" }
        val now = System.currentTimeMillis()
        val ref = db.reference.child(FirebasePaths.pettyCashRequest(branchId, requestId))
        val snap = ref.get().await()
        val existing = snap.getValue(PettyCashRequest::class.java) ?: throw IllegalStateException("Request not found")

        val updatedSteps = existing.steps + PettyCashApprovalStep(
            stepName = "Team Aligned Acknowledged", status = "done", byUid = uid, byName = name, at = now
        )
        ref.updateChildren(
            mapOf(
                "status" to PC_STATUS_ACKNOWLEDGED,
                "teamAlignedByUid" to uid,
                "teamAlignedByName" to name,
                "teamAlignedAt" to now,
                "updatedAt" to now,
                "steps" to updatedSteps
            )
        ).await()
    }

    // ── Cash POC: approve a request (2nd approval) ──────────────────────────

    suspend fun approveRequest(branchId: String, requestId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Cash POC" }
        val now = System.currentTimeMillis()
        val ref = db.reference.child(FirebasePaths.pettyCashRequest(branchId, requestId))
        val snap = ref.get().await()
        val existing = snap.getValue(PettyCashRequest::class.java) ?: throw IllegalStateException("Request not found")

        val updatedSteps = existing.steps + PettyCashApprovalStep(
            stepName = "POC Approval", status = "done", byUid = uid, byName = name, at = now
        )
        ref.updateChildren(
            mapOf(
                "status" to PC_STATUS_APPROVED,
                "pocApprovedByUid" to uid,
                "pocApprovedByName" to name,
                "pocApprovedAt" to now,
                "updatedAt" to now,
                "steps" to updatedSteps
            )
        ).await()
    }

    // ── Accounts: mark a request ready to settle (queues it for cash handover) ──

    suspend fun markReadyToSettle(branchId: String, requestId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Accounts" }
        val now = System.currentTimeMillis()
        val ref = db.reference.child(FirebasePaths.pettyCashRequest(branchId, requestId))
        val snap = ref.get().await()
        val existing = snap.getValue(PettyCashRequest::class.java) ?: throw IllegalStateException("Request not found")

        val updatedSteps = existing.steps + PettyCashApprovalStep(
            stepName = "Ready to Settle", status = "done", byUid = uid, byName = name, at = now
        )
        ref.updateChildren(
            mapOf(
                "status" to PC_STATUS_SETTLE_IN_PROCESS,
                "settleInProcessByUid" to uid,
                "settleInProcessByName" to name,
                "settleInProcessAt" to now,
                "updatedAt" to now,
                "steps" to updatedSteps
            )
        ).await()
    }

    // ── Accounts: settle a request (final step, deducts wallet balance) ─────

    suspend fun settleRequest(
        branchId: String,
        requestId: String,
        paymentMethod: String,
        trxId: String
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Accounts" }
        val now = System.currentTimeMillis()
        val requestRef = db.reference.child(FirebasePaths.pettyCashRequest(branchId, requestId))
        val snap = requestRef.get().await()
        val existing = snap.getValue(PettyCashRequest::class.java) ?: throw IllegalStateException("Request not found")

        val updatedSteps = existing.steps + PettyCashApprovalStep(
            stepName = "Settled", status = "done", byUid = uid, byName = name, at = now
        )

        // Deduct from wallet balance via a transaction so concurrent settlements
        // (e.g. two Accounts users settling different requests at once) don't
        // race and overwrite each other's balance update.
        val balanceRef = db.reference.child(FirebasePaths.pettyCashWalletBalance(branchId))
        balanceRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val current = currentData.getValue(Double::class.java) ?: 0.0
                currentData.value = current - existing.amount
                return Transaction.success(currentData)
            }
            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {}
        })

        requestRef.updateChildren(
            mapOf(
                "status" to PC_STATUS_SETTLED,
                "settledByUid" to uid,
                "settledByName" to name,
                "settledAt" to now,
                "settledPaymentMethod" to paymentMethod,
                "settledTrxId" to trxId,
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
        val ref = db.reference.child(FirebasePaths.pettyCashRequest(branchId, requestId))
        val snap = ref.get().await()
        val existing = snap.getValue(PettyCashRequest::class.java) ?: throw IllegalStateException("Request not found")

        val updatedSteps = existing.steps + PettyCashApprovalStep(
            stepName = "Rejected", status = "rejected", byUid = uid, byName = name, at = now, note = reason
        )
        ref.updateChildren(
            mapOf(
                "status" to PC_STATUS_REJECTED,
                "rejectedByUid" to uid,
                "rejectedByName" to name,
                "rejectedAt" to now,
                "rejectReason" to reason,
                "updatedAt" to now,
                "steps" to updatedSteps
            )
        ).await()
    }

    // ── Accounts: deposit fund into the branch wallet ────────────────────────

    suspend fun depositFund(
        branchId: String,
        amount: Double,
        source: String,
        reference: String,
        remarks: String
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Accounts" }
        val now = System.currentTimeMillis()

        val balanceRef = db.reference.child(FirebasePaths.pettyCashWalletBalance(branchId))
        var newBalance = 0.0
        balanceRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val current = currentData.getValue(Double::class.java) ?: 0.0
                newBalance = current + amount
                currentData.value = newBalance
                return Transaction.success(currentData)
            }
            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {}
        })

        val depositRef = db.reference.child(FirebasePaths.pettyCashDeposits(branchId)).push()
        val depositId = depositRef.key ?: throw IllegalStateException("Could not generate deposit id")
        depositRef.setValue(
            PettyCashDeposit(
                id = depositId,
                amount = amount,
                source = source,
                reference = reference,
                remarks = remarks,
                balanceAfter = newBalance,
                timestamp = now,
                enteredByUid = uid,
                enteredByName = name
            )
        ).await()
    }
}
