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
     *  count tally from the same read, for the dynamic per-status breakdown in load(). */
    private data class AgentLoadResult(val stat: AgentStat, val rawStatusCounts: Map<String, Int>)

    private fun load(range: DateRange) {
        loadJob?.cancel()
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

                // "worker" = exactly the single bounded self-read courier/remarks_by_userId was
                // designed for (DashboardFragment already hides the agent table for this
                // role). Any other role gets the branch-scoped breakdown across workers.
                val results = if (roleId == "worker") {
                    listOf(loadAgentStat(uid, UserNameResolver.resolveName(uid), startKey, endKey, range.startTs, range.endTs))
                } else {
                    val selected = _selectedBranchIds.value ?: emptySet()
                    val available = _availableBranches.value?.map { it.id }?.toSet() ?: emptySet()
                    loadWorkerAgentStats(selected.ifEmpty { available }, roleId, startKey, endKey, range.startTs, range.endTs)
                }
                statusMetaDeferred.await()

                val agentStats = results.map { it.stat }

                val stats = DashboardStats(
                    totalParcels = agentStats.sumOf { it.total },
                    delivered    = agentStats.sumOf { it.delivered },
                    onHold       = agentStats.sumOf { it.onHold },
                    returned     = agentStats.sumOf { it.returned },
                    pending      = agentStats.sumOf { it.pending },
                    openRuns     = agentStats.sumOf { it.openRuns },
                    closedRuns   = agentStats.sumOf { it.closedRuns },
                    earnings     = agentStats.sumOf { it.earnings },
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

                // Always goes through Success, refresh or not — this is what makes a refresh
                // actually show the latest fetch's numbers once it lands, on top of the old
                // data staying visible while it was in flight.
                _state.value = DashboardState.Success(
                    stats     = stats,
                    agents    = agentStats.sortedByDescending { it.delivered },
                    role      = roleId,
                    breakdown = breakdown,
                )
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
            runCatching { db.reference.child("memory/$uid/earnings").get().await() }.getOrNull()
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
            }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        if (systemId != null) {
            val runsSnap = withContext(Dispatchers.IO) {
                runCatching { db.reference.child("courier/runs_by_agentSystemId/$systemId").get().await() }.getOrNull()
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
        )
        return AgentLoadResult(stat, rawCounts)
    }

    /** Handles a commission/amount value that may be stored as Long, Int, or Double —
     *  mirrors SalaryModels.kt's numberValue() (file-private there, so re-declared here). */
    private fun com.google.firebase.database.DataSnapshot.numberValue(): Double? =
        getValue(Double::class.java)
            ?: getValue(Long::class.java)?.toDouble()
            ?: getValue(Int::class.java)?.toDouble()

    /** Workers (role_id == "worker") whose branch_ids intersect [branchFilter], each read via
     *  loadAgentStat in parallel. Empty branchFilter + non-admin viewer -> returns nothing
     *  rather than risk a company-wide read (the exact bug loadBranchView() had). */
    private suspend fun loadWorkerAgentStats(
        branchFilter: Set<String>, viewerRoleId: String, startKey: String, endKey: String,
        rangeStartTs: Long, rangeEndTs: Long,
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
                async { loadAgentStat(candidateUid, name, startKey, endKey, rangeStartTs, rangeEndTs) }
            }.awaitAll()
        }.filter { it.stat.total > 0 || it.stat.earnings > 0 || it.stat.openRuns + it.stat.closedRuns > 0 }
            // hide workers with nothing actioned, earned, or run in this range — a worker
            // whose only activity was a backfilled earning or a run with no parcel action
            // that day would otherwise vanish from those totals too
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
