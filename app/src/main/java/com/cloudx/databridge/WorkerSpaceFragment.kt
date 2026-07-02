package com.cloudx.databridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await


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
    private lateinit var layoutFilterTabs: LinearLayout
    private lateinit var rvParcelList: RecyclerView
    private lateinit var pbProgress: View
    private lateinit var tvEmpty: TextView

    private lateinit var adapter: WorkerParcelAdapter

    private var allParcels = listOf<WorkerParcelItem>()
    private var activeFilter = "all"
    private var searchQuery = ""

    private val auth = FirebaseAuth.getInstance()

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
        setupFilterTabs()
        setupAdapter()
        loadData()
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
        layoutFilterTabs = view.findViewById(R.id.layoutFilterTabs)
        rvParcelList = view.findViewById(R.id.rvParcelList)
        pbProgress = view.findViewById(R.id.twProgressBar)
        tvEmpty = view.findViewById(R.id.twEmptyState)

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
        val db = com.google.firebase.database.FirebaseDatabase.getInstance()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 1. Get employeeId
                val employeeId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    db.reference.child("users/$uid/profile/company_info/employee_id")
                        .get().await().getValue(String::class.java)?.trim()
                } ?: run {
                    pbProgress.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "⚠ Employee ID পাওয়া যায়নি"
                    return@launch
                }

                // 2. Get all run ids for this agent
                val runSnap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    db.reference.child("courier/runs_by_agentId/$employeeId/delivery_run")
                        .get().await()
                }
                if (!runSnap.exists()) {
                    pbProgress.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "📭\n\nআজকের কোনো run নেই"
                    return@launch
                }

                // 3. For each open run, get consignment ids + statuses
                val consignmentStatuses = mutableMapOf<String, String>() // id → status from run
                runSnap.children.forEach { runChild ->
                    val runId    = runChild.key ?: return@forEach
                    val runStatus = runChild.getValue(String::class.java) ?: "open"
                    if (runStatus != "open") return@forEach // শুধু open runs

                    val consSnap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        db.reference.child("courier/run_routes/delivery_run/$runId/consignments")
                            .get().await()
                    }
                    consSnap.children.forEach { c ->
                        val cId     = c.key ?: return@forEach
                        val cStatus = c.getValue(String::class.java) ?: "pending"
                        consignmentStatuses[cId] = cStatus
                    }
                }

                if (consignmentStatuses.isEmpty()) {
                    pbProgress.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "📭\n\nকোনো consignment নেই"
                    return@launch
                }

                // 4. Fetch full details from courier/consignments/
                val parcels = mutableListOf<WorkerParcelItem>()
                consignmentStatuses.entries.forEach { (cId, runStatus) ->
                    val detailSnap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        db.reference.child("courier/consignments/$cId").get().await()
                    }
                    if (!detailSnap.exists()) return@forEach

                    val name    = detailSnap.child("recipientName").getValue(String::class.java) ?: ""
                    val phone   = detailSnap.child("recipientPhone").getValue(String::class.java) ?: ""
                    val address = detailSnap.child("recipientAddress").getValue(String::class.java) ?: ""
                    val cod     = detailSnap.child("collectableAmount").getValue(String::class.java)
                        ?.toDoubleOrNull()?.toInt()
                        ?: detailSnap.child("collectableAmount").getValue(Long::class.java)?.toInt()
                        ?: 0
                    val status  = runStatus // run এর status = source of truth
                    val hub     = detailSnap.child("deliveryHub").getValue(String::class.java) ?: ""

                    // Remarks from remarks_by_consignment
                    val remarksSnap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        db.reference.child("courier/remarks_by_consignment/$cId")
                            .limitToLast(5).get().await()
                    }
                    val history = remarksSnap.children.mapNotNull { r ->
                        val type      = r.child("type").getValue(String::class.java) ?: return@mapNotNull null
                        val rStatus   = r.child("status").getValue(String::class.java) ?: ""
                        val createdAt = r.child("createdAt").getValue(Long::class.java) ?: 0L
                        val timeStr   = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                            .format(java.util.Date(createdAt))
                        HistoryEntry(
                            action     = type.uppercase(),
                            remark     = rStatus,
                            time       = timeStr,
                            author     = "",
                            authorRole = "agent"
                        )
                    }.toList()

                    val lastRemark = history.lastOrNull()?.remark ?: ""

                    parcels.add(WorkerParcelItem(
                        id                = cId,
                        customer          = name,
                        phone             = phone,
                        address           = address,
                        cod               = cod,
                        status            = status,
                        remarks           = lastRemark,
                        validationRequest = status == "verify_req",
                        validationNote    = if (status == "verify_req") lastRemark else "",
                        time              = hub,
                        history           = history
                    ))
                }

                allParcels = parcels.sortedBy { it.id }
                setupFilterTabs()
                applyFilters()

            } catch (e: Exception) {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "⚠ Load failed: ${e.message?.take(60)}"
            } finally {
                pbProgress.visibility = View.GONE
            }
        }
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
}
