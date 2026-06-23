package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 📊 Sheet Config Tab  —  mirrors JSX SheetSection + ConnectFlow + ManagePanel
 *
 * UX Flow:
 *   BRANCH_SELECT  → dropdown "Choose Branch" + Connect / Manage button
 *   CONNECTING     → 4-step wizard (Account → Sheet → Tab → Columns)
 *   MANAGING       → mini-tabs: Overview | Columns | Sync
 */
class ConfigSheetFragment : Fragment() {

    private val db   = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // ── State machine ─────────────────────────────────────────────────────────
    private enum class Screen { BRANCH_SELECT, CONNECTING, MANAGING }
    private var screen         = Screen.BRANCH_SELECT
    private var activeBranch   = ""
    private var connectStep    = 1   // 1=SheetId  2=Tab  3=Columns  4=Preview

    private var branches: List<String>                         = emptyList()
    private var connections: MutableMap<String, SheetConn>     = mutableMapOf()

    data class SheetConn(
        val branchId:    String = "",
        val sheetId:     String = "",
        val sheetName:   String = "",
        val tabName:     String = "",
        val colStart:    Int    = 1,
        val colEnd:      Int    = 10,
        val connectedBy: String = "",
        val connectedAt: Long   = 0L,
    ) {
        val columns: List<String> get() = ('A'..'Z').toList()
            .subList((colStart - 1).coerceIn(0, 25), colEnd.coerceIn(colStart, 26))
            .map { it.toString() }
    }

    // ── Root & shared views ───────────────────────────────────────────────────
    private lateinit var root:              FrameLayout

    /* Branch select panel */
    private lateinit var panelBranch:       LinearLayout
    private lateinit var spinnerBranch:     Spinner
    private lateinit var cardConnInfo:      LinearLayout
    private lateinit var tvConnInfoSheet:   TextView
    private lateinit var tvConnInfoTab:     TextView
    private lateinit var tvConnInfoCols:    TextView
    private lateinit var btnBranchAction:   Button   // "Connect" or "Manage"

    /* ConnectFlow panel */
    private lateinit var panelConnect:      View
    private lateinit var tvConnBranchSub:   TextView
    // Step indicators
    private lateinit var step1Dot:  View; private lateinit var step2Dot:  View
    private lateinit var step3Dot:  View; private lateinit var step4Dot:  View
    private lateinit var step1Line: View; private lateinit var step2Line: View; private lateinit var step3Line: View
    private lateinit var step1Lbl:  TextView; private lateinit var step2Lbl:  TextView
    private lateinit var step3Lbl:  TextView; private lateinit var step4Lbl:  TextView
    // Step views
    private lateinit var stepView1: View; private lateinit var stepView2: View
    private lateinit var stepView3: View; private lateinit var stepView4: View
    // Step-1 inputs
    private lateinit var etSheetId:    EditText
    private lateinit var etSheetName:  EditText
    // Step-2
    private lateinit var etTabName:    EditText
    // Step-3 columns
    private lateinit var etColStart:   EditText
    private lateinit var etColEnd:     EditText
    private lateinit var tvColPreview: TextView
    // Step-4 summary
    private lateinit var tvSummary:    TextView
    // Nav buttons
    private lateinit var btnBack:      Button
    private lateinit var btnNext:      Button
    private lateinit var btnConnect:   Button
    private lateinit var btnCancelConn:View
    private lateinit var tvConnError:  TextView

    /* ManagePanel */
    private lateinit var panelManage:      View
    private lateinit var tvManageBranch:   TextView
    private lateinit var tabOverview:      TextView; private lateinit var tabColumns:   TextView; private lateinit var tabSync: TextView
    private lateinit var indOverview:      View;     private lateinit var indColumns:   View;     private lateinit var indSync: View
    private lateinit var cardOverview:     View
    private lateinit var cardColumns:      View
    private lateinit var cardSync:         View
    private lateinit var tvOvSheet:        TextView; private lateinit var tvOvTab: TextView; private lateinit var tvOvCols: TextView
    private lateinit var tvColPreviewMgr:  TextView
    private lateinit var btnManReconnect:  Button;   private lateinit var btnManDisconn: Button
    private lateinit var btnManBack:       View
    private lateinit var btnSyncNow:       Button

    private var activeManageTab = "overview"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_config_sheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root            = view.findViewById(R.id.sheetRoot)

        // Branch select
        panelBranch     = view.findViewById(R.id.panelBranchSelect)
        spinnerBranch   = view.findViewById(R.id.spinnerBranch)
        cardConnInfo    = view.findViewById(R.id.cardConnInfo)
        tvConnInfoSheet = view.findViewById(R.id.tvConnInfoSheet)
        tvConnInfoTab   = view.findViewById(R.id.tvConnInfoTab)
        tvConnInfoCols  = view.findViewById(R.id.tvConnInfoCols)
        btnBranchAction = view.findViewById(R.id.btnBranchAction)

        // ConnectFlow
        panelConnect    = view.findViewById(R.id.panelConnect)
        tvConnBranchSub = view.findViewById(R.id.tvConnBranchSub)
        step1Dot  = view.findViewById(R.id.step1Dot);  step2Dot  = view.findViewById(R.id.step2Dot)
        step3Dot  = view.findViewById(R.id.step3Dot);  step4Dot  = view.findViewById(R.id.step4Dot)
        step1Line = view.findViewById(R.id.step1Line); step2Line = view.findViewById(R.id.step2Line); step3Line = view.findViewById(R.id.step3Line)
        step1Lbl  = view.findViewById(R.id.step1Lbl);  step2Lbl  = view.findViewById(R.id.step2Lbl)
        step3Lbl  = view.findViewById(R.id.step3Lbl);  step4Lbl  = view.findViewById(R.id.step4Lbl)
        stepView1 = view.findViewById(R.id.stepView1); stepView2 = view.findViewById(R.id.stepView2)
        stepView3 = view.findViewById(R.id.stepView3); stepView4 = view.findViewById(R.id.stepView4)
        etSheetId   = view.findViewById(R.id.etSheetId)
        etSheetName = view.findViewById(R.id.etSheetName)
        etTabName   = view.findViewById(R.id.etTabName)
        etColStart  = view.findViewById(R.id.etColStart)
        etColEnd    = view.findViewById(R.id.etColEnd)
        tvColPreview= view.findViewById(R.id.tvColPreview)
        tvSummary   = view.findViewById(R.id.tvSummary)
        btnBack     = view.findViewById(R.id.btnStepBack)
        btnNext     = view.findViewById(R.id.btnStepNext)
        btnConnect  = view.findViewById(R.id.btnStepConnect)
        btnCancelConn = view.findViewById(R.id.btnCancelConnect)
        tvConnError = view.findViewById(R.id.tvConnectError)

        // ManagePanel
        panelManage      = view.findViewById(R.id.panelManage)
        tvManageBranch   = view.findViewById(R.id.tvManageBranchInfo)
        tabOverview      = view.findViewById(R.id.tabOverview); tabColumns = view.findViewById(R.id.tabColumns); tabSync = view.findViewById(R.id.tabSync)
        indOverview      = view.findViewById(R.id.indOverview); indColumns = view.findViewById(R.id.indColumns); indSync = view.findViewById(R.id.indSync)
        cardOverview     = view.findViewById(R.id.cardOverview)
        cardColumns      = view.findViewById(R.id.cardColumns)
        cardSync         = view.findViewById(R.id.cardSync)
        tvOvSheet        = view.findViewById(R.id.tvOvSheet); tvOvTab = view.findViewById(R.id.tvOvTab); tvOvCols = view.findViewById(R.id.tvOvCols)
        tvColPreviewMgr  = view.findViewById(R.id.tvColPreviewMgr)
        btnManReconnect  = view.findViewById(R.id.btnManReconnect); btnManDisconn = view.findViewById(R.id.btnManDisconnect)
        btnManBack       = view.findViewById(R.id.btnManBack)
        btnSyncNow       = view.findViewById(R.id.btnSyncNow)

        // Listeners
        spinnerBranch.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == 0) { btnBranchAction.visibility = View.GONE; cardConnInfo.visibility = View.GONE; return }
                val branch = branches.getOrNull(pos - 1) ?: return
                activeBranch = branch
                updateBranchActionCard()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnBranchAction.setOnClickListener {
            val conn = connections[activeBranch]
            if (conn != null) { screen = Screen.MANAGING; render() }
            else              { screen = Screen.CONNECTING; connectStep = 1; clearConnectForm(); render() }
        }

        btnCancelConn.setOnClickListener { screen = Screen.BRANCH_SELECT; render() }
        btnManBack   .setOnClickListener { screen = Screen.BRANCH_SELECT; render() }

        btnNext.setOnClickListener { advanceStep() }
        btnBack.setOnClickListener { if (connectStep > 1) { connectStep--; renderConnectStep() } }
        btnConnect.setOnClickListener { handleConnect() }

        tabOverview.setOnClickListener { activeManageTab = "overview"; renderManageTabs() }
        tabColumns .setOnClickListener { activeManageTab = "columns";  renderManageTabs() }
        tabSync    .setOnClickListener { activeManageTab = "sync";     renderManageTabs() }

        btnManReconnect.setOnClickListener { screen = Screen.CONNECTING; connectStep = 1; prefillConnectForm(); render() }
        btnManDisconn  .setOnClickListener { handleDisconnect() }
        btnSyncNow     .setOnClickListener { toast("🔄 Sync শুরু হয়েছে...") }

        etColStart.addTextChangedListener(colWatcher); etColEnd.addTextChangedListener(colWatcher)

        loadFromFirebase()
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private fun render() {
        panelBranch .visibility = if (screen == Screen.BRANCH_SELECT) View.VISIBLE else View.GONE
        panelConnect.visibility = if (screen == Screen.CONNECTING)    View.VISIBLE else View.GONE
        panelManage .visibility = if (screen == Screen.MANAGING)      View.VISIBLE else View.GONE

        when (screen) {
            Screen.BRANCH_SELECT -> updateBranchSpinner()
            Screen.CONNECTING    -> { tvConnBranchSub.text = "Branch: $activeBranch"; renderConnectStep() }
            Screen.MANAGING      -> renderManagePanel()
        }
    }

    // ── Branch select ─────────────────────────────────────────────────────────

    private fun updateBranchSpinner() {
        val opts = listOf("শাখা বেছে নিন...") + branches
        spinnerBranch.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, opts)
        val sel = branches.indexOf(activeBranch)
        if (sel >= 0) spinnerBranch.setSelection(sel + 1)
        updateBranchActionCard()
    }

    private fun updateBranchActionCard() {
        val conn = connections[activeBranch]
        if (activeBranch.isEmpty()) {
            btnBranchAction.visibility = View.GONE
            cardConnInfo.visibility    = View.GONE
            return
        }
        btnBranchAction.visibility = View.VISIBLE
        if (conn != null) {
            cardConnInfo.visibility = View.VISIBLE
            tvConnInfoSheet.text    = "📄 ${conn.sheetName}"
            tvConnInfoTab.text      = "📑 Tab: ${conn.tabName}"
            tvConnInfoCols.text     = "📊 Columns: ${conn.columns.firstOrNull() ?: "A"}–${conn.columns.lastOrNull() ?: "J"} (${conn.columns.size}টি)"
            btnBranchAction.text    = "Manage"
            btnBranchAction.setBackgroundColor(android.graphics.Color.parseColor("#16A34A"))
        } else {
            cardConnInfo.visibility = View.GONE
            btnBranchAction.text    = "Connect করুন"
            btnBranchAction.setBackgroundColor(android.graphics.Color.parseColor("#E8380D"))
        }
    }

    // ── ConnectFlow steps ─────────────────────────────────────────────────────

    private fun renderConnectStep() {
        // Step views
        stepView1.visibility = if (connectStep == 1) View.VISIBLE else View.GONE
        stepView2.visibility = if (connectStep == 2) View.VISIBLE else View.GONE
        stepView3.visibility = if (connectStep == 3) View.VISIBLE else View.GONE
        stepView4.visibility = if (connectStep == 4) View.VISIBLE else View.GONE

        // Step dots  — done=red, active=orange ring, future=grey
        fun styleDot(dot: View, n: Int) {
            val done   = connectStep > n
            val active = connectStep == n
            val color  = when { done -> "#E8380D"; active -> "#FFF3F0"; else -> "#F3F4F6" }
            val border = when { done -> 0f;         active -> 2f;        else -> 2f }
            dot.setBackgroundColor(android.graphics.Color.parseColor(color))
        }
        listOf(step1Dot to 1, step2Dot to 2, step3Dot to 3, step4Dot to 4).forEach { (d, n) -> styleDot(d, n) }

        // Step lines  — filled if step > n
        val lineColor = android.graphics.Color.parseColor("#E8380D")
        val lineGrey  = android.graphics.Color.parseColor("#E5E7EB")
        step1Line.setBackgroundColor(if (connectStep > 1) lineColor else lineGrey)
        step2Line.setBackgroundColor(if (connectStep > 2) lineColor else lineGrey)
        step3Line.setBackgroundColor(if (connectStep > 3) lineColor else lineGrey)

        // Step labels
        val red  = android.graphics.Color.parseColor("#E8380D")
        val dark = android.graphics.Color.parseColor("#111827")
        val grey = android.graphics.Color.parseColor("#9CA3AF")
        listOf(step1Lbl to 1, step2Lbl to 2, step3Lbl to 3, step4Lbl to 4).forEach { (lbl, n) ->
            lbl.setTextColor(when { connectStep > n -> red; connectStep == n -> dark; else -> grey })
        }

        // Nav buttons
        btnBack.visibility    = if (connectStep > 1) View.VISIBLE else View.GONE
        btnNext.visibility    = if (connectStep < 4) View.VISIBLE else View.GONE
        btnConnect.visibility = if (connectStep == 4) View.VISIBLE else View.GONE

        // Update column preview in step 3
        if (connectStep == 3) updateColPreview()

        // Update summary in step 4
        if (connectStep == 4) updateSummary()

        tvConnError.visibility = View.GONE
    }

    private val colWatcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { updateColPreview() }
    }

    private fun updateColPreview() {
        val s = etColStart.text.toString().toIntOrNull() ?: return
        val e = etColEnd.text.toString().toIntOrNull()   ?: return
        if (s < 1 || e < s || e > 26) { tvColPreview.text = "⚠ Invalid range (1–26)"; return }
        val cols = ('A'..'Z').toList().subList(s - 1, e).map { it.toString() }
        tvColPreview.text = "Columns: ${cols.joinToString(", ")} (${cols.size}টি)"
    }

    private fun updateSummary() {
        val id   = etSheetId  .text.toString().trim()
        val name = etSheetName.text.toString().trim()
        val tab  = etTabName  .text.toString().trim()
        val s    = etColStart.text.toString().toIntOrNull() ?: 1
        val e    = etColEnd  .text.toString().toIntOrNull() ?: 10
        val cols = ('A'..'Z').toList().subList((s - 1).coerceIn(0, 25), e.coerceIn(s, 26)).map { it.toString() }
        tvSummary.text = "✅ Summary\n\nSheet: $name\nSheet ID: ${id.take(24)}…\nTab: $tab\nColumns: ${cols.firstOrNull() ?: "A"}–${cols.lastOrNull() ?: "J"} (${cols.size}টি)\nBranch: $activeBranch"
    }

    private fun advanceStep() {
        tvConnError.visibility = View.GONE
        when (connectStep) {
            1 -> {
                if (etSheetId.text.isBlank())   { showErr("Sheet ID দিন"); return }
                if (etSheetName.text.isBlank())  { showErr("Sheet নাম দিন"); return }
            }
            2 -> {
                if (etTabName.text.isBlank())    { showErr("Tab নাম দিন"); return }
            }
            3 -> {
                val s = etColStart.text.toString().toIntOrNull()
                val e = etColEnd  .text.toString().toIntOrNull()
                if (s == null || e == null || s < 1 || e < s || e > 26) { showErr("Valid column range দিন (1–26)"); return }
            }
        }
        connectStep++
        renderConnectStep()
    }

    private fun showErr(msg: String) {
        tvConnError.text = "⚠ $msg"
        tvConnError.visibility = View.VISIBLE
    }

    private fun handleConnect() {
        val sheetId   = etSheetId.text.toString().trim()
        val sheetName = etSheetName.text.toString().trim()
        val tabName   = etTabName.text.toString().trim()
        val s         = etColStart.text.toString().toIntOrNull() ?: 1
        val e         = etColEnd  .text.toString().toIntOrNull() ?: 10
        val conn = SheetConn(
            branchId    = activeBranch,
            sheetId     = sheetId,
            sheetName   = sheetName,
            tabName     = tabName,
            colStart    = s,
            colEnd      = e,
            connectedBy = auth.currentUser?.uid ?: "",
            connectedAt = System.currentTimeMillis(),
        )
        connections[activeBranch] = conn
        saveToFirebase(conn)
        toast("✅ ${activeBranch} connected!")
        screen = Screen.BRANCH_SELECT
        render()
    }

    private fun clearConnectForm() {
        etSheetId.setText(""); etSheetName.setText("")
        etTabName.setText(""); etColStart.setText("1"); etColEnd.setText("10")
    }

    private fun prefillConnectForm() {
        val conn = connections[activeBranch] ?: return
        etSheetId  .setText(conn.sheetId)
        etSheetName.setText(conn.sheetName)
        etTabName  .setText(conn.tabName)
        etColStart .setText(conn.colStart.toString())
        etColEnd   .setText(conn.colEnd.toString())
    }

    // ── ManagePanel ───────────────────────────────────────────────────────────

    private fun renderManagePanel() {
        val conn = connections[activeBranch] ?: return
        tvManageBranch.text = "Branch: $activeBranch"
        activeManageTab = "overview"
        renderManageTabs()

        tvOvSheet.text = conn.sheetName
        tvOvTab  .text = conn.tabName
        tvOvCols .text = "${conn.columns.firstOrNull() ?: "A"}–${conn.columns.lastOrNull() ?: "J"} (${conn.columns.size}টি)"
        tvColPreviewMgr.text = conn.columns.mapIndexed { i, c -> "$c: Col${i+1}" }.joinToString("  ·  ")
    }

    private fun renderManageTabs() {
        val red  = android.graphics.Color.parseColor("#E8380D")
        val grey = android.graphics.Color.parseColor("#6B7280")
        tabOverview.setTextColor(if (activeManageTab == "overview") red else grey)
        tabColumns .setTextColor(if (activeManageTab == "columns")  red else grey)
        tabSync    .setTextColor(if (activeManageTab == "sync")     red else grey)
        indOverview.visibility = if (activeManageTab == "overview") View.VISIBLE else View.INVISIBLE
        indColumns .visibility = if (activeManageTab == "columns")  View.VISIBLE else View.INVISIBLE
        indSync    .visibility = if (activeManageTab == "sync")     View.VISIBLE else View.INVISIBLE
        cardOverview.visibility = if (activeManageTab == "overview") View.VISIBLE else View.GONE
        cardColumns .visibility = if (activeManageTab == "columns")  View.VISIBLE else View.GONE
        cardSync    .visibility = if (activeManageTab == "sync")     View.VISIBLE else View.GONE
    }

    private fun handleDisconnect() {
        val branch = activeBranch
        connections.remove(branch)
        deleteFromFirebase(branch)
        toast("🗑️ $branch disconnected")
        screen = Screen.BRANCH_SELECT
        render()
    }

    // ── Firebase ──────────────────────────────────────────────────────────────

    private fun loadFromFirebase() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val branchSnap = db.reference.child("branches").get().await()
                val loaded = mutableListOf<String>()
                branchSnap.children.forEach { b ->
                    val name = b.child("name").getValue(String::class.java) ?: b.key ?: return@forEach
                    loaded.add(name)
                }
                if (loaded.isEmpty()) loaded.addAll(listOf("Mirpur-1", "Uttara-3", "Dhanmondi-2"))
                branches = loaded

                val sheetSnap = db.reference.child("config/sheets").get().await()
                sheetSnap.children.forEach { bs ->
                    val branchId  = bs.key ?: return@forEach
                    val cur       = bs.child("current")
                    val sheetId   = cur.child("sheetId")  .getValue(String::class.java) ?: return@forEach
                    val sheetName = cur.child("sheetName").getValue(String::class.java) ?: ""
                    val tabName   = cur.child("tabName")  .getValue(String::class.java) ?: ""
                    val colS      = cur.child("colStart") .getValue(Int::class.java)    ?: 1
                    val colE      = cur.child("colEnd")   .getValue(Int::class.java)    ?: 10
                    val by        = cur.child("connectedBy").getValue(String::class.java) ?: ""
                    val at        = cur.child("connectedAt").getValue(Long::class.java)   ?: 0L
                    connections[branchId] = SheetConn(branchId, sheetId, sheetName, tabName, colS, colE, by, at)
                }
            } catch (_: Exception) {}
            if (isAdded) render()
        }
    }

    private fun saveToFirebase(conn: SheetConn) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = mapOf(
                    "sheetId"     to conn.sheetId,
                    "sheetName"   to conn.sheetName,
                    "tabName"     to conn.tabName,
                    "colStart"    to conn.colStart,
                    "colEnd"      to conn.colEnd,
                    "connectedBy" to conn.connectedBy,
                    "connectedAt" to conn.connectedAt,
                )
                db.reference.child("config/sheets/${conn.branchId}/current").setValue(data).await()
                db.reference.child("config/sheets/${conn.branchId}/history").push()
                    .setValue(data + mapOf("action" to "connected")).await()
            } catch (_: Exception) {}
        }
    }

    private fun deleteFromFirebase(branchId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                db.reference.child("config/sheets/$branchId/current").removeValue().await()
                db.reference.child("config/sheets/$branchId/history").push().setValue(mapOf(
                    "action"         to "disconnected",
                    "disconnectedBy" to (auth.currentUser?.uid ?: ""),
                    "disconnectedAt" to System.currentTimeMillis(),
                )).await()
            } catch (_: Exception) {}
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
