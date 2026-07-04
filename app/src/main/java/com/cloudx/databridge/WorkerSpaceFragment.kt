package com.cloudx.databridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext


class WorkerSpaceFragment : Fragment() {

    // UI Elements
    private lateinit var tvRoleLabel: TextView
    private lateinit var tvAgentInfo: TextView
    private lateinit var tvTodayCod: TextView
    private lateinit var tvStatTotalValue: TextView
    private lateinit var tvStatConfirmedValue: TextView
    private lateinit var tvStatPendingValue: TextView
    private lateinit var etSearch: EditText
    private lateinit var tvSearchClear: TextView
    private lateinit var tvSearchScan: TextView
    private lateinit var tvSearchCount: TextView
    private lateinit var spinnerRunType: Spinner
    private lateinit var layoutFilterTabs: LinearLayout
    private lateinit var rvParcelList: RecyclerView
    private lateinit var pbProgress: View
    private lateinit var tvEmpty: TextView
    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var tvRunClosedBanner: TextView

    private lateinit var adapter: WorkerParcelAdapter

    private var allParcels = listOf<WorkerParcelItem>()
    private var activeFilter = "all"
    private var searchQuery = ""
    private var selectedRunType = RUN_TYPE_ALL
    private var runTypeOptions = listOf(RunTypeOption(RUN_TYPE_ALL, "All"))
    private var suppressRunTypeEvents = false
    private var loadGeneration = 0
    private var systemId = ""
    // Current worker's Employee ID — attached to remarks so it's clear who left them.
    // Best-effort fetch: remark-writing still works (falls back to "") if this fails.
    private var employeeId = ""

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseDatabase.getInstance()
    private var runsByAgentRef: DatabaseReference? = null
    private var runsByAgentListener: ValueEventListener? = null

    // Direct live listeners on courier/run_routes/{runType}/{todayRunId}, one per run type —
    // catches BOTH new consignments being added to the run AND the run's own status changing,
    // instead of relying solely on runs_by_agentSystemId (which only updates on status changes).
    private val runNodeListeners = mutableMapOf<String, Pair<DatabaseReference, ValueEventListener>>()
    private val runStatusByType = mutableMapOf<String, String>()
    private var lastRunsSnapshot: DataSnapshot? = null

    // Scan result launcher — trim, crop before pipe (|)
    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            val raw = res.data?.getStringExtra("SCAN_RESULT") ?: ""
            val cleaned = raw.trim().split("|").first().trim()
            if (cleaned.isNotBlank()) {
                etSearch.setText(cleaned)
                etSearch.setSelection(cleaned.length)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_worker_space, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupSearch()
        setupScanButton()
        setupRunTypeSpinner()
        setupFilterTabs()
        setupAdapter()
        loadData()
    }

    override fun onDestroyView() {
        detachRunsListener()
        super.onDestroyView()
    }

    private fun initViews(view: View) {
        tvRoleLabel = view.findViewById(R.id.twRoleLabel)
        tvAgentInfo = view.findViewById(R.id.twAgentInfo)
        tvTodayCod = view.findViewById(R.id.twTodayCod)
        tvStatTotalValue = view.findViewById(R.id.twStatTotalValue)
        tvStatConfirmedValue = view.findViewById(R.id.twStatConfirmedValue)
        tvStatPendingValue = view.findViewById(R.id.twStatPendingValue)
        etSearch = view.findViewById(R.id.twSearchInput)
        tvSearchClear = view.findViewById(R.id.twSearchClear)
        tvSearchScan = view.findViewById(R.id.twSearchScan)
        tvSearchCount = view.findViewById(R.id.twSearchCount)
        spinnerRunType = view.findViewById(R.id.spinnerRunType)
        layoutFilterTabs = view.findViewById(R.id.layoutFilterTabs)
        rvParcelList = view.findViewById(R.id.rvParcelList)
        pbProgress = view.findViewById(R.id.twProgressBar)
        tvEmpty = view.findViewById(R.id.twEmptyState)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        swipeRefresh.setColorSchemeResources(R.color.theme_brand_red)
        swipeRefresh.setOnRefreshListener {
            detachRunsListener()
            loadData()
            swipeRefresh.isRefreshing = false
        }
        tvRunClosedBanner = view.findViewById(R.id.twRunClosedBanner)

        // Set user info
        val user = auth.currentUser
        val displayName = user?.displayName ?: "Agent"
        tvRoleLabel.text = "DELIVERY AGENT"
        tvAgentInfo.text = "$displayName · Active"
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

        tvSearchClear.setOnClickListener {
            etSearch.text?.clear()
        }
    }

    private fun setupRunTypeSpinner() {
        bindRunTypeSpinner()
        spinnerRunType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressRunTypeEvents) return
                val nextRunType = runTypeOptions.getOrNull(position)?.key ?: RUN_TYPE_ALL
                if (nextRunType == selectedRunType) return
                selectedRunType = nextRunType
                updateRunStatusBanner()
                pbProgress.visibility = View.VISIBLE
                tvEmpty.visibility = View.GONE
                runsByAgentListener?.let { listener ->
                    runsByAgentRef?.get()?.addOnSuccessListener { snap ->
                        handleRunsSnapshot(snap)
                    }?.addOnFailureListener {
                        pbProgress.visibility = View.GONE
                        tvEmpty.visibility = View.VISIBLE
                        tvEmpty.text = "⚠ Run load failed: ${it.message?.take(60)}"
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun bindRunTypeSpinner() {
        if (!::spinnerRunType.isInitialized) return
        val labels = runTypeOptions.map { it.label }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        suppressRunTypeEvents = true
        spinnerRunType.adapter = adapter
        spinnerRunType.setSelection(runTypeOptions.indexOfFirst { it.key == selectedRunType }.coerceAtLeast(0))
        suppressRunTypeEvents = false
    }

    private fun setupScanButton() {
        tvSearchScan.setOnClickListener {
            try {
                val intent = Intent(
                    requireContext(),
                    com.journeyapps.barcodescanner.CaptureActivity::class.java
                )
                scanLauncher.launch(intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Camera error: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupFilterTabs() {
        layoutFilterTabs.removeAllViews()
        val total       = allParcels.size
        val statusCounts = allParcels.groupingBy { it.status }.eachCount()

        // Reset active filter if it no longer exists in data
        if (activeFilter != "all" && !statusCounts.containsKey(activeFilter)) {
            activeFilter = "all"
        }

        // Ordered chips: pending first, confirmed, then the rest
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
            val label = WorkerParcelAdapter.getStatusConfig(requireContext(), statusKey).label
            filters.add(FilterTab(statusKey, "$label($count)"))
        }

        for (filter in filters) {
            val chip = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_filter_chip, layoutFilterTabs, false) as TextView
            chip.text = filter.label
            chip.tag  = filter.key
            chip.setOnClickListener {
                activeFilter = filter.key
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
            val isActive = chip.tag == activeFilter
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

    private fun setupAdapter() {
        adapter = WorkerParcelAdapter(
            onCall = { item ->
                AutoDialHelper.dial(this, item.phone) // ✅ auto-dial / dialpad / SIM chooser
            },
            onSetRemarks = { item ->
                showWorkerRemarksDialog(item)
            },
            onViewLog = { item ->
                showActionHistoryDialog(item)
            }
        )

        rvParcelList.layoutManager = LinearLayoutManager(requireContext())
        rvParcelList.adapter = adapter
    }

    private fun showWorkerRemarksDialog(item: WorkerParcelItem) {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_worker_remarks, null)
        val tvTitle = view.findViewById<TextView>(R.id.twWorkerRemarkTitle)
        val tvSub = view.findViewById<TextView>(R.id.twWorkerRemarkSub)
        val tvStatusPreview = view.findViewById<TextView>(R.id.twWorkerRemarkStatusPreview)

        val options = listOf(
            WorkerRemarkOption("✅", "Customer কে parcel delivery করা হচ্ছে", "confirmed", "✓ Confirmed", R.color.theme_green),
            WorkerRemarkOption("📵", "Customer ফোন ধরছে না", "verify_req", "⚡ Verify Request", R.color.theme_purple),
            WorkerRemarkOption("📍", "Address খুঁজে পাচ্ছি না", "verify_req", "⚡ Verify Request", R.color.theme_purple),
            WorkerRemarkOption("🚫", "Customer refuse করল", "return_req", "↩ Return Request", R.color.theme_red),
            WorkerRemarkOption("💬", "Customer পাচ্ছি না", "verify_req", "⚡ Verify Request", R.color.theme_purple)
        )

        val optionViews = mutableListOf<View>()
        val layoutOptions = view.findViewById<LinearLayout>(R.id.layoutWorkerRemarkOptions)
        layoutOptions.removeAllViews()

        for (opt in options) {
            val optView = layoutInflater.inflate(R.layout.item_worker_remark_option, layoutOptions, false)
            val tvIcon = optView.findViewById<TextView>(R.id.twRemarkOptIcon)
            val tvText = optView.findViewById<TextView>(R.id.twRemarkOptText)
            val tvAutoTag = optView.findViewById<TextView>(R.id.twRemarkOptAutoTag)
            val selectedIndicator = optView.findViewById<View>(R.id.viewRemarkOptSelected)

            tvIcon.text = opt.icon
            tvText.text = opt.label
            tvAutoTag.text = opt.statusPreview
            tvAutoTag.visibility = View.VISIBLE

            optView.setOnClickListener {
                optionViews.forEach { v ->
                    v.setBackgroundResource(R.drawable.bg_remark_opt_inactive)
                    v.findViewById<TextView>(R.id.twRemarkOptText)
                        .setTextColor(requireContext().getColor(R.color.theme_text_remark_opt))
                    v.findViewById<View>(R.id.viewRemarkOptSelected).visibility = View.GONE
                }
                optView.setBackgroundResource(R.drawable.bg_remark_opt_active)
                optView.findViewById<TextView>(R.id.twRemarkOptText)
                    .setTextColor(requireContext().getColor(R.color.theme_text_remark_opt_selected))
                optView.findViewById<View>(R.id.viewRemarkOptSelected).visibility = View.VISIBLE

                val previewColor = requireContext().getColor(opt.statusColorRes)
                tvStatusPreview.text = opt.statusPreview
                tvStatusPreview.setTextColor(previewColor)
                tvStatusPreview.tag = opt.statusKey

                view.findViewById<TextView>(R.id.btnWorkerRemarkSubmit).isEnabled = true
                view.findViewById<TextView>(R.id.btnWorkerRemarkSubmit).alpha = 1f
            }

            optionViews.add(optView)
            layoutOptions.addView(optView)
        }

        tvTitle.text = "Set Remarks"
        tvSub.text = "${item.id} · ${item.customer} · ${item.phone}"
        tvStatusPreview.text = "Select an option"
        tvStatusPreview.tag = ""

        val btnSubmit = view.findViewById<TextView>(R.id.btnWorkerRemarkSubmit)
        btnSubmit.isEnabled = false
        btnSubmit.alpha = 0.5f
        btnSubmit.setOnClickListener {
            val statusKey = tvStatusPreview.tag as? String ?: ""
            val selectedLabel = optionViews.firstOrNull { v ->
                v.findViewById<View>(R.id.viewRemarkOptSelected).visibility == View.VISIBLE
            }?.findViewById<TextView>(R.id.twRemarkOptText)?.text?.toString() ?: ""

            if (statusKey.isNotBlank() && selectedLabel.isNotBlank()) {
                val timestamp = System.currentTimeMillis()

                // Write remark to Firebase
                val remarkData = mapOf(
                    "agentSystemId" to systemId,
                    "employeeId"    to employeeId,
                    "remarks"       to selectedLabel,
                    "type"          to statusKey,
                    "status"        to statusKey,
                    "remarked_by"   to "worker",
                    "createdAt"     to timestamp,
                    "runId"         to "run_${
                        java.text.SimpleDateFormat("ddMMyy", java.util.Locale.ENGLISH)
                            .format(java.util.Date())
                    }_${systemId}"
                )
                val multiUpdate = mutableMapOf<String, Any>(
                    "courier/remarks_by_consignment/${item.id}/remarks_$timestamp" to remarkData,
                    "courier/consignments/${item.id}/status" to statusKey,
                    "courier/consignments_by_phone/${item.phone}/${item.id}" to statusKey
                )
                db.reference.updateChildren(multiUpdate)

                // Local update
                val updatedParcels = allParcels.map {
                    if (it.id == item.id) {
                        val newHistory = it.history + HistoryEntry(
                            action = statusKey.uppercase().replace("_", " "),
                            remark = selectedLabel,
                            time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date()),
                            author = auth.currentUser?.displayName ?: "Agent",
                            authorRole = "agent"
                        )
                        it.copy(
                            status = statusKey,
                            remarks = selectedLabel,
                            validationRequest = (statusKey == "verify_req"),
                            history = newHistory
                        )
                    } else it
                }
                allParcels = updatedParcels
                applyFilters()
            }
            dialog.dismiss()
        }

        view.findViewById<TextView>(R.id.btnWorkerRemarkCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun showActionHistoryDialog(item: WorkerParcelItem) {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_action_history, null)
        val tvTitle = view.findViewById<TextView>(R.id.twHistoryTitle)
        val tvSub = view.findViewById<TextView>(R.id.twHistorySub)
        val layoutTimeline = view.findViewById<LinearLayout>(R.id.layoutTimeline)

        tvTitle.text = "Action History"
        tvSub.text = "${item.id} · ${item.customer}"

        layoutTimeline.removeAllViews()

        val historyEntries = mutableListOf<HistoryEntry>()

        if (item.history.isEmpty()) {
            historyEntries.add(
                HistoryEntry("ASSIGNED", "${auth.currentUser?.displayName ?: "Agent"} কে assign করা হয়েছে", "${item.time}", "System", "system")
            )
            if (item.validationNote.isNotBlank()) {
                historyEntries.add(
                    HistoryEntry("VERIFY REQUEST", item.validationNote, item.time, "আপনি", "agent")
                )
            }
            if (item.ccRemark.isNotBlank()) {
                historyEntries.add(
                    HistoryEntry("DELIVERY REQUEST", item.ccRemark, item.ccRemarkTime, "${item.ccRemarkAuthor} · CC", "cc")
                )
            }
            if (item.remarks.isNotBlank() && !item.validationRequest) {
                historyEntries.add(
                    HistoryEntry("CONFIRMED", item.remarks, "Just now", "আপনি", "agent")
                )
            }
        } else {
            historyEntries.addAll(item.history)
        }

        if (historyEntries.isEmpty()) {
            val emptyView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_timeline_empty, layoutTimeline, false)
            layoutTimeline.addView(emptyView)
        } else {
            for ((index, entry) in historyEntries.withIndex()) {
                val timelineView = layoutInflater.inflate(R.layout.item_timeline_entry, layoutTimeline, false)
                val dotColor = when (entry.authorRole) {
                    "cc" -> R.color.theme_accent
                    "system" -> R.color.theme_text_muted
                    else -> R.color.theme_accent
                }
                val statusColor = when (entry.authorRole) {
                    "cc" -> R.color.theme_accent
                    "system" -> R.color.theme_text_muted
                    else -> R.color.theme_accent
                }

                val tvDot = timelineView.findViewById<View>(R.id.viewTimelineDot)
                val tvLine = timelineView.findViewById<View>(R.id.viewTimelineLine)
                val tvStatus = timelineView.findViewById<TextView>(R.id.twTimelineStatus)
                val tvRemark = timelineView.findViewById<TextView>(R.id.twTimelineRemark)
                val tvMeta = timelineView.findViewById<TextView>(R.id.twTimelineMeta)

                tvDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(dotColor)
                ))
                tvLine.visibility = if (index < historyEntries.size - 1) View.VISIBLE else View.GONE

                tvStatus.text = entry.action
                tvStatus.setTextColor(requireContext().getColor(statusColor))

                tvRemark.text = entry.remark
                tvRemark.visibility = if (entry.remark.isNotBlank()) View.VISIBLE else View.GONE

                val authorLabel = if (entry.authorRole == "cc") "${entry.author}" else entry.author
                tvMeta.text = "${entry.time} · ${authorLabel}"

                layoutTimeline.addView(timelineView)
            }
        }

        view.findViewById<TextView>(R.id.btnHistoryClose).setOnClickListener {
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun loadData() {
        pbProgress.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        val uid = auth.currentUser?.uid ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                systemId = withContext(Dispatchers.IO) {
                    db.reference.child("users/$uid/profile/company_info/system_id")
                        .get().await().getValue(String::class.java)?.trim()
                } ?: run {
                    pbProgress.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "⚠ System ID পাওয়া যায়নি"
                    return@launch
                }

                attachRunsListener(systemId)

                // Best-effort employeeId fetch for remark attribution — doesn't gate the run listener
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        employeeId = withContext(Dispatchers.IO) {
                            db.reference.child("users/$uid/profile/company_info/employee_id")
                                .get().await().getValue(String::class.java)?.trim().orEmpty()
                        }
                    } catch (e: Exception) { /* remark writing still works without it */ }
                }
            } catch (e: Exception) {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "⚠ Load failed: ${e.message?.take(60)}"
                pbProgress.visibility = View.GONE
            }
        }
    }

    private fun attachRunsListener(systemId: String) {
        detachRunsListener()
        val ref = db.reference.child("courier/runs_by_agentSystemId/$systemId")
        runsByAgentRef = ref
        runsByAgentListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                handleRunsSnapshot(snapshot)
            }

            override fun onCancelled(error: DatabaseError) {
                if (!isAdded) return
                pbProgress.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "⚠ Run load failed: ${error.message.take(60)}"
            }
        }
        ref.addValueEventListener(runsByAgentListener!!)
    }

    private fun detachRunsListener() {
        runsByAgentListener?.let { listener -> runsByAgentRef?.removeEventListener(listener) }
        runsByAgentListener = null
        runsByAgentRef = null

        runNodeListeners.values.forEach { (ref, listener) -> ref.removeEventListener(listener) }
        runNodeListeners.clear()
        runStatusByType.clear()
        lastRunsSnapshot = null
    }

    private fun handleRunsSnapshot(runSnap: DataSnapshot) {
        if (!isAdded) return
        lastRunsSnapshot = runSnap

        val runTypes = runSnap.children
            .mapNotNull { typeSnap ->
                val key = typeSnap.key?.trim().orEmpty()
                key.takeIf { it.isNotBlank() && typeSnap.children.any() }
            }
            .distinct()
            .sortedWith(compareBy<String> { runTypeSortIndex(it) }.thenBy { it })

        runTypeOptions = listOf(RunTypeOption(RUN_TYPE_ALL, "All")) +
            runTypes.map { RunTypeOption(it, formatRunTypeLabel(it)) }

        if (selectedRunType != RUN_TYPE_ALL && selectedRunType !in runTypes) {
            selectedRunType = RUN_TYPE_ALL
        }
        bindRunTypeSpinner()
        syncRunNodeListeners(runTypes)

        if (!runSnap.exists() || runTypes.isEmpty()) {
            allParcels = emptyList()
            setupFilterTabs()
            applyFilters()
            pbProgress.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "📭\n\nকোনো run নেই"
            return
        }

        triggerReload(runSnap)
    }

    private fun triggerReload(runSnap: DataSnapshot) {
        val generation = ++loadGeneration
        pbProgress.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val parcels = loadParcelsForSelectedRunType(runSnap)
                if (!isAdded || generation != loadGeneration) return@launch
                allParcels = parcels.sortedWith(compareBy<WorkerParcelItem> { it.time }.thenBy { it.id })
                setupFilterTabs()
                applyFilters()
            } catch (e: Exception) {
                if (!isAdded || generation != loadGeneration) return@launch
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "⚠ Load failed: ${e.message?.take(60)}"
            } finally {
                if (isAdded && generation == loadGeneration) {
                    pbProgress.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Attaches a direct ValueEventListener on courier/run_routes/{runType}/{todayRunId} for every
     * active run type, so BOTH a new consignment being added to the run's `consignments` map AND
     * the run's own `status` flipping (e.g. to "closed") are reflected instantly — unlike
     * runs_by_agentSystemId, which only changes when the run's status field changes.
     */
    private fun syncRunNodeListeners(runTypes: List<String>) {
        val todayRunId = computeTodayRunId()

        // Drop listeners for run types that no longer exist for this agent today.
        val stale = runNodeListeners.keys - runTypes.toSet()
        stale.forEach { rt ->
            runNodeListeners.remove(rt)?.let { (ref, listener) -> ref.removeEventListener(listener) }
            runStatusByType.remove(rt)
        }

        // Attach a listener for every run type not already being watched.
        runTypes.forEach { rt ->
            if (runNodeListeners.containsKey(rt)) return@forEach
            val ref = db.reference.child("courier/run_routes/$rt/$todayRunId")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded) return
                    val status = snapshot.child("status").getValue(String::class.java)
                        ?.trim().orEmpty().ifBlank { "open" }
                    runStatusByType[rt] = status
                    updateRunStatusBanner()
                    lastRunsSnapshot?.let { triggerReload(it) }
                }
                override fun onCancelled(error: DatabaseError) { /* transient — next write will retry */ }
            }
            ref.addValueEventListener(listener)
            runNodeListeners[rt] = ref to listener
        }

        updateRunStatusBanner()
    }

    /** Shows a "closed" banner only for the run type(s) currently relevant to the selected filter. */
    private fun updateRunStatusBanner() {
        if (!::tvRunClosedBanner.isInitialized) return
        val relevantTypes = if (selectedRunType == RUN_TYPE_ALL) runStatusByType.keys.toList() else listOf(selectedRunType)
        val closedTypes = relevantTypes.filter { runStatusByType[it].equals("closed", ignoreCase = true) }

        if (closedTypes.isEmpty()) {
            tvRunClosedBanner.visibility = View.GONE
            return
        }
        tvRunClosedBanner.visibility = View.VISIBLE
        tvRunClosedBanner.text = if (selectedRunType == RUN_TYPE_ALL)
            "🔒 বন্ধ: ${closedTypes.joinToString(", ") { formatRunTypeLabel(it) }}"
        else "🔒 আজকের ${formatRunTypeLabel(selectedRunType)} বন্ধ হয়ে গেছে"
    }

    /** Deterministic today's run ID: run_{ddMMyy}_{systemId} — same formula used everywhere a run ID is needed. */
    private fun computeTodayRunId(): String {
        val today = java.util.Calendar.getInstance()
        val ddMMyy = String.format(
            "%02d%02d%02d",
            today.get(java.util.Calendar.DAY_OF_MONTH),
            today.get(java.util.Calendar.MONTH) + 1,
            today.get(java.util.Calendar.YEAR) % 100
        )
        return "run_${ddMMyy}_${systemId}"
    }

    private suspend fun loadParcelsForSelectedRunType(runSnap: DataSnapshot): List<WorkerParcelItem> = coroutineScope {
        val todayRunId = computeTodayRunId()

        // Determine which run types to fetch
        val runTypesToFetch = if (selectedRunType == RUN_TYPE_ALL) {
            runSnap.children.mapNotNull { it.key?.trim()?.takeIf { k -> k.isNotBlank() } }.distinct()
        } else {
            listOf(selectedRunType)
        }

        // Step 2: fetch today's run for each type IN PARALLEL using deterministic run ID
        val consignmentRefs = linkedMapOf<String, ConsignmentRunRef>()
        val runFetches = runTypesToFetch.map { runType ->
            async(Dispatchers.IO) {
                val snap = db.reference.child("courier/run_routes/$runType/$todayRunId/consignments")
                    .get().await()
                Pair(runType, snap)
            }
        }
        runFetches.awaitAll().forEach { (runType, consSnap) ->
            consSnap.children.forEach { c ->
                val cId = c.key ?: return@forEach
                val routeStatus = c.getValue(String::class.java) ?: readString(c, "status")
                consignmentRefs[cId] = ConsignmentRunRef(runType, todayRunId, routeStatus)
            }
        }

        if (consignmentRefs.isEmpty()) return@coroutineScope emptyList()

        // Step 3: fetch consignment details + remarks history for EVERY consignment IN PARALLEL.
        data class ItemFetch(val cId: String, val runRef: ConsignmentRunRef, val detailSnap: DataSnapshot, val remarksSnap: DataSnapshot)
        val itemFetches = consignmentRefs.map { (cId, runRef) ->
            async(Dispatchers.IO) {
                val detailSnap = db.reference.child("courier/consignments/$cId").get().await()
                val remarksSnap = db.reference.child("courier/remarks_by_consignment/$cId")
                    .limitToLast(5).get().await()
                ItemFetch(cId, runRef, detailSnap, remarksSnap)
            }
        }

        val parcels = mutableListOf<WorkerParcelItem>()
        val statusBackfills = mutableMapOf<String, Any?>()
        itemFetches.awaitAll().forEach { fetch ->
            val (cId, runRef, detailSnap, remarksSnap) = fetch
            if (!detailSnap.exists()) return@forEach

            val name = readString(detailSnap, "recipientName")
            val phone = readString(detailSnap, "recipientPhone")
            val address = readString(detailSnap, "recipientAddress")
            val cod = readCod(detailSnap)
            val hub = readString(detailSnap, "deliveryHub")
            val sourceStatus = readString(detailSnap, "status").ifBlank { "pending" }
            if (runRef.routeStatus.isNotBlank() && runRef.routeStatus != sourceStatus) {
                statusBackfills["courier/run_routes/${runRef.runType}/${runRef.runId}/consignments/$cId"] = sourceStatus
            }

            val history = remarksSnap.children.mapNotNull { r ->
                val type = readString(r, "type").ifBlank { return@mapNotNull null }
                val rStatus = readString(r, "status")
                val createdAt = r.child("createdAt").getValue(Long::class.java) ?: 0L
                val timeStr = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                    .format(java.util.Date(createdAt))
                HistoryEntry(
                    action = type.uppercase(),
                    remark = rStatus,
                    time = timeStr,
                    author = "",
                    authorRole = "agent"
                )
            }

            val lastRemark = history.lastOrNull()?.remark ?: ""
            parcels.add(
                WorkerParcelItem(
                    id = cId,
                    customer = name,
                    phone = phone,
                    address = address,
                    cod = cod,
                    status = sourceStatus,
                    remarks = lastRemark,
                    validationRequest = sourceStatus == "verify_req",
                    validationNote = if (sourceStatus == "verify_req") lastRemark else "",
                    time = hub,
                    history = history
                )
            )
        }

        if (statusBackfills.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                runCatching { db.reference.updateChildren(statusBackfills).await() }
            }
        }

        parcels
    }

    private fun readString(snap: DataSnapshot, child: String): String {
        return snap.child(child).getValue(String::class.java)?.trim().orEmpty()
    }

    private fun readCod(snap: DataSnapshot): Int {
        return snap.child("collectableAmount").getValue(String::class.java)
            ?.toDoubleOrNull()?.toInt()
            ?: snap.child("collectableAmount").getValue(Long::class.java)?.toInt()
            ?: snap.child("collectableAmount").getValue(Double::class.java)?.toInt()
            ?: 0
    }

    private fun applyFilters() {
        var filtered = allParcels

        // Search filter — phone, ID, customer name, or COD amount
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            filtered = filtered.filter {
                it.phone.contains(q) ||
                it.id.lowercase().contains(q) ||
                it.customer.lowercase().contains(q) ||
                it.cod.toString().contains(q) // COD search
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

        // Status filter — dynamic exact match
        filtered = if (activeFilter == "all") filtered
                   else filtered.filter { it.status == activeFilter }

        updateCounts()

        adapter.submitList(filtered)
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = if (allParcels.isEmpty()) "📭\n\nNo parcels found"
            else if (searchQuery.isNotBlank()) "📭\n\nNo results for \"$searchQuery\""
            else "📭\n\nNo ${activeFilter} parcels"
    }

    private fun updateCounts() {
        val total = allParcels.size
        val confirmed = allParcels.count { it.status == "confirmed" }
        val pending = allParcels.count { it.status == "pending" || it.status == "verify_req" || it.status == "delivery_req" }
        val totalCod = allParcels.filter { it.status == "confirmed" }.sumOf { it.cod }

        tvStatTotalValue.text = total.toString()
        tvStatConfirmedValue.text = confirmed.toString()
        tvStatPendingValue.text = pending.toString()
        tvTodayCod.text = "৳$totalCod"
    }


    data class FilterTab(val key: String, val label: String)
    data class WorkerRemarkOption(
        val icon: String,
        val label: String,
        val statusKey: String,
        val statusPreview: String,
        val statusColorRes: Int
    )

    data class RunTypeOption(val key: String, val label: String)
    data class ConsignmentRunRef(
        val runType: String,
        val runId: String,
        val routeStatus: String
    )

    companion object {
        private const val RUN_TYPE_ALL = "all"
        private val RUN_TYPE_ORDER = listOf("delivery_run", "pickup_run", "return_run")
        // Run ID shape: run_{ddmmyy}_{employeeId} — ddmmyy is always exactly 6 zero-padded digits.
        private val RUN_ID_PATTERN = Regex("^run_(\\d{6})_(.+)$")

        private fun runTypeSortIndex(runType: String): Int {
            val index = RUN_TYPE_ORDER.indexOf(runType)
            return if (index >= 0) index else Int.MAX_VALUE
        }

        private fun formatRunTypeLabel(runType: String): String {
            return runType.split("_")
                .filter { it.isNotBlank() }
                .joinToString(" ") { part ->
                    part.replaceFirstChar { ch ->
                        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                    }
                }
        }

        /**
         * Extracts the date portion from a run ID of the form "run_{ddMMyy}_{systemId}"
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

        private fun startOfLocalDay(anchorMs: Long = System.currentTimeMillis()): Long {
            return java.util.Calendar.getInstance().apply {
                timeInMillis = anchorMs
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        private fun endOfLocalDay(dayStartMs: Long): Long {
            return java.util.Calendar.getInstance().apply {
                timeInMillis = dayStartMs
                set(java.util.Calendar.HOUR_OF_DAY, 23)
                set(java.util.Calendar.MINUTE, 59)
                set(java.util.Calendar.SECOND, 59)
                set(java.util.Calendar.MILLISECOND, 999)
            }.timeInMillis
        }
    }
}
