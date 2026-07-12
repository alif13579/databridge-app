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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

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
    private lateinit var tvEmpty: TextView
    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var switchAutoCall: Switch
    private lateinit var btnAutoCallStartPause: android.widget.Button
    private lateinit var btnAutoCallGapMenu: TextView

    // Auto Call (sequential dialer) state
    private var autoCallGapSeconds = 8
    private var autoCallJob: Job? = null

    // ── Auto Call filter preference ──────────────────────────────────
    // "status" = only cards whose status is in autoCallStatuses go into the queue.
    // "aging"  = ignore status, only the age condition below applies.
    private var autoCallMode = "status"
    private var autoCallStatuses = mutableSetOf("pending")
    private var autoCallAgeEnabled = false
    private var autoCallMinAgeDays = 3
    private var autoCallQueue: List<String> = emptyList()      // phone numbers, in dial order
    private var autoCallQueueIds: List<String> = emptyList()   // matching parcel ids, same order
    private var autoCallIndex = 0

    // Per-consignment last-seen remark timestamp — used to detect genuinely new remarks
    // (vs. initial listener fire on attach) and trigger in-app notifications.
    private val ccLastSeenRemarkAt = mutableMapOf<String, Long>()

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
    private val uidNameCache = mutableMapOf<String, String>()
    private val uidPhotoCache = mutableMapOf<String, String>()

    /** Resolves [uid] to a display name via users/{uid}/profile/name — direct O(1) access,
     *  cached in [uidNameCache] after the first lookup. Falls back to the raw uid if the
     *  profile/name is missing or the fetch fails. */
    private suspend fun resolveUserName(uid: String): String {
        if (uid.isBlank()) return "Agent"
        uidNameCache[uid]?.let { return it }
        val snap = withContext(Dispatchers.IO) {
            runCatching {
                com.google.firebase.database.FirebaseDatabase.getInstance()
                    .reference.child("users/$uid/profile").get().await()
            }.getOrNull()
        }
        val name = snap?.child("name")?.getValue(String::class.java)?.trim()?.takeIf { it.isNotBlank() } ?: uid
        uidNameCache[uid] = name
        uidPhotoCache[uid] = snap?.child("photo_url")?.getValue(String::class.java)?.trim().orEmpty()
        return name
    }

    // systemId -> display name, fetched once per session via users_by_systemId reverse-index
    // + parallel per-uid name lookup (see ensureAgentNameMap()), reused for every subsequent
    // run listener trigger. Cleared on pull-to-refresh.
    private var systemIdToName: Map<String, String> = emptyMap()

    private lateinit var adapter: CallCenterAdapter

    private var allParcels = listOf<CallCenterParcelItem>()
    private lateinit var etSearch: EditText
    private lateinit var tvSearchClear: TextView
    private lateinit var tvSearchCount: TextView
    private var searchQuery: String = ""

    private lateinit var tvAgentDropdown: TextView
    private val selectedAgentFilters: MutableSet<String> = mutableSetOf() // empty = all agents
    private var ccAgentOptions: List<String> = emptyList() // known agent display-names

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

    // Run ID shape: run_{ddmmyy}_{employeeId} — ddmmyy is always exactly 6 zero-padded digits.
    private val RUN_ID_PATTERN = Regex("^run_(\\d{6})_(.+)$")

    override fun onDestroyView() {
        super.onDestroyView()
        stopAutoCall()
        detachRunsListener()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_call_center, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        updateModeDropdownLabel()
        setupFilterTabs()
        setupAdapter()
        loadCcRemarkOptions()
        loadData()
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
        tvModeDropdown.setOnClickListener { showModeDropdown() }
        tvBranchDropdown.setOnClickListener { showBranchDropdown() }
        tvAgentDropdown.setOnClickListener { showAgentDropdown() }
        layoutFilterTabs = view.findViewById(R.id.layoutCcaFilterTabs)
        rvParcelList = view.findViewById(R.id.rvCcaParcelList)
        pbProgress = view.findViewById(R.id.twCcaProgressBar)
        tvEmpty = view.findViewById(R.id.twCcaEmptyState)
        spinnerCcRunType = view.findViewById(R.id.spinnerCcRunType)

        etSearch = view.findViewById(R.id.twCcaSearchInput)
        tvSearchClear = view.findViewById(R.id.twCcaSearchClear)
        tvSearchCount = view.findViewById(R.id.twCcaSearchCount)
        tvAgentDropdown = view.findViewById(R.id.tvCcaAgentDropdown)
        tvCollapseArrow = view.findViewById(R.id.tvCcaCollapseArrow)
        layoutCollapsibleSection = view.findViewById(R.id.layoutCcaCollapsibleSection)
        setupSearch()
        setupCollapseToggle()
        swipeRefresh = view.findViewById(R.id.swipeRefreshCca)
        swipeRefresh.setColorSchemeResources(R.color.theme_brand_red)
        swipeRefresh.setOnRefreshListener {
            systemIdToName = emptyMap()
            uidNameCache.clear()
            detachRunsListener()
            loadCcRemarkOptions()
            loadData()
            swipeRefresh.isRefreshing = false
        }

        switchAutoCall = view.findViewById(R.id.switchCcAutoCall)
        btnAutoCallStartPause = view.findViewById(R.id.btnCcAutoCallStartPause)
        btnAutoCallGapMenu = view.findViewById(R.id.btnCcAutoCallGapMenu)
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

        switchAutoCall.setOnCheckedChangeListener(null)
        switchAutoCall.isChecked = false
        switchAutoCall.setOnCheckedChangeListener { _, isChecked ->
            btnAutoCallStartPause.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) stopAutoCall()
        }

        btnAutoCallGapMenu.setOnClickListener { showAutoCallGapMenu() }

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
            val allStatuses = StatusMetaCache.entries.entries.sortedByDescending { it.value.priority }
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

        // Fresh queue only when not resuming a paused run (queue empty or we've reached the end).
        if (autoCallQueue.isEmpty() || autoCallIndex >= autoCallQueue.size) {
            val eligible = allParcels.filter { p ->
                if (p.phone.isBlank()) return@filter false
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
            autoCallQueue = eligible.map { it.phone }
            autoCallQueueIds = eligible.map { it.id }
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

                AutoDialHelper.dial(this@CallCenterFragment, phone, forceDirect = true)
                autoCallIndex++
                delay(autoCallGapSeconds * 1000L)
            }
            // Finished the whole queue — mark the last item done too.
            if (isAdded) {
                autoCallQueueIds.lastOrNull()?.let { lastId -> callCardStates[lastId] = colorCallDone }
                pushCallStates()
                btnAutoCallStartPause.text = "▶ Start"
                autoCallQueue = emptyList()
                autoCallQueueIds = emptyList()
                autoCallIndex = 0
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
        settleGlowStatesOnHalt()
        btnAutoCallStartPause.text = "▶ Start"
    }

    private fun stopAutoCall() {
        autoCallJob?.cancel()
        autoCallJob = null
        settleGlowStatesOnHalt()
        autoCallQueue = emptyList()
        autoCallQueueIds = emptyList()
        autoCallIndex = 0
        btnAutoCallStartPause.text = "▶ Start"
    }

    private fun setupAdapter() {
        adapter = CallCenterAdapter(
            onCall = { item ->
                AutoDialHelper.dial(this@CallCenterFragment, item.phone)
                callCardStates[item.id] = colorCallDone
                pushCallStates()
            },
            onSetRemarks = { item -> showRemarksDialog(item) },
            onValidate = { item ->
                allParcels = allParcels.map {
                    if (it.id == item.id) it.copy(
                        validationRequest = false,
                        status = "confirmed",
                        remarks = "Validated by call center"
                    ) else it
                }
                applyFilters()
            },
            onLongPress = { item -> showActionHistoryDialog(item) }
        )
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
                        callCardStates[item.id] = colorCallDone
                        pushCallStates()
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
     * with each entry's author already resolved to a real name (see resolveUserName()).
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
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_action_history, null)
        val tvTitle = view.findViewById<TextView>(R.id.twHistoryTitle)
        val tvSub = view.findViewById<TextView>(R.id.twHistorySub)
        val layoutTimeline = view.findViewById<LinearLayout>(R.id.layoutTimeline)
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

        if (historyEntries.isEmpty()) {
            val emptyView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_timeline_empty, layoutTimeline, false)
            layoutTimeline.addView(emptyView)
        } else {
            for ((index, entry) in historyEntries.withIndex()) {
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

                tvLine.visibility = if (index < historyEntries.size - 1) View.VISIBLE else View.GONE

                tvAuthor.text = entry.author

                tvStatus.text = entry.action
                tvStatus.setTextColor(statusCfg.color)
                tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(statusCfg.bg)

                tvRemark.text = entry.remark
                tvRemark.visibility = if (entry.remark.isNotBlank()) View.VISIBLE else View.GONE

                tvMeta.text = entry.time

                layoutTimeline.addView(timelineView)
            }
        }

        view.findViewById<TextView>(R.id.btnHistoryClose).setOnClickListener {
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
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
                applyFilters()
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
        val checked = BooleanArray(branchArray.size) { i ->
            selectedBranchIds.isEmpty() || branchArray[i] in selectedBranchIds
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Select Branches")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                if (isChecked) selectedBranchIds.add(branchArray[which])
                else selectedBranchIds.remove(branchArray[which])
            }
            .setPositiveButton("Apply") { _, _ ->
                if (selectedBranchIds.size >= branches.size) selectedBranchIds.clear()
                updateBranchDropdownLabel()
                applyFilters()
            }
            .setNeutralButton("All") { _, _ ->
                selectedBranchIds.clear()
                updateBranchDropdownLabel()
                applyFilters()
            }
            .show()
    }

    private fun setupFilterTabs() {
        layoutFilterTabs.removeAllViews()
        val total        = allParcels.size
        val statusCounts = allParcels.groupingBy { it.effectiveStatus }.eachCount()

        // Reset active filter if it no longer exists in data
        if (statusFilter != "all" && !statusCounts.containsKey(statusFilter)) {
            statusFilter = "all"
        }

        // Chips sorted by config/statusMeta/{key}/priority (admin-managed in
        // ConfigStatusesFragment) — higher priority first. Ties broken alphabetically
        // for a stable order; unconfigured statuses (priority 0) sort last together.
        val sortedEntries = statusCounts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { StatusMetaCache.entries[it.key]?.priority ?: 0 }
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
        tvEmpty.visibility    = View.GONE
        detachRunsListener()
        attachRootRunTypesListener()
    }

    /** Discovers today's runs, scoped strictly to the CC agent's OWN assigned branches via
     *  courier/runs_by_branchId/{branchId} — never reads other branches' data or the full
     *  historical courier/run_routes tree. */
    private fun attachRootRunTypesListener() {
        detachRootRunTypesListener()
        myBranchIds = RbacManager.current.branchIds
        if (myBranchIds.isEmpty()) {
            pbProgress.visibility = View.GONE
            tvEmpty.visibility    = View.VISIBLE
            tvEmpty.text          = "⚠ কোনো branch assigned নেই — admin-এর সাথে যোগাযোগ করুন"
            return
        }

        val db = com.google.firebase.database.FirebaseDatabase.getInstance()
        val branchSnapshots = mutableMapOf<String, com.google.firebase.database.DataSnapshot>()
        val branchIdsSnapshot = myBranchIds // stable copy for the closures below

        branchIdsSnapshot.forEach { branchId ->
            val ref = db.reference.child("courier/runs_by_branchId/$branchId")
            val listener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    if (!isAdded) return
                    branchSnapshots[branchId] = snapshot
                    if (branchSnapshots.size >= branchIdsSnapshot.size) {
                        onBranchIndexesLoaded(branchSnapshots.values.toList())
                    }
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                    if (!isAdded) return
                    pbProgress.visibility = View.GONE
                    tvEmpty.visibility    = View.VISIBLE
                    tvEmpty.text          = "⚠ Load failed: ${error.message.take(60)}"
                }
            }
            ref.addValueEventListener(listener)
            ccActiveListeners.add(ref to listener)
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
                searchQuery = s?.toString()?.trim() ?: ""
                tvSearchClear.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                applyFilters()
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

    /** Rebuilds the agent filter dropdown options from whichever workers currently have parcels. */
    private fun bindCcAgentSpinner() {
        if (!::tvAgentDropdown.isInitialized) return
        val agents = allParcels.map { it.worker }.filter { it.isNotBlank() }.distinct().sorted()
        ccAgentOptions = agents
        // Drop any previously-selected agent who no longer has any parcels.
        selectedAgentFilters.retainAll(agents.toSet())
        updateAgentDropdownLabel()
    }

    private fun updateAgentDropdownLabel() {
        val ctx = context ?: return
        val selected = selectedAgentFilters.intersect(ccAgentOptions.toSet())
        val isFiltered = selected.isNotEmpty() && selected.size < ccAgentOptions.size
        val label = when {
            !isFiltered -> "👥 All Agents ▾"
            selected.size == 1 -> "${selected.first()} ▾"
            selected.size == 2 -> {
                val names = selected.toList()
                "${names[0]} & ${names[1]} ▾"
            }
            else -> {
                val names = selected.take(2).toList()
                "${names[0]}, ${names[1]} & ${selected.size - 2} more ▾"
            }
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
        val agentArray = ccAgentOptions.toTypedArray()
        val checked = BooleanArray(agentArray.size) { i ->
            selectedAgentFilters.isEmpty() || agentArray[i] in selectedAgentFilters
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Select Agents")
            .setMultiChoiceItems(agentArray, checked) { _, which, isChecked ->
                if (isChecked) selectedAgentFilters.add(agentArray[which])
                else selectedAgentFilters.remove(agentArray[which])
            }
            .setPositiveButton("Apply") { _, _ ->
                if (selectedAgentFilters.size >= ccAgentOptions.size) selectedAgentFilters.clear()
                updateAgentDropdownLabel()
                applyFilters()
            }
            .setNeutralButton("All") { _, _ ->
                selectedAgentFilters.clear()
                updateAgentDropdownLabel()
                applyFilters()
            }
            .show()
    }

    private fun formatCcRunTypeLabel(runType: String): String =
        runType.split("_")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
            }

    /** Called once every assigned branch's index snapshot has arrived (and again whenever any
     *  of them change — new run created, a run's representative status updated, etc). Derives
     *  today's candidate runs and attaches a dedicated live listener per run node. */
    private fun onBranchIndexesLoaded(branchSnapshots: List<com.google.firebase.database.DataSnapshot>) {
        if (!isAdded) return

        val runTypes = branchSnapshots.flatMap { snap -> snap.children.mapNotNull { it.key } }
            .distinct().sorted()

        ccRunTypeOptions = listOf(CcRunTypeOption(CC_RUN_TYPE_ALL, "All")) +
            runTypes.map { CcRunTypeOption(it, formatCcRunTypeLabel(it)) }
        if (ccSelectedRunType != CC_RUN_TYPE_ALL && ccSelectedRunType !in runTypes) {
            ccSelectedRunType = CC_RUN_TYPE_ALL
        }
        bindCcRunTypeSpinner()

        val typesToWatch = if (ccSelectedRunType == CC_RUN_TYPE_ALL) runTypes else listOf(ccSelectedRunType)
        if (typesToWatch.isEmpty()) {
            pbProgress.visibility = View.GONE
            tvEmpty.visibility    = View.VISIBLE
            tvEmpty.text          = "📭\n\nকোনো run নেই"
            return
        }

        // Today's date range — same bounds as before, just applied earlier (at the small
        // branch-index level) instead of after pulling the entire historical run_routes tree.
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis
        val dayEnd   = dayStart + 24 * 60 * 60 * 1000 - 1

        // Dedupe (runType, runId) across branches — a multi-branch agent's run is written into
        // EVERY one of their assigned branches' indexes, but it's a single real run node.
        val candidateKeys = mutableSetOf<Pair<String, String>>()
        branchSnapshots.forEach { branchSnap ->
            branchSnap.children.forEach { runTypeSnap ->
                val runType = runTypeSnap.key ?: return@forEach
                if (runType !in typesToWatch) return@forEach
                runTypeSnap.children.forEach { runIdSnap ->
                    val runId = runIdSnap.key ?: return@forEach
                    val ts = parseRunTimestamp(runId) ?: return@forEach
                    if (ts in dayStart..dayEnd) candidateKeys.add(runType to runId)
                }
            }
        }

        if (candidateKeys.isEmpty()) {
            pbProgress.visibility = View.GONE
            tvEmpty.visibility    = View.VISIBLE
            tvEmpty.text          = "📭\n\nআজকের কোনো consignment নেই"
            return
        }

        val db = com.google.firebase.database.FirebaseDatabase.getInstance()
        candidateKeys.forEach { (runType, runId) ->
            val key = "$runType/$runId"
            if (key in ccAttachedRunKeys) return@forEach
            ccAttachedRunKeys.add(key)
            val ref = db.reference.child("courier/run_routes/$runType/$runId")
            val listener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    if (!isAdded) return
                    ccRunNodeSnapshots[key] = snapshot
                    viewLifecycleOwner.lifecycleScope.launch { reprocessAllCachedRuns() }
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            }
            ref.addValueEventListener(listener)
            ccActiveListeners.add(ref to listener)
        }
    }

    private val ccActiveListeners = mutableListOf<Pair<com.google.firebase.database.DatabaseReference, com.google.firebase.database.ValueEventListener>>()

    private fun detachRunsListener() {
        ccActiveListeners.forEach { (ref, l) -> ref.removeEventListener(l) }
        ccActiveListeners.clear()
        ccRunNodeSnapshots.clear()
        ccAttachedRunKeys.clear()

        ccRemarkNodeListeners.values.forEach { (ref, l) -> ref.removeEventListener(l) }
        ccRemarkNodeListeners.clear()
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
            // Step 2: uid → name from users/{uid}/profile/name (fresh, never stale).
            // Fired in parallel via async — N concurrent round-trips instead of N
            // sequential ones, since each fetch is independent of the others.
            val map = coroutineScope {
                sysIdToUid.map { (sysId, uid) ->
                    async(Dispatchers.IO) {
                        val name = runCatching {
                            db.reference.child("users/$uid/profile/name").get().await()
                                .getValue(String::class.java)?.trim()
                        }.getOrNull()
                        sysId to name
                    }
                }.awaitAll()
                    .filter { !it.second.isNullOrBlank() }
                    .associate { it.first to it.second!! }
            }
            systemIdToName = map
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Rebuilds the full parcel list from ccRunNodeSnapshots (every currently-attached run
     *  node's latest snapshot) — called whenever any one of those run nodes changes. Today/branch
     *  filtering already happened upstream when candidates were selected, so every cached run
     *  here is guaranteed relevant. */
    private suspend fun reprocessAllCachedRuns() {
        val db = com.google.firebase.database.FirebaseDatabase.getInstance()

        // Collect consignment ids + statuses + which agent's run + which branch they came from.
        // agentSystemId and resolvedBranchIds are flat fields on the run node (written by
        // ConfigSheetFragment's sync) — reading them here costs nothing extra.
        val consignmentInfo = mutableMapOf<String, Triple<String, String, String>>() // cId -> (agentSystemId, status, branch)
        ccRunNodeSnapshots.values.forEach { runSnap ->
            val agentSystemId = runSnap.child("agentSystemId").getValue(String::class.java)?.trim().orEmpty()
            val resolvedBranch = runSnap.child("resolvedBranchIds").children
                .mapNotNull { it.getValue(String::class.java) }.firstOrNull().orEmpty()
            runSnap.child("consignments").children.forEach { c ->
                val cId     = c.key ?: return@forEach
                val cStatus = c.getValue(String::class.java) ?: "pending"
                consignmentInfo[cId] = Triple(agentSystemId, cStatus, resolvedBranch)
            }
        }

        if (consignmentInfo.isEmpty()) {
            pbProgress.visibility = View.GONE
            tvEmpty.visibility    = View.VISIBLE
            tvEmpty.text          = "📭\n\nআজকের কোনো consignment নেই"
            return
        }

        // One-time (cached) bulk fetch of all agents' names — avoids N per-agent lookups.
        val nameMap = ensureAgentNameMap()

        // Parallel fetch consignment details + full remark history
        val parcels = coroutineScope {
            val fetches = consignmentInfo.entries.map { entry ->
                val cId = entry.key
                val (agentSystemId, runStatus, resolvedBranch) = entry.value
                async(Dispatchers.IO) {
                    try {
                        val snap = db.reference.child("courier/consignments/$cId").get().await()
                        if (!snap.exists()) return@async null

                        val name    = snap.child("recipientName").getValue(String::class.java) ?: ""
                        val phone   = snap.child("recipientPhone").getValue(String::class.java) ?: ""
                        val address = snap.child("recipientAddress").getValue(String::class.java) ?: ""
                        val cod     = snap.child("collectableAmount").getValue(String::class.java)
                            ?.toDoubleOrNull()?.toInt()
                            ?: snap.child("collectableAmount").getValue(Long::class.java)?.toInt() ?: 0
                        // Prefer the run's locked-in branch (authoritative, set once at run
                        // creation — see runs_by_branchId). Falls back to the parcel's own
                        // deliveryHub only for pre-migration runs that predate resolvedBranchIds.
                        val hub = resolvedBranch.ifBlank {
                            snap.child("deliveryHub").getValue(String::class.java) ?: ""
                        }
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

                        // Full remark history (not just the latest) — needed for the journey popup.
                        val remarksSnap = db.reference.child("courier/remarks_by_consignment/$cId")
                            .get().await()
                        val latestEntry = remarksSnap.children.lastOrNull()
                        val remarkStatus = latestEntry?.child("status")?.getValue(String::class.java)?.trim().orEmpty()
                        val remarkText = latestEntry?.child("remarks")?.getValue(String::class.java)?.trim().orEmpty()
                        val remarkLabel = when {
                            remarkText.isNotBlank() -> remarkText
                            remarkStatus.isNotBlank() -> context?.let {
                                WorkerParcelAdapter.getStatusConfig(it, remarkStatus, "bn").label
                            } ?: remarkStatus
                            else -> ""
                        }

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
                                validationRequest = remarkStatus == "verify_req",
                                validationNote    = if (remarkStatus == "verify_req") remarkLabel else "",
                                time              = "",
                                worker            = nameMap[agentSystemId] ?: agentSystemId,
                                branch            = hubName,
                                createdAt         = createdAtVal,
                                updatedAt         = updatedAtVal
                            ),
                            remarksSnap,
                            agentSystemId
                        )
                    } catch (e: Exception) { null }
                }
            }.mapNotNull { it.await() }

            // Pre-resolve every distinct remark-author uid across ALL fetched parcels in one
            // parallel batch (direct users/{uid} access — remark data already carries the uid).
            val allUids = fetches.flatMap { (_, remarksSnap, _) -> remarksSnap.children }
                .mapNotNull { it.child("userId").getValue(String::class.java)?.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            allUids.map { uid -> async(Dispatchers.IO) { resolveUserName(uid) } }.awaitAll()

            fetches.map { (item, remarksSnap, agentSystemId) ->
                val history = remarksSnap.children.mapNotNull { r ->
                    val rStatus = r.child("status").getValue(String::class.java)?.trim().orEmpty()
                    val rNote = r.child("remarks").getValue(String::class.java)?.trim().orEmpty()
                    if (rStatus.isBlank() && rNote.isBlank()) return@mapNotNull null
                    val rLabel = when {
                        rNote.isNotBlank()   -> rNote
                        rStatus.isNotBlank() -> context?.let { WorkerParcelAdapter.getStatusConfig(it, rStatus, "bn").label } ?: rStatus
                        else                 -> ""
                    }
                    val createdAt = r.child("createdAt").getValue(Long::class.java) ?: 0L
                    val timeStr = java.text.SimpleDateFormat("dd-MM-yy hh:mm:ss a", java.util.Locale.getDefault())
                        .format(java.util.Date(createdAt))
                    val remarkedBy = r.child("remarked_by").getValue(String::class.java)?.trim().orEmpty()
                    val rUserId = r.child("userId").getValue(String::class.java)?.trim().orEmpty()
                    val resolvedName  = if (rUserId.isNotBlank()) uidNameCache[rUserId] else null
                    val resolvedPhoto = if (rUserId.isNotBlank()) uidPhotoCache[rUserId] else null
                    val authorRole = if (remarkedBy == "support") "cc" else "agent"
                    val author = when {
                        remarkedBy == "support" && !resolvedName.isNullOrBlank() -> "$resolvedName · CC"
                        remarkedBy == "support"                                  -> "CC"
                        !resolvedName.isNullOrBlank()                            -> resolvedName
                        else                                                     -> nameMap[agentSystemId] ?: agentSystemId
                    }
                    HistoryEntry(
                        action = rStatus.ifBlank { "NOTE" }.uppercase(),
                        remark = rLabel,
                        time = timeStr,
                        author = author,
                        authorRole = authorRole,
                        authorPhotoUrl = resolvedPhoto.orEmpty()
                    )
                }.sortedBy { it.time }
                item.copy(history = history)
            }
        }

        if (!isAdded) return
        allParcels = parcels.sortedBy { it.id }
        // Branch chips reflect the CC agent's OWN assignment (RbacManager), not whatever
        // branches happen to show up in the fetched parcels — Karim (Sonargaon only) never
        // sees a "Bandar" chip even if a stray legacy parcel's deliveryHub said otherwise.
        branches = myBranchIds
        setupBranchDropdown()
        setupFilterTabs()
        bindCcAgentSpinner()
        applyFilters()
        pbProgress.visibility = View.GONE
        syncCcRemarkListeners(allParcels.map { it.id }.toSet())
    }

    // Per-parcel remark listeners for Call Center — keyed by consignmentId.
    private val ccRemarkNodeListeners = mutableMapOf<String, Pair<com.google.firebase.database.DatabaseReference, com.google.firebase.database.ValueEventListener>>()


    private fun syncCcRemarkListeners(currentIds: Set<String>) {
        val stale = ccRemarkNodeListeners.keys - currentIds
        stale.forEach { cId ->
            ccRemarkNodeListeners.remove(cId)?.let { (ref, listener) -> ref.removeEventListener(listener) }
        }

        currentIds.forEach { cId ->
            if (ccRemarkNodeListeners.containsKey(cId)) return@forEach
            val ref = com.google.firebase.database.FirebaseDatabase.getInstance()
                .reference.child("courier/remarks_by_consignment/$cId")
            val listener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    if (!isAdded) return
                    val ctx = context ?: return
                    viewLifecycleOwner.lifecycleScope.launch {
                        // Find the most recent remark + update the card in-place.
                        val sorted = snapshot.children.sortedByDescending {
                            it.child("createdAt").getValue(Long::class.java) ?: 0L
                        }
                        val latest = sorted.firstOrNull()

                        // ── New-remark notification ───────────────────────────────
                        // Skip the very first fire (initial attach) by checking prevAt > 0.
                        val latestCreatedAt = latest?.child("createdAt")?.getValue(Long::class.java) ?: 0L
                        val prevAt = ccLastSeenRemarkAt[cId] ?: 0L
                        if (latestCreatedAt > prevAt && prevAt > 0L) {
                            val parcel = allParcels.firstOrNull { it.id == cId }
                            val customer = parcel?.customer?.takeIf { it.isNotBlank() } ?: cId
                            val remarkText = latest?.child("remarks")?.getValue(String::class.java)?.trim().orEmpty()
                            val remarkStatus = latest?.child("status")?.getValue(String::class.java)?.trim().orEmpty()
                            val message = when {
                                remarkText.isNotBlank() -> remarkText
                                remarkStatus.isNotBlank() -> WorkerParcelAdapter.getStatusConfig(ctx, remarkStatus, ccStatusLang).label
                                else -> "নতুন রিমার্ক এসেছে"
                            }
                            AppNotificationManager.add(
                                ctx,
                                AppNotificationManager.NotifItem(
                                    title = "New Remark — $customer",
                                    message = message,
                                    type = "remark"
                                )
                            )
                        }
                        ccLastSeenRemarkAt[cId] = latestCreatedAt
                        // ─────────────────────────────────────────────────────────
                        val latestRemark = latest?.let {
                            val status = it.child("status").getValue(String::class.java)?.trim().orEmpty()
                            val remarkText = it.child("remarks").getValue(String::class.java)?.trim().orEmpty()
                            when {
                                remarkText.isNotBlank() -> remarkText
                                status.isNotBlank() -> WorkerParcelAdapter.getStatusConfig(ctx, status, ccStatusLang).label
                                else -> ""
                            }
                        }.orEmpty()

                        // Resolve every distinct author uid in this remark set in parallel
                        // (direct users/{uid} access), then rebuild the full journey history.
                        val distinctUids = snapshot.children
                            .mapNotNull { it.child("userId").getValue(String::class.java)?.trim() }
                            .filter { it.isNotBlank() }
                            .distinct()
                        coroutineScope {
                            distinctUids.map { uid -> async(Dispatchers.IO) { resolveUserName(uid) } }.awaitAll()
                        }

                        if (!isAdded) return@launch
                        val fallbackWorker = allParcels.firstOrNull { it.id == cId }?.worker ?: "Agent"
                        val history = snapshot.children.mapNotNull { r ->
                            val rStatus = r.child("status").getValue(String::class.java)?.trim().orEmpty()
                            val rNote = r.child("remarks").getValue(String::class.java)?.trim().orEmpty()
                            if (rStatus.isBlank() && rNote.isBlank()) return@mapNotNull null
                            val rLabel = when {
                                rNote.isNotBlank()   -> rNote
                                rStatus.isNotBlank() -> WorkerParcelAdapter.getStatusConfig(ctx, rStatus, ccStatusLang).label
                                else                 -> ""
                            }
                            val createdAt = r.child("createdAt").getValue(Long::class.java) ?: 0L
                            val timeStr = java.text.SimpleDateFormat("dd-MM-yy hh:mm:ss a", java.util.Locale.getDefault())
                                .format(java.util.Date(createdAt))
                            val remarkedBy = r.child("remarked_by").getValue(String::class.java)?.trim().orEmpty()
                            val rUserId = r.child("userId").getValue(String::class.java)?.trim().orEmpty()
                            val resolvedName  = if (rUserId.isNotBlank()) uidNameCache[rUserId] else null
                            val resolvedPhoto = if (rUserId.isNotBlank()) uidPhotoCache[rUserId] else null
                            val authorRole = if (remarkedBy == "support") "cc" else "agent"
                            val author = when {
                                remarkedBy == "support" && !resolvedName.isNullOrBlank() -> "$resolvedName · CC"
                                remarkedBy == "support"                                  -> "CC"
                                !resolvedName.isNullOrBlank()                            -> resolvedName
                                else                                                     -> fallbackWorker
                            }
                            HistoryEntry(
                                action = rStatus.ifBlank { "NOTE" }.uppercase(),
                                remark = rLabel,
                                time = timeStr,
                                author = author,
                                authorRole = authorRole,
                                authorPhotoUrl = resolvedPhoto.orEmpty()
                            )
                        }.sortedBy { it.time }

                        val idx = allParcels.indexOfFirst { it.id == cId }
                        if (idx != -1) {
                            allParcels = allParcels.toMutableList().also {
                                it[idx] = it[idx].copy(
                                    remarks = latestRemark,
                                    history = history
                                )
                            }
                            applyFilters()
                        }
                    }
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            }
            ref.addValueEventListener(listener)
            ccRemarkNodeListeners[cId] = ref to listener
        }
    }

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
        var selectedRemarkText  = ""
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

            // If a remark option was picked: "label — note" (or just label if no note).
            // If NO option was picked but there's a note: the note itself IS the remark,
            // and no target status is applied (status stays blank — this is a note-only entry).
            val fullRemark = when {
                selectedStatus.isNotBlank() && noteText.isNotBlank() -> "$selectedRemarkText — $noteText"
                selectedStatus.isNotBlank()                          -> selectedRemarkText
                else                                                 -> noteText
            }

            if (selectedTemplateId.isNotBlank() && WhatsAppSender.isEnabled(requireContext())) {
                val template = whatsappTemplatesCache[selectedTemplateId]
                if (template != null && template.body.isNotBlank()) {
                    val filledMessage = WhatsAppHelper.fillTemplate(
                        body = template.body,
                        name = item.customer,
                        phone = item.phone,
                        address = item.address,
                        cod = item.cod.toString(),
                        consignmentId = item.id,
                        hub = ""
                    )
                    WhatsAppHelper.send(requireContext(), item.phone, filledMessage)
                }
            }

            // Write to Firebase — remark and status are written as SEPARATE operations
            // (not one atomic multi-path update) so the remark always gets saved even if
            // the status/consignments write gets rejected by a role-restricted rule.
            val db        = com.google.firebase.database.FirebaseDatabase.getInstance()
            val timestamp = System.currentTimeMillis()

            val remarkData = mapOf(
                "userId"        to userId,
                "remarks"       to fullRemark,
                "note"          to noteText,
                "status"        to selectedStatus,
                "remarked_by"   to "support",
                "createdAt"     to timestamp
            )
            db.reference.child("courier/remarks_by_consignment/${item.id}/remarks_$timestamp")
                .setValue(remarkData)
                .addOnFailureListener { e ->
                    FirebaseErrorLogger.log(
                        screen = "CallCenterFragment", action = "remark_write",
                        errorMessage = e.message ?: "unknown",
                        extra = mapOf("consignmentId" to item.id, "userId" to userId)
                    )
                    Toast.makeText(requireContext(), "⚠ Remark save হয়নি: ${e.message}", Toast.LENGTH_LONG).show()
                }

            // Parcel status (courier/consignments/{id}/status) is a SEPARATE concept from
            // remark status and is NEVER written/changed from here — only the remark's own
            // "status" field above (already saved as part of remarkData) represents this.

            allParcels = allParcels.map {
                if (it.id == item.id) it.copy(
                    validationRequest = false,
                    remarkStatus = selectedStatus,
                    remarks = fullRemark
                ) else it
            }
            setupFilterTabs()
            applyFilters()
            dialog.dismiss()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.setContentView(view)
        dialog.show()
    }


    private fun applyFilters() {
        var filtered = allParcels

        // Access mode — Priority-only shows just agents who sent a verify request;
        // All-only shows everyone in-branch regardless, for random spot-verification;
        // both selected shows everyone, priority (validation-requested) parcels first.
        val modeHasPriority = "priority" in selectedAccessModes
        val modeHasAll = "all" in selectedAccessModes
        if (modeHasPriority && !modeHasAll) {
            filtered = filtered.filter { it.validationRequest }
        }

        // Branch filter — empty selectedBranchIds means all branches
        if (selectedBranchIds.isNotEmpty()) {
            filtered = filtered.filter { it.branch in selectedBranchIds }
        }

        // Agent (worker) filter — empty selectedAgentFilters means all agents
        if (selectedAgentFilters.isNotEmpty()) {
            filtered = filtered.filter { it.worker in selectedAgentFilters }
        }

        // Search filter — phone, ID, customer name, or COD amount
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            filtered = filtered.filter {
                it.phone.contains(q) ||
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

        // Update stats
        val total = allParcels.size
        val confirmed = allParcels.count { it.effectiveStatus == "confirmed" }
        val pending = allParcels.count { it.effectiveStatus == "pending" }
        val rejected = allParcels.count { it.effectiveStatus == "rejected" }
        val validationCount = allParcels.count { it.validationRequest }

        tvStatTotal.text = total.toString()
        tvStatConfirmed.text = confirmed.toString()
        tvStatPending.text = pending.toString()
        tvStatRejected.text = rejected.toString()
        tvValidationCount.text = "$validationCount pending"

        // Render list — DiffUtil computes the minimal set of changes, so the
        // RecyclerView only rebinds/animates rows that actually changed.
        adapter.collapseExpanded()
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitParcels(filtered)
    }

    /**
     * Extracts the date portion from a run ID of the form "run_{ddmmyy}_{employeeId}"
     * (ddmmyy is always exactly 6 zero-padded digits: day, month, 2-digit year — employeeId
     * comes after and may itself contain underscores). Returns local midnight (00:00:00)
     * millis for that date, or null if the ID doesn't match the expected shape.
     */
    private fun parseRunTimestamp(runId: String): Long? {
        val match = RUN_ID_PATTERN.matchEntire(runId.trim()) ?: return null
        val ddmmyy = match.groupValues[1]
        val day   = ddmmyy.substring(0, 2).toIntOrNull() ?: return null
        val month = ddmmyy.substring(2, 4).toIntOrNull() ?: return null
        val year  = ddmmyy.substring(4, 6).toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        return try {
            java.util.Calendar.getInstance().apply {
                clear()
                set(2000 + year, month - 1, day, 0, 0, 0)
            }.timeInMillis
        } catch (e: Exception) { null }
    }

    data class FilterTab(val key: String, val label: String)

    data class CcRemarkOption(
        val icon: String,
        val label: String,
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
}
