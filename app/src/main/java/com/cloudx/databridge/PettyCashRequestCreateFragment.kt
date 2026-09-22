package com.cloudx.databridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch

/**
 * Petty Cash Management — New/Edit Request (Requester screen).
 *
 * Not part of the original 10-screen mockup — that batch covered the
 * Accounts/approver view only. This is the "Requester" side of the
 * approval chain: category, amount, at least one photo attachment, purpose
 * (Remarks — the one optional field). Submits
 * via PettyCashViewModel.submitRequest(), or — when opened with an
 * editRequestId — edits an existing request via updateRequest() (owner
 * while PENDING, or branch staff/POC/accounts on anything non-settled).
 * Edit is only reachable from Settlement Details' Edit button, which
 * itself only shows for the request's own submitter while status is still
 * PENDING, or for staff/POC/accounts on anything non-settled — this
 * fragment doesn't re-check that beyond trusting the caller, since
 * updateRequest()/deleteRequest() re-validate ownership and status
 * server-side (well, ViewModel-side) regardless.
 *
 * Category-specific fields: Bulk Delivery shows a Consignment ID text
 * field; LOT Delivery shows the same row as a multi-add list (scan/type +
 * Add, one request covering N consignments, stored comma-joined);
 * Pickup shows a Store picker (fetched from Supabase's public.stores,
 * see SupabaseClaimsReader.fetchStores() — a courier-wide directory shared
 * with the rest of the courier flow, not Petty-Cash-specific; managed from
 * Config's Stores tab, see ConfigStoresFragment). Formerly a bare "Merchant Name"
 * text picker against courier/merchants — renamed and expanded to a full
 * Store record (Store ID, name, address, area, phone) per Alif's request;
 * only storeId + storeName get saved onto the petty cash request itself,
 * same as the old merchantName field did.
 *
 * Attachment upload goes through AttachmentUploader (Cloudflare R2 via a
 * presigned URL from the r2-attachment-upload Supabase Edge Function — see
 * that class's doc comment for why R2 rather than Firebase Storage, and why
 * the actual upload credentials never reach this app). Images only, max 2
 * per claim, each capped at AttachmentUploader.MAX_FILE_BYTES (2 MB, auto-
 * compressed); the Edge Function independently re-enforces all three, since
 * a client-side check alone can always be bypassed by a modified APK calling
 * the function directly.
 */
class PettyCashRequestCreateFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private var branchId: String = ""
    private var editRequestId: String = ""
    private val isEditMode: Boolean get() = editRequestId.isNotBlank()

    private val utilitiesOptions = listOf(
        "Internet Bill",
        "Regarding Mobile Bill For QC Team Member",
        "Gas Bill",
        "Local Security Guard Bill",
        "Garbage Bill",
        "Water/Wasa Bill",
        "Cleaner Bill",
        "Transgender Bill"
    )
    private val categoryOptionsFallback =
        listOf(PC_CATEGORY_BULK_DELIVERY, PC_CATEGORY_PICKUP, PC_CATEGORY_LOT_DELIVERY, PC_CATEGORY_INTER_CHANGE,
            PC_CATEGORY_PARCEL_RECEIVING) + utilitiesOptions
    private var categoryOptions: List<String> = categoryOptionsFallback
    /** Field (worker) categories — workers see only these four, everyone
     *  else filing for self sees every other category (see
     *  visibleCategories). Filing on behalf of an agent is always these
     *  four (field conveyance). */
    private val requesterCategories = setOf(
        PC_CATEGORY_PICKUP, PC_CATEGORY_BULK_DELIVERY, PC_CATEGORY_LOT_DELIVERY, PC_CATEGORY_INTER_CHANGE
    )
    /** Branch roles for the signed-in user (loaded in create mode for the
     *  staff gate below) — null until viewModel.load() returns. */
    private var userRoles: PettyCashUserRoles? = null
    /** Staff submit gate: null = still checking (roles loading), false =
     *  neither requester nor staff (form will close once roles confirm). */
    private var accessGranted: Boolean? = null
    // Admin-managed category → group map (conveyance / operation / office /
    // utilities). Empty until loaded — every branch below falls back to the
    // two known names so the form works offline exactly as before.
    private var categoryGroups: Map<String, String> = emptyMap()
    private var stores: List<Store> = emptyList()
    private var storesLoaded = false

    private var selectedCategory: String = ""
    private var selectedStoreId: String = ""
    private var selectedStoreName: String = ""
    // Fixed payout from the picked store's conveyance_amount (>0 = set).
    // Drives the readonly requested-amount prefill for Pickup (below).
    private var selectedStoreAmount: Double = 0.0

    // Conveyance fields — see the layout's groupPcRequestConveyance comment for why
    // these apply to both Pickup and Bulk Delivery, and applyConveyanceDefaults()
    // below for the Office-default/store-area-prefill logic.
    private val vehicleOptions = listOf("CNG", "Paddle Van", "Auto")
    // Default Auto so a request can never go out with a blank vehicle (all
    // paper vouchers are Auto); the picker can still change it, and editing
    // an old row with a stored vehicle keeps that value (see prefill).
    private var selectedVehicle: String = "Auto"
    // "OFFICE" is a sentinel, not a real areas-table entry — see
    // applyConveyanceDefaults(). pickupAreas/deliveryAreas load once from
    // Supabase (branch-scoped) and are reused for both From and To pickers
    // (Pickup uses pickup_area for From, Bulk Delivery uses delivery_area
    // for To — see SupabaseClaimsReader.fetchAreas()).
    private var pickupAreas: List<Area> = emptyList()
    private var deliveryAreas: List<Area> = emptyList()
    private var areasLoaded = false
    // Hub directory for the Inter Change From/To hub pickers (branch-scoped
    // trip: From = other hubs, To = self hub, locked).
    private var hubBranches: List<SupabaseClaimsReader.BranchOption> = emptyList()
    private var hubsLoaded = false
    private var selectedFromArea: String = "OFFICE"
    private var selectedFromAreaLabel: String = "Office"
    private var selectedToArea: String = "OFFICE"
    private var selectedToAreaLabel: String = "Office"

    // Expense date — defaults to today (the created date); a past date can be
    // picked for backdated expenses, never a future one.
    private var selectedExpenseDate: Long = startOfDay(System.currentTimeMillis())
    private lateinit var tvExpenseDateSelected: TextView
    private lateinit var tvDateLabel: TextView

    private fun startOfDay(millis: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = millis
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Conveyance layout (vehicle/from/to + quantities) for the two known
     *  names even before the catalog loads, otherwise by the admin-managed
     *  group — so a future conveyance category (e.g. InterChange) gets the
     *  same fields without an app release. */
    private fun isConveyanceCategory(category: String): Boolean =
        categoryGroups[category]?.let { it == "conveyance" }
            ?: (category == PC_CATEGORY_PICKUP || category == PC_CATEGORY_BULK_DELIVERY || category == PC_CATEGORY_LOT_DELIVERY || category == PC_CATEGORY_INTER_CHANGE || category == PC_CATEGORY_PARCEL_RECEIVING)
    /** Worker-like filer (requester permission or worker role) → the four
     *  field categories only. Anyone else reaching the form is staff filing
     *  (see the create-mode gate): on behalf of an agent → the same four,
     *  for self → everything except those four. */
    private fun isRequesterLike(): Boolean {
        if (RbacManager.hasPermission("petty_cash_requester")) return true
        if (isWorker()) return true
        return userRoles?.let { !it.isStaff } ?: false
    }

    /** Worker role (by role id or name) — the field filer. */
    private fun isWorker(): Boolean {
        val id = RbacManager.current.roleId.trim().lowercase()
        val name = RbacManager.current.roleName.trim().lowercase()
        return id == "worker" || "worker" in name
    }

    private fun visibleCategories(): List<String> {
        val all = categoryOptions.ifEmpty { categoryOptionsFallback }
        // Worker (or staff filing on behalf of an agent) → field 4 only.
        // Never fall back to the full list here — that once leaked all
        // expense types to agents when the catalog lacked the 4.
        if (isRequesterLike() || onBehalfAgent != null) {
            return all.filter { it in requesterCategories }
                .ifEmpty { requesterCategories.filter { it in categoryOptionsFallback } }
        }
        // Staff for self → everything except those four.
        return all.filter { it !in requesterCategories }.ifEmpty { all }
    }

    /** Staff "Request for" picker: Self (expenses) or a branch agent
     *  (conveyance on their behalf). Switching resets a category that is no
     *  longer visible so a stale pick can't be submitted. */
    private fun showOnBehalfPicker() {
        if (!agentsLoaded) {
            Toast.makeText(requireContext(), "Still loading agent list, try again in a moment", Toast.LENGTH_SHORT).show()
            return
        }
        if (branchAgents.isEmpty()) {
            Toast.makeText(requireContext(), "No agents in this branch", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = listOf("Self") + branchAgents.map {
            if (it.employeeId.isNotBlank()) "${it.name} (${it.employeeId})" else it.name
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Request for")
            .setItems(labels.toTypedArray()) { _, index ->
                onBehalfAgent = if (index == 0) null else branchAgents[index - 1]
                tvOnBehalfSelected.text = labels[index]
                tvOnBehalfSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
                if (selectedCategory.isNotBlank() && selectedCategory !in visibleCategories()) {
                    applyCategory("")
                }
            }
            .show()
    }

    private fun loadAgents() {
        lifecycleScope.launch {
            val result = runCatching { SupabaseClaimsReader.fetchBranchAgents(branchId) }
                .getOrElse { emptyList() }
            branchAgents = result
            agentsLoaded = true
        }
    }

    /** Shows the on-behalf row once staff access is confirmed. */
    private fun refreshOnBehalfRow() {
        if (!::groupOnBehalf.isInitialized) return
        groupOnBehalf.isVisible = !isEditMode && accessGranted == true && !isRequesterLike()
    }

    private fun isLotCategory(category: String): Boolean = category == PC_CATEGORY_LOT_DELIVERY

    /** Route-based conveyance (no consignment ID — the From<>To route
     *  identifies the trip instead): Inter Change Commission + Parcel
     *  Receiving (incharge-only trip type). */
    private fun isRouteBasedCategory(category: String): Boolean =
        category == PC_CATEGORY_INTER_CHANGE || category == PC_CATEGORY_PARCEL_RECEIVING

    // LOT Delivery: multiple consignment IDs on one request (scan/type + Add).
    // Stored comma-joined in consignment_id/cid_or_merchant (display-only
    // downstream), quantities = list size. No schema change needed.
    private val lotConsignments = mutableListOf<String>()
    private lateinit var tvLotCount: TextView
    private lateinit var layoutLotList: LinearLayout
    private lateinit var btnAddConsignment: View
    private lateinit var tvConsignmentLabel: TextView

    // Multi-attachment state (max 5). Each picked file uploads IMMEDIATELY
    // on select (gallery → row with live progress %), not on submit. A row
    // is submittable once its object key exists; the ✕ deletes the row and
    // the R2 object too (best-effort). Submit stays disabled while any row
    // is still uploading. Keys are R2 *object keys*, not URLs — the bucket
    // is private; viewing later means a fresh presigned URL each time (see
    // AttachmentUploader.getDownloadUrl).
    private data class FormAttachment(
        val rowId: Long,
        var displayName: String,
        var progress: Int = 0,
        var objectKey: String = "",
        var sizeBytes: Long = 0,
        var failed: Boolean = false,
        var job: kotlinx.coroutines.Job? = null,
        var statusView: TextView? = null,
    )
    private val formAttachments = mutableListOf<FormAttachment>()
    private var attachRowSeq = 0L
    // One idempotency key per form instance (audit #5): every submit tap —
    // first or retry-after-failure — sends the SAME key, so the Edge returns
    // the existing row instead of a duplicate claim.
    private val formSubmitId: String = java.util.UUID.randomUUID().toString()
    private lateinit var layoutAttachments: LinearLayout

    private val uploadingCount: Int get() = formAttachments.count { it.objectKey.isBlank() && !it.failed }

    private val consignmentPreviewHandler = Handler(Looper.getMainLooper())
    private var consignmentPreviewRunnable: Runnable? = null

    private val attachmentsPicker =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (!uris.isNullOrEmpty()) onAttachmentsPicked(uris)
        }

    // Same scan mechanism as WorkerSpaceFragment's search-by-scan, but the
    // trim + length validation below matches ScannerFragment's scanLauncher instead
    // (the canonical place this is checked) -- valid tracking IDs are always exactly
    // TRACKING_ID_LENGTH (14) characters, length only, not digits-only. Manual/typed
    // entry deliberately isn't held to this same check, matching ScannerFragment's
    // own manual-entry path (showBottomSheetManual() there only blank-checks) --
    // only a scan misfire gets second-guessed this way, not a human typing.
    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            val code = res.data?.getStringExtra("SCAN_RESULT")?.substringBefore('|')?.trim()
            when {
                code.isNullOrBlank() -> Toast.makeText(requireContext(), "No code found", Toast.LENGTH_SHORT).show()
                code.length != ScannerFragment.TRACKING_ID_LENGTH ->
                    Toast.makeText(requireContext(), "⚠ Invalid scan. Please scan again.", Toast.LENGTH_LONG).show()
                else -> {
                    if (isLotCategory(selectedCategory) && !isEditMode) {
                        addLotConsignment(code)
                    } else {
                        etConsignmentId.setText(code)
                        etConsignmentId.setSelection(code.length)
                    }
                }
            }
        }
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvCategorySelected: TextView
    // Staff on-behalf: file a conveyance request for a branch agent.
    private lateinit var groupOnBehalf: View
    private lateinit var tvOnBehalfSelected: TextView
    private var branchAgents: List<SupabaseClaimsReader.BranchAgent> = emptyList()
    private var agentsLoaded = false
    private var onBehalfAgent: SupabaseClaimsReader.BranchAgent? = null
    private lateinit var groupConsignment: View
    private lateinit var etConsignmentId: EditText
    private lateinit var btnScanConsignment: View
    private lateinit var layoutConsignmentPreview: View
    private lateinit var tvConsignmentPreview: TextView
    private lateinit var groupStore: View
    private lateinit var tvStoreSelected: TextView
    private lateinit var etPickupCount: EditText
    private lateinit var layoutVehicle: View
    private lateinit var tvVehicleSelected: TextView
    private lateinit var layoutFromArea: View
    private lateinit var tvFromAreaSelected: TextView
    private lateinit var layoutToArea: View
    private lateinit var tvToAreaSelected: TextView
    private lateinit var groupConveyance: View
    private lateinit var groupAmount: View
    private lateinit var etAmount: EditText
    private lateinit var tvAmountLabel: TextView
    private lateinit var tvLotTotal: TextView
    private lateinit var etPurpose: EditText
    private lateinit var tvPurposeCount: TextView
    private lateinit var btnSubmit: android.widget.Button
    private lateinit var pbSaving: android.widget.ProgressBar

    companion object {
        const val MAX_ATTACHMENTS = 2 // max 2 receipt images per claim (space save)

        private const val ARG_BRANCH_ID = "branch_id"
        private const val ARG_EDIT_REQUEST_ID = "edit_request_id"

        fun newInstance(branchId: String, editRequestId: String = ""): PettyCashRequestCreateFragment {
            val f = PettyCashRequestCreateFragment()
            f.arguments = Bundle().apply {
                putString(ARG_BRANCH_ID, branchId)
                putString(ARG_EDIT_REQUEST_ID, editRequestId)
            }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_request_create, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()
        editRequestId = arguments?.getString(ARG_EDIT_REQUEST_ID).orEmpty()

        tvTitle = view.findViewById(R.id.tvPcRequestCreateTitle)
        tvCategorySelected = view.findViewById(R.id.tvPcRequestCategorySelected)
        groupConsignment = view.findViewById(R.id.groupPcRequestConsignment)
        etConsignmentId = view.findViewById(R.id.etPcRequestConsignmentId)
        btnScanConsignment = view.findViewById(R.id.btnPcRequestScanConsignment)
        layoutConsignmentPreview = view.findViewById(R.id.layoutPcRequestConsignmentPreview)
        tvConsignmentPreview = view.findViewById(R.id.tvPcRequestConsignmentPreview)

        btnScanConsignment.setOnClickListener {
            try {
                scanLauncher.launch(Intent(requireContext(), MlKitScannerActivity::class.java))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        tvConsignmentLabel = view.findViewById(R.id.tvPcRequestConsignmentLabel)
        tvLotCount = view.findViewById(R.id.tvPcRequestLotCount)
        layoutLotList = view.findViewById(R.id.layoutPcRequestLotList)
        btnAddConsignment = view.findViewById(R.id.btnPcRequestAddConsignment)
        btnAddConsignment.setOnClickListener { addLotFromInput() }

        // Debounced preview: waits for a pause in typing so a Firebase read doesn't
        // fire on every keystroke. A scan sets the whole id in one go, so it also
        // benefits from the same debounce rather than needing a separate path.
        etConsignmentId.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                consignmentPreviewRunnable?.let { consignmentPreviewHandler.removeCallbacks(it) }
                val id = s?.toString()?.trim().orEmpty()
                if (id.isBlank()) { layoutConsignmentPreview.isVisible = false; return }
                val runnable = Runnable { loadConsignmentPreview(id) }
                consignmentPreviewRunnable = runnable
                consignmentPreviewHandler.postDelayed(runnable, 500L)
            }
        })
        groupStore = view.findViewById(R.id.groupPcRequestStore)
        tvStoreSelected = view.findViewById(R.id.tvPcRequestStoreSelected)
        etPickupCount = view.findViewById(R.id.etPcRequestPickupCount)
        groupConveyance = view.findViewById(R.id.groupPcRequestConveyance)
        layoutVehicle = view.findViewById(R.id.layoutPcRequestVehicle)
        tvVehicleSelected = view.findViewById(R.id.tvPcRequestVehicleSelected)
        // Default Auto visible from the start (see selectedVehicle) — edit
        // prefill below overwrites it when the row has a stored vehicle.
        tvVehicleSelected.text = selectedVehicle
        tvVehicleSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
        layoutFromArea = view.findViewById(R.id.layoutPcRequestFromArea)
        tvFromAreaSelected = view.findViewById(R.id.tvPcRequestFromAreaSelected)
        layoutToArea = view.findViewById(R.id.layoutPcRequestToArea)
        tvToAreaSelected = view.findViewById(R.id.tvPcRequestToAreaSelected)
        // No quantity fields on the form (removed): quantities are fully
        // derived at submit — Pickup mirrors the pickup count, Bulk Delivery
        // is exactly 1 consignment, anything else 0.

        groupAmount = view.findViewById(R.id.groupPcAmount)
        etAmount = view.findViewById(R.id.etPcRequestAmount)
        tvAmountLabel = view.findViewById(R.id.tvPcRequestAmountLabel)
        tvLotTotal = view.findViewById(R.id.tvPcRequestLotTotal)
        etAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateLotTotal() }
        })
        tvExpenseDateSelected = view.findViewById(R.id.tvPcRequestExpenseDateSelected)
        tvDateLabel = view.findViewById(R.id.tvPcRequestDateLabel)
        updateDateLabel()
        updateExpenseDateLabel()
        view.findViewById<View>(R.id.layoutPcRequestExpenseDate).setOnClickListener { showExpenseDatePicker() }
        etPurpose = view.findViewById(R.id.etPcRequestPurpose)
        tvPurposeCount = view.findViewById(R.id.tvPcRequestPurposeCount)
        layoutAttachments = view.findViewById(R.id.layoutPcRequestAttachments)
        btnSubmit = view.findViewById(R.id.btnPcRequestSubmit)
        pbSaving = view.findViewById(R.id.pbPcRequestSaving)

        if (!isEditMode && !RbacManager.canSubmitPettyCash()) {
            // Approver-side staff (no requester permission) may still submit
            // expense requests — branch roles arrive via load() below.
            accessGranted = null
            viewModel.state.observe(viewLifecycleOwner) { state ->
                if (state is PettyCashState.Success && accessGranted == null) {
                    userRoles = state.roles
                    accessGranted = state.roles.isStaff
                    if (accessGranted == false) {
                        Toast.makeText(requireContext(), "Your role isn't set up to submit petty cash requests", Toast.LENGTH_LONG).show()
                        parentFragmentManager.popBackStack()
                    } else {
                        refreshOnBehalfRow()
                        loadAgents()
                    }
                }
            }
            if (branchId.isNotBlank()) viewModel.load(branchId)
        } else {
            accessGranted = true
        }

        tvTitle.text = if (isEditMode) "Edit Request" else "New Petty Cash Request"
        btnSubmit.text = if (isEditMode) "Update Request" else "Submit Request"

        view.findViewById<View>(R.id.btnPcRequestCreateBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<View>(R.id.layoutPcRequestCategory).setOnClickListener { showCategoryPicker() }
        groupOnBehalf = view.findViewById(R.id.groupPcRequestOnBehalf)
        tvOnBehalfSelected = view.findViewById(R.id.tvPcRequestOnBehalfSelected)
        view.findViewById<View>(R.id.layoutPcRequestOnBehalf).setOnClickListener { showOnBehalfPicker() }
        refreshOnBehalfRow()
        view.findViewById<View>(R.id.layoutPcRequestStore).setOnClickListener { showStorePicker() }
        layoutVehicle.setOnClickListener { showVehiclePicker() }
        layoutFromArea.setOnClickListener { showAreaPicker(forFrom = true) }
        layoutToArea.setOnClickListener { showAreaPicker(forFrom = false) }
        view.findViewById<View>(R.id.layoutPcRequestAttachmentAdd).setOnClickListener {
            attachmentsPicker.launch(AttachmentUploader.PICKER_MIME_TYPE)
        }
        renderAttachments()

        etPurpose.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                tvPurposeCount.text = "${s?.length ?: 0}/300"
            }
        })

        btnSubmit.setOnClickListener { onSubmit() }

        loadStores()
        loadAreas()
        loadHubs()
        loadClaimCategories()

        if (isEditMode) {
            viewModel.state.observe(viewLifecycleOwner) { state -> prefillIfEditing(state) }
            if (branchId.isNotBlank()) viewModel.load(branchId)
        }
    }

    private var prefilled = false

    private fun prefillIfEditing(state: PettyCashState) {
        if (prefilled || state !is PettyCashState.Success) return
        val request = state.requests.find { it.id == editRequestId } ?: return
        userRoles = state.roles
        // Owner edits own pending; branch staff/POC/accounts may overwrite
        // anything non-settled to fix a submit mistake (server re-enforces).
        val canStaffEdit = state.roles.isAnyApprover && request.status != PC_STATUS_SETTLED
        if (request.status != PC_STATUS_PENDING && !canStaffEdit) {
            Toast.makeText(requireContext(), "This request can no longer be edited", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            return
        }
        applyCategory(request.category)
        etAmount.setText(if (request.amount > 0) request.amount.toInt().toString() else "")
        etPurpose.setText(request.purpose)
        // LOT rows are single-consignment (one row per parcel since the
        // separate-rows cutover) — edit stays bulk-like single-ID.
        if (request.consignmentId.isNotBlank()) etConsignmentId.setText(request.consignmentId)
        if (request.storeName.isNotBlank()) {
            selectedStoreId = request.storeId
            selectedStoreName = request.storeName
            tvStoreSelected.text = request.storeName
            tvStoreSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
            // Amount resolves once the store directory loads (below) —
            // refresh then so edit shows the same readonly prefill.
            selectedStoreAmount = stores.find { it.storeId == request.storeId }?.conveyanceAmount ?: 0.0
            refreshPickupAmount()
        }
        if (request.pickupCount > 0) etPickupCount.setText(request.pickupCount.toString())
        if (request.vehicle.isNotBlank()) {
            selectedVehicle = request.vehicle
            tvVehicleSelected.text = request.vehicle
            tvVehicleSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
        }
        if (request.fromArea.isNotBlank()) {
            selectedFromArea = request.fromArea
            selectedFromAreaLabel = areaLabelFor(request.fromArea)
            tvFromAreaSelected.text = selectedFromAreaLabel
        }
        if (request.toArea.isNotBlank()) {
            selectedToArea = request.toArea
            selectedToAreaLabel = areaLabelFor(request.toArea)
            tvToAreaSelected.text = selectedToAreaLabel
        }
        if (request.requestedDate > 0L) {
            selectedExpenseDate = request.requestedDate
            updateExpenseDateLabel()
        }
        // Prefilled values above win over the category defaults (applyCategory ran
        // first inside this same prefill) — now lock the rows for this category.
        updateAreaLocks()
        prefilled = true
    }

    private fun showCategoryPicker() {
        val options = visibleCategories()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Category")
            .setItems(options.toTypedArray()) { _, index -> applyCategory(options[index]) }
            .show()
    }

    /** Loads the admin-managed category catalog — the picker falls back to
     *  Bulk Delivery/Pickup when offline so the form never breaks. A selected
     *  category that vanishes from the catalog is kept as-is (a legacy claim
     *  being edited must not lose its category). */
    private fun loadClaimCategories() {
        lifecycleScope.launch {
            val catalog = SupabaseClaimsReader.fetchClaimCategories()
            if (catalog.isEmpty()) return@launch
            categoryGroups = catalog.associate { it.name to it.group }
            categoryOptions = catalog.map { it.name }
        }
    }

    private fun updateExpenseDateLabel() {
        tvExpenseDateSelected.text =
            java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(selectedExpenseDate))
    }

    private fun showExpenseDatePicker() {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = selectedExpenseDate }
        android.app.DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                selectedExpenseDate = java.util.Calendar.getInstance().apply {
                    set(y, m, d)
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                updateExpenseDateLabel()
            },
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)
        ).apply {
            // Backdated expenses allowed, future dates never — the expense
            // can't have happened after today.
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    /** Date field label follows the selected type: Pickup → "Pickup Date",
     *  Bulk Delivery → "Delivery Date", any other category → "<Category> Date",
     *  nothing selected yet → "Expense Date". */
    private fun updateDateLabel() {
        if (!::tvDateLabel.isInitialized) return
        tvDateLabel.text = when (selectedCategory) {
            PC_CATEGORY_PICKUP -> "Pickup Date"
            PC_CATEGORY_BULK_DELIVERY -> "Delivery Date"
            else -> if (selectedCategory.isNotBlank()) "${selectedCategory} Date" else "Expense Date"
        }
    }

    /** Sets the selected category and shows/hides the category-specific field group. */
    private fun applyCategory(category: String) {
        selectedCategory = category
        updateDateLabel()
        tvCategorySelected.text = category
        tvCategorySelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))

        // Store picker stays Pickup-only; consignment is the generic reference
        // id for Bulk/LOT Delivery. Inter Change trips have no consignment ID
        // (route comes from From/To instead). Non-conveyance categories get
        // neither. LOT Delivery reuses the same consignment row as a multi-add
        // list (scan/type + Add) instead of a single ID field. Create-only: a
        // LOT row being edited is single-consignment, so edit stays bulk-like.
        val isConveyance = isConveyanceCategory(category)
        val isLot = isLotCategory(category) && !isEditMode
        groupConsignment.isVisible = isConveyance && category != PC_CATEGORY_PICKUP && !isRouteBasedCategory(category)
        tvConsignmentLabel.text = if (isLot) "Consignments" else "Consignment ID"
        btnAddConsignment.isVisible = isLot
        tvLotCount.isVisible = isLot
        layoutLotList.isVisible = isLot
        groupStore.isVisible = category == PC_CATEGORY_PICKUP
        groupConveyance.isVisible = isConveyance
        // Pickup: the Requester submits quantities only, no money amount yet
        // (that comes back from the store) — UNLESS the picked store defines
        // a fixed conveyance_amount: then the amount shows prefilled and
        // non-editable (see refreshPickupAmount). Hidden otherwise, not just
        // unrequired, so it can't look like a forgotten/blank field on the
        // Requester's own screen.
        groupAmount.isVisible = category != PC_CATEGORY_PICKUP
        refreshPickupAmount()

        // Switching category clears the other category's field so a
        // half-filled Consignment ID doesn't silently survive a switch to
        // Pickup (or vice versa) and get submitted anyway. Switching INTO
        // LOT carries a typed single ID over as the first list entry.
        if (isLot) {
            val typed = etConsignmentId.text?.toString().orEmpty().trim()
            if (typed.isNotBlank() && lotConsignments.none { it.equals(typed, ignoreCase = true) }) {
                lotConsignments.add(typed)
            }
            etConsignmentId.setText("")
            etConsignmentId.hint = "Type or scan, then tap + Add"
            tvAmountLabel.text = "Rate per consignment"
            etAmount.hint = "e.g. 20"
            renderLotList()
        } else {
            if (lotConsignments.isNotEmpty()) {
                lotConsignments.clear()
                try { renderLotList() } catch (_: Exception) { /* views not bound yet */ }
            }
            etConsignmentId.hint = "Type or scan consignment ID"
            if (::tvAmountLabel.isInitialized) tvAmountLabel.text = "Amount"
            etAmount.hint = "0"
            if (::tvLotTotal.isInitialized) tvLotTotal.isVisible = false
            if (!isConveyance || category == PC_CATEGORY_PICKUP || isRouteBasedCategory(category)) etConsignmentId.setText("")
        }
        if (category != PC_CATEGORY_PICKUP) {
            selectedStoreId = ""
            selectedStoreName = ""
            selectedStoreAmount = 0.0
            tvStoreSelected.text = "Select Store"
            tvStoreSelected.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            etPickupCount.setText("")
        } else {
            etAmount.setText("")
        }
        if (groupConveyance.isVisible) applyConveyanceDefaults(category)
    }

    /** Pickup requested amount comes from the picked store's fixed
     *  conveyance_amount: shown prefilled and NON-editable when set (>0),
     *  hidden + 0 exactly like before when the store defines none.
     *  Non-Pickup categories always keep a free amount field. */
    private fun refreshPickupAmount() {
        if (selectedCategory != PC_CATEGORY_PICKUP) {
            groupAmount.isVisible = true
            etAmount.isEnabled = true
            return
        }
        if (selectedStoreAmount > 0) {
            groupAmount.isVisible = true
            etAmount.isEnabled = false
            etAmount.setText(formatAmount(selectedStoreAmount))
        } else {
            groupAmount.isVisible = false
            etAmount.isEnabled = true
            etAmount.setText("")
        }
    }

    private fun formatAmount(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    private fun showStorePicker() {
        if (!storesLoaded) {
            Toast.makeText(requireContext(), "Still loading store list, try again in a moment", Toast.LENGTH_SHORT).show()
            return
        }
        if (stores.isEmpty()) {
            Toast.makeText(requireContext(), "No stores available — contact your admin to add some", Toast.LENGTH_LONG).show()
            return
        }
        // Searchable single-select (same pattern as the branch picker):
        // merchant list is long, scrolling without search is unusable.
        val ctx = requireContext()
        val dp = resources.displayMetrics.density.toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp * 16, dp * 8, dp * 16, 0)
        }
        val etSearch = EditText(ctx).apply {
            hint = "Search merchant, address, area, phone..."
            setTextColor(android.graphics.Color.parseColor("#0F172A"))
            setHintTextColor(android.graphics.Color.parseColor("#94A3B8"))
        }
        container.addView(etSearch)
        val listView = ListView(ctx)
        container.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp * 300))
        var filtered = stores.toMutableList()
        // Plain rows (no radio): a tap selects immediately + dismisses, so a
        // single-choice radio indicator serves no purpose — it only confuses.
        fun makeAdapter(list: List<Store>) = object : ArrayAdapter<Store>(
            ctx, android.R.layout.simple_list_item_1, android.R.id.text1, list) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getView(pos, cv, parent)
                val s = list[pos]
                val sub = listOfNotNull(
                    s.areaName.takeIf { it.isNotBlank() },
                    s.address.takeIf { it.isNotBlank() },
                ).joinToString(" • ")
                val label = (if (s.conveyanceAmount > 0) "${s.name}  (৳${formatAmount(s.conveyanceAmount)})" else s.name) +
                    (if (sub.isNotBlank()) "\n$sub" else "")
                (v.findViewById<View>(android.R.id.text1) as? TextView)?.let {
                    it.text = label
                    it.textSize = 13f
                }
                return v
            }
        }
        listView.adapter = makeAdapter(filtered)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Select Store")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .create()
        listView.setOnItemClickListener { _, _, pos, _ ->
            val picked = filtered[pos]
            selectedStoreId = picked.storeId
            selectedStoreName = picked.name
            selectedStoreAmount = picked.conveyanceAmount
            tvStoreSelected.text = selectedStoreName
            tvStoreSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
            applyConveyanceDefaults(PC_CATEGORY_PICKUP)
            refreshPickupAmount()
            dialog.dismiss()
        }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                val q = s?.toString()?.trim() ?: ""
                // Name/address/area match as typed; phone matches on digits
                // only ("01516" finds "01516-123456" too).
                val qDigits = q.replace(Regex("[^0-9]"), "")
                filtered = if (q.isEmpty()) stores.toMutableList()
                else stores.filter {
                    it.name.contains(q, ignoreCase = true) ||
                        it.address.contains(q, ignoreCase = true) ||
                        it.areaName.contains(q, ignoreCase = true) ||
                        (qDigits.isNotBlank() && it.phone.replace(Regex("[^0-9]"), "").contains(qDigits))
                }.toMutableList()
                listView.adapter = makeAdapter(filtered)
            }
        })
        dialog.show()
    }

    private fun loadStores() {
        // Stores live in Supabase's public.stores (see
        // SupabaseClaimsReader.fetchStores) — the old Firebase
        // courier/stores read is removed. Areas below come from Supabase too
        // (public.areas via loadAreas); only the consignment preview stays on
        // Firebase: courier-directory data, out of scope for the cutover.
        lifecycleScope.launch {
            runCatching { SupabaseClaimsReader.fetchStores() }
                .onSuccess { result ->
                    stores = result.sortedBy { it.name }
                    storesLoaded = true
                    if (result.isEmpty()) {
                        Toast.makeText(requireContext(), "Couldn't load store list", Toast.LENGTH_SHORT).show()
                    } else if (selectedCategory == PC_CATEGORY_PICKUP && selectedStoreId.isNotBlank()) {
                        // Directory arrived after prefill/selection — resolve
                        // the fixed amount now so the readonly prefill shows.
                        selectedStoreAmount =
                            stores.find { it.storeId == selectedStoreId }?.conveyanceAmount ?: 0.0
                        refreshPickupAmount()
                    }
                }
                .onFailure {
                    storesLoaded = true // don't leave the picker stuck saying "still loading" forever
                    Toast.makeText(requireContext(), "Couldn't load store list: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** Loads this branch's area directory once from Supabase (public.areas).
     *  Pickup areas populate Pickup's From picker; delivery areas populate
     *  Bulk Delivery's To picker. A 'both' row serves either side. */
    private fun loadAreas() {
        lifecycleScope.launch {
            val result = runCatching {
                SupabaseClaimsReader.fetchAreas(
                    branchIds = listOf(branchId).filter { it.isNotBlank() },
                    usages = listOf("pickup", "delivery"),
                )
            }.getOrElse {
                Toast.makeText(requireContext(), "Couldn't load area list: ${it.message}", Toast.LENGTH_SHORT).show()
                emptyList()
            }
            pickupAreas = result.filter { it.matchesUsage("pickup") }.sortedBy { it.name }
            deliveryAreas = result.filter { it.matchesUsage("delivery") }.sortedBy { it.name }
            areasLoaded = true
        }
    }

    /** Hub directory for the Inter Change pickers (see showHubPicker). */
    private fun loadHubs() {
        lifecycleScope.launch {
            val result = runCatching { SupabaseClaimsReader.fetchBranches() }
                .getOrElse {
                    Toast.makeText(requireContext(), "Couldn't load hub list: ${it.message}", Toast.LENGTH_SHORT).show()
                    emptyList()
                }
            hubBranches = result.filter { it.branchId.isNotBlank() && it.name.isNotBlank() }
            hubsLoaded = true
            // Late arrival: an Inter Change form opened before the hubs
            // loaded still gets its locked self-hub To.
            if (selectedCategory == PC_CATEGORY_INTER_CHANGE) applyInterChangeHubDefaults()
        }
    }

    /** Hubs other than this form's branch — the Inter Change From picker. */
    private fun otherHubs(): List<SupabaseClaimsReader.BranchOption> {
        val self = hubBranches.find { it.branchId == branchId }
        return if (self != null) hubBranches.filter { it.branchId != branchId } else hubBranches
    }

    /** Inter Change To is always the self hub (locked) — see isAreaLocked. */
    private fun applyInterChangeHubDefaults() {
        val self = hubBranches.find { it.branchId == branchId } ?: return
        selectedToArea = self.branchId
        selectedToAreaLabel = self.name
        if (::tvToAreaSelected.isInitialized) tvToAreaSelected.text = self.name
        updateAreaLocks()
    }

    /** Inter Change hub pickers: From = other hubs, To = self hub (fixed,
     *  nothing to pick — see applyInterChangeHubDefaults). */
    private fun showHubPicker(forFrom: Boolean) {
        if (isAreaLocked(forFrom)) return
        if (!hubsLoaded) {
            Toast.makeText(requireContext(), "Still loading hub list, try again in a moment", Toast.LENGTH_SHORT).show()
            return
        }
        if (!forFrom) {
            applyInterChangeHubDefaults()
            return
        }
        val hubs = otherHubs()
        if (hubs.isEmpty()) {
            Toast.makeText(requireContext(), "No other hubs found", Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Select From Hub")
            .setItems(hubs.map { it.name }.toTypedArray()) { _, index ->
                val hub = hubs[index]
                selectedFromArea = hub.branchId
                selectedFromAreaLabel = hub.name
                tvFromAreaSelected.text = hub.name
                tvFromAreaSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
            }
            .show()
    }

    private fun showVehiclePicker() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Vehicle")
            .setItems(vehicleOptions.toTypedArray()) { _, index ->
                selectedVehicle = vehicleOptions[index]
                tvVehicleSelected.text = selectedVehicle
                tvVehicleSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
            }
            .show()
    }

    /** Office is available in every list. The remaining areas come from the
     *  directory appropriate to the selected conveyance category. Locked fields
     *  (Pickup From/To, Bulk Delivery From — see isAreaLocked) never reach here:
     *  their rows are disabled AND this guard returns early, so no path can
     *  change a locked value. */
    private fun showAreaPicker(forFrom: Boolean) {
        // Inter Change uses hub pickers, not area pickers (From = other hubs,
        // To = self hub locked).
        if (selectedCategory == PC_CATEGORY_INTER_CHANGE) {
            showHubPicker(forFrom)
            return
        }
        if (isAreaLocked(forFrom)) return
        if (!areasLoaded) {
            Toast.makeText(requireContext(), "Still loading area list, try again in a moment", Toast.LENGTH_SHORT).show()
            return
        }
        val usage = if (selectedCategory == PC_CATEGORY_PICKUP) "pickup" else "delivery"
        val rawAreas = if (selectedCategory == PC_CATEGORY_PICKUP) pickupAreas else deliveryAreas
        // usage collapse: one name never shows twice in a single-usage list.
        val areas = dedupeAreasForPicker(rawAreas, usage)
        val labels = listOf("Office") + areas.map { it.name }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(if (forFrom) "Select From" else "Select Destination")
            .setItems(labels.toTypedArray()) { _, index ->
                val id = if (index == 0) "OFFICE" else areas[index - 1].areaId
                val label = labels[index]
                if (forFrom) {
                    selectedFromArea = id
                    selectedFromAreaLabel = label
                    tvFromAreaSelected.text = label
                } else {
                    selectedToArea = id
                    selectedToAreaLabel = label
                    tvToAreaSelected.text = label
                }
            }
            .show()
    }

    /** Pickup: To defaults 'Office' (a pickup always ends at the office); From is
     *  prefilled from the selected store's own area (every store has one — see
     *  Store.areaId/areaName). Both are LOCKED (see updateAreaLocks) — From follows
     *  the store pick, so with no store it resets to the Office default instead
     *  of keeping a stale area from a previous store/category. Bulk Delivery:
     *  From defaults 'Office' and is locked; To is a plain, unprefilled
     *  dropdown — Bulk Delivery has no store to prefill from (Consignment ID
     *  instead of a store picker). Mirrors the same Office-default/store-prefill
     *  logic already confirmed for the remark-picker's Vehicle/From/To fields. */
    private fun applyConveyanceDefaults(category: String) {
        if (category == PC_CATEGORY_PICKUP) {
            selectedToArea = "OFFICE"; selectedToAreaLabel = "Office"
            tvToAreaSelected.text = "Office"
            val store = stores.find { it.storeId == selectedStoreId }
            if (selectedStoreId.isNotBlank() && store != null && store.areaId.isNotBlank()) {
                selectedFromArea = store.areaId
                selectedFromAreaLabel = store.areaName.ifBlank { store.areaId }
                tvFromAreaSelected.text = selectedFromAreaLabel
            } else {
                selectedFromArea = "OFFICE"; selectedFromAreaLabel = "Office"
                tvFromAreaSelected.text = "Office"
            }
        } else if (category == PC_CATEGORY_BULK_DELIVERY) {
            selectedFromArea = "OFFICE"; selectedFromAreaLabel = "Office"
            tvFromAreaSelected.text = "Office"
        } else if (category == PC_CATEGORY_INTER_CHANGE) {
            // From = other-hub picker (reset any area default so a stale
            // "Office" can't ride along); To = self hub, locked.
            selectedFromArea = ""
            selectedFromAreaLabel = ""
            tvFromAreaSelected.text = "Select hub"
            tvFromAreaSelected.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            applyInterChangeHubDefaults()
        } else if (category == PC_CATEGORY_PARCEL_RECEIVING || category == PC_CATEGORY_INTER_CHANGE_COMMISSION) {
            // Free area pickers (not hub-locked) — reset stale values so a
            // previous category's areas can't ride along; both required.
            selectedFromArea = ""
            selectedFromAreaLabel = ""
            tvFromAreaSelected.text = "Select area"
            tvFromAreaSelected.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            selectedToArea = ""
            selectedToAreaLabel = ""
            tvToAreaSelected.text = "Select area"
            tvToAreaSelected.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
        }
        updateAreaLocks()
    }

    /** User-visible office label — the "OFFICE" sentinel (any casing) must
     *  never reach the UI or Supabase, only "Office". */
    private fun officeLabel(value: String): String =
        if (value.trim().equals("office", ignoreCase = true)) "Office" else value

    /** Resolves a stored areaId (or the "OFFICE" sentinel) back to a display label,
     *  for prefillIfEditing() — falls back to the raw id if the area lists haven't
     *  loaded yet or the id isn't found in either list (still functionally correct,
     *  just shows the raw id instead of a friendly name in that edge case). */
    private fun areaLabelFor(areaId: String): String {
        if (areaId.trim().equals("office", ignoreCase = true)) return "Office"
        return pickupAreas.find { it.areaId == areaId }?.name
            ?: deliveryAreas.find { it.areaId == areaId }?.name
            ?: areaId
    }

    /** Single source of truth for locked From/Destination rows:
     *  Pickup → From (store area) + Destination (Office) both locked;
     *  Bulk Delivery → From (Office) locked, Destination free;
     *  Inter Change → From (other hubs) free, Destination (self hub) locked;
     *  anything else → both free. */
    private fun isAreaLocked(forFrom: Boolean): Boolean {
        return if (forFrom) selectedCategory == PC_CATEGORY_PICKUP || selectedCategory == PC_CATEGORY_BULK_DELIVERY
        else selectedCategory == PC_CATEGORY_PICKUP || selectedCategory == PC_CATEGORY_INTER_CHANGE
    }

    /** Disables locked rows (tap does nothing, dimmed) so the agent can see at a
     *  glance they are fixed. Safe to call before views init (guarded). */
    private fun updateAreaLocks() {
        if (!::layoutFromArea.isInitialized || !::layoutToArea.isInitialized) return
        val lockFrom = isAreaLocked(forFrom = true)
        val lockTo = isAreaLocked(forFrom = false)
        layoutFromArea.isEnabled = !lockFrom
        layoutFromArea.alpha = if (lockFrom) 0.6f else 1f
        layoutToArea.isEnabled = !lockTo
        layoutToArea.alpha = if (lockTo) 0.6f else 1f
    }

    /** LOT list: typed input → normalized → deduped append. Returns false
     *  with a toast when there is nothing to add or it is already listed. */
    private fun addLotConsignment(raw: String): Boolean {
        val code = raw.trim()
        if (code.isBlank()) {
            Toast.makeText(requireContext(), "Type or scan a consignment ID first", Toast.LENGTH_SHORT).show()
            return false
        }
        if (lotConsignments.any { it.equals(code, ignoreCase = true) }) {
            Toast.makeText(requireContext(), "Already added", Toast.LENGTH_SHORT).show()
            return false
        }
        lotConsignments.add(code)
        renderLotList()
        return true
    }

    private fun addLotFromInput() {
        if (addLotConsignment(etConsignmentId.text?.toString().orEmpty())) {
            etConsignmentId.setText("")
            layoutConsignmentPreview.isVisible = false
        }
    }

    /** LOT live total: rate × consignments. Visible in LOT create mode only. */
    private fun updateLotTotal() {
        if (!::tvLotTotal.isInitialized) return
        val isLot = isLotCategory(selectedCategory) && !isEditMode
        tvLotTotal.isVisible = isLot
        if (!isLot) return
        val rate = etAmount.text?.toString()?.toDoubleOrNull() ?: 0.0
        val n = lotConsignments.size
        tvLotTotal.text = if (rate > 0 && n > 0)
            "Total: ${pettyCashTaka(rate * n)} (${pettyCashTaka(rate)} × $n)"
        else "Total: — (rate × consignments)"
    }

    private fun renderLotList() {
        val ctx = context ?: return
        tvLotCount.text = if (lotConsignments.isEmpty()) "No consignments added yet"
            else "${lotConsignments.size} consignment${if (lotConsignments.size == 1) "" else "s"} added"
        layoutLotList.removeAllViews()
        lotConsignments.toList().forEach { code ->
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val tvCode = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = code
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#0F172A"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val tvDel = TextView(ctx).apply {
                text = "✕"
                textSize = 16f
                setTextColor(android.graphics.Color.parseColor("#DC2626"))
                setPadding(16, 8, 16, 8)
                setOnClickListener {
                    lotConsignments.remove(code)
                    renderLotList()
                }
            }
            line.addView(tvCode)
            line.addView(tvDel)
            layoutLotList.addView(line)
        }
        updateLotTotal()
    }

    /** Firebase read-only preview so the agent can confirm they've got the right
     *  parcel — shows recipient name/phone/address/status, or a not-found message.
     *  Guards on isAdded since this can complete after the view is gone (fragment
     *  navigated away mid-request). */
    private fun loadConsignmentPreview(consignmentId: String) {
        FirebaseDatabase.getInstance().reference.child("courier/consignments/$consignmentId")
            .get().addOnCompleteListener { task ->
                if (!isAdded) return@addOnCompleteListener
                val cons = if (task.isSuccessful) task.result?.value as? Map<*, *> else null
                if (cons == null) {
                    tvConsignmentPreview.text = "⚠ No consignment found for this ID"
                    layoutConsignmentPreview.isVisible = true
                    return@addOnCompleteListener
                }
                val name = (cons["recipientName"] as? String).orEmpty().ifBlank { "—" }
                val phone = (cons["recipientPhone"] as? String).orEmpty().ifBlank { "—" }
                val address = (cons["recipientAddress"] as? String).orEmpty().ifBlank { "—" }
                val status = (cons["status"] as? String).orEmpty().ifBlank { "—" }
                tvConsignmentPreview.text = "$name · $phone\n$address\nStatus: $status"
                layoutConsignmentPreview.isVisible = true
            }
    }

    /** Gallery pick → one row per file, each uploading immediately with a
     *  live % — capped at MAX_ATTACHMENTS (overflow files are skipped with
     *  a toast, never silently dropped into a broken state). */
    private fun onAttachmentsPicked(uris: List<Uri>) {
        val room = MAX_ATTACHMENTS - formAttachments.size
        if (room <= 0) {
            Toast.makeText(requireContext(), "Maximum $MAX_ATTACHMENTS attachments", Toast.LENGTH_SHORT).show()
            return
        }
        val accepted = uris.take(room)
        if (accepted.size < uris.size) {
            Toast.makeText(requireContext(),
                "Only $room more allowed (max $MAX_ATTACHMENTS) — extras skipped", Toast.LENGTH_LONG).show()
        }
        accepted.forEach { startAttachmentUpload(it) }
    }

    private fun startAttachmentUpload(uri: Uri) {
        val ctx = requireContext()
        val meta = AttachmentUploader.readFileMeta(ctx, uri)
        val row = FormAttachment(
            rowId = ++attachRowSeq,
            displayName = meta?.displayName?.ifBlank { "attachment" } ?: "attachment",
        )
        formAttachments.add(row)
        renderAttachments()
        refreshSubmitEnabled()
        row.job = lifecycleScope.launch {
            try {
                when (val result = AttachmentUploader.upload(ctx, uri, onProgress = { pct ->
                    row.progress = pct
                    activity?.runOnUiThread { row.statusView?.text = "$pct%" }
                })) {
                    is AttachmentUploader.Result.Success -> {
                        row.objectKey = result.objectKey
                        row.sizeBytes = meta?.sizeBytes ?: 0L
                        // Keep the server-confirmed name (may gain .jpg after compression).
                        row.displayName = result.displayName
                    }
                    is AttachmentUploader.Result.Rejected -> {
                        row.failed = true
                        if (isAdded) Toast.makeText(requireContext(), result.reason, Toast.LENGTH_LONG).show()
                    }
                    is AttachmentUploader.Result.Failed -> {
                        row.failed = true
                        if (isAdded) Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (t: Throwable) {
                // Never brick the form: an unexpected failure just fails this row.
                row.failed = true
                if (isAdded) Toast.makeText(requireContext(),
                    "Couldn't attach ${row.displayName} — try a smaller photo", Toast.LENGTH_LONG).show()
            }
            if (isAdded) {
                renderAttachments()
                refreshSubmitEnabled()
            }
        }
    }

    /** Submit is enabled only when no row is still uploading. */
    private fun refreshSubmitEnabled() {
        if (!isAdded) return
        btnSubmit.isEnabled = uploadingCount == 0
    }

    private fun renderAttachments() {
        val ctx = context ?: return
        layoutAttachments.removeAllViews()
        if (formAttachments.isEmpty()) {
            val tv = TextView(ctx).apply {
                text = "No file selected"
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                setPadding(0, 8, 0, 8)
            }
            layoutAttachments.addView(tv)
            return
        }
        formAttachments.toList().forEach { row ->
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val tvName = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = row.displayName
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#0F172A"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val tvStatus = TextView(ctx).apply {
                text = when {
                    row.objectKey.isNotBlank() -> "✓"
                    row.failed -> "✗ tap ✕ to remove"
                    else -> "${row.progress}%"
                }
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#059669"))
                setPadding(16, 0, 16, 0)
            }
            row.statusView = tvStatus
            val tvDel = TextView(ctx).apply {
                text = "✕"
                textSize = 16f
                setTextColor(android.graphics.Color.parseColor("#DC2626"))
                setPadding(16, 8, 16, 8)
                setOnClickListener { removeAttachment(row.rowId) }
            }
            line.addView(tvName)
            line.addView(tvStatus)
            line.addView(tvDel)
            layoutAttachments.addView(line)
        }
    }

    /** ✕ on a row: cancel an in-flight upload, delete the R2 object if one
     *  was stored (best-effort, never blocks), drop the row. */
    private fun removeAttachment(rowId: Long) {
        val row = formAttachments.find { it.rowId == rowId } ?: return
        row.job?.cancel()
        row.statusView = null
        val key = row.objectKey
        formAttachments.remove(row)
        renderAttachments()
        refreshSubmitEnabled()
        if (key.isNotBlank()) {
            lifecycleScope.launch {
                AttachmentUploader.deleteObject(key)
            }
        }
    }

    /** Saving indicator: spinner + disabled button with "Saving..." text, so
     *  the agent can see work is in progress. Call setSaving(false) on every
     *  exit path (validation returns above never start it). */
    private fun setSaving(saving: Boolean) {
        if (!isAdded) return
        pbSaving.isVisible = saving
        btnSubmit.isEnabled = !saving
        btnSubmit.text = when {
            saving -> "⏳ Saving..."
            isEditMode -> "Update Request"
            else -> "Submit Request"
        }
    }

    private fun onSubmit() {
        if (!isEditMode && !RbacManager.canSubmitPettyCash() && accessGranted != true) {
            if (accessGranted == null) Toast.makeText(requireContext(), "Checking access, please wait…", Toast.LENGTH_SHORT).show()
            return
        }
        if (uploadingCount > 0) {
            Toast.makeText(requireContext(), "Attachments are still uploading — please wait", Toast.LENGTH_SHORT).show()
            return
        }
        val amount = etAmount.text?.toString()?.toDoubleOrNull() ?: 0.0
        val purpose = etPurpose.text?.toString().orEmpty().trim()
        val consignmentId = etConsignmentId.text?.toString().orEmpty().trim()
        val pickupCount = etPickupCount.text?.toString()?.trim()?.toIntOrNull() ?: 0

        if (branchId.isBlank()) {
            Toast.makeText(requireContext(), "No branch selected", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedCategory.isBlank()) {
            Toast.makeText(requireContext(), "Select a category", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedCategory == PC_CATEGORY_PICKUP && selectedStoreId.isBlank()) {
            Toast.makeText(requireContext(), "Select a store", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedCategory == PC_CATEGORY_PICKUP && pickupCount <= 0) {
            Toast.makeText(requireContext(), "Enter how many pickups", Toast.LENGTH_SHORT).show()
            return
        }
        val isConveyanceSubmit = isConveyanceCategory(selectedCategory)
        // LOT multi-row submit is create-only: a LOT row being edited is
        // single-consignment, so edit follows the bulk single-ID path.
        val isLotSubmit = isLotCategory(selectedCategory) && !isEditMode
        if (isLotSubmit) {
            // Forgiving submit: a typed-but-not-yet-added ID counts too.
            val pending = etConsignmentId.text?.toString().orEmpty().trim()
            if (pending.isNotBlank() && lotConsignments.none { it.equals(pending, ignoreCase = true) }) {
                lotConsignments.add(pending)
            }
        }
        if (isLotSubmit && lotConsignments.isEmpty()) {
            Toast.makeText(requireContext(), "Add at least one consignment (scan/type + Add)", Toast.LENGTH_SHORT).show()
            return
        }
        if (isConveyanceSubmit && selectedVehicle.isBlank()) {
            Toast.makeText(requireContext(), "Select vehicle", Toast.LENGTH_SHORT).show()
            return
        }
        if (isConveyanceSubmit && selectedCategory != PC_CATEGORY_PICKUP && !isRouteBasedCategory(selectedCategory) && !isLotSubmit && consignmentId.isBlank()) {
            Toast.makeText(requireContext(), "Enter the consignment ID", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedCategory == PC_CATEGORY_INTER_CHANGE && selectedFromArea.isBlank()) {
            Toast.makeText(requireContext(), "Select From hub", Toast.LENGTH_SHORT).show()
            return
        }
        // From/To are must for every conveyance kind (locked Office/store
        // defaults always satisfy this for Pickup/Bulk — the check bites
        // for the free-picker trip types).
        if (isConveyanceSubmit && selectedCategory != PC_CATEGORY_INTER_CHANGE && selectedFromArea.isBlank()) {
            Toast.makeText(requireContext(), "Select From area", Toast.LENGTH_SHORT).show()
            return
        }
        if (isConveyanceSubmit && selectedToArea.isBlank()) {
            Toast.makeText(requireContext(), "Select Destination", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedCategory != PC_CATEGORY_PICKUP && amount <= 0.0) {
            Toast.makeText(requireContext(),
                if (isLotSubmit) "Enter the rate per consignment" else "Enter a valid amount",
                Toast.LENGTH_SHORT).show()
            return
        }
        // Purpose is the one optional field — everything else on the form
        // is must. (Empty purpose passes through to the server as "".)
        // Photo attachment is must on create (edit can't change attachments).
        if (!isEditMode && formAttachments.none { it.objectKey.isNotBlank() }) {
            Toast.makeText(requireContext(), "Attach at least one photo/receipt", Toast.LENGTH_SHORT).show()
            return
        }
        // Staff file conveyance only on behalf of an agent (never for self).
        val behalf = if (isRequesterLike()) null else onBehalfAgent
        if (!isRequesterLike() && behalf == null && selectedCategory in requesterCategories) {
            Toast.makeText(requireContext(), "Pick an agent in 'Request for' for conveyance requests", Toast.LENGTH_SHORT).show()
            return
        }

        val finalConsignmentId = when {
            isLotSubmit -> "" // per-row below: one claim per consignment
            isRouteBasedCategory(selectedCategory) -> "" // trips have no consignment ID (route instead)
            isConveyanceSubmit && selectedCategory != PC_CATEGORY_PICKUP -> consignmentId
            else -> ""
        }
        val finalStoreId = if (selectedCategory == PC_CATEGORY_PICKUP) selectedStoreId else ""
        val finalStoreName = if (selectedCategory == PC_CATEGORY_PICKUP) selectedStoreName else ""
        val finalPickupCount = if (selectedCategory == PC_CATEGORY_PICKUP) pickupCount else 0
        // Pickup carries quantities only, never a money amount at request time
        // (0 goes in; the settled amount is filled at approve/settle time).
        // Pickup carries quantities only, never a TYPED money amount —
        // but a store with a fixed conveyance_amount sets the requested
        // amount (readonly prefill above); without one it's 0 as before.
        val finalAmount = when {
            selectedCategory == PC_CATEGORY_PICKUP && selectedStoreAmount > 0 -> selectedStoreAmount
            selectedCategory == PC_CATEGORY_PICKUP -> 0.0
            else -> amount
        }
        val finalVehicle = if (isConveyanceSubmit) selectedVehicle else ""
        // Human-readable area LABELS ("Office", store area name) — from/to
        // are display-only (Top Sheet/PDF), nothing joins on them, so raw
        // area ids must never reach the table.
        val finalFromArea = if (isConveyanceSubmit) officeLabel(selectedFromAreaLabel) else ""
        val finalToArea = if (isConveyanceSubmit) officeLabel(selectedToAreaLabel) else ""
        // Fully derived, never typed (no qty fields on the form): Pickup
        // mirrors the pickup count, Bulk Delivery is exactly 1 consignment,
        // LOT rows are 1 consignment each (one claim per parcel).
        val finalAttemptQuantity = when {
            selectedCategory == PC_CATEGORY_PICKUP -> pickupCount
            isConveyanceSubmit -> 1
            else -> 0
        }
        val finalDeliveredQuantity = finalAttemptQuantity
        // Always derived, never typed directly -- no etCidOrMerchant field exists
        // in the layout anymore. Mirrors Consignment ID for Bulk-like
        // conveyance, the picked store's name for Pickup, the From<>To route
        // for Inter Change.
        val finalCidOrMerchant = when {
            selectedCategory == PC_CATEGORY_PICKUP -> finalStoreName
            isRouteBasedCategory(selectedCategory) -> "$finalFromArea<>$finalToArea"
            isConveyanceSubmit -> finalConsignmentId
            else -> ""
        }

        setSaving(true)
        if (isEditMode) {
            // NOTE: updateRequest() has no attachment param — editing an existing
            // request cannot currently change its attachments, only create
            // (submitRequest, below) can. Pre-existing limitation, out of scope
            // for wiring the upload itself; if editing the attachment is wanted
            // later, updateRequest() needs an attachmentUrl/attachmentName param
            // added alongside the other fields it already updates.
            lifecycleScope.launch {
                val result = viewModel.updateRequest(
                    branchId, editRequestId, selectedCategory, purpose, finalAmount,
                    consignmentId = finalConsignmentId, storeId = finalStoreId, storeName = finalStoreName,
                    pickupCount = finalPickupCount,
                    vehicle = finalVehicle, fromArea = finalFromArea, toArea = finalToArea,
                    attemptQuantity = finalAttemptQuantity, deliveredQuantity = finalDeliveredQuantity,
                    cidOrMerchant = finalCidOrMerchant,
                    requestedDate = selectedExpenseDate,
                    onSupabaseResult = { ok ->
                        activity?.runOnUiThread {
                            if (isAdded) Toast.makeText(requireContext(),
                                if (ok) "✓ Supabase saved" else "⚠ Supabase save failed", Toast.LENGTH_SHORT).show()
                        }
                    },
                    allowStaff = userRoles?.isAnyApprover == true
                )
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), "✓ Request updated", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                } else {
                    setSaving(false)
                    // Human-readable toast so the agent understands, plus the
                    // exact technical reason in a copyable dialog for chat.
                    val friendly = UserErrorText.forSaveFailure(result.exceptionOrNull())
                    if (isAdded) {
                        Toast.makeText(requireContext(), friendly, Toast.LENGTH_LONG).show()
                        SupabaseErrorDialog.show(requireContext(), friendly,
                            result.exceptionOrNull()?.message ?: "Update failed")
                    }
                }
            }
        } else {
            lifecycleScope.launch {
                val sharedAttachments = formAttachments
                    .filter { it.objectKey.isNotBlank() && !it.failed }
                    .map { AttachmentRef(it.objectKey, it.displayName, it.sizeBytes) }
                // One receipt set for the whole submission — LOT rows share the
                // same R2 keys (see ClaimsRepository.delete: purge skips keys
                // still referenced by another row).
                val requesterRole = RbacManager.current.roleName.ifBlank { RbacManager.current.roleId }
                if (isLotSubmit) {
                    // Separate-rows LOT: one claim per consignment at the same
                    // rate, so every parcel is its own table row downstream
                    // (lists, voucher, Excel). Per-row idempotency keys, so a
                    // retry after partial failure returns ok-duplicate for the
                    // rows that already landed instead of doubling them.
                    val ids = lotConsignments.toList()
                    val rate = amount
                    val failures = mutableListOf<String>()
                    var ok = 0
                    ids.forEachIndexed { index, cid ->
                        if (!isAdded) return@forEachIndexed
                        activity?.runOnUiThread {
                            if (isAdded) btnSubmit.text = "⏳ ${index + 1}/${ids.size}..."
                        }
                        val r = viewModel.submitRequest(
                            branchId = branchId,
                            category = selectedCategory,
                            purpose = purpose,
                            amount = rate,
                            attachments = sharedAttachments,
                            requesterRole = requesterRole,
                            consignmentId = cid,
                            storeId = "", storeName = "",
                            pickupCount = 0,
                            vehicle = finalVehicle, fromArea = finalFromArea, toArea = finalToArea,
                            attemptQuantity = 1, deliveredQuantity = 1,
                            cidOrMerchant = cid,
                            requestedDate = selectedExpenseDate,
                            clientSubmitId = "$formSubmitId-lot-$index",
                            onSupabaseResult = {},
                            onBehalfSystemId = behalf?.systemId.orEmpty(),
                            onBehalfUid = behalf?.firebaseId.orEmpty(),
                            onBehalfName = behalf?.name.orEmpty(),
                            onBehalfRole = behalf?.role.orEmpty()
                        )
                        if (r.isSuccess) ok++
                        else failures.add("$cid: ${r.exceptionOrNull()?.message ?: "failed"}")
                    }
                    if (!isAdded) return@launch
                    if (failures.isEmpty()) {
                        Toast.makeText(requireContext(),
                            "✓ $ok requests submitted · Total ${pettyCashTaka(rate * ok)}",
                            Toast.LENGTH_LONG).show()
                        parentFragmentManager.popBackStack()
                    } else {
                        setSaving(false)
                        val friendly = "⚠ ${failures.size} failed — $ok submitted, retry the rest"
                        Toast.makeText(requireContext(), friendly, Toast.LENGTH_LONG).show()
                        SupabaseErrorDialog.show(requireContext(), friendly, failures.joinToString("\n"))
                    }
                    return@launch
                }
                val result = viewModel.submitRequest(
                    branchId = branchId,
                    category = selectedCategory,
                    purpose = purpose,
                    amount = finalAmount,
                    attachments = formAttachments
                        .filter { it.objectKey.isNotBlank() && !it.failed }
                        .map { AttachmentRef(it.objectKey, it.displayName, it.sizeBytes) },
                    requesterRole = RbacManager.current.roleName.ifBlank { RbacManager.current.roleId },
                    consignmentId = finalConsignmentId,
                    storeId = finalStoreId,
                    storeName = finalStoreName,
                    pickupCount = finalPickupCount,
                    vehicle = finalVehicle, fromArea = finalFromArea, toArea = finalToArea,
                    attemptQuantity = finalAttemptQuantity, deliveredQuantity = finalDeliveredQuantity,
                    cidOrMerchant = finalCidOrMerchant,
                    requestedDate = selectedExpenseDate,
                    clientSubmitId = formSubmitId,
                    onBehalfSystemId = behalf?.systemId.orEmpty(),
                    onBehalfUid = behalf?.firebaseId.orEmpty(),
                    onBehalfName = behalf?.name.orEmpty(),
                    onBehalfRole = behalf?.role.orEmpty(),
                    onSupabaseResult = { ok ->
                        activity?.runOnUiThread {
                            if (isAdded) Toast.makeText(requireContext(),
                                if (ok) "✓ Supabase saved" else "⚠ Supabase save failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), "✓ Request ${result.getOrNull()} submitted", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                } else {
                    setSaving(false)
                    // Human-readable toast so the agent understands, plus the
                    // exact technical reason in a copyable dialog for chat.
                    val friendly = UserErrorText.forSaveFailure(result.exceptionOrNull())
                    if (isAdded) {
                        Toast.makeText(requireContext(), friendly, Toast.LENGTH_LONG).show()
                        SupabaseErrorDialog.show(requireContext(), friendly,
                            result.exceptionOrNull()?.message ?: "Submit failed")
                    }
                }
            }
        }
    }
}
