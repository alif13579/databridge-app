package com.cloudx.databridge

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class BranchEditFragment : Fragment() {

    companion object {
        private const val ARG_ID = "branch_id"
        fun newInstance(branchId: String) = BranchEditFragment().apply {
            arguments = Bundle().also { it.putString(ARG_ID, branchId) }
        }
    }

    private val db      = FirebaseDatabase.getInstance()
    private val auth    = FirebaseAuth.getInstance()
    private val storage = FirebaseStorage.getInstance()

    data class PickerItem(val id: String, val name: String, val sub: String, val empId: String = "")

    private val allEmployees = mutableListOf<PickerItem>()
    private val allBranches  = mutableListOf<PickerItem>()
    private var selectedManagerUid  = ""
    private var selectedManagerName = ""
    private var selectedParentId    = ""
    private var originalManagerUid  = ""
    private var selectedImageUri: Uri? = null
    private var uploadedImageUrl      = ""

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        selectedImageUri = uri
        ivBranchImage.load(uri) {
            crossfade(true)
            transformations(CircleCropTransformation())
        }
    }

    private lateinit var ivBranchImage: ImageView
    private lateinit var tvId: TextView
    private lateinit var etCode: EditText
    private lateinit var etName: EditText
    private lateinit var spinnerType: Spinner
    private lateinit var etAddress: EditText
    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var etEmail: EditText
    private lateinit var etPhone: EditText
    private lateinit var btnSelectManager: TextView
    private lateinit var btnClearManager: TextView
    private lateinit var tvManagerSelected: TextView
    private lateinit var btnSelectParentBranch: TextView
    private lateinit var btnClearParentBranch: TextView
    private lateinit var tvParentSelected: TextView
    private lateinit var spinnerStatus: Spinner
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_branch_create, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!EmployeeFragment.canManageBranches(RbacManager.current.roleId)) {
            Toast.makeText(requireContext(), "No permission to edit branches", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            return
        }

        bindViews(view)
        view.findViewById<android.widget.TextView>(R.id.tvFormTitle).text = "Update Branch"
        ivBranchImage = view.findViewById(R.id.ivBranchImagePicker)
        ivBranchImage.setOnClickListener { imagePicker.launch("image/*") }
        setupSpinners()
        lifecycleScope.launch { loadAll() }
        setupSearchListeners()
        btnSave.text = "Save Changes"
        btnSave.setOnClickListener { onSave() }
        btnCancel.setOnClickListener { parentFragmentManager.popBackStack() }
    }

    private fun bindViews(v: View) {
        tvId                 = v.findViewById(R.id.tvBranchIdGenerated)
        etCode               = v.findViewById(R.id.etBranchCode)
        etName               = v.findViewById(R.id.etBranchName)
        spinnerType          = v.findViewById(R.id.spinnerBranchType)
        etAddress            = v.findViewById(R.id.etBranchAddress)
        etLat                = v.findViewById(R.id.etLatitude)
        etLng                = v.findViewById(R.id.etLongitude)
        etEmail              = v.findViewById(R.id.etBranchEmail)
        etPhone              = v.findViewById(R.id.etBranchPhone)
        btnSelectManager      = v.findViewById(R.id.btnSelectManager)
        btnClearManager       = v.findViewById(R.id.btnClearManager)
        tvManagerSelected     = v.findViewById(R.id.tvManagerSelected)
        btnSelectParentBranch = v.findViewById(R.id.btnSelectParentBranch)
        btnClearParentBranch  = v.findViewById(R.id.btnClearParentBranch)
        tvParentSelected      = v.findViewById(R.id.tvParentBranchSelected)
        spinnerStatus        = v.findViewById(R.id.spinnerStatus)
        btnSave              = v.findViewById(R.id.btnCreateBranch)
        btnCancel            = v.findViewById(R.id.btnCancelBranch)
    }

    private fun setupSpinners() {
        spinnerType.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, listOf("Hub", "Collection Point", "Sub"))
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerStatus.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, listOf("active", "inactive"))
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun setupSearchListeners() {
        btnSelectManager.setOnClickListener {
            showSearchPicker("Select Manager", allEmployees) { item ->
                selectedManagerUid  = item.id
                selectedManagerName = item.name
                btnSelectManager.text = item.name
                btnSelectManager.setTextColor(0xFFFFFFFF.toInt())
                tvManagerSelected.text = item.sub
                tvManagerSelected.setTextColor(0xFF00D4FF.toInt())
                btnClearManager.visibility = View.VISIBLE
            }
        }
        btnClearManager.setOnClickListener {
            selectedManagerUid  = ""
            selectedManagerName = ""
            btnSelectManager.text = "Tap to select manager ▾"
            btnSelectManager.setTextColor(0xFF888888.toInt())
            tvManagerSelected.text = "None selected"
            tvManagerSelected.setTextColor(0xFF555555.toInt())
            btnClearManager.visibility = View.GONE
        }
        btnSelectParentBranch.setOnClickListener {
            val myId = arguments?.getString(ARG_ID) ?: ""
            showSearchPicker("Select Parent Branch", allBranches.filter { it.id != myId }) { item ->
                selectedParentId = item.id
                btnSelectParentBranch.text = item.name
                btnSelectParentBranch.setTextColor(0xFFFFFFFF.toInt())
                tvParentSelected.text = item.sub
                tvParentSelected.setTextColor(0xFF00D4FF.toInt())
                btnClearParentBranch.visibility = View.VISIBLE
            }
        }
        btnClearParentBranch.setOnClickListener {
            selectedParentId = ""
            btnSelectParentBranch.text = "Tap to select parent branch ▾"
            btnSelectParentBranch.setTextColor(0xFF888888.toInt())
            tvParentSelected.text = "None (root branch)"
            tvParentSelected.setTextColor(0xFF555555.toInt())
            btnClearParentBranch.visibility = View.GONE
        }
    }

    private fun showSearchPicker(title: String, source: List<PickerItem>, onPick: (PickerItem) -> Unit) {
        val ctx = requireContext()
        val dp  = resources.displayMetrics.density.toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp * 16, dp * 8, dp * 16, 0)
        }
        val etSearch = EditText(ctx).apply {
            hint = "Search by name or ID..."
            setTextColor(0xFF000000.toInt())
            setHintTextColor(0xFF888888.toInt())
        }
        container.addView(etSearch)
        val listView = ListView(ctx)
        container.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp * 300))
        var filtered = source.toMutableList()
        fun makeAdapter(list: List<PickerItem>) = object : ArrayAdapter<PickerItem>(
            ctx, android.R.layout.simple_list_item_2, android.R.id.text1, list) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getView(pos, cv, parent)
                val item = list[pos]
                v.findViewById<TextView>(android.R.id.text1).text = item.name
                v.findViewById<TextView>(android.R.id.text2).text =
                    if (item.empId.isNotBlank()) "${item.sub}  •  ${item.empId}" else item.sub
                return v
            }
        }
        listView.adapter = makeAdapter(filtered)
        val dialog = AlertDialog.Builder(ctx).setTitle(title).setView(container).show()
        listView.setOnItemClickListener { _, _, i, _ -> onPick(filtered[i]); dialog.dismiss() }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                val q = s?.toString()?.trim() ?: ""
                filtered = source.filter {
                    q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.empId.contains(q, ignoreCase = true)
                }.toMutableList()
                listView.adapter = makeAdapter(filtered)
            }
        })
    }

    private suspend fun loadAll() {
        val branchId = arguments?.getString(ARG_ID) ?: return
        try {
            val usersSnap  = db.reference.child("users").get().await()
            val allSnap    = db.reference.child("branches").get().await()
            val thisSnap   = db.reference.child("branches/$branchId").get().await()

            allEmployees.clear()
            usersSnap.children.forEach { c ->
                val uid   = c.key ?: return@forEach
                val role  = c.child("profile/company_info/role_id").getValue(String::class.java) ?: ""
                if (role in listOf("admin","manager","supervisor","stuff","worker")) {
                    val name  = c.child("profile/name").getValue(String::class.java)
                               ?: c.child("profile/email").getValue(String::class.java) ?: uid.take(8)
                    val empId = c.child("profile/company_info/employee_id").getValue(String::class.java) ?: ""
                    val desig = c.child("profile/company_info/designation").getValue(String::class.java)
                               ?: EmployeeFragment.ROLE_LABELS[role] ?: role
                    allEmployees.add(PickerItem(uid, name, desig, empId))
                }
            }

            allBranches.clear()
            allSnap.children.forEach { c ->
                val id   = c.key ?: return@forEach
                val name = c.child("name").getValue(String::class.java) ?: id
                val type = c.child("branch_type").getValue(String::class.java) ?: ""
                allBranches.add(PickerItem(id, name, type))
            }

            if (!isAdded) return
            prefill(thisSnap, branchId)
        } catch (e: Exception) {
            if (isAdded) toast("Failed to load: ${e.message}")
        }
    }

    private fun prefill(snap: com.google.firebase.database.DataSnapshot, branchId: String) {
        tvId.text = "Branch ID: $branchId"
        etCode.setText(snap.child("branch_code").getValue(String::class.java) ?: "")
        etName.setText(snap.child("name").getValue(String::class.java) ?: "")
        etAddress.setText(snap.child("address").getValue(String::class.java) ?: "")
        etLat.setText(snap.child("latitude").getValue(Double::class.java)?.toString() ?: "")
        etLng.setText(snap.child("longitude").getValue(Double::class.java)?.toString() ?: "")
        etEmail.setText(snap.child("email").getValue(String::class.java) ?: "")
        etPhone.setText(snap.child("phone").getValue(String::class.java) ?: "")

        val typeList = listOf("Hub", "Collection Point", "Sub")
        val typeIdx  = typeList.indexOf(snap.child("branch_type").getValue(String::class.java) ?: "")
        if (typeIdx >= 0) spinnerType.setSelection(typeIdx)

        val statusList = listOf("active", "inactive")
        val statusIdx  = statusList.indexOf(snap.child("status").getValue(String::class.java) ?: "active")
        if (statusIdx >= 0) spinnerStatus.setSelection(statusIdx)

        selectedManagerUid  = snap.child("manager_uid").getValue(String::class.java) ?: ""
        selectedManagerName = snap.child("manager_name").getValue(String::class.java) ?: ""
        originalManagerUid  = selectedManagerUid
        uploadedImageUrl    = snap.child("image_url").getValue(String::class.java) ?: ""
        if (uploadedImageUrl.isNotBlank()) {
            ivBranchImage.load(uploadedImageUrl) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        }
        if (selectedManagerName.isNotBlank()) {
            btnSelectManager.text = selectedManagerName
            btnSelectManager.setTextColor(0xFFFFFFFF.toInt())
            tvManagerSelected.text = allEmployees.find { it.id == selectedManagerUid }?.sub ?: ""
            tvManagerSelected.setTextColor(0xFF00D4FF.toInt())
            btnClearManager.visibility = View.VISIBLE
        }

        selectedParentId = snap.child("parent_branch_id").getValue(String::class.java) ?: ""
        if (selectedParentId.isNotBlank()) {
            val parentName = allBranches.find { it.id == selectedParentId }?.name ?: selectedParentId
            btnSelectParentBranch.text = parentName
            btnSelectParentBranch.setTextColor(0xFFFFFFFF.toInt())
            tvParentSelected.text = allBranches.find { it.id == selectedParentId }?.sub ?: ""
            tvParentSelected.setTextColor(0xFF00D4FF.toInt())
            btnClearParentBranch.visibility = View.VISIBLE
        }
    }

    private fun onSave() {
        val branchId = arguments?.getString(ARG_ID) ?: return
        val code   = etCode.text.toString().trim()
        val name   = etName.text.toString().trim()
        val type   = spinnerType.selectedItem?.toString() ?: "Hub"
        val status = spinnerStatus.selectedItem?.toString() ?: "active"

        if (code.isBlank()) { toast("Branch Code required"); return }
        if (name.isBlank()) { toast("Branch Name required"); return }

        val now    = System.currentTimeMillis()
        val byUid  = auth.currentUser?.uid ?: ""
        val byName = auth.currentUser?.displayName ?: byUid.take(8)

        lifecycleScope.launch {
            try {
                btnSave.isEnabled = false
                val updates = mutableMapOf<String, Any>(
                    "branches/$branchId/branch_code"     to code,
                    "branches/$branchId/name"            to name,
                    "branches/$branchId/branch_type"     to type,
                    "branches/$branchId/address"         to etAddress.text.toString().trim(),
                    "branches/$branchId/latitude"        to (etLat.text.toString().toDoubleOrNull() ?: 0.0),
                    "branches/$branchId/longitude"       to (etLng.text.toString().toDoubleOrNull() ?: 0.0),
                    "branches/$branchId/email"           to etEmail.text.toString().trim(),
                    "branches/$branchId/phone"           to etPhone.text.toString().trim(),
                    "branches/$branchId/manager_uid"     to selectedManagerUid,
                    "branches/$branchId/manager_name"    to selectedManagerName,
                    "branches/$branchId/parent_branch_id" to selectedParentId,
                    "branches/$branchId/status"          to status,
                    "branches/$branchId/updated_at"      to now,
                    "branches/$branchId/updated_log/$now" to mapOf(
                        "by_uid"  to byUid,
                        "by_name" to byName,
                        "action"  to "updated",
                        "at"      to now
                    )
                )
                val imageUrl = uploadImageIfNeeded(branchId)
                if (imageUrl.isNotBlank()) updates["branches/$branchId/image_url"] = imageUrl

                if (selectedManagerUid.isNotBlank()) {
                    // Ensure manager has this branch in branch_ids and index entry exists
                    val idsSnap = db.reference.child("users/$selectedManagerUid/profile/company_info/branch_ids").get().await()
                    val currentIds = if (idsSnap.exists()) idsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    val newIds = (currentIds + branchId).distinct()
                    updates["users/$selectedManagerUid/profile/company_info/branch_ids"] = newIds
                    val empId = allEmployees.find { it.id == selectedManagerUid }?.empId ?: ""
                    updates["branches/$branchId/employees/$selectedManagerUid"] =
                        mapOf("user_id" to selectedManagerUid, "employee_id" to empId)
                }
                db.reference.updateChildren(updates).await()
                // Remove old manager from employees index and update their branch_ids
                if (originalManagerUid.isNotBlank() && originalManagerUid != selectedManagerUid) {
                    db.reference.child("branches/$branchId/employees/$originalManagerUid").removeValue().await()
                    val oldIdsSnap = db.reference.child("users/$originalManagerUid/profile/company_info/branch_ids").get().await()
                    val oldIds = if (oldIdsSnap.exists()) oldIdsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    val filtered = oldIds.filter { it != branchId }
                    db.reference.child("users/$originalManagerUid/profile/company_info/branch_ids").setValue(filtered).await()
                }
                toast("Branch updated ✓")
                parentFragmentManager.popBackStack()
            } catch (e: Exception) {
                btnSave.isEnabled = true
                toast("Failed: ${e.message}")
            }
        }
    }

    private suspend fun uploadImageIfNeeded(branchId: String): String {
        val uri = selectedImageUri ?: return ""
        return try {
            val ref = storage.reference.child("branch_images/$branchId/cover.jpg")
            ref.putFile(uri).await()
            ref.downloadUrl.await().toString()
        } catch (_: Exception) { "" }
    }

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
