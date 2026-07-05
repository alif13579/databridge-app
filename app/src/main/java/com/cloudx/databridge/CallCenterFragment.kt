package com.cloudx.databridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    private lateinit var layoutBranchFilter: HorizontalScrollView
    private lateinit var layoutBranchChips: LinearLayout
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
    private var autoCallQueue: List<String> = emptyList()      // phone numbers, in dial order
    private var autoCallQueueIds: List<String> = emptyList()   // matching parcel ids, same order
    private var autoCallIndex = 0

    // Per-parcel call-progress glow: id -> color. Persists across pause/stop (done stays green).
    private val callCardStates = mutableMapOf<String, Int>()
    private val colorCallDone = android.graphics.Color.parseColor("#16A34A")
    private val colorCallQueued = android.graphics.Color.parseColor("#F59E0B")
    private val colorCallCalling = android.graphics.Color.parseColor("#7C3AED")

    // Current CC agent's Employee ID — attached to remarks so it's clear who left them.
    // Best-effort fetch: remark-writing still works (falls back to "") if this fails.
    private var employeeId = ""

    // systemId -> display name, fetched once per session (cheap: one bulk read of users/)
    // and reused for every subsequent run listener trigger. Cleared on pull-to-refresh.
    private var systemIdToName: Map<String, String> = emptyMap()

    private lateinit var adapter: CallCenterAdapter

    private var allParcels = listOf<CallCenterParcelItem>()
    private var statusFilter = "all"
    private var branchFilter = "all"
    private var branches = listOf<String>()

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
        layoutBranchFilter = view.findViewById(R.id.layoutCcaBranchFilter)
        layoutBranchChips = view.findViewById(R.id.layoutCcaBranchChips)
        layoutFilterTabs = view.findViewById(R.id.layoutCcaFilterTabs)
        rvParcelList = view.findViewById(R.id.rvCcaParcelList)
        pbProgress = view.findViewById(R.id.twCcaProgressBar)
        tvEmpty = view.findViewById(R.id.twCcaEmptyState)
        spinnerCcRunType = view.findViewById(R.id.spinnerCcRunType)
        swipeRefresh = view.findViewById(R.id.swipeRefreshCca)
        swipeRefresh.setColorSchemeResources(R.color.theme_brand_red)
        swipeRefresh.setOnRefreshListener {
            systemIdToName = emptyMap()
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
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    employeeId = withContext(Dispatchers.IO) {
                        com.google.firebase.database.FirebaseDatabase.getInstance().reference
                            .child("users/$uid/profile/company_info/employee_id")
                            .get().await().getValue(String::class.java)?.trim().orEmpty()
                    }
                } catch (e: Exception) { /* remark writing still works without it */ }
            }
        }
    }

    private fun setupAutoCallControls() {
        val prefs = requireContext().getSharedPreferences("databridge_toggles", android.content.Context.MODE_PRIVATE)
        autoCallGapSeconds = prefs.getInt("cc_auto_call_gap_seconds", 8)

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
        val popup = PopupMenu(requireContext(), btnAutoCallGapMenu)
        val gaps = listOf(5, 8, 10, 15, 20, 30)
        gaps.forEach { seconds -> popup.menu.add("$seconds sec gap") }
        popup.setOnMenuItemClickListener { item ->
            val seconds = item.title.toString().split(" ").firstOrNull()?.toIntOrNull() ?: return@setOnMenuItemClickListener false
            autoCallGapSeconds = seconds
            requireContext().getSharedPreferences("databridge_toggles", android.content.Context.MODE_PRIVATE)
                .edit().putInt("cc_auto_call_gap_seconds", seconds).apply()
            Toast.makeText(requireContext(), "Auto Call gap set to ${seconds}s", Toast.LENGTH_SHORT).show()
            true
        }
        popup.show()
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
            val pending = allParcels.filter { it.status == "pending" && it.phone.isNotBlank() }
            autoCallQueue = pending.map { it.phone }
            autoCallQueueIds = pending.map { it.id }
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
            }
        )
        rvParcelList.layoutManager = LinearLayoutManager(requireContext())
        rvParcelList.adapter = adapter
        // Item views recycle instead of being fully re-inflated on every refresh/filter.
        rvParcelList.setHasFixedSize(false)
        rvParcelList.setItemViewCacheSize(8)
    }

    private fun setupBranchChips() {
        layoutBranchChips.removeAllViews()

        if (branches.size <= 1) {
            layoutBranchFilter.visibility = View.GONE
            return
        }

        layoutBranchFilter.visibility = View.VISIBLE

        // "All Branches" chip
        val allChip = layoutInflater.inflate(R.layout.item_branch_chip, layoutBranchChips, false) as TextView
        allChip.text = "All Branches"
        allChip.tag = "all"
        allChip.setOnClickListener {
            branchFilter = "all"
            updateBranchChips()
            applyFilters()
        }
        layoutBranchChips.addView(allChip)

        for (branch in branches) {
            val chip = layoutInflater.inflate(R.layout.item_branch_chip, layoutBranchChips, false) as TextView
            chip.text = branch
            chip.tag = branch
            chip.setOnClickListener {
                branchFilter = branch
                updateBranchChips()
                applyFilters()
            }
            layoutBranchChips.addView(chip)
        }

        updateBranchChips()
    }

    private fun updateBranchChips() {
        for (i in 0 until layoutBranchChips.childCount) {
            val chip = layoutBranchChips.getChildAt(i) as TextView
            val isActive = chip.tag == branchFilter
            chip.isSelected = isActive
            chip.setBackgroundResource(
                if (isActive) R.drawable.bg_filter_chip_active_purple
                else R.drawable.bg_filter_chip_inactive
            )
            chip.setTextColor(
                requireContext().getColor(
                    if (isActive) android.R.color.white else R.color.theme_text_secondary
                )
            )
        }
    }

    private fun setupFilterTabs() {
        layoutFilterTabs.removeAllViews()
        val total        = allParcels.size
        val statusCounts = allParcels.groupingBy { it.status }.eachCount()

        // Reset active filter if it no longer exists in data
        if (statusFilter != "all" && !statusCounts.containsKey(statusFilter)) {
            statusFilter = "all"
        }

        val statusOrder = listOf(
            "pending", "verify_req", "delivery_req", "hold_req",
            "confirmed", "delivered", "return_req", "rejected"
        )
        val sortedEntries = statusCounts.entries.sortedWith { a, b ->
            val ai = statusOrder.indexOf(a.key).let { if (it == -1) Int.MAX_VALUE else it }
            val bi = statusOrder.indexOf(b.key).let { if (it == -1) Int.MAX_VALUE else it }
            ai.compareTo(bi)
        }

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
        for (i in 0 until layoutFilterTabs.childCount) {
            val chip = layoutFilterTabs.getChildAt(i) as TextView
            val isActive = chip.tag == statusFilter
            chip.isSelected = isActive
            chip.setBackgroundResource(
                if (isActive) R.drawable.bg_filter_chip_active
                else R.drawable.bg_filter_chip_inactive
            )
            chip.setTextColor(
                requireContext().getColor(
                    if (isActive) android.R.color.white else R.color.theme_text_secondary
                )
            )
        }
    }

    private var runsListener: com.google.firebase.database.ValueEventListener? = null
    private var runsRef: com.google.firebase.database.DatabaseReference? = null

    // ── Run type selection (mirrors WorkerSpaceFragment pattern) ──────
    private lateinit var spinnerCcRunType: Spinner
    data class CcRunTypeOption(val key: String, val label: String)
    private val CC_RUN_TYPE_ALL = "__ALL__"
    private var ccSelectedRunType = CC_RUN_TYPE_ALL
    private var ccRunTypeOptions = listOf(CcRunTypeOption(CC_RUN_TYPE_ALL, "All"))
    private var rootRunTypesRef: com.google.firebase.database.DatabaseReference? = null
    private var rootRunTypesListener: com.google.firebase.database.ValueEventListener? = null

    private fun loadData() {
        pbProgress.visibility = View.VISIBLE
        tvEmpty.visibility    = View.GONE
        detachRunsListener()
        attachRootRunTypesListener()
    }

    /** Discovers available run types under courier/run_routes (delivery_run, pickup_run, etc). */
    private fun attachRootRunTypesListener() {
        detachRootRunTypesListener()
        val db  = com.google.firebase.database.FirebaseDatabase.getInstance()
        val ref = db.reference.child("courier/run_routes")
        rootRunTypesRef = ref
        rootRunTypesListener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                if (!isAdded) return
                val runTypes = snapshot.children
                    .mapNotNull { it.key?.trim()?.takeIf { k -> k.isNotBlank() } }
                    .distinct()
                    .sorted()

                ccRunTypeOptions = listOf(CcRunTypeOption(CC_RUN_TYPE_ALL, "All")) +
                    runTypes.map { CcRunTypeOption(it, formatCcRunTypeLabel(it)) }

                if (ccSelectedRunType != CC_RUN_TYPE_ALL && ccSelectedRunType !in runTypes) {
                    ccSelectedRunType = CC_RUN_TYPE_ALL
                }
                bindCcRunTypeSpinner()
                attachRunsListener(runTypes)
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                if (!isAdded) return
                pbProgress.visibility = View.GONE
                tvEmpty.visibility    = View.VISIBLE
                tvEmpty.text          = "⚠ Load failed: ${error.message.take(60)}"
            }
        }
        ref.addValueEventListener(rootRunTypesListener!!)
    }

    private fun detachRootRunTypesListener() {
        val listener = rootRunTypesListener ?: return
        rootRunTypesRef?.removeEventListener(listener)
        rootRunTypesListener = null
        rootRunTypesRef = null
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

    private fun formatCcRunTypeLabel(runType: String): String =
        runType.split("_")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
            }

    /** Attaches a listener on the selected run type's node (or the first discovered type when "All"). */
    private fun attachRunsListener(discoveredTypes: List<String>) {
        val db = com.google.firebase.database.FirebaseDatabase.getInstance()
        val typesToWatch = if (ccSelectedRunType == CC_RUN_TYPE_ALL) discoveredTypes else listOf(ccSelectedRunType)

        if (typesToWatch.isEmpty()) {
            pbProgress.visibility = View.GONE
            tvEmpty.visibility    = View.VISIBLE
            tvEmpty.text          = "📭\n\nকোনো run নেই"
            return
        }

        // For "All", merge every run type's snapshot together before processing.
        val mergedSnapshots = mutableMapOf<String, com.google.firebase.database.DataSnapshot>()
        var remaining = typesToWatch.size

        typesToWatch.forEach { runType ->
            val ref = db.reference.child("courier/run_routes/$runType")
            val listener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    if (!isAdded) return
                    mergedSnapshots[runType] = snapshot
                    if (mergedSnapshots.size >= typesToWatch.size) {
                        viewLifecycleOwner.lifecycleScope.launch { processRunTypeSnapshots(mergedSnapshots.values.toList()) }
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
            // Reuse existing single-ref tracking for the primary type; extra types are cleaned
            // up together in detachRunsListener via the shared list below.
            ccActiveListeners.add(ref to listener)
        }
    }

    private val ccActiveListeners = mutableListOf<Pair<com.google.firebase.database.DatabaseReference, com.google.firebase.database.ValueEventListener>>()

    /** Merges multiple run_type snapshots into one combined view, then runs the existing pipeline. */
    private suspend fun processRunTypeSnapshots(snapshots: List<com.google.firebase.database.DataSnapshot>) {
        if (snapshots.size == 1) {
            processRunsSnapshot(snapshots.first())
            return
        }
        // Multiple run types selected ("All") — process each and concatenate results.
        // processRunsSnapshot already updates the shared list; run sequentially to avoid races.
        snapshots.forEachIndexed { idx, snap ->
            processRunsSnapshot(snap, append = idx > 0)
        }
    }

    private fun detachRunsListener() {
        val listener = runsListener ?: return
        runsRef?.removeEventListener(listener)
        runsListener = null
        runsRef = null

        ccActiveListeners.forEach { (ref, l) -> ref.removeEventListener(l) }
        ccActiveListeners.clear()
        detachRootRunTypesListener()
    }

    /**
     * Fetches the systemId -> name map once (cached in systemIdToName), reused across every
     * subsequent listener trigger until pull-to-refresh clears the cache. One bulk read of
     * users/ instead of one query per distinct agent seen in today's runs.
     */
    private suspend fun ensureAgentNameMap(): Map<String, String> {
        if (systemIdToName.isNotEmpty()) return systemIdToName
        return try {
            val snap = withContext(Dispatchers.IO) {
                com.google.firebase.database.FirebaseDatabase.getInstance()
                    .reference.child("users").get().await()
            }
            val map = mutableMapOf<String, String>()
            snap.children.forEach { child ->
                val sysId = child.child("profile/company_info/system_id")
                    .getValue(String::class.java)?.trim()
                val name = child.child("profile/name").getValue(String::class.java)?.trim()
                    ?: child.child("name").getValue(String::class.java)?.trim()
                if (!sysId.isNullOrBlank() && !name.isNullOrBlank()) map[sysId] = name
            }
            systemIdToName = map
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private suspend fun processRunsSnapshot(runsSnap: com.google.firebase.database.DataSnapshot, append: Boolean = false) {
        val db = com.google.firebase.database.FirebaseDatabase.getInstance()

        // Today's date range
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis
        val dayEnd   = dayStart + 24 * 60 * 60 * 1000 - 1

        // Collect today's consignment ids + statuses + which agent's run they came from.
        // agentSystemId is already a flat field on the run node (written by ConfigSheetFragment's
        // sync) — reading it here costs nothing extra, it's already in the snapshot we have.
        val consignmentInfo = mutableMapOf<String, Pair<String, String>>() // cId -> (agentSystemId, status)
        runsSnap.children.forEach { runSnap ->
            val runId = runSnap.key ?: return@forEach
            val runTimestamp = parseRunTimestamp(runId) ?: return@forEach
            if (runTimestamp < dayStart || runTimestamp > dayEnd) return@forEach
            val agentSystemId = runSnap.child("agentSystemId").getValue(String::class.java)?.trim().orEmpty()
            runSnap.child("consignments").children.forEach { c ->
                val cId     = c.key ?: return@forEach
                val cStatus = c.getValue(String::class.java) ?: "pending"
                consignmentInfo[cId] = agentSystemId to cStatus
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

        // Parallel fetch consignment details
        val parcels = coroutineScope {
            consignmentInfo.entries.map { entry ->
                val cId = entry.key
                val (agentSystemId, runStatus) = entry.value
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
                        val hub     = snap.child("deliveryHub").getValue(String::class.java) ?: ""
                        val status  = snap.child("status").getValue(String::class.java) ?: runStatus

                        val remarkSnap = db.reference.child("courier/remarks_by_consignment/$cId")
                            .limitToLast(1).get().await()
                        val lastRemark = remarkSnap.children.firstOrNull()
                            ?.child("status")?.getValue(String::class.java) ?: ""

                        CallCenterParcelItem(
                            id                = cId,
                            customer          = name,
                            phone             = phone,
                            address           = address,
                            cod               = cod,
                            status            = status,
                            remarks           = lastRemark,
                            validationRequest = status == "verify_req",
                            validationNote    = if (status == "verify_req") lastRemark else "",
                            time              = "",
                            worker            = nameMap[agentSystemId] ?: agentSystemId,
                            branch            = hub
                        )
                    } catch (e: Exception) { null }
                }
            }.mapNotNull { it.await() }
        }

        if (!isAdded) return
        val combined = if (append) (allParcels + parcels).distinctBy { it.id } else parcels
        allParcels = combined.sortedBy { it.id }
        branches   = allParcels.map { it.branch }.filter { it.isNotBlank() }.distinct().sorted()
        setupBranchChips()
        setupFilterTabs()
        applyFilters()
        pbProgress.visibility = View.GONE
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
                        .child("config/remarks").get().await()
                }
                val fetched = mutableListOf<CcRemarkOption>()
                remarksSnap.children.forEach { groupSnap ->
                    groupSnap.children.forEach { r ->
                        val textBn = r.child("text_bn").getValue(String::class.java)?.trim().orEmpty()
                        val textEn = r.child("text_en").getValue(String::class.java)?.trim().orEmpty()
                        val label = (if (remarkLang == "en") textEn.ifBlank { textBn } else textBn.ifBlank { textEn })
                        if (label.isBlank()) return@forEach
                        val target = r.child("target_status").getValue(String::class.java)?.trim()
                            .orEmpty().ifBlank { groupSnap.key ?: return@forEach }
                        val metaEntry = StatusMetaCache.entries[target]
                        val preview = StatusMetaCache.labelOrNull(target, statusLang) ?: target
                        fetched.add(
                            CcRemarkOption(
                                icon = "💬",
                                label = label,
                                statusKey = target,
                                statusPreview = preview,
                                statusColor = metaEntry?.color ?: android.graphics.Color.GRAY
                            )
                        )
                    }
                }

                if (isAdded) {
                    if (fetched.isNotEmpty()) ccRemarkOptions = fetched
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

        var selectedStatus      = ""
        var selectedRemarkText  = ""
        val optionViews         = mutableListOf<android.view.View>()

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
                tvAutoStatus.text  = opt.statusPreview
                tvAutoStatus.setTextColor(opt.statusColor)

                btnSave.isEnabled = true
                btnSave.alpha     = 1f
            }

            optionViews.add(optView)
            layoutOptions.addView(optView)
        }

        // Disabled until option selected
        btnSave.isEnabled = false
        btnSave.alpha     = 0.5f

        btnSave.setOnClickListener {
            if (selectedStatus.isBlank()) return@setOnClickListener
            val noteText   = etRemarks.text?.toString()?.trim() ?: ""
            val fullRemark = if (noteText.isNotBlank()) "$selectedRemarkText — $noteText"
                             else selectedRemarkText

            // Write to Firebase
            val db        = com.google.firebase.database.FirebaseDatabase.getInstance()
            val timestamp = System.currentTimeMillis()
            val multiUpdate = mutableMapOf<String, Any>(
                "courier/remarks_by_consignment/${item.id}/remarks_$timestamp" to mapOf(
                    "agentSystemId" to "",
                    "employeeId"    to employeeId,
                    "remarks"       to fullRemark,
                    "note"          to noteText,
                    "type"          to selectedStatus,
                    "status"        to selectedStatus,
                    "remarked_by"   to "support",
                    "createdAt"     to timestamp
                ),
                "courier/consignments/${item.id}/status"                       to selectedStatus,
                "courier/consignments_by_phone/${item.phone}/${item.id}"       to selectedStatus
            )
            db.reference.updateChildren(multiUpdate)

            allParcels = allParcels.map {
                if (it.id == item.id) it.copy(
                    validationRequest = false,
                    status  = selectedStatus,
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

        // Branch filter
        if (branchFilter != "all") {
            filtered = filtered.filter { it.branch == branchFilter }
        }

        // Status filter — dynamic exact match
        filtered = if (statusFilter == "all") filtered
                   else filtered.filter { it.status == statusFilter }

        // Update stats
        val total = allParcels.size
        val confirmed = allParcels.count { it.status == "confirmed" }
        val pending = allParcels.count { it.status == "pending" }
        val rejected = allParcels.count { it.status == "rejected" }
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
        val statusColor: Int
    )

    // Loaded from config/remarks (+ config/language/ccLang) — see loadCcRemarkOptions().
    // Falls back to this small built-in set if config hasn't loaded yet or is empty.
    private var ccRemarkOptions: List<CcRemarkOption> = listOf(
        CcRemarkOption("✅", "Customer delivery নিতে চান", "confirmed", "✓ Confirmed", android.graphics.Color.parseColor("#16A34A")),
        CcRemarkOption("📵", "Customer ফোন ধরছে না", "pending", "◌ Pending", android.graphics.Color.parseColor("#F59E0B")),
        CcRemarkOption("🔄", "পরে call করতে বলেছেন", "pending", "◌ Pending", android.graphics.Color.parseColor("#F59E0B")),
        CcRemarkOption("📍", "Address ভুল / খুঁজে পাচ্ছি না", "hold_req", "⏸ Hold Request", android.graphics.Color.parseColor("#F97316")),
        CcRemarkOption("🚫", "Customer delivery নেবে না", "rejected", "✗ Rejected", android.graphics.Color.parseColor("#DC2626"))
    )
    private var ccStatusLang: String = "bn"
}
