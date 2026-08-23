package com.cloudx.databridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import coil.load
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class CallCenterFragment : Fragment() {

    // UI
    private lateinit var tvAgentInfo: TextView
    private lateinit var tvValidationCount: TextView
    private lateinit var tvStatTotal: TextView
    private lateinit var tvStatConfirmed: TextView
    private lateinit var tvStatPending: TextView
    private lateinit var tvStatRejected: TextView
    private lateinit var tvModeDropdown: TextView
    private lateinit var tvBranchDropdown: TextView
    private lateinit var layoutFilterTabs: LinearLayout
    private lateinit var rvParcelList: RecyclerView
    private lateinit var pbProgress: ProgressBar
    private lateinit var tvLoadingPercent: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var switchAutoCall: Switch
    private lateinit var btnAutoCallStartPause: android.widget.Button
    private lateinit var btnAutoCallGapMenu: TextView
    private lateinit var btnRecallList: TextView
    private lateinit var cardAutoCallStatus: androidx.cardview.widget.CardView
    private lateinit var tvAutoCallStatusLabel: TextView
    private lateinit var tvAutoCallStatusName: TextView
    private lateinit var tvAutoCallStatusTimer: TextView
    private lateinit var tvAutoCallStatusInfo: TextView
    private lateinit var tvSortByDropdown: TextView
    private var sortMode: String = "attempt" // "attempt" (default) or "aging" — same options as Worker Fragment

    // Auto Call (sequential dialer) state
    private var autoCallGapSeconds = 8
    private var autoCallJob: Job? = null
    private var searchJob: Job? = null  // ✅ Fix #8: Search debounce job
    // Perf fix: debounces reprocessAllCachedRuns() so a burst of near-simultaneous run-node
    // listener callbacks (many agents' initial onDataChange firing within ms of each other on
    // cold load) collapses into ONE reprocess instead of one per listener — see the listener
    // in syncCcRunNodeListeners() and the generation guard inside reprocessAllCachedRuns().
    private var reprocessJob: Job? = null
    private var ccLoadGeneration = 0

    // Waits for the agent to actually return to this screen (call ended/dismissed)
    // before dialing the next number, instead of guessing with a fixed delay.
    // Permission-free: relies on onPause→onResume, not real telephony call state.
    private var resumeSignal: CompletableDeferred<Unit>? = null
    private var hasPausedSincePendingDial = false
    private val AUTO_CALL_RETURN_TIMEOUT_MS = 5 * 60 * 1000L // safety net if the signal never arrives
    // Minimum total ring time (dial -> end) before an unanswered call (CallLog duration == 0)
    // is confident enough to auto-remark. Below this, it's more likely an immediate
    // reject/invalid-number/network-fail than a genuine unanswered ring — left for manual
    // review instead of risking a wrong auto-classification.
    private val AUTO_NO_ANSWER_MIN_RING_SECONDS = 30
    private var autoRedialEnabled = false
    private var autoRedialMaxTimes = 2
    // Distinctive, code-controlled text — nothing else in the app produces this exact string —
    // so recall candidates can be identified by a plain text match on the latest remark
    // instead of threading a new field through the whole remarks-parsing pipeline.
    private val AUTO_NO_ANSWER_REMARK_TEXT = "The customer doesn't receive the call"
    private var recallModeActive = false

    // ── Auto Call filter preference ──────────────────────────────────
    // "status" = only cards whose status is in autoCallStatuses go into the queue.
    // "aging"  = ignore status, only the age condition below applies.
    private var autoCallMode = "status"
    private var autoCallStatuses = mutableSetOf("pending")
    private var autoCallAgeEnabled = false
    private var autoCallMinAgeDays = 3
    private var autoCallQueue: List<String> = emptyList()      // phone numbers, in dial order
    private var autoCallQueueIds: List<String> = emptyList()   // matching parcel ids, same order
    private var autoCallQueueNames: List<String> = emptyList() // matching customer names, same order
    private var autoCallQueueItems: List<CallCenterParcelItem> = emptyList() // full item, for rich popup
    private var autoCallIndex = 0

    // Cursor for the event-triggered FCM fallback fetch. Realtime is the normal live path;
    // this is never advanced by a timer.
    private var ccRemarkFallbackCursorMs = 0L
    private var ccRealtimeJob: kotlinx.coroutines.Job? = null
    private var ccRealtimeChannelKey: String? = null

    // Parcel ID to expand after data loads (set when navigating from a notification tap).
    private var pendingExpandParcelId: String? = null

    // FCM is an event-triggered fallback if Realtime has not delivered yet.
    // Keep this listener scoped to the Fragment view so a destroyed screen is never
    // refreshed from a process-wide notification callback.
    private val remarkNotificationListener: (AppNotificationManager.NotifItem) -> Unit = { notification ->
        if (notification.scope == "cc" && notification.parcelId.isNotBlank()) {
            refreshCcParcelFromPush(notification.parcelId)
        }
    }

    // Per-parcel call-progress glow: id -> color. Persists across pause/stop (done stays green).
    private val callCardStates = mutableMapOf<String, Int>()
    private val colorCallDone = android.graphics.Color.parseColor("#16A34A")
    private val colorCallQueued = android.graphics.Color.parseColor("#F59E0B")
    private val colorCallCalling = android.graphics.Color.parseColor("#7C3AED")

    // Firebase UID of the current CC agent — used as userId in remark writes for users/{uid} lookup.
    private var userId = ""

    // uid -> display name, resolved on demand from users/{uid}/profile/name and cached so
    // repeated remark authors (workers or other CC agents) across a session don't refetch.
    // Cleared on pull-to-refresh alongside systemIdToName.
    // ✅ Using shared UserNameResolver (Fix #4) — eliminates duplicate code & caching

    // systemId -> display name, fetched once per session via users_by_systemId reverse-index
    // + parallel per-uid name lookup (see ensureAgentNameMap()), reused for every subsequent
    // run listener trigger. Cleared on pull-to-refresh.
    private var systemIdToName: Map<String, String> = emptyMap()
    // systemId -> HR-assigned employee_id (users/{uid}/profile/company_info/employee_id)
    private var systemIdToEmployeeId: Map<String, String> = emptyMap()
    // systemId -> users/{uid}/profile/photo_url, resolved alongside name/employee_id in
    // ensureAgentNameMap()'s same parallel per-uid fetch. Cleared on pull-to-refresh.
    private var systemIdToPhotoUrl: Map<String, String> = emptyMap()
    // systemId -> users/{uid}/profile/phone, resolved alongside name/employee_id/photo_url
    // in ensureAgentNameMap()'s same parallel per-uid fetch. Cleared on pull-to-refresh.
    private var systemIdToPhone: Map<String, String> = emptyMap()

    private lateinit var adapter: CallCenterAdapter

    private var allParcels = listOf<CallCenterParcelItem>()
    private lateinit var etSearch: EditText
    private lateinit var tvSearchClear: TextView
    private lateinit var tvSearchCount: TextView
    private var searchQuery: String = ""

    private lateinit var tvAgentDropdown: TextView
    /** systemId (== employee_id, the same token embedded in run_ids) -> display name.
     *  Filtering keys off systemId, never off name — two agents can share a display name,
     *  but systemId/employee_id is always unique (per user's explicit correction). */
    data class AgentOption(val systemId: String, val name: String, val employeeId: String = "") {
        /** "Mehedi (EMP001)" — shows HR-assigned employee_id, falls back to systemId. */
        val display: String get() = "$name (${employeeId.ifBlank { systemId }})"
    }
    private val selectedAgentFilters: MutableSet<String> = mutableSetOf() // systemIds; empty = all agents
    private var ccAgentOptions: List<AgentOption> = emptyList() // known agents, systemId+name bound together

    private lateinit var tvCollapseArrow: TextView
    private lateinit var layoutCollapsibleSection: LinearLayout
    private var isHeaderExpanded = false // starts collapsed to save screen space
    private var statusFilter = "all"
    private val selectedBranchIds = mutableSetOf<String>()
    private val branchIdToName = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var branches = listOf<String>()
    // "priority" = only verify_req parcels (agents who sent a request, called first).
    // "all" = every branch-scoped parcel regardless of request, for random spot-verification.
    // Both may be selected simultaneously — see showModeDropdown() / applyFilters().
    private val selectedAccessModes: MutableSet<String> = mutableSetOf("priority")
    // CC agent's own assigned branches (RbacManager, loaded at login) — scopes ALL data fetching.
    private var myBranchIds: List<String> = emptyList()
    // (runType/runId) keys that already have a dedicated live listener attached — prevents
    // re-attaching duplicates every time a branch index snapshot re-fires.
    private val ccAttachedRunKeys = mutableSetOf<String>()
    // Cache of each attached run node's latest snapshot; the full parcel list is rebuilt from
    // this cache whenever any one run node changes (new consignment, status update, etc).
    private val ccRunNodeSnapshots = mutableMapOf<String, com.google.firebase.database.DataSnapshot>()
    // (runType/runId) -> branch IDs whose branch index currently points at that run.
    // A multi-branch agent's single run can appear under more than one branch index; keeping
    // the full set lets the branch filter match all valid branches instead of only the first
    // resolvedBranchIds value stored on the run node.
    private val ccRunKeyBranchIds = mutableMapOf<String, MutableSet<String>>()
    private val ccEngagedAtListeners = mutableMapOf<String, Pair<com.google.firebase.database.DatabaseReference, com.google.firebase.database.ValueEventListener>>()

    // Run ID shape: run_{yyyyMMdd}_{employeeId} — yyyyMMdd is always exactly 8 zero-padded
    // digits (4-digit year first, so plain string ordering sorts chronologically).
    private val RUN_ID_PATTERN = Regex("^run_(\\d{8})_(.+)$")

    override fun onPause() {
        super.onPause()
        // Only meaningful while an auto-call is pending a return signal (see startAutoCall).
        if (resumeSignal != null) hasPausedSincePendingDial = true
    }

    override fun onResume() {
        super.onResume()
        // Require an intervening onPause so this doesn't fire from the same resumed
        // state the dial happened in (e.g. an OEM call overlay that never backgrounds us).
        if (resumeSignal != null && hasPausedSincePendingDial) {
            resumeSignal?.complete(Unit)
            resumeSignal = null
            hasPausedSincePendingDial = false
        }
    }

    override fun onDestroyView() {
        // ✅ Fix #8: Cancel pending search debounce job
        searchJob?.cancel()
        searchJob = null
        reprocessJob?.cancel()
        reprocessJob = null
        ccRealtimeJob?.cancel()
        ccRealtimeJob = null
        ccRealtimeChannelKey = null
        AppNotificationManager.removeRemarkListener(remarkNotificationListener)
        super.onDestroyView()
        stopAutoCall()
        detachRunsListener()
        detachCcEngagedAtListeners()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_call_center, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Notification tap-to-navigate: expand this parcel after data loads
        arguments?.getString("expand_parcel_id")?.takeIf { it.isNotBlank() }?.let {
            pendingExpandParcelId = it
        }

        initViews(view)
        AppNotificationManager.addRemarkListener(remarkNotificationListener)
        loadFilterPreferences()
        updateModeDropdownLabel()
        updateCcSortByLabel()
        setupFilterTabs()
        setupAdapter()
        loadCcRemarkOptions()
        loadData()
    }

    // ── Filter preference persistence ──────────────────────────────────────
    // selectedAccessModes/selectedBranchIds/selectedAgentFilters previously reset to
    // hardcoded defaults (priority mode, no branch/agent filter) every time this fragment
    // was recreated (navigating away and back, app restart). Persist the last-applied
    // choice from each dropdown so it survives across sessions, same SharedPreferences
    // file already used for auto-call settings.
    private fun filterPrefs() =
        requireContext().getSharedPreferences("databridge_toggles", android.content.Context.MODE_PRIVATE)

    private fun loadFilterPreferences() {
        val prefs = filterPrefs()
        // No saved value yet (first run) -> keep the "priority" default already set at
        // declaration. Once the user has applied anything, always restore exactly that.
        prefs.getStringSet("cc_filter_access_modes", null)?.let {
            selectedAccessModes.clear()
            selectedAccessModes.addAll(it)
        }
        selectedBranchIds.clear()
        selectedBranchIds.addAll(prefs.getStringSet("cc_filter_branch_ids", emptySet()) ?: emptySet())
        selectedAgentFilters.clear()
        selectedAgentFilters.addAll(prefs.getStringSet("cc_filter_agent_names", emptySet()) ?: emptySet())
        sortMode = prefs.getString("cc_sort_mode", "attempt") ?: "attempt"
    }

    private fun saveFilterPreferences() {
        filterPrefs().edit()
            .putStringSet("cc_filter_access_modes", selectedAccessModes.toSet())
            .putStringSet("cc_filter_branch_ids", selectedBranchIds.toSet())
            .putStringSet("cc_filter_agent_names", selectedAgentFilters.toSet())
            .putString("cc_sort_mode", sortMode)
            .apply()
    }

    private fun initViews(view: View) {
        tvAgentInfo = view.findViewById(R.id.twCcaAgentInfo)
        tvValidationCount = view.findViewById(R.id.twCcaValidationCount)
        tvStatTotal = view.findViewById(R.id.twCcaStatTotalValue)
        tvStatConfirmed = view.findViewById(R.id.twCcaStatConfirmedValue)
        tvStatPending = view.findViewById(R.id.twCcaStatPendingValue)
        tvStatRejected = view.findViewById(R.id.twCcaStatRejectedValue)
        tvModeDropdown = view.findViewById(R.id.tvCcaModeDropdown)
        tvBranchDropdown = view.findViewById(R.id.tvCcaBranchDropdown)
        tvAgentDropdown = view.findViewById(R.id.tvCcaAgentDropdown)
        tvSortByDropdown = view.findViewById(R.id.tvCcaSortByDropdown)
        tvModeDropdown.setOnClickListener { showModeDropdown() }
        tvBranchDropdown.setOnClickListener { showBranchDropdown() }
        tvAgentDropdown.setOnClickListener { showAgentDropdown() }
        tvSortByDropdown.setOnClickListener { showCcSortByDropdown() }
        layoutFilterTabs = view.findViewById(R.id.layoutCcaFilterTabs)
        rvParcelList = view.findViewById(R.id.rvCcaParcelList)
        pbProgress = view.findViewById(R.id.twCcaProgressBar)
        tvLoadingPercent = view.findViewById(R.id.twCcaLoadingPercent)
        tvEmpty = view.findViewById(R.id.twCcaEmptyState)
        spinnerCcRunType = view.findViewById(R.id.spinnerCcRunType)

        etSearch = view.findViewById(R.id.twCcaSearchInput)
        tvSearchClear = view.findViewById(R.id.twCcaSearchClear)
        tvSearchCount = view.findViewById(R.id.twCcaSearchCount)
        tvCollapseArrow = view.findViewById(R.id.tvCcaCollapseArrow)
        layoutCollapsibleSection = view.findViewById(R.id.layoutCcaCollapsibleSection)
        setupSearch()
        setupCollapseToggle()
        swipeRefresh = view.findViewById(R.id.swipeRefreshCca)
        // Pull-to-refresh gesture disabled — it conflicts with the card swipe-to-call gesture
        // (both are touch-drag systems on the same RecyclerView), causing misfired/dropped
        // swipes. The listener below is left in place but effectively dormant; re-enable
        // (swipeRefresh.isEnabled = true) if pull-to-refresh is ever wanted back once the
        // gesture conflict is resolved some other way.
        swipeRefresh.isEnabled = false
        swipeRefresh.setColorSchemeResources(R.color.theme_brand_red)
        swipeRefresh.setOnRefreshListener {
            systemIdToName = emptyMap()
            systemIdToEmployeeId = emptyMap()
            systemIdToPhotoUrl = emptyMap()
            UserNameResolver.clearCache()
            detachRunsListener()
            loadCcRemarkOptions()
            loadData()
            swipeRefresh.isRefreshing = false
        }

        switchAutoCall = view.findViewById(R.id.switchCcAutoCall)
        btnAutoCallStartPause = view.findViewById(R.id.btnCcAutoCallStartPause)
        btnAutoCallGapMenu = view.findViewById(R.id.btnCcAutoCallGapMenu)
        btnRecallList = view.findViewById(R.id.btnCcRecallList)
        cardAutoCallStatus = view.findViewById(R.id.cardAutoCallStatus)
        tvAutoCallStatusLabel = view.findViewById(R.id.tvAutoCallStatusLabel)
        tvAutoCallStatusName = view.findViewById(R.id.tvAutoCallStatusName)
        tvAutoCallStatusTimer = view.findViewById(R.id.tvAutoCallStatusTimer)
        tvAutoCallStatusInfo = view.findViewById(R.id.tvAutoCallStatusInfo)
        setupAutoCallControls()

        val user = FirebaseAuth.getInstance().currentUser
        val displayName = user?.displayName ?: "Agent"
        tvAgentInfo.text = "$displayName · Supervisor"

        user?.uid?.let { uid ->
            userId = uid
        }
    }

    private fun setupAutoCallControls() {
        val prefs = requireContext().getSharedPreferences("databridge_toggles", android.content.Context.MODE_PRIVATE)
        autoCallGapSeconds = prefs.getInt("cc_auto_call_gap_seconds", 8)
        autoCallMode = prefs.getString("cc_auto_call_mode", "status") ?: "status"
        autoCallStatuses = (prefs.getStringSet("cc_auto_call_statuses", setOf("pending")) ?: setOf("pending")).toMutableSet()
        autoCallAgeEnabled = prefs.getBoolean("cc_auto_call_age_enabled", false)
        autoCallMinAgeDays = prefs.getInt("cc_auto_call_min_age_days", 3)
        val togglePrefs = requireContext().getSharedPreferences("databridge_toggles", android.content.Context.MODE_PRIVATE)
        autoRedialEnabled = togglePrefs.getBoolean("auto_redial", false)
        autoRedialMaxTimes = togglePrefs.getInt("auto_redial_count", 2).coerceIn(1, 5)

        switchAutoCall.setOnCheckedChangeListener(null)
        switchAutoCall.isChecked = false
        switchAutoCall.setOnCheckedChangeListener { _, isChecked ->
            btnAutoCallStartPause.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) stopAutoCall()
        }

        btnAutoCallGapMenu.setOnClickListener { showAutoCallGapMenu() }

        btnRecallList.setOnClickListener {
            recallModeActive = true
            autoCallQueue = emptyList() // force a fresh queue build under recall filtering
            autoCallIndex = 0
            switchAutoCall.isChecked = true
            startAutoCall()
        }

        btnAutoCallStartPause.setOnClickListener {
            if (autoCallJob?.isActive == true) {
                pauseAutoCall()
            } else {
                startAutoCall()
            }
        }
    }

    private fun showAutoCallGapMenu() {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val scroll = android.widget.ScrollView(ctx)
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24.dp(), 16.dp(), 24.dp(), 8.dp())
        }
        scroll.addView(root)

        fun sectionTitle(text: String) = TextView(ctx).apply {
            this.text = text; textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#6B7280"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10.dp(); bottomMargin = 4.dp() }
        }

        // ── Gap ──────────────────────────────────────────────────────
        root.addView(sectionTitle("Gap (প্রতিটা call এর মাঝে)"))
        val gapSpinner = Spinner(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 44.dp())
        }
        val gapOptions = listOf(5, 8, 10, 15, 20, 30)
        gapSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
            gapOptions.map { "$it sec" })
        gapSpinner.setSelection(gapOptions.indexOf(autoCallGapSeconds).coerceAtLeast(0))
        root.addView(gapSpinner)

        // ── Call Preference ──────────────────────────────────────────
        root.addView(sectionTitle("Call Preference"))
        val modeSpinner = Spinner(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 44.dp())
        }
        val modeOptions = listOf("status" to "Status Wise", "aging" to "Aging Wise")
        modeSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
            modeOptions.map { it.second })
        modeSpinner.setSelection(modeOptions.indexOfFirst { it.first == autoCallMode }.coerceAtLeast(0))
        root.addView(modeSpinner)

        // ── Status checklist (shown only in Status Wise mode) ─────────
        val statusContainer = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        root.addView(statusContainer)

        val statusCheckboxes = mutableListOf<Pair<String, android.widget.CheckBox>>()
        fun buildStatusChecklist() {
            statusContainer.removeAllViews()
            statusCheckboxes.clear()
            statusContainer.addView(sectionTitle("কোন কোন Status এ Call যাবে"))
            val allStatuses = StatusMetaCache.entries.entries.sortedByDescending { it.value.sortOrder }
            if (allStatuses.isEmpty()) {
                statusContainer.addView(TextView(ctx).apply {
                    text = "কোনো status পাওয়া যায়নি"; textSize = 12f
                    setTextColor(android.graphics.Color.parseColor("#9CA3AF"))
                })
            }
            allStatuses.forEach { (key, entry) ->
                val cb = android.widget.CheckBox(ctx).apply {
                    text = entry.bn.ifBlank { key }
                    isChecked = key in autoCallStatuses
                    textSize = 13f
                }
                statusCheckboxes.add(key to cb)
                statusContainer.addView(cb)
            }
        }

        // ── Age condition ──────────────────────────────────────────────
        root.addView(sectionTitle("Age Condition (ঐচ্ছিক)"))
        val ageRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val cbAgeEnabled = android.widget.CheckBox(ctx).apply {
            text = "More than"
            isChecked = autoCallAgeEnabled
            textSize = 13f
        }
        val etAgeDays = EditText(ctx).apply {
            setText(autoCallMinAgeDays.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = android.widget.LinearLayout.LayoutParams(60.dp(), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = android.view.Gravity.CENTER
            isEnabled = autoCallAgeEnabled
        }
        val tvAgeDaysLabel = TextView(ctx).apply {
            text = " দিনের বেশি বয়স"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#111827"))
        }
        cbAgeEnabled.setOnCheckedChangeListener { _, checked -> etAgeDays.isEnabled = checked }
        ageRow.addView(cbAgeEnabled)
        ageRow.addView(etAgeDays)
        ageRow.addView(tvAgeDaysLabel)
        root.addView(ageRow)

        // Toggle status checklist visibility based on mode
        fun refreshModeVisibility() {
            val isStatusMode = modeOptions[modeSpinner.selectedItemPosition].first == "status"
            statusContainer.visibility = if (isStatusMode) View.VISIBLE else View.GONE
        }
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshModeVisibility()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        buildStatusChecklist()
        refreshModeVisibility()

        android.app.AlertDialog.Builder(ctx)
            .setTitle("Auto Call Settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                autoCallGapSeconds = gapOptions[gapSpinner.selectedItemPosition]
                autoCallMode = modeOptions[modeSpinner.selectedItemPosition].first
                autoCallStatuses = statusCheckboxes.filter { it.second.isChecked }.map { it.first }.toMutableSet()
                autoCallAgeEnabled = cbAgeEnabled.isChecked
                autoCallMinAgeDays = etAgeDays.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 3

                requireContext().getSharedPreferences("databridge_toggles", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putInt("cc_auto_call_gap_seconds", autoCallGapSeconds)
                    .putString("cc_auto_call_mode", autoCallMode)
                    .putStringSet("cc_auto_call_statuses", autoCallStatuses)
                    .putBoolean("cc_auto_call_age_enabled", autoCallAgeEnabled)
                    .putInt("cc_auto_call_min_age_days", autoCallMinAgeDays)
                    .apply()

                Toast.makeText(requireContext(), "Auto Call settings সেভ হয়েছে", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Starts (or resumes) sequentially dialing every currently-pending parcel's number. */
    private fun pushCallStates() {
        if (::adapter.isInitialized) adapter.callStates = callCardStates.toMap()
    }

    // ── Auto Call status overlay (non-modal — never blocks taps on the parcel list below it) ──

    /** Phase: gap period before the next dial. Shows who's about to be called + a live
     *  countdown in seconds. Caller ticks this down every second from autoCallGapSeconds. */
    private fun showAutoCallCountdown(item: CallCenterParcelItem, secondsRemaining: Int) {
        val dialCount = DialCountStore.get(requireContext(), item.id)
        val infoLine = buildString {
            if (item.cod > 0) append("💵 ৳${item.cod}")
            if (dialCount > 0) { if (isNotEmpty()) append("  •  "); append("📞 ${dialCount}x attempt") }
            if (item.address.isNotBlank()) { if (isNotEmpty()) append("  •  "); append("📍 ${item.address.take(35).trimEnd()}") }
        }
        tvAutoCallStatusLabel.text = "পরবর্তী কল আসছে"
        tvAutoCallStatusName.text = item.customer
        tvAutoCallStatusInfo.text = infoLine
        tvAutoCallStatusInfo.visibility = if (infoLine.isNotEmpty()) View.VISIBLE else View.GONE
        tvAutoCallStatusTimer.text = secondsRemaining.toString()
        tvAutoCallStatusTimer.visibility = View.VISIBLE
        cardAutoCallStatus.visibility = View.VISIBLE
    }

    /** Phase: current call is active (dial just fired). Shows who's next in queue after this
     *  one — no timer, since we don't know when the current call will end. */
    private fun showAutoCallNextPreview(nextItem: CallCenterParcelItem?) {
        if (nextItem == null) {
            hideAutoCallStatus()
            return
        }
        val dialCount = DialCountStore.get(requireContext(), nextItem.id)
        val infoLine = buildString {
            if (nextItem.cod > 0) append("💵 ৳${nextItem.cod}")
            if (dialCount > 0) { if (isNotEmpty()) append("  •  "); append("📞 ${dialCount}x attempt") }
            if (nextItem.address.isNotBlank()) { if (isNotEmpty()) append("  •  "); append("📍 ${nextItem.address.take(35).trimEnd()}") }
        }
        tvAutoCallStatusLabel.text = "এরপর কল যাবে"
        tvAutoCallStatusName.text = nextItem.customer
        tvAutoCallStatusInfo.text = infoLine
        tvAutoCallStatusInfo.visibility = if (infoLine.isNotEmpty()) View.VISIBLE else View.GONE
        tvAutoCallStatusTimer.visibility = View.GONE
        cardAutoCallStatus.visibility = View.VISIBLE
    }

    /** Phase: call ended (or auto-call stopped entirely). */
    private fun hideAutoCallStatus() {
        cardAutoCallStatus.visibility = View.GONE
    }

    private fun startAutoCall() {
        val ctx = requireContext()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(ctx, "Auto Call needs Call permission first.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            switchAutoCall.isChecked = false
            return
        }
        if (!CallStateWatcher.hasPermission(ctx)) {
            // Not blocking — Auto Call still works via the screen-focus fallback — but an
            // install from before READ_PHONE_STATE existed (or a user who declined it during
            // onboarding) won't get the reliable "wait for the real call to end" behavior
            // until they grant it, so nudge once per Auto Call start.
            Toast.makeText(
                ctx,
                "টিপ: Settings-এ Phone permission দিলে Auto Call আরও নির্ভরযোগ্যভাবে কাজ করবে।",
                Toast.LENGTH_LONG
            ).show()
        }

        // Fresh queue only when not resuming a paused run (queue empty or we've reached the end).
        if (autoCallQueue.isEmpty() || autoCallIndex >= autoCallQueue.size) {
            val eligible = allParcels.filter { p ->
                if (p.phone.isBlank()) return@filter false
                if (recallModeActive) {
                    return@filter p.remarks == AUTO_NO_ANSWER_REMARK_TEXT
                }
                val matchesMode = when (autoCallMode) {
                    "status" -> p.effectiveStatus in autoCallStatuses
                    "aging"  -> true // aging mode ignores status entirely
                    else     -> p.status == "pending"
                }
                val matchesAge = if (autoCallAgeEnabled) {
                    val days = if (p.createdAt > 0) (System.currentTimeMillis() - p.createdAt) / (24 * 60 * 60 * 1000) else 0L
                    days > autoCallMinAgeDays
                } else true
                matchesMode && matchesAge
            }
            if (eligible.isEmpty()) {
                Toast.makeText(ctx, "এই filter অনুযায়ী কোনো parcel পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
                switchAutoCall.isChecked = false
                return
            }
            val seenPhones = mutableSetOf<String>()
            val dedupedEligible = eligible.filter { seenPhones.add(it.phone.trim()) }
            autoCallQueue = dedupedEligible.map { it.phone }
            autoCallQueueIds = dedupedEligible.map { it.id }
            autoCallQueueNames = dedupedEligible.map { it.customer }
            autoCallQueueItems = dedupedEligible
            autoCallIndex = 0
            // Mark the whole fresh queue as "waiting its turn".
            autoCallQueueIds.forEach { id -> callCardStates[id] = colorCallQueued }
            pushCallStates()
        } else {
            // Resuming a paused run — re-mark anything still pending its turn as queued again.
            for (i in autoCallIndex until autoCallQueueIds.size) {
                callCardStates[autoCallQueueIds[i]] = colorCallQueued
            }
            pushCallStates()
        }

        if (autoCallQueue.isEmpty()) {
            Toast.makeText(ctx, "No pending parcels to call.", Toast.LENGTH_SHORT).show()
            return
        }

        btnAutoCallStartPause.text = "⏸ Pause"
        autoCallJob = viewLifecycleOwner.lifecycleScope.launch {
            while (autoCallIndex < autoCallQueue.size) {
                val phone = autoCallQueue[autoCallIndex]
                val id = autoCallQueueIds[autoCallIndex]

                // Mark previous item done now that we're moving past it.
                if (autoCallIndex > 0) {
                    callCardStates[autoCallQueueIds[autoCallIndex - 1]] = colorCallDone
                }
                callCardStates[id] = colorCallCalling
                pushCallStates()

                val dialStartMs = System.currentTimeMillis()
                AutoDialHelper.dial(this@CallCenterFragment, phone, forceDirect = true)
                // Auto-expand this parcel's remarks now, not after the call ends — the
                // overlay below is non-modal, so the agent can start writing notes while the
                // call is still going instead of waiting for it to finish.
                pendingExpandParcelId = id
                autoCallIndex++

                // Preview who's next while this call is active. No timer here — we don't
                // know when the current call will end.
                showAutoCallNextPreview(autoCallQueueItems.getOrNull(autoCallIndex))

                // Wait for the call to actually END, not just for the agent's focus to
                // return to this screen — the two are NOT the same thing. Android often lets
                // the app regain foreground while a call is still active in the background
                // (agent switches back mid-call to check something), and the old
                // onPause()/onResume()-only signal below would misread that as "call over"
                // and dial the next number while the current one was still going.
                //
                // Reliable path: real telephony call-state (OFFHOOK -> IDLE) via
                // CallStateWatcher, which needs READ_PHONE_STATE.
                val realCallEndDetected = CallStateWatcher.awaitCallEnd(ctx, AUTO_CALL_RETURN_TIMEOUT_MS)
                hideAutoCallStatus() // call just ended (or we timed out waiting) — clear the preview

                if (!realCallEndDetected) {
                    // No READ_PHONE_STATE (declined during onboarding, or an existing
                    // install that hasn't been through the updated flow yet), or the
                    // watcher genuinely timed out — fall back to the old screen-focus
                    // heuristic so Auto Call still works, just less reliably.
                    hasPausedSincePendingDial = false
                    val deferred = CompletableDeferred<Unit>()
                    resumeSignal = deferred
                    withTimeoutOrNull(AUTO_CALL_RETURN_TIMEOUT_MS) { deferred.await() }
                    resumeSignal = null
                }

                // No-answer detection: only once we're actually confident the call has ended
                // (real detection succeeded, OR the screen-focus fallback above also
                // resolved) — NOT right after awaitCallEnd() alone, since a timeout there
                // doesn't mean the call ended, just that we gave up waiting on the reliable
                // signal. Checking too early risks reading the call log mid-call. Brief
                // buffer after that for the OS to actually write the log entry.
                delay(1000L)
                val talkDurationSec = CallLogHelper.getLastCallDurationSeconds(ctx, phone, dialStartMs)
                val totalDurationSec = ((System.currentTimeMillis() - dialStartMs) / 1000L).toInt()

                // Same verified-counting principle as verifyAndIncrementDialCount() for the
                // manual tap/swipe paths: only count it once the call log confirms the dial
                // actually happened (talkDurationSec != null), reusing the query above instead
                // of a second one. Falls back to unconditional counting if READ_CALL_LOG isn't
                // granted, same graceful degradation as the manual paths.
                if (talkDurationSec != null || !CallLogHelper.hasPermission(ctx)) {
                    DialCountStore.increment(ctx, id)
                }

                val noAnswer = talkDurationSec == 0 && totalDurationSec >= AUTO_NO_ANSWER_MIN_RING_SECONDS

                if (noAnswer && autoRedialEnabled) {
                    var redialAttempts = 0
                    while (redialAttempts < autoRedialMaxTimes) {
                        redialAttempts++
                        val redialItem = autoCallQueueItems.find { it.id == id }
                        if (redialItem != null) {
                            for (remaining in autoCallGapSeconds downTo 1) {
                                showAutoCallCountdown(redialItem, remaining)
                                delay(1000L)
                            }
                        }
                        hideAutoCallStatus()
                        val redialStartMs = System.currentTimeMillis()
                        AutoDialHelper.dial(this@CallCenterFragment, phone, forceDirect = true)
                        showAutoCallNextPreview(autoCallQueueItems.getOrNull(autoCallIndex))
                        val redialRealEnd = CallStateWatcher.awaitCallEnd(ctx, AUTO_CALL_RETURN_TIMEOUT_MS)
                        hideAutoCallStatus()
                        if (!redialRealEnd) {
                            hasPausedSincePendingDial = false
                            val d2 = CompletableDeferred<Unit>(); resumeSignal = d2
                            withTimeoutOrNull(AUTO_CALL_RETURN_TIMEOUT_MS) { d2.await() }; resumeSignal = null
                        }
                        delay(1000L)
                        val redialTalk = CallLogHelper.getLastCallDurationSeconds(ctx, phone, redialStartMs)
                        val redialTotal = ((System.currentTimeMillis() - redialStartMs) / 1000L).toInt()
                        if (redialTalk != null && redialTalk > 0) break // answered — stop
                        if (redialTotal < AUTO_NO_ANSWER_MIN_RING_SECONDS) break // auto-cut — stop
                    }
                    allParcels.find { it.id == id }?.let { item -> saveAutoNoAnswerRemark(item) }
                } else if (noAnswer) {
                    allParcels.find { it.id == id }?.let { item -> saveAutoNoAnswerRemark(item) }
                }

                // Short breather before the next dial — shown as a live countdown instead of
                // a silent delay, so it's clear who's coming up and exactly when.
                val upcomingItem = autoCallQueueItems.getOrNull(autoCallIndex)
                if (upcomingItem != null) {
                    for (remaining in autoCallGapSeconds downTo 1) {
                        showAutoCallCountdown(upcomingItem, remaining)
                        delay(1000L)
                    }
                    hideAutoCallStatus() // about to dial — next-preview (above) takes over from here
                }
            }
            // Finished the whole queue — mark the last item done too.
            if (isAdded) {
                autoCallQueueIds.lastOrNull()?.let { lastId -> callCardStates[lastId] = colorCallDone }
                pushCallStates()
                btnAutoCallStartPause.text = "▶ Start"
                autoCallQueue = emptyList()
                autoCallQueueIds = emptyList()
                autoCallQueueNames = emptyList()
                autoCallQueueItems = emptyList()
                autoCallIndex = 0
                hideAutoCallStatus()
                Toast.makeText(requireContext(), "Auto Call finished", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Marks the in-progress item done (its call was already placed) and un-queues the rest. */
    private fun settleGlowStatesOnHalt() {
        autoCallQueueIds.getOrNull(autoCallIndex)?.let { id -> callCardStates[id] = colorCallDone }
        for (i in (autoCallIndex + 1) until autoCallQueueIds.size) {
            callCardStates.remove(autoCallQueueIds[i])
        }
        pushCallStates()
    }

    private fun pauseAutoCall() {
        autoCallJob?.cancel()
        autoCallJob = null
        resumeSignal = null
        hasPausedSincePendingDial = false
        settleGlowStatesOnHalt()
        btnAutoCallStartPause.text = "▶ Start"
    }

    private fun stopAutoCall() {
        autoCallJob?.cancel()
        autoCallJob = null
        resumeSignal = null
        hasPausedSincePendingDial = false
        settleGlowStatesOnHalt()
        autoCallQueue = emptyList()
        autoCallQueueIds = emptyList()
        autoCallQueueNames = emptyList()
        autoCallQueueItems = emptyList()
        autoCallIndex = 0
        recallModeActive = false
        if (::cardAutoCallStatus.isInitialized) hideAutoCallStatus()
        btnAutoCallStartPause.text = "▶ Start"
    }

    /** Last-10-digit normalization so phone numbers with/without a country-code prefix
     *  still match — same rule showRemarksDialog() uses for its sibling-parcel lookup. */
    private fun String.normalizedPhone(): String = filter { it.isDigit() }.takeLast(10)

    /** Every parcel (including [item] itself) sharing item's phone number. Used to fan the
     *  engaged-glow out to the whole group, so a colleague sees ALL of this customer's
     *  parcels as "someone's on it" — not just the one card that was actually tapped. */
    private fun samePhoneGroup(item: CallCenterParcelItem): List<CallCenterParcelItem> {
        val normalized = item.phone.normalizedPhone()
        return allParcels.filter { it.phone.normalizedPhone() == normalized }
    }

    /** Only counts a dial attempt once the system call log actually confirms it happened —
     *  not just that we asked Android to place the call, which can silently fail (permission
     *  denied mid-flow, no dialer app, etc.) without AutoDialHelper knowing. Falls back to
     *  counting immediately if READ_CALL_LOG isn't granted, so the badge still works (just
     *  less precisely — back to "we asked to dial" instead of "the call log confirms it") for
     *  anyone who declined that permission during onboarding. */
    private fun verifyAndIncrementDialCount(consignmentId: String, phone: String) {
        val ctx = requireContext()
        if (!CallLogHelper.hasPermission(ctx)) {
            DialCountStore.increment(ctx, consignmentId)
            adapter.refreshItem(consignmentId)
            return
        }
        val dialAttemptMs = System.currentTimeMillis()
        viewLifecycleOwner.lifecycleScope.launch {
            delay(1500L) // give the OS a moment to write the call log entry
            val confirmed = withContext(Dispatchers.IO) {
                CallLogHelper.getLastCallDurationSeconds(ctx, phone, dialAttemptMs) != null
            }
            if (confirmed && isAdded) {
                DialCountStore.increment(ctx, consignmentId)
                adapter.refreshItem(consignmentId) // targeted — full applyFilters() here can
                // land mid-swipe on another card and disrupt the gesture (see refreshItem() doc)
            }
        }
    }

    private fun setupAdapter() {
        adapter = CallCenterAdapter(
            onCall = { item ->
                AutoDialHelper.dial(this@CallCenterFragment, item.phone)
                verifyAndIncrementDialCount(item.id, item.phone)
                callCardStates[item.id] = colorCallDone
                pushCallStates()
            },
            onSetRemarks = { item -> showRemarksDialog(item) },
            onWhatsappToAgent = { item ->
                // Today's remarks need a fresh fetch (not the list's cached data, which can be
                // stale by the time this button is actually tapped).
                viewLifecycleOwner.lifecycleScope.launch {
                    val todaysRemarksText = withContext(Dispatchers.IO) {
                        val deferred = kotlinx.coroutines.CompletableDeferred<List<org.json.JSONObject>>()
                        SupabaseRemarkValidationWriter.fetchHistory(item.id, "CallCenterFragment") { fetched ->
                            deferred.complete(fetched)
                        }
                        val rows = deferred.await()
                        val todayStart = bangladeshTodayStartMillis()
                        val nameMap = systemIdToName
                        val timeFmt = java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.ENGLISH)
                        rows.mapNotNull { r ->
                            val createdAt = SupabaseRemarkValidationWriter.parseCreatedAtMillis(r.optString("created_at"))
                            if (createdAt < todayStart) return@mapNotNull null
                            val remarksText = resolveRemarkBn(r.optString("remarks").trim())
                            val noteText = r.optString("note").trim()
                            if (remarksText.isBlank() && noteText.isBlank()) return@mapNotNull null
                            val authorSystemId = r.optString("author_system_id").trim()
                            val authorName = r.optJSONObject("author")?.optString("name")?.trim().orEmpty()
                                .ifBlank { nameMap[authorSystemId].orEmpty() }
                                .ifBlank { authorSystemId }
                            createdAt to buildString {
                                append("🕑 ").append(timeFmt.format(java.util.Date(createdAt)))
                                append(" — ").append(authorName)
                                if (remarksText.isNotBlank()) append("\nRemarks: ").append(remarksText)
                                if (noteText.isNotBlank()) append("\nNote: ").append(noteText)
                            }
                        }.sortedBy { it.first }.joinToString("\n\n") { it.second }
                    }
                    if (!isAdded) return@launch

                    val remarksSection = "\n\n━━━━━━━━━━━━━━━━━━━━\n📝 Today's Remarks\n━━━━━━━━━━━━━━━━━━━━\n\n" +
                        todaysRemarksText.ifBlank { "No remarks yet today" }

                    val message = WhatsAppHelper.fillTemplate(
                        body = "━━━━━━━━━━━━━━━━━━━━\n📦 Parcel Info\n━━━━━━━━━━━━━━━━━━━━\n" +
                            "🆔 Consignment: {consignmentId}\n" +
                            "👤 Customer: {name}\n" +
                            "📞 Phone: {phone}\n" +
                            "📍 Address: {address}\n" +
                            "💰 COD: ৳{cod}\n" +
                            "🏢 Hub: {hub}" + remarksSection,
                        name = item.customer,
                        phone = item.phone,
                        address = item.address,
                        cod = item.cod.toString(),
                        consignmentId = item.id,
                        hub = item.branch
                    )
                    // item.workerPhone is blank when the agent has none on file -- send() already
                    // toasts "Phone number নেই" in that case, so no extra guard needed here.
                    WhatsAppHelper.send(requireContext(), item.workerPhone, message)
                }
            },
            onSendToDesktop = { item ->
                viewLifecycleOwner.lifecycleScope.launch {
                    SendToDesktopHelper.sendToConnectedExtensions(
                        requireContext().applicationContext,
                        SendToDesktopHelper.buildParcelInfoText(item)
                    )
                }
            },
            onLongPress = { item -> showActionHistoryDialog(item) },
            onExpand = { item ->
                val user = FirebaseAuth.getInstance().currentUser
                val uid = user?.uid.orEmpty()
                val group = samePhoneGroup(item)
                val agent = EngagedAgent(
                    uid = uid,
                    name = user?.displayName.orEmpty().ifBlank { "CC Agent" },
                    timestamp = System.currentTimeMillis(),
                    photoUrl = user?.photoUrl?.toString().orEmpty()
                )
                applyLocalCcEngagement(group.map { it.id }.toSet(), agent)
                group.forEach { p ->
                    EngagedStateManager.markEngaged(
                        consignmentId = p.id,
                        agentUid = uid,
                        agentName = agent.name,
                        agentRole = "cc"
                    )
                }
            },
            onCollapse = { item ->
                val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                val group = samePhoneGroup(item)
                removeLocalCcEngagement(group.map { it.id }.toSet(), uid)
                group.forEach { p -> EngagedStateManager.clearEngaged(p.id, uid) }
            }
        )
        adapter.sortMode = sortMode // reflect the preference restored in loadFilterPreferences()
        rvParcelList.layoutManager = LinearLayoutManager(requireContext())
        rvParcelList.adapter = adapter
        // Item views recycle instead of being fully re-inflated on every refresh/filter.
        rvParcelList.setHasFixedSize(false)

        // Swipe shortcuts: right = call, left = remarks. Header rows aren't swipeable.
        ItemTouchHelper(
            SwipeActionCallback(
                context = requireContext(),
                isSwipeable = { position -> adapter.isCardRow(position) },
                onSwipeRight = { position ->
                    adapter.parcelAt(position)?.let { item ->
                        AutoDialHelper.dial(this@CallCenterFragment, item.phone)
                        verifyAndIncrementDialCount(item.id, item.phone)
                        callCardStates[item.id] = colorCallDone
                        pushCallStates()
                        // Expand this card's remarks drawer immediately so it's visible to
                        // anyone else on the same screen that this parcel is being worked on.
                        pendingExpandParcelId = item.id
                        applyFilters()
                    }
                },
                onSwipeLeft = { position ->
                    adapter.parcelAt(position)?.let { item -> showRemarksDialog(item) }
                }
            )
        ).attachToRecyclerView(rvParcelList)
        rvParcelList.setItemViewCacheSize(8)
    }

    /**
     * Long-press journey popup — shows a parcel's full remark history (courier/
     * remarks_by_consignment/{id}), built in processRunsSnapshot()/syncCcRemarkListeners()
     * with each entry's author already resolved to a real name (see UserNameResolver).
     * Reuses the same bottom_sheet_action_history layout as WorkerSpaceFragment.
     */
    /** Formats the gap between updatedAt and createdAt as a human-readable age
     *  (e.g. "2 Days", "1 Day", "5 Hours", "Just now"). */
    private fun formatAge(createdAt: Long, updatedAt: Long): String {
        if (createdAt <= 0L) return "—"
        val end = if (updatedAt > 0L) updatedAt else System.currentTimeMillis()
        val diffMs = (end - createdAt).coerceAtLeast(0L)
        val days = diffMs / (24 * 60 * 60 * 1000)
        val hours = diffMs / (60 * 60 * 1000)
        val minutes = diffMs / (60 * 1000)
        return when {
            days >= 1  -> "$days ${if (days == 1L) "Day" else "Days"}"
            hours >= 1 -> "$hours ${if (hours == 1L) "Hour" else "Hours"}"
            minutes >= 1 -> "$minutes ${if (minutes == 1L) "Minute" else "Minutes"}"
            else -> "Just now"
        }
    }

    private fun showActionHistoryDialog(item: CallCenterParcelItem) {
        // Full history is intentionally loaded only when the journey log is opened.
        // Keep the SAME dialog+view instance across the loading -> loaded transition:
        // dismissing the loading dialog and opening a second, brand-new one for the
        // content caused a visible flicker (the sheet appeared to open twice).
        val (dialog, dialogView) = renderActionHistoryDialog(item, isLoading = true)

        fun load() {
            renderActionHistoryDialog(item, isLoading = true, existing = dialog to dialogView)
            viewLifecycleOwner.lifecycleScope.launch {
                // withTimeoutOrNull is a safety net on top of SupabaseClientManager's own
                // httpClient timeouts (connect/read/write 10s, overall callTimeout 15s) —
                // between the two, this coroutine can never hang indefinitely even if a
                // future change to the client's timeout config regresses. A timeout or any
                // exception here surfaces as a normal "load failed, tap to retry" state
                // instead of an unbounded spinner.
                val rows = kotlin.runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(20_000) {
                        withContext(Dispatchers.IO) {
                            val deferred = kotlinx.coroutines.CompletableDeferred<List<org.json.JSONObject>>()
                            SupabaseRemarkValidationWriter.fetchHistory(item.id, "CallCenterFragment") { fetched ->
                                deferred.complete(fetched)
                            }
                            val fetched = deferred.await()
                            // Supabase validation rows contain author_system_id, not the nested
                            // Firebase profile object used by the old history response. Ensure the
                            // existing system-id -> Firebase profile cache is ready before rendering.
                            ensureAgentNameMap()
                            fetched
                        }
                    }
                }.getOrNull()

                if (!isAdded || !dialog.isShowing) return@launch
                if (rows == null) {
                    renderActionHistoryDialog(
                        item, isLoading = false, hasFailed = true,
                        existing = dialog to dialogView, onRetry = { load() }
                    )
                    return@launch
                }
                renderActionHistoryDialog(
                    item.copy(history = buildHistoryEntries(item, rows)),
                    isLoading = false,
                    existing = dialog to dialogView
                )
            }
        }
        load()
    }

    private fun buildHistoryEntries(item: CallCenterParcelItem, rows: List<org.json.JSONObject>): List<HistoryEntry> {
        val nameMap = systemIdToName
        return rows.mapNotNull { r ->
            val status = r.optString("remarks_status").trim()
            val remarks = listOf(resolveRemarkBn(r.optString("remarks").trim()), r.optString("note").trim())
                .filter { it.isNotBlank() }.joinToString("\n")
            if (status.isBlank() && remarks.isBlank()) return@mapNotNull null
            val createdAt = SupabaseRemarkValidationWriter.parseCreatedAtMillis(r.optString("created_at"))
            val authorSystemId = r.optString("author_system_id").trim()
            // The writer stores the authoritative actor type in `source`. The assigned
            // worker can also submit a CC-originated note, so comparing system IDs here
            // mislabels those entries as WORKER actions.
            val fromWorker = r.optString("source").trim().equals("WORKER", ignoreCase = true)
            val authorUser = r.optJSONObject("author")
            val authorName = authorUser?.optString("name")?.trim().orEmpty()
                .ifBlank { nameMap[authorSystemId].orEmpty() }
                .ifBlank { authorSystemId }
            val authorEmployeeId = authorUser?.optString("employee_id")?.trim().orEmpty()
                .ifBlank { systemIdToEmployeeId[authorSystemId].orEmpty() }
            val authorLabel = if (authorEmployeeId.isBlank()) authorName else "$authorName ($authorEmployeeId)"
            HistoryEntry(
                action = status.ifBlank { "NOTE" }.uppercase(),
                remark = remarks,
                time = java.text.SimpleDateFormat("dd-MM-yy hh:mm:ss a", java.util.Locale.getDefault())
                    .format(java.util.Date(createdAt)),
                author = authorLabel + if (fromWorker) "" else " · CC",
                authorRole = if (fromWorker) "agent" else "cc",
                authorPhotoUrl = authorUser?.optString("photo_url")?.trim().orEmpty()
                    .ifBlank { systemIdToPhotoUrl[authorSystemId].orEmpty() },
                createdAt = createdAt,
                callLogCount = 0,
                callLogTotalDurationSec = 0
            )
        }.sortedBy { it.createdAt }
    }

    private fun renderActionHistoryDialog(
        item: CallCenterParcelItem,
        isLoading: Boolean = false,
        hasFailed: Boolean = false,
        existing: Pair<BottomSheetDialog, View>? = null,
        onRetry: (() -> Unit)? = null
    ): Pair<BottomSheetDialog, View> {
        val dialog = existing?.first ?: BottomSheetDialog(requireContext())
        val view = existing?.second ?: layoutInflater.inflate(R.layout.bottom_sheet_action_history, null)
        val tvTitle = view.findViewById<TextView>(R.id.twHistoryTitle)
        val tvSub = view.findViewById<TextView>(R.id.twHistorySub)
        val layoutTimeline = view.findViewById<LinearLayout>(R.id.layoutTimeline)
        val layoutLoading = view.findViewById<View>(R.id.layoutHistoryLoading)
        val scrollTimeline = view.findViewById<View>(R.id.scrollHistoryTimeline)
        val pbLoading = view.findViewById<android.widget.ProgressBar>(R.id.pbHistoryLoading)
        val tvLoadingLabel = view.findViewById<TextView>(R.id.twHistoryLoadingLabel)
        val btnRetry = view.findViewById<TextView>(R.id.btnHistoryRetry)
        val tvOvStatus = view.findViewById<TextView>(R.id.twOverviewStatus)
        val tvOvCreatedAt = view.findViewById<TextView>(R.id.twOverviewCreatedAt)
        val tvOvUpdatedAt = view.findViewById<TextView>(R.id.twOverviewUpdatedAt)
        val tvOvAge = view.findViewById<TextView>(R.id.twOverviewAge)

        tvTitle.text = "Journey Log"
        tvSub.text = "${item.id} · ${item.customer}"

        // Overview
        val cfg = WorkerParcelAdapter.getStatusConfig(requireContext(), item.effectiveStatus, "bn")
        tvOvStatus.text = cfg.label
        tvOvStatus.setTextColor(cfg.color)
        val fullFmt = java.text.SimpleDateFormat("dd-MM-yy hh:mm:ss a", java.util.Locale.getDefault())
        tvOvCreatedAt.text = if (item.createdAt > 0) fullFmt.format(java.util.Date(item.createdAt)) else "—"
        tvOvUpdatedAt.text = if (item.updatedAt > 0) fullFmt.format(java.util.Date(item.updatedAt)) else "—"
        tvOvAge.text = formatAge(item.createdAt, item.updatedAt)
        val (ovAgeColor, _) = WorkerParcelAdapter.ageColorFor(item.createdAt)
        tvOvAge.setTextColor(ovAgeColor)

        layoutTimeline.removeAllViews()
        // Three states share layoutHistoryLoading's space: spinner-only (isLoading, not
        // failed), retry-only (failed), or hidden entirely (loaded) — scrollTimeline is
        // visible only in the loaded state.
        layoutLoading.visibility = if (isLoading || hasFailed) View.VISIBLE else View.GONE
        scrollTimeline.visibility = if (isLoading || hasFailed) View.GONE else View.VISIBLE
        pbLoading.visibility = if (isLoading && !hasFailed) View.VISIBLE else View.GONE
        tvLoadingLabel.visibility = if (isLoading && !hasFailed) View.VISIBLE else View.GONE
        if (hasFailed) {
            btnRetry.visibility = View.VISIBLE
            btnRetry.setOnClickListener { onRetry?.invoke() }
        } else {
            btnRetry.visibility = View.GONE
            btnRetry.setOnClickListener(null)
        }
        if (isLoading || hasFailed) {
            if (existing == null) {
                view.findViewById<TextView>(R.id.btnHistoryClose).setOnClickListener { dialog.dismiss() }
                dialog.setContentView(view)
                dialog.show()
            }
            return dialog to view
        }

        val historyEntries = mutableListOf<HistoryEntry>()
        if (item.createdAt > 0) {
            historyEntries.add(
                HistoryEntry(
                    action = "CREATED",
                    remark = "Parcel তৈরি হয়েছে",
                    time = fullFmt.format(java.util.Date(item.createdAt)),
                    author = "System",
                    authorRole = "system"
                )
            )
        }
        historyEntries.addAll(item.history)

        // Annotate consecutive entries with worker↔CC handoff response times.
        val entriesWithGaps = WorkerParcelAdapter.withResponseGaps(historyEntries)

        if (entriesWithGaps.isEmpty()) {
            val emptyView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_timeline_empty, layoutTimeline, false)
            layoutTimeline.addView(emptyView)
        } else {
            for ((index, entry) in entriesWithGaps.withIndex()) {
                val timelineView = layoutInflater.inflate(R.layout.item_timeline_entry, layoutTimeline, false)
                val statusCfg = WorkerParcelAdapter.getStatusConfig(
                    requireContext(),
                    entry.action.lowercase().replace(" ", "_"),
                    ccStatusLang
                )

                val ivAvatar = timelineView.findViewById<android.widget.ImageView>(R.id.ivTimelineAvatar)
                val tvLine = timelineView.findViewById<View>(R.id.viewTimelineLine)
                val tvAuthor = timelineView.findViewById<TextView>(R.id.twTimelineAuthor)
                val tvStatus = timelineView.findViewById<TextView>(R.id.twTimelineStatus)
                val tvRemark = timelineView.findViewById<TextView>(R.id.twTimelineRemark)
                val tvMeta = timelineView.findViewById<TextView>(R.id.twTimelineMeta)
                val tvGap = timelineView.findViewById<TextView>(R.id.twTimelineGap)
                val tvCallLogs = timelineView.findViewById<TextView>(R.id.twTimelineCallLogs)

                if (entry.authorPhotoUrl.isNotBlank()) {
                    ivAvatar.load(entry.authorPhotoUrl) {
                        crossfade(true)
                        placeholder(R.drawable.bg_timeline_avatar_placeholder)
                        error(R.drawable.bg_timeline_avatar_placeholder)
                    }
                } else {
                    ivAvatar.setImageDrawable(null)
                    ivAvatar.setBackgroundResource(R.drawable.bg_timeline_avatar_placeholder)
                }

                tvLine.visibility = if (index < entriesWithGaps.size - 1) View.VISIBLE else View.GONE

                tvAuthor.text = entry.author

                tvStatus.text = entry.action
                tvStatus.setTextColor(statusCfg.color)
                tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(statusCfg.bg)

                tvRemark.text = entry.remark
                tvRemark.visibility = if (entry.remark.isNotBlank()) View.VISIBLE else View.GONE

                tvMeta.text = entry.time

                val gapMin = entry.responseGapMinutes
                if (gapMin != null) {
                    tvGap.text = "⏱ ${gapMin}m response"
                    tvGap.visibility = View.VISIBLE
                } else {
                    tvGap.visibility = View.GONE
                }

                if (entry.callLogCount > 0) {
                    tvCallLogs.text = "📞 ${entry.callLogCount} call${if (entry.callLogCount == 1) "" else "s"}, ${entry.callLogTotalDurationSec}s total"
                    tvCallLogs.visibility = View.VISIBLE
                } else {
                    tvCallLogs.visibility = View.GONE
                }

                layoutTimeline.addView(timelineView)
            }
        }

        if (existing == null) {
            view.findViewById<TextView>(R.id.btnHistoryClose).setOnClickListener {
                dialog.dismiss()
            }
            dialog.setContentView(view)
            dialog.show()
        }
        return dialog to view
    }

    private fun updateModeDropdownLabel() {
        val ctx = context ?: return
        val label = when {
            selectedAccessModes.containsAll(listOf("priority", "all")) -> "🔔+👥 Both ▾"
            "priority" in selectedAccessModes -> "🔔 Priority ▾"
            else -> "👥 All Agents ▾"
        }
        tvModeDropdown.text = label
        tvModeDropdown.setBackgroundResource(R.drawable.bg_filter_chip_active)
        tvModeDropdown.setTextColor(ctx.getColor(android.R.color.white))
    }

    private fun updateCcSortByLabel() {
        tvSortByDropdown.text = if (sortMode == "aging") "🕐 Aging ▾" else "🔁 Attempt ▾"
    }

    private fun showCcSortByDropdown() {
        val ctx = context ?: return
        val options = arrayOf("🔁 Attempt (most attempted first)", "🕐 Aging (oldest first)")
        val keys = arrayOf("attempt", "aging")
        val currentIndex = keys.indexOf(sortMode).coerceAtLeast(0)
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Sort by")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                sortMode = keys[which]
                updateCcSortByLabel()
                adapter.sortMode = sortMode
                saveFilterPreferences()
                // applyFilters() re-applies search/status filtering AND calls
                // adapter.submitParcels() at the end — calling submitParcels() directly
                // here would bypass whatever search query or status filter is active.
                applyFilters()
                dialog.dismiss()
            }
            .show()
    }

    private fun showModeDropdown() {
        val ctx = context ?: return
        val options = arrayOf("🔔 Priority Queue", "👥 All Agents")
        val keys = arrayOf("priority", "all")
        val checked = BooleanArray(keys.size) { i -> keys[i] in selectedAccessModes }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Access Mode")
            .setMultiChoiceItems(options, checked) { _, which, isChecked ->
                if (isChecked) selectedAccessModes.add(keys[which])
                else selectedAccessModes.remove(keys[which])
            }
            .setPositiveButton("Apply") { _, _ ->
                // Both being deselected isn't a meaningful state — fall back to "all"
                // rather than showing an empty/undefined list.
                if (selectedAccessModes.isEmpty()) selectedAccessModes.add("all")
                updateModeDropdownLabel()
                // Rebuilds chips too — their counts depend on this scope (see scopedParcels()).
                // NOT rebuildCcAgentRoster() here — access mode filters a selected agent's
                // parcels, it doesn't change which agents are selectable (see that function's
                // doc comment for why).
                setupFilterTabs()
                applyFilters()
                saveFilterPreferences()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupBranchDropdown() {
        if (branches.size <= 1) {
            tvBranchDropdown.visibility = View.GONE
            return
        }
        tvBranchDropdown.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val db = com.google.firebase.database.FirebaseDatabase.getInstance()
            branches.forEach { branchId ->
                if (!branchIdToName.containsKey(branchId)) {
                    val name = withContext(Dispatchers.IO) {
                        runCatching {
                            db.reference.child("branches/$branchId/name").get().await()
                                .getValue(String::class.java) ?: branchId
                        }.getOrDefault(branchId)
                    }
                    branchIdToName[branchId] = name
                }
            }
            updateBranchDropdownLabel()
        }
    }

    private fun updateBranchDropdownLabel() {
        val ctx = context ?: return
        val selected = selectedBranchIds.intersect(branches.toSet())
        val isFiltered = selected.isNotEmpty() && selected.size < branches.size
        val label = when {
            !isFiltered -> "All Branches ▾"
            selected.size == 1 -> "${branchIdToName[selected.first()] ?: selected.first()} ▾"
            selected.size == 2 -> {
                val names = selected.map { branchIdToName[it] ?: it }
                "${names[0]} & ${names[1]} ▾"
            }
            else -> {
                val names = selected.take(2).map { branchIdToName[it] ?: it }
                "Filter for ${names[0]}, ${names[1]} & ${selected.size - 2} more ▾"
            }
        }
        tvBranchDropdown.text = label
        tvBranchDropdown.setBackgroundResource(
            if (isFiltered) R.drawable.bg_filter_chip_active_purple else R.drawable.bg_filter_chip_inactive
        )
        tvBranchDropdown.setTextColor(
            ctx.getColor(if (isFiltered) android.R.color.white else R.color.theme_text_secondary)
        )
    }

    private fun showBranchDropdown() {
        val ctx = context ?: return
        val branchArray = branches.toTypedArray()
        val names = branchArray.map { branchIdToName[it] ?: it }.toTypedArray()
        // Working copy for this dialog session. Stored selectedBranchIds uses "empty
        // = all" as its canonical resting state — but if we check boxes against that
        // empty set directly, unchecking a box tries to remove an id that was never
        // actually in the set, silently doing nothing. So: expand to the full set
        // up front when starting from "all", then collapse back to empty at Apply
        // time if everything is still selected.
        val working = if (selectedBranchIds.isEmpty()) branchArray.toMutableSet()
                      else selectedBranchIds.toMutableSet()
        val checked = BooleanArray(branchArray.size) { i -> branchArray[i] in working }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Select Branches")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                if (isChecked) working.add(branchArray[which]) else working.remove(branchArray[which])
            }
            .setPositiveButton("Apply") { _, _ ->
                selectedBranchIds.clear()
                if (working.size < branches.size) selectedBranchIds.addAll(working)
                updateBranchDropdownLabel()
                setupFilterTabs()
                rebuildCcAgentRoster()
                applyFilters()
                saveFilterPreferences()
            }
            .setNeutralButton(if (selectedBranchIds.isEmpty()) "All" else "Clear") { _, _ ->
                selectedBranchIds.clear()
                updateBranchDropdownLabel()
                setupFilterTabs()
                rebuildCcAgentRoster()
                applyFilters()
                saveFilterPreferences()
            }
            .show()
    }

    /**
     * allParcels narrowed by access mode + branch ONLY (not agent, not search, not the
     * status chip). This is the shared basis for scopedParcels() below. NOT used by the
     * agent dropdown (see rebuildCcAgentRoster()) — that roster is sourced from
     * ccBranchRangeSnapshots instead, specifically to stay independent of which agents are
     * currently fetched into allParcels.
     */
    private fun parcelsScopedByModeAndBranch(): List<CallCenterParcelItem> {
        var scoped = allParcels

        // Access mode — Priority-only shows just agents who sent a verify request;
        // All-only shows everyone in-branch regardless; both selected = everyone
        // (priority-first ordering is applied later, in applyFilters()).
        if ("priority" in selectedAccessModes && "all" !in selectedAccessModes) {
            scoped = scoped.filter { it.validationRequest }
        }
        if (selectedBranchIds.isNotEmpty()) {
            scoped = scoped.filter { parcel ->
                if (parcel.branchIds.isNotEmpty()) {
                    parcel.branchIds.any { it in selectedBranchIds }
                } else {
                    // Legacy fallback for parcels built before branchIds existed in-memory.
                    val selectedNames = selectedBranchIds.map { branchIdToName[it] ?: it }.toSet()
                    parcel.branch in selectedNames
                }
            }
        }
        return scoped
    }

    /**
     * allParcels narrowed by the three "scope" filters — access mode, branch, agent —
     * but NOT by search or the status chip itself. This is the shared basis for:
     * the status chip counts, the stat-summary numbers (Total/Confirmed/Pending/
     * Rejected/Validation), and the starting point of applyFilters()'s visible list.
     * Keeping one function means chip counts and the actual list can't drift apart.
     */
    private fun scopedParcels(): List<CallCenterParcelItem> {
        var scoped = parcelsScopedByModeAndBranch()
        if (selectedAgentFilters.isNotEmpty()) {
            scoped = scoped.filter { it.workerSystemId in selectedAgentFilters }
        }
        return scoped
    }


    private fun updateRecallButton() {
        val count = allParcels.count { it.remarks == AUTO_NO_ANSWER_REMARK_TEXT }
        btnRecallList.text = "🔁 Recall($count)"
        btnRecallList.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    private fun setupFilterTabs() {
        updateRecallButton()
        layoutFilterTabs.removeAllViews()
        val scoped       = scopedParcels()
        val total        = scoped.size
        val statusCounts = scoped.groupingBy { it.effectiveStatus }.eachCount()

        // Reset active filter if it no longer exists in data
        if (statusFilter != "all" && !statusCounts.containsKey(statusFilter)) {
            statusFilter = "all"
        }

        // Chips sorted by config/statusMeta/{key}/sortOrder (admin-managed in
        // ConfigStatusesFragment) — higher sortOrder first. Ties broken alphabetically
        // for a stable order; unconfigured statuses (sortOrder 0) sort last together.
        val sortedEntries = statusCounts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { StatusMetaCache.entries[it.key]?.sortOrder ?: 0 }
                .thenBy { it.key }
        )

        val filters = mutableListOf(FilterTab("all", "All($total)"))
        sortedEntries.forEach { (statusKey, count) ->
            val label = WorkerParcelAdapter.getStatusConfig(requireContext(), statusKey, ccStatusLang).label
            filters.add(FilterTab(statusKey, "$label($count)"))
        }

        for (filter in filters) {
            val chip = layoutInflater.inflate(R.layout.item_filter_chip, layoutFilterTabs, false) as TextView
            chip.text = filter.label
            chip.tag  = filter.key
            chip.setOnClickListener {
                statusFilter = filter.key
                updateFilterChips()
                applyFilters()
            }
            layoutFilterTabs.addView(chip)
        }
        updateFilterChips()
    }

    private fun updateFilterChips() {
        val ctx = requireContext()
        for (i in 0 until layoutFilterTabs.childCount) {
            val chip = layoutFilterTabs.getChildAt(i) as? TextView ?: continue
            val statusKey = chip.tag as? String ?: continue
            val isActive = statusKey == statusFilter
            val metaColor: Int? = if (statusKey == "all") null
                else StatusMetaCache.entries[statusKey]?.color
            chip.isSelected = isActive
            if (isActive && metaColor != null) {
                try {
                    chip.background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(metaColor); cornerRadius = 24f
                    }
                    chip.setTextColor(android.graphics.Color.WHITE)
                } catch (_: Exception) {
                    chip.setBackgroundResource(R.drawable.bg_filter_chip_active)
                    chip.setTextColor(ctx.getColor(android.R.color.white))
                }
            } else if (isActive) {
                chip.setBackgroundResource(R.drawable.bg_filter_chip_active)
                chip.setTextColor(ctx.getColor(android.R.color.white))
            } else {
                chip.setBackgroundResource(R.drawable.bg_filter_chip_inactive)
                chip.setTextColor(ctx.getColor(R.color.theme_text_secondary))
            }
        }
    }

    // ── Run type selection (mirrors WorkerSpaceFragment pattern) ──────
    private lateinit var spinnerCcRunType: Spinner
    data class CcRunTypeOption(val key: String, val label: String)
    private val CC_RUN_TYPE_ALL = "__ALL__"
    private var ccSelectedRunType = CC_RUN_TYPE_ALL
    private var ccRunTypeOptions = listOf(CcRunTypeOption(CC_RUN_TYPE_ALL, "All"))

    private fun loadData() {
        pbProgress.visibility = View.VISIBLE
        tvLoadingPercent.visibility = View.VISIBLE
        tvLoadingPercent.text = "লোড হচ্ছে... 0%"
        tvEmpty.visibility    = View.GONE
        detachRunsListener()
        attachRootRunTypesListener()
    }

    /** Discovers today's runs, scoped strictly to the CC agent's OWN assigned branches via
     *  courier/runs_by_branchId/{branchId} — never reads other branches' data or the full
     *  historical courier/run_routes tree.
     *
     *  Two-phase approach:
     *  1. One-time fetch of each branch index node to discover run-type keys
     *     (e.g. "delivery_run", "return_run") — accepted one-time cost.
     *  2. Per run-type: a server-side range query using today's yyyyMMdd STRING prefix
     *     (run keys are run_{yyyyMMdd}_{systemId} — a date string, not an epoch timestamp).
     *     orderByKey().startAt("run_{yyyyMMdd}_").endAt("run_{yyyyMMdd}_\uf8ff") — \uf8ff is
     *     the highest Unicode char Firebase keys can contain, so this range matches every key
     *     with that exact date prefix regardless of what follows (any systemId), and
     *     nothing outside today. Live-listens, so status changes within today still fire.
     *
     *  NOTE (prior bug, now fixed): an earlier version built the range bounds from
     *  Calendar.timeInMillis (epoch ms) — e.g. startAt("run_1752480000000") — which can
     *  never match a date-string key like "run_20260714_EMP001" (numeric prefixes diverge
     *  immediately char-by-char), so the query always returned empty regardless of what
     *  data existed. The fix is to build the SAME date-string format the write path uses
     *  (the exact yyyyMMdd date-string format the write path uses — chosen so it also
     *  sorts chronologically as a plain string, which a 6-digit day-first format doesn't).
     */
    private fun attachRootRunTypesListener() {
        detachRootRunTypesListener()
        myBranchIds = RbacManager.current.branchIds
        // Drop any previously-selected branch filter id that's no longer part of this
        // agent's current assignment (e.g. admin changed their branches since the filter
        // was last saved) — same self-healing pattern rebuildCcAgentRoster() already
        // applies to selectedAgentFilters. Without this, a stale id that matches nothing
        // in the current parcel set silently filters the list down to zero results — and
        // if myBranchIds is now down to 1, the branch dropdown hides itself entirely
        // (branches.size <= 1 in setupBranchDropdown()), leaving no in-app way to notice
        // or fix it. retainAll() on an empty myBranchIds just clears the filter too, which
        // is fine since the "no branch assigned" empty-state below returns right after.
        if (selectedBranchIds.retainAll(myBranchIds.toSet())) {
            saveFilterPreferences()
        }
        if (myBranchIds.isEmpty()) {
            pbProgress.visibility = View.GONE
            tvLoadingPercent.visibility = View.GONE
            tvEmpty.visibility    = View.VISIBLE
            tvEmpty.text          = "⚠ কোনো branch assigned নেই — admin-এর সাথে যোগাযোগ করুন"
            return
        }

        val db = com.google.firebase.database.FirebaseDatabase.getInstance()
        val branchIdsSnapshot = myBranchIds
        ccReportedBranchIds.clear()
        ccExpectedBranchCount = branchIdsSnapshot.size
        ccExpectedPhase2Keys.clear()
        ccStableCandidateKeys = emptySet()

        val todayDateKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ENGLISH)
            .format(java.util.Date())

        // Agent name-map resolution and the range queries below are INDEPENDENT — the name
        // map is only consulted later, inside onBranchIndexesLoaded()'s filtering step, not
        // needed to issue the queries themselves. Firing them in parallel (rather than
        // awaiting the name map first) shaves the full ensureAgentNameMap() round-trip off
        // time-to-first-data, which matters most on a cold app start with many agents.
        viewLifecycleOwner.lifecycleScope.launch { ensureAgentNameMap() }

        branchIdsSnapshot.forEach { branchId ->
            val branchRef = db.reference.child("courier/runs_by_branchId/$branchId")

            // Phase 1 — one-time fetch to discover run-type keys for this branch
            branchRef.addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    if (!isAdded) return
                    ccReportedBranchIds.add(branchId)
                    updateCcLoadingPercentDisplay()
                    val runTypes = snapshot.children.mapNotNull { it.key }.distinct().sorted()
                    if (runTypes.isEmpty()) {
                        val allTypes = ccBranchRangeSnapshots.keys
                            .map { it.substringAfter("/") }.distinct().sorted()
                        onBranchIndexesLoaded(allTypes, ccBranchRangeSnapshots)
                        return
                    }

                    // Phase 2 — per run-type: server-side range query on today's date-string prefix
                    runTypes.forEach { runType ->
                        val rangeKey = "$branchId/$runType"
                        val query = branchRef.child(runType)
                            .orderByKey()
                            .startAt("run_${todayDateKey}_")
                            .endAt("run_${todayDateKey}_\uf8ff")

                        val listener = object : com.google.firebase.database.ValueEventListener {
                            override fun onDataChange(snap: com.google.firebase.database.DataSnapshot) {
                                if (!isAdded) return
                                ccBranchRangeSnapshots[rangeKey] = snap
                                updateCcLoadingPercentDisplay()
                                val allRunTypes = ccBranchRangeSnapshots.keys
                                    .map { it.substringAfter("/") }.distinct().sorted()
                                onBranchIndexesLoaded(allRunTypes, ccBranchRangeSnapshots)
                            }
                            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
                        }
                        query.addValueEventListener(listener)
                        ccActiveListeners.add(query to listener)
                        ccExpectedPhase2Keys.add(rangeKey)
                    }
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                    if (!isAdded) return
                    pbProgress.visibility = View.GONE
                    tvLoadingPercent.visibility = View.GONE
                    tvEmpty.visibility    = View.VISIBLE
                    tvEmpty.text          = "⚠ Load failed: ${error.message.take(60)}"
                }
            })
        }
    }

    private fun detachRootRunTypesListener() {
        // Branch-index listeners live in ccActiveListeners now (shared cleanup with per-run
        // listeners in detachRunsListener) — nothing separate to tear down here. Kept as a
        // no-op so loadData()'s call site doesn't need to change.
    }

    private fun bindCcRunTypeSpinner() {
        if (!::spinnerCcRunType.isInitialized) return
        val ctx = context ?: return
        val labels = ccRunTypeOptions.map { it.label }
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCcRunType.adapter = adapter
        spinnerCcRunType.setSelection(ccRunTypeOptions.indexOfFirst { it.key == ccSelectedRunType }.coerceAtLeast(0))
        spinnerCcRunType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val nextType = ccRunTypeOptions.getOrNull(position)?.key ?: CC_RUN_TYPE_ALL
                if (nextType == ccSelectedRunType) return
                ccSelectedRunType = nextType
                loadData()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // ✅ Fix #8: 300ms debounce — prevents excessive filter calls on every keystroke
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    searchQuery = s?.toString()?.trim() ?: ""
                    tvSearchClear.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                    applyFilters()
                }
            }
        })
        tvSearchClear.setOnClickListener { etSearch.text?.clear() }
    }

    private fun setupCollapseToggle() {
        layoutCollapsibleSection.visibility = if (isHeaderExpanded) View.VISIBLE else View.GONE
        tvCollapseArrow.text = if (isHeaderExpanded) "▲" else "▼"
        tvCollapseArrow.setOnClickListener {
            isHeaderExpanded = !isHeaderExpanded
            layoutCollapsibleSection.visibility = if (isHeaderExpanded) View.VISIBLE else View.GONE
            tvCollapseArrow.text = if (isHeaderExpanded) "▲" else "▼"
        }
    }

    /** Rebuilds the agent filter dropdown roster from the run ID keys already present in
     *  ccBranchRangeSnapshots — deliberately NOT from allParcels. The agent dropdown must
     *  always contain every agent with a run in the selected branches, regardless of the
     *  currently selected agent filter. ccBranchRangeSnapshots costs nothing extra to read
     *  here — the range query that discovers run IDs runs unconditionally for every branch.
     *
     *  Depends on selectedBranchIds (client-side filter over already-fetched branches, via
     *  each rangeKey's "branchId/runType" prefix) but deliberately NOT on selectedAccessModes:
     *  whether an agent has a verify_req parcel can only be known once their run node is
     *  actually fetched, which is exactly the fetch this function avoids triggering. The
     *  roster answers "who has a run today in my selected branches" — access mode then filters
     *  THAT selected agent's parcels (via scopedParcels()), not which agents are selectable. */
    private fun rebuildCcAgentRoster() {
        if (!::tvAgentDropdown.isInitialized) return
        val selectedBranches = selectedBranchIds.toSet()
        val systemIds = mutableSetOf<String>()
        ccBranchRangeSnapshots.forEach { (rangeKey, snap) ->
            val branchId = rangeKey.substringBefore("/")
            if (selectedBranches.isNotEmpty() && branchId !in selectedBranches) return@forEach
            snap.children.forEach { runIdSnap ->
                val runId = runIdSnap.key ?: return@forEach
                val match = RUN_ID_PATTERN.matchEntire(runId.trim()) ?: return@forEach
                systemIds.add(match.groupValues[2])
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val nameMap = ensureAgentNameMap()
            if (!isAdded) return@launch
            val agents = systemIds
                .map { sysId -> AgentOption(sysId, nameMap[sysId] ?: sysId, systemIdToEmployeeId[sysId].orEmpty()) }
                .sortedBy { it.name }
            ccAgentOptions = agents
            // Drop any previously-selected agent (by systemId) who no longer has a run today
            // in the selected branches.
            val liveSystemIds = agents.map { it.systemId }.toSet()
            selectedAgentFilters.retainAll(liveSystemIds + NO_AGENT_SENTINEL)
            updateAgentDropdownLabel()
        }
    }

    private fun updateAgentDropdownLabel() {
        val ctx = context ?: return
        val optionsBySystemId = ccAgentOptions.associateBy { it.systemId }
        val selected = selectedAgentFilters.mapNotNull { optionsBySystemId[it] }
        val isFiltered = selected.isNotEmpty() && selected.size < ccAgentOptions.size
        val label = when {
            !isFiltered -> "👥 All Agents ▾"
            selected.size == 1 -> "${selected.first().name} ▾"
            selected.size == 2 -> "${selected[0].name} & ${selected[1].name} ▾"
            else -> "${selected[0].name}, ${selected[1].name} & ${selected.size - 2} more ▾"
        }
        tvAgentDropdown.text = label
        tvAgentDropdown.setBackgroundResource(
            if (isFiltered) R.drawable.bg_filter_chip_active_purple else R.drawable.bg_filter_chip_inactive
        )
        tvAgentDropdown.setTextColor(
            ctx.getColor(if (isFiltered) android.R.color.white else R.color.theme_text_secondary)
        )
    }

    private fun showAgentDropdown() {
        val ctx = context ?: return
        if (ccAgentOptions.isEmpty()) {
            Toast.makeText(ctx, "এখনো কোনো agent-এর parcel নেই", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_agent_multiselect, null)
        val etSearchAgent        = dialogView.findViewById<EditText>(R.id.etAgentSearch)
        val layoutCheckboxes     = dialogView.findViewById<LinearLayout>(R.id.layoutAgentCheckboxes)
        val tvNoResults          = dialogView.findViewById<TextView>(R.id.tvAgentNoResults)
        val btnSelectClearAll    = dialogView.findViewById<Button>(R.id.btnAgentSelectClearAll)
        val btnApply             = dialogView.findViewById<Button>(R.id.btnAgentApply)

        // Expand to full set when starting from "all" (empty sentinel state), so unchecking
        // removes real membership instead of silently no-op'ing on an empty set.
        val working = when {
            selectedAgentFilters.isEmpty() -> ccAgentOptions.map { it.systemId }.toMutableSet()
            selectedAgentFilters == setOf(NO_AGENT_SENTINEL) -> mutableSetOf()
            else -> selectedAgentFilters.toMutableSet()
        }

        val checkboxes = ccAgentOptions.map { option ->
            val cb = LayoutInflater.from(ctx).inflate(R.layout.item_agent_checkbox, layoutCheckboxes, false) as CheckBox
            cb.text = option.display
            cb.isChecked = option.systemId in working
            layoutCheckboxes.addView(cb)
            option to cb
        }

        var dialog: android.app.AlertDialog? = null

        fun updateToggleButtonLabel() {
            val allChecked = working.size >= ccAgentOptions.size
            btnSelectClearAll.text = if (allChecked) "Clear All" else "Select All"
        }

        checkboxes.forEach { (option, cb) ->
            cb.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) working.add(option.systemId) else working.remove(option.systemId)
                updateToggleButtonLabel()
            }
        }

        // Select All / Clear All — does NOT close dialog
        btnSelectClearAll.setOnClickListener {
            val allChecked = working.size >= ccAgentOptions.size
            if (allChecked) {
                // Clear All — uncheck everything
                working.clear()
                checkboxes.forEach { (_, cb) -> cb.isChecked = false }
            } else {
                // Select All — check everything visible + all
                working.clear()
                working.addAll(ccAgentOptions.map { it.systemId })
                checkboxes.forEach { (_, cb) -> cb.isChecked = true }
            }
            updateToggleButtonLabel()
        }

        etSearchAgent.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                var anyVisible = false
                checkboxes.forEach { (option, cb) ->
                    val matches = q.isEmpty() ||
                        option.name.lowercase().contains(q) ||
                        option.systemId.lowercase().contains(q) ||
                        option.employeeId.lowercase().contains(q)
                    cb.visibility = if (matches) View.VISIBLE else View.GONE
                    if (matches) anyVisible = true
                }
                tvNoResults.visibility = if (anyVisible) View.GONE else View.VISIBLE
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Apply — close dialog and apply the filter
        btnApply.setOnClickListener {
            selectedAgentFilters.clear()
            when {
                working.size >= ccAgentOptions.size -> { /* empty = all agents */ }
                working.isEmpty() -> selectedAgentFilters.add(NO_AGENT_SENTINEL)
                else -> selectedAgentFilters.addAll(working)
            }
            updateAgentDropdownLabel()
            setupFilterTabs()
            saveFilterPreferences()
            // Runs for every assigned branch are already live-cached. Agent selection is a
            // presentation filter only; reloading here would discard the other agents' cache
            // and make a later branch switch appear to have no data.
            applyFilters()
            dialog?.dismiss()
        }

        dialog = android.app.AlertDialog.Builder(ctx)
            .setTitle("Select Agents")
            .setView(dialogView)
            .create()
        dialog?.setOnShowListener { updateToggleButtonLabel() }
        dialog?.show()
    }

    private fun formatCcRunTypeLabel(runType: String): String =
        runType.split("_")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
            }

    /** Same defensive String/Long/Double fallback as WorkerSpaceFragment.readAttempt() —
     *  courier/consignments/{id}/attempt has been written from more than one code path
     *  historically, not always as the same Firebase value type. */
    private fun readCcAttempt(snap: com.google.firebase.database.DataSnapshot): Int {
        return snap.child("attempt").getValue(String::class.java)
            ?.toDoubleOrNull()?.toInt()
            ?: snap.child("attempt").getValue(Long::class.java)?.toInt()
            ?: snap.child("attempt").getValue(Double::class.java)?.toInt()
            ?: 0
    }

    /** Called once every assigned branch/run-type range query's snapshot has arrived (and again
     *  whenever any of them change — new run created, a run's representative status updated,
     *  etc). rangeSnapshots is already server-side scoped to TODAY's date-string prefix (see
     *  attachRootRunTypesListener) — no client-side date parsing/filtering needed here anymore.
     *  Attaches a dedicated live listener for every available run. Branch and agent choices
     *  are applied afterward against the cached parcel list, so one branch's selection can
     *  never prevent another branch's agent from being filtered later. */
    private fun onBranchIndexesLoaded(
        runTypes: List<String>,
        rangeSnapshots: Map<String, com.google.firebase.database.DataSnapshot>
    ) {
        if (!isAdded) return

        ccRunTypeOptions = listOf(CcRunTypeOption(CC_RUN_TYPE_ALL, "All")) +
            runTypes.map { CcRunTypeOption(it, formatCcRunTypeLabel(it)) }
        if (ccSelectedRunType != CC_RUN_TYPE_ALL && ccSelectedRunType !in runTypes) {
            ccSelectedRunType = CC_RUN_TYPE_ALL
        }
        bindCcRunTypeSpinner()
        // Roster only needs the range-query snapshots (already fetched above, unconditionally
        // for every branch) — not the run-type filter or the agent-filtered parcel fetch below.
        rebuildCcAgentRoster()

        val typesToWatch = if (ccSelectedRunType == CC_RUN_TYPE_ALL) runTypes else listOf(ccSelectedRunType)
        if (typesToWatch.isEmpty()) {
            if (ccReportedBranchIds.size < ccExpectedBranchCount) {
                // Not every assigned branch has reported yet — an empty result right now
                // could just mean the branches that DO have runs haven't responded yet.
                // Leave the loading spinner (already showing since loadData()) as-is rather
                // than flashing "No Run" and then correcting it a moment later.
                return
            }
            pbProgress.visibility = View.GONE
            tvLoadingPercent.visibility = View.GONE
            tvEmpty.visibility    = View.VISIBLE
            tvEmpty.text          = "📭\n\nকোনো run নেই"
            syncCcEngagedAtListeners(emptySet())
            return
        }

        // Dedupe (runType, runId) across branches — a multi-branch agent's run is written into
        // EVERY one of their assigned branches' indexes, but it's a single real run node.
        // rangeSnapshots keys are "branchId/runType"; each snapshot's children are runId -> status,
        // already scoped to today by the server-side range query (no timestamp check needed).
        val candidateKeys = mutableSetOf<Pair<String, String>>()
        ccRunKeyBranchIds.clear()
        rangeSnapshots.forEach { (rangeKey, snap) ->
            val branchId = rangeKey.substringBefore("/")
            val runType = rangeKey.substringAfter("/")
            if (runType !in typesToWatch) return@forEach
            snap.children.forEach { runIdSnap ->
                val runId = runIdSnap.key ?: return@forEach
                if (RUN_ID_PATTERN.matchEntire(runId.trim()) == null) return@forEach
                candidateKeys.add(runType to runId)
                ccRunKeyBranchIds.getOrPut("$runType/$runId") { mutableSetOf() }.add(branchId)
            }
        }

        if (candidateKeys.isEmpty()) {
            val allBranchesReported = ccReportedBranchIds.size >= ccExpectedBranchCount
            val allPhase2Reported = ccBranchRangeSnapshots.keys.containsAll(ccExpectedPhase2Keys)
            if (!allBranchesReported || !allPhase2Reported) {
                // Same reasoning as the No Run gate above, one layer deeper: some branches
                // may not have finished Phase 1 discovery yet (so we don't yet know all the
                // Phase 2 listeners that WILL be attached), or some already-attached Phase 2
                // range queries haven't reported back yet. Either way, an empty result right
                // now doesn't mean genuinely nothing today — leave the loading state alone.
                return
            }
            pbProgress.visibility = View.GONE
            tvLoadingPercent.visibility = View.GONE
            tvEmpty.visibility    = View.VISIBLE
            tvEmpty.text          = "📭\n\nআজকের কোনো consignment নেই"
            syncCcEngagedAtListeners(emptySet())
            return
        }

        val db = com.google.firebase.database.FirebaseDatabase.getInstance()
        ccStableCandidateKeys = candidateKeys.toSet()
        updateCcLoadingPercentDisplay()
        candidateKeys.forEach { (runType, runId) ->
            val key = "$runType/$runId"
            if (key in ccAttachedRunKeys) return@forEach
            ccAttachedRunKeys.add(key)
            val ref = db.reference.child("courier/run_routes/$runType/$runId")
            val listener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    if (!isAdded) return
                    ccRunNodeSnapshots[key] = snapshot
                    updateCcLoadingPercentDisplay()
                    // Debounced (same 300ms idiom as setupSearch()'s searchJob) — on cold
                    // load, many agents' run listeners fire their initial onDataChange within
                    // ms of each other. Undebounced, each one independently reprocessed the
                    // then-current (still-growing) ccRunNodeSnapshots from scratch, redoing
                    // every earlier agent's consignment fetch again on every new arrival — up
                    // to O(listener count²) wasted fetches. Coalescing into one call after the
                    // burst settles fixes that; the generation guard inside
                    // reprocessAllCachedRuns() is a second line of defense against any results
                    // still arriving out of order (e.g. a manual refresh mid-debounce).
                    reprocessJob?.cancel()
                    reprocessJob = viewLifecycleOwner.lifecycleScope.launch {
                        delay(300)
                        reprocessAllCachedRuns()
                    }
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            }
            ref.addValueEventListener(listener)
            ccActiveListeners.add(ref to listener)
        }
    }

    private val ccActiveListeners = mutableListOf<Pair<com.google.firebase.database.Query, com.google.firebase.database.ValueEventListener>>()
    /** Per (branchId/runType) range-query result snapshots — accumulated as each live query fires. */
    private val ccBranchRangeSnapshots = mutableMapOf<String, com.google.firebase.database.DataSnapshot>()
    /** Snapshot of candidateKeys taken once Stage 2 (below) completes — a fixed denominator
     *  for the loading-percentage Stage 3, since the live candidateKeys local recomputes
     *  (and would keep growing) on every onBranchIndexesLoaded() call before that point. */
    private var ccStableCandidateKeys: Set<Pair<String, String>> = emptySet()

    /** 3-stage sequential progress (0-30% branch discovery, 30-70% run-type range queries,
     *  70-100% individual run-node fetches) rather than a naive completed/expected ratio,
     *  because each later stage's denominator only becomes known/stable once the stage
     *  before it fully completes — a naive ratio would grow its own denominator as more
     *  branches/run-types are discovered and could make the shown percentage go backwards.
     *  A stage is capped at not-yet-started (0% of its own share) until the stage before it
     *  is done, so the number only ever moves forward. */
    private fun computeCcLoadingPercent(): Int {
        if (ccExpectedBranchCount <= 0) return 0
        val stage1Pct = (ccReportedBranchIds.size.toFloat() / ccExpectedBranchCount).coerceIn(0f, 1f)
        val stage1Done = ccReportedBranchIds.size >= ccExpectedBranchCount
        if (!stage1Done) return (stage1Pct * 30).toInt().coerceIn(0, 29)

        val stage2Done = ccExpectedPhase2Keys.isEmpty() ||
            ccBranchRangeSnapshots.keys.containsAll(ccExpectedPhase2Keys)
        val stage2Pct = if (ccExpectedPhase2Keys.isNotEmpty())
            (ccBranchRangeSnapshots.keys.count { it in ccExpectedPhase2Keys }
                .toFloat() / ccExpectedPhase2Keys.size).coerceIn(0f, 1f)
        else 1f // nothing to wait for — every reporting branch genuinely has zero run-types
        if (!stage2Done) return 30 + (stage2Pct * 40).toInt().coerceIn(0, 39)

        if (ccStableCandidateKeys.isEmpty()) return 100 // stage 2 done, genuinely nothing to fetch
        val stage3Pct = (ccRunNodeSnapshots.keys.count { key ->
            ccStableCandidateKeys.any { "${it.first}/${it.second}" == key }
        }.toFloat() / ccStableCandidateKeys.size).coerceIn(0f, 1f)
        return 70 + (stage3Pct * 30).toInt().coerceIn(0, 30)
    }

    private fun updateCcLoadingPercentDisplay() {
        if (!isAdded || !::tvLoadingPercent.isInitialized) return
        if (tvLoadingPercent.visibility != View.VISIBLE) return
        tvLoadingPercent.text = "লোড হচ্ছে... ${computeCcLoadingPercent()}%"
    }

    // Guards the "no run" empty state against a race: each assigned branch's Phase 1
    // discovery listener fires independently and asynchronously (Firebase gives no
    // ordering guarantee across separate reads), so onBranchIndexesLoaded() can be called
    // with an incomplete picture — e.g. branch A (genuinely empty today) reports before
    // branch B (which has runs) has reported at all, momentarily computing an empty
    // runTypes list and flashing "No Run" before branch B's data arrives a moment later
    // and corrects it. ccReportedBranchIds tracks which branches have completed Phase 1
    // discovery at least once; only once every branch in ccExpectedBranchCount has
    // reported is an empty result trusted enough to show the empty state. Does not affect
    // how or when data is fetched or rendered once available — only gates the specific
    // "conclude there's nothing" decision during the initial load window.
    private var ccExpectedBranchCount = 0
    private val ccReportedBranchIds = mutableSetOf<String>()
    // Same race, one layer deeper: once run-TYPES are known (the gate above passes), the
    // ACTUAL consignments for those types come from per-(branch,runType) Phase 2 range
    // queries, each firing independently. typesToWatch can go non-empty the moment ANY one
    // branch's Phase 1 finds run-types, well before every attached Phase 2 listener (for
    // that branch's OTHER run-types, or a slower branch's own Phase 1+Phase 2) has reported
    // — so candidateKeys can look empty and flash "No consignment" the same way runTypes
    // could flash "No Run". ccExpectedPhase2Keys records "$branchId/$runType" the moment
    // each Phase 2 listener is attached; only once ccBranchRangeSnapshots has an entry for
    // every key in it (regardless of whether that entry's snapshot has children) is an
    // empty candidateKeys trusted.
    private val ccExpectedPhase2Keys = mutableSetOf<String>()

    private fun detachRunsListener() {
        ccActiveListeners.forEach { (ref, l) -> ref.removeEventListener(l) }
        ccActiveListeners.clear()
        detachCcEngagedAtListeners()
        ccRunNodeSnapshots.clear()
        ccRunKeyBranchIds.clear()
        ccAttachedRunKeys.clear()
        // Range-query results are additive across live-fires within one loadData() cycle
        // (each branch/runType's listener only ever updates its own key) — but a NEW cycle
        // (e.g. triggered by an agent-filter change) must start clean, or stale entries from
        // before the filter changed would still be merged into the candidate set.
        ccBranchRangeSnapshots.clear()

        ccRealtimeJob?.cancel()
        ccRealtimeJob = null
    }

    private fun syncCcEngagedAtListeners(currentIds: Set<String>) {
        val stale = ccEngagedAtListeners.keys - currentIds
        stale.forEach { id ->
            ccEngagedAtListeners.remove(id)?.let { (ref, listener) -> ref.removeEventListener(listener) }
        }

        currentIds.forEach { id ->
            if (ccEngagedAtListeners.containsKey(id)) return@forEach
            val ref = com.google.firebase.database.FirebaseDatabase.getInstance()
                .reference.child(EngagedStateManager.nodePath(id))
            val listener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (!isAdded) return@launch
                        val agents = withContext(Dispatchers.IO) {
                            EngagedStateManager.parseEngagedAgents(snapshot)
                        }
                        replaceCcEngagedAgents(id, agents)
                    }
                }

                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                    android.util.Log.w("CallCenterFragment", "engaged_at listener cancelled for $id: ${error.message}")
                }
            }
            ref.addValueEventListener(listener)
            ccEngagedAtListeners[id] = ref to listener
        }
    }

    private fun detachCcEngagedAtListeners() {
        ccEngagedAtListeners.values.forEach { (ref, listener) -> ref.removeEventListener(listener) }
        ccEngagedAtListeners.clear()
    }

    private fun replaceCcEngagedAgents(consignmentId: String, agents: List<EngagedAgent>) {
        allParcels = allParcels.map { item ->
            if (item.id == consignmentId) item.copy(engagedAgents = agents) else item
        }
        applyFilters()
    }

    private fun applyLocalCcEngagement(consignmentIds: Set<String>, agent: EngagedAgent) {
        if (agent.uid.isBlank() || consignmentIds.isEmpty()) return
        allParcels = allParcels.map { item ->
            if (item.id !in consignmentIds) item else item.copy(
                engagedAgents = item.engagedAgents.filterNot { it.uid == agent.uid } + agent
            )
        }
        applyFilters()
    }

    private fun removeLocalCcEngagement(consignmentIds: Set<String>, uid: String) {
        if (uid.isBlank() || consignmentIds.isEmpty()) return
        allParcels = allParcels.map { item ->
            if (item.id !in consignmentIds) item else item.copy(
                engagedAgents = item.engagedAgents.filterNot { it.uid == uid }
            )
        }
        applyFilters()
    }

    /**
     * Fetches the systemId -> name map once (cached in systemIdToName), reused across every
     * subsequent listener trigger until pull-to-refresh clears the cache. Uses the
     * users_by_systemId reverse-index for O(1) targeted lookups instead of scanning
     * the full users/ tree, then resolves names for those uids in parallel.
     */
    private suspend fun ensureAgentNameMap(): Map<String, String> {
        if (systemIdToName.isNotEmpty()) return systemIdToName
        return try {
            val db = com.google.firebase.database.FirebaseDatabase.getInstance()
            // Step 1: systemId → uid from reverse-index (single node read)
            val indexSnap = withContext(Dispatchers.IO) {
                db.reference.child("users_by_systemId").get().await()
            }
            val sysIdToUid = mutableMapOf<String, String>()
            indexSnap.children.forEach { child ->
                val sysId = child.key?.trim()
                val uid   = child.child("uid").getValue(String::class.java)?.trim()
                if (!sysId.isNullOrBlank() && !uid.isNullOrBlank()) sysIdToUid[sysId] = uid
            }
            // Step 2: uid -> name + employee_id + photo, ONE read per uid (all concurrent
            // across uids). Was 3 sequential reads per uid (name, employee_id, photo_url each
            // its own round-trip) — same fix as WorkerSpaceFragment.loadData() already applies
            // for its own agentPhone lookup: read the whole profile node once, pull every
            // field off that single snapshot instead of a separate fetch per field.
            data class AgentData(val sysId: String, val name: String?, val empId: String?, val photoUrl: String?, val phone: String?)
            val results = coroutineScope {
                sysIdToUid.map { (sysId, uid) ->
                    async(Dispatchers.IO) {
                        val profileSnap = runCatching {
                            db.reference.child("users/$uid/profile").get().await()
                        }.getOrNull()
                        val name     = profileSnap?.child("name")?.getValue(String::class.java)?.trim()
                        val empId    = profileSnap?.child("company_info/employee_id")?.getValue(String::class.java)?.trim()
                        val photoUrl = profileSnap?.child("photo_url")?.getValue(String::class.java)?.trim()
                        val phone    = profileSnap?.child("phone")?.getValue(String::class.java)?.trim()
                        AgentData(sysId, name, empId, photoUrl, phone)
                    }
                }.awaitAll()
            }
            val nameMap  = results.filter { !it.name.isNullOrBlank()  }.associate { it.sysId to it.name!! }
            val empIdMap = results.filter { !it.empId.isNullOrBlank() }.associate { it.sysId to it.empId!! }
            val photoMap = results.filter { !it.photoUrl.isNullOrBlank() }.associate { it.sysId to it.photoUrl!! }
            val phoneMap = results.filter { !it.phone.isNullOrBlank() }.associate { it.sysId to it.phone!! }
            systemIdToName       = nameMap
            systemIdToEmployeeId = empIdMap
            systemIdToPhotoUrl   = photoMap
            systemIdToPhone      = phoneMap
            nameMap
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Rebuilds the full parcel list from ccRunNodeSnapshots (every currently-attached run
     *  node's latest snapshot) — called whenever any one of those run nodes changes. Today/branch
     *  filtering already happened upstream when candidates were selected, so every cached run
     *  here is guaranteed relevant. */
    private suspend fun reprocessAllCachedRuns() {
        // Perf/correctness guard — see the debounce comment on reprocessJob above. Even with
        // debouncing, a manual pull-to-refresh (or another trigger) could still overlap an
        // in-flight reprocess; this generation check (same idea as WorkerSpaceFragment's
        // loadGeneration) makes sure only the NEWEST call's results ever get applied below,
        // so a slower-finishing older call can't clobber fresher data with stale results.
        val generation = ++ccLoadGeneration
        // Do not let an older fallback fetch update cards while this fresh batch is rebuilding
        // the list. syncCcRemarkListeners() restores the IDs after the new list is applied.
        ccRemarkTrackedIds = emptySet()
        val db = com.google.firebase.database.FirebaseDatabase.getInstance()

        // Collect consignment ids + statuses + which agent's run + which branch they came from.
        // agentSystemId and resolvedBranchIds are flat fields on the run node (written by
        // ConfigSheetFragment's sync) — reading them here costs nothing extra.
        data class CcConsignmentInfo(
            val agentSystemId: String,
            val routeStatus: String,
            val branchIds: List<String>
        )
        val consignmentInfo = mutableMapOf<String, CcConsignmentInfo>()
        ccRunNodeSnapshots.forEach { (runKey, runSnap) ->
            val agentSystemId = runSnap.child("agentSystemId").getValue(String::class.java)?.trim().orEmpty()
            val indexedBranchIds = ccRunKeyBranchIds[runKey].orEmpty()
            val resolvedBranchIds = runSnap.child("resolvedBranchIds").children
                .mapNotNull { it.getValue(String::class.java)?.trim()?.takeIf { id -> id.isNotBlank() } }
            val scopedBranchIds = (indexedBranchIds + resolvedBranchIds).distinct()
            runSnap.child("consignments").children.forEach { c ->
                val cId     = c.key ?: return@forEach
                val cStatus = c.getValue(String::class.java) ?: "pending"
                consignmentInfo[cId] = CcConsignmentInfo(agentSystemId, cStatus, scopedBranchIds)
            }
        }

        if (consignmentInfo.isEmpty()) {
            if (!isAdded || generation != ccLoadGeneration) return
            pbProgress.visibility = View.GONE
            tvLoadingPercent.visibility = View.GONE
            tvEmpty.visibility    = View.VISIBLE
            tvEmpty.text          = "📭\n\nআজকের কোনো consignment নেই"
            return
        }

        // Fetch only today's rows once for card badges/statuses. Full history is lazy-loaded
        // when the journey dialog is opened, avoiding one Edge Function request per parcel.
        val todayStartForBadges = bangladeshTodayStartMillis()
        val todayBatchRequestedAtMs = System.currentTimeMillis()
        val todayRemarkRows = run {
            val deferred = CompletableDeferred<List<org.json.JSONObject>>()
            SupabaseRemarkValidationWriter.fetchNewRemarksSince(
                consignmentInfo.keys.toList(), todayStartForBadges, "CallCenterFragment"
            ) { rows -> deferred.complete(rows) }
            deferred.await()
        }
        val todayRowsByConsignment = todayRemarkRows.groupBy { it.optString("consignment") }
        // The initial batch is the baseline. Later push-triggered fallback fetches ask only
        // for rows written after this point, rather than fetching all historical rows again.
        ccRemarkFallbackCursorMs = todayBatchRequestedAtMs

        // Parallel fetch consignment details (remark history is intentionally omitted here).
        val parcels = coroutineScope {
            // nameMap is only consulted when building each item/history entry below, never
            // needed to START any of the per-consignment reads — so fire it concurrently with
            // those instead of blocking them behind a full name-resolution round-trip first.
            // (ensureAgentNameMap() self-caches, so this is a cheap instant .await() on the
            // common warm-cache path where attachRootRunTypesListener()'s early fire-and-forget
            // call already finished.)
            val nameMapDeferred = async { ensureAgentNameMap() }

            val fetches = consignmentInfo.entries.map { entry ->
                val cId = entry.key
                val info = entry.value
                val agentSystemId = info.agentSystemId
                val runStatus = info.routeStatus
                async(Dispatchers.IO) {
                    try {
                        // Fire both independent reads together instead of sequentially —
                        // remarkRows only needs cId (known up-front), not any field from
                        // snap. snap is awaited first since the early-exists-check and every
                        // field below depends on it; remarkRows is awaited later, right where
                        // it's first used — by then it's essentially always already done.
                        val snapDeferred = async(Dispatchers.IO) {
                            db.reference.child("courier/consignments/$cId").get().await()
                        }
                        val engagedAtSnapDeferred = async(Dispatchers.IO) {
                            db.reference.child(EngagedStateManager.nodePath(cId)).get().await()
                        }
                        val snap = snapDeferred.await()
                        if (!snap.exists()) return@async null

                        val name    = snap.child("recipientName").getValue(String::class.java) ?: ""
                        val phone   = snap.child("recipientPhone").getValue(String::class.java) ?: ""
                        val address = snap.child("recipientAddress").getValue(String::class.java) ?: ""
                        val cod     = snap.child("collectableAmount").getValue(String::class.java)
                            ?.toDoubleOrNull()?.toInt()
                            ?: snap.child("collectableAmount").getValue(Long::class.java)?.toInt() ?: 0
                        // Prefer the branch index/resolved branch scope. For a multi-branch run,
                        // choose the currently selected branch for display while retaining all
                        // IDs in branchIds so filtering can match every valid branch.
                        val fallbackHub = snap.child("deliveryHub").getValue(String::class.java)?.trim().orEmpty()
                        val scopedBranchIds = info.branchIds.ifEmpty {
                            listOf(fallbackHub).filter { it.isNotBlank() }
                        }
                        val hub = selectedBranchIds.firstOrNull { it in scopedBranchIds }
                            ?: scopedBranchIds.firstOrNull().orEmpty()
                        // Resolve to a display name (not the raw id) — reuses/feeds the same
                        // branchIdToName cache the branch-filter dropdown uses, and self-heals
                        // (fetches + caches on demand) instead of depending on that dropdown's
                        // own async population having already finished.
                        val hubName = when {
                            hub.isBlank() -> hub
                            branchIdToName.containsKey(hub) -> branchIdToName[hub] ?: hub
                            else -> {
                                val resolved = runCatching {
                                    db.reference.child("branches/$hub/name").get().await()
                                        .getValue(String::class.java)
                                }.getOrNull()?.takeIf { it.isNotBlank() } ?: hub
                                branchIdToName[hub] = resolved
                                resolved
                            }
                        }
                        val status  = snap.child("status").getValue(String::class.java) ?: runStatus
                        val createdAtVal = snap.child("createdAt").getValue(Long::class.java) ?: 0L
                        val updatedAtVal = snap.child("updatedAt").getValue(Long::class.java) ?: 0L
                        val attemptVal = readCcAttempt(snap)

                        // Only today's rows are needed for the initial card. Full history is
                        // fetched lazily by showActionHistoryDialog().
                        val remarkRows = todayRowsByConsignment[cId].orEmpty()

                        // Card badge (overview) — TODAY's TRUE latest remark, any author.
                        // remark_validations rows are already newest-first (fetchHistory orders
                        // by created_at.desc), so "today's latest" is just the first row whose
                        // created_at falls on or after today's midnight.
                        //
                        // This same today-scoped entry ALSO drives remarkStatus/
                        // effectiveStatus/validationRequest below — a remark from a previous
                        // day must not keep overriding today's status/validation just because
                        // no one has left a newer remark since. Each day is effectively a
                        // fresh attempt (new run_id), so a stale prior-day remark shouldn't
                        // silently linger into today's state.
                        fun rowCreatedAtMillis(row: org.json.JSONObject): Long =
                            SupabaseRemarkValidationWriter.parseCreatedAtMillis(row.optString("created_at"))
                        val latestTodayEntry = remarkRows.firstOrNull { rowCreatedAtMillis(it) >= todayStartForBadges }
                        val remarkStatus = latestTodayEntry?.optString("remarks_status")?.trim().orEmpty()
                        // Supabase rows don't carry a "who wrote this" role label the way
                        // Firebase's remarked_by ("support" vs a worker uid) did — authorSystemId
                        // is always a system_id now, for either a CC agent or a worker, with no
                        // clean "is this row from support" check left to make. The old behavior
                        // (hide the badge text when the CC agent's own remark is the latest) is
                        // dropped: the badge always shows the latest remark's text now,
                        // regardless of who wrote it.
                        val entryRemarksText = latestTodayEntry?.let { row ->
                            listOf(resolveRemarkBn(row.optString("remarks").trim()), row.optString("note").trim())
                                .filter { it.isNotBlank() }.joinToString("\n")
                        }.orEmpty()
                        val remarkLabelNote = entryRemarksText
                        val validationNoteText = remarkLabelNote
                        val remarkLabel = remarkLabelNote

                        val nameMap = nameMapDeferred.await()
                        Triple(
                            CallCenterParcelItem(
                                id                = cId,
                                customer          = name,
                                phone             = phone,
                                address           = address,
                                cod               = cod,
                                status            = status,
                                remarks           = remarkLabel,
                                remarkStatus      = remarkStatus,
                                validationRequest = isVerifyRequestStatus(remarkStatus),
                                validationNote    = if (isVerifyRequestStatus(remarkStatus)) validationNoteText else "",
                                time              = "",
                                worker            = nameMap[agentSystemId] ?: agentSystemId,
                                workerSystemId    = agentSystemId,
                                workerPhotoUrl    = systemIdToPhotoUrl[agentSystemId] ?: "",
                                workerPhone       = systemIdToPhone[agentSystemId] ?: "",
                                branch            = hubName,
                                branchIds         = scopedBranchIds,
                                remarksAt         = latestTodayEntry?.let { rowCreatedAtMillis(it) } ?: 0L,
                                createdAt         = createdAtVal,
                                updatedAt         = updatedAtVal,
                                engagedAgents     = EngagedStateManager.parseEngagedAgents(engagedAtSnapDeferred.await()),
                                attemptCount      = attemptVal
                            ),
                            remarkRows,
                            agentSystemId
                        )
                    } catch (e: Exception) { null }
                }
            }.mapNotNull { it.await() }

            // `remarkRows` intentionally stays out of CallCenterParcelItem.history. The
            // complete journey is fetched only from showActionHistoryDialog() on demand.
            fetches.map { (item, _, _) -> item }
        }

        if (!isAdded || generation != ccLoadGeneration) return
        allParcels = parcels.sortedBy { it.id }
        // Branch chips reflect the CC agent's OWN assignment (RbacManager), not whatever
        // branches happen to show up in the fetched parcels — Karim (Sonargaon only) never
        // sees a "Bandar" chip even if a stray legacy parcel's deliveryHub said otherwise.
        branches = myBranchIds
        setupBranchDropdown()
        setupFilterTabs()
        applyFilters()
        pbProgress.visibility = View.GONE
        tvLoadingPercent.visibility = View.GONE
        syncCcRemarkListeners(allParcels.map { it.id }.toSet())
        syncCcEngagedAtListeners(allParcels.map { it.id }.toSet())
    }

    private var ccRemarkTrackedIds: Set<String> = emptySet()

    /**
     * Starts a Supabase Realtime subscription for this branch.
     * INSERT events on validations arrive instantly via WebSocket — no polling,
     * no Edge Function invocation consumed.
     * Previous job is cancelled before starting a new one.
     */
    private fun syncCcRemarkListeners(currentIds: Set<String>) {
        ccRemarkTrackedIds = currentIds
        val branchId = RbacManager.current.branchIds.firstOrNull() ?: return
        val channelKey = "cc_branch_$branchId"
        if (ccRealtimeChannelKey == channelKey && ccRealtimeJob != null) return
        ccRealtimeJob?.cancel()
        ccRealtimeChannelKey = channelKey
        ccRealtimeJob = SupabaseRealtimeManager.subscribeValidations(
            channelKey = channelKey,
            filter     = "branch_id" to branchId,
            scope      = viewLifecycleOwner.lifecycleScope,
        ) { row ->
            val cId = row.optString("consignment")
            if (cId.isBlank() || cId !in ccRemarkTrackedIds) return@subscribeValidations
            viewLifecycleOwner.lifecycleScope.launch {
                if (isAdded) refreshOneCcParcelFromSupabase(cId, row)
            }
        }
    }

    /** Event-triggered REST fallback only; never scheduled on an interval. */
    private fun fetchNewCcRemarksFromPush() {
        if (!isAdded) return
        val ids = ccRemarkTrackedIds
        if (ids.isEmpty()) return
        val requestedAtMs = System.currentTimeMillis()
        val cursorMs = ccRemarkFallbackCursorMs.takeIf { it > 0L } ?: requestedAtMs
        SupabaseRemarkValidationWriter.fetchNewRemarksSince(ids.toList(), cursorMs, "CallCenterFragment") { rows ->
            ccRemarkFallbackCursorMs = requestedAtMs
            if (rows.isEmpty()) return@fetchNewRemarksSince
            val latestByConsignment = rows.groupBy { it.optString("consignment") }
                .mapValues { (_, g) -> g.maxByOrNull { SupabaseRemarkValidationWriter.parseCreatedAtMillis(it.optString("created_at")) } }
            viewLifecycleOwner.lifecycleScope.launch {
                if (!isAdded) return@launch
                latestByConsignment.forEach { (cId, latest) ->
                    if (!cId.isNullOrBlank() && latest != null) refreshOneCcParcelFromSupabase(cId, latest)
                }
            }
        }
    }

    /**
     * FCM only identifies the changed consignment; it intentionally does not put a remark's
     * contents into the push payload. Fetch only on receipt, then reuse the same
     * card-update path as Realtime. This keeps the card text, effective-status filters and
     * summary counts in sync without reloading every parcel.
     */
    private fun refreshCcParcelFromPush(consignmentId: String) {
        // Trigger a one-off REST fetch only when a push arrives. This does not invoke the
        // Edge Function and is not scheduled periodically.
        if (!isAdded || allParcels.none { it.id == consignmentId }) return
        fetchNewCcRemarksFromPush()
    }

    /** Updates one card directly from a Supabase validation row. */
    private fun refreshOneCcParcelFromSupabase(cId: String, latestRemarkRow: org.json.JSONObject) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!isAdded) return@launch
            val idx = allParcels.indexOfFirst { it.id == cId }
            if (idx != -1) {
                val oldStatus = allParcels[idx].effectiveStatus
                val createdAt = SupabaseRemarkValidationWriter
                    .parseCreatedAtMillis(latestRemarkRow.optString("created_at"))
                val liveRemarkStatus = latestRemarkRow.optString("remarks_status").trim()
                val latestRemark = listOf(
                    resolveRemarkBn(latestRemarkRow.optString("remarks").trim()), latestRemarkRow.optString("note").trim()
                ).filter { it.isNotBlank() }.joinToString("\n")
                allParcels = allParcels.toMutableList().also {
                    it[idx] = it[idx].copy(
                        remarks = latestRemark,
                        remarkStatus = liveRemarkStatus,
                        validationRequest = isVerifyRequestStatus(liveRemarkStatus),
                        validationNote = if (isVerifyRequestStatus(liveRemarkStatus)) latestRemark else "",
                        remarksAt = createdAt
                    )
                }
                val newStatus = allParcels[idx].effectiveStatus
                if (oldStatus != newStatus) {
                    setupFilterTabs()
                }
                applyFilters()
            }
        }
    }

    /** The operations day follows Bangladesh time regardless of the phone's configured zone. */
    private fun bangladeshTodayStartMillis(): Long = java.time.LocalDate
        .now(java.time.ZoneId.of("Asia/Dhaka"))
        .atStartOfDay(java.time.ZoneId.of("Asia/Dhaka"))
        .toInstant()
        .toEpochMilli()

    /**
     * Loads Call Center remark options for the "Set Remarks" sheet from config/remarks,
     * respecting config/language/ccLang for which language to show remark text vs status
     * label in (independent of workerLang — see ConfigLanguageFragment).
     */
    private fun loadCcRemarkOptions() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                StatusMetaCache.refresh()

                val langValue = withContext(Dispatchers.IO) {
                    com.google.firebase.database.FirebaseDatabase.getInstance().reference
                        .child("config/language/ccLang").get().await()
                        .getValue(String::class.java)
                }?.trim().orEmpty().ifBlank { "bn_en" }
                val (remarkLang, statusLang) = parseLangPair(langValue)
                ccStatusLang = statusLang

                val remarksSnap = withContext(Dispatchers.IO) {
                    com.google.firebase.database.FirebaseDatabase.getInstance().reference
                        .child("config/remarks_call_center").get().await()
                }
                val templatesSnap = withContext(Dispatchers.IO) {
                    com.google.firebase.database.FirebaseDatabase.getInstance().reference
                        .child("config/whatsappTemplates").get().await()
                }
                val loadedTemplates = mutableMapOf<String, ConfigState.WhatsAppTemplate>()
                templatesSnap.children.forEach { t ->
                    val tid  = t.key ?: return@forEach
                    val name = t.child("name").getValue(String::class.java) ?: ""
                    val body = t.child("body").getValue(String::class.java) ?: ""
                    loadedTemplates[tid] = ConfigState.WhatsAppTemplate(tid, name, body)
                }
                whatsappTemplatesCache = loadedTemplates
                data class FetchedCcRemark(val option: CcRemarkOption, val priority: Int)
                val fetched = mutableListOf<FetchedCcRemark>()
                remarksSnap.children.forEach { groupSnap ->
                    groupSnap.children.forEach { r ->
                        val textBn = r.child("text_bn").getValue(String::class.java)?.trim().orEmpty()
                        val textEn = r.child("text_en").getValue(String::class.java)?.trim().orEmpty()
                        val label = (if (remarkLang == "en") textEn.ifBlank { textBn } else textBn.ifBlank { textEn })
                        val englishLabel = textEn.ifBlank { textBn }
                        if (label.isBlank()) return@forEach
                        val target = r.child("target_status").getValue(String::class.java)?.trim()
                            .orEmpty().ifBlank { groupSnap.key ?: return@forEach }
                        val templateId = r.child("template_id").getValue(String::class.java)?.trim().orEmpty()
                        val priority = r.child("priority").getValue(Int::class.java) ?: 0
                        val metaEntry = StatusMetaCache.entries[target]
                        val preview = StatusMetaCache.labelOrNull(target, statusLang) ?: target
                        fetched.add(FetchedCcRemark(
                            CcRemarkOption(
                                icon = "💬",
                                label = label,
                                englishLabel = englishLabel,
                                statusKey = target,
                                statusPreview = preview,
                                statusColor = metaEntry?.color ?: android.graphics.Color.GRAY,
                                templateId = templateId
                            ),
                            priority
                        ))
                    }
                }

                if (isAdded) {
                    ccRemarkOptions = fetched.sortedByDescending { it.priority }.map { it.option }
                    if (::adapter.isInitialized) {
                        adapter.statusLang = ccStatusLang
                        adapter.notifyDataSetChanged()
                    }
                    setupFilterTabs()
                }
            } catch (e: Exception) {
                android.util.Log.e("CallCenter", "Failed to load remark options from config, using defaults", e)
            }
        }
    }

    /**
     * Resolves a stored English remark text back to its display label for the CC card badge.
     * Supabase stores englishLabel for reporting; the card shows the language-configured label.
     * Falls back to raw unchanged for free-text notes or unmatched entries.
     */
    private fun resolveRemarkBn(raw: String): String {
        if (raw.isBlank()) return raw
        return ccRemarkOptions.find { it.englishLabel == raw }?.label ?: raw
    }

    private fun showRemarksDialog(item: CallCenterParcelItem) {
        val dialog = BottomSheetDialog(requireContext())
        val view   = layoutInflater.inflate(R.layout.bottom_sheet_remarks, null)

        val tvTitle      = view.findViewById<TextView>(R.id.tvRemarksTitle)
        val etRemarks    = view.findViewById<EditText>(R.id.etRemarksText)
        val tvAutoStatus = view.findViewById<TextView>(R.id.tvRemarksAutoStatus)
        val layoutOptions = view.findViewById<android.widget.LinearLayout>(R.id.layoutCcRemarkOptions)
        val btnCancel    = view.findViewById<TextView>(R.id.btnRemarksCancel)
        val btnSave      = view.findViewById<TextView>(R.id.btnRemarksSave)

        tvTitle.text = "${item.customer} · ${item.id} · ${item.phone}"

        // ── CC Remark options with auto-status (loaded from config/remarks) ─────
        val options = ccRemarkOptions

        if (options.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "⚠ Config-এ কোনো remark সেট করা নেই।\nAdmin-কে config/remarks_call_center-এ remark যোগ করতে বলুন।"
            tv.textSize = 13f
            tv.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
            tv.setPadding(0, 24, 0, 24)
            layoutOptions.addView(tv)
            dialog.show()
            return
        }

        var selectedStatus      = ""
        var selectedRemarkText  = "" // Display text in the configured CC language.
        var selectedStoredRemarkText = "" // Canonical English text for reporting.
        var selectedTemplateId  = ""
        val optionViews         = mutableListOf<android.view.View>()

        // Enabled once EITHER a remark option is picked OR the note has text —
        // a note alone (no predefined remark) must still be saveable.
        btnSave.isEnabled = false
        btnSave.alpha     = 0.5f

        fun refreshSaveEnabled() {
            val hasNote = etRemarks.text?.toString()?.trim().orEmpty().isNotBlank()
            val enabled = selectedStatus.isNotBlank() || hasNote
            btnSave.isEnabled = enabled
            btnSave.alpha     = if (enabled) 1f else 0.5f
        }

        etRemarks.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { refreshSaveEnabled() }
        })

        for (opt in options) {
            val optView = layoutInflater.inflate(R.layout.item_worker_remark_option, layoutOptions, false)
            val tvIcon  = optView.findViewById<TextView>(R.id.twRemarkOptIcon)
            val tvText  = optView.findViewById<TextView>(R.id.twRemarkOptText)
            val tvTag   = optView.findViewById<TextView>(R.id.twRemarkOptAutoTag)
            val dot     = optView.findViewById<android.view.View>(R.id.viewRemarkOptSelected)

            tvIcon.text = opt.icon
            tvText.text = opt.label
            tvTag.text  = "→${opt.statusPreview.uppercase()}"
            tvTag.visibility = android.view.View.VISIBLE

            optView.setOnClickListener {
                // Reset all options
                optionViews.forEach { v ->
                    v.setBackgroundResource(R.drawable.bg_remark_opt_inactive)
                    v.findViewById<TextView>(R.id.twRemarkOptText)
                        .setTextColor(requireContext().getColor(R.color.theme_text_remark_opt))
                    v.findViewById<android.view.View>(R.id.viewRemarkOptSelected).visibility = android.view.View.GONE
                }
                // Highlight selected
                optView.setBackgroundResource(R.drawable.bg_remark_opt_active)
                tvText.setTextColor(requireContext().getColor(R.color.theme_text_remark_opt_selected))
                dot.visibility = android.view.View.VISIBLE

                // Update auto-status preview
                selectedStatus     = opt.statusKey
                selectedRemarkText = opt.label
                selectedStoredRemarkText = opt.englishLabel
                selectedTemplateId = opt.templateId
                tvAutoStatus.text  = opt.statusPreview
                tvAutoStatus.setTextColor(opt.statusColor)

                refreshSaveEnabled()
            }

            optionViews.add(optView)
            layoutOptions.addView(optView)
        }

        btnSave.setOnClickListener {
            val noteText = etRemarks.text?.toString()?.trim() ?: ""
            if (selectedStatus.isBlank() && noteText.isBlank()) return@setOnClickListener

            // Find other parcels in the current list with the same phone number — same
            // normalization + confirm-dialog pattern as
            // WorkerSpaceFragment.showWorkerRemarksDialog().
            val normalizedPhone = item.phone.filter { it.isDigit() }.takeLast(10)
            val samePhoneParcels = allParcels.filter { p ->
                p.id != item.id &&
                p.phone.filter { it.isDigit() }.takeLast(10) == normalizedPhone
            }

            if (samePhoneParcels.isNotEmpty()) {
                // Ask the agent whether to apply the same remark to all parcels of this
                // customer. Dismiss the remarks sheet first so both dialogs don't stack.
                dialog.dismiss()
                val total = samePhoneParcels.size + 1
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("একই Customer — $total টি Parcel")
                    .setMessage(
                        "\"${item.customer}\" (${item.phone}) এর মোট $total টি parcel আছে।\n\n" +
                        "সবগুলোতে একই remark দিতে চান?\n\n" +
                        "• Yes — $total টি parcel এ save হবে\n" +
                        "• No — শুধু ${item.id} তে save হবে"
                    )
                    .setPositiveButton("Yes, সবগুলোতে") { _, _ ->
                        saveCcRemarkForItems(
                            items = listOf(item) + samePhoneParcels,
                            selectedStatus = selectedStatus,
                            selectedRemarkText = selectedRemarkText,
                            selectedStoredRemarkText = selectedStoredRemarkText,
                            noteText = noteText,
                            selectedTemplateId = selectedTemplateId,
                            triggerItem = item
                        )
                    }
                    .setNegativeButton("No, শুধু এটায়") { _, _ ->
                        saveCcRemarkForItems(
                            items = listOf(item),
                            selectedStatus = selectedStatus,
                            selectedRemarkText = selectedRemarkText,
                            selectedStoredRemarkText = selectedStoredRemarkText,
                            noteText = noteText,
                            selectedTemplateId = selectedTemplateId,
                            triggerItem = item
                        )
                    }
                    .show()
                return@setOnClickListener
            }

            // No siblings — save directly for the single parcel.
            saveCcRemarkForItems(
                items = listOf(item),
                selectedStatus = selectedStatus,
                selectedRemarkText = selectedRemarkText,
                selectedStoredRemarkText = selectedStoredRemarkText,
                noteText = noteText,
                selectedTemplateId = selectedTemplateId,
                triggerItem = item
            )
            dialog.dismiss()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.setContentView(view)
        // Same fix as WorkerSpaceFragment.showWorkerRemarksDialog(): cap the sheet's
        // height so the ScrollView (weight=1) has a bounded parent to size against.
        // Without this, a long remark-options list + the note field could push
        // Cancel/Save off-screen with no way to scroll down to them.
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        val rootSheet = view.findViewById<View>(R.id.rootCcRemarkSheet)
        rootSheet.post {
            val maxHeight = (resources.displayMetrics.heightPixels * 0.9).toInt()
            if (rootSheet.height > maxHeight || rootSheet.layoutParams.height != maxHeight) {
                rootSheet.layoutParams = rootSheet.layoutParams.apply { height = maxHeight }
                rootSheet.requestLayout()
            }
        }
        dialog.show()
    }

    /**
     * Saves the same remark for every parcel in [items] — same fan-out pattern as
     * WorkerSpaceFragment.saveRemarkForItems(). [triggerItem] is the one the agent
     * actually tapped; its WhatsApp template fires (if configured) — siblings share
     * the same phone number so we don't send the customer duplicate messages.
     */
    /** Auto-remark for a call that rang long enough (AUTO_NO_ANSWER_MIN_RING_SECONDS) but was
     *  never answered (CallLog duration == 0). Free-text note only, status left blank — same
     *  shape as the "no configured options" manual remark elsewhere. An agent can still add a
     *  proper status/remark afterward; this just makes sure the parcel doesn't silently look
     *  untouched after a real, unanswered call attempt. Tagged "auto": true for traceability. */
    private fun saveAutoNoAnswerRemark(item: CallCenterParcelItem) {
        val noteText = AUTO_NO_ANSWER_REMARK_TEXT

        SupabaseRemarkValidationWriter.write(
            assignedAgentSystemId = item.workerSystemId,
            branchId = item.branchIds.firstOrNull().orEmpty(),
            consignmentId = item.id,
            status = "",
            remarksText = "",
            noteText = noteText,
            source = "CC",
            screen = "CallCenterFragment"
        )

        allParcels = allParcels.map {
            if (it.id == item.id) it.copy(remarks = noteText) else it
        }
    }

    private fun saveCcRemarkForItems(
        items: List<CallCenterParcelItem>,
        selectedStatus: String,
        selectedRemarkText: String,
        selectedStoredRemarkText: String,
        noteText: String,
        selectedTemplateId: String,
        triggerItem: CallCenterParcelItem
    ) {
        if (selectedTemplateId.isNotBlank() && WhatsAppSender.isEnabled(requireContext())) {
            val template = whatsappTemplatesCache[selectedTemplateId]
            if (template != null && template.body.isNotBlank()) {
                val filledMessage = WhatsAppHelper.fillTemplate(
                    body = template.body,
                    name = triggerItem.customer,
                    phone = triggerItem.phone,
                    address = triggerItem.address,
                    cod = triggerItem.cod.toString(),
                    consignmentId = triggerItem.id,
                    hub = ""
                )
                WhatsAppHelper.send(requireContext(), triggerItem.phone, filledMessage)
            }
        }

        // Write to Firebase — remark and status are written as SEPARATE operations
        // (not one atomic multi-path update) so the remark always gets saved even if
        // Write to Supabase's remark_validations table — replaces the old
        // Firebase courier/remarks_by_consignment + remarks_by_userId +
        // users_by_consignment writes (see SupabaseRemarkValidationWriter's
        // doc comment). One INSERT per target, no read-before-write needed
        // since every remark is its own row.
        items.forEach { target ->
            SupabaseRemarkValidationWriter.write(
                assignedAgentSystemId = target.workerSystemId,
                branchId = target.branchIds.firstOrNull().orEmpty(),
                consignmentId = target.id,
                status = selectedStatus,
                remarksText = selectedStoredRemarkText,
                noteText = noteText,
                source = "CC",
                screen = "CallCenterFragment"
            )

            EngagedStateManager.clearEngaged(target.id, userId)
        }

        // Parcel status (courier/consignments/{id}/status) is a SEPARATE concept from
        // remark status and is NEVER written/changed from here — only the remark's own
        // "status" field above (already saved per-item as part of remarkData) represents this.
        val targetIds = items.map { it.id }.toSet()
        allParcels = allParcels.map {
            if (it.id in targetIds) it.copy(
                validationRequest = false,
                remarkStatus = selectedStatus,
                remarks = selectedRemarkText.ifBlank { noteText }
            ) else it
        }
        // Remark saved -> collapse the card it was set from. Deliberately separate from
        // applyFilters()'s own collapse check below (which only collapses a card that fell
        // out of the current filter, to avoid the flash-close bug an EngagedStateManager
        // echo used to cause) -- this only fires for the item(s) actually just saved.
        if (adapter.expandedItemId in targetIds) {
            adapter.expandedItemId = null
        }
        setupFilterTabs()
        applyFilters()
    }


    private fun applyFilters() {
        // Access mode / branch / agent — same scope the chips and stat summary use,
        // factored into scopedParcels() so the two can never drift apart.
        var filtered = scopedParcels()
        val modeHasPriority = "priority" in selectedAccessModes
        val modeHasAll = "all" in selectedAccessModes

        // Search filter — phone, ID, customer name, or COD amount
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            // Phone search also matches on digits-only, so "+8801878221454", "8801878221454",
            // and "01878221454" all find a phone stored in any of those formats -- the raw
            // .contains(q) below alone fails whenever the typed "+" isn't in the stored value.
            val qDigits = q.filter { it.isDigit() }
            filtered = filtered.filter {
                it.phone.contains(q) ||
                (qDigits.isNotEmpty() && it.phone.filter { c -> c.isDigit() }.contains(qDigits)) ||
                it.id.lowercase().contains(q) ||
                it.customer.lowercase().contains(q) ||
                it.cod.toString().contains(q)
            }
            tvSearchCount.visibility = View.VISIBLE
            tvSearchCount.text = if (filtered.isEmpty()) {
                "⚠ No results for \"$searchQuery\""
            } else {
                "${filtered.size} result${if (filtered.size > 1) "s" else ""} found"
            }
            tvSearchCount.setTextColor(
                if (filtered.isEmpty()) 0xFFef4444.toInt() else 0xFF64748b.toInt()
            )
        } else {
            tvSearchCount.visibility = View.GONE
        }

        // Status filter — dynamic exact match, remark status takes priority over parcel status
        filtered = if (statusFilter == "all") filtered
                   else filtered.filter { it.effectiveStatus == statusFilter }

        // When both Priority Queue and All Agents are selected, surface priority
        // (validation-requested) parcels first — stable sort keeps each group's
        // own existing relative order intact.
        if (modeHasPriority && modeHasAll) {
            filtered = filtered.sortedByDescending { it.validationRequest }
        }

        // Update stats — same mode+branch+agent scope as the chips (not the raw,
        // unfiltered allParcels), so these numbers stay consistent with what's
        // actually visible below rather than always showing the global totals.
        //
        // "confirmed"/"pending"/"rejected" here used to be literal string comparisons
        // against effectiveStatus, always 0 in practice: statuses in this app are
        // admin-configured (config/statusMeta), not a fixed set, so a hardcoded literal
        // essentially never matches real data -- the exact bug StatusMetaCache.kt
        // documents already having been fixed once for validationRequest itself
        // ("VERIFY_REQUEST" vs a hardcoded "verify_req"). These two now read
        // validationRequest directly (Total Request) and whether a remark exists at all
        // (Total Served) instead of guessing at another literal status key.
        val scoped = scopedParcels()
        val total = scoped.size
        val confirmed = scoped.count { it.validationRequest }
        val pending = scoped.count { it.remarks.isNotBlank() }
        val rejected = scoped.count { it.effectiveStatus == "rejected" }
        val validationCount = scoped.count { it.validationRequest }

        tvStatTotal.text = total.toString()
        tvStatConfirmed.text = confirmed.toString()
        tvStatPending.text = pending.toString()
        tvStatRejected.text = rejected.toString()
        tvValidationCount.text = "$validationCount pending"

        // Render list — DiffUtil computes the minimal set of changes, so the
        // RecyclerView only rebinds/animates rows that actually changed.
        val targetId = pendingExpandParcelId
        if (targetId != null) {
            // Don't collapse — set expansion to the notification target before submit
            adapter.expandedItemId = targetId
            pendingExpandParcelId = null
        } else if (autoCallJob?.isActive == true && adapter.expandedItemId != null) {
            // Auto-call running — keep the expanded card's drawer open for remarks entry.
        } else if (adapter.expandedItemId != null && filtered.none { it.id == adapter.expandedItemId }) {
            // Whatever was expanded fell out of the current filter/tab — nothing to show it
            // against, so collapse it. Otherwise leave adapter.expandedItemId alone: this
            // used to unconditionally collapseExpanded() on every refresh, which meant a
            // normal tap-to-expand got wiped out almost immediately by the very
            // EngagedStateManager.markEngaged() write that same tap triggers (see onExpand
            // in setupAdapter()) echoing back through this fragment's live parcels listener —
            // the Call/Remarks buttons would flash open then snap shut.
            adapter.collapseExpanded()
        }
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        // Split-assignment conflicts: same customer, parcels assigned to different agents.
        // Computed against allParcels (not filtered) so a conflict still shows even if one
        // of the conflicting siblings is filtered out of the current view/tab.
        adapter.conflictedPhones = allParcels
            .groupBy { it.phone.normalizedPhone() }
            .filterValues { group -> group.mapNotNull { it.workerSystemId.ifBlank { null } }.distinct().size > 1 }
            .keys

        adapter.submitParcels(filtered)

        // Scroll to the expanded parcel (post so RecyclerView has measured the new items)
        if (targetId != null) {
            val idx = filtered.indexOfFirst { it.id == targetId }
            if (idx >= 0) rvParcelList.post { rvParcelList.smoothScrollToPosition(idx) }
        }
    }

    /** Today's date as yyyyMMdd (e.g. "20260725") — year-first so plain string/key ordering
     *  sorts chronologically. Used for runId construction and the courier/remarks_by_userId secondary
     *  index key — both now share this one format, so one helper covers both. */
    private fun todayDateKeyYyyyMmDd(): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ENGLISH).format(java.util.Date())

    /**
     * Extracts the date portion from a run ID of the form "run_{yyyyMMdd}_{employeeId}"
     * (yyyyMMdd is always exactly 8 zero-padded digits: 4-digit year, month, day — employeeId
     * comes after and may itself contain underscores). Returns local midnight (00:00:00)
     * millis for that date, or null if the ID doesn't match the expected shape.
     */
    private fun parseRunTimestamp(runId: String): Long? {
        val match = RUN_ID_PATTERN.matchEntire(runId.trim()) ?: return null
        val yyyymmdd = match.groupValues[1]
        val year  = yyyymmdd.substring(0, 4).toIntOrNull() ?: return null
        val month = yyyymmdd.substring(4, 6).toIntOrNull() ?: return null
        val day   = yyyymmdd.substring(6, 8).toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        return try {
            java.util.Calendar.getInstance().apply {
                clear()
                set(year, month - 1, day, 0, 0, 0)
            }.timeInMillis
        } catch (e: Exception) { null }
    }

    data class FilterTab(val key: String, val label: String)

    data class CcRemarkOption(
        val icon: String,
        val label: String,
        val englishLabel: String,
        val statusKey: String,
        val statusPreview: String,
        val statusColor: Int,
        val templateId: String = ""
    )

    // Loaded from config/remarks (+ config/language/ccLang) — see loadCcRemarkOptions().
    // Falls back to this small built-in set if config hasn't loaded yet or is empty.
    private var whatsappTemplatesCache: Map<String, ConfigState.WhatsAppTemplate> = emptyMap()
    private var ccRemarkOptions: List<CcRemarkOption> = emptyList()
    private var ccStatusLang: String = "bn"

    companion object {
        /** Sentinel stored in selectedAgentFilters when the user explicitly selects NO agents
         *  (Clear All + Apply). Distinct from the empty-set state, which means "show all agents". */
        private const val NO_AGENT_SENTINEL = "__no_agent__"

        /** Poll interval for the new-remark badge refresh. One minute keeps the free-tier
         *  request volume modest while FCM remains the immediate notification channel. */
    }
}
