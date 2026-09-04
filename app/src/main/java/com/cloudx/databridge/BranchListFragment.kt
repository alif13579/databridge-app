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
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 🏢 Branch List Fragment
 * Admin can view, edit, delete all branches
 */
class BranchListFragment : Fragment() {

    private val db = FirebaseDatabase.getInstance()

    private lateinit var rvBranches: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var ivAddBranch: ImageView

    private var allBranches = listOf<BranchEntry>()

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
        val accountantUid: String,
        val pettyCashPocUid: String,
        val staffUid: String,
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
                // Branch directory reads ONLY Supabase now (branch cutover).
                // public.branches has no name columns (manager_name etc. were
                // Firebase-only copies), so holder names resolve from Firebase
                // user profiles below — batched per distinct uid, not per row.
                val rows = SupabaseBranchReader.listBranches()
                val rawBranches = rows.map { r ->
                    BranchEntry(
                        branchId      = r.branchId,
                        branchCode    = r.branchCode,
                        name          = r.name.ifBlank { r.branchId },
                        branchType    = r.branchType,
                        address       = r.address,
                        latitude      = r.latitude,
                        longitude     = r.longitude,
                        email         = r.email,
                        phone         = r.phone,
                        managerUid    = r.managerUid,
                        managerName   = "",
                        accountantUid = r.accountantUid,
                        pettyCashPocUid = r.pettyCashPocUid,
                        staffUid = r.staffUid,
                        parentBranchId = r.parentBranchId,
                        status        = r.status,
                        imageUrl      = r.imageUrl,
                        createdAt     = r.createdAt
                    )
                }
                // Resolve manager names (targeted per-uid, not full scan)
                val nameByUid = mutableMapOf<String, String>()
                rawBranches.map { it.managerUid }.filter { it.isNotBlank() }.distinct().forEach { uid ->
                    val name = runCatching {
                        db.reference.child("users/$uid/profile/name").get().await()
                            .getValue(String::class.java)
                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uid.take(6)
                    nameByUid[uid] = name
                }
                allBranches = rawBranches.map { b ->
                    if (b.managerUid.isNotBlank())
                        b.copy(managerName = nameByUid[b.managerUid] ?: "None")
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
                // Branch row deletes from Supabase (Edge refuses with 409 when
                // claims still reference it). Firebase user-profile branch_ids
                // are cleaned alongside (membership, not branch data).
                SupabaseBranchWriter.delete(branch.branchId)
                // Remove this branch from manager's branch_ids
                if (branch.managerUid.isNotBlank()) {
                    val idsSnap = db.reference.child("users/${branch.managerUid}/profile/company_info/branch_ids").get().await()
                    val ids = if (idsSnap.exists()) idsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    val filtered = ids.filter { it != branch.branchId }
                    db.reference.child("users/${branch.managerUid}/profile/company_info/branch_ids").setValue(filtered).await()
                }

                // Same for accountant's branch_ids
                if (branch.accountantUid.isNotBlank()) {
                    val idsSnap = db.reference.child("users/${branch.accountantUid}/profile/company_info/branch_ids").get().await()
                    val ids = if (idsSnap.exists()) idsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    val filtered = ids.filter { it != branch.branchId }
                    db.reference.child("users/${branch.accountantUid}/profile/company_info/branch_ids").setValue(filtered).await()
                }

                // Same for Petty Cash POC's branch_ids
                if (branch.pettyCashPocUid.isNotBlank()) {
                    val idsSnap = db.reference.child("users/${branch.pettyCashPocUid}/profile/company_info/branch_ids").get().await()
                    val ids = if (idsSnap.exists()) idsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    val filtered = ids.filter { it != branch.branchId }
                    db.reference.child("users/${branch.pettyCashPocUid}/profile/company_info/branch_ids").setValue(filtered).await()
                }

                // Same for Staff's branch_ids
                if (branch.staffUid.isNotBlank()) {
                    val idsSnap = db.reference.child("users/${branch.staffUid}/profile/company_info/branch_ids").get().await()
                    val ids = if (idsSnap.exists()) idsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    val filtered = ids.filter { it != branch.branchId }
                    db.reference.child("users/${branch.staffUid}/profile/company_info/branch_ids").setValue(filtered).await()
                }

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
