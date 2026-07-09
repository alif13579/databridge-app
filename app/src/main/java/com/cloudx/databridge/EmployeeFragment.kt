package com.cloudx.databridge

import android.graphics.Color
import android.graphics.Typeface
import android.content.res.Configuration
import android.util.TypedValue
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import coil.load
import coil.transform.CircleCropTransformation
import com.google.firebase.database.FirebaseDatabase
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class EmployeeFragment : Fragment() {

    // ── Role hierarchy (level = lower number means higher authority) ──
    // admin can manage ALL roles including same level (admin)
    // others can only manage LOWER level roles
    companion object {
        val ROLE_LEVELS  = mapOf("admin" to 0, "manager" to 1, "supervisor" to 2, "stuff" to 3, "worker" to 4, "guest" to 5)
        val ROLE_LABELS  = mapOf("admin" to "👑 Admin", "manager" to "💼 Manager",
                                  "supervisor" to "🎯 Supervisor", "stuff" to "📋 Stuff",
                                  "worker" to "👤 Worker", "guest" to "🙋 Guest")
        val ROLE_COLORS  = mapOf("admin" to "#7c3aed", "manager" to "#2563eb",
                                  "supervisor" to "#059669", "stuff" to "#d97706",
                                  "worker" to "#4b5563", "guest" to "#888888")

        private const val ARG_ROLE   = "arg_role"
        private const val ARG_BRANCH = "arg_branch"

        /** Only admin and manager may create/edit/delete branches. */
        fun canManageBranches(roleId: String) = roleId == "admin" || roleId == "manager"

        /** Minimum level required to manage (create/edit) users: admin/manager/supervisor/stuff (level <= 3). */
        fun canManageUsers(roleId: String) = (ROLE_LEVELS[roleId] ?: 99) <= 3

        fun forRole(roleId: String): EmployeeFragment =
            EmployeeFragment().also {
                it.arguments = android.os.Bundle().apply { putString(ARG_ROLE, roleId) }
            }

        fun forBranch(branchId: String): EmployeeFragment =
            EmployeeFragment().also {
                it.arguments = android.os.Bundle().apply { putString(ARG_BRANCH, branchId) }
            }
    }

    data class UserEntry(
        val uid: String,
        val systemId: String = "",
        val employeeId: String,
        val name: String,
        val email: String,
        val roleId: String,
        val status: String,
        val roleName: String,
        val roleWarning: String = "",
        val branchIds: List<String>,
        val branchName: String,
        val designation: String,
        val photoUrl: String = ""
    )

    private fun showFilterDialog() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 8)
        }
        fun label(text: String) = TextView(ctx).apply {
            this.text = text
            setTextColor(Color.parseColor("#888888"))
            textSize = 12f
        }.also { layout.addView(it) }

        fun spinner(options: List<String>, selectedValue: String, labelList: List<String>? = null): Spinner {
            val labels = labelList ?: options
            val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, labels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            val sp = Spinner(ctx)
            sp.adapter = adapter
            val idx = options.indexOf(selectedValue).takeIf { it >= 0 } ?: 0
            sp.setSelection(idx)
            layout.addView(sp)
            return sp
        }

        label("Status")
        val statusOptions = listOf("", "active", "inactive")
        val statusLabels = listOf("All", "Active", "Inactive")
        val spStatus = spinner(statusOptions, filterStatus, statusLabels)

        label("Role")
        val roleOptions = listOf("") + roleMap.keys.sorted()
        val roleLabels = listOf("All roles") + roleOptions.drop(1).map { rid -> roleMap[rid].takeUnless { it.isNullOrBlank() } ?: rid }
        val spRole = spinner(roleOptions, filterRole, roleLabels)

        label("Designation")
        val designations = (listOf("") + allUsers.map { it.designation }.filter { it.isNotBlank() }.distinct().sorted())
        val spDesig = spinner(designations, filterDesignation, listOf("All designations") + designations.drop(1))

        label("Branch")
        val branchOpts = listOf("") + branchMap.keys.sorted()
        val spBranch = spinner(branchOpts, filterBranch, listOf("All branches") + branchOpts.drop(1).map { branchMap[it] ?: it })

        AlertDialog.Builder(ctx)
            .setTitle("Filters")
            .setView(layout)
            .setPositiveButton("Apply") { d, _ ->
                filterStatus = statusOptions.getOrNull(spStatus.selectedItemPosition).orEmpty()
                filterRole = roleOptions.getOrNull(spRole.selectedItemPosition).orEmpty()
                filterDesignation = designations.getOrNull(spDesig.selectedItemPosition).orEmpty()
                filterBranch = branchOpts.getOrNull(spBranch.selectedItemPosition).orEmpty()
                renderUsers()
                d.dismiss()
            }
            .setNegativeButton("Clear") { d, _ ->
                filterStatus = ""; filterRole = ""; filterDesignation = ""; filterBranch = ""
                renderUsers(); d.dismiss()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private val db = FirebaseDatabase.getInstance()
    private val branchFilter by lazy { arguments?.getString(ARG_BRANCH).orEmpty() }

    private var allUsers   = listOf<UserEntry>()
    private var roleMap    = mapOf<String, String>()   // roleId -> configured name, blank means undefined name
    private var branchMap  = mapOf<String, String>()   // branchId → name
    private var agentTypeOptions = listOf<SalaryModelOption>()
    private var salaryModelOptions = listOf<SalaryModelOption>()
    private var salaryModelsByAgent = mapOf<String, List<SalaryModelOption>>()
    private var filterRole = ""
    private var filterStatus = ""
    private var filterDesignation = ""
    private var filterBranch = ""
    private var searchQuery = ""

    private lateinit var adapter: UserAdapter
    private lateinit var rvUsers: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var ivAddUser: ImageView
    private lateinit var etSearch: EditText
    private lateinit var spFilterStatus: Spinner
    private lateinit var spFilterRole: Spinner
    private lateinit var spFilterDesignation: Spinner
    private lateinit var spFilterBranch: Spinner
    private lateinit var btnClearStatus: ImageButton
    private lateinit var btnClearRole: ImageButton
    private lateinit var btnClearDesignation: ImageButton
    private lateinit var btnClearBranch: ImageButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_employee, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rvUsers   = view.findViewById(R.id.rvUsers)
        pbLoading = view.findViewById(R.id.pbUsersLoading)
        tvEmpty   = view.findViewById(R.id.tvUsersEmpty)
        ivAddUser = view.findViewById(R.id.ivAddUser)
        etSearch  = view.findViewById(R.id.etUserSearch)
        spFilterStatus = view.findViewById(R.id.spFilterStatus)
        spFilterRole = view.findViewById(R.id.spFilterRole)
        spFilterDesignation = view.findViewById(R.id.spFilterDesignation)
        spFilterBranch = view.findViewById(R.id.spFilterBranch)
        btnClearStatus = view.findViewById(R.id.btnClearStatus)
        btnClearRole = view.findViewById(R.id.btnClearRole)
        btnClearDesignation = view.findViewById(R.id.btnClearDesignation)
        btnClearBranch = view.findViewById(R.id.btnClearBranch)

        // Pre-filter if launched from Employees submenu
        arguments?.getString(ARG_ROLE)?.let { roleId ->
            filterRole = roleId
        }

        setupRecyclerView()
        setupFilterSpinners()
        setupSearch()
        setupAddButton()
        loadData()
    }

    // ── RBAC helpers ──────────────────────────────────────────────────────

    private val myRoleId get() = RbacManager.current.roleId

    private fun canManageRole(targetRoleId: String): Boolean {
        if (myRoleId == "admin") return true
        val myLevel = ROLE_LEVELS[myRoleId] ?: return false
        if (myLevel > 3) return false  // worker(4) / guest(5) cannot manage anyone
        val targetLevel = ROLE_LEVELS[targetRoleId] ?: return false
        return myLevel < targetLevel
    }

    private fun manageableRoleIds(): List<String> {
        val myRole = RbacManager.current.roleId
        val myLevel = ROLE_LEVELS[myRole] ?: 99
        val roleIdsFromDb = roleMap.keys.sorted()
        return roleIdsFromDb.filter { rid ->
            when {
                myRole == "admin" -> true
                myLevel > 3 -> false // stuff/worker/guest cannot assign any role
                else -> (ROLE_LEVELS[rid] ?: 99) > myLevel
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ── Setup ─────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = UserAdapter(
            onEdit   = { user -> openEditDialog(user) },
            onDelete = { user -> confirmRemoveRole(user) },
            canManage = ::canManageRole
        )
        rvUsers.layoutManager = LinearLayoutManager(requireContext())
        rvUsers.adapter = adapter
    }

    private fun setupFilterSpinners() {
        // Status
        val statusOptions = listOf("", "active", "inactive")
        val statusLabels = listOf("Status", "Active", "Inactive")
        spFilterStatus.adapter = spinnerAdapter(statusLabels)
        spFilterStatus.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                filterStatus = statusOptions.getOrNull(position).orEmpty()
                toggleClear(btnClearStatus, filterStatus)
                renderUsers()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        // Role
        fun refreshRoleSpinner() {
            val roleOpts = listOf("") + roleMap.keys.sorted()
            val roleLabels = listOf("Role") + roleOpts.drop(1).map { rid -> roleMap[rid].takeUnless { it.isNullOrBlank() } ?: rid }
            spFilterRole.adapter = spinnerAdapter(roleLabels)
            val idx = roleOpts.indexOf(filterRole).takeIf { it >= 0 } ?: 0
            spFilterRole.setSelection(idx)
            spFilterRole.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                    filterRole = roleOpts.getOrNull(position).orEmpty()
                    toggleClear(btnClearRole, filterRole)
                    renderUsers()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
            }
        }
        refreshRoleSpinner()

        // Designation
        fun refreshDesignationSpinner() {
            val desigs = listOf("") + allUsers.map { it.designation }.filter { it.isNotBlank() }.distinct().sorted()
            val labels = listOf("Designation") + desigs.drop(1)
            spFilterDesignation.adapter = spinnerAdapter(labels)
            val idx = desigs.indexOf(filterDesignation).takeIf { it >= 0 } ?: 0
            spFilterDesignation.setSelection(idx)
            spFilterDesignation.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                    filterDesignation = desigs.getOrNull(position).orEmpty()
                    toggleClear(btnClearDesignation, filterDesignation)
                    renderUsers()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
            }
        }
        refreshDesignationSpinner()

        // Branch
        fun refreshBranchSpinner() {
            val branchOpts = listOf("") + branchMap.keys.sorted()
            val labels = listOf("Branch") + branchOpts.drop(1).map { branchMap[it] ?: it }
            spFilterBranch.adapter = spinnerAdapter(labels)
            val idx = branchOpts.indexOf(filterBranch).takeIf { it >= 0 } ?: 0
            spFilterBranch.setSelection(idx)
            spFilterBranch.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                    filterBranch = branchOpts.getOrNull(position).orEmpty()
                    toggleClear(btnClearBranch, filterBranch)
                    renderUsers()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
            }
        }
        refreshBranchSpinner()

        btnClearStatus.setOnClickListener {
            filterStatus = ""
            spFilterStatus.setSelection(0)
            toggleClear(btnClearStatus, filterStatus)
            renderUsers()
        }
        btnClearRole.setOnClickListener {
            filterRole = ""
            spFilterRole.setSelection(0)
            toggleClear(btnClearRole, filterRole)
            renderUsers()
        }
        btnClearDesignation.setOnClickListener {
            filterDesignation = ""
            spFilterDesignation.setSelection(0)
            toggleClear(btnClearDesignation, filterDesignation)
            renderUsers()
        }
        btnClearBranch.setOnClickListener {
            filterBranch = ""
            spFilterBranch.setSelection(0)
            toggleClear(btnClearBranch, filterBranch)
            renderUsers()
        }
    }

    private fun toggleClear(button: ImageButton, value: String) {
        button.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
    }

    private fun spinnerAdapter(labels: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.apply {
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ContextCompat.getColor(context, android.R.color.white))
                    setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_arrow_drop_down_white, 0)
                    compoundDrawablePadding = dp(6)
                }
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.apply { textSize = 12f }
                return v
            }
        }.apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString().orEmpty().trim()
                renderUsers()
            }
        })
    }

    private fun setupAddButton() {
        refreshAddButtonVisibility()
        ivAddUser.setOnClickListener { openAddDialog() }
    }

    private fun refreshAddButtonVisibility() {
        val canAdd = manageableRoleIds().isNotEmpty()
        ivAddUser.visibility = if (canAdd) View.VISIBLE else View.GONE
    }

    // ── Data loading ──────────────────────────────────────────────────────

    private fun loadData() {
        pbLoading.visibility = View.VISIBLE
        rvUsers.visibility   = View.GONE
        tvEmpty.visibility   = View.GONE

        lifecycleScope.launch {
            try {
                val rolesSnap  = db.reference.child("roles").get().await()
                val branchSnap = db.reference.child("branches").get().await()
                val usersSnap  = db.reference.child("users").get().await()
                val salariesSnap = runCatching {
                    db.reference.child("salaries").get().await()
                }.getOrNull()

                roleMap = rolesSnap.children.mapNotNull { child ->
                    child.key?.let { it to child.child("name").getValue(String::class.java).orEmpty().trim() }
                }.toMap()

                branchMap = branchSnap.children.mapNotNull { child ->
                    child.key?.let { it to (child.child("name").getValue(String::class.java) ?: it) }
                }.toMap()

                agentTypeOptions = salariesSnap?.children?.mapNotNull { child ->
                    val id = child.key ?: return@mapNotNull null
                    SalaryModelOption(id, agentTypeLabel(id))
                }?.sortedBy { it.name.lowercase() } ?: emptyList()
                salaryModelsByAgent = agentTypeOptions.associate { option ->
                    option.id to (salariesSnap?.child("${option.id}/commission_models")?.toSalaryModelOptions() ?: emptyList())
                }
                salaryModelOptions = emptyList()

                allUsers = usersSnap.children.mapNotNull { child ->
                    val uid   = child.key ?: return@mapNotNull null
                    val systemId = child.child("profile/company_info/system_id").getValue(String::class.java) ?: ""
                    val employeeId = child.child("profile/company_info/employee_id").getValue(String::class.java) ?: ""
                    val name  = child.child("profile/name").getValue(String::class.java)
                                ?: child.child("name").getValue(String::class.java)
                                ?: "Unknown"
                    val email = child.child("profile/email").getValue(String::class.java)
                                ?: child.child("email").getValue(String::class.java)
                                ?: ""
                    val roleId      = child.child("profile/company_info/role_id").getValue(String::class.java).orEmpty()
                    val status      = child.child("profile/company_info/status").getValue(String::class.java).orEmpty()
                    val configuredRoleName = roleMap[roleId].orEmpty().trim()
                    val roleWarning = when {
                        roleId.isBlank() -> "missing_role"
                        configuredRoleName.isBlank() -> "missing_role_name"
                        else -> ""
                    }
                    val roleNameDisplay = when (roleWarning) {
                        "missing_role" -> "Role undefined"
                        "missing_role_name" -> "Role Name Undefined"
                        else -> configuredRoleName
                    }
                    val branchIds   = child.child("profile/company_info/branch_ids").children.mapNotNull { it.getValue(String::class.java) }
                    val primaryId   = branchIds.firstOrNull().orEmpty()
                    val branchNameDisplay = if (branchIds.isEmpty()) "" else if (branchIds.size == 1) {
                        branchMap[primaryId] ?: primaryId
                    } else {
                        val names = branchIds.map { id -> branchMap[id] ?: id }
                        if (names.size <= 3) names.joinToString(", ") else names.take(3).joinToString(", ") + " +${names.size - 3} more"
                    }
                    val designation = child.child("profile/company_info/designation").getValue(String::class.java).orEmpty()
                    val photoUrl    = child.child("profile/photo_url").getValue(String::class.java).orEmpty()
                    UserEntry(
                        uid         = uid,
                        systemId    = systemId,
                        employeeId  = employeeId,
                        name        = name,
                        email       = email,
                        roleId      = roleId,
                        status      = status,
                        roleName    = roleNameDisplay,
                        roleWarning = roleWarning,
                        branchIds   = branchIds,
                        branchName  = branchNameDisplay,
                        designation = designation,
                        photoUrl    = photoUrl
                    )
                }.sortedWith(
                    compareBy({ ROLE_LEVELS[it.roleId] ?: 99 }, { it.name })
                ).let { list ->
                    if (branchFilter.isNotBlank()) list.filter { it.branchIds.contains(branchFilter) } else list
                }

                pbLoading.visibility = View.GONE
                refreshAddButtonVisibility()
                setupFilterSpinners()
                renderUsers()
            } catch (e: Exception) {
                if (!isAdded) return@launch
                pbLoading.visibility = View.GONE
                tvEmpty.text    = "Failed to load members."
                tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun renderUsers() {
        val q = searchQuery.lowercase()
        val filtered = allUsers.filter { user ->
            (filterRole.isEmpty() || user.roleId == filterRole) &&
            (filterStatus.isEmpty() || user.status.equals(filterStatus, ignoreCase = true)) &&
            (filterDesignation.isEmpty() || user.designation.equals(filterDesignation, ignoreCase = true)) &&
            (filterBranch.isEmpty() || user.branchIds.contains(filterBranch)) &&
            (q.isEmpty() || user.name.contains(q, ignoreCase = true)
                         || user.email.contains(q, ignoreCase = true)
                         || user.branchName.contains(q, ignoreCase = true))
        }
        adapter.submitList(filtered)
        rvUsers.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text       = if (filtered.isEmpty()) "No matching members." else ""
    }

    // ── Add / Edit dialogs ────────────────────────────────────────────────

    private fun openAddDialog() {
        val allowed = manageableRoleIds()
        if (allowed.isEmpty()) {
            Toast.makeText(requireContext(), "No permission to add users.", Toast.LENGTH_SHORT).show()
            return
        }
        showUserDialog(title = "Add Team Member", user = null, allowedRoles = allowed)
    }

    private fun openEditDialog(user: UserEntry) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, EmployeeEditFragment.newInstance(user.uid))
            .addToBackStack(null)
            .commit()
    }

    private fun showUserDialog(title: String, user: UserEntry?, allowedRoles: List<String>) {
        val nextSystemId = if (user == null) generateSystemId() else user.systemId
        buildUserDialog(title, user, allowedRoles, nextSystemId)
    }

    /** Scans all users' system_id, returns (max numeric + 1) as string. Falls back to "1". */
    /** Generates System ID: U + DDMMYY + 4-digit random, e.g. U0407262653 */
    private fun generateSystemId(): String {
        val sdf = java.text.SimpleDateFormat("ddMMyy", java.util.Locale.US)
        val datePart = sdf.format(java.util.Date())
        val randomPart = (0..9999).random().toString().padStart(4, '0')
        return "U$datePart$randomPart"
    }

    private fun buildUserDialog(title: String, user: UserEntry?, allowedRoles: List<String>, prefilledSystemId: String) {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        val isNight = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val colorPrimary = if (isNight) Color.parseColor("#ffffff") else Color.parseColor("#111111")
        val colorSecondary = Color.parseColor("#888888")
        val colorHint = if (isNight) Color.parseColor("#777777") else Color.parseColor("#888888")
        val colorAccent = Color.parseColor("#00d4ff")

        fun addLabel(text: String) = TextView(ctx).also {
            it.text = text; it.setTextColor(colorSecondary); it.textSize = 12f
            layout.addView(it)
        }
        fun addInput(hint: String, value: String = ""): EditText = EditText(ctx).also {
            it.hint = hint; it.setText(value)
            it.setTextColor(colorPrimary)
            it.setHintTextColor(colorHint)
            it.textSize = 14f
            layout.addView(it)
        }

        addLabel("Firebase UID (required for new user)")
        val etUid     = addInput("users/{uid}", user?.uid ?: "")
        etUid.isEnabled = (user == null)

        addLabel("System ID")
        val etSystemId = addInput("Auto-generated", prefilledSystemId)

        addLabel("Employee ID")
        val etEmployeeId = addInput("EMP001", user?.employeeId ?: "")

        addLabel("Display Name")
        val etName    = addInput("Full name", user?.name ?: "")

        addLabel("Email")
        val etEmail   = addInput("email@company.com", user?.email ?: "")

        addLabel("Phone")
        val etPhone   = addInput("+880...", "")

        addLabel("Designation")
        val etDesig   = addInput("e.g. Senior Developer", "")

        addLabel("Branches")
        val allBranchIds = branchMap.keys.toList()
        if (allBranchIds.isNotEmpty()) {
            val branchLabels  = allBranchIds.map { branchMap[it] ?: it }
            val selected = mutableSetOf<String>()
            // Picker trigger
            val btnPick = TextView(ctx).apply {
                text = "Tap to select branches ▾"
                setTextColor(colorSecondary)
                textSize = 14f
                setPadding(16, 24, 16, 24)
                // Use ripple/selectable background to avoid dark box in light mode
                val tv = TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                isClickable = true; isFocusable = true
            }
            val tvPicked = TextView(ctx).apply {
                text = "None selected"
                setTextColor(colorSecondary)
                textSize = 12f
                setPadding(4, 8, 4, 16)
            }
            fun refreshSummary() {
                if (selected.isEmpty()) {
                    btnPick.text = "Tap to select branches ▾"
                    btnPick.setTextColor(colorSecondary)
                    tvPicked.text = "None selected"
                    tvPicked.setTextColor(colorSecondary)
                } else if (selected.size == 1) {
                    val id = selected.first()
                    val name = branchMap[id] ?: id
                    btnPick.text = name
                    btnPick.setTextColor(colorPrimary)
                    tvPicked.text = "1 branch selected"
                    tvPicked.setTextColor(colorAccent)
                } else {
                    val names = allBranchIds.filter { selected.contains(it) }.map { branchMap[it] ?: it }
                    btnPick.text = "${selected.size} branches selected"
                    btnPick.setTextColor(colorPrimary)
                    val preview = if (names.size <= 3) names.joinToString(", ") else names.take(3).joinToString(", ") + " +${names.size - 3} more"
                    tvPicked.text = preview
                    tvPicked.setTextColor(colorAccent)
                }
            }
            btnPick.setOnClickListener {
                val checked = allBranchIds.map { selected.contains(it) }.toBooleanArray()
                AlertDialog.Builder(ctx)
                    .setTitle("Select Branches")
                    .setMultiChoiceItems(branchLabels.toTypedArray(), checked) { _, which, isChecked ->
                        val bid = allBranchIds[which]
                        if (isChecked) selected.add(bid) else selected.remove(bid)
                    }
                    .setPositiveButton("Done") { d, _ ->
                        refreshSummary(); d.dismiss()
                    }
                    .setNeutralButton("Clear") { _, _ ->
                        selected.clear(); refreshSummary()
                    }
                    .show()
            }
            refreshSummary()
            layout.addView(btnPick)
            layout.addView(tvPicked)

            addLabel("Role")
            val roleSpinner = Spinner(ctx)
            val roleLabels  = allowedRoles.map { rid -> roleMap[rid]?.takeIf { it.isNotBlank() } ?: rid }
            roleSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, roleLabels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val currentRoleIdx = allowedRoles.indexOf(user?.roleId ?: "").coerceAtLeast(0)
            roleSpinner.setSelection(currentRoleIdx)
            layout.addView(roleSpinner)

            addLabel("Status")
            val statusSpinner = Spinner(ctx)
            val statusValues = listOf("active", "inactive")
            val statusLabels = listOf("Active", "Inactive")
            statusSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, statusLabels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            statusSpinner.setSelection(0)
            layout.addView(statusSpinner)

            // Salary Type (separate toggle, placeholder non-selectable, clear button)
            val btnToggleType = TextView(ctx).apply {
                text = "+ Add Salary Type"
                setTextColor(colorSecondary)
                textSize = 13f
                setPadding(0, 16, 0, 8)
            }
            layout.addView(btnToggleType)
            val llType = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            layout.addView(llType)
            llType.addView(TextView(ctx).also { it.text = "Salary Type (optional)"; it.setTextColor(colorSecondary); it.textSize = 12f })
            val flType = FrameLayout(ctx)
            llType.addView(flType)
            val typeLabels  = listOf("Fixed", "Variable", "Select salary type")
            val typeValues  = listOf("fixed", "variable")
            val typeAdapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, typeLabels) {
                override fun getCount(): Int = super.getCount() - 1
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val spType = Spinner(ctx).apply { adapter = typeAdapter; setSelection(typeAdapter.count) }
            flType.addView(spType, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            val btnClearType = TextView(ctx).apply { text = "✕"; setTextColor(colorSecondary); textSize = 16f; setPadding(16, 16, 16, 16); visibility = View.GONE }
            flType.addView(btnClearType, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
            btnToggleType.setOnClickListener {
                val visible = llType.visibility == View.VISIBLE
                llType.visibility = if (visible) View.GONE else View.VISIBLE
                btnToggleType.text = if (visible) "+ Add Salary Type" else "− Hide Salary Type"
                btnToggleType.setTextColor(if (visible) colorSecondary else colorPrimary)
            }
            spType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                    val placeholderIdx = (spType.adapter as ArrayAdapter<*>).count
                    btnClearType.visibility = if (position < placeholderIdx) View.VISIBLE else View.GONE
                    if (position < placeholderIdx) {
                        llType.visibility = View.VISIBLE
                        btnToggleType.visibility = View.GONE
                    } else {
                        llType.visibility = View.GONE
                        btnToggleType.visibility = View.VISIBLE
                        btnToggleType.text = "+ Add Salary Type"
                        btnToggleType.setTextColor(colorSecondary)
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
            }
            btnClearType.setOnClickListener {
                val placeholderIdx = (spType.adapter as ArrayAdapter<*>).count
                spType.setSelection(placeholderIdx)
                btnClearType.visibility = View.GONE
                llType.visibility = View.GONE
                btnToggleType.visibility = View.VISIBLE
                btnToggleType.text = "+ Add Salary Type"
                btnToggleType.setTextColor(colorSecondary)
            }

            // Agent Type (separate toggle, placeholder non-selectable, clear button)
            val btnToggleAgent = TextView(ctx).apply {
                text = "+ Add Agent Type"
                setTextColor(colorSecondary)
                textSize = 13f
                setPadding(0, 16, 0, 8)
            }
            layout.addView(btnToggleAgent)
            val llAgent = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            layout.addView(llAgent)
            llAgent.addView(TextView(ctx).also { it.text = "Agent Type (optional)"; it.setTextColor(colorSecondary); it.textSize = 12f })
            val flAgent = FrameLayout(ctx)
            llAgent.addView(flAgent)
            val agentLabels = agentTypeOptions.map { it.name } + "Select agent type"
            val agentValues = agentTypeOptions.map { it.id }
            val agentAdapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, agentLabels) {
                override fun getCount(): Int = super.getCount() - 1
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val spAgent = Spinner(ctx).apply { adapter = agentAdapter; setSelection(agentAdapter.count) }
            flAgent.addView(spAgent, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            val btnClearAgent = TextView(ctx).apply { text = "✕"; setTextColor(colorSecondary); textSize = 16f; setPadding(16, 16, 16, 16); visibility = View.GONE }
            flAgent.addView(btnClearAgent, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
            btnToggleAgent.setOnClickListener {
                val visible = llAgent.visibility == View.VISIBLE
                llAgent.visibility = if (visible) View.GONE else View.VISIBLE
                btnToggleAgent.text = if (visible) "+ Add Agent Type" else "− Hide Agent Type"
                btnToggleAgent.setTextColor(if (visible) colorSecondary else colorPrimary)
            }
            layout.removeView(btnToggleType)
            layout.removeView(llType)
            layout.addView(btnToggleType)
            layout.addView(llType)

            // Salary Model (separate toggle, placeholder non-selectable, clear button)
            val btnToggleModel = TextView(ctx).apply {
                text = "+ Add Salary Model"
                setTextColor(colorSecondary)
                textSize = 13f
                setPadding(0, 16, 0, 8)
            }
            layout.addView(btnToggleModel)
            val llModel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            layout.addView(llModel)
            llModel.addView(TextView(ctx).also { it.text = "Salary Model (optional)"; it.setTextColor(colorSecondary); it.textSize = 12f })
            val flModel = FrameLayout(ctx)
            llModel.addView(flModel)
            var modelValues = emptyList<String>()
            val spModel = Spinner(ctx)
            fun configureModelSpinner(agentType: String) {
                val options = salaryModelsByAgent[agentType] ?: emptyList()
                modelValues = options.map { it.id }
                val modelLabels = options.map { it.name } + "Select salary model"
                val modelAdapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, modelLabels) {
                    override fun getCount(): Int = super.getCount() - 1
                }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                spModel.adapter = modelAdapter
                spModel.setSelection(modelAdapter.count)
            }
            configureModelSpinner("")
            flModel.addView(spModel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            val btnClearModel = TextView(ctx).apply { text = "✕"; setTextColor(colorSecondary); textSize = 16f; setPadding(16, 16, 16, 16); visibility = View.GONE }
            flModel.addView(btnClearModel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
            btnToggleModel.setOnClickListener {
                val visible = llModel.visibility == View.VISIBLE
                llModel.visibility = if (visible) View.GONE else View.VISIBLE
                btnToggleModel.text = if (visible) "+ Add Salary Model" else "− Hide Salary Model"
                btnToggleModel.setTextColor(if (visible) colorSecondary else colorPrimary)
            }
            spModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                    val placeholderIdx = (spModel.adapter as ArrayAdapter<*>).count
                    btnClearModel.visibility = if (position < placeholderIdx) View.VISIBLE else View.GONE
                    if (position < placeholderIdx) {
                        llModel.visibility = View.VISIBLE
                        btnToggleModel.visibility = View.GONE
                    } else {
                        llModel.visibility = View.GONE
                        btnToggleModel.visibility = View.VISIBLE
                        btnToggleModel.text = "+ Add Salary Model"
                        btnToggleModel.setTextColor(colorSecondary)
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
            }
            btnClearModel.setOnClickListener {
                val placeholderIdx = (spModel.adapter as ArrayAdapter<*>).count
                spModel.setSelection(placeholderIdx)
                btnClearModel.visibility = View.GONE
                llModel.visibility = View.GONE
                btnToggleModel.visibility = View.VISIBLE
                btnToggleModel.text = "+ Add Salary Model"
                btnToggleModel.setTextColor(colorSecondary)
            }
            spAgent.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                    val placeholderIdx = (spAgent.adapter as ArrayAdapter<*>).count
                    btnClearAgent.visibility = if (position < placeholderIdx) View.VISIBLE else View.GONE
                    if (position < placeholderIdx) {
                        llAgent.visibility = View.VISIBLE
                        btnToggleAgent.visibility = View.GONE
                        configureModelSpinner(agentValues.getOrNull(position).orEmpty())
                    } else {
                        llAgent.visibility = View.GONE
                        btnToggleAgent.visibility = View.VISIBLE
                        btnToggleAgent.text = "+ Add Agent Type"
                        btnToggleAgent.setTextColor(colorSecondary)
                        configureModelSpinner("")
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
            }
            btnClearAgent.setOnClickListener {
                val placeholderIdx = (spAgent.adapter as ArrayAdapter<*>).count
                spAgent.setSelection(placeholderIdx)
                btnClearAgent.visibility = View.GONE
                llAgent.visibility = View.GONE
                btnToggleAgent.visibility = View.VISIBLE
                btnToggleAgent.text = "+ Add Agent Type"
                btnToggleAgent.setTextColor(colorSecondary)
                configureModelSpinner("")
            }

            // Fixed Amount (separate toggle)
            val btnToggleFixed = TextView(ctx).apply {
                text = "+ Add Salary Amount"
                setTextColor(colorSecondary)
                textSize = 13f
                setPadding(0, 16, 0, 8)
            }
            layout.addView(btnToggleFixed)
            val llFixed = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            layout.addView(llFixed)
            llFixed.addView(TextView(ctx).also { it.text = "Fixed Amount (optional)"; it.setTextColor(colorSecondary); it.textSize = 12f })
            val etFixed = EditText(ctx).apply {
                hint = "e.g. 20000"
                setTextColor(colorPrimary)
                setHintTextColor(colorHint)
                textSize = 14f
            }
            llFixed.addView(etFixed)
            btnToggleFixed.setOnClickListener {
                val visible = llFixed.visibility == View.VISIBLE
                llFixed.visibility = if (visible) View.GONE else View.VISIBLE
                btnToggleFixed.text = if (visible) "+ Add Salary Amount" else "− Hide Salary Amount"
                btnToggleFixed.setTextColor(if (visible) colorSecondary else colorPrimary)
            }

            AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    val uid      = etUid.text.toString().trim()
                    val systemId = etSystemId.text.toString().trim()
                    val employeeId = etEmployeeId.text.toString().trim()
                    val name     = etName.text.toString().trim()
                    val email    = etEmail.text.toString().trim()
                    val picked   = selected.toList()
                    val roleId   = allowedRoles.getOrNull(roleSpinner.selectedItemPosition) ?: ""
                    val status   = statusValues.getOrNull(statusSpinner.selectedItemPosition) ?: "active"
                    val typePlaceholderIdx = (spType.adapter as ArrayAdapter<*>).count
                    val sType    = if (spType.selectedItemPosition >= typePlaceholderIdx) "" else typeValues[spType.selectedItemPosition]
                    val agentPlaceholderIdx = (spAgent.adapter as ArrayAdapter<*>).count
                    val agentType = if (spAgent.selectedItemPosition >= agentPlaceholderIdx) "" else agentValues.getOrNull(spAgent.selectedItemPosition).orEmpty()
                    val modelPlaceholderIdx = (spModel.adapter as ArrayAdapter<*>).count
                    val sModel   = if (spModel.selectedItemPosition >= modelPlaceholderIdx) "" else modelValues.getOrNull(spModel.selectedItemPosition).orEmpty()
                    val fixedAmt = etFixed.text.toString().trim()
                    if (uid.isBlank()) { Toast.makeText(ctx, "UID required", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                    saveUser(uid, systemId, employeeId, name, email, roleId, picked, status, sType, agentType, sModel, fixedAmt, etPhone.text.toString().trim(), etDesig.text.toString().trim())
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            addLabel("Role")
            val roleSpinner = Spinner(ctx)
            val roleLabels  = allowedRoles.map { rid -> roleMap[rid]?.takeIf { it.isNotBlank() } ?: rid }
            roleSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, roleLabels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val currentRoleIdx = allowedRoles.indexOf(user?.roleId ?: "").coerceAtLeast(0)
            roleSpinner.setSelection(currentRoleIdx)
            layout.addView(roleSpinner)

            AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    val uid    = etUid.text.toString().trim()
                    val systemId = etSystemId.text.toString().trim()
                    val employeeId = etEmployeeId.text.toString().trim()
                    val name   = etName.text.toString().trim()
                    val email  = etEmail.text.toString().trim()
                    val roleId = allowedRoles.getOrNull(roleSpinner.selectedItemPosition) ?: ""
                    if (uid.isBlank()) { Toast.makeText(ctx, "UID required", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                    saveUser(uid, systemId, employeeId, name, email, roleId, emptyList(), "active", "", "", "", "", etPhone.text.toString().trim(), etDesig.text.toString().trim())
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun saveUser(
        uid: String, systemId: String, employeeId: String, name: String, email: String,
        roleId: String, branchIds: List<String>, status: String,
        salaryType: String, agentType: String, salaryModel: String, fixedAmount: String,
        phone: String, designation: String
    ) {
        lifecycleScope.launch {
            try {
                val updates = mutableMapOf<String, Any>(
                    "users/$uid/profile/company_info/system_id"   to systemId,
                    "users/$uid/profile/company_info/employee_id" to employeeId,
                    "users/$uid/profile/company_info/role_id"     to roleId,
                    "users/$uid/profile/company_info/status"      to status,
                    "users/$uid/profile/company_info/salary_type"  to salaryType,
                    "users/$uid/profile/company_info/agent_type"   to agentType,
                    "users/$uid/profile/company_info/salary_model" to salaryModel,
                    "users/$uid/profile/company_info/fixed_amount" to fixedAmount,
                    "users/$uid/profile/company_info/designation"  to designation,
                    "users/$uid/profile/company_info/branch_ids"  to branchIds,
                    "users/$uid/profile/name"                     to name,
                    "users/$uid/profile/email"                    to email,
                    "users/$uid/profile/phone"                    to phone,
                    "users/$uid/profile/user_id"                  to uid
                )
                // Reverse-index: systemId → {uid, status}
                if (systemId.isNotBlank()) {
                    updates["users_by_systemId/$systemId/uid"]    = uid
                    updates["users_by_systemId/$systemId/status"] = status
                }
                // Sync branch employees index
                branchIds.forEach { bid ->
                    updates["branches/$bid/employees/$uid"] = mapOf("employee_id" to employeeId, "user_id" to uid)
                }
                db.reference.updateChildren(updates).await()
                Toast.makeText(requireContext(), "Saved ✓", Toast.LENGTH_SHORT).show()
                loadData()
            } catch (e: Exception) {
                if (isAdded) Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun agentTypeLabel(id: String): String {
        return id.split("_")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
    }

    // ── Remove role (soft delete) ─────────────────────────────────────────

    private fun confirmRemoveRole(user: UserEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete ${user.name}?")
            .setMessage("This will permanently delete the user and remove their branch assignments.")
            .setPositiveButton("Delete User") { _, _ -> removeRole(user) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeRole(user: UserEntry) {
        lifecycleScope.launch {
            try {
                // Load current branch_ids to clean indices
                val idsSnap = db.reference.child("users/${user.uid}/profile/company_info/branch_ids").get().await()
                val ids = if (idsSnap.exists()) idsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                val updates = mutableMapOf<String, Any?>()
                // Remove user node entirely
                updates["users/${user.uid}"] = null
                // Remove indices from all branches
                for (bid in ids) {
                    updates["branches/$bid/employees/${user.uid}"] = null
                }
                db.reference.updateChildren(updates).await()
                Toast.makeText(requireContext(), "${user.name} deleted.", Toast.LENGTH_SHORT).show()
                loadData()
            } catch (e: Exception) {
                if (isAdded) Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    class UserAdapter(
        private val onEdit:    (UserEntry) -> Unit,
        private val onDelete:  (UserEntry) -> Unit,
        private val canManage: (String) -> Boolean
    ) : ListAdapter<UserEntry, UserAdapter.VH>(Diff()) {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivAvatar   : ImageView = v.findViewById(R.id.ivUserAvatar)
            val tvInitials : TextView  = v.findViewById(R.id.tvUserInitials)
            val tvName     : TextView  = v.findViewById(R.id.tvUserName)
            val tvRole     : TextView  = v.findViewById(R.id.tvUserRoleBadge)
            val tvBranch   : TextView  = v.findViewById(R.id.tvUserBranch)
            val tvEmail    : TextView  = v.findViewById(R.id.tvUserEmail)
            val ivEdit     : ImageView = v.findViewById(R.id.ivEditUser)
            val ivDel      : ImageView = v.findViewById(R.id.ivDeleteUser)
            val card       : MaterialCardView = v.findViewById(R.id.cardUser)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_user_card, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val user = getItem(position)
            if (user.photoUrl.isNotBlank()) {
                h.ivAvatar.visibility  = View.VISIBLE
                h.tvInitials.visibility = View.GONE
                h.ivAvatar.load(user.photoUrl) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                    error(android.R.drawable.ic_menu_myplaces)
                }
            } else {
                h.ivAvatar.visibility  = View.GONE
                h.tvInitials.visibility = View.VISIBLE
            }
            h.tvInitials.text = user.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")
            h.tvName.text     = user.name
            h.tvEmail.text    = user.email
            h.tvBranch.text   = if (user.branchName.isNotBlank()) "📍 ${user.branchName}" else ""

            val roleColor = when (user.roleWarning) {
                "missing_role" -> "#dc2626"
                "missing_role_name" -> "#d97706"
                else -> "#0099b8"
            }
            h.tvRole.text = user.roleName
            h.tvRole.setTextColor(Color.parseColor(roleColor))
            h.card.strokeWidth = if (user.roleWarning.isBlank()) 0 else (2 * h.itemView.resources.displayMetrics.density).toInt()
            h.card.strokeColor = Color.parseColor(roleColor)
            try { h.tvInitials.setTextColor(Color.parseColor(roleColor)) } catch (_: Exception) {}

            val canAct = canManage(user.roleId)
            h.ivEdit.visibility = if (canAct) View.VISIBLE else View.GONE
            h.ivDel.visibility  = if (canAct) View.VISIBLE else View.GONE

            h.ivEdit.setOnClickListener { onEdit(user) }
            h.ivDel.setOnClickListener  { onDelete(user) }
        }

        class Diff : DiffUtil.ItemCallback<UserEntry>() {
            override fun areItemsTheSame(o: UserEntry, n: UserEntry) = o.uid == n.uid
            override fun areContentsTheSame(o: UserEntry, n: UserEntry) = o == n
        }
    }
}
