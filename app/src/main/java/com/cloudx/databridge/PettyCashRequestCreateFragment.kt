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
 * approval chain: category, amount, purpose, optional attachment. Submits
 * via PettyCashViewModel.submitRequest(), or — when opened with an
 * editRequestId — edits an existing PENDING request via updateRequest().
 * Edit is only reachable from Settlement Details' Edit button, which
 * itself only shows for the request's own submitter while status is still
 * PENDING (before Staff has acknowledged it) — this fragment
 * doesn't re-check that beyond trusting the caller, since
 * updateRequest()/deleteRequest() re-validate ownership and status
 * server-side (well, ViewModel-side) regardless.
 *
 * Category-specific fields: Bulk Delivery shows a Consignment ID text
 * field; Pickup shows a Store picker (fetched from Supabase's public.stores,
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
 * the actual upload credentials never reach this app). Accepts any image
 * format or PDF, capped at AttachmentUploader.MAX_FILE_BYTES (5 MB); the
 * Edge Function independently re-enforces both, since a client-side check
 * alone can always be bypassed by a modified APK calling the function
 * directly.
 */
class PettyCashRequestCreateFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private var branchId: String = ""
    private var editRequestId: String = ""
    private val isEditMode: Boolean get() = editRequestId.isNotBlank()

    private val categoryOptionsFallback = listOf(PC_CATEGORY_BULK_DELIVERY, PC_CATEGORY_PICKUP)
    private var categoryOptions: List<String> = categoryOptionsFallback
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
    private var selectedVehicle: String = ""
    // "OFFICE" is a sentinel, not a real courier/areas entry — see
    // applyConveyanceDefaults(). pickupAreas/deliveryAreas load once and are reused
    // for both From and To pickers (Pickup uses pickup_area for From, Bulk Delivery
    // uses delivery_area for To — see FirebasePaths.deliveryAreas()/pickupAreas()).
    private var pickupAreas: List<Area> = emptyList()
    private var deliveryAreas: List<Area> = emptyList()
    private var areasLoaded = false
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
            ?: (category == PC_CATEGORY_PICKUP || category == PC_CATEGORY_BULK_DELIVERY)

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
                    Toast.makeText(requireContext(), "⚠ আপনার স্ক্যান সঠিক নয়। পুনরায় স্ক্যান করুন।", Toast.LENGTH_LONG).show()
                else -> {
                    etConsignmentId.setText(code)
                    etConsignmentId.setSelection(code.length)
                }
            }
        }
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvCategorySelected: TextView
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
    private lateinit var etPurpose: EditText
    private lateinit var tvPurposeCount: TextView
    private lateinit var btnSubmit: android.widget.Button
    private lateinit var pbSaving: android.widget.ProgressBar

    companion object {
        const val MAX_ATTACHMENTS = 5

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
        layoutFromArea = view.findViewById(R.id.layoutPcRequestFromArea)
        tvFromAreaSelected = view.findViewById(R.id.tvPcRequestFromAreaSelected)
        layoutToArea = view.findViewById(R.id.layoutPcRequestToArea)
        tvToAreaSelected = view.findViewById(R.id.tvPcRequestToAreaSelected)
        // No quantity fields on the form (removed): quantities are fully
        // derived at submit — Pickup mirrors the pickup count, Bulk Delivery
        // is exactly 1 consignment, anything else 0.

        groupAmount = view.findViewById(R.id.groupPcAmount)
        etAmount = view.findViewById(R.id.etPcRequestAmount)
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

        if (!isEditMode && !RbacManager.hasPermission("petty_cash_requester")) {
            Toast.makeText(requireContext(), "Your role isn't set up to submit petty cash requests", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            return
        }

        tvTitle.text = if (isEditMode) "Edit Request" else "New Petty Cash Request"
        btnSubmit.text = if (isEditMode) "Update Request" else "Submit Request"

        view.findViewById<View>(R.id.btnPcRequestCreateBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<View>(R.id.layoutPcRequestCategory).setOnClickListener { showCategoryPicker() }
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
        if (request.status != PC_STATUS_PENDING) {
            Toast.makeText(requireContext(), "This request can no longer be edited", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            return
        }
        applyCategory(request.category)
        etAmount.setText(if (request.amount > 0) request.amount.toInt().toString() else "")
        etPurpose.setText(request.purpose)
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
        prefilled = true
    }

    private fun showCategoryPicker() {
        val options = categoryOptions.ifEmpty { categoryOptionsFallback }
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
        // id for every other conveyance category (Bulk Delivery today,
        // InterChange tomorrow). Non-conveyance categories get neither.
        val isConveyance = isConveyanceCategory(category)
        groupConsignment.isVisible = isConveyance && category != PC_CATEGORY_PICKUP
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
        // Pickup (or vice versa) and get submitted anyway.
        if (!isConveyance || category == PC_CATEGORY_PICKUP) etConsignmentId.setText("")
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
            hint = "Search merchant..."
            setTextColor(android.graphics.Color.parseColor("#0F172A"))
            setHintTextColor(android.graphics.Color.parseColor("#94A3B8"))
        }
        container.addView(etSearch)
        val listView = ListView(ctx).apply { choiceMode = ListView.CHOICE_MODE_SINGLE }
        container.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp * 300))
        var filtered = stores.toMutableList()
        fun makeAdapter(list: List<Store>) = object : ArrayAdapter<Store>(
            ctx, android.R.layout.simple_list_item_single_choice, android.R.id.text1, list) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getView(pos, cv, parent)
                val s = list[pos]
                val label = if (s.conveyanceAmount > 0) "${s.name}  (৳${formatAmount(s.conveyanceAmount)})" else s.name
                (v.findViewById<View>(android.R.id.text1) as? TextView)?.text = label
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
                filtered = if (q.isEmpty()) stores.toMutableList()
                else stores.filter { it.name.contains(q, ignoreCase = true) }.toMutableList()
                listView.adapter = makeAdapter(filtered)
            }
        })
        dialog.show()
    }

    private fun loadStores() {
        // Stores live in Supabase's public.stores (see
        // SupabaseClaimsReader.fetchStores) — the old Firebase
        // courier/stores read is removed. Areas + consignment preview below
        // stay on Firebase: courier-directory data, out of scope for the
        // Petty Cash cutover.
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

    /** Loads both area directories once. Pickup areas populate Pickup's From
     *  picker; delivery areas populate Bulk Delivery's To picker. */
    private fun loadAreas() {
        val db = FirebaseDatabase.getInstance().reference
        db.child(FirebasePaths.pickupAreas()).get()
            .addOnSuccessListener { snap ->
                pickupAreas = snap.children
                    .mapNotNull { it.getValue(Area::class.java)?.copy(id = it.key.orEmpty()) }
                    .sortedBy { it.name }
                areasLoaded = true
            }
            .addOnFailureListener { areasLoaded = true }
        db.child(FirebasePaths.deliveryAreas()).get()
            .addOnSuccessListener { snap ->
                deliveryAreas = snap.children
                    .mapNotNull { it.getValue(Area::class.java)?.copy(id = it.key.orEmpty()) }
                    .sortedBy { it.name }
            }
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
     *  directory appropriate to the selected conveyance category. */
    private fun showAreaPicker(forFrom: Boolean) {
        if (!areasLoaded) {
            Toast.makeText(requireContext(), "Still loading area list, try again in a moment", Toast.LENGTH_SHORT).show()
            return
        }
        val areas = if (selectedCategory == PC_CATEGORY_PICKUP) pickupAreas else deliveryAreas
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
     *  Store.areaId/areaName) but stays freely changeable via showAreaPicker(),
     *  not locked to the store's area. Bulk Delivery: From defaults 'Office' (a
     *  bulk delivery always starts at the office); To is a plain, unprefilled
     *  dropdown — Bulk Delivery has no store to prefill from (Consignment ID
     *  instead of a store picker). Mirrors the same Office-default/store-prefill
     *  logic already confirmed for the remark-picker's Vehicle/From/To fields. */
    private fun applyConveyanceDefaults(category: String) {
        if (category == PC_CATEGORY_PICKUP) {
            selectedToArea = "OFFICE"; selectedToAreaLabel = "Office"
            tvToAreaSelected.text = "Office"
            if (selectedStoreId.isNotBlank()) {
                val store = stores.find { it.storeId == selectedStoreId }
                if (store != null && store.areaId.isNotBlank()) {
                    selectedFromArea = store.areaId
                    selectedFromAreaLabel = store.areaName.ifBlank { store.areaId }
                    tvFromAreaSelected.text = selectedFromAreaLabel
                }
            }
        } else if (category == PC_CATEGORY_BULK_DELIVERY) {
            selectedFromArea = "OFFICE"; selectedFromAreaLabel = "Office"
            tvFromAreaSelected.text = "Office"
        }
    }

    /** Resolves a stored areaId (or the "OFFICE" sentinel) back to a display label,
     *  for prefillIfEditing() — falls back to the raw id if the area lists haven't
     *  loaded yet or the id isn't found in either list (still functionally correct,
     *  just shows the raw id instead of a friendly name in that edge case). */
    private fun areaLabelFor(areaId: String): String {
        if (areaId == "OFFICE") return "Office"
        return pickupAreas.find { it.areaId == areaId }?.name
            ?: deliveryAreas.find { it.areaId == areaId }?.name
            ?: areaId
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
                    tvConsignmentPreview.text = "⚠ এই ID-তে কোনো consignment পাওয়া যায়নি"
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
                    "Couldn't attach ${row.displayName} — try a smaller photo or PDF", Toast.LENGTH_LONG).show()
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
        if (isConveyanceSubmit && selectedCategory != PC_CATEGORY_PICKUP && consignmentId.isBlank()) {
            Toast.makeText(requireContext(), "Enter the consignment ID", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedCategory != PC_CATEGORY_PICKUP && amount <= 0.0) {
            Toast.makeText(requireContext(), "Enter a valid amount", Toast.LENGTH_SHORT).show()
            return
        }
        if (purpose.isBlank()) {
            Toast.makeText(requireContext(), "Describe the purpose", Toast.LENGTH_SHORT).show()
            return
        }

        val finalConsignmentId = if (isConveyanceSubmit && selectedCategory != PC_CATEGORY_PICKUP) consignmentId else ""
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
        val finalFromArea = if (isConveyanceSubmit) selectedFromAreaLabel else ""
        val finalToArea = if (isConveyanceSubmit) selectedToAreaLabel else ""
        // Fully derived, never typed (no qty fields on the form): Pickup
        // mirrors the pickup count, Bulk Delivery is exactly 1 consignment.
        val finalAttemptQuantity = when {
            selectedCategory == PC_CATEGORY_PICKUP -> pickupCount
            isConveyanceSubmit -> 1
            else -> 0
        }
        val finalDeliveredQuantity = finalAttemptQuantity
        // Always derived, never typed directly -- no etCidOrMerchant field exists
        // in the layout anymore. Mirrors Consignment ID for Bulk-like
        // conveyance, the picked store's name for Pickup.
        val finalCidOrMerchant = when {
            selectedCategory == PC_CATEGORY_PICKUP -> finalStoreName
            isConveyanceSubmit -> finalConsignmentId
            else -> ""
        }

        setSaving(true)
        if (isEditMode) {
            // NOTE: updateRequest() has no attachment param — editing an existing
            // PENDING request cannot currently change its attachments, only create
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
                    }
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
                val result = viewModel.submitRequest(
                    branchId = branchId,
                    category = selectedCategory,
                    purpose = purpose,
                    amount = finalAmount,
                    attachments = formAttachments
                        .filter { it.objectKey.isNotBlank() && !it.failed }
                        .map { AttachmentRef(it.objectKey, it.displayName, it.sizeBytes) },
                    workerRole = RbacManager.current.roleName.ifBlank { RbacManager.current.roleId },
                    consignmentId = finalConsignmentId,
                    storeId = finalStoreId,
                    storeName = finalStoreName,
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
