package com.cloudx.databridge

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

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
 * PENDING (before Team Aligned has acknowledged it) — this fragment
 * doesn't re-check that beyond trusting the caller, since
 * updateRequest()/deleteRequest() re-validate ownership and status
 * server-side (well, ViewModel-side) regardless.
 *
 * Category-specific fields: Bulk Delivery shows a Consignment ID text
 * field; Pickup shows a Merchant Name picker. Merchant names are a
 * hardcoded demo list — that's been replaced with a real fetch from
 * courier/merchants (see FirebasePaths.merchants()), a courier-wide
 * directory shared with the rest of the courier flow, not Petty-Cash-
 * specific.
 *
 * Attachment upload to Firebase Storage isn't wired yet — the picker just
 * captures a display name for now (attachmentUrl stays blank). Full file
 * upload can follow the same pattern as Branch's image upload
 * (uploadImageIfNeeded in BranchCreateFragment) when needed.
 *
 * Requested Date defaults to today and can be backdated (not postdated —
 * this is when the expense happened, not a future plan) via a date picker,
 * matching the mockup's field. Separate from createdAt (submission time).
 */
class PettyCashRequestCreateFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private var branchId: String = ""
    private var editRequestId: String = ""
    private val isEditMode: Boolean get() = editRequestId.isNotBlank()

    private val categoryOptions = listOf(PC_CATEGORY_BULK_DELIVERY, PC_CATEGORY_PICKUP)
    private var merchants: List<Merchant> = emptyList()
    private var merchantsLoaded = false

    private var selectedCategory: String = ""
    private var selectedMerchant: String = ""
    private var attachmentName: String = ""
    private var selectedDateMillis: Long = System.currentTimeMillis()

    private lateinit var tvTitle: TextView
    private lateinit var tvCategorySelected: TextView
    private lateinit var groupConsignment: View
    private lateinit var etConsignmentId: EditText
    private lateinit var groupMerchant: View
    private lateinit var tvMerchantSelected: TextView
    private lateinit var etAmount: EditText
    private lateinit var etPurpose: EditText
    private lateinit var tvPurposeCount: TextView
    private lateinit var tvDateSelected: TextView
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
        groupMerchant = view.findViewById(R.id.groupPcRequestMerchant)
        tvMerchantSelected = view.findViewById(R.id.tvPcRequestMerchantSelected)
        etAmount = view.findViewById(R.id.etPcRequestAmount)
        etPurpose = view.findViewById(R.id.etPcRequestPurpose)
        tvPurposeCount = view.findViewById(R.id.tvPcRequestPurposeCount)
        tvDateSelected = view.findViewById(R.id.tvPcRequestDateSelected)
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
        view.findViewById<View>(R.id.layoutPcRequestMerchant).setOnClickListener { showMerchantPicker() }
        view.findViewById<View>(R.id.layoutPcRequestAttachment).setOnClickListener { showAttachmentPicker(view) }
        view.findViewById<View>(R.id.layoutPcRequestDate).setOnClickListener { showDatePicker() }
        applyDate(selectedDateMillis) // defaults to today until edited or prefilled

        etPurpose.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                tvPurposeCount.text = "${s?.length ?: 0}/300"
            }
        })

        btnSubmit.setOnClickListener { onSubmit() }

        loadMerchants()

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
        if (request.requestedDate != 0L) applyDate(request.requestedDate)
        if (request.consignmentId.isNotBlank()) etConsignmentId.setText(request.consignmentId)
        if (request.merchantName.isNotBlank()) {
            selectedMerchant = request.merchantName
            tvMerchantSelected.text = request.merchantName
            tvMerchantSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
        }
        prefilled = true
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply { set(year, month, day, 0, 0, 0) }
                applyDate(picked.timeInMillis)
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).apply {
            // Requested Date is when the expense happened, not a future plan.
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun applyDate(millis: Long) {
        selectedDateMillis = millis
        tvDateSelected.text = SimpleDateFormat("dd MMM yyyy", Locale.US).format(java.util.Date(millis))
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
        groupMerchant.isVisible = category == PC_CATEGORY_PICKUP

        // Switching category clears the other category's field so a
        // half-filled Consignment ID doesn't silently survive a switch to
        // Pickup (or vice versa) and get submitted anyway.
        if (category != PC_CATEGORY_BULK_DELIVERY) etConsignmentId.setText("")
        if (category != PC_CATEGORY_PICKUP) {
            selectedMerchant = ""
            tvMerchantSelected.text = "Select Merchant"
            tvMerchantSelected.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
        }
    }

    private fun showMerchantPicker() {
        if (!merchantsLoaded) {
            Toast.makeText(requireContext(), "Still loading merchant list, try again in a moment", Toast.LENGTH_SHORT).show()
            return
        }
        if (merchants.isEmpty()) {
            Toast.makeText(requireContext(), "No merchants available — contact your admin to add some", Toast.LENGTH_LONG).show()
            return
        }
        val names = merchants.map { it.name }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Merchant")
            .setItems(names) { _, index ->
                selectedMerchant = names[index]
                tvMerchantSelected.text = selectedMerchant
                tvMerchantSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
            }
            .show()
    }

    private fun loadMerchants() {
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .reference.child(FirebasePaths.merchants())
            .get()
            .addOnSuccessListener { snap ->
                merchants = snap.children
                    .mapNotNull { it.getValue(Merchant::class.java)?.copy(id = it.key.orEmpty()) }
                    .sortedBy { it.name }
                merchantsLoaded = true
            }
            .addOnFailureListener {
                merchantsLoaded = true // don't leave the picker stuck saying "still loading" forever
                Toast.makeText(requireContext(), "Couldn't load merchant list: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showAttachmentPicker(root: View) {
        // Placeholder until Storage upload is wired — captures a name only,
        // matching the "attach a receipt" intent without a real file picker yet.
        attachmentName = "receipt_${System.currentTimeMillis().toString().takeLast(4)}.jpg"
        root.findViewById<TextView>(R.id.tvPcRequestAttachmentName).apply {
            text = attachmentName
            setTextColor(android.graphics.Color.parseColor("#0F172A"))
        }
        Toast.makeText(requireContext(), "Attachment picker not wired yet — using a placeholder name", Toast.LENGTH_SHORT).show()
    }

    private fun onSubmit() {
        val amount = etAmount.text?.toString()?.toDoubleOrNull() ?: 0.0
        val purpose = etPurpose.text?.toString().orEmpty().trim()
        val consignmentId = etConsignmentId.text?.toString().orEmpty().trim()

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
        if (selectedCategory == PC_CATEGORY_PICKUP && selectedMerchant.isBlank()) {
            Toast.makeText(requireContext(), "Select a merchant", Toast.LENGTH_SHORT).show()
            return
        }
        if (amount <= 0.0) {
            Toast.makeText(requireContext(), "Enter a valid amount", Toast.LENGTH_SHORT).show()
            return
        }
        if (purpose.isBlank()) {
            Toast.makeText(requireContext(), "Describe the purpose", Toast.LENGTH_SHORT).show()
            return
        }

        val finalConsignmentId = if (selectedCategory == PC_CATEGORY_BULK_DELIVERY) consignmentId else ""
        val finalMerchant = if (selectedCategory == PC_CATEGORY_PICKUP) selectedMerchant else ""

        btnSubmit.isEnabled = false
        if (isEditMode) {
            lifecycleScope.launch {
                val result = viewModel.updateRequest(
                    branchId, editRequestId, selectedCategory, purpose, amount,
                    consignmentId = finalConsignmentId, merchantName = finalMerchant,
                    requestedDate = selectedDateMillis
                )
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), "Request updated", Toast.LENGTH_SHORT).show()
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
                    amount = amount,
                    priority = PC_PRIORITY_NORMAL,
                    attachmentUrl = "",
                    attachmentName = attachmentName,
                    workerRole = RbacManager.current.roleName.ifBlank { RbacManager.current.roleId },
                    consignmentId = finalConsignmentId,
                    merchantName = finalMerchant,
                    requestedDate = selectedDateMillis
                )
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), "Request ${result.getOrNull()} submitted", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                } else {
                    btnSubmit.isEnabled = true
                    Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Submit failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
