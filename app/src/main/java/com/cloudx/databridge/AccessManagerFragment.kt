package com.cloudx.databridge

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.PopupMenu
import android.text.Editable
import android.text.TextWatcher
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ktx.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

class AccessManagerFragment : Fragment() {

    private lateinit var spinner: Spinner
    private lateinit var btnCreate: View
    private lateinit var btnRename: View
    private lateinit var btnDelete: View
    private lateinit var btnSave: Button
    private lateinit var tvRole: TextView
    private lateinit var progress: ProgressBar
    private lateinit var rv: RecyclerView
    private lateinit var rvUsers: RecyclerView
    private lateinit var tabRoles: TextView
    private lateinit var tabUsers: TextView
    private lateinit var indicatorRoles: View
    private lateinit var indicatorUsers: View
    private lateinit var cardRoleControls: View
    private lateinit var cardPermissions: View
    private lateinit var cardUserFilter: View
    private lateinit var spinnerFilterRole: Spinner
    private lateinit var etFilterUser: android.widget.EditText
    private lateinit var spinnerCopyRole: Spinner
    private lateinit var btnCopyPermissions: MaterialButton
    private lateinit var btnRoleMenu: View
    private lateinit var cbSelectAllPerms: CheckBox

    private val allToggleListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        setAllPermissions(isChecked)
    }

    private val adapter = PermissionsAdapter { _, _ -> updatePermCountUI() }
    private val usersAdapter = UsersAdapter()
    private val db = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private var roles: MutableMap<String, RoleEntry> = mutableMapOf()
    private var roleIds: List<String> = emptyList()
    private var currentRoleId: String? = null
    private var roleUserCounts: Map<String, Int> = emptyMap()

    data class RoleEntry(
        var name: String = "",
        var permissions: Map<String, Boolean> = emptyMap()
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_access_manager, container, false)
    }

    private fun refreshCopyRoleOptions() {
        val current = currentRoleId
        val options = listOf("") + roleIds.filter { it != current }
        val prevSelection = spinnerCopyRole.selectedItem?.toString()
        val adapterCopy = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, options)
        spinnerCopyRole.adapter = adapterCopy
        val restoreIndex = adapterCopy.getPosition(prevSelection).takeIf { it >= 0 } ?: 0
        spinnerCopyRole.setSelection(restoreIndex)
    }

    private fun copyPermissionsFromSelected() {
        val targetRole = currentRoleId ?: run {
            Toast.makeText(requireContext(), "No role selected", Toast.LENGTH_SHORT).show(); return
        }
        val sourceRole = spinnerCopyRole.selectedItem?.toString().orEmpty()
        if (sourceRole.isBlank()) {
            Toast.makeText(requireContext(), "Select a role to copy from", Toast.LENGTH_SHORT).show(); return
        }
        if (sourceRole == targetRole) {
            Toast.makeText(requireContext(), "Choose a different role to copy", Toast.LENGTH_SHORT).show(); return
        }
        val sourcePerms = roles[sourceRole]?.permissions ?: PermissionCatalog.defaultPermissions()
        val entry = roles[targetRole] ?: return
        entry.permissions = sourcePerms.toMap()
        bindPermissions()
        Toast.makeText(requireContext(), "Copied from $sourceRole", Toast.LENGTH_SHORT).show()
        spinnerCopyRole.setSelection(0)
        updateCopyButtonVisibility()
    }

    private fun updateCopyButtonVisibility() {
        val sel = spinnerCopyRole.selectedItem?.toString().orEmpty()
        btnCopyPermissions.visibility = if (sel.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun setAllPermissions(value: Boolean) {
        val current = adapter.currentState().toMutableMap()
        PermissionCatalog.all.forEach { perm -> current[perm.key] = value }
        adapter.submit(PermissionCatalog.all, current)
        updatePermCountUI()
    }

    private fun showRoleMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 2, 0, "Rename role")
        popup.menu.add(0, 3, 1, "Delete role")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                2 -> btnRename.performClick()
                3 -> btnDelete.performClick()
            }
            true
        }
        popup.show()
    }

    private fun openUsersAccessDialog() {
        val ctx = requireContext()
        val isNight = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val colorPrimary = if (isNight) android.graphics.Color.parseColor("#ffffff") else android.graphics.Color.parseColor("#111111")
        val colorSecondary = android.graphics.Color.parseColor("#888888")
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 0) }
        val pb = ProgressBar(ctx)
        container.addView(pb)
        val dialog = AlertDialog.Builder(ctx).setTitle("Users Access").setView(container).setNegativeButton("Close", null).create()
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val usersSnap = db.reference.child("users").get().await()
                container.removeView(pb)
                usersSnap.children.forEach { u ->
                    val uid  = u.key ?: return@forEach
                    val name = u.child("profile/name").getValue(String::class.java)
                        ?: u.child("name").getValue(String::class.java) ?: uid
                    val role = u.child("profile/company_info/role_id").getValue(String::class.java).orEmpty()

                    val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 8) }
                    val tvName = TextView(ctx).apply { text = name; textSize = 14f; setTextColor(colorPrimary) }
                    val tvRole = TextView(ctx).apply { text = "Role: ${role.ifBlank { "(none)" }}"; textSize = 12f; setTextColor(colorSecondary) }
                    val btnOverride = Button(ctx).apply { text = "Override" }
                    btnOverride.setOnClickListener { openUserOverrideDialog(uid, name, role) }
                    row.addView(tvName); row.addView(tvRole); row.addView(btnOverride)
                    container.addView(row)
                }
            } catch (e: Exception) {
                container.removeView(pb)
                container.addView(TextView(ctx).apply { text = "Failed: ${e.message}" })
            }
        }
    }

    private fun openUserOverrideDialog(uid: String, name: String, roleId: String) {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 0) }
        val tvHeader = TextView(ctx).apply { text = name; textSize = 16f; setTextColor(android.graphics.Color.parseColor("#00d4ff")); setPadding(0,0,0,12) }
        layout.addView(tvHeader)

        val checks = mutableMapOf<String, CheckBox>()
        PermissionCatalog.all.forEach { perm ->
            val cb = CheckBox(ctx).apply { text = "${perm.title} (${perm.key})" }
            checks[perm.key] = cb
            layout.addView(cb)
        }

        // Load existing override or role defaults
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val overrideSnap = db.reference.child("users/$uid/profile/company_info/access_overrides/permissions").get().await()
                val overrideMap: Map<String, Boolean>? = overrideSnap.getValue(object : com.google.firebase.database.GenericTypeIndicator<Map<String, Boolean>>() {})
                val base = PermissionCatalog.defaultPermissions().toMutableMap()
                if (overrideMap != null) {
                    overrideMap.forEach { (k, v) -> base[k] = v }
                } else {
                    val rolePermSnap = db.reference.child("roles/$roleId/permissions").get().await()
                    val roleMap: Map<String, Boolean>? = rolePermSnap.getValue(object : com.google.firebase.database.GenericTypeIndicator<Map<String, Boolean>>() {})
                    roleMap?.forEach { (k, v) -> base[k] = v }
                }
                checks.forEach { (k, cb) -> cb.isChecked = base[k] == true }
            } catch (_: Exception) {}
        }

        AlertDialog.Builder(ctx)
            .setTitle("Override Access")
            .setView(layout)
            .setPositiveButton("Save") { d, _ ->
                val map = mutableMapOf<String, Boolean>()
                checks.forEach { (k, cb) -> map[k] = cb.isChecked }
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val roleBase = getPermStateForRole(roleId)
                        val isSameAsRole = roleBase.size == map.size && roleBase.all { (k, v) -> map[k] == v }
                        val ref = db.reference.child("users/$uid/profile/company_info/access_overrides/permissions")
                        if (isSameAsRole) {
                            ref.removeValue().await()
                            Toast.makeText(ctx, "Saved (override cleared)", Toast.LENGTH_SHORT).show()
                        } else {
                            ref.setValue(map).await()
                            Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                        }
                        loadUsersForTab()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                d.dismiss()
            }
            .setNeutralButton("Reset") { d, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        db.reference.child("users/$uid/profile/company_info/access_overrides/permissions").removeValue().await()
                        Toast.makeText(ctx, "Reset to role defaults", Toast.LENGTH_SHORT).show()
                        loadUsersForTab()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Admin-only guard
        val role = RbacManager.current.roleId
        if (role != "admin") {
            Toast.makeText(requireContext(), "Admin only", Toast.LENGTH_LONG).show()
            requireActivity().onBackPressedDispatcher.onBackPressed()
            return
        }

        spinner = view.findViewById(R.id.spinnerRoles)
        btnCreate = view.findViewById(R.id.btnCreateRole)
        btnRename = view.findViewById(R.id.btnRenameRole)
        btnDelete = view.findViewById(R.id.btnDeleteRole)
        btnSave = view.findViewById(R.id.btnSavePermissions)
        tvRole = view.findViewById(R.id.tvRoleName)
        progress = view.findViewById(R.id.progressAccess)
        rv = view.findViewById(R.id.rvPermissions)
        rvUsers = view.findViewById(R.id.rvUsersAccessList)
        tabRoles = view.findViewById(R.id.tabRoles)
        tabUsers = view.findViewById(R.id.tabUsers)
        indicatorRoles = view.findViewById(R.id.indicatorRoles)
        indicatorUsers = view.findViewById(R.id.indicatorUsers)
        cardRoleControls = view.findViewById(R.id.cardRoleControls)
        cardPermissions = view.findViewById(R.id.cardPermissions)
        cardUserFilter = view.findViewById(R.id.cardUserFilter)
        spinnerFilterRole = view.findViewById(R.id.spinnerFilterRole)
        etFilterUser = view.findViewById(R.id.etFilterUser)
        spinnerCopyRole = view.findViewById(R.id.spinnerCopyRole)
        btnCopyPermissions = view.findViewById(R.id.btnCopyPermissions)
        btnRoleMenu = view.findViewById(R.id.btnRoleMenu)
        cbSelectAllPerms = view.findViewById(R.id.cbSelectAllPerms)

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        rvUsers.layoutManager = LinearLayoutManager(requireContext())
        rvUsers.adapter = usersAdapter

        btnCreate.setOnClickListener { promptCreate() }
        btnRename.setOnClickListener { promptRename() }
        btnDelete.setOnClickListener { confirmDelete() }
        btnSave.setOnClickListener { savePermissions() }
        btnCopyPermissions.setOnClickListener { copyPermissionsFromSelected() }
        cbSelectAllPerms.setOnCheckedChangeListener(allToggleListener)
        btnRoleMenu.setOnClickListener { showRoleMenu(it) }
        spinnerCopyRole.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateCopyButtonVisibility()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                updateCopyButtonVisibility()
            }
        }
        tabRoles.setOnClickListener { showRolesTab() }
        tabUsers.setOnClickListener { showUsersTab() }

        loadRoles()
        loadUsersForTab()
        initUserFilter()
        refreshCopyRoleOptions()
        updateCopyButtonVisibility()
        showRolesTab()
    }

    private fun loadRoles() {
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val snap = db.reference.child("roles").get().await()
                roles.clear()
                snap.children.forEach { child ->
                    val id = child.key ?: return@forEach
                    val name = child.child("name").getValue(String::class.java).orEmpty()
                    val perms = child.child("permissions").getValue<Map<String, Boolean>>() ?: emptyMap()
                    roles[id] = RoleEntry(name = name, permissions = perms)
                }
                bindRoles()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to load roles: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun bindRoles(selectId: String? = null) {
        roleIds = roles.keys.sorted()
        val items = roleIds.map { id -> "$id (${roles[id]?.name?.ifBlank { "Unnamed" } ?: "Unnamed"})" }
        val adapterSpin = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, items)
        adapterSpin.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapterSpin
        val targetId = selectId ?: roleIds.firstOrNull()
        currentRoleId = targetId
        val index = roleIds.indexOf(targetId)
        if (index >= 0) spinner.setSelection(index)

        spinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val roleId = roleIds.getOrNull(position)
                currentRoleId = roleId
                bindPermissions()
                refreshCopyRoleOptions()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        bindPermissions()
        // Update Users filter role list if visible
        refreshRoleFilterOptions()
        refreshCopyRoleOptions()
    }

    private fun bindPermissions() {
        val roleId = currentRoleId ?: return
        val entry = roles[roleId] ?: return
        val state = PermissionCatalog.defaultPermissions().toMutableMap()
        entry.permissions.forEach { (k, v) -> state[k] = v }
        adapter.submit(PermissionCatalog.all, state)
        updatePermCountUI()
    }

    private fun updatePermCountUI() {
        val roleId = currentRoleId ?: return
        if (roles[roleId] == null) return
        val countUsers = roleUserCounts[roleId] ?: 0
        val state = adapter.currentState()
        val allowedCount = state.values.count { it }
        val totalPerms = PermissionCatalog.all.size
        tvRole.text = "Role: $roleId · ${countUsers} users · $allowedCount/$totalPerms perms"
        val shouldAllBeChecked = totalPerms > 0 && allowedCount == totalPerms
        cbSelectAllPerms.setOnCheckedChangeListener(null)
        cbSelectAllPerms.isChecked = shouldAllBeChecked
        cbSelectAllPerms.setOnCheckedChangeListener(allToggleListener)
    }

    private fun loadUsersForTab() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val usersSnap = db.reference.child("users").get().await()
                val list = mutableListOf<UserRow>()
                val counts = mutableMapOf<String, Int>()
                usersSnap.children.forEach { u ->
                    val uid  = u.key ?: return@forEach
                    val name = u.child("profile/name").getValue(String::class.java)
                        ?: u.child("name").getValue(String::class.java) ?: uid
                    val role = u.child("profile/company_info/role_id").getValue(String::class.java).orEmpty()
                    val overrideNode = u.child("profile/company_info/access_overrides/permissions")
                    val hasOverride = overrideNode.exists()
                    list.add(UserRow(uid, name, role, hasOverride))
                    if (role.isNotBlank()) counts[role] = (counts[role] ?: 0) + 1
                }
                fullUserList = list.sortedBy { it.name.lowercase(Locale.getDefault()) }
                applyUserFilter()
                roleUserCounts = counts
                // Update header text count for current role if visible
                if (rv.visibility == View.VISIBLE) bindPermissions()
                refreshRoleFilterOptions()
            } catch (_: Exception) {}
        }
    }

    private fun showRolesTab() {
        // Show roles UI
        cardRoleControls.visibility = View.VISIBLE
        cardPermissions.visibility = View.VISIBLE
        cardUserFilter.visibility = View.GONE
        view?.findViewById<View>(R.id.spinnerRoles)?.visibility = View.VISIBLE
        view?.findViewById<View>(R.id.btnCreateRole)?.visibility = View.VISIBLE
        view?.findViewById<View>(R.id.btnRenameRole)?.visibility = View.GONE
        view?.findViewById<View>(R.id.btnDeleteRole)?.visibility = View.GONE
        btnRoleMenu.visibility = View.VISIBLE
        tvRole.visibility = View.VISIBLE
        rv.visibility = View.VISIBLE
        btnSave.visibility = View.VISIBLE
        // Hide users list
        rvUsers.visibility = View.GONE
        // Tab colors
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val colorActive = if (isNight) android.graphics.Color.parseColor("#ffffff") else android.graphics.Color.parseColor("#111111")
        val colorInactive = android.graphics.Color.parseColor("#888888")
        tabRoles.setTextColor(colorActive)
        tabUsers.setTextColor(colorInactive)
        indicatorRoles.visibility = View.VISIBLE
        indicatorUsers.visibility = View.GONE
    }

    private fun showUsersTab() {
        // Hide roles UI
        cardRoleControls.visibility = View.GONE
        cardPermissions.visibility = View.GONE
        cardUserFilter.visibility = View.VISIBLE
        view?.findViewById<View>(R.id.spinnerRoles)?.visibility = View.GONE
        view?.findViewById<View>(R.id.btnCreateRole)?.visibility = View.GONE
        view?.findViewById<View>(R.id.btnRenameRole)?.visibility = View.GONE
        view?.findViewById<View>(R.id.btnDeleteRole)?.visibility = View.GONE
        btnRoleMenu.visibility = View.GONE
        tvRole.visibility = View.GONE
        rv.visibility = View.GONE
        btnSave.visibility = View.GONE
        // Show users list
        rvUsers.visibility = View.VISIBLE
        loadUsersForTab()
        // Tab colors
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val colorActive = if (isNight) android.graphics.Color.parseColor("#ffffff") else android.graphics.Color.parseColor("#111111")
        val colorInactive = android.graphics.Color.parseColor("#888888")
        tabRoles.setTextColor(colorInactive)
        tabUsers.setTextColor(colorActive)
        indicatorRoles.visibility = View.GONE
        indicatorUsers.visibility = View.VISIBLE
    }

    // region Users filter
    private var fullUserList: List<UserRow> = emptyList()
    private fun initUserFilter() {
        spinnerFilterRole.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf("All") + roleIds)
        spinnerFilterRole.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyUserFilter()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        etFilterUser.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { applyUserFilter() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun refreshRoleFilterOptions() {
        (spinnerFilterRole.adapter as? ArrayAdapter<String>)?.let { adapter ->
            val currentSel = spinnerFilterRole.selectedItem?.toString()
            adapter.clear()
            adapter.addAll(listOf("All") + roleIds)
            adapter.notifyDataSetChanged()
            val idx = adapter.getPosition(currentSel)
            if (idx >= 0) spinnerFilterRole.setSelection(idx)
        } ?: run {
            spinnerFilterRole.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf("All") + roleIds)
        }
    }

    private fun applyUserFilter() {
        val roleFilter = spinnerFilterRole.selectedItem?.toString().orEmpty()
        val query = etFilterUser.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        var filtered = fullUserList
        if (roleFilter.isNotBlank() && roleFilter != "All") {
            filtered = filtered.filter { it.roleId == roleFilter }
        }
        if (query.isNotBlank()) {
            filtered = filtered.filter { it.name.lowercase(Locale.getDefault()).contains(query) }
        }
        usersAdapter.submit(filtered)
    }
    // endregion

    data class UserRow(val uid: String, val name: String, val roleId: String, val hasOverride: Boolean)

    inner class UsersAdapter : RecyclerView.Adapter<UsersAdapter.Holder>() {
        private val items = mutableListOf<UserRow>()
        fun submit(list: List<UserRow>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvUserName)
            val tvBadge: TextView = view.findViewById(R.id.tvCustomBadge)
            val spinner: Spinner = view.findViewById(R.id.spinnerUserRole)
            val btnOverride: Button = view.findViewById(R.id.btnOverrideUser)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_user_access_row, parent, false)
            return Holder(v)
        }
        override fun onBindViewHolder(h: Holder, pos: Int) {
            val row = items[pos]
            val isNight = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val colorPrimary = if (isNight) android.graphics.Color.parseColor("#ffffff") else android.graphics.Color.parseColor("#111111")
            h.tvName.text = row.name
            h.tvName.setTextColor(colorPrimary)
            h.tvBadge.visibility = if (row.hasOverride) View.VISIBLE else View.GONE

            // Setup spinner with roleIds
            val rolesArr = roleIds
            val adapterSpin = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, rolesArr)
            h.spinner.adapter = adapterSpin
            val sel = rolesArr.indexOf(row.roleId).let { if (it >= 0) it else 0 }
            var initBind = true
            h.spinner.setSelection(sel, false)
            h.spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (initBind) { initBind = false; return }
                    val rid = rolesArr.getOrNull(position) ?: return
                    if (rid == row.roleId) return
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            db.reference.child("users/${row.uid}/profile/company_info/role_id").setValue(rid).await()
                            Toast.makeText(requireContext(), "Role updated", Toast.LENGTH_SHORT).show()
                            // Refresh list and counts
                            loadUsersForTab()
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }

            h.btnOverride.setOnClickListener { openUserOverrideDialog(row.uid, row.name, row.roleId) }
        }
        override fun getItemCount(): Int = items.size
    }

    private fun promptCreate() {
        val inputName = android.widget.EditText(requireContext())
        val inputId = android.widget.EditText(requireContext())
        inputName.hint = "Role Name"
        inputId.hint = "slug"
        var autoSlug = true
        var suppressSlug = false
        inputName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (autoSlug) {
                    suppressSlug = true
                    inputId.setText(slugify(s?.toString().orEmpty()))
                    inputId.setSelection(inputId.text.length)
                    suppressSlug = false
                }
            }
        })
        inputId.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppressSlug) autoSlug = false
            }
        })

        val copyLabel = TextView(requireContext()).apply { text = "Copy permissions from" }
        val copySpinner = Spinner(requireContext())
        val copyOptions = listOf("None") + roleIds
        copySpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, copyOptions)

        val checks = mutableListOf<Pair<String, CheckBox>>()
        val permsLayout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        PermissionCatalog.all.forEach { perm ->
            val cb = CheckBox(requireContext()).apply {
                text = "${perm.title} (${perm.key})"
                isChecked = false
            }
            checks.add(perm.key to cb)
            permsLayout.addView(cb)
        }

        val updateChecks: (Map<String, Boolean>) -> Unit = { state ->
            checks.forEach { (key, cb) -> cb.isChecked = state[key] ?: false }
        }

        copySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val sel = copyOptions.getOrNull(position)
                val base = if (sel == null || sel == "None") PermissionCatalog.defaultPermissions() else getPermStateForRole(sel)
                updateChecks(base)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        updateChecks(PermissionCatalog.defaultPermissions())

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
            addView(inputName)
            addView(inputId)
            addView(copyLabel)
            addView(copySpinner)
            addView(permsLayout)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Create role")
            .setView(layout)
            .setPositiveButton("Create") { dialog, _ ->
                val rid = slugify(inputId.text.toString())
                val rname = inputName.text.toString().trim()
                if (rid.isBlank()) { Toast.makeText(requireContext(), "Role id required", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                if (rname.isBlank()) { Toast.makeText(requireContext(), "Role name required", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                if (roles.containsKey(rid)) { Toast.makeText(requireContext(), "Role exists", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val state = PermissionCatalog.defaultPermissions().toMutableMap()
                checks.forEach { (key, cb) -> state[key] = cb.isChecked }
                createRole(rid, rname, state)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun createRole(id: String, name: String, perms: Map<String, Boolean>) {
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val updates = mapOf(
                    "roles/$id/name" to name,
                    "roles/$id/permissions" to perms,
                )
                db.reference.updateChildren(updates).await()
                roles[id] = RoleEntry(name = name, permissions = perms)
                bindRoles(selectId = id)
                Toast.makeText(requireContext(), "Role created", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Create failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally { setLoading(false) }
        }
    }

    private fun promptRename() {
        val roleId = currentRoleId ?: run {
            Toast.makeText(requireContext(), "No role selected", Toast.LENGTH_SHORT).show(); return
        }
        val currentName = roles[roleId]?.name.orEmpty()
        val inputName = android.widget.EditText(requireContext()).apply {
            hint = "Role Name"
            setText(currentName)
        }
        val inputSlug = android.widget.EditText(requireContext()).apply {
            hint = "slug"
            setText(roleId)
        }
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
            addView(inputName)
            addView(inputSlug)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Rename $roleId")
            .setView(layout)
            .setPositiveButton("Save") { dialog, _ ->
                val newName = inputName.text.toString().trim()
                val newSlug = slugify(inputSlug.text.toString())
                if (newName.isBlank()) { Toast.makeText(requireContext(), "Name required", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                if (newSlug.isBlank()) { Toast.makeText(requireContext(), "Slug required", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                if (newSlug != roleId && roles.containsKey(newSlug)) {
                    Toast.makeText(requireContext(), "Role slug exists", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                renameRole(roleId, newSlug, newName)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun renameRole(roleId: String, newRoleId: String, name: String) {
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val oldRef = db.reference.child("roles/$roleId")
                val oldSnap = oldRef.get().await()
                val payload = snapshotMap(oldSnap).toMutableMap()
                payload["name"] = name
                db.reference.child("roles/$newRoleId").setValue(payload).await()
                if (newRoleId != roleId) {
                    migrateUsers(roleId, newRoleId)
                    oldRef.removeValue().await()
                }
                val perms = (payload["permissions"] as? Map<*, *>)?.mapNotNull { (k, v) ->
                    val key = k as? String ?: return@mapNotNull null
                    key to (v as? Boolean ?: false)
                }?.toMap() ?: roles[roleId]?.permissions.orEmpty()
                roles.remove(roleId)
                roles[newRoleId] = RoleEntry(name = name, permissions = perms)
                bindRoles(selectId = newRoleId)
                Toast.makeText(requireContext(), "Renamed", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Rename failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally { setLoading(false) }
        }
    }

    private fun confirmDelete() {
        val roleId = currentRoleId ?: run {
            Toast.makeText(requireContext(), "No role selected", Toast.LENGTH_SHORT).show(); return
        }
        if (roleId == "admin") {
            Toast.makeText(requireContext(), "Admin role cannot be deleted", Toast.LENGTH_LONG).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Count users currently assigned to this role
                val usersSnap = db.reference.child("users").get().await()
                var inUseCount = 0
                usersSnap.children.forEach { u ->
                    val r = u.child("profile/company_info/role_id").getValue(String::class.java)
                    if (r == roleId) inUseCount++
                }

                val migrateTargets = roleIds.filter { it != roleId }
                val hasTargets = migrateTargets.isNotEmpty()

                if (inUseCount > 0 && !hasTargets) {
                    Toast.makeText(requireContext(), "Users found on this role. Create another role to migrate them before deleting.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Build dialog UI
                val ctx = requireContext()
                val container = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 16, 32, 0)
                }
                if (inUseCount > 0) {
                    val tvInfo = TextView(ctx).apply {
                        text = "$inUseCount users currently have role '$roleId'. Select a role to migrate them to, then confirm delete."
                        textSize = 14f
                        setTextColor(android.graphics.Color.parseColor("#666666"))
                        setPadding(0, 0, 0, 12)
                    }
                    container.addView(tvInfo)
                    val tvLabel = TextView(ctx).apply {
                        text = "Migrate to"
                        textSize = 12f
                        setTextColor(android.graphics.Color.parseColor("#888888"))
                    }
                    container.addView(tvLabel)
                    val spinner = Spinner(ctx)
                    val adapterSpin = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, migrateTargets)
                    spinner.adapter = adapterSpin
                    container.addView(spinner)
                    // Dynamic summary showing how many users will be migrated (and to which role)
                    val tvMigrateSummary = TextView(ctx).apply {
                        text = "$inUseCount users will be migrated to '${migrateTargets.firstOrNull().orEmpty()}'"
                        textSize = 12f
                        setTextColor(android.graphics.Color.parseColor("#666666"))
                        setPadding(0, 8, 0, 0)
                    }
                    container.addView(tvMigrateSummary)
                    spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                            val targetName = migrateTargets.getOrNull(position) ?: ""
                            tvMigrateSummary.text = "$inUseCount users will be migrated to '$targetName'"
                        }
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    }

                    AlertDialog.Builder(ctx)
                        .setTitle("Delete $roleId?")
                        .setView(container)
                        .setPositiveButton("Delete") { dialog, _ ->
                            val target = migrateTargets.getOrNull(spinner.selectedItemPosition)
                            if (target.isNullOrBlank()) {
                                Toast.makeText(ctx, "Select a role to migrate users.", Toast.LENGTH_SHORT).show()
                            } else {
                                deleteRole(roleId, target)
                                dialog.dismiss()
                            }
                        }
                        .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                        .show()
                } else {
                    // No users on this role; allow direct delete (no migration needed)
                    AlertDialog.Builder(ctx)
                        .setTitle("Delete $roleId?")
                        .setMessage("No users currently have this role. This will remove the role permanently.")
                        .setPositiveButton("Delete") { dialog, _ ->
                            deleteRole(roleId, null)
                            dialog.dismiss()
                        }
                        .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteRole(roleId: String, migrateTo: String?) {
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!migrateTo.isNullOrBlank()) {
                    migrateUsers(roleId, migrateTo)
                }
                db.reference.child("roles/$roleId").removeValue().await()
                roles.remove(roleId)
                if (roles.isEmpty()) {
                    roleIds = emptyList()
                    currentRoleId = null
                    spinner.adapter = null
                    adapter.submit(PermissionCatalog.all, emptyMap())
                    tvRole.text = "Role:"
                } else {
                    bindRoles()
                }
                Toast.makeText(requireContext(), "Deleted", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally { setLoading(false) }
        }
    }

    private fun savePermissions() {
        val roleId = currentRoleId ?: run {
            Toast.makeText(requireContext(), "No role selected", Toast.LENGTH_SHORT).show(); return
        }
        val state = adapter.currentState()
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                db.reference.child("roles/$roleId/permissions").setValue(state).await()
                roles[roleId]?.permissions = state
                Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show()
                (activity as? AuthUiHost)?.refreshAuthUi(forceReload = true)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally { setLoading(false) }
        }
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        btnSave.isEnabled = !loading
        btnCreate.isEnabled = !loading
        btnRename.isEnabled = !loading
        btnDelete.isEnabled = !loading
    }

    private suspend fun migrateUsers(fromRole: String, toRole: String) {
        val usersSnap = db.reference.child("users").get().await()
        val updates = mutableMapOf<String, Any>()
        usersSnap.children.forEach { userSnap ->
            val uid = userSnap.key ?: return@forEach
            val currentRole = userSnap.child("profile/company_info/role_id").getValue(String::class.java)
            if (currentRole == fromRole) {
                updates["users/$uid/profile/company_info/role_id"] = toRole
            }
        }
        if (updates.isNotEmpty()) {
            db.reference.updateChildren(updates).await()
        }
    }

    private fun getPermStateForRole(roleId: String?): Map<String, Boolean> {
        val base = PermissionCatalog.defaultPermissions().toMutableMap()
        if (roleId != null) {
            roles[roleId]?.permissions?.forEach { (k, v) -> base[k] = v }
        }
        return base
    }

    private fun slugify(text: String): String = text.trim()
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

    private fun snapshotMap(snapshot: com.google.firebase.database.DataSnapshot): Map<String, Any?> {
        val raw = snapshot.value as? Map<*, *> ?: return emptyMap()
        return raw.mapNotNull { (key, value) ->
            val k = key as? String ?: return@mapNotNull null
            k to value
        }.toMap()
    }
}
