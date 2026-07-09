package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 🏢 Branch List Fragment
 * Admin can view, edit, delete all branches
 */
class BranchListFragment : Fragment() {

    private val db = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private lateinit var rvBranches: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var ivAddBranch: ImageView

    private var allBranches = listOf<BranchEntry>()
    private val managers = mutableListOf<Pair<String, String>>()

    private lateinit var adapter: BranchAdapter

    data class BranchEntry(
        val branchId: String,
        val branchCode: String,
        val name: String,
        val branchType: String,
        val address: String,
        val latitude: Double,
        val longitude: Double,
        val email: String,
        val phone: String,
        val managerUid: String,
        val managerName: String,
        val parentBranchId: String,
        val status: String,
        val imageUrl: String,
        val createdAt: Long
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_branch_list, container, false)

        rvBranches = view.findViewById(R.id.rvBranches)
        pbLoading = view.findViewById(R.id.pbBranchesLoading)
        tvEmpty = view.findViewById(R.id.tvBranchesEmpty)
        ivAddBranch = view.findViewById(R.id.ivAddBranch)

        setupRecyclerView()
        setupAddButton()
        loadData()

        return view
    }

    private fun setupRecyclerView() {
        val canManage = EmployeeFragment.canManageBranches(RbacManager.current.roleId)
        adapter = BranchAdapter(
            onView    = { branch -> openDetailPage(branch) },
            onEdit    = { branch -> openEditDialog(branch) },
            onDelete  = { branch -> confirmDelete(branch) },
            canManage = canManage
        )
        rvBranches.layoutManager = LinearLayoutManager(requireContext())
        rvBranches.adapter = adapter
    }

    private fun openDetailPage(branch: BranchEntry) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, BranchDetailFragment.newInstance(branch.branchId))
            .addToBackStack(null)
            .commit()
    }

    private fun setupAddButton() {
        val canAdd = EmployeeFragment.canManageBranches(RbacManager.current.roleId)
        ivAddBranch.visibility = if (canAdd) View.VISIBLE else View.GONE
        ivAddBranch.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, BranchCreateFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    private fun loadData() {
        pbLoading.visibility = View.VISIBLE
        rvBranches.visibility = View.GONE
        tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            try {
                // Load branches — manager_name is already stored on the branch node,
                // so no full users/ scan needed. For the rare case it's missing,
                // fetch only that specific user's name.
                val branchesSnap = db.reference.child("branches").get().await()
                val missingManagerUids = mutableSetOf<String>()
                val rawBranches = branchesSnap.children.mapNotNull { child ->
                    val branchId   = child.key ?: return@mapNotNull null
                    val managerUid = child.child("manager_uid").getValue(String::class.java) ?: ""
                    val cachedName = child.child("manager_name").getValue(String::class.java)
                    if (cachedName.isNullOrBlank() && managerUid.isNotBlank()) missingManagerUids.add(managerUid)
                    BranchEntry(
                        branchId      = branchId,
                        branchCode    = child.child("branch_code").getValue(String::class.java) ?: "",
                        name          = child.child("name").getValue(String::class.java) ?: branchId,
                        branchType    = child.child("branch_type").getValue(String::class.java) ?: "",
                        address       = child.child("address").getValue(String::class.java) ?: "",
                        latitude      = child.child("latitude").getValue(Double::class.java) ?: 0.0,
                        longitude     = child.child("longitude").getValue(Double::class.java) ?: 0.0,
                        email         = child.child("email").getValue(String::class.java) ?: "",
                        phone         = child.child("phone").getValue(String::class.java) ?: "",
                        managerUid    = managerUid,
                        managerName   = cachedName ?: "",
                        parentBranchId = child.child("parent_branch_id").getValue(String::class.java) ?: "",
                        status        = child.child("status").getValue(String::class.java) ?: "active",
                        imageUrl      = child.child("image_url").getValue(String::class.java) ?: "",
                        createdAt     = child.child("created_at").getValue(Long::class.java) ?: 0L
                    )
                }
                // Fetch only missing manager names (targeted per-uid, not full scan)
                managers.clear()
                missingManagerUids.forEach { uid ->
                    val name = runCatching {
                        db.reference.child("users/$uid/profile/name").get().await()
                            .getValue(String::class.java)
                    }.getOrNull() ?: uid.take(6)
                    managers.add(uid to name)
                }
                allBranches = rawBranches.map { b ->
                    if (b.managerName.isBlank() && b.managerUid.isNotBlank())
                        b.copy(managerName = managers.find { it.first == b.managerUid }?.second ?: "None")
                    else b
                }.sortedBy { it.name }

                pbLoading.visibility = View.GONE
                adapter.submitList(allBranches)

                if (allBranches.isEmpty()) {
                    tvEmpty.text = "No branches found."
                    tvEmpty.visibility = View.VISIBLE
                    rvBranches.visibility = View.GONE
                } else {
                    tvEmpty.visibility = View.GONE
                    rvBranches.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                pbLoading.visibility = View.GONE
                tvEmpty.text = "Failed to load branches."
                tvEmpty.visibility = View.VISIBLE
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openEditDialog(branch: BranchEntry) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, BranchEditFragment.newInstance(branch.branchId))
            .addToBackStack(null)
            .commit()
    }

    private fun confirmDelete(branch: BranchEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete ${branch.name}?")
            .setMessage("This will remove the branch. Employees will keep their data but lose branch association.")
            .setPositiveButton("Delete") { _, _ -> deleteBranch(branch) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteBranch(branch: BranchEntry) {
        lifecycleScope.launch {
            try {
                // Remove this branch from manager's branch_ids
                if (branch.managerUid.isNotBlank()) {
                    val idsSnap = db.reference.child("users/${branch.managerUid}/profile/company_info/branch_ids").get().await()
                    val ids = if (idsSnap.exists()) idsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    val filtered = ids.filter { it != branch.branchId }
                    db.reference.child("users/${branch.managerUid}/profile/company_info/branch_ids").setValue(filtered).await()
                }

                db.reference.child("branches/${branch.branchId}").removeValue().await()
                Toast.makeText(requireContext(), "Branch deleted ✓", Toast.LENGTH_SHORT).show()
                loadData()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    class BranchAdapter(
        private val onView: (BranchEntry) -> Unit,
        private val onEdit: (BranchEntry) -> Unit,
        private val onDelete: (BranchEntry) -> Unit,
        private val canManage: Boolean = false
    ) : ListAdapter<BranchEntry, BranchAdapter.VH>(Diff()) {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivIcon: ImageView  = v.findViewById(R.id.ivBranchIcon)
            val tvName: TextView   = v.findViewById(R.id.tvBranchName)
            val tvId: TextView     = v.findViewById(R.id.tvBranchId)
            val tvAddress: TextView = v.findViewById(R.id.tvBranchAddress)
            val tvManager: TextView = v.findViewById(R.id.tvBranchManager)
            val ivEdit: ImageView  = v.findViewById(R.id.ivEditBranch)
            val ivDelete: ImageView = v.findViewById(R.id.ivDeleteBranch)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_branch_card, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val branch = getItem(position)
            if (branch.imageUrl.isNotBlank()) {
                h.ivIcon.load(branch.imageUrl) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                    error(R.drawable.bg_branch_placeholder)
                }
            } else {
                h.ivIcon.setImageDrawable(null)
            }
            h.tvName.text    = branch.name
            h.tvId.text      = "ID: ${branch.branchId}"
            h.tvAddress.text = if (branch.address.isNotBlank()) "📍 ${branch.address}" else ""
            h.tvManager.text = "👤 ${branch.managerName}"

            h.itemView.setOnClickListener { onView(branch) }

            h.ivEdit.visibility   = if (canManage) View.VISIBLE else View.GONE
            h.ivDelete.visibility = if (canManage) View.VISIBLE else View.GONE
            if (canManage) {
                h.ivEdit.setOnClickListener   { onEdit(branch) }
                h.ivDelete.setOnClickListener { onDelete(branch) }
            }
        }

        class Diff : DiffUtil.ItemCallback<BranchEntry>() {
            override fun areItemsTheSame(o: BranchEntry, n: BranchEntry) = o.branchId == n.branchId
            override fun areContentsTheSame(o: BranchEntry, n: BranchEntry) = o == n
        }
    }
}
