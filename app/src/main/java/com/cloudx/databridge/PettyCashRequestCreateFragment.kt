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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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
 * field; Pickup shows a Store picker (fetched from courier/stores, see
 * FirebasePaths.stores() — a courier-wide directory shared with the rest
 * of the courier flow, not Petty-Cash-specific; managed from Config's
 * Stores tab, see ConfigStoresFragment). Formerly a bare "Merchant Name"
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

    private val categoryOptions = listOf(PC_CATEGORY_BULK_DELIVERY, PC_CATEGORY_PICKUP)
    private var stores: List<Store> = emptyList()
    private var storesLoaded = false

    private var selectedCategory: String = ""
    private var selectedStoreId: String = ""
    private var selectedStoreName: String = ""

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

    // Attachment state. attachmentUrl is what actually gets saved onto the
    // request (empty until upload succeeds) — despite the name, this holds
    // an R2 *object key*, not a URL: the bucket is private, so there's no
    // standing public URL for it. Viewing the attachment later means asking
    // AttachmentUploader.getDownloadUrl() for a fresh presigned URL each
    // time, using this stored key. attachmentName is shown to the user
    // immediately on pick, before the upload finishes, so the picker
    // doesn't look like it did nothing while the network call is in flight.
    private var attachmentName: String = ""
    private var attachmentUrl: String = ""
    private var attachmentUploading = false

    private val consignmentPreviewHandler = Handler(Looper.getMainLooper())
    private var consignmentPreviewRunnable: Runnable? = null

    private val attachmentPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        onAttachmentPicked(uri)
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
    private lateinit var etAttemptQuantity: EditText
    private lateinit var etDeliveredQuantity: EditText
    private lateinit var etCidOrMerchant: EditText
    private lateinit var groupConveyance: View
    private lateinit var groupAmount: View
    private lateinit var etAmount: EditText
    private lateinit var etPurpose: EditText
    private lateinit var tvPurposeCount: TextView
    private lateinit var tvAttachmentName: TextView
    private lateinit var btnSubmit: android.widget.Button

    companion object {
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
        etAttemptQuantity = view.findViewById(R.id.etPcRequestAttemptQuantity)
        etDeliveredQuantity = view.findViewById(R.id.etPcRequestDeliveredQuantity)
        etCidOrMerchant = view.findViewById(R.id.etPcRequestCidOrMerchant)
        groupAmount = view.findViewById(R.id.groupPcAmount)
        etAmount = view.findViewById(R.id.etPcRequestAmount)
        etPurpose = view.findViewById(R.id.etPcRequestPurpose)
        tvPurposeCount = view.findViewById(R.id.tvPcRequestPurposeCount)
        tvAttachmentName = view.findViewById(R.id.tvPcRequestAttachmentName)
        btnSubmit = view.findViewById(R.id.btnPcRequestSubmit)

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
        view.findViewById<View>(R.id.layoutPcRequestAttachment).setOnClickListener {
            if (attachmentUploading) return@setOnClickListener // ignore taps mid-upload
            attachmentPicker.launch(AttachmentUploader.PICKER_MIME_TYPE)
        }

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
        if (request.attemptQuantity > 0) etAttemptQuantity.setText(request.attemptQuantity.toString())
        if (request.deliveredQuantity > 0) etDeliveredQuantity.setText(request.deliveredQuantity.toString())
        if (request.cidOrMerchant.isNotBlank()) etCidOrMerchant.setText(request.cidOrMerchant)
        prefilled = true
    }

    private fun showCategoryPicker() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Category")
            .setItems(categoryOptions.toTypedArray()) { _, index -> applyCategory(categoryOptions[index]) }
            .show()
    }

    /** Sets the selected category and shows/hides the category-specific field group. */
    private fun applyCategory(category: String) {
        selectedCategory = category
        tvCategorySelected.text = category
        tvCategorySelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))

        groupConsignment.isVisible = category == PC_CATEGORY_BULK_DELIVERY
        groupStore.isVisible = category == PC_CATEGORY_PICKUP
        groupConveyance.isVisible = category == PC_CATEGORY_PICKUP || category == PC_CATEGORY_BULK_DELIVERY
        // Pickup: the Requester submits without knowing the final cost yet (that comes
        // back from the store), so Staff fills the amount in after the fact instead —
        // see the "next role edits amount" step. Hidden here, not just unrequired, so
        // it can't look like a forgotten/blank field on the Requester's own screen.
        groupAmount.isVisible = category != PC_CATEGORY_PICKUP

        // Switching category clears the other category's field so a
        // half-filled Consignment ID doesn't silently survive a switch to
        // Pickup (or vice versa) and get submitted anyway.
        if (category != PC_CATEGORY_BULK_DELIVERY) etConsignmentId.setText("")
        if (category != PC_CATEGORY_PICKUP) {
            selectedStoreId = ""
            selectedStoreName = ""
            tvStoreSelected.text = "Select Store"
            tvStoreSelected.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            etPickupCount.setText("")
        } else {
            etAmount.setText("")
        }
        if (groupConveyance.isVisible) applyConveyanceDefaults(category)
    }

    private fun showStorePicker() {
        if (!storesLoaded) {
            Toast.makeText(requireContext(), "Still loading store list, try again in a moment", Toast.LENGTH_SHORT).show()
            return
        }
        if (stores.isEmpty()) {
            Toast.makeText(requireContext(), "No stores available — contact your admin to add some", Toast.LENGTH_LONG).show()
            return
        }
        val names = stores.map { it.name }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Store")
            .setItems(names) { _, index ->
                selectedStoreId = stores[index].storeId
                selectedStoreName = stores[index].name
                tvStoreSelected.text = selectedStoreName
                tvStoreSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
                applyConveyanceDefaults(PC_CATEGORY_PICKUP)
            }
            .show()
    }

    private fun loadStores() {
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .reference.child(FirebasePaths.stores())
            .get()
            .addOnSuccessListener { snap ->
                stores = snap.children
                    .mapNotNull { it.getValue(Store::class.java)?.copy(id = it.key.orEmpty()) }
                    .sortedBy { it.name }
                storesLoaded = true
            }
            .addOnFailureListener {
                storesLoaded = true // don't leave the picker stuck saying "still loading" forever
                Toast.makeText(requireContext(), "Couldn't load store list: ${it.message}", Toast.LENGTH_SHORT).show()
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
            .setTitle(if (forFrom) "Select From" else "Select To")
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

    private fun onAttachmentPicked(uri: Uri) {
        val meta = AttachmentUploader.readFileMeta(requireContext(), uri)
        // Show the picked name right away so the tap feels responsive, before
        // the network round trip finishes — attachmentUrl stays blank until
        // upload actually succeeds, so onSubmit() can't send a URL that
        // doesn't point at anything.
        tvAttachmentName.text = meta?.displayName ?: "Uploading…"
        tvAttachmentName.setTextColor(android.graphics.Color.parseColor("#0F172A"))
        attachmentName = meta?.displayName.orEmpty()
        attachmentUrl = ""
        attachmentUploading = true
        btnSubmit.isEnabled = false // an in-flight upload shouldn't let Submit fire without it

        lifecycleScope.launch {
            when (val result = AttachmentUploader.upload(requireContext(), uri)) {
                is AttachmentUploader.Result.Success -> {
                    attachmentUrl = result.objectKey
                    attachmentName = result.displayName
                    if (isAdded) {
                        tvAttachmentName.text = result.displayName
                        tvAttachmentName.setTextColor(android.graphics.Color.parseColor("#0F172A"))
                    }
                }
                is AttachmentUploader.Result.Rejected -> {
                    attachmentName = ""
                    if (isAdded) {
                        tvAttachmentName.text = "No file selected"
                        tvAttachmentName.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                        Toast.makeText(requireContext(), result.reason, Toast.LENGTH_LONG).show()
                    }
                }
                is AttachmentUploader.Result.Failed -> {
                    attachmentName = ""
                    if (isAdded) {
                        tvAttachmentName.text = "No file selected"
                        tvAttachmentName.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            attachmentUploading = false
            if (isAdded) btnSubmit.isEnabled = true
        }
    }

    private fun onSubmit() {
        if (attachmentUploading) {
            Toast.makeText(requireContext(), "Attachment is still uploading — please wait", Toast.LENGTH_SHORT).show()
            return
        }
        val amount = etAmount.text?.toString()?.toDoubleOrNull() ?: 0.0
        val purpose = etPurpose.text?.toString().orEmpty().trim()
        val consignmentId = etConsignmentId.text?.toString().orEmpty().trim()
        val pickupCount = etPickupCount.text?.toString()?.trim()?.toIntOrNull() ?: 0
        val attemptQuantity = etAttemptQuantity.text?.toString()?.trim()?.toIntOrNull() ?: 0
        val deliveredQuantity = etDeliveredQuantity.text?.toString()?.trim()?.toIntOrNull() ?: 0
        val cidOrMerchant = etCidOrMerchant.text?.toString().orEmpty().trim()

        if (branchId.isBlank()) {
            Toast.makeText(requireContext(), "No branch selected", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedCategory.isBlank()) {
            Toast.makeText(requireContext(), "Select a category", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedCategory == PC_CATEGORY_BULK_DELIVERY && consignmentId.isBlank()) {
            Toast.makeText(requireContext(), "Enter the consignment ID", Toast.LENGTH_SHORT).show()
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
        if (selectedCategory != PC_CATEGORY_PICKUP && amount <= 0.0) {
            Toast.makeText(requireContext(), "Enter a valid amount", Toast.LENGTH_SHORT).show()
            return
        }
        if (purpose.isBlank()) {
            Toast.makeText(requireContext(), "Describe the purpose", Toast.LENGTH_SHORT).show()
            return
        }

        val finalConsignmentId = if (selectedCategory == PC_CATEGORY_BULK_DELIVERY) consignmentId else ""
        val finalStoreId = if (selectedCategory == PC_CATEGORY_PICKUP) selectedStoreId else ""
        val finalStoreName = if (selectedCategory == PC_CATEGORY_PICKUP) selectedStoreName else ""
        val finalPickupCount = if (selectedCategory == PC_CATEGORY_PICKUP) pickupCount else 0
        val finalAmount = if (selectedCategory == PC_CATEGORY_PICKUP) 0.0 else amount
        val isConveyanceCategory = selectedCategory == PC_CATEGORY_PICKUP || selectedCategory == PC_CATEGORY_BULK_DELIVERY
        val finalVehicle = if (isConveyanceCategory) selectedVehicle else ""
        val finalFromArea = if (isConveyanceCategory) selectedFromArea else ""
        val finalToArea = if (isConveyanceCategory) selectedToArea else ""
        val finalAttemptQuantity = if (isConveyanceCategory) attemptQuantity else 0
        val finalDeliveredQuantity = if (isConveyanceCategory) deliveredQuantity else 0
        val finalCidOrMerchant = if (isConveyanceCategory) cidOrMerchant else ""

        btnSubmit.isEnabled = false
        if (isEditMode) {
            // NOTE: updateRequest() has no attachment param — editing an existing
            // PENDING request cannot currently change its attachment, only create
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
                    onSupabaseResult = { ok ->
                        activity?.runOnUiThread {
                            if (isAdded) Toast.makeText(requireContext(),
                                if (ok) "✓ Supabase saved" else "⚠ Supabase save failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), "✓ Firebase saved — Request updated", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                } else {
                    btnSubmit.isEnabled = true
                    Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Update failed", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            lifecycleScope.launch {
                val result = viewModel.submitRequest(
                    branchId = branchId,
                    category = selectedCategory,
                    purpose = purpose,
                    amount = finalAmount,
                    priority = PC_PRIORITY_NORMAL,
                    attachmentUrl = attachmentUrl,
                    attachmentName = attachmentName,
                    workerRole = RbacManager.current.roleName.ifBlank { RbacManager.current.roleId },
                    consignmentId = finalConsignmentId,
                    storeId = finalStoreId,
                    storeName = finalStoreName,
                    pickupCount = finalPickupCount,
                    vehicle = finalVehicle, fromArea = finalFromArea, toArea = finalToArea,
                    attemptQuantity = finalAttemptQuantity, deliveredQuantity = finalDeliveredQuantity,
                    cidOrMerchant = finalCidOrMerchant,
                    onSupabaseResult = { ok ->
                        activity?.runOnUiThread {
                            if (isAdded) Toast.makeText(requireContext(),
                                if (ok) "✓ Supabase saved" else "⚠ Supabase save failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), "✓ Firebase saved — Request ${result.getOrNull()} submitted", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                } else {
                    btnSubmit.isEnabled = true
                    Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Submit failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
