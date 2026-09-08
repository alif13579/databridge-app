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
import java.util.UUID

class BranchCreateFragment : Fragment() {

    private val db      = FirebaseDatabase.getInstance()
    private val storage = FirebaseStorage.getInstance()

    private val autoId = UUID.randomUUID().toString().replace("-", "").take(12)

    data class PickerItem(val id: String, val name: String, val sub: String, val empId: String = "")

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        selectedImageUri = uri
        ivBranchImage.load(uri) {
            crossfade(true)
            transformations(CircleCropTransformation())
        }
    }

    private val allEmployees = mutableListOf<PickerItem>()
    private val allBranches  = mutableListOf<PickerItem>()
    private val allRoles     = mutableListOf<PickerItem>()

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
    private var selectedParentId    = ""
    private var selectedImageUri: Uri? = null
    private var uploadedImageUrl      = ""

    private lateinit var ivBranchImage: ImageView
    private lateinit var tvIdGenerated: TextView
    private lateinit var etBranchCode: EditText
    private lateinit var etBranchName: EditText
    private lateinit var spinnerType: Spinner
    private lateinit var etAddress: EditText
    private lateinit var etLatitude: EditText
    private lateinit var etLongitude: EditText
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
    private lateinit var btnCreate: Button
    private lateinit var btnCancel: Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_branch_create, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!EmployeeFragment.canManageBranches(RbacManager.current.roleId)) {
            Toast.makeText(requireContext(), "No permission to create branches", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            return
        }

        tvIdGenerated        = view.findViewById(R.id.tvBranchIdGenerated)
        etBranchCode         = view.findViewById(R.id.etBranchCode)
        etBranchName         = view.findViewById(R.id.etBranchName)
        spinnerType          = view.findViewById(R.id.spinnerBranchType)
        etAddress            = view.findViewById(R.id.etBranchAddress)
        etLatitude           = view.findViewById(R.id.etLatitude)
        etLongitude          = view.findViewById(R.id.etLongitude)
        etEmail              = view.findViewById(R.id.etBranchEmail)
        etPhone              = view.findViewById(R.id.etBranchPhone)
        etRegion             = view.findViewById(R.id.etBranchRegion)
        etPettyCashLimit     = view.findViewById(R.id.etBranchPettyCashLimit)
        btnSelectManager      = view.findViewById(R.id.btnSelectManager)
        btnClearManager       = view.findViewById(R.id.btnClearManager)
        tvManagerSelected     = view.findViewById(R.id.tvManagerSelected)
        btnSelectAccountant   = view.findViewById(R.id.btnSelectAccountant)
        btnClearAccountant    = view.findViewById(R.id.btnClearAccountant)
        tvAccountantSelected  = view.findViewById(R.id.tvAccountantSelected)
        btnSelectPettyCashPoc  = view.findViewById(R.id.btnSelectPettyCashPoc)
        btnClearPettyCashPoc   = view.findViewById(R.id.btnClearPettyCashPoc)
        tvPettyCashPocSelected = view.findViewById(R.id.tvPettyCashPocSelected)
        btnSelectStaff  = view.findViewById(R.id.btnSelectStaff)
        btnClearStaff   = view.findViewById(R.id.btnClearStaff)
        tvStaffSelected = view.findViewById(R.id.tvStaffSelected)
        btnSelectParentBranch = view.findViewById(R.id.btnSelectParentBranch)
        btnClearParentBranch  = view.findViewById(R.id.btnClearParentBranch)
        tvParentSelected      = view.findViewById(R.id.tvParentBranchSelected)
        spinnerStatus        = view.findViewById(R.id.spinnerStatus)
        btnCreate            = view.findViewById(R.id.btnCreateBranch)
        btnCancel            = view.findViewById(R.id.btnCancelBranch)

        ivBranchImage = view.findViewById(R.id.ivBranchImagePicker)
        ivBranchImage.setOnClickListener { imagePicker.launch("image/*") }

        tvIdGenerated.text = "Branch ID: $autoId"

        spinnerType.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item,
            listOf("Hub", "Collection Point", "Sub")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spinnerStatus.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item,
            listOf("active", "inactive")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        lifecycleScope.launch { loadData() }

        btnSelectManager.setOnClickListener {
            showMultiSlotPicker("Select Managers (persons + roles)", allEmployees, managerSel) {
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
            showMultiSlotPicker("Select Accountants (persons + roles)", allEmployees, accountantSel) {
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
            showMultiSlotPicker("Select Petty Cash POCs (persons + roles)", allEmployees, pocSel) {
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
            // Both the display label and field/variable names are "Staff"
            // now (staff_uid/staff_role, selectedStaff*) -- renamed from
            // "Team Aligned" fully since no production data existed under
            // the old names yet.
            showMultiSlotPicker("Select Staff (persons + roles)", allEmployees, staffSel) {
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
            showSearchPicker("Select Parent Branch", allBranches) { item ->
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

        btnCreate.setOnClickListener { onCreateClicked() }
        btnCancel.setOnClickListener { parentFragmentManager.popBackStack() }
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

    private suspend fun loadData() {
        try {
            val usersSnap    = db.reference.child("users").get().await()

            allEmployees.clear()
            usersSnap.children.forEach { child ->
                val uid   = child.key ?: return@forEach
                val role  = child.child("profile/company_info/role_id").getValue(String::class.java) ?: ""
                val name  = child.child("profile/name").getValue(String::class.java)
                           ?: child.child("profile/email").getValue(String::class.java)
                           ?: uid.take(8)
                val empId = child.child("profile/company_info/employee_id").getValue(String::class.java) ?: ""
                val desig = child.child("profile/company_info/designation").getValue(String::class.java)
                           ?: EmployeeFragment.ROLE_LABELS[role] ?: role
                allEmployees.add(PickerItem(uid, name, desig, empId))
            }

            // Roles come straight from Firebase (roles/{roleId}, admin-configured via Access
            // Manager) — no built-in fallback list, so a role that's been removed or renamed
            // there can't still show up here as a pickable (but no-longer-real) option.
            allRoles.clear()
            val rolesSnap = db.reference.child("roles").get().await()
            rolesSnap.children.forEach { child ->
                val roleId = child.key ?: return@forEach
                val roleName = child.child("name").getValue(String::class.java) ?: roleId
                allRoles.add(PickerItem("role:$roleId", roleName, "Role — everyone with this role at this branch"))
            }

            allBranches.clear()
            // Parent-branch picker reads the Supabase directory (the branch
            // cutover moved persistence off Firebase `branches/`) — a failed
            // read only empties this picker, never the employee/role pickers.
            runCatching { SupabaseBranchReader.listBranches() }
                .onSuccess { rows ->
                    rows.forEach { r -> allBranches.add(PickerItem(r.branchId, r.name, r.branchType)) }
                }
                .onFailure {
                    if (isAdded) Toast.makeText(requireContext(), "Couldn't load branch list", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            if (isAdded) Toast.makeText(requireContext(), "Failed to load data", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onCreateClicked() {
        val code   = etBranchCode.text.toString().trim()
        val name   = etBranchName.text.toString().trim()
        val type   = spinnerType.selectedItem?.toString() ?: "Hub"
        val addr   = etAddress.text.toString().trim()
        val lat    = etLatitude.text.toString().toDoubleOrNull() ?: 0.0
        val lng    = etLongitude.text.toString().toDoubleOrNull() ?: 0.0
        val email  = etEmail.text.toString().trim()
        val phone  = etPhone.text.toString().trim()
        val region = etRegion.text.toString().trim()
        val pettyCashLimit = etPettyCashLimit.text.toString().trim().toDoubleOrNull() ?: 0.0
        val status = spinnerStatus.selectedItem?.toString() ?: "active"

        if (code.isBlank()) { toast("Branch Code required"); return }
        if (name.isBlank()) { toast("Branch Name required"); return }

        lifecycleScope.launch {
            try {
                btnCreate.isEnabled = false

                val imageUrl = uploadImageIfNeeded(autoId)

                // Branch directory persists ONLY to Supabase now (branch
                // cutover) — no Firebase `branches/$autoId` write, no
                // employees index, no updated_log (none have Supabase
                // columns). The Edge Function also syncs the assignees'
                // Supabase users.branch_ids (RLS membership) server-side.
                SupabaseBranchWriter.save(
                    SupabaseBranchWriter.BranchPayload(
                        branchId = autoId,
                        branchCode = code,
                        name = name,
                        branchType = type,
                        address = addr,
                        latitude = lat,
                        longitude = lng,
                        email = email,
                        phone = phone,
                        managerUids = managerSel.uids.toList(),
                        managerRoles = managerSel.roles.toList(),
                        accountantUids = accountantSel.uids.toList(),
                        accountantRoles = accountantSel.roles.toList(),
                        pettyCashPocUids = pocSel.uids.toList(),
                        pettyCashPocRoles = pocSel.roles.toList(),
                        pettyCashLimit = pettyCashLimit,
                        staffUids = staffSel.uids.toList(),
                        staffRoles = staffSel.roles.toList(),
                        parentBranchId = selectedParentId,
                        region = region,
                        status = status,
                        imageUrl = imageUrl,
                        managerUid = managerSel.uids.firstOrNull().orEmpty(),
                        accountantUid = accountantSel.uids.firstOrNull().orEmpty(),
                        accountantRole = accountantSel.roles.firstOrNull().orEmpty(),
                        pettyCashPocUid = pocSel.uids.firstOrNull().orEmpty(),
                        staffUid = staffSel.uids.firstOrNull().orEmpty(),
                        staffRole = staffSel.roles.firstOrNull().orEmpty()
                    )
                )

                // Firebase user-profile branch_ids stay in sync (they feed other
                // Firebase-gated reads and the next sync_profile) — branch
                // membership, not branch data. The Edge Function fans the same
                // membership out to Supabase users.branch_ids (RLS) server-side.
                val allNewUids = managerSel.uids + accountantSel.uids + pocSel.uids + staffSel.uids
                for (uid in allNewUids) {
                    val idsSnap = db.reference.child("users/$uid/profile/company_info/branch_ids").get().await()
                    val currentIds = if (idsSnap.exists()) idsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    db.reference.child("users/$uid/profile/company_info/branch_ids")
                        .setValue((currentIds + autoId).distinct()).await()
                }

                toast("Branch created ✓")
                parentFragmentManager.popBackStack()
            } catch (e: Exception) {
                btnCreate.isEnabled = true
                if (isAdded) toast("Failed: ${e.message}")
            }
        }
    }

    private suspend fun uploadImageIfNeeded(branchId: String): String {
        val uri = selectedImageUri ?: return uploadedImageUrl
        return try {
            val ref = storage.reference.child("branch_images/$branchId/cover.jpg")
            ref.putFile(uri).await()
            ref.downloadUrl.await().toString()
        } catch (_: Exception) { uploadedImageUrl }
    }

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
