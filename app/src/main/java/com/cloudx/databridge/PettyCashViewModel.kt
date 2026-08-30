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
 *   claims/{claimId}/info                             -> ClaimInfo
 *   petty_cash/{branchId}/wallet/balance              -> Double
 *   petty_cash/{branchId}/wallet/deposits/{depositId} -> PettyCashDeposit
 *   branches/{branchId}/staff_uid / petty_cash_poc_uid / accountant_uid
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
    val isStaff: Boolean = false,
    val isCashPoc: Boolean = false,
    val isAccounts: Boolean = false
) {
    val isAnyApprover get() = isStaff || isCashPoc || isAccounts
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

    private data class LoadResult(
        val report: ClaimsReport,
        val deposits: DataSnapshot,
        val balance: DataSnapshot,
        val branch: DataSnapshot
    )

    private val db = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val claims = ClaimsRepository(db)

    private val _state = MutableLiveData<PettyCashState>(PettyCashState.Loading)
    val state: LiveData<PettyCashState> = _state

    private var branchId: String = ""

    // ── Load ─────────────────────────────────────────────────────────────────

    fun load(branchId: String) {
        this.branchId = branchId
        _state.value = PettyCashState.Loading
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    coroutineScope {
                        // Requests now come from the branch index. The index is
                        // key-range queried by the server, not read wholesale.
                        val requestsDeferred = async {
                            claims.search(ClaimsReportFilter(
                                branchIds = setOf(branchId),
                                fromMillis = 1_000_000_000_000L,
                                toMillis = 9_999_999_999_999L
                            ))
                        }
                        val depositsDeferred = async { db.reference.child(FirebasePaths.pettyCashDeposits(branchId)).get().await() }
                        val balanceDeferred  = async { db.reference.child(FirebasePaths.pettyCashWalletBalance(branchId)).get().await() }
                        val branchDeferred   = async { db.reference.child("branches/$branchId").get().await() }
                        LoadResult(requestsDeferred.await(), depositsDeferred.await(), balanceDeferred.await(), branchDeferred.await())
                    }
                }

                val claimReport = loaded.report
                val depositsSnap = loaded.deposits
                val balanceSnap = loaded.balance
                val branchSnap = loaded.branch

                val requests = claimReport.claims.map { it.asPettyCashRequest() }

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
            isStaff = matches(branch.staff_uid, branch.staff_role),
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

    private suspend fun currentEmployeeId(): String {
        val uid = auth.currentUser?.uid.orEmpty()
        return db.reference.child("users/$uid/profile/company_info/employee_id").get().await()
            .getValue(String::class.java).orEmpty().trim()
    }

    // Digits-only, unlike employee_id above — this is the safe-as-a-Firebase-key identity
    // used for claims_by_systemId (see FirebasePaths.claimsBySystemId).
    private suspend fun currentSystemId(): String {
        val uid = auth.currentUser?.uid.orEmpty()
        return db.reference.child("users/$uid/profile/company_info/system_id").get().await()
            .getValue(String::class.java).orEmpty().trim()
    }

    private suspend fun branchName(branchId: String): String =
        db.reference.child(FirebasePaths.branchName(branchId)).get().await()
            .getValue(String::class.java).orEmpty()

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
        storeId: String = "",
        storeName: String = "",
        pickupCount: Int = 0,
        requestedDate: Long = 0L,
        onSupabaseResult: (Boolean) -> Unit = {}
    ): Result<String> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Requester" }
        val now = System.currentTimeMillis()
        val employeeId = currentEmployeeId()
        require(employeeId.isNotBlank()) { "Your employee ID is missing. Please contact an administrator." }
        val systemId = currentSystemId()
        require(systemId.isNotBlank()) { "Your system ID is missing. Please contact an administrator." }
        val claim = claims.create(ClaimInfo(
            branchId = branchId,
            branchName = branchName(branchId), employeeId = employeeId, employeeName = name,
            agentSystemId = systemId,
            workerUid = uid, workerRole = workerRole, type = category,
            category = category,
            consignmentId = consignmentId,
            storeId = storeId,
            storeName = storeName,
            pickupCount = pickupCount,
            purpose = purpose,
            requestedAmount = amount,
            priority = priority,
            attachmentUrl = attachmentUrl,
            attachmentName = attachmentName,
            status = PC_STATUS_PENDING, requestedAt = if (requestedDate != 0L) requestedDate else now
        ), onSupabaseResult)
        claim.claimCode
    }

    // ── Requester: edit a request (only while status == pending) ────────────

    suspend fun updateRequest(
        branchId: String,
        requestId: String,
        category: String,
        purpose: String,
        amount: Double,
        consignmentId: String = "",
        storeId: String = "",
        storeName: String = "",
        pickupCount: Int = 0,
        requestedDate: Long = 0L,
        onSupabaseResult: (Boolean) -> Unit = {}
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val existing = claims.get(requestId)?.asPettyCashRequest() ?: throw IllegalStateException("Request not found")

        if (existing.workerUid != uid) throw IllegalStateException("You can only edit your own requests")
        if (existing.status != PC_STATUS_PENDING) throw IllegalStateException("This request can no longer be edited")

        claims.update(requestId, mapOf(
                "category" to category,
                "consignmentId" to consignmentId,
                "storeId" to storeId,
                "storeName" to storeName,
                "pickupCount" to pickupCount,
                "purpose" to purpose,
                "requestedAmount" to amount,
                "requestedAt" to (if (requestedDate != 0L) requestedDate else existing.requestedDate)
            ), onSupabaseResult)
    }

    // ── Requester: delete a request (only while status == pending) ──────────

    suspend fun deleteRequest(branchId: String, requestId: String, onSupabaseResult: (Boolean) -> Unit = {}): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val existing = claims.get(requestId)?.asPettyCashRequest() ?: throw IllegalStateException("Request not found")

        if (existing.workerUid != uid) throw IllegalStateException("You can only delete your own requests")
        if (existing.status != PC_STATUS_PENDING) throw IllegalStateException("This request can no longer be deleted")

        // Claims are never deleted: cancellation keeps audit and reporting intact.
        claims.update(requestId, mapOf("status" to PC_STATUS_CANCELLED), onSupabaseResult)
    }

    // ── Reviewer (any role EXCEPT the original requester): correct the requested
    //    date ──────────────────────────────────────────────────────────────────
    // The requester's own date entry (submitRequest()/updateRequest(), while still
    // pending) can be wrong or simply a rough guess. This is the counterpart for
    // whoever reviews the request afterward — Staff, Cash POC, or Accounts — to
    // correct it so report generation's "Requested Date" is accurate. Deliberately
    // not status-restricted, unlike updateRequest(): the date can still need fixing
    // at any stage before/around report generation, not just while pending.

    suspend fun updateRequestedDate(requestId: String, requestedDate: Long, onSupabaseResult: (Boolean) -> Unit = {}): Result<Unit> = runCatching {
        require(requestedDate > 0L) { "A valid date is required" }
        val uid = auth.currentUser?.uid.orEmpty()
        val existing = claims.get(requestId)?.asPettyCashRequest() ?: throw IllegalStateException("Request not found")

        if (existing.workerUid == uid) throw IllegalStateException("The requester can't edit this — only another reviewer can")

        claims.update(requestId, mapOf("requestedAt" to requestedDate), onSupabaseResult)
    }

    // ── Staff (formerly "Team Aligned"): acknowledge a request (1st approval) ──
    // Both the display label AND the field/variable names are now "Staff"
    // (staff_uid, staff_role, isStaff, staffByUid, staffByName, staffAt) --
    // this was previously a UI-only rename with old field names kept for
    // data compatibility, but since there was no production data yet, the
    // underlying names were renamed too instead of staying permanently
    // mismatched with the "Team Aligned" label. stepName below is what
    // gets written to NEW requests going forward — pre-existing requests
    // (if any survive from before this rename) may have "Team Aligned
    // Acknowledged" stored in their steps list and are left as-is, since
    // rewriting historical timeline text isn't something this function
    // touches (that history is an accurate record of what the screen said
    // at the time).

    suspend fun acknowledgeRequest(branchId: String, requestId: String, comment: String = "", onSupabaseResult: (Boolean) -> Unit = {}): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Staff" }
        val now = System.currentTimeMillis()
        claims.get(requestId) ?: throw IllegalStateException("Request not found")
        claims.update(requestId, mapOf(
                "status" to PC_STATUS_ACKNOWLEDGED,
                "staffByUid" to uid,
                "staffByName" to name,
                "staffAt" to now,
                "staffComment" to comment,
                "updatedAt" to now
            ), onSupabaseResult)
    }

    // ── Cash POC: approve a request (2nd approval) ──────────────────────────

    suspend fun approveRequest(branchId: String, requestId: String, comment: String = "", approvedAmount: Double? = null, onSupabaseResult: (Boolean) -> Unit = {}): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Cash POC" }
        val now = System.currentTimeMillis()
        val existing = claims.get(requestId)?.asPettyCashRequest() ?: throw IllegalStateException("Request not found")
        // Defaults to the originally requested amount when the caller doesn't
        // specify one, e.g. approving in full without touching the amount field.
        val finalApprovedAmount = approvedAmount ?: existing.amount

        claims.update(requestId, mapOf(
                "status" to PC_STATUS_APPROVED,
                "pocApprovedByUid" to uid,
                "pocApprovedByName" to name,
                "approvedAt" to now,
                "pocComment" to comment,
                "approvedAmount" to finalApprovedAmount,
                "updatedAt" to now
            ), onSupabaseResult)
    }

    // ── Accounts: mark a request ready to settle (queues it for cash handover) ──

    suspend fun markReadyToSettle(branchId: String, requestId: String, onSupabaseResult: (Boolean) -> Unit = {}): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Accounts" }
        val now = System.currentTimeMillis()
        claims.get(requestId) ?: throw IllegalStateException("Request not found")
        claims.update(requestId, mapOf(
                "status" to PC_STATUS_SETTLE_IN_PROCESS,
                "settleInProcessByUid" to uid,
                "settleInProcessByName" to name,
                "settleInProcessAt" to now,
                "updatedAt" to now
            ), onSupabaseResult)
    }

    // ── Accounts: settle a request (final step, deducts wallet balance) ─────

    suspend fun settleRequest(
        branchId: String,
        requestId: String,
        paymentMethod: String,
        trxId: String,
        settledAmount: Double? = null,
        onSupabaseResult: (Boolean) -> Unit = {}
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Accounts" }
        val now = System.currentTimeMillis()
        val existing = claims.get(requestId)?.asPettyCashRequest() ?: throw IllegalStateException("Request not found")
        // Defaults to what Cash POC approved (itself already defaulted to the original
        // claim if POC didn't touch it) when Accounts settles as-is without adjusting.
        val finalSettledAmount = settledAmount ?: existing.approvedAmount.takeIf { it > 0 } ?: existing.amount

        // Deduct from wallet balance via a transaction so concurrent settlements
        // (e.g. two Accounts users settling different requests at once) don't
        // race and overwrite each other's balance update.
        val balanceRef = db.reference.child(FirebasePaths.pettyCashWalletBalance(branchId))
        var newBalance = 0.0
        balanceRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val current = currentData.getValue(Double::class.java) ?: 0.0
                newBalance = current - finalSettledAmount
                currentData.value = newBalance
                return Transaction.success(currentData)
            }
            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {}
        })
        // Silent best-effort, same as e.g. sendRemarkPush() elsewhere in this
        // codebase — onSupabaseResult below is already spoken for by the
        // claims.update() mirror right after, and combining two independent
        // async results into one caller-facing callback isn't worth the
        // complexity for a side effect that never blocks/fails the settle
        // either way.
        SupabasePettyCashWriter.mirrorWalletBalance(branchId, newBalance)

        claims.update(requestId, mapOf(
                "status" to PC_STATUS_SETTLED,
                "settledByUid" to uid,
                "settledByName" to name,
                "settledAt" to now,
                "settledAmount" to finalSettledAmount,
                "paymentMethod" to paymentMethod,
                "transactionId" to trxId,
                "updatedAt" to now
            ), onSupabaseResult)
    }

    // ── Reject (can happen at Pending or Acknowledged stage only) ───────────

    suspend fun rejectRequest(branchId: String, requestId: String, reason: String, onSupabaseResult: (Boolean) -> Unit = {}): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName()
        val now = System.currentTimeMillis()
        claims.get(requestId) ?: throw IllegalStateException("Request not found")
        claims.update(requestId, mapOf(
                "status" to PC_STATUS_REJECTED,
                "rejectedByUid" to uid,
                "rejectedByName" to name,
                "rejectedAt" to now,
                "rejectReason" to reason,
                "updatedAt" to now
            ), onSupabaseResult)
    }

    // ── Accounts: deposit fund into the branch wallet ────────────────────────

    suspend fun depositFund(
        branchId: String,
        amount: Double,
        source: String,
        reference: String,
        remarks: String,
        onSupabaseResult: (Boolean) -> Unit = {}
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
        val deposit = PettyCashDeposit(
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
        depositRef.setValue(deposit).await()

        // Firebase is the source of truth and already has both writes above;
        // Supabase is a best-effort alternative copy — never blocks or fails
        // the deposit. Balance first, then the deposit row, chained rather
        // than parallel purely to keep this simple (see SupabasePettyCashWriter's
        // doc comment) — by the time either completes the caller has normally
        // already popped back off this screen, so the extra latency of doing
        // them one after another instead of concurrently doesn't cost anything
        // observable.
        SupabasePettyCashWriter.mirrorWalletBalance(branchId, newBalance) {
            SupabasePettyCashWriter.mirrorDeposit(branchId, deposit, onSupabaseResult)
        }
    }
}
