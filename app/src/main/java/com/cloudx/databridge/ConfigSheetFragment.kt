package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 📊 Sheet Config Tab
 * JSX equivalent: SheetSection + ConnectFlow + ManagePanel components
 *
 * States (mirrors JSX):
 *   - BRANCH_LIST: show all branches with connect/manage button
 *   - CONNECTING:  ConnectFlow — step-by-step Google Sheet connect wizard
 *   - MANAGING:    ManagePanel — column mapping, sync, disconnect, reconnect
 *
 * Firebase:
 *   config/sheets/{branchId}/current/   ← active config
 *   config/sheets/{branchId}/history/   ← audit log
 *   config/sheets/{branchId}/data/      ← synced rows (read by WorkerFragment)
 */
class ConfigSheetFragment : Fragment() {

    private val db = FirebaseDatabase.getInstance()

    // ── State machine (mirrors JSX connectingFor / managingFor) ──────────────
    private enum class SheetState { BRANCH_LIST, CONNECTING, MANAGING }
    private var state           = SheetState.BRANCH_LIST
    private var activeBranch    = ""

    // Loaded from Firebase
    private var branches: List<String> = emptyList()
    data class SheetConnection(
        val branchId:    String = "",
        val sheetId:     String = "",
        val sheetName:   String = "",
        val tabName:     String = "",
        val columns:     List<String> = emptyList(),
        val connectedBy: String = "",
        val connectedAt: Long = 0L,
    )
    private var connections: MutableMap<String, SheetConnection> = mutableMapOf()

    // ── Root views ────────────────────────────────────────────────────────────
    private lateinit var rootContainer:      ViewGroup
    private lateinit var branchListLayout:   LinearLayout

    // ConnectFlow views
    private lateinit var connectFlowLayout:  View
    private lateinit var tvConnectBranch:    TextView
    private lateinit var etSheetId:          EditText
    private lateinit var etSheetName:        EditText
    private lateinit var etTabName:          EditText
    private lateinit var etColumns:          EditText
    private lateinit var btnConnectSave:     Button
    private lateinit var btnConnectCancel:   View
    private lateinit var tvConnectError:     TextView

    // ManagePanel views
    private lateinit var managePanelLayout:  View
    private lateinit var tvManageBranch:     TextView
    private lateinit var tvManageSheetInfo:  TextView
    private lateinit var tvColumnMapping:    TextView
    private lateinit var btnDisconnect:      Button
    private lateinit var btnReconnect:       Button
    private lateinit var btnManageBack:      View

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_config_sheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rootContainer     = view.findViewById(R.id.sheetRootContainer)
        branchListLayout  = view.findViewById(R.id.branchListContainer)
        connectFlowLayout = view.findViewById(R.id.connectFlowLayout)
        managePanelLayout = view.findViewById(R.id.managePanelLayout)

        // ConnectFlow references
        tvConnectBranch  = view.findViewById(R.id.tvConnectBranchName)
        etSheetId        = view.findViewById(R.id.etSheetId)
        etSheetName      = view.findViewById(R.id.etSheetName)
        etTabName        = view.findViewById(R.id.etSheetTabName)
        etColumns        = view.findViewById(R.id.etSheetColumns)
        btnConnectSave   = view.findViewById(R.id.btnConnectSave)
        btnConnectCancel = view.findViewById(R.id.btnConnectCancel)
        tvConnectError   = view.findViewById(R.id.tvConnectError)

        // ManagePanel references
        tvManageBranch   = view.findViewById(R.id.tvManageBranchName)
        tvManageSheetInfo= view.findViewById(R.id.tvManageSheetInfo)
        tvColumnMapping  = view.findViewById(R.id.tvColumnMappingInfo)
        btnDisconnect    = view.findViewById(R.id.btnDisconnect)
        btnReconnect     = view.findViewById(R.id.btnReconnect)
        btnManageBack    = view.findViewById(R.id.btnManageBack)

        btnConnectCancel.setOnClickListener { goToBranchList() }
        btnConnectSave  .setOnClickListener { handleConnect() }
        btnManageBack   .setOnClickListener { goToBranchList() }
        btnDisconnect   .setOnClickListener { handleDisconnect() }
        btnReconnect    .setOnClickListener {
            state = SheetState.CONNECTING
            renderState()
        }

        loadFromFirebase()
    }

    // ── State rendering ───────────────────────────────────────────────────────

    private fun renderState() {
        branchListLayout .visibility = View.GONE
        connectFlowLayout.visibility = View.GONE
        managePanelLayout.visibility = View.GONE

        when (state) {
            SheetState.BRANCH_LIST -> {
                branchListLayout.visibility = View.VISIBLE
                bindBranchList()
            }
            SheetState.CONNECTING -> {
                connectFlowLayout.visibility = View.VISIBLE
                tvConnectBranch.text = activeBranch
                tvConnectError.visibility = View.GONE
                // Pre-fill from existing connection if reconnecting
                val existing = connections[activeBranch]
                if (existing != null) {
                    etSheetId  .setText(existing.sheetId)
                    etSheetName.setText(existing.sheetName)
                    etTabName  .setText(existing.tabName)
                    etColumns  .setText(existing.columns.joinToString(", "))
                }
            }
            SheetState.MANAGING -> {
                managePanelLayout.visibility = View.VISIBLE
                bindManagePanel()
            }
        }
    }

    // ── Branch list (mirrors SheetSection branch list JSX) ───────────────────
    private fun bindBranchList() {
        branchListLayout.removeAllViews()
        if (branches.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "কোনো Branch পাওয়া যায়নি"
            tv.setPadding(16, 32, 16, 0)
            branchListLayout.addView(tv)
            return
        }
        branches.forEach { branch ->
            val conn = connections[branch]
            val row  = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_sheet_branch_row, branchListLayout, false)

            val tvName   = row.findViewById<TextView>(R.id.tvSheetBranchName)
            val tvInfo   = row.findViewById<TextView>(R.id.tvSheetBranchInfo)
            val btnAction = row.findViewById<Button>(R.id.btnSheetAction)

            val connected = conn != null
            tvName.text  = if (connected) "✅ $branch" else "○ $branch"
            tvName.setTextColor(android.graphics.Color.parseColor(if (connected) "#166534" else "#374151"))
            tvInfo.text  = if (connected) "${conn!!.sheetName} · ${conn.tabName} · ${conn.columns.size} cols" else ""
            tvInfo.visibility = if (connected) View.VISIBLE else View.GONE

            val cardBg = if (connected) "#F0FDF4" else "#FAFAFA"
            row.setBackgroundColor(android.graphics.Color.parseColor(cardBg))

            btnAction.text = if (connected) "Manage" else "Connect"
            btnAction.setTextColor(android.graphics.Color.parseColor(if (connected) "#16A34A" else "#E8380D"))

            btnAction.setOnClickListener {
                activeBranch = branch
                state = if (connected) SheetState.MANAGING else SheetState.CONNECTING
                renderState()
            }
            branchListLayout.addView(row)
        }
    }

    // ── ConnectFlow: handleConnect (mirrors JSX handleConnect) ────────────────
    private fun handleConnect() {
        tvConnectError.visibility = View.GONE
        val sheetId   = etSheetId.text.toString().trim()
        val sheetName = etSheetName.text.toString().trim()
        val tabName   = etTabName.text.toString().trim()
        val cols      = etColumns.text.toString().trim()
            .split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }

        if (sheetId.isEmpty())   { showConnectError("Sheet ID দিন"); return }
        if (sheetName.isEmpty()) { showConnectError("Sheet নাম দিন"); return }
        if (tabName.isEmpty())   { showConnectError("Tab নাম দিন"); return }
        if (cols.isEmpty())      { showConnectError(" can't find any columns"); return }

        val conn = SheetConnection(
            branchId    = activeBranch,
            sheetId     = sheetId,
            sheetName   = sheetName,
            tabName     = tabName,
            columns     = cols,
            connectedBy = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "",
            connectedAt = System.currentTimeMillis(),
        )
        connections[activeBranch] = conn
        saveConnectionToFirebase(conn)
        goToBranchList()
    }

    private fun showConnectError(msg: String) {
        tvConnectError.text = "⚠ $msg"
        tvConnectError.visibility = View.VISIBLE
    }

    // ── ManagePanel ───────────────────────────────────────────────────────────
    private fun bindManagePanel() {
        val conn = connections[activeBranch] ?: return
        tvManageBranch.text   = "Branch: $activeBranch"
        tvManageSheetInfo.text = "${conn.sheetName} · Tab: ${conn.tabName}"
        tvColumnMapping.text   = conn.columns.mapIndexed { i, c -> "${('A' + i)}: $c" }.joinToString("  ")
    }

    private fun handleDisconnect() {
        connections.remove(activeBranch)
        deleteConnectionFromFirebase(activeBranch)
        goToBranchList()
    }

    private fun goToBranchList() {
        state = SheetState.BRANCH_LIST
        renderState()
    }

    // ── Firebase ──────────────────────────────────────────────────────────────
    private fun loadFromFirebase() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Load branches list
                val branchSnap = db.reference.child("branches").get().await()
                val loaded = mutableListOf<String>()
                branchSnap.children.forEach { b ->
                    val name = b.child("name").getValue(String::class.java) ?: b.key ?: return@forEach
                    loaded.add(name)
                }
                if (loaded.isEmpty()) loaded.addAll(listOf("Mirpur-1", "Uttara-3", "Dhanmondi-2"))
                branches = loaded

                // Load sheet connections
                val sheetSnap = db.reference.child("config/sheets").get().await()
                sheetSnap.children.forEach { bs ->
                    val branchId  = bs.key ?: return@forEach
                    val cur       = bs.child("current")
                    val sheetId   = cur.child("sheetId").getValue(String::class.java)   ?: return@forEach
                    val sheetName = cur.child("sheetName").getValue(String::class.java) ?: ""
                    val tabName   = cur.child("tabName").getValue(String::class.java)   ?: ""
                    val colsRaw   = cur.child("columns").getValue(String::class.java)   ?: ""
                    val cols      = colsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val by        = cur.child("connectedBy").getValue(String::class.java) ?: ""
                    val at        = cur.child("connectedAt").getValue(Long::class.java)  ?: 0L
                    connections[branchId] = SheetConnection(branchId, sheetId, sheetName, tabName, cols, by, at)
                }
            } catch (_: Exception) {}
            if (isAdded) renderState()
        }
    }

    private fun saveConnectionToFirebase(conn: SheetConnection) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = mapOf(
                    "sheetId"     to conn.sheetId,
                    "sheetName"   to conn.sheetName,
                    "tabName"     to conn.tabName,
                    "columns"     to conn.columns.joinToString(","),
                    "connectedBy" to conn.connectedBy,
                    "connectedAt" to conn.connectedAt,
                )
                db.reference.child("config/sheets/${conn.branchId}/current").setValue(data).await()
                // Write audit log entry
                val histRef = db.reference.child("config/sheets/${conn.branchId}/history").push()
                histRef.setValue(data + mapOf("action" to "connected")).await()
            } catch (_: Exception) {}
        }
    }

    private fun deleteConnectionFromFirebase(branchId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                db.reference.child("config/sheets/$branchId/current").removeValue().await()
                val histRef = db.reference.child("config/sheets/$branchId/history").push()
                histRef.setValue(mapOf(
                    "action"         to "disconnected",
                    "disconnectedBy" to (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""),
                    "disconnectedAt" to System.currentTimeMillis(),
                )).await()
            } catch (_: Exception) {}
        }
    }
}
