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
    val earnings:     Double = 0.0,
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
    val earnings:  Double = 0.0,
    val openRuns:  Int = 0,
    val closedRuns: Int = 0,
    // Needed to drill into this row: re-run subordinatePool() with THEM as the viewer
    // (their own role + their own branch scope), not the root viewer's.
    val level:      Int = RoleLevelCache.DEFAULT_LEVEL,
    val roleId:     String = "",
    val branchIds:  List<String> = emptyList(),
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
        // Whether subordinatePool() found ANYONE at all — independent of whether the
        // CURRENT mode (rollup vs flat) happened to produce zero rows, so the Fragment can
        // decide the flat/rollup toggle's visibility without hardcoding role == "manager".
        val hasSubordinates: Boolean = false,
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

    // True only while a load() is in flight AND we already have Success data on screen —
    // lets the Fragment keep showing that data and just spin swipeRefresh's own indicator,
    // instead of the full-screen Loading view (see load()).
    private val _isRefreshing = MutableLiveData(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    // Set when a refresh (i.e. hasExistingData == true in load()) fails — the Fragment
    // shows this as a Snackbar/Toast and calls clearRefreshError(), rather than the old
    // Success data being replaced by the full-screen error view.
    private val _refreshError = MutableLiveData<String?>(null)
    val refreshError: LiveData<String?> = _refreshError

    fun clearRefreshError() { _refreshError.value = null }

    private var loadJob: kotlinx.coroutines.Job? = null

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

    // Rollup toggle: true (default) = one row per immediate-next-tier account, each an
    // aggregate of everyone below that tier (direct + indirect). false = flat list of
    // individual subordinates at any depth. Meaningless for a viewer with nobody below
    // them — DashboardFragment shows the toggle only when Success.hasSubordinates is true.
    private val _rollupMode = MutableLiveData(true)
    val rollupMode: LiveData<Boolean> = _rollupMode

    fun setRollupMode(rollup: Boolean) {
        if (_rollupMode.value == rollup) return
        _rollupMode.value = rollup
        load(_dateRange.value ?: todayRange())
    }

    // ── Drill-down ───────────────────────────────────────────────────────────
    // Tapping a row re-runs subordinatePool()/load() with THAT person as the viewer instead
    // of rebuilding a separate nested-tree data structure — reuses the exact same role_
    // reports_to resolution, just anchored one hop lower each time. The stack is the
    // breadcrumb trail back up to the real logged-in viewer (empty stack).
    data class DrillLevel(val uid: String, val name: String, val level: Int, val roleId: String, val branchIds: List<String>)

    private val _drillStack = MutableLiveData<List<DrillLevel>>(emptyList())
    val drillStack: LiveData<List<DrillLevel>> = _drillStack

    /** Only meaningful to call on a row that's already visible to the current viewer (i.e.
     *  it came from this same subordinatePool() call) — drilling in doesn't check any new
     *  permission, since seeing [roleId]/[branchIds] worth of aggregate for that person
     *  already implied visibility into who makes it up. */
    fun drillInto(uid: String, name: String, level: Int, roleId: String, branchIds: List<String>) {
        _drillStack.value = (_drillStack.value ?: emptyList()) + DrillLevel(uid, name, level, roleId, branchIds)
        load(_dateRange.value ?: todayRange())
    }

    fun drillBack() {
        val stack = _drillStack.value ?: return
        if (stack.isEmpty()) return
        _drillStack.value = stack.dropLast(1)
        load(_dateRange.value ?: todayRange())
    }

    fun drillToRoot() {
        if (_drillStack.value.isNullOrEmpty()) return
        _drillStack.value = emptyList()
        load(_dateRange.value ?: todayRange())
    }

    // ── Expand-in-place (Phase 3: recursive multi-level grouping) ──────────────
    // A row's own chevron shows ITS subordinates nested/indented directly under it in the
    // same list — same subordinatePool()/rollup resolution as drill-down, just rendered in
    // place instead of replacing the whole screen. Lazy: nothing is fetched until a row is
    // actually expanded, so this stays bounded-reads regardless of how many tiers exist.
    private val _expandedRows = MutableLiveData<Map<String, List<AgentStat>>>(emptyMap())
    val expandedRows: LiveData<Map<String, List<AgentStat>>> = _expandedRows

    fun toggleExpand(uid: String, roleId: String, branchIds: List<String>) {
        val current = _expandedRows.value ?: emptyMap()
        if (uid in current) {
            // Collapse: also drop this row's own already-expanded children (recursive
            // prune) so re-expanding later starts clean instead of showing stale grandchildren.
            val toRemove = mutableSetOf(uid)
            var frontier = setOf(uid)
            while (frontier.isNotEmpty()) {
                val next = mutableSetOf<String>()
                frontier.forEach { u -> current[u]?.forEach { child -> if (child.agentId in current) next += child.agentId } }
                toRemove += next
                frontier = next
            }
            _expandedRows.value = current - toRemove
            return
        }
        viewModelScope.launch {
            try {
                val range = _dateRange.value ?: todayRange()
                val startKey = dateKey(range.startTs)
                val endKey = dateKey(range.endTs)
                // Never an admin bypass here, same convention as drill-down — expanding a
                // row resolves THAT person's own subordinates on their own standing, not
                // the root viewer's.
                val subordinates = subordinatePool(uid, roleId, branchIds.toSet(), isAdmin = false)
                val children = if (subordinates.isEmpty()) {
                    emptyList()
                } else if (_rollupMode.value != false) {
                    loadTieredRollups(subordinates, startKey, endKey, range.startTs, range.endTs)
                } else {
                    loadSubordinateAgentStats(subordinates, startKey, endKey, range.startTs, range.endTs)
                }.map { it.stat }
                _expandedRows.value = (_expandedRows.value ?: emptyMap()) + (uid to children)
            } catch (_: Exception) {
                // Silent — an expand failing just leaves the chevron collapsed; the row's
                // own stat (already visible) is unaffected.
            }
        }
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
    //     "assigned but not yet actioned" entry, so "totalParcels" means "actioned in range",
    //     not "assigned". DashboardStats.pending / AgentStat.pending do NOT mean that though —
    //     they're derived (entryCount - delivered - onHold - returned in loadAgentStat), i.e.
    //     "actioned in range but not a final delivery outcome" (verify_request, a blank-status
    //     note, or any other status bucketForStatus doesn't recognize). This is what makes
    //     total (delivered+onHold+returned+pending) always equal entryCount — every entry
    //     found in the date range counts toward the total, whatever its status is.
    //   - It carries no run reference, so AgentStat.runId/runStatus and
    //     DashboardStats.openRuns/closedRuns can't come from it — left blank/0 for now.
    /** loadAgentStat's return: the existing bucketed AgentStat, plus a raw final_status ->
     *  count tally from the same read, for the dynamic per-status breakdown in load().
     *  readErrorMessage carries the LAST internal read failure (if any) from any of
     *  loadAgentStat's 4 reads, so load() can surface it as a visible refreshError toast —
     *  previously these were only logged to error_logs, with the dashboard silently showing
     *  degraded/zeroed numbers and no visible sign anything had failed. */
    private data class AgentLoadResult(
        val stat: AgentStat,
        val rawStatusCounts: Map<String, Int>,
        val readErrorMessage: String? = null,
    )

    private fun load(range: DateRange) {
        loadJob?.cancel()
        _expandedRows.value = emptyMap() // new top-level rows are coming — any old expansion
                                          // would refer to a stale/different row's children
        loadJob = viewModelScope.launch {
            // If we already have data on screen, this is a refresh (pull-to-refresh, a chip
            // tap, or a branch-filter change) — keep that data visible and just flip the
            // swipeRefresh spinner, instead of blanking the whole screen to the Loading view.
            // Only a true first load (nothing shown yet) goes through DashboardState.Loading.
            val hasExistingData = _state.value is DashboardState.Success
            if (hasExistingData) {
                _isRefreshing.value = true
            } else {
                _state.value = DashboardState.Loading
            }

            val uid = auth.currentUser?.uid
            if (uid.isNullOrBlank()) {
                _isRefreshing.value = false
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

                // The overview at the top is the logged-in user's own validation activity.
                // Every role writes remarks_by_userId under its Firebase uid, so reading only
                // branch workers here makes supervisor/support/manager users look empty even
                // when their own indexed validation data exists.
                val selfResult = loadAgentStat(
                    uid,
                    UserNameResolver.resolveName(uid),
                    startKey,
                    endKey,
                    range.startTs,
                    range.endTs
                )

                // The agent table now uses the generic level + reports_to hierarchy (Phase
                // 2/3 of the dynamic role-hierarchy plan, see RbacManager.kt) instead of
                // hardcoded roleId == "worker" / "manager" checks — works for any number of
                // tiers and any role name. Drill-down: if a row further down the org was
                // tapped into, resolve THEIR subordinates instead of the real logged-in
                // viewer's (their own role + branch scope, never the root viewer's selected
                // filter or admin bypass).
                val selected = _selectedBranchIds.value ?: emptySet()
                val available = _availableBranches.value?.map { it.id }?.toSet() ?: emptySet()
                val branchFilter = selected.ifEmpty { available }
                val isAdmin = roleId == "admin"

                val drillStack = _drillStack.value ?: emptyList()
                val effectiveUid: String
                val effectiveRoleId: String
                val effectiveBranchFilter: Set<String>
                val effectiveIsAdmin: Boolean
                if (drillStack.isEmpty()) {
                    effectiveUid = uid
                    effectiveRoleId = roleId
                    effectiveBranchFilter = branchFilter
                    effectiveIsAdmin = isAdmin
                } else {
                    val top = drillStack.last()
                    effectiveUid = top.uid
                    effectiveRoleId = top.roleId
                    effectiveBranchFilter = top.branchIds.toSet()
                    effectiveIsAdmin = false
                }

                val subordinates = subordinatePool(effectiveUid, effectiveRoleId, effectiveBranchFilter, effectiveIsAdmin)
                val teamResults = if (subordinates.isEmpty()) {
                    emptyList()
                } else if (_rollupMode.value != false) {
                    loadTieredRollups(subordinates, startKey, endKey, range.startTs, range.endTs)
                } else {
                    loadSubordinateAgentStats(subordinates, startKey, endKey, range.startTs, range.endTs)
                }
                statusMetaDeferred.await()

                val agentStats = teamResults.map { it.stat }

                val stats = DashboardStats(
                    totalParcels = selfResult.stat.total,
                    delivered    = selfResult.stat.delivered,
                    onHold       = selfResult.stat.onHold,
                    returned     = selfResult.stat.returned,
                    pending      = selfResult.stat.pending,
                    openRuns     = selfResult.stat.openRuns,
                    closedRuns   = selfResult.stat.closedRuns,
                    earnings     = selfResult.stat.earnings,
                )

                // One dashboard-level count per distinct final_status from this logged-in
                // user's own remarks_by_userId range read, then resolve each against
                // StatusMetaCache for its admin-configured label/color/priority.
                val mergedRawCounts = mutableMapOf<String, Int>()
                selfResult.rawStatusCounts.forEach { (k, v) -> mergedRawCounts[k] = v }
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
                    compareByDescending<StatusBreakdownItem> { StatusMetaCache.entries[it.key]?.sortOrder ?: 0 }
                        .thenByDescending { it.count }
                )

                // Always goes through Success, refresh or not — this is what makes a refresh
                // actually show the latest fetch's numbers once it lands, on top of the old
                // data staying visible while it was in flight.
                _state.value = DashboardState.Success(
                    stats     = stats,
                    agents    = agentStats.sortedByDescending { it.delivered },
                    role      = roleId,
                    breakdown = breakdown,
                    hasSubordinates = subordinates.isNotEmpty(),
                )

                // The load as a whole succeeded (no exception reached this far), but one or
                // more of loadAgentStat's individual reads may have failed internally and
                // degraded to zero/empty for that agent (see AgentLoadResult.readErrorMessage) —
                // previously that was only checkable via error_logs, with the dashboard just
                // silently showing fewer/zeroed numbers and no visible sign anything had
                // failed. Surface it as the same refreshError toast a full load()-level
                // failure would use, on top of the Success state above rather than instead of
                // it, since the numbers just shown may be incomplete, not necessarily empty.
                (listOf(selfResult) + teamResults).firstOrNull { it.readErrorMessage != null }?.let { failed ->
                    _refreshError.value = "⚠ কিছু data load হয়নি: ${failed.readErrorMessage}"
                }
            } catch (e: Exception) {
                if (hasExistingData) {
                    // Keep the old Success data on screen — surface the failure as a one-off
                    // event instead of replacing a working dashboard with the full error view.
                    _refreshError.value = e.message ?: "Dashboard reload ব্যর্থ হয়েছে"
                } else {
                    _state.value = DashboardState.Error(e.message ?: "Dashboard load ব্যর্থ হয়েছে")
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** One agent's delivered/onHold/returned counts for [startKey]..[endKey] (both yyyyMMdd,
     *  inclusive) from a single bounded read of courier/remarks_by_userId/{uid}, plus a raw
     *  final_status -> count tally from that same read (see AgentLoadResult). pending is
     *  derived (entryCount - delivered - onHold - returned), and runId/runStatus are always
     *  blank — see the limitations note above load(). */
    private suspend fun loadAgentStat(
        uid: String, name: String, startKey: String, endKey: String,
        rangeStartTs: Long, rangeEndTs: Long,
        level: Int = RoleLevelCache.DEFAULT_LEVEL, roleId: String = "", branchIds: List<String> = emptyList(),
    ): AgentLoadResult {
        var readError: String? = null
        val snap = withContext(Dispatchers.IO) {
            runCatching {
                db.reference.child("courier/remarks_by_userId/$uid")
                    .orderByKey()
                    .startAt("push_$startKey")
                    .endAt("push_$endKey~") // '~' sorts after any consignmentId suffix that day
                    .get().await()
            }.onFailure { e ->
                // A failed read here silently becomes an empty result below (agentStat shows
                // 0 for everything, no error surfaced anywhere) — that's the right degrade
                // for one agent's read failing inside a multi-agent branch view, but it means
                // a genuine cause (e.g. a security-rules permission denial on this specific
                // path) is otherwise invisible. Logging it here doesn't change that graceful
                // degrade; it just makes the real reason checkable via error_logs/{uid} — and
                // readError below also surfaces it as a visible toast via load()'s refreshError.
                readError = "courier/remarks_by_userId/$uid ($startKey-$endKey): ${e.message ?: "read failed"}"
                FirebaseErrorLogger.log(
                    screen = "DashboardViewModel", action = "remarks_by_userId_read",
                    errorMessage = e.message ?: "unknown",
                    extra = mapOf("uid" to uid, "startKey" to startKey, "endKey" to endKey)
                )
            }.getOrNull()
        }

        var delivered = 0
        var onHold = 0
        var returned = 0
        var entryCount = 0
        val rawCounts = mutableMapOf<String, Int>()
        snap?.children?.forEach { entry ->
            entryCount++
            val statusKey = entry.child("final_status").getValue(String::class.java).orEmpty()
            when (bucketForStatus(statusKey)) {
                "delivered" -> delivered++
                "on_hold"   -> onHold++
                "returned"  -> returned++
                else        -> {} // verify_request / blank / unrecognized — not a final
                                   // delivery outcome, so left out of these 3 fixed KPI
                                   // buckets. Still counted in `pending` below and in the
                                   // dynamic breakdown, so it's never invisible to the totals.
            }
            // Dynamic breakdown: EVERY entry counts under its own raw status value, with no
            // exclusions — this intentionally differs from the delivered/on_hold/returned
            // bucketing above, which only cares about final delivery outcomes. Every unique
            // status found in range (verify_request included) gets its own count+percentage
            // slice; blank status groups under an explicit label rather than being dropped.
            val trimmed = statusKey.trim()
            val breakdownKey = trimmed.ifBlank { "(no status)" }
            rawCounts[breakdownKey] = (rawCounts[breakdownKey] ?: 0) + 1
        }

        // memory/{uid}/earnings/earning_{savedAtMs} — the key's own timestamp is just when
        // that record was last saved, NOT the date it represents (MemoryFragment lets a
        // worker backfill a past day via a date picker, so createdAt can differ from the key
        // by anything from seconds to days). Every entry has to be fetched and bucketed by
        // its createdAt field instead — same date field MemoryFragment.updateSum() uses for
        // its own "Today" total — a Firebase-side key range query would bucket by save time,
        // not the date the earning actually belongs to.
        val earningsSnap = withContext(Dispatchers.IO) {
            runCatching { db.reference.child("memory/$uid/earnings").get().await() }
                .onFailure { e ->
                    readError = e.message ?: "memory/earnings read failed"
                    FirebaseErrorLogger.log(
                        screen = "DashboardViewModel", action = "memory_earnings_read",
                        errorMessage = e.message ?: "unknown", extra = mapOf("uid" to uid)
                    )
                }.getOrNull()
        }
        val earnings = earningsSnap?.children?.sumOf { e ->
            val createdAt = e.child("createdAt").numberValue() ?: 0.0
            if (createdAt >= rangeStartTs && createdAt <= rangeEndTs) {
                (e.child("parcelCommission").numberValue() ?: 0.0) +
                (e.child("documentCommission").numberValue() ?: 0.0) +
                (e.child("parcelPickupCommission").numberValue() ?: 0.0) +
                (e.child("documentPickupCommission").numberValue() ?: 0.0)
            } else 0.0
        } ?: 0.0

        // courier/runs_by_agentSystemId/{systemId}/{runType}/{runKey} = status — keyed by
        // company_info/system_id, NOT the Firebase uid (WorkerSpaceFragment.loadData()
        // resolves the same field before calling attachRunsListener()).
        var openRuns = 0
        var closedRuns = 0
        val systemId = withContext(Dispatchers.IO) {
            runCatching {
                db.reference.child("users/$uid/profile/company_info/system_id")
                    .get().await().getValue(String::class.java)?.trim()
            }.onFailure { e ->
                readError = e.message ?: "system_id read failed"
                FirebaseErrorLogger.log(
                    screen = "DashboardViewModel", action = "system_id_read",
                    errorMessage = e.message ?: "unknown", extra = mapOf("uid" to uid)
                )
            }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        if (systemId != null) {
            val runsSnap = withContext(Dispatchers.IO) {
                runCatching { db.reference.child("courier/runs_by_agentSystemId/$systemId").get().await() }
                    .onFailure { e ->
                        readError = e.message ?: "runs_by_agentSystemId read failed"
                        FirebaseErrorLogger.log(
                            screen = "DashboardViewModel", action = "runs_by_agentSystemId_read",
                            errorMessage = e.message ?: "unknown", extra = mapOf("systemId" to systemId)
                        )
                    }.getOrNull()
            }
            // No createdAt-style field on these entries (unlike memory/earnings) — a run's
            // status can still change well after its own date (open -> closed later), so
            // range filtering has to go by the date the run key itself encodes, not by when
            // it was last written. runKey format per the current sheet-sync config:
            // "run_{yyyyMMdd}_{systemId}" — the yyyyMMdd is compared as a string against the
            // same startKey/endKey bounds used for courier/remarks_by_userId above, since
            // same-length zero-padded date strings sort identically to their numeric value.
            // A runType whose keys don't match this shape is silently skipped, not crashed on.
            runsSnap?.children?.forEach { runTypeSnap ->
                runTypeSnap.children.forEach { runEntry ->
                    val key = runEntry.key ?: return@forEach
                    val runDateKey = Regex("^run_(\\d{8})_").find(key)?.groupValues?.get(1) ?: return@forEach
                    if (runDateKey in startKey..endKey) {
                        val status = runEntry.getValue(String::class.java)?.trim().orEmpty()
                        if (status.equals("closed", ignoreCase = true)) closedRuns++ else openRuns++
                    }
                }
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
            // Derived, not hardcoded: whatever this agent actioned in range that ISN'T one
            // of the three final-outcome buckets above (verify_request, a blank-status note,
            // or any other unrecognized status). This guarantees `total`
            // (delivered+onHold+returned+pending) always equals entryCount — i.e. "every
            // entry found in the date range counts toward the total", full stop, regardless
            // of what its status is.
            pending   = entryCount - delivered - onHold - returned,
            earnings  = earnings,
            openRuns  = openRuns,
            closedRuns = closedRuns,
            level     = level,
            roleId    = roleId,
            branchIds = branchIds,
        )
        return AgentLoadResult(stat, rawCounts, readError)
    }

    /** Handles a commission/amount value that may be stored as Long, Int, or Double —
     *  mirrors SalaryModels.kt's numberValue() (file-private there, so re-declared here). */
    private fun com.google.firebase.database.DataSnapshot.numberValue(): Double? =
        getValue(Double::class.java)
            ?: getValue(Long::class.java)?.toDouble()
            ?: getValue(Int::class.java)?.toDouble()

    /** One candidate account found while scanning users/ — enough to match subordinates
     *  against the viewer (and each other) by role + shared branch. */
    private data class SubordinateCandidate(
        val uid: String, val name: String, val branchIds: List<String>, val level: Int, val roleId: String,
    )

    /** role_reports_to/{roleId}/target_roles — an admin-configured policy (Access Manager)
     *  for every account holding that role: "report to whoever holds one of these OTHER
     *  roles, in the same branch". Person-specific overrides (a named individual instead of
     *  a role) are a later phase, not built yet — role-wise only for now. */
    private data class RoleReportsPolicy(val targetRoles: Set<String>)

    /** Resolves everyone beneath [viewerUid] at ANY depth via role_reports_to policy alone —
     *  no individual reports_to, no level/branch inference fallback.
     *
     *  Example: delivery_agent's policy has target_roles = [incharge, supervisor]. An
     *  incharge account then sees every delivery_agent who shares a branch with them
     *  specifically (not every delivery_agent company-wide) — role_reports_to says WHICH
     *  roles can see a delivery_agent's data, branch still scopes it to THAT incharge's own
     *  assigned branch.
     *
     *  A role can itself be a target of another role's policy, chaining upward (e.g.
     *  incharge's own policy could point to supervisor) — walked as a BFS frontier since one
     *  hop can fan out to multiple people, not a single linear chain. Cycle/depth guarded at
     *  10 hops.
     *
     *  An account whose role has no policy pointing anyone at [viewerUid]'s role has no boss
     *  and isn't anyone's subordinate until an admin configures target_roles for it — no
     *  fallback keeps someone visible in the meantime.
     *
     *  [isAdmin] bypasses this resolution entirely — same "admin sees everything below them"
     *  convention as every other admin bypass in this codebase. */
    private suspend fun subordinatePool(
        viewerUid: String, viewerRoleId: String, branchFilter: Set<String>, isAdmin: Boolean,
    ): List<SubordinateCandidate> {
        if (branchFilter.isEmpty() && !isAdmin) return emptyList() // bounded-read safety —
                                                                     // never a company-wide scan
        val usersSnap = withContext(Dispatchers.IO) {
            runCatching { db.reference.child("users").get().await() }.getOrNull()
        } ?: return emptyList()
        val policySnap = withContext(Dispatchers.IO) {
            runCatching { db.reference.child("role_reports_to").get().await() }.getOrNull()
        }

        val all = usersSnap.children.mapNotNull { child ->
            val uid = child.key ?: return@mapNotNull null
            val info = child.child("profile/company_info")
            val roleId = info.child("role_id").getValue(String::class.java) ?: return@mapNotNull null
            val level = RoleLevelCache.levelOf(roleId)
            val branchIds = info.child("branch_ids").children.mapNotNull { it.getValue(String::class.java) }
            val name = child.child("profile/name").getValue(String::class.java)
                ?.trim()?.takeIf { it.isNotBlank() } ?: uid
            SubordinateCandidate(uid, name, branchIds, level, roleId)
        }
        val byRole = all.groupBy { it.roleId }

        val policies = policySnap?.children?.mapNotNull { roleSnap ->
            val roleId = roleSnap.key ?: return@mapNotNull null
            val targetRoles = roleSnap.child("target_roles").children.mapNotNull { it.key }.toSet()
            roleId to RoleReportsPolicy(targetRoles)
        }?.toMap() ?: emptyMap()

        // Which roles this person's OWN role reports to, one hop — e.g. delivery_agent's
        // policy might say [incharge, supervisor].
        fun targetRolesOf(roleId: String): Set<String> = policies[roleId]?.targetRoles ?: emptySet()

        fun roleChainReachesViewerRole(startRoleId: String): Boolean {
            var frontier = targetRolesOf(startRoleId)
            repeat(10) {
                if (viewerRoleId in frontier) return true
                val next = mutableSetOf<String>()
                frontier.forEach { next += targetRolesOf(it) }
                if (next.isEmpty()) return false
                frontier = next
            }
            return false // cycle/depth guard tripped — treat as unresolved, not a match
        }

        // Cache role-chain results per distinct role (not per person) — the chain only
        // depends on the ROLE, so this avoids re-walking it for every single employee.
        val roleReachesViewer = mutableMapOf<String, Boolean>()

        return all.filter { c ->
            if (c.uid == viewerUid) return@filter false
            if (isAdmin) return@filter true
            val reaches = roleReachesViewer.getOrPut(c.roleId) { roleChainReachesViewerRole(c.roleId) }
            reaches && c.branchIds.any { it in branchFilter }
        }
    }

    /** Every already-resolved subordinate (subordinatePool() — level+branch or explicit
     *  reports_to, any depth), each read via loadAgentStat in parallel. */
    private suspend fun loadSubordinateAgentStats(
        candidates: List<SubordinateCandidate>, startKey: String, endKey: String,
        rangeStartTs: Long, rangeEndTs: Long,
    ): List<AgentLoadResult> {
        return coroutineScope {
            candidates.map { c ->
                async { loadAgentStat(c.uid, c.name, startKey, endKey, rangeStartTs, rangeEndTs, c.level, c.roleId, c.branchIds) }
            }.awaitAll()
        }.filter { it.stat.total > 0 || it.stat.earnings > 0 || it.stat.openRuns + it.stat.closedRuns > 0 }
            // hide subordinates with nothing actioned, earned, or run in this range — same
            // convention as before, now applying to whoever the candidate turned out to be
    }

    /** Rollup view: one row per person at the viewer's IMMEDIATE next tier down (whichever
     *  level that turns out to be — dynamically the lowest level number found among
     *  [candidates], never a specific role name), each row an AGGREGATE (delivered/onHold/
     *  returned/pending, earnings, openRuns/closedRuns) of everyone deeper than that tier who
     *  shares a branch with them. A person whose branch is covered by more than one
     *  direct-report counts under each of them; that's a real shared-branch org shape, not
     *  something to dedupe away. A direct report with nothing under them (or whose matched
     *  subordinates summed to all-zero) is hidden, same convention loadSubordinateAgentStats
     *  uses.
     *
     *  Combines Phase 2 (dynamic level-based tiers) with role_reports_to-resolved membership —
     *  see subordinatePool()'s doc comment for how each account gets placed in [candidates]. */
    private suspend fun loadTieredRollups(
        candidates: List<SubordinateCandidate>, startKey: String, endKey: String,
        rangeStartTs: Long, rangeEndTs: Long,
    ): List<AgentLoadResult> {
        if (candidates.isEmpty()) return emptyList()

        val nextTierLevel = candidates.minOf { it.level }
        val directReports = candidates.filter { it.level == nextTierLevel }
        val deeper = candidates.filter { it.level > nextTierLevel }

        if (deeper.isEmpty()) {
            // Nobody sits below the direct-reports tier -- an aggregate row would just
            // duplicate each direct report's own stat 1:1, so show their real individual
            // stats instead of an empty rollup. ASSUMPTION: previously this case returned
            // nothing at all (supervisors.isEmpty() || workers.isEmpty() -> emptyList()) --
            // this is a deliberate behavior change, confirm it's wanted.
            return coroutineScope {
                directReports.map { c ->
                    async { loadAgentStat(c.uid, c.name, startKey, endKey, rangeStartTs, rangeEndTs, c.level, c.roleId, c.branchIds) }
                }.awaitAll()
            }
        }

        // Every matched subordinate's stat is loaded exactly once regardless of how many
        // direct reports end up summing it in, then grouped below — not re-fetched per report.
        val deeperResults = coroutineScope {
            deeper.map { c ->
                async { c to loadAgentStat(c.uid, c.name, startKey, endKey, rangeStartTs, rangeEndTs, c.level, c.roleId, c.branchIds) }
            }.awaitAll()
        }

        // Which direct report(s) a "deeper" person rolls up under: branch overlap against
        // every direct report — a person can match more than one (see doc comment above).
        // NOTE: there is no individual/per-person reports_to chain to consult here —
        // subordinatePool() resolves this entire candidate list via role_reports_to POLICY
        // alone (see its doc comment); SubordinateCandidate doesn't carry a reports_to field.
        // An earlier version of this function walked one, back when accounts could each have
        // their own explicit reports_to uid — that model was replaced.
        fun directReportsFor(person: SubordinateCandidate): List<String> =
            directReports.filter { rep -> person.branchIds.any { it in rep.branchIds } }.map { it.uid }
        val deeperByReport = mutableMapOf<String, MutableList<Pair<SubordinateCandidate, AgentLoadResult>>>()
        deeperResults.forEach { (person, result) ->
            directReportsFor(person).forEach { repUid ->
                deeperByReport.getOrPut(repUid) { mutableListOf() }.add(person to result)
            }
        }

        return directReports.mapNotNull { rep ->
            val matched = deeperByReport[rep.uid] ?: return@mapNotNull null
            if (matched.isEmpty()) return@mapNotNull null
            val delivered  = matched.sumOf { it.second.stat.delivered }
            val onHold     = matched.sumOf { it.second.stat.onHold }
            val returned   = matched.sumOf { it.second.stat.returned }
            val pending    = matched.sumOf { it.second.stat.pending }
            val earnings   = matched.sumOf { it.second.stat.earnings }
            val openRuns   = matched.sumOf { it.second.stat.openRuns }
            val closedRuns = matched.sumOf { it.second.stat.closedRuns }
            if (delivered + onHold + returned + pending == 0 && earnings <= 0 && openRuns + closedRuns == 0) {
                return@mapNotNull null
            }
            val rawCounts = mutableMapOf<String, Int>()
            matched.forEach { (_, r) -> r.rawStatusCounts.forEach { (k, v) -> rawCounts[k] = (rawCounts[k] ?: 0) + v } }
            AgentLoadResult(
                stat = AgentStat(
                    agentId    = rep.uid,
                    agentName  = rep.name,
                    runId      = "",
                    runStatus  = "",
                    delivered  = delivered,
                    onHold     = onHold,
                    returned   = returned,
                    pending    = pending,
                    earnings   = earnings,
                    openRuns   = openRuns,
                    closedRuns = closedRuns,
                    level      = rep.level,
                    roleId     = rep.roleId,
                    branchIds  = rep.branchIds,
                ),
                rawStatusCounts = rawCounts,
                readErrorMessage = matched.firstNotNullOfOrNull { (_, r) -> r.readErrorMessage },
            )
        }
    }

    /** [ts] (epoch ms) as yyyyMMdd — must match the write side's date-key format exactly
     *  (WorkerSpaceFragment.todayDateKeyYyyyMmDd / CallCenterFragment's twin). */
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
