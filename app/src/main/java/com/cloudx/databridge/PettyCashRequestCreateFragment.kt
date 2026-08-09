package com.cloudx.databridge

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Petty Cash Management — New Request (Worker/Requester screen).
 *
 * Not part of the original 10-screen mockup — that batch covered the
 * Accounts/approver view only. This is the missing piece for the
 * "Requester" role in the approval chain: category, amount, purpose,
 * priority, optional attachment. Submits via PettyCashViewModel.submitRequest().
 *
 * Attachment upload to Firebase Storage isn't wired yet — the picker just
 * captures a display name for now (attachmentUrl stays blank). Full file
 * upload can follow the same pattern as Branch's image upload
 * (uploadImageIfNeeded in BranchCreateFragment) when needed.
 */
class PettyCashRequestCreateFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private var branchId: String = ""
    private val categoryOptions = listOf("Bulk Delivery", "Pickup")
    private var selectedCategory: String = ""
    private var attachmentName: String = ""

    private lateinit var tvCategorySelected: TextView
    private lateinit var etAmount: EditText
    private lateinit var etPurpose: EditText
    private lateinit var tvPurposeCount: TextView
    private lateinit var btnSubmit: android.widget.Button

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        fun newInstance(branchId: String): PettyCashRequestCreateFragment {
            val f = PettyCashRequestCreateFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_request_create, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        tvCategorySelected = view.findViewById(R.id.tvPcRequestCategorySelected)
        etAmount = view.findViewById(R.id.etPcRequestAmount)
        etPurpose = view.findViewById(R.id.etPcRequestPurpose)
        tvPurposeCount = view.findViewById(R.id.tvPcRequestPurposeCount)
        btnSubmit = view.findViewById(R.id.btnPcRequestSubmit)

        if (!RbacManager.hasPermission("petty_cash_requester")) {
            Toast.makeText(requireContext(), "Your role isn't set up to submit petty cash requests", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            return
        }

        view.findViewById<View>(R.id.btnPcRequestCreateBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<View>(R.id.layoutPcRequestCategory).setOnClickListener { showCategoryPicker() }
        view.findViewById<View>(R.id.layoutPcRequestAttachment).setOnClickListener { showAttachmentPicker(view) }

        etPurpose.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                tvPurposeCount.text = "${s?.length ?: 0}/200"
            }
        })

        btnSubmit.setOnClickListener { onSubmit() }
    }

    private fun showCategoryPicker() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Category")
            .setItems(categoryOptions.toTypedArray()) { _, index ->
                selectedCategory = categoryOptions[index]
                tvCategorySelected.text = selectedCategory
                tvCategorySelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
            }
            .show()
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

        if (branchId.isBlank()) {
            Toast.makeText(requireContext(), "No branch selected", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedCategory.isBlank()) {
            Toast.makeText(requireContext(), "Select a category", Toast.LENGTH_SHORT).show()
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

        btnSubmit.isEnabled = false
        lifecycleScope.launch {
            val result = viewModel.submitRequest(
                branchId = branchId,
                category = selectedCategory,
                purpose = purpose,
                amount = amount,
                priority = PC_PRIORITY_NORMAL,
                attachmentUrl = "",
                attachmentName = attachmentName,
                workerRole = RbacManager.current.roleName.ifBlank { RbacManager.current.roleId }
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
