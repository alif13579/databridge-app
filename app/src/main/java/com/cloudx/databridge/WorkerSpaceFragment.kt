package com.cloudx.databridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import coil.load
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
import androidx.recyclerview.widget.ItemTouchHelper
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
    private lateinit var tvCollapseArrow: TextView
    private lateinit var layoutStatsCollapsible: LinearLayout
    private var isHeaderExpanded = false // starts collapsed to save screen space

    private lateinit var adapter: WorkerParcelAdapter
    private var workerStatusLang: String = "bn"

    private var allParcels = listOf<WorkerParcelItem>()
    private var activeFilter = "all"
    private var searchQuery = ""
    private var selectedRunType = RUN_TYPE_ALL
    private var runTypeOptions = listOf(RunTypeOption(RUN_TYPE_ALL, "All"))
    private var suppressRunTypeEvents = false
    private var loadGeneration = 0
    private var systemId = ""
    private var userId = ""
    private var agentPhone = ""
    private var sortMode: String = "attempt" // "attempt" (default, most-attempted first) or "aging" (oldest first)
    private lateinit var tvSortByDropdown: TextView

    // uid -> display name, resolved on demand from users/{uid}/profile/name and cached so
    // repeated remarks by the same author (worker or CC agent) don't refetch. Cleared on
    // pull-to-refresh alongside the other per-session caches.
    private val uidNameCache = mutableMapOf<String, String>()
    private val uidPhotoCache = mutableMapOf<String, String>()

    /** Resolves [uid] to a display name via users/{uid}/profile/name — direct O(1) access,
     *  cached in [uidNameCache] after the first lookup. Falls back to the raw uid if the
     *  profile/name is missing or the fetch fails. */
    private suspend fun resolveUserName(uid: String): String {
        if (uid.isBlank()) return "Agent"
        uidNameCache[uid]?.let { return it }
        val snap = withContext(Dispatchers.IO) {
            runCatching { db.reference.child("users/$uid/profile").get().await() }.getOrNull()
        }
        val name = snap?.child("name")?.getValue(String::class.java)?.trim()?.takeIf { it.isNotBlank() } ?: uid
        uidNameCache[uid] = name
        uidPhotoCache[uid] = snap?.child("photo_url")?.getValue(String::class.java)?.trim().orEmpty()
        return name
    }

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

        // Restore the last-used sort mode — otherwise it'd silently reset to the
        // default every time this fragment is recreated (navigating away and back).
        loadSortPref()

        // Notification tap-to-navigate: expand this parcel after data loads
        arguments?.getString("expand_parcel_id")?.takeIf { it.isNotBlank() }?.let {
            pendingExpandParcelId = it
        }

        initViews(view)
        updateSortByLabel()
        setupCollapseToggle()
        setupSearch()
        setupScanButton()
        setupRunTypeSpinner()
        setupFilterTabs()
        setupAdapter()
        loadRemarkOptions()
        loadData()
    }

    override fun onDestroyView() {
        detachRunsListener()
        super.onDestroyView()
    }

    private fun initViews(view: View) {
        tvRoleLabel = view.findViewById(R.id.twRoleLabel)
        tvAgentInfo = view.findViewById(R.id.twAgentInfo)
        tvSortByDropdown = view.findViewById(R.id.tvSortByDropdown)
        tvSortByDropdown.setOnClickListener { showSortByDropdown() }
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
            uidNameCache.clear()
            detachRunsListener()
            loadRemarkOptions()
            loadData()
            swipeRefresh.isRefreshing = false
        }
        tvRunClosedBanner = view.findViewById(R.id.twRunClosedBanner)
        tvCollapseArrow = view.findViewById(R.id.twCollapseArrow)
        layoutStatsCollapsible = view.findViewById(R.id.layoutStatsCollapsible)

        // Set user info
        val user = auth.currentUser
        val displayName = user?.displayName ?: "Agent"
        tvRoleLabel.text = "DELIVERY AGENT"
        tvAgentInfo.text = "$displayName · Active"
    }

    /** Toggles the Stats grid (Total/Confirmed/Pending) between hidden and visible —
     *  collapsed by default so the header doesn't eat screen space; mirrors
     *  CallCenterFragment's setupCollapseToggle(). */
    private fun setupCollapseToggle() {
        layoutStatsCollapsible.visibility = if (isHeaderExpanded) View.VISIBLE else View.GONE
        tvCollapseArrow.text = if (isHeaderExpanded) "▲" else "▼"
        tvCollapseArrow.setOnClickListener {
            isHeaderExpanded = !isHeaderExpanded
            layoutStatsCollapsible.visibility = if (isHeaderExpanded) View.VISIBLE else View.GONE
            tvCollapseArrow.text = if (isHeaderExpanded) "▲" else "▼"
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
                val intent = Intent(requireContext(), MlKitScannerActivity::class.java)
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

        // Chips sorted by config/statusMeta/{key}/priority (admin-managed in
        // ConfigStatusesFragment) — higher priority first. Ties broken alphabetically
        // for a stable order; unconfigured statuses (priority 0) sort last together.
        val sortedEntries = statusCounts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { StatusMetaCache.entries[it.key]?.priority ?: 0 }
                .thenBy { it.key }
        )

        val filters = mutableListOf(FilterTab("all", "All($total)"))
        sortedEntries.forEach { (statusKey, count) ->
            val label = WorkerParcelAdapter.getStatusConfig(requireContext(), statusKey, workerStatusLang).label
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
        val ctx = requireContext()
        for (i in 0 until layoutFilterTabs.childCount) {
            val chip = layoutFilterTabs.getChildAt(i) as TextView
            val statusKey = chip.tag as? String ?: continue
            val isActive = statusKey == activeFilter
            val metaColor: Int? = if (statusKey == "all") null
                else StatusMetaCache.entries[statusKey]?.color
            chip.isSelected = isActive
            if (isActive && metaColor != null) {
                try {
                    chip.background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(metaColor); cornerRadius = 24f
                    }
                    chip.setTextColor(ctx.getColor(android.R.color.white))
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

    private fun setupAdapter() {
        adapter = WorkerParcelAdapter(
            onCall = { item ->
                AutoDialHelper.dial(this, item.phone) // ✅ auto-dial / dialpad / SIM chooser
            },
            onSetRemarks = { item ->
                showWorkerRemarksDialog(item)
            },
            onLongPress = { item ->
                showActionHistoryDialog(item)
            }
        )

        rvParcelList.layoutManager = LinearLayoutManager(requireContext())
        rvParcelList.adapter = adapter

        // Swipe shortcuts: right = call, left = remarks. Card always snaps back after firing.
        ItemTouchHelper(
            SwipeActionCallback(
                context = requireContext(),
                onSwipeRight = { position ->
                    adapter.currentList.getOrNull(position)?.let { item ->
                        AutoDialHelper.dial(this, item.phone)
                    }
                },
                onSwipeLeft = { position ->
                    adapter.currentList.getOrNull(position)?.let { item ->
                        showWorkerRemarksDialog(item)
                    }
                }
            )
        ).attachToRecyclerView(rvParcelList)
    }

    /**
     * Loads remark options for the "Set Remarks" sheet from config/remarks (admin-managed in
     * ConfigRemarksFragment) instead of a fixed hardcoded list. Each remark's target_status is
     * resolved against config/statusMeta for its display label + color. Falls back to the
     * built-in default list (see remarkOptions' initializer) if config is empty/unreachable.
     */
    private fun loadRemarkOptions() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                StatusMetaCache.refresh()

                val langValue = withContext(Dispatchers.IO) {
                    db.reference.child("config/language/workerLang").get().await()
                        .getValue(String::class.java)
                }?.trim().orEmpty().ifBlank { "bn_bn" }
                val (remarkLang, statusLang) = parseLangPair(langValue)
                workerStatusLang = statusLang

                val remarksSnap = withContext(Dispatchers.IO) {
                    db.reference.child("config/remarks_worker").get().await()
                }
                val templatesSnap = withContext(Dispatchers.IO) {
                    db.reference.child("config/whatsappTemplates").get().await()
                }
                val loadedTemplates = mutableMapOf<String, ConfigState.WhatsAppTemplate>()
                templatesSnap.children.forEach { t ->
                    val tid  = t.key ?: return@forEach
                    val name = t.child("name").getValue(String::class.java) ?: ""
                    val body = t.child("body").getValue(String::class.java) ?: ""
                    loadedTemplates[tid] = ConfigState.WhatsAppTemplate(tid, name, body)
                }
                whatsappTemplatesCache = loadedTemplates
                data class FetchedRemark(val option: WorkerRemarkOption, val priority: Int)
                val fetched = mutableListOf<FetchedRemark>()
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
                        fetched.add(FetchedRemark(
                            WorkerRemarkOption(
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
                    remarkOptions = fetched.sortedByDescending { it.priority }.map { it.option }
                    if (::adapter.isInitialized) {
                        adapter.statusLang = workerStatusLang
                        adapter.notifyDataSetChanged()
                    }
                    setupFilterTabs()
                }
            } catch (e: Exception) {
                Log.e("WorkerSpace", "Failed to load remark options from config, using defaults", e)
            }
        }
    }

    private fun showWorkerRemarksDialog(item: WorkerParcelItem) {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_worker_remarks, null)
        val tvTitle = view.findViewById<TextView>(R.id.twWorkerRemarkTitle)
        val tvSub = view.findViewById<TextView>(R.id.twWorkerRemarkSub)
        val tvStatusPreview = view.findViewById<TextView>(R.id.twWorkerRemarkStatusPreview)

        val options = remarkOptions
        val optionViews = mutableListOf<View>()
        val layoutOptions = view.findViewById<LinearLayout>(R.id.layoutWorkerRemarkOptions)
        layoutOptions.removeAllViews()

        if (options.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "⚠ Config-এ কোনো remark সেট করা নেই। Admin-কে config/remarks_worker-এ remark যোগ করতে বলুন।\n\nনোট হিসেবে লিখতে পারেন:"
            tv.textSize = 13f
            tv.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
            tv.setPadding(0, 24, 0, 12)
            layoutOptions.addView(tv)

            val etNote = android.widget.EditText(requireContext()).apply {
                hint = "এখানে note লিখুন..."
                textSize = 13f
                minLines = 3
                background = requireContext().getDrawable(R.drawable.bg_input_rounded)
                setPadding(24, 20, 24, 20)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16 }
            }
            layoutOptions.addView(etNote)

            val btnSaveNote = android.widget.Button(requireContext())
            btnSaveNote.text = "Note Save করুন"
            btnSaveNote.setOnClickListener {
                val noteText = etNote.text.toString().trim()
                if (noteText.isBlank()) return@setOnClickListener
                val timestamp = System.currentTimeMillis()
                val remarkData = mapOf(
                    "agentSystemId" to systemId,
                    "userId"        to userId,
                    "remarks"       to noteText,
                    "note"          to noteText,
                    "status"        to "",
                    "remarked_by"   to "worker",
                    "createdAt"     to timestamp,
                    "runId"         to "run_${
                        java.text.SimpleDateFormat("ddMMyy", java.util.Locale.ENGLISH).format(java.util.Date())
                    }_${systemId}"
                )
                db.reference.child("courier/remarks_by_consignment/${item.id}/remarks_$timestamp")
                    .setValue(remarkData)
                android.widget.Toast.makeText(requireContext(), "✓ Note saved", android.widget.Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            layoutOptions.addView(btnSaveNote)
            dialog.show()
            return
        }

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

                val previewColor = opt.statusColor
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

                // Resolve which option was picked (for its optional WhatsApp template)
                val selectedOption = options.firstOrNull { it.label == selectedLabel && it.statusKey == statusKey }
                val templateId = selectedOption?.templateId.orEmpty()
                var sentViaRemarkTemplate = false
                if (templateId.isNotBlank() && WhatsAppSender.isEnabled(requireContext())) {
                    val template = whatsappTemplatesCache[templateId]
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
                        sentViaRemarkTemplate = true
                    }
                }

                // Write remark and status as SEPARATE Firebase operations (not one atomic
                // multi-path update) so the remark always gets saved even if the status/
                // consignments write gets rejected by a role-restricted rule.
                val remarkData = mapOf(
                    "agentSystemId" to systemId,
                    "userId"        to userId,
                    "remarks"       to selectedLabel,
                    "status"        to statusKey,
                    "remarked_by"   to "worker",
                    "createdAt"     to timestamp,
                    "runId"         to "run_${
                        java.text.SimpleDateFormat("ddMMyy", java.util.Locale.ENGLISH)
                            .format(java.util.Date())
                    }_${systemId}"
                )
                db.reference.child("courier/remarks_by_consignment/${item.id}/remarks_$timestamp")
                    .setValue(remarkData)
                    .addOnFailureListener { e ->
                        FirebaseErrorLogger.log(
                            screen = "WorkerSpaceFragment", action = "remark_write",
                            errorMessage = e.message ?: "unknown",
                            extra = mapOf("consignmentId" to item.id, "userId" to userId)
                        )
                        android.widget.Toast.makeText(
                            requireContext(), "⚠ Remark save হয়নি: ${e.message}", android.widget.Toast.LENGTH_LONG
                        ).show()
                    }

                // Parcel status (courier/consignments/{id}/status) is a SEPARATE concept from
                // remark status and is NEVER written/changed from here — only the remark's own
                // "status" field (already saved as part of remarkData above) represents this.

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
                            remarkStatus = statusKey,
                            remarks = selectedLabel,
                            validationRequest = (statusKey == "verify_req"),
                            history = newHistory
                        )
                    } else it
                }
                allParcels = updatedParcels
                applyFilters()

                // Global fallback template (Settings → Automatic WhatsApp Sender) — only fires
                // if this specific remark had NO linked template above, so a customer never
                // gets two WhatsApp messages for one remark.
                if (!sentViaRemarkTemplate) {
                    WhatsAppSender.sendIfEnabled(
                        this@WorkerSpaceFragment,
                        item.phone,
                        mapOf(
                            "customer_name"  to item.customer,
                            "parcel_value"   to item.cod.toString(),
                            "address"        to item.address,
                            "consignment_id" to item.id,
                            "agent_phone"    to agentPhone
                        )
                    )
                }
            }
            dialog.dismiss()
        }

        view.findViewById<TextView>(R.id.btnWorkerRemarkCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

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

    private fun showActionHistoryDialog(item: WorkerParcelItem) {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_action_history, null)
        val tvTitle = view.findViewById<TextView>(R.id.twHistoryTitle)
        val tvSub = view.findViewById<TextView>(R.id.twHistorySub)
        val layoutTimeline = view.findViewById<LinearLayout>(R.id.layoutTimeline)
        val tvOvStatus = view.findViewById<TextView>(R.id.twOverviewStatus)
        val tvOvCreatedAt = view.findViewById<TextView>(R.id.twOverviewCreatedAt)
        val tvOvUpdatedAt = view.findViewById<TextView>(R.id.twOverviewUpdatedAt)
        val tvOvAge = view.findViewById<TextView>(R.id.twOverviewAge)

        tvTitle.text = "Action History"
        tvSub.text = "${item.id} · ${item.customer}"

        // Overview
        val cfg = WorkerParcelAdapter.getStatusConfig(requireContext(), item.status, "bn")
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

        // Always lead with the parcel's actual creation — the true start of its journey.
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
                val statusCfg = WorkerParcelAdapter.getStatusConfig(
                    requireContext(),
                    entry.action.lowercase().replace(" ", "_"),
                    workerStatusLang
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

    private fun loadData() {
        pbProgress.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        val uid = auth.currentUser?.uid ?: return
        userId = uid

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

                // Best-effort: agentPhone for WhatsApp template
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val profileSnap = withContext(Dispatchers.IO) {
                            db.reference.child("users/$uid/profile").get().await()
                        }
                        agentPhone = profileSnap.child("phone")
                            .getValue(String::class.java)?.trim().orEmpty()
                            .ifBlank {
                                profileSnap.child("company_info/phone")
                                    .getValue(String::class.java)?.trim().orEmpty()
                            }
                    } catch (e: Exception) { /* WhatsApp template still works without agentPhone */ }
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

        remarkNodeListeners.values.forEach { (ref, listener) -> ref.removeEventListener(listener) }
        remarkNodeListeners.clear()
    }

    // Per-parcel remark listeners — keyed by consignmentId, replaced on every full reload.
    private val remarkNodeListeners = mutableMapOf<String, Pair<DatabaseReference, ValueEventListener>>()
    // Tracks last-seen remark timestamp per consignment to detect genuinely new CC remarks
    private val workerLastSeenRemarkAt = mutableMapOf<String, Long>()
    // Parcel ID to expand after data loads (set when navigating from a notification tap).
    private var pendingExpandParcelId: String? = null

    /**
     * Attaches a ValueEventListener on courier/remarks_by_consignment/{cId} for every loaded
     * parcel. When a new remark arrives (from CC or another worker), we update that single
     * parcel's history in allParcels and re-apply filters — no full Firebase round-trip needed.
     */
    private fun syncRemarkListeners(currentIds: Set<String>) {
        // Drop listeners for parcels no longer in the current run.
        val stale = remarkNodeListeners.keys - currentIds
        stale.forEach { cId ->
            remarkNodeListeners.remove(cId)?.let { (ref, listener) -> ref.removeEventListener(listener) }
        }

        // Attach a listener for every parcel not already being watched.
        currentIds.forEach { cId ->
            if (remarkNodeListeners.containsKey(cId)) return@forEach
            val ref = db.reference.child("courier/remarks_by_consignment/$cId")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded) return
                    val ctx = context ?: return
                    viewLifecycleOwner.lifecycleScope.launch {
                        data class RawEntry(
                            val rStatus: String, val rLabel: String, val rNote: String, val timeStr: String,
                            val remarkedBy: String, val rUserId: String, val createdAt: Long
                        )

                        // ── New CC-remark notification ────────────────────────────
                        // Find the latest remark overall (by createdAt); notify only
                        // if it's from a CC agent (remarkedBy == "support") and is
                        // genuinely new (not the initial listener fire).
                        val sortedByTime = snapshot.children.sortedByDescending {
                            it.child("createdAt").getValue(Long::class.java) ?: 0L
                        }
                        val latestSnap = sortedByTime.firstOrNull()
                        val latestCreatedAt = latestSnap?.child("createdAt")?.getValue(Long::class.java) ?: 0L
                        val prevAt = workerLastSeenRemarkAt[cId] ?: 0L
                        if (latestCreatedAt > prevAt && prevAt > 0L) {
                            val remarkedBy = latestSnap?.child("remarked_by")?.getValue(String::class.java)?.trim().orEmpty()
                            if (remarkedBy == "support") {
                                val parcel = allParcels.firstOrNull { it.id == cId }
                                val customer = parcel?.customer?.takeIf { it.isNotBlank() } ?: cId
                                val remarkText = latestSnap?.child("remarks")?.getValue(String::class.java)?.trim().orEmpty()
                                val remarkStatus = latestSnap?.child("status")?.getValue(String::class.java)?.trim().orEmpty()
                                val message = when {
                                    remarkText.isNotBlank() -> remarkText
                                    remarkStatus.isNotBlank() -> WorkerParcelAdapter.getStatusConfig(ctx, remarkStatus, workerStatusLang).label
                                    else -> "CC থেকে নতুন রিমার্ক এসেছে"
                                }
                                AppNotificationManager.add(
                                    ctx,
                                    AppNotificationManager.NotifItem(
                                        title = "CC Remark — $customer",
                                        message = message,
                                        type = "remark",
                                        parcelId = cId,
                                        scope = "worker"
                                    )
                                )
                            }
                        }
                        workerLastSeenRemarkAt[cId] = latestCreatedAt
                        // ─────────────────────────────────────────────────────────
                        val raw = snapshot.children.mapNotNull { r ->
                            val rStatus   = readString(r, "status")
                            val rRemarks  = readString(r, "remarks")  // full remark text
                            val rNoteOnly = readString(r, "note")     // note-only field
                            if (rStatus.isBlank() && rRemarks.isBlank()) return@mapNotNull null
                            val statusLabel = if (rStatus.isNotBlank())
                                WorkerParcelAdapter.getStatusConfig(ctx, rStatus, workerStatusLang).label else ""
                            // Journey log: status label + remarks text (full combined view)
                            val rLabel = when {
                                statusLabel.isNotBlank() && rRemarks.isNotBlank() -> "$statusLabel\n$rRemarks"
                                statusLabel.isNotBlank() -> statusLabel
                                rRemarks.isNotBlank() -> rRemarks
                                else -> ""
                            }
                            // Card badge: remarks on line 1, "Note: {note}" on line 2
                            // only if note is non-blank and different from remarks.
                            val rBadge = when {
                                rRemarks.isNotBlank() && rNoteOnly.isNotBlank() && rNoteOnly != rRemarks ->
                                    "$rRemarks\nNote: $rNoteOnly"
                                rRemarks.isNotBlank() -> rRemarks
                                rNoteOnly.isNotBlank() -> rNoteOnly
                                else -> ""
                            }
                            val createdAt = r.child("createdAt").getValue(Long::class.java) ?: 0L
                            val timeStr = java.text.SimpleDateFormat("dd-MM-yy hh:mm:ss a", java.util.Locale.getDefault())
                                .format(java.util.Date(createdAt))
                            val remarkedBy = readString(r, "remarked_by")
                            val rUserId = readString(r, "userId")
                            RawEntry(rStatus, rLabel, rBadge, timeStr, remarkedBy, rUserId, createdAt)
                        }
                        // Resolve every distinct uid to a name+photo in parallel (direct
                        // users/{uid} access — no full-tree scan, no reverse-index needed).
                        val distinctUids = raw.map { it.rUserId }.filter { it.isNotBlank() }.distinct()
                        coroutineScope {
                            distinctUids.map { uid -> async(Dispatchers.IO) { resolveUserName(uid) } }.awaitAll()
                        }
                        val history = raw.map { e ->
                            val authorRole = if (e.remarkedBy == "support") "cc" else "agent"
                            val resolvedName = if (e.rUserId.isNotBlank()) uidNameCache[e.rUserId] else null
                            val resolvedPhoto = if (e.rUserId.isNotBlank()) uidPhotoCache[e.rUserId] else null
                            val author = when {
                                e.remarkedBy == "support" && !resolvedName.isNullOrBlank() -> "$resolvedName · CC"
                                e.remarkedBy == "support" -> "CC"
                                !resolvedName.isNullOrBlank() -> resolvedName
                                else -> "Agent"
                            }
                            HistoryEntry(
                                action = e.rStatus.ifBlank { "NOTE" }.uppercase(),
                                remark = e.rLabel,
                                time = e.timeStr,
                                author = author,
                                authorRole = authorRole,
                                authorPhotoUrl = resolvedPhoto.orEmpty(),
                                createdAt = e.createdAt,
                                noteOnly = e.rNote
                            )
                        }.sortedBy { it.time }

                        if (!isAdded) return@launch
                        val lastRemarkStatus = snapshot.children
                            .mapNotNull { r -> r.child("status").getValue(String::class.java)?.trim()?.takeIf { it.isNotBlank() } }
                            .lastOrNull() ?: ""
                        // Card badge shows only TODAY's remark text — a remark from yesterday
                        // (or earlier) is no longer actionable for today's work, so it shouldn't
                        // linger on the card. The full multi-day history (journey log) is
                        // unaffected — this only narrows what feeds the card's `remarks` field.
                        val todayCal = java.util.Calendar.getInstance()
                        todayCal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                        todayCal.set(java.util.Calendar.MINUTE, 0)
                        todayCal.set(java.util.Calendar.SECOND, 0)
                        todayCal.set(java.util.Calendar.MILLISECOND, 0)
                        val todayStart = todayCal.timeInMillis
                        // Card badge: only today's remark FROM CC — shows clean note text only
                        // (noteOnly), never the status label (which the card's own status badge
                        // already shows). remark (journey log) still includes the status label.
                        val lastRemark = history.lastOrNull { it.createdAt >= todayStart && it.authorRole == "cc" }?.noteOnly ?: ""
                        val idx = allParcels.indexOfFirst { it.id == cId }
                        if (idx != -1) {
                            val effectiveStatus = if (lastRemarkStatus.isNotBlank()) lastRemarkStatus else allParcels[idx].status
                            allParcels = allParcels.toMutableList().also {
                                it[idx] = it[idx].copy(
                                    status  = effectiveStatus,
                                    remarks = lastRemark,
                                    history = history
                                )
                            }
                            setupFilterTabs()
                            applyFilters()
                        }
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            ref.addValueEventListener(listener)
            remarkNodeListeners[cId] = ref to listener
        }
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
                allParcels = when (sortMode) {
                    "aging" -> WorkerParcelAdapter.sortByGroupAge(parcels)
                    else    -> WorkerParcelAdapter.sortByAttempt(parcels)
                }
                setupFilterTabs()
                applyFilters()
                syncRemarkListeners(parcels.map { it.id }.toSet())
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
                    .get().await()
                ItemFetch(cId, runRef, detailSnap, remarksSnap)
            }
        }
        val fetches = itemFetches.awaitAll()

        // Pre-resolve every distinct remark author uid across ALL parcels in one parallel
        // batch — direct users/{uid} access, cached in uidNameCache so the per-parcel loop
        // below never blocks on a network round-trip.
        val allUids = fetches.flatMap { it.remarksSnap.children }
            .mapNotNull { it.child("userId").getValue(String::class.java)?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        coroutineScope {
            allUids.map { uid -> async(Dispatchers.IO) { resolveUserName(uid) } }.awaitAll()
        }

        val parcels = mutableListOf<WorkerParcelItem>()
        val statusBackfills = mutableMapOf<String, Any?>()
        fetches.forEach { fetch ->
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
                val rStatus   = readString(r, "status")
                val rRemarks  = readString(r, "remarks")
                val rNoteOnly = readString(r, "note")
                if (rStatus.isBlank() && rRemarks.isBlank()) return@mapNotNull null
                val statusLabelBulk = if (rStatus.isNotBlank())
                    context?.let { WorkerParcelAdapter.getStatusConfig(it, rStatus, "bn").label } ?: rStatus else ""
                val rLabel = when {
                    statusLabelBulk.isNotBlank() && rRemarks.isNotBlank() -> "$statusLabelBulk\n$rRemarks"
                    statusLabelBulk.isNotBlank() -> statusLabelBulk
                    rRemarks.isNotBlank()   -> rRemarks
                    else                 -> ""
                }
                // Card badge: remarks line 1, "Note: note" line 2 if different
                val rBadge = when {
                    rRemarks.isNotBlank() && rNoteOnly.isNotBlank() && rNoteOnly != rRemarks ->
                        "$rRemarks\nNote: $rNoteOnly"
                    rRemarks.isNotBlank() -> rRemarks
                    rNoteOnly.isNotBlank() -> rNoteOnly
                    else -> ""
                }
                val createdAt = r.child("createdAt").getValue(Long::class.java) ?: 0L
                val timeStr = java.text.SimpleDateFormat("dd-MM-yy hh:mm:ss a", java.util.Locale.getDefault())
                    .format(java.util.Date(createdAt))
                val remarkedBy = readString(r, "remarked_by")
                val rUserId    = readString(r, "userId")
                val resolvedName  = if (rUserId.isNotBlank()) uidNameCache[rUserId] else null
                val resolvedPhoto = if (rUserId.isNotBlank()) uidPhotoCache[rUserId] else null
                val authorRole = if (remarkedBy == "support") "cc" else "agent"
                val author = when {
                    remarkedBy == "support" && !resolvedName.isNullOrBlank() -> "$resolvedName · CC"
                    remarkedBy == "support"                                  -> "CC"
                    !resolvedName.isNullOrBlank()                            -> resolvedName
                    else                                                     -> "Agent"
                }
                HistoryEntry(
                    action = rStatus.ifBlank { "NOTE" }.uppercase(),
                    remark = rLabel,
                    time = timeStr,
                    author = author,
                    authorRole = authorRole,
                    authorPhotoUrl = resolvedPhoto.orEmpty(),
                    createdAt = createdAt,
                    noteOnly = rBadge
                )
            }.sortedBy { it.time }
            val lastRemarkStatus = remarksSnap.children.lastOrNull()
                ?.child("status")?.getValue(String::class.java) ?: ""

            // Card badge: today-only, same rule as the live-listener path — a remark from a
            // prior day isn't actionable for today's work, so it shouldn't linger on the card.
            val todayCalBulk = java.util.Calendar.getInstance()
            todayCalBulk.set(java.util.Calendar.HOUR_OF_DAY, 0)
            todayCalBulk.set(java.util.Calendar.MINUTE, 0)
            todayCalBulk.set(java.util.Calendar.SECOND, 0)
            todayCalBulk.set(java.util.Calendar.MILLISECOND, 0)
            val todayStartBulk = todayCalBulk.timeInMillis
            // Card badge: today-only, clean note text only (no status label).
            val lastRemark = history.lastOrNull { it.createdAt >= todayStartBulk && it.authorRole == "cc" }?.noteOnly ?: ""
            val createdAtVal = detailSnap.child("createdAt").getValue(Long::class.java) ?: 0L
            val updatedAtVal = detailSnap.child("updatedAt").getValue(Long::class.java) ?: 0L
            val attemptVal = readAttempt(detailSnap)
            parcels.add(
                WorkerParcelItem(
                    id = cId,
                    customer = name,
                    phone = phone,
                    address = address,
                    cod = cod,
                    status = sourceStatus,
                    remarks = lastRemark,
                    remarkStatus = lastRemarkStatus,
                    validationRequest = lastRemarkStatus == "verify_req",
                    validationNote = if (lastRemarkStatus == "verify_req") lastRemark else "",
                    time = hub,
                    createdAt = createdAtVal,
                    updatedAt = updatedAtVal,
                    attemptCount = attemptVal,
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

    private fun readAttempt(snap: DataSnapshot): Int {
        return snap.child("attempt").getValue(String::class.java)
            ?.toDoubleOrNull()?.toInt()
            ?: snap.child("attempt").getValue(Long::class.java)?.toInt()
            ?: snap.child("attempt").getValue(Double::class.java)?.toInt()
            ?: 0
    }

    private fun loadSortPref() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("databridge_toggles", android.content.Context.MODE_PRIVATE)
        sortMode = prefs.getString("worker_sort_mode", "attempt") ?: "attempt"
    }

    private fun saveSortPref() {
        val ctx = context ?: return
        ctx.getSharedPreferences("databridge_toggles", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("worker_sort_mode", sortMode)
            .apply()
    }

    private fun updateSortByLabel() {
        tvSortByDropdown.text = if (sortMode == "aging") "🕐 Aging ▾" else "🔁 Attempt ▾"
    }

    private fun showSortByDropdown() {
        val ctx = context ?: return
        val options = arrayOf("🔁 Attempt (most attempted first)", "🕐 Aging (oldest first)")
        val keys = arrayOf("attempt", "aging")
        val currentIndex = keys.indexOf(sortMode).coerceAtLeast(0)
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Sort by")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                sortMode = keys[which]
                updateSortByLabel()
                saveSortPref()
                allParcels = when (sortMode) {
                    "aging" -> WorkerParcelAdapter.sortByGroupAge(allParcels)
                    else    -> WorkerParcelAdapter.sortByAttempt(allParcels)
                }
                applyFilters()
                dialog.dismiss()
            }
            .show()
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

        // No re-sort needed here: allParcels is already ordered by sortByGroupAge()
        // (same-phone parcels adjacent, oldest group/parcel first), and filtering
        // preserves relative order, so the grouping survives search/status filters.

        updateCounts()

        // If navigated from a notification, expand the target parcel
        val targetId = pendingExpandParcelId
        if (targetId != null) {
            adapter.expandedItemId = targetId
            pendingExpandParcelId = null
        }
        adapter.submitList(filtered)
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = if (allParcels.isEmpty()) "📭\n\nNo parcels found"
            else if (searchQuery.isNotBlank()) "📭\n\nNo results for \"$searchQuery\""
            else "📭\n\nNo ${activeFilter} parcels"

        // Scroll to the expanded parcel after layout
        if (targetId != null) {
            val idx = filtered.indexOfFirst { it.id == targetId }
            if (idx >= 0) rvParcelList.post { rvParcelList.smoothScrollToPosition(idx) }
        }
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

    // Remark options for the "Set Remarks" sheet — loaded from config/remarks (target_status
    // per remark, set by admins in ConfigRemarksFragment). Falls back to a small built-in set
    // if the config hasn't loaded yet or is empty, so the feature never breaks entirely.
    private var whatsappTemplatesCache: Map<String, ConfigState.WhatsAppTemplate> = emptyMap()
    private var remarkOptions: List<WorkerRemarkOption> = emptyList()
    data class WorkerRemarkOption(
        val icon: String,
        val label: String,
        val statusKey: String,
        val statusPreview: String,
        val statusColor: Int,
        val templateId: String = "" // optional linked WhatsApp template
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
