package com.cloudx.databridge

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class BranchDetailFragment : Fragment() {

    companion object {
        private const val ARG_ID = "branch_id"
        fun newInstance(branchId: String) = BranchDetailFragment().apply {
            arguments = Bundle().also { it.putString(ARG_ID, branchId) }
        }
    }

    private val db = FirebaseDatabase.getInstance()

    private var allEmployees = listOf<EmployeeFragment.UserEntry>()
    private var filterRole  = ""
    private var searchQuery = ""
    private var roleMap     = mapOf<String, String>()

    private lateinit var tvTitle: TextView
    private lateinit var ivEditBranch: ImageView
    private lateinit var tvBranchCode: TextView
    private lateinit var tvBranchType: TextView
    private lateinit var tvBranchAddress: TextView
    private lateinit var tvBranchManager: TextView
    private lateinit var tvBranchEmail: TextView
    private lateinit var tvBranchPhone: TextView
    private lateinit var tvBranchStatus: TextView
    private lateinit var tvBranchParent: TextView
    private lateinit var ivBranchImage: ImageView
    private lateinit var tvTeamCount: TextView
    private lateinit var etSearch: EditText
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var rvEmployees: RecyclerView
    private lateinit var chipContainer: LinearLayout

    private lateinit var employeeAdapter: EmployeeAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_branch_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvTitle         = view.findViewById(R.id.tvBranchDetailTitle)
        ivEditBranch    = view.findViewById(R.id.ivEditBranchDetail)
        tvBranchCode    = view.findViewById(R.id.tvDetailBranchCode)
        tvBranchType    = view.findViewById(R.id.tvDetailBranchType)
        tvBranchAddress = view.findViewById(R.id.tvDetailBranchAddress)
        tvBranchManager = view.findViewById(R.id.tvDetailBranchManager)
        tvBranchEmail   = view.findViewById(R.id.tvDetailBranchEmail)
        tvBranchPhone   = view.findViewById(R.id.tvDetailBranchPhone)
        tvBranchStatus  = view.findViewById(R.id.tvDetailBranchStatus)
        tvBranchParent  = view.findViewById(R.id.tvDetailBranchParent)
        ivBranchImage   = view.findViewById(R.id.ivBranchDetailImage)
        tvTeamCount     = view.findViewById(R.id.tvTeamCount)
        etSearch        = view.findViewById(R.id.etEmployeeSearch)
        pbLoading       = view.findViewById(R.id.pbEmployeesLoading)
        tvEmpty         = view.findViewById(R.id.tvEmployeesEmpty)
        rvEmployees     = view.findViewById(R.id.rvBranchEmployees)
        chipContainer   = view.findViewById(R.id.llDetailRoleChips)

        view.findViewById<TextView>(R.id.tvBackDetail).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val canManageBranch = EmployeeFragment.canManageBranches(RbacManager.current.roleId)
        ivEditBranch.visibility = if (canManageBranch) View.VISIBLE else View.GONE
        ivEditBranch.setOnClickListener {
            val branchId = arguments?.getString(ARG_ID) ?: return@setOnClickListener
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, BranchEditFragment.newInstance(branchId))
                .addToBackStack(null)
                .commit()
        }

        setupRecyclerView()
        setupChips()
        setupSearch()

        lifecycleScope.launch { loadData() }
    }

    private fun setupRecyclerView() {
        employeeAdapter = EmployeeAdapter(
            onEdit = { user -> openEditEmployee(user) },
            canManage = { roleId ->
                val myRole  = RbacManager.current.roleId
                if (myRole == "admin") true
                else {
                    val myLevel = EmployeeFragment.ROLE_LEVELS[myRole] ?: 99
                    if (myLevel > 3) false  // worker(4) / guest(5) read-only
                    else (EmployeeFragment.ROLE_LEVELS[roleId] ?: 99) > myLevel
                }
            }
        )
        rvEmployees.layoutManager = LinearLayoutManager(requireContext())
        rvEmployees.adapter = employeeAdapter
    }

    private fun setupChips() {
        chipContainer.removeAllViews()
    }

    private fun createChip(roleId: String, label: String, count: Int): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = if (roleId.isEmpty()) "All ($count)" else "$label ($count)"
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.theme_text_secondary))
            setPadding(12, 8, 12, 8)
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_role_badge_themed)
            tag = roleId
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 12, 0)
            layoutParams = lp
            setOnClickListener {
                filterRole = roleId
                updateChipStyles()
                renderEmployees()
            }
        }
    }

    private fun setupDynamicChips(availableRoles: Set<String>) {
        if (filterRole.isNotEmpty() && !availableRoles.contains(filterRole)) filterRole = ""
        val countByRole = allEmployees.groupingBy { it.roleId }.eachCount().toMutableMap()
        val total = allEmployees.size
        chipContainer.removeAllViews()

        val chipAll = createChip("", "All", total)
        chipContainer.addView(chipAll)

        availableRoles.forEach { rid ->
            val label = roleMap[rid].takeUnless { it.isNullOrBlank() } ?: rid
            val chip = createChip(rid, label, countByRole[rid] ?: 0)
            chipContainer.addView(chip)
        }
        updateChipStyles()
    }

    private fun updateChipStyles() {
        for (i in 0 until chipContainer.childCount) {
            val chip = chipContainer.getChildAt(i) as? TextView ?: continue
            val roleKey = chip.tag as? String ?: if (i == 0) "" else null
            val active = roleKey == filterRole
            chip.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (active) R.color.theme_text_accent else R.color.theme_text_secondary
                )
            )
            chip.alpha = if (active) 1f else 0.6f
        }
    }

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty().trim()
                renderEmployees()
            }
        })
    }

    private suspend fun loadData() {
        val branchId = arguments?.getString(ARG_ID) ?: return
        try {
            pbLoading.visibility = View.VISIBLE
            rvEmployees.visibility = View.GONE
            tvEmpty.visibility = View.GONE

            val branchSnap = db.reference.child("branches/$branchId").get().await()
            val rolesSnap  = db.reference.child("roles").get().await()
            val name        = branchSnap.child("name").getValue(String::class.java) ?: "Branch"
            val code        = branchSnap.child("branch_code").getValue(String::class.java) ?: ""
            val type        = branchSnap.child("branch_type").getValue(String::class.java) ?: ""
            val address     = branchSnap.child("address").getValue(String::class.java) ?: ""
            val managerName = branchSnap.child("manager_name").getValue(String::class.java) ?: "—"
            val email       = branchSnap.child("email").getValue(String::class.java) ?: ""
            val phone       = branchSnap.child("phone").getValue(String::class.java) ?: ""
            val status      = branchSnap.child("status").getValue(String::class.java) ?: "active"
            val parentId    = branchSnap.child("parent_branch_id").getValue(String::class.java) ?: ""

            val parentName = if (parentId.isNotBlank()) {
                runCatching {
                    db.reference.child("branches/$parentId/name").get().await()
                        .getValue(String::class.java) ?: parentId
                }.getOrDefault(parentId)
            } else ""

            if (!isAdded) return

            tvTitle.text         = name
            tvBranchCode.text    = if (code.isNotBlank()) "Code: $code" else ""
            tvBranchType.text    = if (type.isNotBlank()) type.uppercase() else ""
            tvBranchAddress.text = if (address.isNotBlank()) "📍 $address" else ""
            tvBranchManager.text = "👤 $managerName"
            tvBranchEmail.text   = if (email.isNotBlank()) "✉  $email" else ""
            tvBranchPhone.text   = if (phone.isNotBlank()) "📞 $phone" else ""
            tvBranchStatus.text  = if (status == "active") "● Active" else "● Inactive"
            tvBranchStatus.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (status == "active") R.color.theme_green else R.color.theme_red
                )
            )

            val imageUrl = branchSnap.child("image_url").getValue(String::class.java).orEmpty()
            if (imageUrl.isNotBlank()) {
                ivBranchImage.load(imageUrl) {
                    crossfade(true)
                }
            }

            if (parentName.isNotBlank()) {
                tvBranchParent.text = "Parent Branch: $parentName"
                tvBranchParent.visibility = View.VISIBLE
            } else {
                tvBranchParent.visibility = View.GONE
            }

            roleMap = rolesSnap.children.mapNotNull { c ->
                c.key?.let { it to c.child("name").getValue(String::class.java).orEmpty().trim() }
            }.toMap()

            // Fetch branch employee index → set of user_ids
            val empIndexSnap = db.reference.child("branches/$branchId/employees").get().await()
            if (!isAdded) return
            val branchUserIds = empIndexSnap.children.mapNotNull { it.key }.toSet()

            // Fetch all user profiles and keep only those in the branch index
            val usersSnap = db.reference.child("users").get().await()
            if (!isAdded) return

            allEmployees = usersSnap.children.mapNotNull { child ->
                val uid = child.key ?: return@mapNotNull null
                if (!branchUserIds.contains(uid)) return@mapNotNull null
                val roleId  = child.child("profile/company_info/role_id").getValue(String::class.java).orEmpty()
                val empName = child.child("profile/name").getValue(String::class.java)
                    ?: child.child("name").getValue(String::class.java) ?: "Unknown"
                val empEmail = child.child("profile/email").getValue(String::class.java).orEmpty()
                val empId    = empIndexSnap.child(uid).child("employee_id").getValue(String::class.java)
                    ?: child.child("profile/company_info/employee_id").getValue(String::class.java).orEmpty()
                val desig    = child.child("profile/company_info/designation").getValue(String::class.java).orEmpty()
                val status   = child.child("profile/company_info/status").getValue(String::class.java).orEmpty()
                val photoUrl = child.child("profile/photo_url").getValue(String::class.java).orEmpty()
                val roleName = roleMap[roleId].takeUnless { it.isNullOrBlank() } ?: roleId
                EmployeeFragment.UserEntry(
                    uid         = uid,
                    employeeId  = empId,
                    name        = empName,
                    email       = empEmail,
                    roleId      = roleId,
                    status      = status,
                    roleName    = roleName,
                    branchIds   = listOf(branchId),
                    branchName  = name,
                    designation = desig,
                    photoUrl    = photoUrl
                )
            }.sortedWith(compareBy({ EmployeeFragment.ROLE_LEVELS[it.roleId] ?: 99 }, { it.name }))

            pbLoading.visibility = View.GONE
            val availableRoles = allEmployees.map { it.roleId }.filter { it.isNotBlank() }.toSet()
            setupDynamicChips(availableRoles)
            renderEmployees()
        } catch (e: Exception) {
            if (!isAdded) return
            pbLoading.visibility = View.GONE
            Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderEmployees() {
        val q = searchQuery.lowercase()
        val filtered = allEmployees.filter { user ->
            (filterRole.isEmpty() || user.roleId == filterRole) &&
            (q.isEmpty()
                || user.name.contains(q, ignoreCase = true)
                || user.email.contains(q, ignoreCase = true)
                || user.employeeId.contains(q, ignoreCase = true))
        }
        tvTeamCount.text       = "Team Members (${filtered.size})"
        employeeAdapter.submitList(filtered)
        rvEmployees.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        tvEmpty.visibility     = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text           = if (filtered.isEmpty()) "No matching team members." else ""
    }

    private fun openEditEmployee(user: EmployeeFragment.UserEntry) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, EmployeeEditFragment.newInstance(user.uid))
            .addToBackStack(null)
            .commit()
    }

    // ── Adapter ──────────────────────────────────────────────────────────

    class EmployeeAdapter(
        private val onEdit: (EmployeeFragment.UserEntry) -> Unit,
        private val canManage: (String) -> Boolean
    ) : ListAdapter<EmployeeFragment.UserEntry, EmployeeAdapter.VH>(Diff()) {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivAvatar  : ImageView = v.findViewById(R.id.ivUserAvatar)
            val tvInitials: TextView  = v.findViewById(R.id.tvUserInitials)
            val tvName    : TextView  = v.findViewById(R.id.tvUserName)
            val tvRole    : TextView  = v.findViewById(R.id.tvUserRoleBadge)
            val tvSub     : TextView  = v.findViewById(R.id.tvUserBranch)
            val tvEmail   : TextView  = v.findViewById(R.id.tvUserEmail)
            val ivEdit    : ImageView = v.findViewById(R.id.ivEditUser)
            val ivDel     : ImageView = v.findViewById(R.id.ivDeleteUser)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_user_card, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val user = getItem(position)

            if (user.photoUrl.isNotBlank()) {
                h.ivAvatar.visibility   = View.VISIBLE
                h.tvInitials.visibility = View.GONE
                h.ivAvatar.load(user.photoUrl) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                    error(android.R.drawable.ic_menu_myplaces)
                }
            } else {
                h.ivAvatar.visibility   = View.GONE
                h.tvInitials.visibility = View.VISIBLE
            }
            h.tvInitials.text = user.name.split(" ")
                .mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")
            h.tvName.text  = user.name
            h.tvSub.text   = if (user.designation.isNotBlank()) user.designation else ""
            h.tvEmail.text = user.email

            val roleLabel = user.roleName.ifBlank { user.roleId.ifBlank { "No Role" } }
            h.tvRole.text = roleLabel
            h.tvRole.setTextColor(ContextCompat.getColor(h.itemView.context, R.color.theme_text_secondary))

            val canAct = canManage(user.roleId)
            h.ivEdit.visibility = if (canAct) View.VISIBLE else View.GONE
            h.ivDel.visibility  = View.GONE
            if (canAct) h.ivEdit.setOnClickListener { onEdit(user) }
        }

        class Diff : DiffUtil.ItemCallback<EmployeeFragment.UserEntry>() {
            override fun areItemsTheSame(o: EmployeeFragment.UserEntry, n: EmployeeFragment.UserEntry) = o.uid == n.uid
            override fun areContentsTheSame(o: EmployeeFragment.UserEntry, n: EmployeeFragment.UserEntry) = o == n
        }
    }
}
