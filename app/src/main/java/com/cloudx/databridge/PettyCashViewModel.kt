package com.cloudx.databridge

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Petty Cash Management — central Supabase read/write + role resolution.
 *
 * Supabase tables (public schema):
 *   claims                                    -> ClaimInfo (via ClaimsRepository)
 *   petty_cash_deposits                       -> PettyCashDeposit (via SupabasePettyCashReader/Writer)
 *   petty_cash_wallet_balance (one row/branch)-> Double (via SupabasePettyCashReader/Writer)
 *   branches                                  -> Branch role assignments (staff/poc/accountant)
 *   users                                     -> signed-in user's name + system_id
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
        val deposits: List<PettyCashDeposit>,
        val walletBalance: Double,
        val branch: Branch
    )

    private val auth = FirebaseAuth.getInstance()
    // Live claim save/read is Supabase-only (ClaimsRepository). The one-time
    // Firebase index migration tool lives in FirebaseClaimsIndexMigration and
    // is unrelated to this ViewModel.
    private val claims = ClaimsRepository()

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
                        // Requests come from Supabase's public.claims (see
                        // ClaimsRepository) — deposits, wallet balance and the
                        // branch row come from their own Supabase tables now,
                        // not Firebase. All four are independent reads.
                        val requestsDeferred = async {
                            claims.search(ClaimsReportFilter(
                                branchIds = setOf(branchId),
                                fromMillis = 1_000_000_000_000L,
                                toMillis = 9_999_999_999_999L
                            ))
                        }
                        val depositsDeferred = async { SupabasePettyCashReader.fetchDeposits(branchId) }
                        val balanceDeferred  = async { SupabasePettyCashReader.fetchWalletBalance(branchId) }
                        val branchDeferred   = async { SupabasePettyCashReader.fetchBranch(branchId) }
                        LoadResult(requestsDeferred.await(), depositsDeferred.await(), balanceDeferred.await(), branchDeferred.await())
                    }
                }

                val claimReport = loaded.report

                val requests = claimReport.claims.map { it.asPettyCashRequest() }

                // Reader already returns newest-first; keep the sort explicit
                // so the Deposit History screen's contract doesn't depend on
                // query ordering alone.
                val deposits = loaded.deposits.sortedByDescending { it.timestamp }

                val walletBalance = loaded.walletBalance

                val branch = loaded.branch
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

    /** Fetches the signed-in user's real display name from their Supabase users
     *  row — role names ("Manager", "Cash POC") are not person names and
     *  shouldn't be stored as the actor on an approval step. */
    private suspend fun currentUserName(): String {
        if (auth.currentUser?.uid.isNullOrBlank()) return ""
        return runCatching { SupabasePettyCashReader.fetchCurrentUser().name }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: auth.currentUser?.displayName.orEmpty()
    }

    // Digits-only — the canonical identity for claims (see ClaimInfo.
    // agentSystemId), resolved from the signed-in user's Supabase users row.
    private suspend fun currentSystemId(): String {
        return SupabasePettyCashReader.fetchCurrentUser().systemId.trim()
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
        storeId: String = "",
        storeName: String = "",
        pickupCount: Int = 0,
        vehicle: String = "",
        fromArea: String = "",
        toArea: String = "",
        attemptQuantity: Int = 0,
        deliveredQuantity: Int = 0,
        cidOrMerchant: String = "",
        requestedDate: Long = 0L,
        onSupabaseResult: (Boolean) -> Unit = {}
    ): Result<String> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Requester" }
        val now = System.currentTimeMillis()
        val systemId = currentSystemId()
        require(systemId.isNotBlank()) { "Your system ID is missing. Please contact an administrator." }
        val claim = claims.create(ClaimInfo(
            branchId = branchId,
            employeeName = name,
            agentSystemId = systemId,
            workerUid = uid, workerRole = workerRole, type = category,
            category = category,
            consignmentId = consignmentId,
            storeId = storeId,
            storeName = storeName,
            pickupCount = pickupCount,
            vehicle = vehicle, fromArea = fromArea, toArea = toArea,
            attemptQuantity = attemptQuantity, deliveredQuantity = deliveredQuantity, cidOrMerchant = cidOrMerchant,
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
        vehicle: String = "",
        fromArea: String = "",
        toArea: String = "",
        attemptQuantity: Int = 0,
        deliveredQuantity: Int = 0,
        cidOrMerchant: String = "",
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
                "vehicle" to vehicle,
                "fromArea" to fromArea,
                "toArea" to toArea,
                "attemptQuantity" to attemptQuantity,
                "deliveredQuantity" to deliveredQuantity,
                "cidOrMerchant" to cidOrMerchant,
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

    suspend fun acknowledgeRequest(branchId: String, requestId: String, comment: String = "", approvedAmount: Double? = null, onSupabaseResult: (Boolean) -> Unit = {}): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Staff" }
        val actorSystemId = currentSystemId()
        val now = System.currentTimeMillis()
        claims.get(requestId) ?: throw IllegalStateException("Request not found")
        val updates = mutableMapOf<String, Any?>(
                "status" to PC_STATUS_ACKNOWLEDGED,
                "staffByUid" to uid,
                "staffBySystemId" to actorSystemId,
                "staffByName" to name,
                "staffAt" to now,
                "staffComment" to comment,
                "updatedAt" to now
            )
        // Staff can optionally pre-set the amount here -- Cash POC's Approve step still
        // prefills from (and can override) whatever ends up in approvedAmount, so this is
        // a convenience default for POC, not a final figure.
        if (approvedAmount != null) updates["approvedAmount"] = approvedAmount
        claims.update(requestId, updates, onSupabaseResult)
    }

    // ── Cash POC: approve a request (2nd approval) ──────────────────────────

    suspend fun approveRequest(branchId: String, requestId: String, comment: String = "", approvedAmount: Double? = null, onSupabaseResult: (Boolean) -> Unit = {}): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid.orEmpty()
        val name = currentUserName().ifBlank { "Cash POC" }
        val actorSystemId = currentSystemId()
        val now = System.currentTimeMillis()
        val existing = claims.get(requestId)?.asPettyCashRequest() ?: throw IllegalStateException("Request not found")
        // Defaults to the originally requested amount when the caller doesn't
        // specify one, e.g. approving in full without touching the amount field.
        val finalApprovedAmount = approvedAmount ?: existing.amount

        claims.update(requestId, mapOf(
                "status" to PC_STATUS_APPROVED,
                "pocApprovedByUid" to uid,
                "pocApprovedBySystemId" to actorSystemId,
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
        val actorSystemId = currentSystemId()
        val now = System.currentTimeMillis()
        claims.get(requestId) ?: throw IllegalStateException("Request not found")
        claims.update(requestId, mapOf(
                "status" to PC_STATUS_SETTLE_IN_PROCESS,
                "settleInProcessByUid" to uid,
                "settleInProcessBySystemId" to actorSystemId,
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
        val actorSystemId = currentSystemId()
        val now = System.currentTimeMillis()
        val existing = claims.get(requestId)?.asPettyCashRequest() ?: throw IllegalStateException("Request not found")
        // Defaults to what Cash POC approved (itself already defaulted to the original
        // claim if POC didn't touch it) when Accounts settles as-is without adjusting.
        val finalSettledAmount = settledAmount ?: existing.approvedAmount.takeIf { it > 0 } ?: existing.amount

        // Read-compute-write on the Supabase wallet row (see
        // SupabasePettyCashWriter's doc comment for the concurrency note —
        // Firebase's atomic transaction has no direct equivalent here).
        // Balance first, then the claim update, matching the original order.
        val currentBalance = SupabasePettyCashReader.fetchWalletBalance(branchId)
        val newBalance = currentBalance - finalSettledAmount
        SupabasePettyCashWriter.saveWalletBalance(branchId, newBalance)

        claims.update(requestId, mapOf(
                "status" to PC_STATUS_SETTLED,
                "settledByUid" to uid,
                "settledBySystemId" to actorSystemId,
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
        val actorSystemId = currentSystemId()
        val now = System.currentTimeMillis()
        claims.get(requestId) ?: throw IllegalStateException("Request not found")
        claims.update(requestId, mapOf(
                "status" to PC_STATUS_REJECTED,
                "rejectedByUid" to uid,
                "rejectedBySystemId" to actorSystemId,
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

        // Read-compute-write on the Supabase wallet row (same concurrency
        // note as settleRequest above).
        val currentBalance = SupabasePettyCashReader.fetchWalletBalance(branchId)
        val newBalance = currentBalance + amount
        SupabasePettyCashWriter.saveWalletBalance(branchId, newBalance)

        // Supabase id column is uuid (see SupabasePettyCashWriter's doc
        // comment) — a random UUID keeps inserts unique without needing the
        // old Firebase push-id scheme.
        val deposit = PettyCashDeposit(
            id = java.util.UUID.randomUUID().toString(),
            amount = amount,
            source = source,
            reference = reference,
            remarks = remarks,
            balanceAfter = newBalance,
            timestamp = now,
            enteredByUid = uid,
            enteredByName = name
        )
        SupabasePettyCashWriter.saveDeposit(branchId, deposit)
        // Both saves above throw on failure, so reaching here means Supabase
        // has the write — same posture as ClaimsRepository (fires true; a
        // failure throws before reaching this line and surfaces via the
        // Result). Keeps PettyCashDepositFundFragment's "✓ Supabase saved"
        // toast working.
        onSupabaseResult(true)
    }
}
