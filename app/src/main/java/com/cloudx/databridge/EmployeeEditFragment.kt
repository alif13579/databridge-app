package com.cloudx.databridge

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.util.Patterns
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class EmployeeEditFragment : Fragment() {

    companion object {
        private const val ARG_UID = "uid"
        fun newInstance(uid: String) = EmployeeEditFragment().apply {
            arguments = Bundle().also { it.putString(ARG_UID, uid) }
        }
    }

    private val db = FirebaseDatabase.getInstance()

    data class BranchItem(val id: String, val name: String, val type: String)

    private val allBranches  = mutableListOf<BranchItem>()
    private val allowedRoles = mutableListOf<String>()
    private val rolesMap     = mutableMapOf<String, String>() // id -> name
    private val agentTypeOptions = mutableListOf<SalaryModelOption>()
    private val salaryModelOptions = mutableListOf<SalaryModelOption>()
    private val selectedBranchIds = mutableSetOf<String>()
    private val originalBranchIds = mutableSetOf<String>()
    private var suppressAgentEvents = false
    private var suppressSalaryModelEvents = false
    private var savedAgentType = ""
    private var savedSalaryModel = ""

    private lateinit var tvUid: TextView
    private lateinit var etSystemId: EditText
    private lateinit var etEmployeeId: EditText
    private lateinit var etName: EditText
    private lateinit var etEmail: EditText
    private lateinit var etPhone: EditText
    private lateinit var etDesignation: EditText
    private lateinit var spinnerRole: Spinner
    private lateinit var spinnerStatus: Spinner
    private lateinit var spinnerSalaryType: Spinner
    private lateinit var spinnerAgentType: Spinner
    private lateinit var spinnerSalaryModel: Spinner
    private lateinit var btnClearSalaryType: TextView
    private lateinit var btnClearAgentType: TextView
    private lateinit var btnClearSalaryModel: TextView
    private lateinit var etFixedAmount: EditText
    private lateinit var btnToggleTypeModel: TextView
    private lateinit var llTypeModel: LinearLayout
    private lateinit var btnToggleAgentType: TextView
    private lateinit var llAgentType: LinearLayout
    private lateinit var btnToggleSalaryModel: TextView
    private lateinit var llSalaryModel: LinearLayout
    private lateinit var btnToggleFixedAmount: TextView
    private lateinit var llFixedAmount: LinearLayout
    private lateinit var btnSelectBranch: TextView
    private lateinit var btnClearBranch: TextView
    private lateinit var tvBranchSelected: TextView
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_employee_edit, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvUid            = view.findViewById(R.id.tvEmployeeUid)
        etSystemId       = view.findViewById(R.id.etSystemId)
        etEmployeeId     = view.findViewById(R.id.etEmployeeId)
        etName           = view.findViewById(R.id.etEmployeeName)
        etEmail          = view.findViewById(R.id.etEmployeeEmail)
        etPhone          = view.findViewById(R.id.etEmployeePhone)
        etDesignation    = view.findViewById(R.id.etDesignation)
        spinnerRole      = view.findViewById(R.id.spinnerEmployeeRole)
        spinnerStatus      = view.findViewById(R.id.spinnerStatus)
        spinnerSalaryType  = view.findViewById(R.id.spinnerSalaryType)
        spinnerAgentType   = view.findViewById(R.id.spinnerAgentType)
        spinnerSalaryModel = view.findViewById(R.id.spinnerSalaryModel)
        btnClearSalaryType = view.findViewById(R.id.btnClearSalaryType)
        btnClearAgentType  = view.findViewById(R.id.btnClearAgentType)
        btnClearSalaryModel = view.findViewById(R.id.btnClearSalaryModel)
        etFixedAmount     = view.findViewById(R.id.etFixedAmount)
        btnToggleTypeModel = view.findViewById(R.id.btnToggleTypeModel)
        llTypeModel        = view.findViewById(R.id.llTypeModel)
        btnToggleAgentType = view.findViewById(R.id.btnToggleAgentType)
        llAgentType        = view.findViewById(R.id.llAgentType)
        btnSelectBranch  = view.findViewById(R.id.btnSelectBranch)
        btnClearBranch   = view.findViewById(R.id.btnClearBranch)
        tvBranchSelected = view.findViewById(R.id.tvBranchSelected)
        btnSave          = view.findViewById(R.id.btnSaveEmployee)
        btnCancel        = view.findViewById(R.id.btnCancelEmployee)
        btnToggleSalaryModel = view.findViewById(R.id.btnToggleSalaryModel)
        llSalaryModel        = view.findViewById(R.id.llSalaryModel)
        btnToggleFixedAmount = view.findViewById(R.id.btnToggleFixedAmount)
        llFixedAmount        = view.findViewById(R.id.llFixedAmount)

        lifecycleScope.launch { loadAll() }

        btnSelectBranch.setOnClickListener { showBranchPicker() }
        btnClearBranch.setOnClickListener {
            selectedBranchIds.clear()
            btnSelectBranch.text = "Tap to select branch ▾"
            btnSelectBranch.setTextColor(color(R.color.theme_text_secondary))
            tvBranchSelected.text = "None selected"
            tvBranchSelected.setTextColor(color(R.color.theme_text_secondary))
            btnClearBranch.visibility = View.GONE
        }

        btnSave.setOnClickListener { onSave() }
        btnCancel.setOnClickListener { parentFragmentManager.popBackStack() }

        btnToggleTypeModel.setOnClickListener {
            val visible = llTypeModel.visibility == View.VISIBLE
            llTypeModel.visibility = if (visible) View.GONE else View.VISIBLE
            btnToggleTypeModel.text = if (visible) "+ Add Salary Type" else "− Hide Salary Type"
        }
        btnToggleAgentType.setOnClickListener {
            val visible = llAgentType.visibility == View.VISIBLE
            llAgentType.visibility = if (visible) View.GONE else View.VISIBLE
            btnToggleAgentType.text = if (visible) "+ Add Agent Type" else "− Hide Agent Type"
        }
        btnToggleSalaryModel.setOnClickListener {
            val visible = llSalaryModel.visibility == View.VISIBLE
            llSalaryModel.visibility = if (visible) View.GONE else View.VISIBLE
            btnToggleSalaryModel.text = if (visible) "+ Add Salary Model" else "− Hide Salary Model"
        }
        btnToggleFixedAmount.setOnClickListener {
            val visible = llFixedAmount.visibility == View.VISIBLE
            llFixedAmount.visibility = if (visible) View.GONE else View.VISIBLE
            btnToggleFixedAmount.text = if (visible) "+ Add Salary Amount" else "− Hide Salary Amount"
        }

        btnClearSalaryType.setOnClickListener {
            val placeholderIndex = (spinnerSalaryType.adapter as ArrayAdapter<*>).count
            spinnerSalaryType.setSelection(placeholderIndex)
            btnClearSalaryType.visibility = View.GONE
            // When cleared, allow user to use + Add again and keep section hidden by default
            llTypeModel.visibility = View.GONE
            btnToggleTypeModel.visibility = View.VISIBLE
            btnToggleTypeModel.text = "+ Add Salary Type"
        }
        btnClearAgentType.setOnClickListener {
            val placeholderIndex = (spinnerAgentType.adapter as ArrayAdapter<*>).count
            spinnerAgentType.setSelection(placeholderIndex)
            btnClearAgentType.visibility = View.GONE
            llAgentType.visibility = View.GONE
            btnToggleAgentType.visibility = View.VISIBLE
            btnToggleAgentType.text = "+ Add Agent Type"
            salaryModelOptions.clear()
            configureSalaryModelSpinner("")
        }
        btnClearSalaryModel.setOnClickListener {
            val placeholderIndex = (spinnerSalaryModel.adapter as ArrayAdapter<*>).count
            spinnerSalaryModel.setSelection(placeholderIndex)
            btnClearSalaryModel.visibility = View.GONE
        }

        spinnerSalaryType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                val placeholderIndex = (spinnerSalaryType.adapter as ArrayAdapter<*>).count
                btnClearSalaryType.visibility = if (position < placeholderIndex) View.VISIBLE else View.GONE
                // Stick open when a valid type is chosen; hide the + button. Show + only when unset.
                if (position < placeholderIndex) {
                    llTypeModel.visibility = View.VISIBLE
                    btnToggleTypeModel.visibility = View.GONE
                } else {
                    llTypeModel.visibility = View.GONE
                    btnToggleTypeModel.visibility = View.VISIBLE
                    btnToggleTypeModel.text = "+ Add Salary Type"
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }
        spinnerAgentType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                android.util.Log.d("EmployeeEdit", ">>> spinnerAgentType onItemSelected: position=$position, suppress=$suppressAgentEvents")
                if (suppressAgentEvents) {
                    android.util.Log.d("EmployeeEdit", ">>> spinnerAgentType onItemSelected: SUPPRESSED, returning")
                    return
                }
                android.util.Log.d("EmployeeEdit", ">>> spinnerAgentType onItemSelected: NOT suppressed, continuing")
                val placeholderIndex = (spinnerAgentType.adapter as ArrayAdapter<*>).count
                btnClearAgentType.visibility = if (position < placeholderIndex) View.VISIBLE else View.GONE
                if (position < placeholderIndex) {
                    llAgentType.visibility = View.VISIBLE
                    btnToggleAgentType.visibility = View.GONE
                    val agentType = agentTypeOptions.getOrNull(position)?.id.orEmpty()
                    android.util.Log.d("EmployeeEdit", ">>> spinnerAgentType onItemSelected: loading salary models for=$agentType")
                    lifecycleScope.launch {
                        loadSalaryModelsForAgent(agentType)
                        configureSalaryModelSpinner(savedSalaryModel)
                    }
                } else {
                    llAgentType.visibility = View.GONE
                    btnToggleAgentType.visibility = View.VISIBLE
                    btnToggleAgentType.text = "+ Add Agent Type"
                    salaryModelOptions.clear()
                    configureSalaryModelSpinner("")
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }
        spinnerSalaryModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                android.util.Log.d("EmployeeEdit", ">>> spinnerSalaryModel onItemSelected: position=$position, suppress=$suppressSalaryModelEvents")
                if (suppressSalaryModelEvents) {
                    android.util.Log.d("EmployeeEdit", ">>> spinnerSalaryModel onItemSelected: SUPPRESSED, returning early")
                    return
                }
                android.util.Log.d("EmployeeEdit", ">>> spinnerSalaryModel onItemSelected: NOT suppressed, continuing")
                val placeholderIndex = (spinnerSalaryModel.adapter as ArrayAdapter<*>).count
                btnClearSalaryModel.visibility = if (position < placeholderIndex) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }
    }

    private fun showBranchPicker() {
        val ctx = requireContext()
        val dp  = resources.displayMetrics.density.toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp * 16, dp * 8, dp * 16, 0)
        }
        val etSearch = EditText(ctx).apply {
            hint = "Search branch..."
            setTextColor(color(R.color.theme_text_primary))
            setHintTextColor(color(R.color.theme_text_secondary))
        }
        container.addView(etSearch)
        val listView = ListView(ctx).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
        container.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp * 300))

        var filtered = allBranches.toMutableList()

        fun refreshChecks() {
            // Mark checked rows based on selectedBranchIds
            for (i in 0 until filtered.size) {
                val id = filtered[i].id
                listView.setItemChecked(i, selectedBranchIds.contains(id))
            }
        }

        fun makeAdapter(list: List<BranchItem>) = object : ArrayAdapter<BranchItem>(
            ctx, android.R.layout.simple_list_item_multiple_choice, android.R.id.text1, list) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getView(pos, cv, parent)
                val item = list[pos]
                (v.findViewById<View>(android.R.id.text1) as? TextView)?.text = "${item.name}  (${item.type})"
                return v
            }
        }
        listView.adapter = makeAdapter(filtered)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Select Branches")
            .setView(container)
            .setPositiveButton("Done", null)
            .setNeutralButton("Clear", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // Update summary UI
                if (selectedBranchIds.isEmpty()) {
                    btnSelectBranch.text = "Tap to select branch ▾"
                    btnSelectBranch.setTextColor(color(R.color.theme_text_secondary))
                    tvBranchSelected.text = "None selected"
                    tvBranchSelected.setTextColor(color(R.color.theme_text_secondary))
                    btnClearBranch.visibility = View.GONE
                } else if (selectedBranchIds.size == 1) {
                    val id = selectedBranchIds.first()
                    val b = allBranches.find { it.id == id }
                    btnSelectBranch.text = b?.name ?: id
                    btnSelectBranch.setTextColor(color(R.color.theme_text_primary))
                    tvBranchSelected.text = b?.type ?: ""
                    tvBranchSelected.setTextColor(0xFF00D4FF.toInt())
                    btnClearBranch.visibility = View.VISIBLE
                } else {
                    val names = allBranches.filter { selectedBranchIds.contains(it.id) }.map { it.name }
                    btnSelectBranch.text = "${selectedBranchIds.size} branches selected"
                    btnSelectBranch.setTextColor(color(R.color.theme_text_primary))
                    val preview = if (names.size <= 3) names.joinToString(", ") else names.take(3).joinToString(", ") + " +${names.size - 3} more"
                    tvBranchSelected.text = preview
                    tvBranchSelected.setTextColor(0xFF00D4FF.toInt())
                    btnClearBranch.visibility = View.VISIBLE
                }
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                selectedBranchIds.clear()
                refreshChecks()
            }
            refreshChecks()
        }
        dialog.show()

        listView.setOnItemClickListener { _, _, i, _ ->
            val item = filtered[i]
            if (selectedBranchIds.contains(item.id)) selectedBranchIds.remove(item.id) else selectedBranchIds.add(item.id)
            listView.setItemChecked(i, selectedBranchIds.contains(item.id))
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                val q = s?.toString()?.trim() ?: ""
                filtered = allBranches.filter {
                    q.isEmpty() || it.name.contains(q, ignoreCase = true)
                }.toMutableList()
                listView.adapter = makeAdapter(filtered)
                refreshChecks()
            }
        })
    }

    private suspend fun loadAll() {
        val uid = arguments?.getString(ARG_UID) ?: return
        try {
            val userSnap     = db.reference.child("users/$uid/profile").get().await()
            val branchesSnap = db.reference.child("branches").get().await()
            val rolesSnap    = db.reference.child("roles").get().await()
            val salariesSnap = runCatching {
                db.reference.child("salaries").get().await()
            }.getOrNull()
            val rbac         = RbacManager.current

            allBranches.clear()
            branchesSnap.children.forEach { c ->
                val id   = c.key ?: return@forEach
                val name = c.child("name").getValue(String::class.java) ?: id
                val type = c.child("branch_type").getValue(String::class.java) ?: ""
                allBranches.add(BranchItem(id, name, type))
            }

            rolesMap.clear()
            rolesSnap.children.forEach { r ->
                val id = r.key ?: return@forEach
                val name = r.child("name").getValue(String::class.java).orEmpty()
                rolesMap[id] = name
            }

            agentTypeOptions.clear()
            agentTypeOptions.addAll(
                salariesSnap?.children?.mapNotNull { child ->
                    val id = child.key ?: return@mapNotNull null
                    SalaryModelOption(id, agentTypeLabel(id))
                }?.sortedBy { it.name.lowercase() } ?: emptyList()
            )

            val myRole  = rbac.roleId
            val myLevel = EmployeeFragment.ROLE_LEVELS[myRole] ?: 99

            val currentRole = userSnap.child("company_info/role_id").getValue(String::class.java) ?: ""
            val status      = userSnap.child("company_info/status").getValue(String::class.java) ?: "active"
            val salaryType  = userSnap.child("company_info/salary_type").getValue(String::class.java) ?: ""
            val agentType   = userSnap.child("company_info/agent_type").getValue(String::class.java) ?: ""
            val salaryModel = userSnap.child("company_info/salary_model").getValue(String::class.java) ?: ""
            val fixedAmount = userSnap.child("company_info/fixed_amount").getValue(String::class.java) ?: ""
            val targetLevel = EmployeeFragment.ROLE_LEVELS[currentRole] ?: 99
            
            // Save for access in listeners
            savedAgentType = agentType
            savedSalaryModel = salaryModel

            // Guard: must be able to manage this user's role
            if (myRole != "admin" && (myLevel > 2 || myLevel >= targetLevel)) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "No permission to edit this user", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                }
                return
            }

            allowedRoles.clear()
            val roleIdsFromDb = rolesMap.keys.sorted()
            allowedRoles.addAll(
                roleIdsFromDb.filter { rid ->
                    val level = EmployeeFragment.ROLE_LEVELS[rid] ?: 99
                    when {
                        myRole == "admin" -> true
                        myLevel > 2 -> false // stuff/worker/guest cannot assign any role
                        else -> level > myLevel
                    }
                }
            )

            if (!isAdded) return

            tvUid.text = "UID: $uid"
            etSystemId.setText(userSnap.child("company_info/system_id").getValue(String::class.java) ?: "")
            etEmployeeId.setText(userSnap.child("company_info/employee_id").getValue(String::class.java) ?: "")
            etName.setText(userSnap.child("name").getValue(String::class.java) ?: "")
            etEmail.setText(userSnap.child("email").getValue(String::class.java) ?: "")
            etPhone.setText(userSnap.child("phone").getValue(String::class.java) ?: "")
            etDesignation.setText(userSnap.child("company_info/designation").getValue(String::class.java) ?: "")

            val roleLabels = allowedRoles.map { rid -> rolesMap[rid]?.takeIf { it.isNotBlank() } ?: rid }
            spinnerRole.adapter = ArrayAdapter(requireContext(),
                android.R.layout.simple_spinner_item, roleLabels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinnerRole.isEnabled = allowedRoles.isNotEmpty()
            spinnerRole.setSelection(allowedRoles.indexOf(currentRole).coerceAtLeast(0))

            val statusValues = listOf("active", "inactive")
            val statusLabels = listOf("Active", "Inactive")
            spinnerStatus.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, statusLabels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinnerStatus.setSelection(statusValues.indexOf(status).coerceAtLeast(0))

            val typeValues = listOf("fixed", "variable")
            val typeLabels = listOf("Fixed", "Variable", "Select salary type")
            val typeAdapter = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item, typeLabels) {
                override fun getCount(): Int = super.getCount() - 1 // hide placeholder in dropdown
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinnerSalaryType.adapter = typeAdapter
            val typePlaceholderIndex = typeAdapter.count
            val typeIndex = if (salaryType.isBlank()) typePlaceholderIndex else typeValues.indexOf(salaryType).coerceAtLeast(0)
            spinnerSalaryType.setSelection(typeIndex)
            btnClearSalaryType.visibility = if (typeIndex < typePlaceholderIndex) View.VISIBLE else View.GONE
            if (typeIndex < typePlaceholderIndex) {
                llTypeModel.visibility = View.VISIBLE
                btnToggleTypeModel.visibility = View.GONE
            } else {
                llTypeModel.visibility = View.GONE
                btnToggleTypeModel.visibility = View.VISIBLE
                btnToggleTypeModel.text = "+ Add Salary Type"
            }

            val agentLabels = agentTypeOptions.map { it.name } + "Select agent type"
            val agentAdapter = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item, agentLabels) {
                override fun getCount(): Int = super.getCount() - 1 // hide placeholder in dropdown
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinnerAgentType.adapter = agentAdapter
            val agentPlaceholderIndex = agentAdapter.count
            val agentIndex = if (agentType.isBlank()) agentPlaceholderIndex else {
                val idx = agentTypeOptions.indexOfFirst { it.id == agentType }
                if (idx >= 0) idx else agentPlaceholderIndex
            }
            suppressAgentEvents = true
            spinnerAgentType.setSelection(agentIndex)
            suppressAgentEvents = false
            btnClearAgentType.visibility = if (agentIndex < agentPlaceholderIndex) View.VISIBLE else View.GONE
            if (agentIndex < agentPlaceholderIndex) {
                llAgentType.visibility = View.VISIBLE
                btnToggleAgentType.visibility = View.GONE
            } else {
                llAgentType.visibility = View.GONE
                btnToggleAgentType.visibility = View.VISIBLE
                btnToggleAgentType.text = "+ Add Agent Type"
            }

            if (agentIndex < agentPlaceholderIndex) {
                loadSalaryModelsForAgent(agentType)
                android.util.Log.d("EmployeeEdit", "loadAll: Loaded salary models for agentType=$agentType from dropdown (index=$agentIndex)")
            } else if (agentType.isNotBlank()) {
                // If agentType is in data but not in options list, still load models for it
                // Don't load again - agent type listener will handle it
                android.util.Log.d("EmployeeEdit", "loadAll: agentType=$agentType NOT in dropdown, will load when listener fires")
            } else {
                salaryModelOptions.clear()
                android.util.Log.d("EmployeeEdit", "loadAll: No agentType, clearing salary models")
            }
            
            // Fetch salary model NAME from Firebase to display
            val salaryModelName = if (salaryModel.isNotBlank() && agentType.isNotBlank()) {
                fetchSalaryModelName(agentType, salaryModel)
            } else {
                salaryModel
            }
            configureSalaryModelSpinner(salaryModel)

            etFixedAmount.setText(fixedAmount)
            if (fixedAmount.isNotBlank()) {
                llFixedAmount.visibility = View.VISIBLE
                btnToggleFixedAmount.visibility = View.GONE
            } else {
                llFixedAmount.visibility = View.GONE
                btnToggleFixedAmount.visibility = View.VISIBLE
                btnToggleFixedAmount.text = "+ Add Salary Amount"
            }

            val branchIdsNode = userSnap.child("company_info/branch_ids")
            val loaded = if (branchIdsNode.exists()) {
                branchIdsNode.children.mapNotNull { it.getValue(String::class.java) }
            } else emptyList()
            originalBranchIds.clear(); originalBranchIds.addAll(loaded)
            selectedBranchIds.clear(); selectedBranchIds.addAll(loaded)

            if (selectedBranchIds.isNotEmpty()) {
                if (selectedBranchIds.size == 1) {
                    val id = selectedBranchIds.first()
                    val b = allBranches.find { it.id == id }
                    btnSelectBranch.text = b?.name ?: id
                    btnSelectBranch.setTextColor(color(R.color.theme_text_primary))
                    tvBranchSelected.text = b?.type ?: ""
                    tvBranchSelected.setTextColor(0xFF00D4FF.toInt())
                    btnClearBranch.visibility = View.VISIBLE
                } else {
                    val names = allBranches.filter { selectedBranchIds.contains(it.id) }.map { it.name }
                    btnSelectBranch.text = "${selectedBranchIds.size} branches selected"
                    btnSelectBranch.setTextColor(color(R.color.theme_text_primary))
                    val preview = if (names.size <= 3) names.joinToString(", ") else names.take(3).joinToString(", ") + " +${names.size - 3} more"
                    tvBranchSelected.text = preview
                    tvBranchSelected.setTextColor(0xFF00D4FF.toInt())
                    btnClearBranch.visibility = View.VISIBLE
                }
            }
        } catch (e: Exception) {
            if (isAdded) toast("Failed to load: ${e.message}")
        }
    }

    private suspend fun loadSalaryModelsForAgent(agentType: String) {
        android.util.Log.d("EmployeeEdit", ">>> loadSalaryModelsForAgent CALLED for=$agentType, current_size_before_clear=${salaryModelOptions.size}")
        salaryModelOptions.clear()
        if (agentType.isBlank()) {
            android.util.Log.d("EmployeeEdit", "loadSalaryModelsForAgent: agentType is blank")
            return
        }
        val snap = runCatching {
            db.reference.child("salaries/$agentType/commission_models").get().await()
        }.getOrNull()
        if (snap == null) {
            android.util.Log.w("EmployeeEdit", "loadSalaryModelsForAgent: Failed to load from salaries/$agentType/commission_models")
            return
        }
        val distinct = snap.toSalaryModelOptions().distinctBy { it.id }
        salaryModelOptions.addAll(distinct)
        android.util.Log.d("EmployeeEdit", ">>> loadSalaryModelsForAgent DONE: Loaded ${salaryModelOptions.size} models for $agentType, list=$salaryModelOptions")
    }

    private fun configureSalaryModelSpinner(selectedModel: String) {
        if (!isAdded) return
        android.util.Log.d("EmployeeEdit", ">>> configureSalaryModelSpinner CALLED: selectedModel=$selectedModel, current_options=${salaryModelOptions.size}")
        val options = salaryModelOptions.distinctBy { it.id }
        val modelValues = options.map { it.id }
        val modelLabels = options.map { it.name } + "Select salary model"
        android.util.Log.d("EmployeeEdit", ">>> configureSalaryModelSpinner: options=$modelValues, labels=$modelLabels")
        val modelAdapter = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item, modelLabels) {
            override fun getCount(): Int = super.getCount() - 1
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerSalaryModel.adapter = modelAdapter
        val modelPlaceholderIndex = modelAdapter.count
        val modelIndex = if (selectedModel.isBlank()) modelPlaceholderIndex else {
            val idx = modelValues.indexOf(selectedModel)
            if (idx >= 0) idx else modelPlaceholderIndex
        }
        android.util.Log.d("EmployeeEdit", ">>> configureSalaryModelSpinner: Setting selection to index=$modelIndex (suppress=${suppressSalaryModelEvents})")
        suppressSalaryModelEvents = true
        spinnerSalaryModel.setSelection(modelIndex)
        suppressSalaryModelEvents = false
        android.util.Log.d("EmployeeEdit", ">>> configureSalaryModelSpinner DONE")
        btnClearSalaryModel.visibility = if (modelIndex < modelPlaceholderIndex) View.VISIBLE else View.GONE
        if (modelIndex < modelPlaceholderIndex) {
            llSalaryModel.visibility = View.VISIBLE
            btnToggleSalaryModel.visibility = View.GONE
        } else {
            llSalaryModel.visibility = View.GONE
            btnToggleSalaryModel.visibility = View.VISIBLE
            btnToggleSalaryModel.text = "+ Add Salary Model"
        }
    }

    private suspend fun fetchSalaryModelName(agentType: String, salaryModel: String): String {
        if (agentType.isBlank() || salaryModel.isBlank()) return salaryModel
        val snap = runCatching {
            db.reference.child("salaries/$agentType/commission_models/$salaryModel/name").get().await()
        }.getOrNull()
        val name = snap?.getValue(String::class.java)
        android.util.Log.d("EmployeeEdit", "fetchSalaryModelName: $agentType / $salaryModel -> $name")
        return name ?: salaryModel
    }

    private fun agentTypeLabel(id: String): String {
        return id.split("_")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
    }

    private fun onSave() {
        val uid  = arguments?.getString(ARG_UID) ?: return
        // Defense in depth: re-verify permission
        if (allowedRoles.isEmpty() && !EmployeeFragment.canManageUsers(RbacManager.current.roleId)) {
            toast("No permission to edit this user")
            return
        }
        val name = etName.text.toString().trim()
        if (name.isBlank()) { toast("Name required"); return }

        val emailText = etEmail.text.toString().trim()
        if (emailText.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(emailText).matches()) {
            toast("Invalid email format"); return
        }

        val phoneText = etPhone.text.toString().trim()
        if (phoneText.isNotEmpty() && !Patterns.PHONE.matcher(phoneText).matches()) {
            toast("Invalid phone number"); return
        }

        val roleId = allowedRoles.getOrNull(spinnerRole.selectedItemPosition) ?: ""
        val status = listOf("active", "inactive").getOrNull(spinnerStatus.selectedItemPosition) ?: "active"
        val selectedTypePos = spinnerSalaryType.selectedItemPosition
        val typePlaceholderIdx = (spinnerSalaryType.adapter as ArrayAdapter<*>).count
        val salaryType = if (selectedTypePos >= typePlaceholderIdx) "" else listOf("fixed", "variable")[selectedTypePos]
        val selectedAgentPos = spinnerAgentType.selectedItemPosition
        val agentPlaceholderIdx = (spinnerAgentType.adapter as ArrayAdapter<*>).count
        val agentType = if (selectedAgentPos >= agentPlaceholderIdx) "" else agentTypeOptions.getOrNull(selectedAgentPos)?.id.orEmpty()
        val selectedModelPos = spinnerSalaryModel.selectedItemPosition
        val modelPlaceholderIdx = (spinnerSalaryModel.adapter as ArrayAdapter<*>).count
        val salaryModel = if (selectedModelPos >= modelPlaceholderIdx) "" else salaryModelOptions.getOrNull(selectedModelPos)?.id.orEmpty()
        val fixedAmount = etFixedAmount.text.toString().trim()

        lifecycleScope.launch {
            try {
                btnSave.isEnabled = false
                val sysId = etSystemId.text.toString().trim()
                val empId = etEmployeeId.text.toString().trim()
                val updates = mutableMapOf<String, Any?>(
                    "users/$uid/profile/company_info/system_id"    to sysId,
                    "users/$uid/profile/company_info/employee_id"  to empId,
                    "users/$uid/profile/name"                      to name,
                    "users/$uid/profile/email"                     to emailText,
                    "users/$uid/profile/phone"                     to phoneText,
                    "users/$uid/profile/company_info/designation"  to etDesignation.text.toString().trim(),
                    "users/$uid/profile/company_info/role_id"      to roleId,
                    "users/$uid/profile/company_info/status"       to status,
                    // If no branches selected, remove the path so old values don't linger
                    "users/$uid/profile/company_info/branch_ids"   to (if (selectedBranchIds.isEmpty()) null else selectedBranchIds.toList()),
                    "users/$uid/profile/company_info/salary_type"  to salaryType,
                    "users/$uid/profile/company_info/agent_type"   to agentType,
                    "users/$uid/profile/company_info/salary_model" to salaryModel,
                    "users/$uid/profile/company_info/fixed_amount" to fixedAmount
                )
                // Sync branch employees index: add for all selected
                selectedBranchIds.forEach { bid ->
                    updates["branches/$bid/employees/$uid"] = mapOf("employee_id" to empId, "user_id" to uid)
                }
                db.reference.updateChildren(updates).await()
                // Remove from old branch indices no longer selected
                val toRemove = originalBranchIds.minus(selectedBranchIds)
                for (oldId in toRemove) {
                    db.reference.child("branches/$oldId/employees/$uid").removeValue().await()
                }
                toast("Saved ✓")
                parentFragmentManager.popBackStack()
            } catch (e: Exception) {
                btnSave.isEnabled = true
                toast("Failed: ${e.message}")
            }
        }
    }

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)
}
