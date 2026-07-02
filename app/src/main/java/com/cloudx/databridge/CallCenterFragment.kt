package com.cloudx.databridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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
    private lateinit var layoutParcelContainer: LinearLayout
    private lateinit var pbProgress: ProgressBar
    private lateinit var tvEmpty: TextView

    private lateinit var adapter: CallCenterAdapter

    private var allParcels = listOf<CallCenterParcelItem>()
    private var statusFilter = "all"
    private var branchFilter = "all"
    private var branches = listOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_call_center, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupFilterTabs()
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
        layoutParcelContainer = view.findViewById(R.id.layoutCcaParcelContainer)
        pbProgress = view.findViewById(R.id.twCcaProgressBar)
        tvEmpty = view.findViewById(R.id.twCcaEmptyState)

        val user = FirebaseAuth.getInstance().currentUser
        val displayName = user?.displayName ?: "Agent"
        tvAgentInfo.text = "$displayName · Supervisor"
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
            val label = WorkerParcelAdapter.getStatusConfig(requireContext(), statusKey).label
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

    private fun loadData() {
        pbProgress.visibility = View.VISIBLE
        tvEmpty.visibility    = View.GONE

        val db = com.google.firebase.database.FirebaseDatabase.getInstance()

        androidx.lifecycle.lifecycleScope.launch {
            try {
                // Today's date range
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val dayStart = cal.timeInMillis
                val dayEnd   = dayStart + 24 * 60 * 60 * 1000 - 1

                // 1. Fetch all today's delivery runs
                val runsSnap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    db.reference.child("courier/run_routes/delivery_run").get().await()
                }

                // Collect all consignment ids from today's runs
                val consignmentStatuses = mutableMapOf<String, String>()
                runsSnap.children.forEach { runSnap ->
                    val runId = runSnap.key ?: return@forEach
                    val runTimestamp = runId.removePrefix("run_").toLongOrNull() ?: return@forEach
                    if (runTimestamp < dayStart || runTimestamp > dayEnd) return@forEach

                    runSnap.child("consignments").children.forEach { c ->
                        val cId     = c.key ?: return@forEach
                        val cStatus = c.getValue(String::class.java) ?: "pending"
                        consignmentStatuses[cId] = cStatus
                    }
                }

                if (consignmentStatuses.isEmpty()) {
                    pbProgress.visibility = View.GONE
                    tvEmpty.visibility    = View.VISIBLE
                    tvEmpty.text          = "📭\n\nআজকের কোনো consignment নেই"
                    return@launch
                }

                // 2. Fetch consignment details
                val parcels = mutableListOf<CallCenterParcelItem>()
                consignmentStatuses.entries.forEach { (cId, runStatus) ->
                    val snap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        db.reference.child("courier/consignments/$cId").get().await()
                    }
                    if (!snap.exists()) return@forEach

                    val name    = snap.child("recipientName").getValue(String::class.java) ?: ""
                    val phone   = snap.child("recipientPhone").getValue(String::class.java) ?: ""
                    val address = snap.child("recipientAddress").getValue(String::class.java) ?: ""
                    val cod     = snap.child("collectableAmount").getValue(String::class.java)
                        ?.toDoubleOrNull()?.toInt()
                        ?: snap.child("collectableAmount").getValue(Long::class.java)?.toInt() ?: 0
                    val hub     = snap.child("deliveryHub").getValue(String::class.java) ?: ""

                    // Last remark
                    val remarkSnap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        db.reference.child("courier/remarks_by_consignment/$cId")
                            .limitToLast(1).get().await()
                    }
                    val lastRemark = remarkSnap.children.firstOrNull()
                        ?.child("status")?.getValue(String::class.java) ?: ""

                    parcels.add(CallCenterParcelItem(
                        id                = cId,
                        customer          = name,
                        phone             = phone,
                        address           = address,
                        cod               = cod,
                        status            = runStatus,
                        remarks           = lastRemark,
                        validationRequest = runStatus == "verify_req",
                        validationNote    = if (runStatus == "verify_req") lastRemark else "",
                        time              = "",
                        worker            = "",
                        branch            = hub
                    ))
                }

                allParcels = parcels.sortedBy { it.id }
                branches   = allParcels.map { it.branch }.filter { it.isNotBlank() }.distinct().sorted()
                setupBranchChips()
                setupFilterTabs()

                adapter = CallCenterAdapter(
                    onCall = { item -> AutoDialHelper.dial(this@CallCenterFragment, item.phone) },
                    onSetRemarks = { item -> showRemarksDialog(item) },
                    onValidate = { item ->
                        allParcels = allParcels.map {
                            if (it.id == item.id) it.copy(
                                validationRequest = false,
                                status  = "confirmed",
                                remarks = "Validated by call center"
                            ) else it
                        }
                        applyFilters()
                    }
                )
                applyFilters()

            } catch (e: Exception) {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text       = "⚠ Load failed: ${e.message?.take(60)}"
            } finally {
                pbProgress.visibility = View.GONE
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

        // ── CC Remark options with auto-status ───────────────────────────
        data class CcRemarkOption(
            val icon: String,
            val label: String,
            val statusKey: String,
            val statusPreview: String,
            val statusColorRes: Int
        )

        val options = listOf(
            CcRemarkOption("✅", "Customer delivery নিতে চান",        "confirmed", "✓ Confirmed",    R.color.theme_green),
            CcRemarkOption("📵", "Customer ফোন ধরছে না",              "pending",   "◌ Pending",      R.color.theme_yellow),
            CcRemarkOption("🔄", "পরে call করতে বলেছেন",             "pending",   "◌ Pending",      R.color.theme_yellow),
            CcRemarkOption("📍", "Address ভুল / খুঁজে পাচ্ছি না",    "hold_req",  "⏸ Hold Request", R.color.theme_orange),
            CcRemarkOption("🚫", "Customer delivery নেবে না",         "rejected",  "✗ Rejected",     R.color.theme_red)
        )

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
                tvAutoStatus.setTextColor(requireContext().getColor(opt.statusColorRes))

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
                    "type"      to "cc_remark",
                    "status"    to fullRemark,
                    "createdAt" to timestamp
                ),
                "courier/consignments_by_phone/${item.phone}/${item.id}" to selectedStatus
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

        // Render list
        adapter.items = filtered
        adapter.expandedItemId = null

        layoutParcelContainer.removeAllViews()
        if (filtered.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
            adapter.renderInto(layoutParcelContainer)
        }
    }

    data class FilterTab(val key: String, val label: String)
}
