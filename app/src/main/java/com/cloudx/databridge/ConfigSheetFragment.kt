package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 📊 Config → Sheet tab
 *
 * Firebase paths (read/write):
 *   config/sheets/{branch_id}/current/   ← SheetConfig data class
 *   config/sheets/{branch_id}/history/   ← audit entries (push_id)
 *
 * Flow:
 *   1. Load user's branch_ids from RbacManager
 *   2. User picks branch from spinner
 *   3. Load existing config from Firebase (if any)
 *   4. Show connected / not-connected state
 *   5. Connect → TODO: Google OAuth flow (Sheets readonly scope)
 *   6. Column mapping (Manual / Auto-detect)
 *   7. Sync interval spinner → save to Firebase
 *   8. Save writes current/ and appends history/
 */
class ConfigSheetFragment : Fragment() {

    // ── Data model ────────────────────────────────────────────────────────────
    data class SheetConfig(
        val spreadsheetId: String = "",
        val spreadsheetName: String = "",
        val accountEmail: String = "",
        val tabName: String = "",
        val headerRow: Int = 1,
        val startRow: Int = 2,
        val endRow: Int = 0,           // 0 = no limit
        val colStart: String = "A",
        val colEnd: String = "H",
        val mappingMode: String = "manual",  // "manual" | "auto"
        val agentIdCol: String = "",
        val consignmentCol: String = "",
        val customerNameCol: String = "",
        val customerPhoneCol: String = "",
        val addressCol: String = "",
        val codCol: String = "",
        val orderStatusCol: String = "",
        val noteCol: String = "",
        val syncIntervalMin: Int = 30,
        val updatedAt: Long = 0,
        val updatedByUid: String = "",
        val updatedByName: String = "",
        val updatedByRole: String = ""
    )

    // ── Column mapping fields ─────────────────────────────────────────────────
    data class MappingField(
        val key: String,
        val label: String,
        val description: String,
        val required: Boolean
    )

    private val mappingFields = listOf(
        MappingField("agent_id",       "Agent ID",        "employee_id এর সাথে match করবে", true),
        MappingField("consignment",    "Consignment ID",  "Parcel / order ID",              true),
        MappingField("customer_name",  "Customer Name",   "Recipient name",                 false),
        MappingField("customer_phone", "Customer Phone",  "Recipient phone number",         false),
        MappingField("address",        "Address",         "Delivery address",               false),
        MappingField("cod",            "COD Amount",      "Cash on delivery amount",        false),
        MappingField("order_status",   "Order Status",    "Parcel status",                  false),
        MappingField("note",           "Special Note",    "Instructions / remarks",         false)
    )

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var spinnerBranch:       Spinner
    private lateinit var layoutNotConnected:  View
    private lateinit var layoutConnected:     View
    private lateinit var btnConnect:          Button
    private lateinit var btnReconnect:        Button
    private lateinit var btnDisconnect:       Button
    private lateinit var tvSheetName:         TextView
    private lateinit var tvSheetMeta:         TextView
    private lateinit var tvLastSync:          TextView
    private lateinit var spinnerSyncInterval: Spinner
    private lateinit var btnSyncNow:          Button
    private lateinit var cardMapping:         View
    private lateinit var tabModeManual:       TextView
    private lateinit var tabModeAuto:         TextView
    private lateinit var layoutAutoDetect:    View
    private lateinit var btnDetectHeaders:    Button
    private lateinit var rvColumnMapping:     RecyclerView

    // ── State ─────────────────────────────────────────────────────────────────
    private var branchIds   = listOf<String>()
    private var branchNames = listOf<String>()
    private var selectedBranchId = ""
    private var currentConfig: SheetConfig? = null
    private var mappingMode = "manual"
    private var detectedHeaders = listOf<String>()      // from auto-detect
    private val mappingSelections = mutableMapOf<String, String>()  // key → col/header

    private val syncOptions = listOf(15, 30, 60, 120)
    private val syncLabels  = listOf("15 min", "30 min", "1 hour", "2 hours")

    private val db   = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // ── Inflate ───────────────────────────────────────────────────────────────
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_config_sheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupSyncIntervalSpinner()
        setupMappingRecycler()
        loadBranches()
    }

    // ── Bind views ────────────────────────────────────────────────────────────
    private fun bindViews(view: View) {
        spinnerBranch       = view.findViewById(R.id.spinnerBranch)
        layoutNotConnected  = view.findViewById(R.id.layoutNotConnected)
        layoutConnected     = view.findViewById(R.id.layoutConnected)
        btnConnect          = view.findViewById(R.id.btnConnect)
        btnReconnect        = view.findViewById(R.id.btnReconnect)
        btnDisconnect       = view.findViewById(R.id.btnDisconnect)
        tvSheetName         = view.findViewById(R.id.tvSheetName)
        tvSheetMeta         = view.findViewById(R.id.tvSheetMeta)
        tvLastSync          = view.findViewById(R.id.tvLastSync)
        spinnerSyncInterval = view.findViewById(R.id.spinnerSyncInterval)
        btnSyncNow          = view.findViewById(R.id.btnSyncNow)
        cardMapping         = view.findViewById(R.id.cardMapping)
        tabModeManual       = view.findViewById(R.id.tabModeManual)
        tabModeAuto         = view.findViewById(R.id.tabModeAuto)
        layoutAutoDetect    = view.findViewById(R.id.layoutAutoDetect)
        btnDetectHeaders    = view.findViewById(R.id.btnDetectHeaders)
        rvColumnMapping     = view.findViewById(R.id.rvColumnMapping)

        btnConnect   .setOnClickListener { launchGoogleOAuth() }
        btnReconnect .setOnClickListener { launchGoogleOAuth() }
        btnDisconnect.setOnClickListener { confirmDisconnect() }
        btnSyncNow   .setOnClickListener { triggerManualSync() }
        btnDetectHeaders.setOnClickListener { detectHeaders() }

        tabModeManual.setOnClickListener { setMappingMode("manual") }
        tabModeAuto  .setOnClickListener { setMappingMode("auto") }
    }

    // ── Load branches from RbacManager ───────────────────────────────────────
    private fun loadBranches() {
        branchIds = RbacManager.current.branchIds
        if (branchIds.isEmpty()) {
            Toast.makeText(requireContext(), "No branches assigned to your account", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val names = branchIds.map { id ->
                runCatching {
                    db.reference.child("branches/$id/name").get().await()
                        .getValue(String::class.java) ?: id
                }.getOrDefault(id)
            }
            branchNames = names
            setupBranchSpinner()
        }
    }

    private fun setupBranchSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, branchNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerBranch.adapter = adapter
        spinnerBranch.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                selectedBranchId = branchIds[pos]
                loadBranchConfig(selectedBranchId)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ── Load config from Firebase ─────────────────────────────────────────────
    private fun loadBranchConfig(branchId: String) {
        lifecycleScope.launch {
            val snap = runCatching {
                db.reference.child("config/sheets/$branchId/current").get().await()
            }.getOrNull()

            if (snap == null || !snap.exists()) {
                currentConfig = null
                showNotConnectedState()
                return@launch
            }

            val cfg = SheetConfig(
                spreadsheetId   = snap.child("spreadsheet_id").getValue(String::class.java) ?: "",
                spreadsheetName = snap.child("spreadsheet_name").getValue(String::class.java) ?: "",
                accountEmail    = snap.child("account_email").getValue(String::class.java) ?: "",
                tabName         = snap.child("tab_name").getValue(String::class.java) ?: "",
                headerRow       = snap.child("header_row").getValue(Int::class.java) ?: 1,
                startRow        = snap.child("start_row").getValue(Int::class.java) ?: 2,
                endRow          = snap.child("end_row").getValue(Int::class.java) ?: 0,
                colStart        = snap.child("col_start").getValue(String::class.java) ?: "A",
                colEnd          = snap.child("col_end").getValue(String::class.java) ?: "H",
                mappingMode     = snap.child("mapping_mode").getValue(String::class.java) ?: "manual",
                agentIdCol      = snap.child("column_mapping/agent_id").getValue(String::class.java) ?: "",
                consignmentCol  = snap.child("column_mapping/consignment").getValue(String::class.java) ?: "",
                customerNameCol = snap.child("column_mapping/customer_name").getValue(String::class.java) ?: "",
                customerPhoneCol= snap.child("column_mapping/customer_phone").getValue(String::class.java) ?: "",
                addressCol      = snap.child("column_mapping/address").getValue(String::class.java) ?: "",
                codCol          = snap.child("column_mapping/cod").getValue(String::class.java) ?: "",
                orderStatusCol  = snap.child("column_mapping/order_status").getValue(String::class.java) ?: "",
                noteCol         = snap.child("column_mapping/note").getValue(String::class.java) ?: "",
                syncIntervalMin = snap.child("sync_interval_min").getValue(Int::class.java) ?: 30,
                updatedAt       = snap.child("updated_at").getValue(Long::class.java) ?: 0L
            )
            currentConfig = cfg
            showConnectedState(cfg)
        }
    }

    // ── UI states ─────────────────────────────────────────────────────────────
    private fun showNotConnectedState() {
        layoutNotConnected.visibility = View.VISIBLE
        layoutConnected   .visibility = View.GONE
        cardMapping       .visibility = View.GONE
    }

    private fun showConnectedState(cfg: SheetConfig) {
        layoutNotConnected.visibility = View.GONE
        layoutConnected   .visibility = View.VISIBLE
        cardMapping       .visibility = View.VISIBLE

        tvSheetName.text = cfg.spreadsheetName.ifBlank { cfg.spreadsheetId }
        tvSheetMeta.text = buildString {
            append(cfg.tabName)
            append(" · ${cfg.colStart}–${cfg.colEnd}")
            if (cfg.accountEmail.isNotBlank()) append(" · ${cfg.accountEmail}")
        }

        val lastSyncText = if (cfg.updatedAt > 0L) formatLastSync(cfg.updatedAt) else "Never"
        tvLastSync.text = "Last sync: $lastSyncText"

        // Sync interval spinner position
        val idx = syncOptions.indexOf(cfg.syncIntervalMin).takeIf { it >= 0 } ?: 1
        spinnerSyncInterval.setSelection(idx)

        // Restore mapping mode
        setMappingMode(cfg.mappingMode)

        // Restore mapping selections
        mappingSelections["agent_id"]        = cfg.agentIdCol
        mappingSelections["consignment"]     = cfg.consignmentCol
        mappingSelections["customer_name"]   = cfg.customerNameCol
        mappingSelections["customer_phone"]  = cfg.customerPhoneCol
        mappingSelections["address"]         = cfg.addressCol
        mappingSelections["cod"]             = cfg.codCol
        mappingSelections["order_status"]    = cfg.orderStatusCol
        mappingSelections["note"]            = cfg.noteCol

        rvColumnMapping.adapter?.notifyDataSetChanged()
    }

    // ── Sync interval spinner ─────────────────────────────────────────────────
    private fun setupSyncIntervalSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, syncLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSyncInterval.adapter = adapter
        spinnerSyncInterval.setSelection(1) // default 30 min
        spinnerSyncInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                saveSyncInterval(syncOptions[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun saveSyncInterval(minutes: Int) {
        if (selectedBranchId.isBlank() || currentConfig == null) return
        db.reference.child("config/sheets/$selectedBranchId/current/sync_interval_min")
            .setValue(minutes)
        appendHistory(action = "sync_interval_changed", extraNote = "${minutes}m")
    }

    // ── Mapping RecyclerView ──────────────────────────────────────────────────
    private fun setupMappingRecycler() {
        rvColumnMapping.layoutManager = LinearLayoutManager(requireContext())
        rvColumnMapping.adapter = MappingAdapter()
    }

    inner class MappingAdapter : RecyclerView.Adapter<MappingAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvRequired:    TextView = v.findViewById(R.id.tvRequired)
            val tvFieldName:   TextView = v.findViewById(R.id.tvFieldName)
            val tvFieldDesc:   TextView = v.findViewById(R.id.tvFieldDesc)
            val spinnerColumn: Spinner  = v.findViewById(R.id.spinnerColumn)
            val spinnerHeader: Spinner  = v.findViewById(R.id.spinnerHeader)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_column_mapping_row, parent, false))

        override fun getItemCount() = mappingFields.size

        override fun onBindViewHolder(vh: VH, pos: Int) {
            val field = mappingFields[pos]
            vh.tvRequired .visibility  = if (field.required) View.VISIBLE else View.INVISIBLE
            vh.tvFieldName.text        = field.label
            vh.tvFieldDesc.text        = field.description

            if (mappingMode == "manual") {
                vh.spinnerColumn.visibility = View.VISIBLE
                vh.spinnerHeader.visibility = View.GONE
                setupColumnSpinner(vh.spinnerColumn, field.key)
            } else {
                vh.spinnerColumn.visibility = View.GONE
                vh.spinnerHeader.visibility = View.VISIBLE
                setupHeaderSpinner(vh.spinnerHeader, field.key)
            }
        }

        private fun setupColumnSpinner(spinner: Spinner, fieldKey: String) {
            val cols = listOf("—") + ('A'..'Z').map { it.toString() }
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, cols)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
            val current = mappingSelections[fieldKey] ?: ""
            val idx = if (current.isBlank()) 0 else cols.indexOf(current).takeIf { it >= 0 } ?: 0
            spinner.setSelection(idx)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    val selected = if (position == 0) "" else cols[position]
                    mappingSelections[fieldKey] = selected
                    saveMapping()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        private fun setupHeaderSpinner(spinner: Spinner, fieldKey: String) {
            val options = listOf("— select header —") + detectedHeaders
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, options)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
            val current = mappingSelections[fieldKey] ?: ""
            val idx = if (current.isBlank()) 0 else options.indexOf(current).takeIf { it >= 0 } ?: 0
            spinner.setSelection(idx)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    val selected = if (position == 0) "" else options[position]
                    mappingSelections[fieldKey] = selected
                    saveMapping()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
    }

    // ── Mapping mode toggle ───────────────────────────────────────────────────
    private fun setMappingMode(mode: String) {
        mappingMode = mode
        val accentBg   = resources.getColor(R.color.theme_btn_accent_bg,   requireContext().theme)
        val accentText = resources.getColor(R.color.theme_btn_accent_text,  requireContext().theme)
        val transparent = android.graphics.Color.TRANSPARENT
        val secondary  = resources.getColor(R.color.theme_text_secondary,   requireContext().theme)

        if (mode == "manual") {
            tabModeManual.setBackgroundColor(accentBg);  tabModeManual.setTextColor(accentText)
            tabModeAuto  .setBackgroundColor(transparent); tabModeAuto.setTextColor(secondary)
            layoutAutoDetect.visibility = View.GONE
        } else {
            tabModeAuto  .setBackgroundColor(accentBg);  tabModeAuto.setTextColor(accentText)
            tabModeManual.setBackgroundColor(transparent); tabModeManual.setTextColor(secondary)
            layoutAutoDetect.visibility = View.VISIBLE
        }
        rvColumnMapping.adapter?.notifyDataSetChanged()
    }

    // ── Auto-detect headers ───────────────────────────────────────────────────
    private fun detectHeaders() {
        // TODO: call Google Sheets API with stored OAuth token
        // GET https://sheets.googleapis.com/v4/spreadsheets/{id}/values/{tab}!{col_start}{header_row}:{col_end}{header_row}
        // On success → detectedHeaders = response; rvColumnMapping.adapter?.notifyDataSetChanged()
        Toast.makeText(requireContext(), "Auto-detect: Google Sheets API integration coming soon", Toast.LENGTH_SHORT).show()
    }

    // ── Google OAuth ──────────────────────────────────────────────────────────
    private fun launchGoogleOAuth() {
        // TODO: launch Google Sign-In with Sheets readonly scope
        // GoogleSignInOptions.Builder(...)
        //   .requestScopes(Scope("https://www.googleapis.com/auth/spreadsheets.readonly"))
        //   .requestEmail()
        // On success → save account email + token, show ConnectFlow dialog
        Toast.makeText(requireContext(), "Google Sheets OAuth — coming soon", Toast.LENGTH_SHORT).show()
    }

    // ── Disconnect ────────────────────────────────────────────────────────────
    private fun confirmDisconnect() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Disconnect Sheet?")
            .setMessage("This branch এর sheet connection remove হবে। Audit history থাকবে।")
            .setPositiveButton("Disconnect") { _, _ -> disconnectSheet() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun disconnectSheet() {
        if (selectedBranchId.isBlank()) return
        appendHistory(action = "disconnected")
        db.reference.child("config/sheets/$selectedBranchId/current").removeValue()
        currentConfig = null
        showNotConnectedState()
        Toast.makeText(requireContext(), "Sheet disconnected", Toast.LENGTH_SHORT).show()
    }

    // ── Save mapping to Firebase ──────────────────────────────────────────────
    private fun saveMapping() {
        if (selectedBranchId.isBlank() || currentConfig == null) return
        val mappingRef = db.reference.child("config/sheets/$selectedBranchId/current/column_mapping")
        mappingRef.updateChildren(
            mapOf(
                "agent_id"       to (mappingSelections["agent_id"]       ?: ""),
                "consignment"    to (mappingSelections["consignment"]    ?: ""),
                "customer_name"  to (mappingSelections["customer_name"]  ?: ""),
                "customer_phone" to (mappingSelections["customer_phone"] ?: ""),
                "address"        to (mappingSelections["address"]        ?: ""),
                "cod"            to (mappingSelections["cod"]            ?: ""),
                "order_status"   to (mappingSelections["order_status"]   ?: ""),
                "note"           to (mappingSelections["note"]           ?: "")
            )
        )
        db.reference.child("config/sheets/$selectedBranchId/current/mapping_mode").setValue(mappingMode)
        appendHistory(action = "mapping_updated")
    }

    // ── Manual sync ───────────────────────────────────────────────────────────
    private fun triggerManualSync() {
        // TODO: trigger WorkManager one-time sync task for this branch
        Toast.makeText(requireContext(), "Manual sync — coming soon", Toast.LENGTH_SHORT).show()
    }

    // ── Audit history ─────────────────────────────────────────────────────────
    private fun appendHistory(action: String, extraNote: String = "") {
        if (selectedBranchId.isBlank()) return
        val user = auth.currentUser ?: return
        val entry = mutableMapOf<String, Any>(
            "action"           to action,
            "changed_at"       to System.currentTimeMillis(),
            "changed_by_uid"   to user.uid,
            "changed_by_name"  to (user.displayName ?: user.email ?: user.uid),
            "changed_by_role"  to RbacManager.current.roleId
        )
        if (extraNote.isNotBlank()) entry["note"] = extraNote
        db.reference.child("config/sheets/$selectedBranchId/history").push().setValue(entry)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun formatLastSync(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000L        -> "Just now"
            diff < 3_600_000L     -> "${diff / 60_000}m ago"
            diff < 86_400_000L    -> "${diff / 3_600_000}h ago"
            else                  -> "${diff / 86_400_000}d ago"
        }
    }
}
