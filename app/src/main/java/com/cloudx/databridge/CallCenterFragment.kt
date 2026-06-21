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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth

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
        val filters = listOf(
            FilterTab("all", "All"),
            FilterTab("confirmed", "✓ Confirmed"),
            FilterTab("pending", "◌ Pending"),
            FilterTab("rejected", "✗ Rejected"),
            FilterTab("validation", "⚡ Validation")
        )

        for (filter in filters) {
            val chip = layoutInflater.inflate(R.layout.item_filter_chip, layoutFilterTabs, false) as TextView
            chip.text = filter.label
            chip.tag = filter.key
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
        tvEmpty.visibility = View.GONE

        // Demo data
        allParcels = getDemoAgentParcels()

        // Extract unique branches (dynamic)
        branches = allParcels.map { it.branch }.distinct()
        setupBranchChips()

        adapter = CallCenterAdapter(
            onCall = { item ->
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${item.phone}"))
                startActivity(intent)
            },
            onSetRemarks = { item ->
                showRemarksDialog(item)
            },
            onValidate = { item ->
                // Auto-validate: set as confirmed
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

        applyFilters()
        pbProgress.visibility = View.GONE
    }

    private fun showRemarksDialog(item: CallCenterParcelItem) {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_remarks, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvRemarksTitle)
        val etRemarks = view.findViewById<EditText>(R.id.etRemarksText)
        val btnCancel = view.findViewById<TextView>(R.id.btnRemarksCancel)
        val btnSave = view.findViewById<TextView>(R.id.btnRemarksSave)

        // Status selector
        val btnConfirmed = view.findViewById<TextView>(R.id.btnRemarkStatusConfirmed)
        val btnPending = view.findViewById<TextView>(R.id.btnRemarkStatusPending)
        val btnRejected = view.findViewById<TextView>(R.id.btnRemarkStatusRejected)
        var selectedStatus = item.status

        fun updateStatusBtns() {
            val cfg = WorkerParcelAdapter.getStatusConfig(view.context, selectedStatus)
            btnConfirmed.isSelected = selectedStatus == "confirmed"
            btnPending.isSelected = selectedStatus == "pending"
            btnRejected.isSelected = selectedStatus == "rejected"
            listOf(btnConfirmed, btnPending, btnRejected).forEach { btn ->
                btn.setBackgroundResource(
                    if (btn.isSelected) R.drawable.bg_filter_chip_active
                    else R.drawable.bg_filter_chip_inactive
                )
                btn.setTextColor(
                    if (btn.isSelected) 0xFFFFFFFF.toInt() else 0xFF64748b.toInt()
                )
            }
        }

        btnConfirmed.setOnClickListener { selectedStatus = "confirmed"; updateStatusBtns() }
        btnPending.setOnClickListener { selectedStatus = "pending"; updateStatusBtns() }
        btnRejected.setOnClickListener { selectedStatus = "rejected"; updateStatusBtns() }
        updateStatusBtns()

        // Quick templates
        view.findViewById<TextView>(R.id.btnQuickCustomerWants).setOnClickListener {
            etRemarks.setText("Customer want to receive")
        }
        view.findViewById<TextView>(R.id.btnQuickNotAvailable).setOnClickListener {
            etRemarks.setText("Not available, call later")
        }
        view.findViewById<TextView>(R.id.btnQuickAddressNotFound).setOnClickListener {
            etRemarks.setText("Address not found")
        }
        view.findViewById<TextView>(R.id.btnQuickRefused).setOnClickListener {
            etRemarks.setText("Refused delivery")
        }

        tvTitle.text = "${item.customer} · ${item.id} · ${item.phone}"
        etRemarks.setText(item.remarks)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val text = etRemarks.text.toString().trim()
            allParcels = allParcels.map {
                if (it.id == item.id) it.copy(
                    status = selectedStatus,
                    remarks = text,
                    validationRequest = false
                ) else it
            }
            applyFilters()
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun applyFilters() {
        var filtered = allParcels

        // Branch filter
        if (branchFilter != "all") {
            filtered = filtered.filter { it.branch == branchFilter }
        }

        // Status filter
        filtered = when (statusFilter) {
            "confirmed" -> filtered.filter { it.status == "confirmed" }
            "pending" -> filtered.filter { it.status == "pending" }
            "rejected" -> filtered.filter { it.status == "rejected" }
            "validation" -> filtered.filter { it.validationRequest }
            else -> filtered
        }

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

    private fun getDemoAgentParcels(): List<CallCenterParcelItem> {
        return listOf(
            CallCenterParcelItem("DB-2201", "Rahim Mia", "01885580909", "House 12, Mograpara Bazar", 850, "confirmed", "Customer want to receive today", false, "", "9:15 AM", "Sonia Akter", "Sonargaon Hub"),
            CallCenterParcelItem("DB-2202", "Fatema Begum", "01712345678", "Vill: Pirojpur, Sonargaon", 1200, "pending", "", true, "Address খুঁজে পাচ্ছি না", "9:30 AM", "Sonia Akter", "Sonargaon Hub"),
            CallCenterParcelItem("DB-2203", "Karim Hossain", "01987654321", "College Road, Flat 3B", 650, "rejected", "Customer not available today", false, "", "10:00 AM", "Sonia Akter", "Sonargaon Hub"),
            CallCenterParcelItem("DB-2204", "Nasrin Akter", "01611223344", "Kanchpur Bridge Road", 2100, "confirmed", "Will receive between 2-4 PM", false, "", "10:15 AM", "Sonia Akter", "Sonargaon Hub"),
            CallCenterParcelItem("DB-2207", "Rashed Khan", "01900112233", "Bandar Port Road", 1750, "confirmed", "Ready to receive", false, "", "9:00 AM", "Sonia Akter", "Bandar Hub"),
            CallCenterParcelItem("DB-2208", "Mitu Begum", "01600998877", "Char Bandar, House 5", 900, "pending", "", true, "Customer phone off", "9:45 AM", "Sonia Akter", "Bandar Hub"),
            CallCenterParcelItem("DB-2209", "Selim Mia", "01744332211", "Tanbazar, Shop 22", 3200, "rejected", "Refused delivery", false, "", "10:30 AM", "Alif Mia", "Sonargaon Hub"),
            CallCenterParcelItem("DB-2210", "Poly Khatun", "01522110099", "Goaldi Mosque Road", 560, "pending", "", false, "", "11:15 AM", "Alif Mia", "Sonargaon Hub")
        )
    }

    data class FilterTab(val key: String, val label: String)
}
