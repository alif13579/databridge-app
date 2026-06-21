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
import java.util.UUID

class BranchCreateFragment : Fragment() {

    private val db      = FirebaseDatabase.getInstance()
    private val auth    = FirebaseAuth.getInstance()
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

    private var selectedManagerUid  = ""
    private var selectedManagerName = ""
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
    private lateinit var btnSelectManager: TextView
    private lateinit var btnClearManager: TextView
    private lateinit var tvManagerSelected: TextView
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
        btnSelectManager      = view.findViewById(R.id.btnSelectManager)
        btnClearManager       = view.findViewById(R.id.btnClearManager)
        tvManagerSelected     = view.findViewById(R.id.tvManagerSelected)
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
            showSearchPicker("Select Parent Branch", allBranches) { item ->
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

        btnCreate.setOnClickListener { onCreateClicked() }
        btnCancel.setOnClickListener { parentFragmentManager.popBackStack() }
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
            val branchesSnap = db.reference.child("branches").get().await()

            allEmployees.clear()
            usersSnap.children.forEach { child ->
                val uid   = child.key ?: return@forEach
                val role  = child.child("profile/company_info/role_id").getValue(String::class.java) ?: ""
                if (role in listOf("admin", "manager", "supervisor", "stuff", "worker")) {
                    val name  = child.child("profile/name").getValue(String::class.java)
                               ?: child.child("profile/email").getValue(String::class.java)
                               ?: uid.take(8)
                    val empId = child.child("profile/company_info/employee_id").getValue(String::class.java) ?: ""
                    val desig = child.child("profile/company_info/designation").getValue(String::class.java)
                               ?: EmployeeFragment.ROLE_LABELS[role] ?: role
                    allEmployees.add(PickerItem(uid, name, desig, empId))
                }
            }

            allBranches.clear()
            branchesSnap.children.forEach { child ->
                val id   = child.key ?: return@forEach
                val name = child.child("name").getValue(String::class.java) ?: id
                val type = child.child("branch_type").getValue(String::class.java) ?: ""
                allBranches.add(PickerItem(id, name, type))
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
        val status = spinnerStatus.selectedItem?.toString() ?: "active"

        if (code.isBlank()) { toast("Branch Code required"); return }
        if (name.isBlank()) { toast("Branch Name required"); return }

        val now    = System.currentTimeMillis()
        val byUid  = auth.currentUser?.uid ?: ""
        val byName = auth.currentUser?.displayName ?: byUid.take(8)

        lifecycleScope.launch {
            try {
                btnCreate.isEnabled = false

                val imageUrl = uploadImageIfNeeded(autoId)

                val logKey = now.toString()
                val branch = Branch(
                    branch_id       = autoId,
                    branch_code     = code,
                    name            = name,
                    branch_type     = type,
                    address         = addr,
                    latitude        = lat,
                    longitude       = lng,
                    email           = email,
                    phone           = phone,
                    manager_uid     = selectedManagerUid,
                    manager_name    = selectedManagerName,
                    parent_branch_id = selectedParentId,
                    status          = status,
                    image_url       = imageUrl,
                    created_by      = byUid,
                    created_at      = now,
                    updated_at      = now,
                    updated_log     = mapOf(logKey to UpdateLogEntry(byUid, byName, "created", now))
                )

                db.reference.child("branches/$autoId").setValue(branch).await()

                if (selectedManagerUid.isNotBlank()) {
                    // Add branch to manager's branch_ids and ensure branch employees index
                    val idsSnap = db.reference.child("users/$selectedManagerUid/profile/company_info/branch_ids").get().await()
                    val currentIds = if (idsSnap.exists()) idsSnap.children.mapNotNull { it.getValue(String::class.java) } else emptyList()
                    val newIds = (currentIds + autoId).distinct()
                    db.reference.child("users/$selectedManagerUid/profile/company_info/branch_ids").setValue(newIds).await()
                    val empId = allEmployees.find { it.id == selectedManagerUid }?.empId ?: ""
                    db.reference.child("branches/$autoId/employees/$selectedManagerUid")
                        .setValue(mapOf("user_id" to selectedManagerUid, "employee_id" to empId)).await()
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
