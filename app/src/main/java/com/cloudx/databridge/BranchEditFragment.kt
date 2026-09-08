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
    /** One access slot's selection: multiple persons (Firebase uids) + multiple
     *  roles (roleIds, no "role:" prefix). Full access freedom per slot. */
    private data class SlotSel(
        val uids: MutableSet<String> = mutableSetOf(),
        val roles: MutableSet<String> = mutableSetOf()
    )
    private val managerSel = SlotSel()
    private val accountantSel = SlotSel()
    private val pocSel = SlotSel()
    private val staffSel = SlotSel()
    // Person uids holding any slot when the screen opened — drives removedUids
    // (Edge strips the branch from holders matching no current assignment).
    // Role removals are additive-safe by design (no auto-strip); visibility
    // trims happen via employee edit.
    private val origHolderUids = mutableSetOf<String>()
    private var selectedParentId    = ""
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

    private fun personName(uid: String): String =
        allEmployees.find { it.id == uid }?.name ?: uid.take(8)

    private fun roleName(roleId: String): String =
        allRoles.find { it.id == "role:$roleId" }?.name ?: roleId

    /** Button + subtitle for one multi slot: up to 2 names, then "+N more". */
    private fun refreshSlotLabel(
        btn: TextView, btnClear: TextView, tvSub: TextView, sel: SlotSel, emptyHint: String
    ) {
        if (!isAdded) return
        val names = sel.uids.map { personName(it) } + sel.roles.map { roleName(it) }
        if (names.isEmpty()) {
            btn.text = emptyHint
            btn.setTextColor(0xFF888888.toInt())
            tvSub.text = "None selected"
            tvSub.setTextColor(0xFF555555.toInt())
            btnClear.visibility = View.GONE
        } else {
            btn.text = if (names.size <= 2) names.joinToString(", ") else "${names.take(2).joinToString(", ")} +${names.size - 2} more"
            btn.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_text_primary))
            val bits = listOfNotNull(
                "${sel.uids.size} person".plural(sel.uids.size).takeIf { sel.uids.isNotEmpty() },
                "${sel.roles.size} role".plural(sel.roles.size).takeIf { sel.roles.isNotEmpty() }
            )
            tvSub.text = bits.joinToString(" · ")
            tvSub.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_accent))
            btnClear.visibility = View.VISIBLE
        }
    }

    private fun String.plural(n: Int) = if (n == 1) this else "${this}s"

    /** Multi-select access picker: Roles section + Persons section, searchable.
     *  Writes into [sel] on Apply only — Cancel leaves the slot untouched. */
    private fun showMultiSlotPicker(
        title: String,
        persons: List<PickerItem>,
        sel: SlotSel,
        onApply: () -> Unit
    ) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 8.dp(), 16.dp(), 0)
        }
        val etSearch = EditText(ctx).apply {
            hint = "Search persons or roles..."
            setTextColor(0xFF000000.toInt())
            setHintTextColor(0xFF888888.toInt())
        }
        container.addView(etSearch)
        val scroll = android.widget.ScrollView(ctx)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(box)
        container.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 300.dp()))

        val workUids = sel.uids.toMutableSet()
        val workRoles = sel.roles.toMutableSet()

        fun sectionHeader(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF64748B.toInt())
            setPadding(0, 12.dp(), 0, 4.dp())
        }
        fun rebuild(q: String) {
            box.removeAllViews()
            val roleHits = allRoles.filter {
                q.isEmpty() || it.name.contains(q, ignoreCase = true)
            }
            if (roleHits.isNotEmpty()) {
                box.addView(sectionHeader("Roles (${roleHits.size})"))
                roleHits.forEach { r ->
                    val roleId = r.id.removePrefix("role:")
                    box.addView(android.widget.CheckBox(ctx).apply {
                        text = r.name
                        textSize = 14f
                        isChecked = roleId in workRoles
                        setOnCheckedChangeListener { _, on ->
                            if (on) workRoles.add(roleId) else workRoles.remove(roleId)
                        }
                    })
                }
            }
            val personHits = persons.filter {
                q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.empId.contains(q, ignoreCase = true)
            }
            box.addView(sectionHeader("Persons (${personHits.size})"))
            if (personHits.isEmpty()) {
                box.addView(TextView(ctx).apply {
                    text = "No matches"
                    textSize = 13f
                    setTextColor(0xFF94A3B8.toInt())
                })
            }
            personHits.forEach { p ->
                box.addView(android.widget.CheckBox(ctx).apply {
                    text = if (p.empId.isNotBlank()) "${p.name}  •  ${p.empId}" else "${p.name}  •  ${p.sub}"
                    textSize = 14f
                    isChecked = p.id in workUids
                    setOnCheckedChangeListener { _, on ->
                        if (on) workUids.add(p.id) else workUids.remove(p.id)
                    }
                })
            }
        }
        rebuild("")
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                rebuild(s?.toString()?.trim().orEmpty())
            }
        })

        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                sel.uids.clear()
                sel.uids.addAll(workUids)
                sel.roles.clear()
                sel.roles.addAll(workRoles)
                onApply()
            }
            .setNeutralButton("Clear all") { _, _ ->
                sel.uids.clear()
                sel.roles.clear()
                onApply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupSearchListeners() {
        btnSelectManager.setOnClickListener {
            val branchScopedEmployees = allEmployees.filter { it.id in branchEmployeeUids }
            showMultiSlotPicker("Select Managers (persons + roles)", branchScopedEmployees, managerSel) {
                refreshSlotLabel(btnSelectManager, btnClearManager, tvManagerSelected,
                    managerSel, "Tap to select managers ▾")
            }
        }
        btnClearManager.setOnClickListener {
            managerSel.uids.clear()
            managerSel.roles.clear()
            refreshSlotLabel(btnSelectManager, btnClearManager, tvManagerSelected,
                managerSel, "Tap to select managers ▾")
        }
        btnSelectAccountant.setOnClickListener {
            val branchScopedEmployees = allEmployees.filter { it.id in branchEmployeeUids }
            showMultiSlotPicker("Select Accountants (persons + roles)", branchScopedEmployees, accountantSel) {
                refreshSlotLabel(btnSelectAccountant, btnClearAccountant, tvAccountantSelected,
                    accountantSel, "Tap to select accountants ▾")
            }
        }
        btnClearAccountant.setOnClickListener {
            accountantSel.uids.clear()
            accountantSel.roles.clear()
            refreshSlotLabel(btnSelectAccountant, btnClearAccountant, tvAccountantSelected,
                accountantSel, "Tap to select accountants ▾")
        }
        btnSelectPettyCashPoc.setOnClickListener {
            val branchScopedEmployees = allEmployees.filter { it.id in branchEmployeeUids }
            showMultiSlotPicker("Select Petty Cash POCs (persons + roles)", branchScopedEmployees, pocSel) {
                refreshSlotLabel(btnSelectPettyCashPoc, btnClearPettyCashPoc, tvPettyCashPocSelected,
                    pocSel, "Tap to select petty cash POCs ▾")
            }
        }
        btnClearPettyCashPoc.setOnClickListener {
            pocSel.uids.clear()
            pocSel.roles.clear()
            refreshSlotLabel(btnSelectPettyCashPoc, btnClearPettyCashPoc, tvPettyCashPocSelected,
                pocSel, "Tap to select petty cash POCs ▾")
        }
        btnSelectStaff.setOnClickListener {
            // Both display label and field/variable names are "Staff" now
            // (renamed fully from "Team Aligned" -- no production data
            // existed under the old names).
            val branchScopedEmployees = allEmployees.filter { it.id in branchEmployeeUids }
            showMultiSlotPicker("Select Staff (persons + roles)", branchScopedEmployees, staffSel) {
                refreshSlotLabel(btnSelectStaff, btnClearStaff, tvStaffSelected,
                    staffSel, "Tap to select Staff ▾")
            }
        }
        btnClearStaff.setOnClickListener {
            staffSel.uids.clear()
            staffSel.roles.clear()
            refreshSlotLabel(btnSelectStaff, btnClearStaff, tvStaffSelected,
                staffSel, "Tap to select Staff ▾")
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

        managerSel.uids.clear()
        managerSel.uids.addAll(branch.managerUids + listOfNotNull(branch.managerUid.ifBlank { null }))
        managerSel.roles.clear()
        managerSel.roles.addAll(branch.managerRoles)
        accountantSel.uids.clear()
        accountantSel.uids.addAll(branch.accountantUids + listOfNotNull(branch.accountantUid.ifBlank { null }))
        accountantSel.roles.clear()
        accountantSel.roles.addAll(branch.accountantRoles + listOfNotNull(branch.accountantRole.ifBlank { null }))
        pocSel.uids.clear()
        pocSel.uids.addAll(branch.pettyCashPocUids + listOfNotNull(branch.pettyCashPocUid.ifBlank { null }))
        pocSel.roles.clear()
        pocSel.roles.addAll(branch.pettyCashPocRoles)
        staffSel.uids.clear()
        staffSel.uids.addAll(branch.staffUids + listOfNotNull(branch.staffUid.ifBlank { null }))
        staffSel.roles.clear()
        staffSel.roles.addAll(branch.staffRoles + listOfNotNull(branch.staffRole.ifBlank { null }))
        origHolderUids.clear()
        origHolderUids.addAll(managerSel.uids + accountantSel.uids + pocSel.uids + staffSel.uids)
        uploadedImageUrl    = branch.imageUrl
        if (uploadedImageUrl.isNotBlank()) {
            ivBranchImage.load(uploadedImageUrl) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        }
        refreshSlotLabel(btnSelectManager, btnClearManager, tvManagerSelected,
            managerSel, "Tap to select managers ▾")
        refreshSlotLabel(btnSelectAccountant, btnClearAccountant, tvAccountantSelected,
            accountantSel, "Tap to select accountants ▾")
        refreshSlotLabel(btnSelectPettyCashPoc, btnClearPettyCashPoc, tvPettyCashPocSelected,
            pocSel, "Tap to select petty cash POCs ▾")
        refreshSlotLabel(btnSelectStaff, btnClearStaff, tvStaffSelected,
            staffSel, "Tap to select Staff ▾")

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
                // branch from the RLS membership of holders matching no
                // current assignment on this branch.
                val newHolderUids = managerSel.uids + accountantSel.uids + pocSel.uids + staffSel.uids
                val removedUids = (origHolderUids - newHolderUids).toList()
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
                        managerUids = managerSel.uids.filter { it.isNotBlank() },
                        managerRoles = managerSel.roles.filter { it.isNotBlank() },
                        accountantUids = accountantSel.uids.filter { it.isNotBlank() },
                        accountantRoles = accountantSel.roles.filter { it.isNotBlank() },
                        pettyCashPocUids = pocSel.uids.filter { it.isNotBlank() },
                        pettyCashPocRoles = pocSel.roles.filter { it.isNotBlank() },
                        pettyCashLimit = etPettyCashLimit.text.toString().trim().toDoubleOrNull() ?: 0.0,
                        staffUids = staffSel.uids.filter { it.isNotBlank() },
                        staffRoles = staffSel.roles.filter { it.isNotBlank() },
                        parentBranchId = selectedParentId,
                        region = etRegion.text.toString().trim(),
                        status = status,
                        imageUrl = if (imageUrl.isNotBlank()) imageUrl else uploadedImageUrl,
                        removedUids = removedUids,
                        managerUid = managerSel.uids.firstOrNull().orEmpty(),
                        accountantUid = accountantSel.uids.firstOrNull().orEmpty(),
                        accountantRole = accountantSel.roles.firstOrNull().orEmpty(),
                        pettyCashPocUid = pocSel.uids.firstOrNull().orEmpty(),
                        staffUid = staffSel.uids.firstOrNull().orEmpty(),
                        staffRole = staffSel.roles.firstOrNull().orEmpty()
                    )
                )

                // Firebase user-profile branch_ids stay in sync (membership,
                // not branch data) — same add/remove semantics as before.
                // The Edge Function fans the same membership out to Supabase
                // users.branch_ids (RLS) server-side, including role-holders.
                val membershipUpdates = mutableMapOf<String, Any>()
                suspend fun addMembership(uid: String) {
                    val idsSnap = db.reference.child("users/$uid/profile/company_info/branch_ids").get().await()
                    val currentIds = if (idsSnap.exists()) idsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    membershipUpdates["users/$uid/profile/company_info/branch_ids"] =
                        (currentIds + branchId).distinct()
                }
                suspend fun removeMembership(uid: String) {
                    val idsSnap = db.reference.child("users/$uid/profile/company_info/branch_ids").get().await()
                    val currentIds = if (idsSnap.exists()) idsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    membershipUpdates["users/$uid/profile/company_info/branch_ids"] =
                        currentIds.filter { it != branchId }
                }
                for (uid in newHolderUids) addMembership(uid)
                // Remove an old holder's membership — but only if they hold no
                // slot on this branch anymore (still needs access otherwise).
                // (The Firebase employees index is gone with the cutover, so
                // only branch_ids is cleaned now.)
                for (uid in origHolderUids - newHolderUids) removeMembership(uid)
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
