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
import kotlinx.coroutines.coroutineScope
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

data class StatusBreakdownItem(
    val key:     String,
    val label:   String,
    val color:   Int,
    val count:   Int,
    val percent: Int,
)

sealed class DashboardState {
    object Loading : DashboardState()
    data class Success(
        val stats:     DashboardStats,
        val agents:    List<AgentStat>,
        val role:      String,
        val breakdown: List<StatusBreakdownItem>,
    ) : DashboardState()
    data class Error(val message: String) : DashboardState()
}

data class DateRange(val startTs: Long, val endTs: Long, val label: String)

data class BranchOption(val id: String, val name: String)

// ── ViewModel ────────────────────────────────────────────────────────────────

class DashboardViewModel : ViewModel() {

    private val db   = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val _state = MutableLiveData<DashboardState>(DashboardState.Loading)
    val state: LiveData<DashboardState> = _state

    private val _dateRange = MutableLiveData(todayRange())
    val dateRange: LiveData<DateRange> = _dateRange

    // Branches the CURRENT user has access to (RbacManager.current.branchIds — their own
    // assignment, never company-wide) with names resolved for display. Empty
    // selectedBranchIds means "all of my branches", same convention as CallCenterFragment.
    private val _availableBranches = MutableLiveData<List<BranchOption>>(emptyList())
    val availableBranches: LiveData<List<BranchOption>> = _availableBranches

    private val _selectedBranchIds = MutableLiveData<Set<String>>(emptySet())
    val selectedBranchIds: LiveData<Set<String>> = _selectedBranchIds

    init {
        loadAvailableBranches()
    }

    /** Resolves display names for the current user's OWN branch assignment only — a small,
     *  bounded fetch (at most however many branches this one user has), not a company-wide
     *  query. Mirrors CallCenterFragment.setupBranchDropdown()'s per-id name lookup. */
    private fun loadAvailableBranches() {
        val ids = RbacManager.current.branchIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val options = ids.map { id ->
                async(Dispatchers.IO) {
                    val name = runCatching {
                        db.reference.child("branches/$id/name").get().await()
                            .getValue(String::class.java)
                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: id
                    BranchOption(id, name)
                }
            }.awaitAll().sortedBy { it.name }
            _availableBranches.value = options
        }
    }

    /** Empty set = no filter = all of the user's own branches. */
    fun setSelectedBranchIds(ids: Set<String>) {
        _selectedBranchIds.value = ids
        load(_dateRange.value ?: todayRange())
    }

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
    // Sourced from courier/remarks_by_userId/{uid}/push_{yyyyMMdd}_{consignmentId} — the secondary
    // per-user index WorkerSpaceFragment/CallCenterFragment write alongside every
    // courier/remarks_by_consignment entry. This sidesteps the three problems the old
    // (removed) loadBranchView()/loadWorkerView() had:
    //   1. Branch scoping: candidate agents are filtered by branch_ids BEFORE any per-agent
    //      read (loadWorkerAgentStats), instead of pulling every branch first.
    //   2. Agent names: resolved from the same users/ read used to find the candidates,
    //      not a separate indexed query per agent.
    //   3. Bounded reads: orderByKey().startAt/endAt on the yyyyMMdd-prefixed key restricts
    //      each per-agent read to the selected date range, not "every run ever".
    //
    // ⚠️ Known limitations of courier/remarks_by_userId as a data source (flagging, not guessing):
    //   - An entry only exists once an agent saves a remark on a consignment — there's no
    //     "assigned but not yet actioned" entry. So DashboardStats.pending / AgentStat.pending
    //     are always 0 here, and "totalParcels" means "actioned in range", not "assigned".
    //   - It carries no run reference, so AgentStat.runId/runStatus and
    //     DashboardStats.openRuns/closedRuns can't come from it — left blank/0 for now.
    /** loadAgentStat's return: the existing bucketed AgentStat, plus a raw final_status ->
     *  count tally from the same read, for the dynamic per-status breakdown in load(). */
    private data class AgentLoadResult(val stat: AgentStat, val rawStatusCounts: Map<String, Int>)

    private fun load(range: DateRange) {
        viewModelScope.launch {
            _state.value = DashboardState.Loading
            val uid = auth.currentUser?.uid
            if (uid.isNullOrBlank()) {
                _state.value = DashboardState.Error("লগইন করা নেই")
                return@launch
            }
            try {
                val roleId = RbacManager.current.roleId.ifBlank { "worker" }
                val startKey = dateKey(range.startTs)
                val endKey = dateKey(range.endTs)

                // Runs alongside the per-agent reads below rather than blocking ahead of them —
                // same pattern WorkerSpaceFragment/CallCenterFragment already use. Keeps whatever
                // was cached before on failure, so a transient miss here degrades to raw status
                // keys as labels rather than an empty breakdown.
                val statusMetaDeferred = async(Dispatchers.IO) { StatusMetaCache.refresh() }

                // "worker" = exactly the single bounded self-read courier/remarks_by_userId was
                // designed for (DashboardFragment already hides the agent table for this
                // role). Any other role gets the branch-scoped breakdown across workers.
                val results = if (roleId == "worker") {
                    listOf(loadAgentStat(uid, UserNameResolver.resolveName(uid), startKey, endKey))
                } else {
                    val selected = _selectedBranchIds.value ?: emptySet()
                    val available = _availableBranches.value?.map { it.id }?.toSet() ?: emptySet()
                    loadWorkerAgentStats(selected.ifEmpty { available }, roleId, startKey, endKey)
                }
                statusMetaDeferred.await()

                val agentStats = results.map { it.stat }

                val stats = DashboardStats(
                    totalParcels = agentStats.sumOf { it.total },
                    delivered    = agentStats.sumOf { it.delivered },
                    onHold       = agentStats.sumOf { it.onHold },
                    returned     = agentStats.sumOf { it.returned },
                    pending      = agentStats.sumOf { it.pending },
                    openRuns     = 0,
                    closedRuns   = 0,
                )

                // Merge every agent's raw final_status tally into one dashboard-level count
                // per distinct status actually seen in range, then resolve each against
                // StatusMetaCache for its admin-configured label/color/priority.
                val mergedRawCounts = mutableMapOf<String, Int>()
                results.forEach { r ->
                    r.rawStatusCounts.forEach { (k, v) -> mergedRawCounts[k] = (mergedRawCounts[k] ?: 0) + v }
                }
                val totalForPercent = mergedRawCounts.values.sum().coerceAtLeast(1)
                val breakdown = mergedRawCounts.entries.map { (key, count) ->
                    val meta = StatusMetaCache.entries[key]
                    StatusBreakdownItem(
                        key     = key,
                        label   = meta?.en?.takeIf { it.isNotBlank() } ?: key,
                        color   = meta?.color ?: android.graphics.Color.GRAY,
                        count   = count,
                        percent = (count * 100) / totalForPercent,
                    )
                }.sortedWith(
                    compareByDescending<StatusBreakdownItem> { StatusMetaCache.entries[it.key]?.priority ?: 0 }
                        .thenByDescending { it.count }
                )

                _state.value = DashboardState.Success(
                    stats     = stats,
                    agents    = agentStats.sortedByDescending { it.delivered },
                    role      = roleId,
                    breakdown = breakdown,
                )
            } catch (e: Exception) {
                _state.value = DashboardState.Error(e.message ?: "Dashboard load ব্যর্থ হয়েছে")
            }
        }
    }

    /** One agent's delivered/onHold/returned counts for [startKey]..[endKey] (both yyyyMMdd,
     *  inclusive) from a single bounded read of courier/remarks_by_userId/{uid}, plus a raw
     *  final_status -> count tally from that same read (see AgentLoadResult). pending is
     *  always 0 and runId/runStatus are always blank — see the limitations note above load(). */
    private suspend fun loadAgentStat(
        uid: String, name: String, startKey: String, endKey: String
    ): AgentLoadResult {
        val snap = withContext(Dispatchers.IO) {
            runCatching {
                db.reference.child("courier/remarks_by_userId/$uid")
                    .orderByKey()
                    .startAt("push_$startKey")
                    .endAt("push_$endKey~") // '~' sorts after any consignmentId suffix that day
                    .get().await()
            }.getOrNull()
        }

        var delivered = 0
        var onHold = 0
        var returned = 0
        val rawCounts = mutableMapOf<String, Int>()
        snap?.children?.forEach { entry ->
            val statusKey = entry.child("final_status").getValue(String::class.java).orEmpty()
            when (bucketForStatus(statusKey)) {
                "delivered" -> delivered++
                "on_hold"   -> onHold++
                "returned"  -> returned++
                else        -> {} // verify_request / blank / unrecognized — not counted
            }
            // Same exclusions as bucketForStatus: verify_request isn't a final delivery
            // outcome (config/statusMeta/{key}/updatesParcelStatus=false), so it's left out
            // of the breakdown too, not just the delivered/onHold/returned buckets. Blank is
            // grouped under an explicit label rather than silently dropped.
            val trimmed = statusKey.trim()
            if (trimmed.isNotBlank() && !isVerifyRequestStatus(trimmed)) {
                rawCounts[trimmed] = (rawCounts[trimmed] ?: 0) + 1
            } else if (trimmed.isBlank()) {
                rawCounts["(no status)"] = (rawCounts["(no status)"] ?: 0) + 1
            }
        }

        val stat = AgentStat(
            agentId   = uid,
            agentName = name,
            runId     = "",
            runStatus = "",
            delivered = delivered,
            onHold    = onHold,
            returned  = returned,
            pending   = 0,
        )
        return AgentLoadResult(stat, rawCounts)
    }

    /** Workers (role_id == "worker") whose branch_ids intersect [branchFilter], each read via
     *  loadAgentStat in parallel. Empty branchFilter + non-admin viewer -> returns nothing
     *  rather than risk a company-wide read (the exact bug loadBranchView() had). */
    private suspend fun loadWorkerAgentStats(
        branchFilter: Set<String>, viewerRoleId: String, startKey: String, endKey: String
    ): List<AgentLoadResult> {
        if (branchFilter.isEmpty() && viewerRoleId != "admin") return emptyList()

        val usersSnap = withContext(Dispatchers.IO) {
            runCatching { db.reference.child("users").get().await() }.getOrNull()
        } ?: return emptyList()

        val candidates = usersSnap.children.mapNotNull { child ->
            val candidateUid = child.key ?: return@mapNotNull null
            val info = child.child("profile/company_info")
            if (info.child("role_id").getValue(String::class.java) != "worker") return@mapNotNull null
            val branchIds = info.child("branch_ids").children.mapNotNull { it.getValue(String::class.java) }
            if (branchFilter.isNotEmpty() && branchIds.none { it in branchFilter }) return@mapNotNull null
            val name = child.child("profile/name").getValue(String::class.java)
                ?.trim()?.takeIf { it.isNotBlank() } ?: candidateUid
            candidateUid to name
        }

        return coroutineScope {
            candidates.map { (candidateUid, name) ->
                async { loadAgentStat(candidateUid, name, startKey, endKey) }
            }.awaitAll()
        }.filter { it.stat.total > 0 } // hide workers with nothing actioned in this range
    }

    /** [ts] (epoch ms) as yyyyMMdd — must match the write side's date-key format exactly
     *  (WorkerSpaceFragment.remarksIndexDateKeyYyyyMmDd / CallCenterFragment's twin). */
    private fun dateKey(ts: Long): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ENGLISH).format(java.util.Date(ts))

    /** Buckets a courier/remarks_by_userId final_status into delivered/on_hold/returned. Status keys
     *  are admin-configurable (config/statusMeta), so this matches by keyword rather than a
     *  fixed key list — same approach the DataBridge Chrome extension's reconciliation
     *  highlighter uses for the Hermes status badges (HOLD/RETURN/DRTO/PARTIAL/EXCHANGE).
     *  verify_request and anything unrecognized return null (uncounted, see load()'s note). */
    private fun bucketForStatus(rawKey: String): String? {
        val k = rawKey.trim()
        if (k.isBlank() || isVerifyRequestStatus(k)) return null
        return when {
            k.contains("deliver", ignoreCase = true) -> "delivered"
            k.contains("hold", ignoreCase = true) -> "on_hold"
            Regex("return|drto|partial|exchange", RegexOption.IGNORE_CASE).containsMatchIn(k) -> "returned"
            else -> null
        }
    }
}
