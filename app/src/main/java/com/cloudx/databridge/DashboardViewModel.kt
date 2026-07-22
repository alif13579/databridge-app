package com.cloudx.databridge

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
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
    //
    // ⚠️ Data-loading logic intentionally removed for now (huge/unscoped Firebase reads —
    // see conversation notes). Issues found before removal, to revisit when redesigning:
    //   1. loadBranchView() queried courier/run_routes/delivery_run directly, filtered
    //      ONLY by date — no branch scoping at all. Pulled ALL branches company-wide,
    //      and (worse than a perf issue) meant branch_manager/branch_incharge roles
    //      would see every branch's data, not just their own.
    //   2. Agent display names were resolved via one separate indexed Firebase query
    //      PER unique agent (N+1), instead of a single bulk users_by_systemId fetch
    //      (the pattern already used elsewhere — see UserNameResolver/ensureAgentNameMap).
    //   3. loadWorkerView() fetched EVERY run the worker has ever had (unbounded by
    //      date at the fetch level) and only filtered by date AFTER fetching each one
    //      in full — wasteful and grows worse the longer a worker has been active.
    //
    // Structure kept as-is (DateRange/DashboardStats/AgentStat/DashboardState, and the
    // state/dateRange/setDateRange/refresh public API) so the Fragment keeps compiling
    // unchanged, and a properly-scoped version can be dropped back into load() later.

    private fun load(range: DateRange) {
        viewModelScope.launch {
            _state.value = DashboardState.Error("Dashboard is temporarily disabled while the data-loading approach is redesigned.")
        }
    }
}
