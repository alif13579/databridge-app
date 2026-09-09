package com.cloudx.databridge

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Scanner approval queue — agent scan -> pending -> Incharge approve/reject.
 *
 * Firebase shape (per scan at scanned/self_assigned/{ownerUid}/{scanKey}):
 *   scan_text, scan_at (ordering key, client capture time), manual, user_id,
 *   status: pending | approved | rejected, branch_id, employee_id, agent_name,
 *   reviewed_by, reviewed_at, sheet_written (bool).
 *
 * Role resolution mirrors Leave (role-NAME match "Incharge", branch-scoped
 * shared queue — whoever acts first resolves it). No per-branch fixed
 * assignee, no permission-catalog toggle.
 *
 * Approve side-effect: connectors sheet write via
 * [ScannerSheetRepository.writeScannedValue] — agent branch's scanner
 * connection, employee_id lookup, write-column blank row (append fallback).
 * Sheet failures never block the approval itself; they land in
 * scan_sheet_retry/{branchId}/{pushId} for later retry.
 */
data class QueuedScan(
    val ownerUid: String = "",
    val firebaseKey: String = "",
    val code: String = "",
    val scanAt: Long = 0L,
    val manual: Boolean = false,
    val status: String = "pending",
    val branchId: String = "",
    val employeeId: String = "",
    val agentName: String = "",
    val agentUid: String = "",
    val reviewedBy: String = "",
    val reviewedAt: Long = 0L,
    val sheetWritten: Boolean = false
)

sealed class ScanQueueState {
    object Loading : ScanQueueState()
    data class Success(
        val scans: List<QueuedScan>,
        val isIncharge: Boolean,
        val retryCount: Int = 0
    ) : ScanQueueState() {
        val pending: List<QueuedScan> get() = scans.filter { it.status.equals("pending", ignoreCase = true) }
        val approved: List<QueuedScan> get() = scans.filter { it.status.equals("approved", ignoreCase = true) }
        val rejected: List<QueuedScan> get() = scans.filter { it.status.equals("rejected", ignoreCase = true) }
    }
    data class Error(val message: String) : ScanQueueState()
}

data class ScanBulkResult(
    val acted: Int,
    val sheetOk: Int,
    val sheetRetry: Int,
    val failed: Int
)

class ScanApprovalViewModel : ViewModel() {

    private val db = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val opsZone = ZoneId.of("Asia/Dhaka")

    private val httpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val _state = MutableLiveData<ScanQueueState>(ScanQueueState.Loading)
    val state: LiveData<ScanQueueState> = _state

    private var branchId: String = ""

    fun load(branchId: String) {
        this.branchId = branchId
        _state.value = ScanQueueState.Loading
        viewModelScope.launch {
            try {
                val isIncharge = withContext(Dispatchers.IO) { resolveIncharge(branchId) }
                val scans = withContext(Dispatchers.IO) { loadBranchScans(branchId) }
                val retryCount = withContext(Dispatchers.IO) { retryCount(branchId) }
                _state.value = ScanQueueState.Success(scans, isIncharge, retryCount)
            } catch (e: Exception) {
                _state.value = ScanQueueState.Error(e.message ?: "Failed to load scan queue")
            }
        }
    }

    fun refresh() = load(branchId)

    /** Same tolerant role-NAME match as Leave — admin-typed names vary in case. */
    private suspend fun resolveIncharge(branchId: String): Boolean {
        val uid = auth.currentUser?.uid.orEmpty()
        if (uid.isBlank()) return false
        val companyInfo = db.reference.child(FirebasePaths.userCompanyInfo(uid)).get().await()
        val roleId = companyInfo.child("role_id").getValue(String::class.java).orEmpty()
        val branchIds = companyInfo.child("branch_ids").children.mapNotNull { it.getValue(String::class.java) }
        if (roleId.isBlank() || branchId !in branchIds) return false
        val roleName = db.reference.child(FirebasePaths.role(roleId)).child("name")
            .get().await().getValue(String::class.java).orEmpty().trim()
        return roleName.equals(LEAVE_ACKNOWLEDGER_ROLE_NAME, ignoreCase = true)
    }

    /**
     * Fan-in read: users in this branch -> each one's scanned/self_assigned
     * list. Branch sizes are small (tens of agents), so one users read +
     * one read per branch member is fine. Scans that predate the
     * branch_id stamp fall back to their owner's branch membership.
     */
    private suspend fun loadBranchScans(branchId: String): List<QueuedScan> = coroutineScope {
        val usersSnap = db.reference.child("users").get().await()
        val memberUids = usersSnap.children.mapNotNull { child ->
            val uid = child.key ?: return@mapNotNull null
            val ids = child.child("profile/company_info/branch_ids").children
                .mapNotNull { it.getValue(String::class.java) }
            // users/ path stores company_info directly under profile in some
            // accounts and under profile/company_info in others — check both.
            val idsAlt = child.child("profile/branch_ids").children
                .mapNotNull { it.getValue(String::class.java) }
            val ciAlt = child.child("company_info/branch_ids").children
                .mapNotNull { it.getValue(String::class.java) }
            if (branchId in ids || branchId in idsAlt || branchId in ciAlt) uid else null
        }.toSet()
        if (memberUids.isEmpty()) return@coroutineScope emptyList()

        val deferred = memberUids.map { uid ->
            async(Dispatchers.IO) {
                runCatching {
                    db.reference.child(RunRoutePaths.userScans(uid)).get().await()
                }.getOrNull()?.children?.mapNotNull { child ->
                    val scanText = child.child("scan_text").getValue(String::class.java) ?: ""
                    if (scanText.isBlank()) return@mapNotNull null
                    val stampedBranch = child.child("branch_id").getValue(String::class.java).orEmpty()
                    // Old scans without a stamp belong to this branch via
                    // their owner; new scans must match this branch.
                    if (stampedBranch.isNotBlank() && stampedBranch != branchId) return@mapNotNull null
                    QueuedScan(
                        ownerUid = uid,
                        firebaseKey = child.key.orEmpty(),
                        code = scanText,
                        scanAt = child.child("scan_at").getValue(Long::class.java) ?: 0L,
                        manual = child.child("manual").getValue(Boolean::class.java) ?: false,
                        status = child.child("status").getValue(String::class.java) ?: "pending",
                        branchId = stampedBranch.ifBlank { branchId },
                        employeeId = child.child("employee_id").getValue(String::class.java).orEmpty(),
                        agentName = child.child("agent_name").getValue(String::class.java).orEmpty(),
                        agentUid = child.child("user_id").getValue(String::class.java)?.ifBlank { uid } ?: uid,
                        reviewedBy = child.child("reviewed_by").getValue(String::class.java).orEmpty(),
                        reviewedAt = child.child("reviewed_at").getValue(Long::class.java) ?: 0L,
                        sheetWritten = child.child("sheet_written").getValue(Boolean::class.java) ?: false
                    )
                }.orEmpty()
            }
        }
        deferred.awaitAll().flatten().sortedByDescending { it.scanAt }
    }

    private suspend fun retryCount(branchId: String): Int {
        return runCatching {
            db.reference.child(scanRetryPath(branchId)).get().await().childrenCount.toInt()
        }.getOrDefault(0)
    }

    // ── Single actions ───────────────────────────────────────────────────

    fun approve(scan: QueuedScan, appContext: Context, done: (ok: Boolean, sheet: String) -> Unit) {
        viewModelScope.launch {
            try {
                val me = auth.currentUser?.uid.orEmpty()
                val now = System.currentTimeMillis()
                db.reference.child(RunRoutePaths.scanItem(scan.ownerUid, scan.firebaseKey))
                    .updateChildren(mapOf(
                        "status" to "approved",
                        "reviewed_by" to me,
                        "reviewed_at" to now
                    )).await()
                val sheet = withContext(Dispatchers.IO) { writeToSheet(appContext, scan) }
                done(true, sheet)
            } catch (e: Exception) {
                done(false, e.message ?: "Approve failed")
            } finally {
                refresh()
            }
        }
    }

    fun reject(scan: QueuedScan, done: (ok: Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val me = auth.currentUser?.uid.orEmpty()
                db.reference.child(RunRoutePaths.scanItem(scan.ownerUid, scan.firebaseKey))
                    .updateChildren(mapOf(
                        "status" to "rejected",
                        "reviewed_by" to me,
                        "reviewed_at" to System.currentTimeMillis()
                    )).await()
                done(true)
            } catch (e: Exception) {
                done(false)
            } finally {
                refresh()
            }
        }
    }

    // ── Bulk actions (single + bulk full way) ────────────────────────────

    fun approveAll(scans: List<QueuedScan>, appContext: Context, done: (ScanBulkResult) -> Unit) {
        viewModelScope.launch {
            var acted = 0; var sheetOk = 0; var sheetRetry = 0; var failed = 0
            val me = auth.currentUser?.uid.orEmpty()
            val now = System.currentTimeMillis()
            for (scan in scans) {
                try {
                    db.reference.child(RunRoutePaths.scanItem(scan.ownerUid, scan.firebaseKey))
                        .updateChildren(mapOf(
                            "status" to "approved",
                            "reviewed_by" to me,
                            "reviewed_at" to now
                        )).await()
                    acted++
                    val sheet = withContext(Dispatchers.IO) { writeToSheet(appContext, scan) }
                    if (sheet.isBlank()) sheetOk++ else sheetRetry++
                } catch (_: Exception) {
                    failed++
                }
            }
            refresh()
            done(ScanBulkResult(acted, sheetOk, sheetRetry, failed))
        }
    }

    fun rejectAll(scans: List<QueuedScan>, done: (acted: Int, failed: Int) -> Unit) {
        viewModelScope.launch {
            var acted = 0; var failed = 0
            val me = auth.currentUser?.uid.orEmpty()
            val now = System.currentTimeMillis()
            for (scan in scans) {
                try {
                    db.reference.child(RunRoutePaths.scanItem(scan.ownerUid, scan.firebaseKey))
                        .updateChildren(mapOf(
                            "status" to "rejected",
                            "reviewed_by" to me,
                            "reviewed_at" to now
                        )).await()
                    acted++
                } catch (_: Exception) {
                    failed++
                }
            }
            refresh()
            done(acted, failed)
        }
    }

    // ── Sheet write + retry queue ────────────────────────────────────────

    /**
     * Returns "" on success, else a short reason (and the scan is queued
     * for retry). Approval itself is never rolled back on sheet failure.
     */
    private suspend fun writeToSheet(appContext: Context, scan: QueuedScan): String {
        val branch = scan.branchId.ifBlank { branchId }
        var employeeId = scan.employeeId.trim()
        if (employeeId.isBlank()) {
            employeeId = runCatching {
                db.reference.child("users/${scan.ownerUid}/profile/company_info/employee_id")
                    .get().await().getValue(String::class.java)
            }.getOrNull()?.trim().orEmpty()
        }
        if (branch.isBlank()) return "no branch"
        if (employeeId.isBlank()) {
            enqueueRetry(branch, scan, "no employee_id")
            return "no employee_id"
        }
        // All-in-one: Scanner 🔌 bindings only (legacy purpose/kind
        // connections are no longer read — convert them to libraries).
        val boundResult = writeViaBindings(appContext, branch, scan, employeeId)
        if (boundResult != null) return boundResult
        enqueueRetry(branch, scan, "no scanner binding — Scanner 🔌 থেকে sheet bind করুন")
        return "no scanner binding"
    }

    /**
     * Library-bound sheet write (Scanner 🔌 bindings).
     * Returns null when no binding covers this branch/date (caller queues a
     * "bind koro" reason); "" on success; else a short reason (already
     * queued for retry).
     */
    private suspend fun writeViaBindings(
        appContext: Context,
        branch: String,
        scan: QueuedScan,
        employeeId: String,
    ): String? {
        val bindings = runCatching {
            SheetLibraryRepository.loadScannerBindings(branch)
        }.getOrNull().orEmpty().filter {
            it.enabled && it.effectiveLookups().isNotEmpty() && it.effectiveWrites().isNotEmpty()
        }
        if (bindings.isEmpty()) return null
        val libraries = runCatching {
            SheetLibraryRepository.loadLibraries(branch)
        }.getOrNull().orEmpty().filter { it.enabled }.associateBy { it.libraryId }
        val today = LocalDate.now(opsZone)
        val covering = bindings.mapNotNull { b ->
            val lib = libraries[b.libraryId] ?: return@mapNotNull null
            if (!SheetScope.covers(lib.scopeType, lib.scopeMonth, lib.scopeFrom, lib.scopeTo, today)) {
                return@mapNotNull null
            }
            b to lib
        }
        if (covering.isEmpty()) return null // out of scope → legacy fallback
        val token = silentWriteToken(appContext)
        if (token.isNullOrBlank()) {
            enqueueRetry(branch, scan, "no sheet token")
            return "no sheet token"
        }
        var lastReason = "write failed"
        for ((binding, lib) in covering) {
            fun valueOf(field: String): String =
                SheetLibraryRepository.scanFieldValue(
                    field, scan.code.trim(), employeeId, scan.agentName, scan.scanAt)
            val lookups = binding.effectiveLookups().map { m ->
                SheetColRef(m.colRef, m.mode) to valueOf(m.field)
            }
            val writes = binding.effectiveWrites().map { m ->
                SheetColRef(m.colRef, m.mode) to valueOf(m.field)
            }
            when (val out = SheetLibraryRepository.writeBoundValues(
                lib, token, lookups, writes, httpClient)) {
                is ScannerSheetRepository.WriteResult.Success -> {
                    markSheetWritten(scan)
                    return ""
                }
                is ScannerSheetRepository.WriteResult.Failure -> lastReason = out.message
            }
        }
        enqueueRetry(branch, scan, lastReason)
        return lastReason
    }

    private suspend fun markSheetWritten(scan: QueuedScan) {
        runCatching {
            db.reference.child(RunRoutePaths.scanItem(scan.ownerUid, scan.firebaseKey))
                .updateChildren(mapOf("sheet_written" to true)).await()
        }
    }

    private suspend fun enqueueRetry(branch: String, scan: QueuedScan, reason: String) {
        runCatching {
            db.reference.child(scanRetryPath(branch)).push().setValue(mapOf(
                "owner_uid" to scan.ownerUid,
                "scan_key" to scan.firebaseKey,
                "code" to scan.code,
                "branch_id" to branch,
                "employee_id" to scan.employeeId,
                "scan_at" to scan.scanAt,
                "reason" to reason.take(120),
                "created_at" to System.currentTimeMillis()
            )).await()
        }
    }

    /** Manual + auto retry of queued sheet writes. Returns (ok, stillPending). */
    fun retryPendingWrites(appContext: Context, done: (ok: Int, pending: Int) -> Unit) {
        viewModelScope.launch {
            var ok = 0
            try {
                val snap = withContext(Dispatchers.IO) {
                    db.reference.child(scanRetryPath(branchId)).get().await()
                }
                for (child in snap.children) {
                    val key = child.key ?: continue
                    val ownerUid = child.child("owner_uid").getValue(String::class.java).orEmpty()
                    val scanKey = child.child("scan_key").getValue(String::class.java).orEmpty()
                    val code = child.child("code").getValue(String::class.java).orEmpty()
                    val empId = child.child("employee_id").getValue(String::class.java).orEmpty()
                    if (ownerUid.isBlank() || scanKey.isBlank() || code.isBlank()) {
                        runCatching { child.ref.removeValue().await() }
                        continue
                    }
                    val retryScan = QueuedScan(
                        ownerUid = ownerUid, firebaseKey = scanKey, code = code,
                        branchId = branchId, employeeId = empId,
                        scanAt = child.child("scan_at").getValue(Long::class.java) ?: 0L,
                    )
                    val err = withContext(Dispatchers.IO) { writeToSheet(appContext, retryScan) }
                    if (err.isBlank()) {
                        runCatching { child.ref.removeValue().await() }
                        ok++
                    }
                }
            } catch (_: Exception) { }
            val pending = withContext(Dispatchers.IO) { retryCount(branchId) }
            refresh()
            done(ok, pending)
        }
    }

    private fun scanRetryPath(branch: String) = "scan_sheet_retry/$branch"

    /** Silent write token for the connectors Google account — no UI possible here. */
    private suspend fun silentWriteToken(appContext: Context): String? = withContext(Dispatchers.IO) {
        try {
            val account = GoogleSignInHelper.restoreOwnAccountIfMatching(
                appContext, "connectors_google_account",
                listOf(com.google.android.gms.common.api.Scope(ConfigSheetDriveApi.SCOPE_SHEETS_WRITE))
            ) ?: return@withContext null
            val acct = account.account ?: return@withContext null
            com.google.android.gms.auth.GoogleAuthUtil.getToken(
                appContext, acct, ConfigSheetDriveApi.OAUTH_SCOPE_WRITE
            )
        } catch (_: Exception) {
            null
        }
    }
}
