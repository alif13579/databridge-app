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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
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
    private val storage = FirebaseStorage.getInstance()

    data class PickerItem(val id: String, val name: String, val sub: String, val empId: String = "")

    private val allEmployees = mutableListOf<PickerItem>()
    private val allBranches  = mutableListOf<PickerItem>()
    private val allRoles     = mutableListOf<PickerItem>()
    private val branchEmployeeUids = mutableSetOf<String>()
    private var selectedManagerUid  = ""
    private var selectedManagerName = ""
    private var selectedAccountantUid  = ""
    private var selectedAccountantName = ""
    private var selectedAccountantRole = ""
    private var selectedPettyCashPocUid  = ""
    private var selectedPettyCashPocName = ""
    private var selectedStaffUid  = ""
    private var selectedStaffName = ""
    private var selectedStaffRole = ""
    private var selectedParentId    = ""
    private var originalManagerUid  = ""
    private var originalAccountantUid  = ""
    private var originalPettyCashPocUid  = ""
    private var originalStaffUid  = ""
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
    private lateinit var etRegion: EditText
    private lateinit var etPettyCashLimit: EditText
    private lateinit var btnSelectManager: TextView
    private lateinit var btnClearManager: TextView
    private lateinit var tvManagerSelected: TextView
    private lateinit var btnSelectAccountant: TextView
    private lateinit var btnClearAccountant: TextView
    private lateinit var tvAccountantSelected: TextView
    private lateinit var btnSelectPettyCashPoc: TextView
    private lateinit var btnClearPettyCashPoc: TextView
    private lateinit var tvPettyCashPocSelected: TextView
    private lateinit var btnSelectStaff: TextView
    private lateinit var btnClearStaff: TextView
    private lateinit var tvStaffSelected: TextView
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
        etRegion             = v.findViewById(R.id.etBranchRegion)
        etPettyCashLimit     = v.findViewById(R.id.etBranchPettyCashLimit)
        btnSelectManager      = v.findViewById(R.id.btnSelectManager)
        btnClearManager       = v.findViewById(R.id.btnClearManager)
        tvManagerSelected     = v.findViewById(R.id.tvManagerSelected)
        btnSelectAccountant   = v.findViewById(R.id.btnSelectAccountant)
        btnClearAccountant    = v.findViewById(R.id.btnClearAccountant)
        tvAccountantSelected  = v.findViewById(R.id.tvAccountantSelected)
        btnSelectPettyCashPoc  = v.findViewById(R.id.btnSelectPettyCashPoc)
        btnClearPettyCashPoc   = v.findViewById(R.id.btnClearPettyCashPoc)
        tvPettyCashPocSelected = v.findViewById(R.id.tvPettyCashPocSelected)
        btnSelectStaff  = v.findViewById(R.id.btnSelectStaff)
        btnClearStaff   = v.findViewById(R.id.btnClearStaff)
        tvStaffSelected = v.findViewById(R.id.tvStaffSelected)
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
            val branchScopedEmployees = allEmployees.filter { it.id in branchEmployeeUids }
            showSearchPicker("Select Manager", branchScopedEmployees) { item ->
                selectedManagerUid  = item.id
                selectedManagerName = item.name
                btnSelectManager.text = item.name
                btnSelectManager.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_text_primary))
                tvManagerSelected.text = item.sub
                tvManagerSelected.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_accent))
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
        btnSelectAccountant.setOnClickListener {
            val branchScopedEmployees = allEmployees.filter { it.id in branchEmployeeUids }
            val combined = allRoles + branchScopedEmployees
            showSearchPicker("Select Accountant (role or person)", combined) { item ->
                if (item.id.startsWith("role:")) {
                    selectedAccountantRole = item.id.removePrefix("role:")
                    selectedAccountantUid  = ""
                    selectedAccountantName = item.name
                } else {
                    selectedAccountantRole = ""
                    selectedAccountantUid  = item.id
                    selectedAccountantName = item.name
                }
                btnSelectAccountant.text = item.name
                btnSelectAccountant.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_text_primary))
                tvAccountantSelected.text = item.sub
                tvAccountantSelected.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_accent))
                btnClearAccountant.visibility = View.VISIBLE
            }
        }
        btnClearAccountant.setOnClickListener {
            selectedAccountantUid  = ""
            selectedAccountantName = ""
            selectedAccountantRole = ""
            btnSelectAccountant.text = "Tap to select accountant ▾"
            btnSelectAccountant.setTextColor(0xFF888888.toInt())
            tvAccountantSelected.text = "None selected"
            tvAccountantSelected.setTextColor(0xFF555555.toInt())
            btnClearAccountant.visibility = View.GONE
        }
        btnSelectPettyCashPoc.setOnClickListener {
            val branchScopedEmployees = allEmployees.filter { it.id in branchEmployeeUids }
            showSearchPicker("Select Petty Cash POC", branchScopedEmployees) { item ->
                selectedPettyCashPocUid  = item.id
                selectedPettyCashPocName = item.name
                btnSelectPettyCashPoc.text = item.name
                btnSelectPettyCashPoc.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_text_primary))
                tvPettyCashPocSelected.text = item.sub
                tvPettyCashPocSelected.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_accent))
                btnClearPettyCashPoc.visibility = View.VISIBLE
            }
        }
        btnClearPettyCashPoc.setOnClickListener {
            selectedPettyCashPocUid  = ""
            selectedPettyCashPocName = ""
            btnSelectPettyCashPoc.text = "Tap to select petty cash POC ▾"
            btnSelectPettyCashPoc.setTextColor(0xFF888888.toInt())
            tvPettyCashPocSelected.text = "None selected"
            tvPettyCashPocSelected.setTextColor(0xFF555555.toInt())
            btnClearPettyCashPoc.visibility = View.GONE
        }
        btnSelectStaff.setOnClickListener {
            // Both display label and field/variable names are "Staff" now
            // (renamed fully from "Team Aligned" -- no production data
            // existed under the old names).
            val branchScopedEmployees = allEmployees.filter { it.id in branchEmployeeUids }
            val combined = allRoles + branchScopedEmployees
            showSearchPicker("Select Staff (role or person)", combined) { item ->
                if (item.id.startsWith("role:")) {
                    selectedStaffRole = item.id.removePrefix("role:")
                    selectedStaffUid  = ""
                    selectedStaffName = item.name
                } else {
                    selectedStaffRole = ""
                    selectedStaffUid  = item.id
                    selectedStaffName = item.name
                }
                btnSelectStaff.text = item.name
                btnSelectStaff.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_text_primary))
                tvStaffSelected.text = item.sub
                tvStaffSelected.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_accent))
                btnClearStaff.visibility = View.VISIBLE
            }
        }
        btnClearStaff.setOnClickListener {
            selectedStaffUid  = ""
            selectedStaffName = ""
            selectedStaffRole = ""
            btnSelectStaff.text = "Tap to select Staff ▾"
            btnSelectStaff.setTextColor(0xFF888888.toInt())
            tvStaffSelected.text = "None selected"
            tvStaffSelected.setTextColor(0xFF555555.toInt())
            btnClearStaff.visibility = View.GONE
        }
        btnSelectParentBranch.setOnClickListener {
            val myId = arguments?.getString(ARG_ID) ?: ""
            showSearchPicker("Select Parent Branch", allBranches.filter { it.id != myId }) { item ->
                selectedParentId = item.id
                btnSelectParentBranch.text = item.name
                btnSelectParentBranch.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_text_primary))
                tvParentSelected.text = item.sub
                tvParentSelected.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_accent))
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
            // Branch core + parent list come from Supabase (branch cutover);
            // employees/users/roles pickers stay on Firebase.
            val branch = SupabaseBranchReader.getBranch(branchId)
            val supabaseBranches = runCatching { SupabaseBranchReader.listBranches() }.getOrElse { emptyList() }

            allEmployees.clear()
            branchEmployeeUids.clear()

            usersSnap.children.forEach { c ->
                val uid   = c.key ?: return@forEach
                val role  = c.child("profile/company_info/role_id").getValue(String::class.java) ?: ""
                val name  = c.child("profile/name").getValue(String::class.java)
                           ?: c.child("profile/email").getValue(String::class.java) ?: uid.take(8)
                val empId = c.child("profile/company_info/employee_id").getValue(String::class.java) ?: ""
                val desig = c.child("profile/company_info/designation").getValue(String::class.java)
                           ?: EmployeeFragment.ROLE_LABELS[role] ?: role
                allEmployees.add(PickerItem(uid, name, desig, empId))
                // Picker scoping used to come from the Firebase employees
                // index (removed with the cutover) — users whose profile
                // branch_ids contain this branch are the equivalent set.
                val bids = c.child("profile/company_info/branch_ids").children
                    .mapNotNull { it.getValue(String::class.java) }
                if (branchId in bids) branchEmployeeUids.add(uid)
            }

            // Roles come straight from Firebase (roles/{roleId}, admin-configured via Access
            // Manager) — no built-in fallback list, so a role that's been removed or renamed
            // there can't still show up here as a pickable (but no-longer-real) option.
            allRoles.clear()
            val rolesSnap = db.reference.child("roles").get().await()
            rolesSnap.children.forEach { c ->
                val roleId = c.key ?: return@forEach
                val roleName = c.child("name").getValue(String::class.java) ?: roleId
                allRoles.add(PickerItem("role:$roleId", roleName, "Role — everyone with this role at this branch"))
            }

            allBranches.clear()
            supabaseBranches.forEach { r ->
                allBranches.add(PickerItem(r.branchId, r.name, r.branchType))
            }

            if (!isAdded) return
            prefill(branch)
        } catch (e: Exception) {
            if (isAdded) toast("Failed to load: ${e.message}")
        }
    }

    private fun prefill(branch: SupabaseBranchReader.BranchRow) {
        val branchId = branch.branchId
        tvId.text = "Branch ID: $branchId"
        etCode.setText(branch.branchCode)
        etName.setText(branch.name)
        etAddress.setText(branch.address)
        etLat.setText(branch.latitude.takeIf { it != 0.0 }?.toString() ?: "")
        etLng.setText(branch.longitude.takeIf { it != 0.0 }?.toString() ?: "")
        etEmail.setText(branch.email)
        etPhone.setText(branch.phone)
        etRegion.setText(branch.region)
        etPettyCashLimit.setText(branch.pettyCashLimit.takeIf { it > 0 }?.let {
            if (it == kotlin.math.floor(it)) it.toLong().toString() else it.toString()
        } ?: "")

        val typeList = listOf("Hub", "Collection Point", "Sub")
        val typeIdx  = typeList.indexOf(branch.branchType)
        if (typeIdx >= 0) spinnerType.setSelection(typeIdx)

        val statusList = listOf("active", "inactive")
        val statusIdx  = statusList.indexOf(branch.status.ifBlank { "active" })
        if (statusIdx >= 0) spinnerStatus.setSelection(statusIdx)

        selectedManagerUid  = branch.managerUid
        selectedManagerName = allEmployees.find { it.id == branch.managerUid }?.name ?: ""
        originalManagerUid  = selectedManagerUid
        selectedAccountantUid  = branch.accountantUid
        selectedAccountantName = allEmployees.find { it.id == branch.accountantUid }?.name ?: ""
        selectedAccountantRole = branch.accountantRole
        originalAccountantUid  = selectedAccountantUid
        selectedPettyCashPocUid  = branch.pettyCashPocUid
        selectedPettyCashPocName = allEmployees.find { it.id == branch.pettyCashPocUid }?.name ?: ""
        originalPettyCashPocUid  = selectedPettyCashPocUid
        selectedStaffUid  = branch.staffUid
        selectedStaffName = allEmployees.find { it.id == branch.staffUid }?.name ?: ""
        selectedStaffRole = branch.staffRole
        originalStaffUid  = selectedStaffUid
        uploadedImageUrl    = branch.imageUrl
        if (uploadedImageUrl.isNotBlank()) {
            ivBranchImage.load(uploadedImageUrl) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        }
        if (selectedManagerName.isNotBlank()) {
            btnSelectManager.text = selectedManagerName
            btnSelectManager.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_text_primary))
            tvManagerSelected.text = allEmployees.find { it.id == selectedManagerUid }?.sub ?: ""
            tvManagerSelected.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_accent))
            btnClearManager.visibility = View.VISIBLE
        }
        if (selectedAccountantName.isNotBlank()) {
            btnSelectAccountant.text = selectedAccountantName
            btnSelectAccountant.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_text_primary))
            tvAccountantSelected.text = if (selectedAccountantRole.isNotBlank())
                "Role — everyone with this role at this branch"
            else
                allEmployees.find { it.id == selectedAccountantUid }?.sub ?: ""
            tvAccountantSelected.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_accent))
            btnClearAccountant.visibility = View.VISIBLE
        }
        if (selectedPettyCashPocName.isNotBlank()) {
            btnSelectPettyCashPoc.text = selectedPettyCashPocName
            btnSelectPettyCashPoc.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_text_primary))
            tvPettyCashPocSelected.text = allEmployees.find { it.id == selectedPettyCashPocUid }?.sub ?: ""
            tvPettyCashPocSelected.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_accent))
            btnClearPettyCashPoc.visibility = View.VISIBLE
        }
        if (selectedStaffName.isNotBlank()) {
            btnSelectStaff.text = selectedStaffName
            btnSelectStaff.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_text_primary))
            tvStaffSelected.text = if (selectedStaffRole.isNotBlank())
                "Role — everyone with this role at this branch"
            else
                allEmployees.find { it.id == selectedStaffUid }?.sub ?: ""
            tvStaffSelected.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_accent))
            btnClearStaff.visibility = View.VISIBLE
        }

        selectedParentId = branch.parentBranchId
        if (selectedParentId.isNotBlank()) {
            val parentName = allBranches.find { it.id == selectedParentId }?.name ?: selectedParentId
            btnSelectParentBranch.text = parentName
            btnSelectParentBranch.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_text_primary))
            tvParentSelected.text = allBranches.find { it.id == selectedParentId }?.sub ?: ""
            tvParentSelected.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_accent))
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

        lifecycleScope.launch {
            try {
                btnSave.isEnabled = false
                val imageUrl = uploadImageIfNeeded(branchId)
                // Branch directory persists ONLY to Supabase now (branch
                // cutover) — no Firebase `branches/$branchId` write, no
                // employees index, no updated_log (none have Supabase
                // columns). removedUids lets the Edge Function strip the
                // branch from the RLS membership of unassigned holders.
                val removedUids = listOf(
                    originalManagerUid.takeIf { it.isNotBlank() && it != selectedManagerUid },
                    originalAccountantUid.takeIf { it.isNotBlank() && it != selectedAccountantUid },
                    originalPettyCashPocUid.takeIf { it.isNotBlank() && it != selectedPettyCashPocUid },
                    originalStaffUid.takeIf { it.isNotBlank() && it != selectedStaffUid }
                ).mapNotNull { it }
                SupabaseBranchWriter.save(
                    SupabaseBranchWriter.BranchPayload(
                        branchId = branchId,
                        branchCode = code,
                        name = name,
                        branchType = type,
                        address = etAddress.text.toString().trim(),
                        latitude = etLat.text.toString().toDoubleOrNull() ?: 0.0,
                        longitude = etLng.text.toString().toDoubleOrNull() ?: 0.0,
                        email = etEmail.text.toString().trim(),
                        phone = etPhone.text.toString().trim(),
                        managerUid = selectedManagerUid,
                        accountantUid = selectedAccountantUid,
                        accountantRole = selectedAccountantRole,
                        pettyCashPocUid = selectedPettyCashPocUid,
                        pettyCashLimit = etPettyCashLimit.text.toString().trim().toDoubleOrNull() ?: 0.0,
                        staffUid = selectedStaffUid,
                        staffRole = selectedStaffRole,
                        parentBranchId = selectedParentId,
                        region = etRegion.text.toString().trim(),
                        status = status,
                        imageUrl = if (imageUrl.isNotBlank()) imageUrl else uploadedImageUrl,
                        removedUids = removedUids
                    )
                )

                // Firebase user-profile branch_ids stay in sync (membership,
                // not branch data) — same add/remove semantics as before.
                // Collect into one updateChildren map, applied below.
                val membershipUpdates = mutableMapOf<String, Any>()
                if (selectedManagerUid.isNotBlank()) {
                    // Ensure manager has this branch in branch_ids
                    val idsSnap = db.reference.child("users/$selectedManagerUid/profile/company_info/branch_ids").get().await()
                    val currentIds = if (idsSnap.exists()) idsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    membershipUpdates["users/$selectedManagerUid/profile/company_info/branch_ids"] =
                        (currentIds + branchId).distinct()
                }
                if (selectedAccountantUid.isNotBlank()) {
                    // Same as manager: ensure accountant has this branch in branch_ids
                    val idsSnap = db.reference.child("users/$selectedAccountantUid/profile/company_info/branch_ids").get().await()
                    val currentIds = if (idsSnap.exists()) idsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    membershipUpdates["users/$selectedAccountantUid/profile/company_info/branch_ids"] =
                        (currentIds + branchId).distinct()
                }
                if (selectedPettyCashPocUid.isNotBlank()) {
                    // Same as manager/accountant: ensure POC has this branch in branch_ids
                    val idsSnap = db.reference.child("users/$selectedPettyCashPocUid/profile/company_info/branch_ids").get().await()
                    val currentIds = if (idsSnap.exists()) idsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    membershipUpdates["users/$selectedPettyCashPocUid/profile/company_info/branch_ids"] =
                        (currentIds + branchId).distinct()
                }
                if (selectedStaffUid.isNotBlank()) {
                    // Same as manager/accountant/POC: ensure Staff has this branch in branch_ids
                    val idsSnap = db.reference.child("users/$selectedStaffUid/profile/company_info/branch_ids").get().await()
                    val currentIds = if (idsSnap.exists()) idsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    membershipUpdates["users/$selectedStaffUid/profile/company_info/branch_ids"] =
                        (currentIds + branchId).distinct()
                }
                // Remove an old holder's membership — but only if they didn't
                // just take another role on this branch (still needs access).
                // (The Firebase employees index is gone with the cutover, so
                // only branch_ids is cleaned now.)
                if (originalManagerUid.isNotBlank() && originalManagerUid != selectedManagerUid &&
                    originalManagerUid != selectedAccountantUid && originalManagerUid != selectedPettyCashPocUid &&
                    originalManagerUid != selectedStaffUid) {
                    val oldIdsSnap = db.reference.child("users/$originalManagerUid/profile/company_info/branch_ids").get().await()
                    val oldIds = if (oldIdsSnap.exists()) oldIdsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    membershipUpdates["users/$originalManagerUid/profile/company_info/branch_ids"] =
                        oldIds.filter { it != branchId }
                }
                // Same guarded cleanup for the old accountant
                if (originalAccountantUid.isNotBlank() && originalAccountantUid != selectedAccountantUid &&
                    originalAccountantUid != selectedManagerUid && originalAccountantUid != selectedPettyCashPocUid &&
                    originalAccountantUid != selectedStaffUid) {
                    val oldIdsSnap = db.reference.child("users/$originalAccountantUid/profile/company_info/branch_ids").get().await()
                    val oldIds = if (oldIdsSnap.exists()) oldIdsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    membershipUpdates["users/$originalAccountantUid/profile/company_info/branch_ids"] =
                        oldIds.filter { it != branchId }
                }
                // Same guarded cleanup for the old Petty Cash POC
                if (originalPettyCashPocUid.isNotBlank() && originalPettyCashPocUid != selectedPettyCashPocUid &&
                    originalPettyCashPocUid != selectedManagerUid && originalPettyCashPocUid != selectedAccountantUid &&
                    originalPettyCashPocUid != selectedStaffUid) {
                    val oldIdsSnap = db.reference.child("users/$originalPettyCashPocUid/profile/company_info/branch_ids").get().await()
                    val oldIds = if (oldIdsSnap.exists()) oldIdsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    membershipUpdates["users/$originalPettyCashPocUid/profile/company_info/branch_ids"] =
                        oldIds.filter { it != branchId }
                }
                // Same guarded cleanup for the old Staff
                if (originalStaffUid.isNotBlank() && originalStaffUid != selectedStaffUid &&
                    originalStaffUid != selectedManagerUid && originalStaffUid != selectedAccountantUid &&
                    originalStaffUid != selectedPettyCashPocUid) {
                    val oldIdsSnap = db.reference.child("users/$originalStaffUid/profile/company_info/branch_ids").get().await()
                    val oldIds = if (oldIdsSnap.exists()) oldIdsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    membershipUpdates["users/$originalStaffUid/profile/company_info/branch_ids"] =
                        oldIds.filter { it != branchId }
                }
                if (membershipUpdates.isNotEmpty()) db.reference.updateChildren(membershipUpdates).await()
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
