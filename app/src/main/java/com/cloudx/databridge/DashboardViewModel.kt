package com.cloudx.databridge

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Calendar

// ── Data models ──────────────────────────────────────────────────────────────

data class DashboardStats(
    val totalParcels: Int   = 0,
    val delivered:    Int   = 0,
    val onHold:       Int   = 0,
    val returned:     Int   = 0,
    val pending:      Int   = 0,
    val openRuns:     Int   = 0,
    val closedRuns:   Int   = 0,
)

data class AgentStat(
    val agentId:   String,
    val agentName: String,
    val runId:     String,
    val runStatus: String,
    val delivered: Int,
    val onHold:    Int,
    val returned:  Int,
    val pending:   Int,
) {
    val total get() = delivered + onHold + returned + pending
    val deliveryRate get() = if (total > 0) (delivered * 100) / total else 0
}

sealed class DashboardState {
    object Loading : DashboardState()
    data class Success(
        val stats:  DashboardStats,
        val agents: List<AgentStat>,
        val role:   String,
    ) : DashboardState()
    data class Error(val message: String) : DashboardState()
}

data class DateRange(val startTs: Long, val endTs: Long, val label: String)

// ── ViewModel ────────────────────────────────────────────────────────────────

class DashboardViewModel : ViewModel() {

    private val db   = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val _state = MutableLiveData<DashboardState>(DashboardState.Loading)
    val state: LiveData<DashboardState> = _state

    private val _dateRange = MutableLiveData(todayRange())
    val dateRange: LiveData<DateRange> = _dateRange

    // ── Date helpers ─────────────────────────────────────────────────────────

    companion object {
        fun todayRange(): DateRange {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis
            return DateRange(start, start + 86_400_000L - 1, "Today")
        }

        fun yesterdayRange(): DateRange {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -1)
            }
            val start = cal.timeInMillis
            return DateRange(start, start + 86_400_000L - 1, "Yesterday")
        }

        fun last7DaysRange(): DateRange {
            val now = System.currentTimeMillis()
            val start = now - 7 * 86_400_000L
            return DateRange(start, now, "Last 7 Days")
        }

        fun thisMonthRange(): DateRange {
            val cal = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
            }
            return DateRange(cal.timeInMillis, System.currentTimeMillis(), "This Month")
        }

        fun customRange(startTs: Long, endTs: Long) =
            DateRange(startTs, endTs + 86_400_000L - 1, "Custom")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setDateRange(range: DateRange) {
        _dateRange.value = range
        load(range)
    }

    fun refresh() { load(_dateRange.value ?: todayRange()) }

    // ── Core load ─────────────────────────────────────────────────────────────

    private fun load(range: DateRange) {
        _state.value = DashboardState.Loading
        viewModelScope.launch {
            try {
                val roleId = RbacManager.current.roleId
                val uid    = auth.currentUser?.uid

                when {
                    isAdminOrBranch(roleId) -> loadBranchView(range, roleId)
                    isWorker(roleId)        -> loadWorkerView(range, uid)
                    isCallCenter(roleId)    -> loadBranchView(range, roleId) // CC sees same counts
                    else                    -> loadBranchView(range, roleId)
                }
            } catch (e: Exception) {
                _state.value = DashboardState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ── Branch / Admin view ───────────────────────────────────────────────────

    private suspend fun loadBranchView(range: DateRange, roleId: String) {
        val snap = withContext(Dispatchers.IO) {
            db.reference
                .child("courier/run_routes/delivery_run")
                .orderByChild("createdAt")
                .startAt(range.startTs.toDouble())
                .endAt(range.endTs.toDouble())
                .get().await()
        }

        var delivered = 0; var onHold = 0; var returned = 0; var pending = 0
        var openRuns = 0;  var closedRuns = 0
        val agentMap = mutableMapOf<String, MutableList<Pair<String, String>>>()
        // agentMap[agentId] → list of (runId, consignmentStatus)

        snap.children.forEach { runSnap ->
            val runId     = runSnap.key ?: return@forEach
            val runStatus = runSnap.child("status").getValue(String::class.java) ?: "unknown"
            val agentId   = runSnap.child("agentId").getValue(String::class.java) ?: "unknown"

            if (runStatus == "open")   openRuns++   else closedRuns++

            runSnap.child("consignments").children.forEach { conSnap ->
                val status = conSnap.getValue(String::class.java) ?: return@forEach
                bucketStatus(status).also { bucket ->
                    when (bucket) {
                        "delivered" -> delivered++
                        "onHold"    -> onHold++
                        "returned"  -> returned++
                        else        -> pending++
                    }
                    agentMap.getOrPut(agentId) { mutableListOf() }
                        .add(runId to status)
                }
            }
        }

        // Build agent stats (fetch names in parallel)
        val agentStats = agentMap.entries
            .map { (agentId, pairs) ->
                viewModelScope.async(Dispatchers.IO) {
                    val nameSnap = runCatching {
                        db.reference.child("users").orderByChild("profile/company_info/system_id")
                            .equalTo(agentId).limitToFirst(1).get().await()
                    }.getOrNull()
                    val displayName = nameSnap?.children?.firstOrNull()
                        ?.child("profile/name")?.getValue(String::class.java)
                        ?.takeIf { it.isNotBlank() } ?: agentId

                    val runId = pairs.firstOrNull()?.first ?: ""
                    val runStatus = snap.child(runId).child("status")
                        .getValue(String::class.java) ?: "unknown"

                    var d = 0; var h = 0; var r = 0; var p = 0
                    pairs.forEach { (_, s) ->
                        when (bucketStatus(s)) {
                            "delivered" -> d++
                            "onHold"    -> h++
                            "returned"  -> r++
                            else        -> p++
                        }
                    }
                    AgentStat(agentId, displayName, runId, runStatus, d, h, r, p)
                }
            }.awaitAll()
            .sortedByDescending { it.deliveryRate }

        _state.value = DashboardState.Success(
            stats  = DashboardStats(
                totalParcels = delivered + onHold + returned + pending,
                delivered    = delivered,
                onHold       = onHold,
                returned     = returned,
                pending      = pending,
                openRuns     = openRuns,
                closedRuns   = closedRuns,
            ),
            agents = agentStats,
            role   = roleId,
        )
    }

    // ── Worker view ───────────────────────────────────────────────────────────

    private suspend fun loadWorkerView(range: DateRange, uid: String?) {
        if (uid == null) { _state.value = DashboardState.Error("Not logged in"); return }

        val systemId = withContext(Dispatchers.IO) {
            db.reference.child("users/$uid/profile/company_info/system_id")
                .get().await().getValue(String::class.java)?.trim()
        } ?: run {
            _state.value = DashboardState.Error("System ID not found")
            return
        }

        val myRunsSnap = withContext(Dispatchers.IO) {
            db.reference.child("courier/runs_by_agentSystemId/$systemId").get().await()
        }

        var delivered = 0; var onHold = 0; var returned = 0; var pending = 0
        var openRuns = 0;  var closedRuns = 0

        val runsToFetch = myRunsSnap.children.mapNotNull { it.key }

        val runSnaps = runsToFetch.map { runId ->
            viewModelScope.async(Dispatchers.IO) {
                runCatching {
                    db.reference.child("courier/run_routes/delivery_run/$runId").get().await()
                }.getOrNull()
            }
        }.awaitAll()

        runSnaps.filterNotNull().forEach { runSnap ->
            val runCreatedAt = runSnap.child("createdAt").getValue(Long::class.java) ?: 0L
            if (runCreatedAt !in range.startTs..range.endTs) return@forEach

            val runStatus = runSnap.child("status").getValue(String::class.java) ?: "unknown"
            if (runStatus == "open") openRuns++ else closedRuns++

            runSnap.child("consignments").children.forEach { conSnap ->
                val status = conSnap.getValue(String::class.java) ?: return@forEach
                when (bucketStatus(status)) {
                    "delivered" -> delivered++
                    "onHold"    -> onHold++
                    "returned"  -> returned++
                    else        -> pending++
                }
            }
        }

        _state.value = DashboardState.Success(
            stats = DashboardStats(
                totalParcels = delivered + onHold + returned + pending,
                delivered    = delivered,
                onHold       = onHold,
                returned     = returned,
                pending      = pending,
                openRuns     = openRuns,
                closedRuns   = closedRuns,
            ),
            agents = emptyList(), // Workers don't see agent breakdown
            role   = "worker",
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun bucketStatus(status: String): String = when (status.trim().lowercase()) {
        "delivered"                               -> "delivered"
        "on hold"                                 -> "onHold"
        "return", "return requested",
        "paid return", "drto",
        "exchange", "partial delivery"            -> "returned"
        else                                      -> "pending"
    }

    private fun isAdminOrBranch(role: String) =
        role in listOf("admin", "branch_manager", "branch_incharge", "manager")

    private fun isWorker(role: String) =
        role in listOf("worker", "delivery", "delivery_agent")

    private fun isCallCenter(role: String) =
        role in listOf("call_center", "cc", "callcenter")
}
