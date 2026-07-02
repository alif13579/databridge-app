package com.cloudx.databridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 📊 Sheet Config Tab  —  mirrors JSX SheetSection + ConnectFlow + ManagePanel
 *
 * UX Flow (JSX 1:1):
 *   BRANCH_SELECT  → dropdown "Choose Branch" + Connect / Manage button
 *   CONNECTING     → 4-step wizard (Account → Sheet → Tab → Columns)
 *                    Step 1: Google account chooser (real OAuth popup)
 *                    Step 2: Sheet dropdown (from Drive API)
 *                    Step 3: Tab dropdown (from Sheets API)
 *                    Step 4: Column range + summary
 *   MANAGING       → mini-tabs: Overview | Columns | Sync
 */
class ConfigSheetFragment : Fragment() {

    companion object {
        private const val SCOPE_DRIVE  = "https://www.googleapis.com/auth/drive.readonly"
        private const val SCOPE_SHEETS = "https://www.googleapis.com/auth/spreadsheets.readonly"
        private const val OAUTH_SCOPE  = "oauth2:$SCOPE_DRIVE $SCOPE_SHEETS"
    }

    private val db   = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private var googleSignInClient: GoogleSignInClient? = null

    // ── State machine ─────────────────────────────────────────────────
    private enum class Screen { BRANCH_SELECT, CONNECTING, MANAGING }
    private var screen       = Screen.BRANCH_SELECT
    private var activeBranch = ""
    private var connectStep  = 1   // 1=Account  2=Sheet  3=Tab  4=Columns

    private var branches:    List<String>                  = emptyList()
    private var branchInfos: Map<String, BranchInfo>       = emptyMap()
    private var connections: MutableMap<String, MutableList<SheetConn>> = mutableMapOf()
    private var activeConnectionId = ""   // connectionId of the conn being managed

    // ── Connect flow state (mirrors JSX) ──────────────────────────────
    private var googleAccount:    GoogleSignInAccount? = null
    private var availableSheets:  List<DriveFile>      = emptyList()
    private var selectedSheet:    DriveFile?           = null
    private var availableTabs:    List<String>         = emptyList()
    private var selectedTab:      String               = ""

    data class DriveFile(val id: String, val name: String) {
        override fun toString() = name
    }

    data class BranchInfo(
        val id: String = "",
        val name: String = "",
        val code: String = "",
        val address: String = "",
        val type: String = "",
        val status: String = "",
    )

    data class SheetConn(
        val connectionId:   String  = "",   // Firebase push key
        val nickname:       String  = "",   // user-defined label
        val branchId:       String  = "",
        val sheetId:        String  = "",
        val sheetName:      String  = "",
        val tabName:        String  = "",
        val colStart:       Int     = 1,
        val colEnd:         Int     = 10,
        val startRow:       Int?    = null,
        val endRow:         Int?    = null,
        val autoSync:       Boolean = false,
        val syncIntervalMin:Int     = 30,
        val googleEmail:    String  = "",
        val connectedBy:    String  = "",
        val connectedAt:    Long    = 0L,
        val columnMapping:  Map<String, String> = emptyMap(), // firebaseField → colLetter
        val targetNode:     String  = "courier/consignments",
    ) {
        val columns: List<String> get() = (colStart..colEnd).map { n ->
            var num = n; var result = ""
            while (num > 0) { val rem = (num - 1) % 26; result = ('A' + rem) + result; num = (num - 1) / 26 }
            result
        }
    }

    // ── Views ─────────────────────────────────────────────────────────
    private var root: FrameLayout? = null

    /* Branch select panel */
    private var panelBranch:     View? = null
    private var tvBranchLabel:   TextView? = null
    private var spinnerBranch:   Spinner? = null
    private var tvSingleBranch:  TextView? = null
    private var tvBranchEmpty:   TextView? = null
    private var layoutBranchTabs:     View? = null
    private var tabBranchConnected:   TextView? = null
    private var tabBranchUnconnected: TextView? = null
    private var activeBranchTab = "connected" // "connected" | "unconnected"
    private var expandedBranch: String? = null  // accordion: which branch is expanded
    private var cardBranchInfo:  LinearLayout? = null
    private var tvBranchInfoName: TextView? = null
    private var tvBranchInfoCode: TextView? = null
    private var tvBranchInfoAddress: TextView? = null
    private var tvBranchInfoType: TextView? = null
    private var tvBranchInfoStatus: TextView? = null
    private var cardConnInfo:    LinearLayout? = null
    private var tvConnInfoSheet: TextView? = null
    private var tvConnInfoTab:   TextView? = null
    private var tvConnInfoCols:  TextView? = null
    private var btnBranchAction: Button? = null
    private var sheetBusyOverlay: View? = null
    private var tvSheetBusy: TextView? = null
    // Branch sections
    private var sectionConnected:          LinearLayout? = null
    private var containerConnectedBranches: LinearLayout? = null
    private var sectionUnconnected:        LinearLayout? = null

    /* ConnectFlow */
    private var panelConnect:        View? = null
    private var tvConnBranchSub:     TextView? = null
    private var step1Dot:  TextView? = null; private var step2Dot:  TextView? = null
    private var step3Dot:  TextView? = null; private var step4Dot:  TextView? = null; private var step5Dot: TextView? = null
    private var step1Line: View? = null; private var step2Line: View? = null; private var step3Line: View? = null; private var step4Line: View? = null
    private var step1Lbl:  TextView? = null; private var step2Lbl:  TextView? = null
    private var step3Lbl:  TextView? = null; private var step4Lbl:  TextView? = null; private var step5Lbl: TextView? = null
    private var stepView1: View? = null; private var stepView2: View? = null
    private var stepView3: View? = null; private var stepView4: View? = null; private var stepView5: View? = null
    private var containerMapping: android.widget.LinearLayout? = null
    private var etTargetNode:     EditText? = null
    private var btnFetchFields:   android.widget.Button? = null
    private var pbFetchFields:    ProgressBar? = null
    private var tvFetchStatus:    TextView? = null
    private var btnAddMappingField: TextView? = null

    // Step 1 - Account picker
    private var cardSelectedAccount:   View? = null
    private var tvSelectedAccountName: TextView? = null
    private var tvSelectedAccountEmail:TextView? = null
    private var btnPickAccount:        View? = null
    private var tvPickAccountLabel:    TextView? = null

    // Step 2 - Sheet picker (searchable dialog)
    private var tvSelectedSheet: TextView? = null
    private var pbSheetLoad:     ProgressBar? = null

    // Step 3 - Tab spinner
    private var spinnerTab: Spinner? = null
    private var pbTabLoad:  ProgressBar? = null

    // Step 4 - column range + live preview + summary
    private var etColStart:      EditText? = null
    private var etColEnd:        EditText? = null
    private var btnDefineRow:    TextView? = null
    private var layoutRowRange:  View? = null
    private var etStartRow:      EditText? = null
    private var etEndRow:        EditText? = null
    private var isRowRangeVisible = false
    private var tvColPreview:    TextView? = null
    private var tvLivePreview:   TextView? = null
    private var scrollLivePreview: HorizontalScrollView? = null
    private var tableLivePreview: TableLayout? = null
    private var pbPreviewLoad:   ProgressBar? = null
    private var pbColPreviewMgr: ProgressBar? = null
    private var tvSummary:       TextView? = null

    // Nav buttons
    private var btnBack:    Button? = null
    private var btnNext:    Button? = null
    private var btnConnect: Button? = null
    private var btnCancelConn: View? = null
    private var etNickname:    EditText? = null
    private var tvConnError: TextView? = null

    /* ManagePanel */
    private var panelManage:     View? = null
    private var tvManageBranch:  TextView? = null
    private var spinnerManageBranch: Spinner? = null
    private var tabOverview:     TextView? = null; private var tabColumns: TextView? = null; private var tabSync: TextView? = null
    private var indOverview:     View? = null;     private var indColumns: View? = null;     private var indSync: View? = null
    private var cardOverview:    View? = null
    private var cardColumns:     View? = null
    private var cardSync:        View? = null
    private var tvOvSheet:       TextView? = null; private var tvOvTab: TextView? = null; private var tvOvCols: TextView? = null
    private var tvColPreviewMgr:     TextView? = null
    private var scrollColPreviewMgr: android.widget.HorizontalScrollView? = null
    private var tableColPreviewMgr:  android.widget.TableLayout? = null
    private var btnColChange:    Button? = null
    private var btnManReconnect: Button? = null;   private var btnManDisconn: Button? = null
    private var btnManBack:      View? = null
    private var btnSyncNow:      Button? = null
    private var switchAutoSync:  android.widget.Switch? = null
    private var btnSyncGear:     android.widget.ImageView? = null
    private var tvSyncIntervalLabel: TextView? = null
    private var tvLastSynced:    TextView? = null

    private var activeManageTab = "overview"
    private var previewJob: kotlinx.coroutines.Job? = null
    private var isRangeEdit = false   // true = opened from Manage → Positioning, not full reconnect
    private var selectedNickname = ""   // nickname entered in step 3

    // Step 5 — column mapping
    // Firebase field → column letter selected by user
    private val pendingMapping = mutableMapOf<String, String>()
    private var targetNode = "courier/consignments"
    // Custom fields added manually via "+ Add Field" — fieldName to label
    private val customMappingFields = mutableListOf<Pair<String, String>>()
    // Headers fetched from sheet (letter → header text)
    private var sheetHeaders: Map<String, String> = emptyMap()

    // All Firebase fields for orders/
    // Dynamic keys fetched from Firebase node — replaces hardcoded mappingFields
    private val fetchedNodeKeys = mutableListOf<String>()  // keys from Firebase first record

    // Activity-result launcher for Google Sign-In
    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: androidx.activity.result.ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            handleSignInResult(result.data)
        } else {
            // User cancelled — silently
        }
    }

    // Recovery launcher (in case getToken throws UserRecoverableAuthException)
    private val recoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadSheetsForAccount()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestProfile()
                .requestScopes(Scope(SCOPE_DRIVE), Scope(SCOPE_SHEETS))
                .build()
            googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)

            // Restore last signed-in account if scopes still granted
            GoogleSignIn.getLastSignedInAccount(requireContext())?.let { acc ->
                if (GoogleSignIn.hasPermissions(acc, Scope(SCOPE_DRIVE), Scope(SCOPE_SHEETS))) {
                    googleAccount = acc
                }
            }
        } catch (_: Exception) { /* defensive: never crash on init */ }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_config_sheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            bindViews(view)
            attachListeners()
            render()
            loadFromFirebase()
        } catch (e: Exception) {
            toast("Sheet tab load error: ${e.message ?: "unknown"}")
        }
    }

    private fun bindViews(view: View) {
        root            = view.findViewById(R.id.sheetRoot)

        // Branch select
        panelBranch     = view.findViewById(R.id.panelBranchSelect)
        tvBranchLabel   = view.findViewById(R.id.tvBranchLabel)
        spinnerBranch   = view.findViewById(R.id.spinnerBranch)
        tvSingleBranch  = view.findViewById(R.id.tvSingleBranch)
        tvBranchEmpty   = view.findViewById(R.id.tvBranchEmpty)
        cardBranchInfo  = view.findViewById(R.id.cardBranchInfo)
        tvBranchInfoName = view.findViewById(R.id.tvBranchInfoName)
        tvBranchInfoCode = view.findViewById(R.id.tvBranchInfoCode)
        tvBranchInfoAddress = view.findViewById(R.id.tvBranchInfoAddress)
        tvBranchInfoType = view.findViewById(R.id.tvBranchInfoType)
        tvBranchInfoStatus = view.findViewById(R.id.tvBranchInfoStatus)
        cardConnInfo    = view.findViewById(R.id.cardConnInfo)
        tvConnInfoSheet = view.findViewById(R.id.tvConnInfoSheet)
        tvConnInfoTab   = view.findViewById(R.id.tvConnInfoTab)
        tvConnInfoCols  = view.findViewById(R.id.tvConnInfoCols)
        btnBranchAction = view.findViewById(R.id.btnBranchAction)
        sheetBusyOverlay = view.findViewById(R.id.sheetBusyOverlay)
        tvSheetBusy = view.findViewById(R.id.tvSheetBusy)
        sectionConnected           = view.findViewById(R.id.sectionConnected)
        containerConnectedBranches = view.findViewById(R.id.containerConnectedBranches)
        sectionUnconnected         = view.findViewById(R.id.sectionUnconnected)
        layoutBranchTabs           = view.findViewById(R.id.layoutBranchTabs)
        tabBranchConnected         = view.findViewById(R.id.tabBranchConnected)
        tabBranchUnconnected       = view.findViewById(R.id.tabBranchUnconnected)

        // ConnectFlow
        panelConnect    = view.findViewById(R.id.panelConnect)
        tvConnBranchSub = view.findViewById(R.id.tvConnBranchSub)
        step1Dot  = view.findViewById<TextView>(R.id.step1Dot);  step2Dot  = view.findViewById<TextView>(R.id.step2Dot)
        step3Dot  = view.findViewById<TextView>(R.id.step3Dot);  step4Dot  = view.findViewById<TextView>(R.id.step4Dot); step5Dot = view.findViewById(R.id.step5Dot)
        step1Line = view.findViewById(R.id.step1Line); step2Line = view.findViewById(R.id.step2Line); step3Line = view.findViewById(R.id.step3Line); step4Line = view.findViewById(R.id.step4Line)
        step1Lbl  = view.findViewById(R.id.step1Lbl);  step2Lbl  = view.findViewById(R.id.step2Lbl)
        step3Lbl  = view.findViewById(R.id.step3Lbl);  step4Lbl  = view.findViewById(R.id.step4Lbl); step5Lbl = view.findViewById(R.id.step5Lbl)
        stepView1 = view.findViewById(R.id.stepView1); stepView2 = view.findViewById(R.id.stepView2)
        stepView3 = view.findViewById(R.id.stepView3); stepView4 = view.findViewById(R.id.stepView4); stepView5 = view.findViewById(R.id.stepView5)
        containerMapping    = view.findViewById(R.id.containerMapping)
        etTargetNode        = view.findViewById(R.id.etTargetNode)
        btnFetchFields      = view.findViewById(R.id.btnFetchFields)
        pbFetchFields       = view.findViewById(R.id.pbFetchFields)
        tvFetchStatus       = view.findViewById(R.id.tvFetchStatus)
        btnAddMappingField  = view.findViewById(R.id.btnAddMappingField)

        cardSelectedAccount    = view.findViewById(R.id.cardSelectedAccount)
        tvSelectedAccountName  = view.findViewById(R.id.tvSelectedAccountName)
        tvSelectedAccountEmail = view.findViewById(R.id.tvSelectedAccountEmail)
        btnPickAccount         = view.findViewById(R.id.btnPickAccount)
        tvPickAccountLabel     = view.findViewById(R.id.tvPickAccountLabel)

        tvSelectedSheet = view.findViewById(R.id.tvSelectedSheet)
        pbSheetLoad     = view.findViewById(R.id.pbSheetLoad)
        spinnerTab   = view.findViewById(R.id.spinnerTab)
        pbTabLoad    = view.findViewById(R.id.pbTabLoad)

        etColStart     = view.findViewById(R.id.etColStart)
        etColEnd       = view.findViewById(R.id.etColEnd)
        btnDefineRow   = view.findViewById(R.id.btnDefineRow)
        layoutRowRange = view.findViewById(R.id.layoutRowRange)
        etStartRow     = view.findViewById(R.id.etStartRow)
        etEndRow       = view.findViewById(R.id.etEndRow)
        tvColPreview   = view.findViewById(R.id.tvColPreview)
        tvLivePreview  = view.findViewById(R.id.tvLivePreview)
        scrollLivePreview = view.findViewById(R.id.scrollLivePreview)
        tableLivePreview = view.findViewById(R.id.tableLivePreview)
        pbPreviewLoad  = view.findViewById(R.id.pbPreviewLoad)
        pbColPreviewMgr = view.findViewById(R.id.pbColPreviewMgr)
        tvSummary      = view.findViewById(R.id.tvSummary)

        etNickname    = view.findViewById(R.id.etNickname)
        btnBack       = view.findViewById(R.id.btnStepBack)
        btnNext       = view.findViewById(R.id.btnStepNext)
        btnConnect    = view.findViewById(R.id.btnStepConnect)
        btnCancelConn = view.findViewById(R.id.btnCancelConnect)
        tvConnError   = view.findViewById(R.id.tvConnectError)

        // ManagePanel
        panelManage         = view.findViewById(R.id.panelManage)
        tvManageBranch      = view.findViewById(R.id.tvManageBranchInfo)
        spinnerManageBranch = view.findViewById(R.id.spinnerManageBranch)
        tabOverview      = view.findViewById(R.id.tabOverview); tabColumns = view.findViewById(R.id.tabColumns); tabSync = view.findViewById(R.id.tabSync)
        indOverview      = view.findViewById(R.id.indOverview); indColumns = view.findViewById(R.id.indColumns); indSync = view.findViewById(R.id.indSync)
        cardOverview     = view.findViewById(R.id.cardOverview)
        cardColumns      = view.findViewById(R.id.cardColumns)
        cardSync         = view.findViewById(R.id.cardSync)
        tvOvSheet        = view.findViewById(R.id.tvOvSheet); tvOvTab = view.findViewById(R.id.tvOvTab); tvOvCols = view.findViewById(R.id.tvOvCols)
        tvColPreviewMgr      = view.findViewById(R.id.tvColPreviewMgr)
        scrollColPreviewMgr  = view.findViewById(R.id.scrollColPreviewMgr)
        tableColPreviewMgr   = view.findViewById(R.id.tableColPreviewMgr)
        btnColChange     = view.findViewById(R.id.btnColChange)
        btnManReconnect  = view.findViewById(R.id.btnManReconnect); btnManDisconn = view.findViewById(R.id.btnManDisconnect)
        btnManBack       = view.findViewById(R.id.btnManBack)
        btnSyncNow           = view.findViewById(R.id.btnSyncNow)
        switchAutoSync       = view.findViewById(R.id.switchAutoSync)
        btnSyncGear          = view.findViewById(R.id.btnSyncGear)
        tvSyncIntervalLabel  = view.findViewById(R.id.tvSyncIntervalLabel)
        tvLastSynced         = view.findViewById(R.id.tvLastSynced)
    }

    private fun attachListeners() {
        spinnerBranch?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == 0) {
                    activeBranch = ""
                    btnBranchAction?.visibility = View.GONE
                    cardConnInfo?.visibility    = View.GONE
                    return
                }
                val unconnected = branches.filter { connections[it].isNullOrEmpty() }
                val branch = unconnected.getOrNull(pos - 1) ?: return
                activeBranch = branch
                updateBranchActionCard()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnBranchAction?.setOnClickListener {
            if (activeBranch.isEmpty()) return@setOnClickListener
            val conn = activeConn()
            if (conn != null) { activeConnectionId = conn.connectionId; screen = Screen.MANAGING; render() }
            else              { screen = Screen.CONNECTING; connectStep = 1; clearConnectForm(); render() }
        }

        btnCancelConn?.setOnClickListener {
            if (isRangeEdit) {
                isRangeEdit = false
                screen = Screen.MANAGING
            } else {
                screen = Screen.BRANCH_SELECT
            }
            render()
        }
        btnManBack?.setOnClickListener    { screen = Screen.BRANCH_SELECT; render() }

        btnNext?.setOnClickListener { advanceStep() }
        btnBack?.setOnClickListener { if (connectStep > 1) { connectStep--; renderConnectStep() } }
        btnConnect?.setOnClickListener { handleConnect() }

        btnPickAccount?.setOnClickListener { pickGoogleAccount() }

        tvSelectedSheet?.setOnClickListener { openSheetPickerDialog() }

        spinnerTab?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos <= 0) { selectedTab = ""; return }
                selectedTab = availableTabs.getOrNull(pos - 1) ?: ""
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        tabOverview?.setOnClickListener { activeManageTab = "overview"; renderManageTabs() }
        tabColumns?.setOnClickListener  { activeManageTab = "columns";  renderManageTabs(); fetchManageColPreview() }
        tabSync?.setOnClickListener     { activeManageTab = "sync";     renderManageTabs() }

        spinnerManageBranch?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val connected = branches.filter { connections.containsKey(it) }
                val picked = connected.getOrNull(pos) ?: return
                if (picked != activeBranch) { activeBranch = picked; renderManagePanel() }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnManReconnect?.setOnClickListener { screen = Screen.CONNECTING; connectStep = 1; prefillConnectForm(); render() }
        btnColChange?.setOnClickListener { openRangeEditor() }
        btnManDisconn?.setOnClickListener   { handleDisconnect() }

        btnFetchFields?.setOnClickListener {
            val node = etTargetNode?.text?.toString()?.trim() ?: ""
            if (node.isBlank()) { showErr("Node path দিন"); return@setOnClickListener }
            targetNode = node
            fetchNodeKeys(node)
        }

        btnAddMappingField?.setOnClickListener { showAddFieldDialog() }
        btnSyncNow?.setOnClickListener {
            val conn = activeConn() ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch { syncSheetToFirebase(conn) }
        }

        switchAutoSync?.setOnCheckedChangeListener { _, isChecked ->
            val conn = activeConn() ?: return@setOnCheckedChangeListener
            val updated = conn.copy(autoSync = isChecked)
            updateActiveConn(updated)
            updateSyncGearState(isChecked)
            saveSyncSettings(updated)
        }

        btnSyncGear?.setOnClickListener {
            val conn = activeConn() ?: return@setOnClickListener
            if (!conn.autoSync) return@setOnClickListener
            openIntervalPickerDialog(conn)
        }

        etColStart?.addTextChangedListener(colWatcher); etColEnd?.addTextChangedListener(colWatcher)
        etStartRow?.addTextChangedListener(colWatcher); etEndRow?.addTextChangedListener(colWatcher)

        btnDefineRow?.setOnClickListener {
            isRowRangeVisible = !isRowRangeVisible
            layoutRowRange?.visibility = if (isRowRangeVisible) View.VISIBLE else View.GONE
            btnDefineRow?.text = if (isRowRangeVisible) "− Hide Row Range" else "+ Define Row Range"
            if (!isRowRangeVisible) {
                etStartRow?.setText("")
                etEndRow?.setText("")
                scheduleLivePreview() // reset preview to default range
            }
        }
    }

    // ── Render ────────────────────────────────────────────────────────
    private fun render() {
        if (!isAdded) return
        panelBranch?.visibility  = if (screen == Screen.BRANCH_SELECT) View.VISIBLE else View.GONE
        panelConnect?.visibility = if (screen == Screen.CONNECTING)    View.VISIBLE else View.GONE
        panelManage?.visibility  = if (screen == Screen.MANAGING)      View.VISIBLE else View.GONE

        when (screen) {
            Screen.BRANCH_SELECT -> updateBranchSpinner()
            Screen.CONNECTING    -> { tvConnBranchSub?.text = "Branch: ${branchLabel(activeBranch)}"; renderConnectStep() }
            Screen.MANAGING      -> renderManagePanel()
        }
    }

    // ── Branch select ─────────────────────────────────────────────────
    private fun updateBranchSpinner() {
        val ctx = context ?: return

        if (branches.isEmpty()) {
            tvBranchEmpty?.visibility      = View.VISIBLE
            layoutBranchTabs?.visibility   = View.GONE
            sectionConnected?.visibility   = View.GONE
            sectionUnconnected?.visibility = View.GONE
            return
        }

        tvBranchEmpty?.visibility = View.GONE

        val connectedBranches   = branches.filter { connections[it]?.isNotEmpty() == true }
        val unconnectedBranches = branches.filter { connections[it].isNullOrEmpty() }
        val hasBoth = connectedBranches.isNotEmpty() && unconnectedBranches.isNotEmpty()

        // ── Tab row ───────────────────────────────────────────────────
        if (hasBoth) {
            layoutBranchTabs?.visibility = View.VISIBLE
            if (activeBranchTab != "unconnected") activeBranchTab = "connected"
            updateBranchTabStyles()
            tabBranchConnected?.setOnClickListener {
                activeBranchTab = "connected"
                updateBranchTabStyles()
                renderBranchSections(ctx, connectedBranches, unconnectedBranches)
            }
            tabBranchUnconnected?.setOnClickListener {
                activeBranchTab = "unconnected"
                updateBranchTabStyles()
                renderBranchSections(ctx, connectedBranches, unconnectedBranches)
            }
            tabBranchConnected?.text   = "Connected (${connectedBranches.size})"
            tabBranchUnconnected?.text = "Unconnected (${unconnectedBranches.size})"
        } else {
            // No unconnected branches → hide tab row entirely
            layoutBranchTabs?.visibility = View.GONE
            activeBranchTab = if (connectedBranches.isEmpty()) "unconnected" else "connected"
        }

        renderBranchSections(ctx, connectedBranches, unconnectedBranches)
    }

    private fun updateBranchTabStyles() {
        val activeColor   = android.graphics.Color.parseColor("#E8380D")
        val inactiveColor = android.graphics.Color.parseColor("#6B7280")
        tabBranchConnected?.setTextColor(
            if (activeBranchTab == "connected") activeColor else inactiveColor
        )
        tabBranchUnconnected?.setTextColor(
            if (activeBranchTab == "unconnected") activeColor else inactiveColor
        )
    }

    private fun renderBranchSections(
        ctx: android.content.Context,
        connectedBranches: List<String>,
        unconnectedBranches: List<String>
    ) {
        val hasBoth = connectedBranches.isNotEmpty() && unconnectedBranches.isNotEmpty()

        // Show connected section
        val showConnected = connectedBranches.isNotEmpty() &&
            (!hasBoth || activeBranchTab == "connected")
        sectionConnected?.visibility = if (showConnected) View.VISIBLE else View.GONE

        if (showConnected) {
            containerConnectedBranches?.removeAllViews()

            // Single branch → auto expand on first load only
            // Multiple branches → all collapsed by default
            if (expandedBranch == null && connectedBranches.size == 1) {
                expandedBranch = connectedBranches.first()
            } else if (connectedBranches.size > 1 && expandedBranch != null &&
                !connectedBranches.contains(expandedBranch)) {
                expandedBranch = null
            }

            val dp = resources.displayMetrics.density
            fun Int.dp() = (this * dp).toInt()

            connectedBranches.forEach { branchId ->
                val connList   = connections[branchId] ?: emptyList()
                val isExpanded = expandedBranch == branchId

                // ── Outer branch card ──────────────────────────────────
                val branchCard = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    background  = resources.getDrawable(R.drawable.bg_card_rounded, null)
                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = 12.dp()
                    layoutParams = lp
                }

                // ── Header row ─────────────────────────────────────────
                val headerRow = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity     = android.view.Gravity.CENTER_VERTICAL
                    setPadding(16.dp(), 14.dp(), 12.dp(), 14.dp())
                    isClickable = true
                    isFocusable = true
                }

                val tvArrow = TextView(ctx).apply {
                    text     = if (isExpanded) "▼" else "▶"
                    textSize = 11f
                    setTextColor(android.graphics.Color.parseColor("#6B7280"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 10.dp() }
                }

                val headerCenter = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val tvBranchName = TextView(ctx).apply {
                    text     = branchLabel(branchId)
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.parseColor("#111827"))
                }
                // Collapsed summary
                val tvSummary = TextView(ctx).apply {
                    text      = "${connList.size} sheet${if (connList.size != 1) "s" else ""} connected"
                    textSize  = 11f
                    setTextColor(android.graphics.Color.parseColor("#6B7280"))
                    visibility = if (isExpanded) android.view.View.GONE else android.view.View.VISIBLE
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 2.dp() }
                }
                headerCenter.addView(tvBranchName)
                headerCenter.addView(tvSummary)

                val btnNewSheet = TextView(ctx).apply {
                    text      = "+ New Sheet"
                    textSize  = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.parseColor("#3B82F6"))
                    background = resources.getDrawable(R.drawable.bg_card_rounded, null)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#EFF6FF")
                    )
                    setPadding(16.dp(), 8.dp(), 16.dp(), 8.dp())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        activeBranch       = branchId
                        activeConnectionId = ""
                        selectedNickname   = ""
                        clearConnectForm()
                        screen      = Screen.CONNECTING
                        connectStep = 1
                        render()
                    }
                }

                headerRow.addView(tvArrow)
                headerRow.addView(headerCenter)
                headerRow.addView(btnNewSheet)

                // ── Sheet sub-cards (visible when expanded) ────────────
                val sheetsContainer = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    visibility  = if (isExpanded) android.view.View.VISIBLE else android.view.View.GONE
                    setPadding(12.dp(), 0, 12.dp(), 12.dp())
                }

                connList.forEach { conn ->
                    val sheetCard = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        background  = resources.getDrawable(R.drawable.bg_input_rounded, null)
                        setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = 8.dp() }
                    }

                    // Nickname — top identifier (bold)
                    val tvNickname = TextView(ctx).apply {
                        text     = conn.nickname.ifBlank { conn.sheetName }
                        textSize = 13f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(android.graphics.Color.parseColor("#111827"))
                    }

                    // Sheet Name
                    val tvSheetInfo = TextView(ctx).apply {
                        text = "Sheet Name: ${conn.sheetName}"
                        textSize = 11f
                        setTextColor(android.graphics.Color.parseColor("#6B7280"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = 4.dp() }
                    }

                    // Tab Name
                    val tvTabInfo = TextView(ctx).apply {
                        text = "Tab Name: ${conn.tabName}"
                        textSize = 11f
                        setTextColor(android.graphics.Color.parseColor("#6B7280"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = 2.dp() }
                    }

                    // Range
                    val startLetter = colIndexToLetter(conn.colStart)
                    val endLetter   = colIndexToLetter(conn.colEnd)
                    val sRow        = conn.startRow?.takeIf { it > 1 }
                    val eRow        = conn.endRow?.takeIf   { it > 0 }
                    val rangeText   = when {
                        sRow != null && eRow != null -> "$startLetter$sRow:$endLetter$eRow"
                        sRow != null                 -> "$startLetter$sRow:$endLetter"
                        else                         -> "$startLetter:$endLetter"
                    }
                    val tvRange = TextView(ctx).apply {
                        text     = "Current Range: $rangeText"
                        textSize = 11f
                        setTextColor(android.graphics.Color.parseColor("#6B7280"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = 2.dp() }
                    }

                    // ── Buttons row (Manage + Sync) ───────────────────
                    val buttonsRow = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity     = android.view.Gravity.END
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = 10.dp() }
                    }

                    val btnSync = android.widget.Button(ctx).apply {
                        text      = "🔄 Sync"
                        textSize  = 11f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(android.graphics.Color.parseColor("#2563EB"))
                        backgroundTintList = android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.parseColor("#EFF6FF")
                        )
                        setPadding(20.dp(), 6.dp(), 20.dp(), 6.dp())
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = 8.dp() }
                        setOnClickListener {
                            toast("🔄 Syncing ${conn.nickname.ifBlank { conn.sheetName }}...")
                            // TODO: trigger actual sync for this connection
                        }
                    }

                    val btnManage = android.widget.Button(ctx).apply {
                        text      = "Manage"
                        textSize  = 11f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(android.graphics.Color.WHITE)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.parseColor("#16A34A")
                        )
                        setPadding(24.dp(), 6.dp(), 24.dp(), 6.dp())
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        setOnClickListener {
                            activeBranch       = branchId
                            activeConnectionId = conn.connectionId
                            screen = Screen.MANAGING
                            render()
                        }
                    }

                    buttonsRow.addView(btnSync)
                    buttonsRow.addView(btnManage)

                    sheetCard.addView(tvNickname)
                    sheetCard.addView(tvSheetInfo)
                    sheetCard.addView(tvTabInfo)
                    sheetCard.addView(tvRange)
                    sheetCard.addView(buttonsRow)
                    sheetsContainer.addView(sheetCard)
                }

                // ── Toggle click ───────────────────────────────────────
                headerRow.setOnClickListener {
                    expandedBranch = if (isExpanded) null else branchId
                    updateBranchSpinner()
                }

                branchCard.addView(headerRow)
                branchCard.addView(sheetsContainer)
                containerConnectedBranches?.addView(branchCard)
            }
        }

        // Show unconnected section
        val showUnconnected = unconnectedBranches.isNotEmpty() &&
            (!hasBoth || activeBranchTab == "unconnected")
        sectionUnconnected?.visibility = if (showUnconnected) View.VISIBLE else View.GONE

        // Always hide spinner/action card when not showing unconnected section
        if (!showUnconnected) {
            spinnerBranch?.visibility  = View.GONE
            tvSingleBranch?.visibility = View.GONE
            btnBranchAction?.visibility = View.GONE
            cardConnInfo?.visibility    = View.GONE
            cardBranchInfo?.visibility  = View.GONE
            return
        }

        if (showUnconnected) {
            if (unconnectedBranches.size == 1) {
                activeBranch = unconnectedBranches.first()
                spinnerBranch?.visibility  = View.GONE
                tvSingleBranch?.visibility = View.GONE
            } else {
                spinnerBranch?.visibility  = View.VISIBLE
                tvSingleBranch?.visibility = View.GONE
                val opts = listOf("শাখা বেছে নিন...") + unconnectedBranches.map { branchLabel(it) }
                spinnerBranch?.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, opts)
                val sel = unconnectedBranches.indexOf(activeBranch)
                if (sel >= 0) spinnerBranch?.setSelection(sel + 1)
            }
            updateBranchActionCard()
        }
    }

    private fun updateBranchActionCard() {
        val conn = connections[activeBranch]?.firstOrNull()
        if (activeBranch.isEmpty()) {
            btnBranchAction?.visibility = View.GONE
            cardConnInfo?.visibility    = View.GONE
            cardBranchInfo?.visibility  = View.GONE
            return
        }
        updateSelectedBranchInfo()
        btnBranchAction?.visibility = View.VISIBLE
        if (conn != null) {
            cardConnInfo?.visibility = View.VISIBLE
            tvConnInfoSheet?.text    = "📄 ${conn.sheetName}"
            tvConnInfoTab?.text      = "📑 Tab: ${conn.tabName}"
            tvConnInfoCols?.text     = "📊 Columns: ${conn.columns.firstOrNull() ?: "A"}–${conn.columns.lastOrNull() ?: "J"} (${conn.columns.size}টি)"
            btnBranchAction?.text    = "Manage"
            btnBranchAction?.setBackgroundColor(android.graphics.Color.parseColor("#16A34A"))
        } else {
            cardConnInfo?.visibility = View.GONE
            btnBranchAction?.text    = "Connect করুন"
            btnBranchAction?.setBackgroundColor(android.graphics.Color.parseColor("#E8380D"))
        }
    }

    private fun branchLabel(branchId: String): String {
        val info = branchInfos[branchId]
        val name = info?.name?.takeIf { it.isNotBlank() } ?: branchId
        val code = info?.code.orEmpty()
        return if (code.isBlank()) name else "$name ($code)"
    }

    private fun updateSelectedBranchInfo() {
        val info = branchInfos[activeBranch] ?: BranchInfo(id = activeBranch, name = activeBranch)
        cardBranchInfo?.visibility = View.VISIBLE
        tvBranchInfoName?.text = branchLabel(activeBranch)
        tvBranchInfoCode?.text = "Code: ${info.code.ifBlank { "N/A" }}"
        tvBranchInfoAddress?.text = "Address: ${info.address.ifBlank { "N/A" }}"
        tvBranchInfoType?.text = "Type: ${info.type.ifBlank { "N/A" }}"
        tvBranchInfoStatus?.text = "Status: ${info.status.ifBlank { "N/A" }}"
    }


    // ── ConnectFlow steps ─────────────────────────────────────────────
    private fun renderConnectStep() {
        // Step views
        stepView1?.visibility = if (connectStep == 1) View.VISIBLE else View.GONE
        stepView2?.visibility = if (connectStep == 2) View.VISIBLE else View.GONE
        stepView3?.visibility = if (connectStep == 3) View.VISIBLE else View.GONE
        stepView4?.visibility = if (connectStep == 4) View.VISIBLE else View.GONE
        stepView5?.visibility = if (connectStep == 5) View.VISIBLE else View.GONE

        // Step dots — done=green circle + tick, active=white circle + step number + border, future=grey circle + step number
        val density = resources.displayMetrics.density
        fun roundBg(fillColor: Int, strokeColor: Int? = null, strokeDp: Int = 2) =
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(fillColor)
                strokeColor?.let { setStroke((strokeDp * density).toInt(), it) }
            }
        fun styleDot(dot: TextView?, n: Int) {
            when {
                connectStep > n -> {
                    // Done — green fill, white tick
                    dot?.background = roundBg(android.graphics.Color.parseColor("#16A34A"))
                    dot?.text = "✓"
                    dot?.setTextColor(android.graphics.Color.WHITE)
                }
                connectStep == n -> {
                    // Active — white fill, green border, dark number
                    dot?.background = roundBg(
                        android.graphics.Color.WHITE,
                        android.graphics.Color.parseColor("#16A34A"), 2
                    )
                    dot?.text = "$n"
                    dot?.setTextColor(android.graphics.Color.parseColor("#16A34A"))
                }
                else -> {
                    // Future — light grey fill, grey number
                    dot?.background = roundBg(android.graphics.Color.parseColor("#E5E7EB"))
                    dot?.text = "$n"
                    dot?.setTextColor(android.graphics.Color.parseColor("#9CA3AF"))
                }
            }
        }
        styleDot(step1Dot, 1); styleDot(step2Dot, 2); styleDot(step3Dot, 3); styleDot(step4Dot, 4); styleDot(step5Dot, 5)

        // Step lines
        val lineColor = android.graphics.Color.parseColor("#16A34A")
        val lineGrey  = android.graphics.Color.parseColor("#E5E7EB")
        step1Line?.setBackgroundColor(if (connectStep > 1) lineColor else lineGrey)
        step2Line?.setBackgroundColor(if (connectStep > 2) lineColor else lineGrey)
        step3Line?.setBackgroundColor(if (connectStep > 3) lineColor else lineGrey)
        step4Line?.setBackgroundColor(if (connectStep > 4) lineColor else lineGrey)

        // Step labels
        val green = android.graphics.Color.parseColor("#16A34A")
        val dark  = android.graphics.Color.parseColor("#111827")
        val grey  = android.graphics.Color.parseColor("#9CA3AF")
        fun styleLbl(lbl: TextView?, n: Int) {
            lbl?.setTextColor(when { connectStep > n -> green; connectStep == n -> dark; else -> grey })
        }
        styleLbl(step1Lbl, 1); styleLbl(step2Lbl, 2); styleLbl(step3Lbl, 3); styleLbl(step4Lbl, 4); styleLbl(step5Lbl, 5)

        // Nav buttons
        // Range edit mode: no back (can't go to step 3), only Cancel + Save
        btnBack?.visibility    = if (!isRangeEdit && connectStep > 1) View.VISIBLE else View.GONE
        // Step 1: Next only visible when account is selected
        btnNext?.visibility    = when {
            isRangeEdit      -> View.GONE
            connectStep == 1 -> if (googleAccount != null) View.VISIBLE else View.GONE
            connectStep < 5  -> View.VISIBLE
            else             -> View.GONE
        }
        btnConnect?.visibility = if (connectStep == 5) View.VISIBLE else View.GONE
        btnConnect?.text = if (connections[activeBranch]?.any { it.connectionId == activeConnectionId } == true) "Save" else "Connect"

        // Cancel button label changes in range edit mode
        (btnCancelConn as? TextView)?.text = if (isRangeEdit) "Cancel" else "✕"

        tvConnError?.visibility = View.GONE

        // Per-step UI
        when (connectStep) {
            1 -> updateAccountStep()
            2 -> updateSheetPickerLabel()
            3 -> updateTabSpinner()
            4 -> { updateColPreview(); updateSummary(); scheduleLivePreview() }
            5 -> renderMappingStep()
        }
    }

    private val colWatcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            if (connectStep == 4) {
                updateColPreview()
                updateSummary()
                scheduleLivePreview()
            }
        }
    }

    /** Parse "A", "a", "1", "AA" etc → 1-based column index */
    private fun parseColInput(raw: String): Int? {
        val s = raw.trim().uppercase()
        if (s.isEmpty()) return null
        // Numeric
        s.toIntOrNull()?.let { return it }
        // Letter(s): A=1, B=2 ... Z=26, AA=27 ...
        var result = 0
        for (ch in s) {
            if (ch !in 'A'..'Z') return null
            result = result * 26 + (ch - 'A' + 1)
        }
        return result
    }

    private fun colIndexToLetter(n: Int): String {
        var num = n; var result = ""
        while (num > 0) {
            val rem = (num - 1) % 26
            result = ('A' + rem) + result
            num = (num - 1) / 26
        }
        return result
    }

    private fun updateColPreview() {
        val s = parseColInput(etColStart?.text?.toString() ?: "") ?: run {
            tvColPreview?.text = "⚠ শুরু column দিন (A বা 1)"
            return
        }
        val e = parseColInput(etColEnd?.text?.toString() ?: "") ?: run {
            tvColPreview?.text = "⚠ শেষ column দিন (J বা 10)"
            return
        }
        if (s < 1 || e < s) {
            tvColPreview?.text = "⚠ Invalid range (start ≤ end)"
            return
        }
        val startLetter = colIndexToLetter(s)
        val endLetter   = colIndexToLetter(e)
        val count = e - s + 1
        tvColPreview?.text = "Columns: $startLetter ($s) – $endLetter ($e)  ·  মোট $count টি"
    }

    private fun updateSummary() {
        val sheetName = selectedSheet?.name ?: ""
        val sheetId   = selectedSheet?.id ?: ""
        val tab       = selectedTab
        val email     = googleAccount?.email ?: ""
        val s = parseColInput(etColStart?.text?.toString() ?: "") ?: 1
        val e = parseColInput(etColEnd?.text?.toString() ?: "") ?: 10
        val startLetter = colIndexToLetter(s.coerceAtLeast(1))
        val endLetter   = colIndexToLetter(e.coerceAtLeast(s))
        tvSummary?.text = "✅ Summary\n\nAccount: $email\nSheet: $sheetName\nSheet ID: ${if (sheetId.length > 24) sheetId.take(24) + "…" else sheetId}\nTab: $tab\nColumns: $startLetter–$endLetter (${(e - s + 1).coerceAtLeast(1)}টি)\nBranch: ${branchLabel(activeBranch)}"
    }

    private fun scheduleLivePreview() {
        previewJob?.cancel()
        val account = googleAccount ?: run {
            tvLivePreview?.text = "Live preview দেখতে Google account sign in দরকার। Range save করা যাবে।"
            scrollLivePreview?.visibility = View.GONE
            tableLivePreview?.removeAllViews()
            return
        }
        val sheet   = selectedSheet ?: return
        val tab     = selectedTab.takeIf { it.isNotBlank() } ?: return
        val s = parseColInput(etColStart?.text?.toString() ?: "") ?: return
        val e = parseColInput(etColEnd?.text?.toString() ?: "") ?: return
        if (s < 1 || e < s) return

        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(600)
            fetchAndShowLivePreview(googleAccount ?: return@launch, sheet.id, tab, s, e)
        }
    }

    private suspend fun fetchAndShowLivePreview(
        account: GoogleSignInAccount,
        sheetId: String,
        tab: String,
        colStart: Int,
        colEnd: Int
    ) {
        val acctObj = account.account ?: return
        try {
            pbPreviewLoad?.visibility = View.VISIBLE
            tvLivePreview?.text = "Fetching preview..."
            scrollLivePreview?.visibility = View.GONE
            tableLivePreview?.removeAllViews()
            val ctx = context ?: return
            val token = withContext(Dispatchers.IO) {
                try { GoogleAuthUtil.getToken(ctx, acctObj, OAUTH_SCOPE) }
                catch (e: UserRecoverableAuthException) { null }
            } ?: run {
                tvLivePreview?.text = "⚠ Token পাওয়া যায়নি"
                scrollLivePreview?.visibility = View.GONE
                return
            }

            val startLetter = colIndexToLetter(colStart)
            val endLetter   = colIndexToLetter(colEnd)

            // Row range: use defined values or defaults
            val sRow = etStartRow?.text?.toString()?.trim()?.toIntOrNull() ?: 1
            val eRow = etEndRow?.text?.toString()?.trim()?.toIntOrNull()
            // Max 5 data rows after header, but stop at endRow if defined
            val maxEnd      = sRow + 5
            val previewEndRow = if (eRow != null) minOf(eRow, maxEnd) else maxEnd
            val range = "$tab!${startLetter}${sRow}:${endLetter}${previewEndRow}"
            val previewLabel = when {
                eRow == null              -> "Preview: Row $sRow + next 5 rows"
                eRow <= sRow              -> "Preview: Row $sRow (end row ≤ start row)"
                previewEndRow < maxEnd    -> "Preview: Row $sRow → $eRow (${previewEndRow - sRow} rows)"
                else                     -> "Preview: Row $sRow + next 5 rows (end: $eRow)"
            }
            val encodedRange = java.net.URLEncoder.encode(range, "UTF-8")
            val url = "https://sheets.googleapis.com/v4/spreadsheets/$sheetId/values/$encodedRange"

            val rows = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url)
                    .header("Authorization", "Bearer $token").build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string() ?: return@withContext null
                    val obj = org.json.JSONObject(body)
                    val arr = obj.optJSONArray("values") ?: return@withContext emptyList<List<String>>()
                    (0 until arr.length()).map { i ->
                        val row = arr.getJSONArray(i)
                        (0 until row.length()).map { j -> row.optString(j, "") }
                    }
                }
            }

            if (rows == null) {
                tvLivePreview?.text = "⚠ Sheet fetch failed"
                scrollLivePreview?.visibility = View.GONE
                return
            }
            if (rows.isEmpty()) {
                tvLivePreview?.text = "⚠ এই range এ কোনো data নেই"
                renderLivePreviewTable(emptyList(), colStart, colEnd)
                return
            }

            tvLivePreview?.text = previewLabel
            // Capture header row for step 5 auto-mapping
            val headerRow = rows.firstOrNull()
            if (headerRow != null) {
                val newHeaders = mutableMapOf<String, String>()
                headerRow.forEachIndexed { idx, text ->
                    val letter = colIndexToLetter(colStart + idx)
                    if (text.isNotBlank()) newHeaders[letter] = text
                }
                sheetHeaders = newHeaders
            }
            renderLivePreviewTable(rows, colStart, colEnd)

        } catch (e: Exception) {
            tvLivePreview?.text = "⚠ Preview error: ${e.message?.take(60)}"
            scrollLivePreview?.visibility = View.GONE
        } finally {
            pbPreviewLoad?.visibility = View.GONE
        }
    }

    private fun renderLivePreviewTable(
        rows: List<List<String>>,
        colStart: Int,
        colEnd: Int,
        targetTable: android.widget.TableLayout? = tableLivePreview,
        targetScroll: android.widget.HorizontalScrollView? = scrollLivePreview
    ) {
        val table = targetTable ?: return
        table.removeAllViews()
        val colCount = (colEnd - colStart + 1).coerceAtLeast(1)

        // Only show actual data rows — no blank padding rows
        val dataRows = rows.map { row -> List(colCount) { c -> row.getOrElse(c) { "" } } }

        val letters = List(colCount) { c -> colIndexToLetter(colStart + c) }
        table.addView(tableRow(letters, "#F3F4F6", "#6B7280", bold = true, compact = true))

        if (dataRows.isEmpty()) {
            // Show one placeholder row when no data yet
            table.addView(tableRow(List(colCount) { "" }, "#FFF7ED", "#111827", bold = true))
        } else {
            dataRows.forEachIndexed { i, row ->
                val bg = when (i) {
                    0    -> "#FFF7ED"
                    else -> if (i % 2 == 0) "#FFFFFF" else "#F9FAFB"
                }
                val bold = i == 0
                table.addView(tableRow(row, bg, if (bold) "#111827" else "#374151", bold = bold))
            }
        }
        targetScroll?.visibility = View.VISIBLE
    }
    private fun fetchManageColPreview() {
        val conn = activeConn() ?: return
        val signInAccount = GoogleSignIn.getLastSignedInAccount(requireContext()) ?: run {
            tvColPreviewMgr?.text = "⚠ Google account দিয়ে reconnect করুন"
            return
        }

        val startLetter = colIndexToLetter(conn.colStart)
        val endLetter   = colIndexToLetter(conn.colEnd)
        val sRow        = conn.startRow ?: 1
        val eRow        = conn.endRow?.takeIf { it > 0 }
        val maxEnd      = sRow + 5
        val previewEnd  = if (eRow != null) minOf(eRow, maxEnd) else maxEnd
        val range       = "${conn.tabName}!${startLetter}${sRow}:${endLetter}${previewEnd}"
        val label = when {
            eRow == null           -> "Preview: Row $sRow + next 5 rows"
            eRow <= sRow           -> "Preview: Row $sRow (end row ≤ start row)"
            previewEnd < maxEnd    -> "Preview: Row $sRow → $eRow (${previewEnd - sRow} rows)"
            else                   -> "Preview: Row $sRow + next 5 rows (end: $eRow)"
        }

        tvColPreviewMgr?.text = ""
        pbColPreviewMgr?.visibility = View.VISIBLE
        scrollColPreviewMgr?.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val acctObj = signInAccount.account ?: return@launch
                val ctx     = context ?: return@launch
                val token   = withContext(Dispatchers.IO) {
                    try { GoogleAuthUtil.getToken(ctx, acctObj, OAUTH_SCOPE) }
                    catch (e: UserRecoverableAuthException) { null }
                } ?: run {
                    tvColPreviewMgr?.text = "⚠ Token পাওয়া যায়নি"
                    return@launch
                }

                val encodedRange = java.net.URLEncoder.encode(range, "UTF-8")
                val url = "https://sheets.googleapis.com/v4/spreadsheets/${conn.sheetId}/values/$encodedRange"
                val rows = withContext(Dispatchers.IO) {
                    val req = Request.Builder().url(url)
                        .header("Authorization", "Bearer $token").build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext null
                        val body = resp.body?.string() ?: return@withContext null
                        val arr = org.json.JSONObject(body).optJSONArray("values")
                            ?: return@withContext emptyList<List<String>>()
                        (0 until arr.length()).map { i ->
                            val row = arr.getJSONArray(i)
                            (0 until row.length()).map { j -> row.optString(j, "") }
                        }
                    }
                }

                if (!isAdded) return@launch
                pbColPreviewMgr?.visibility = View.GONE
                if (rows == null) {
                    tvColPreviewMgr?.text = "⚠ Sheet fetch failed"
                    return@launch
                }
                tvColPreviewMgr?.text = label
                renderLivePreviewTable(rows, conn.colStart, conn.colEnd, tableColPreviewMgr, scrollColPreviewMgr)

            } catch (e: Exception) {
                if (isAdded) {
                    pbColPreviewMgr?.visibility = View.GONE
                    tvColPreviewMgr?.text = "⚠ Error: ${e.message?.take(60)}"
                }
            }
        }
    }

    private suspend fun syncSheetToFirebase(conn: SheetConn) {
        val ctx = context ?: return
        val account = googleAccount ?: run { toast("Google account নেই"); return }
        val acctObj = account.account ?: return

        if (conn.columnMapping.isEmpty()) {
            toast("⚠ Column mapping নেই — Step 5 complete করুন")
            return
        }
        val consignmentCol = conn.columnMapping["consignmentId"] ?: run {
            toast("⚠ consignmentId column map করা নেই")
            return
        }
        val phoneCol = conn.columnMapping["recipientPhone"]

        setBusy(true, "Sheet fetch করছে...")

        try {
            // ── 1. Get token ─────────────────────────────────────────
            val token = withContext(Dispatchers.IO) {
                try { GoogleAuthUtil.getToken(ctx, acctObj, OAUTH_SCOPE) }
                catch (e: UserRecoverableAuthException) { null }
            } ?: run { setBusy(false); toast("⚠ Token পাওয়া যায়নি"); return }

            // ── 2. Fetch all sheet rows ───────────────────────────────
            val startLetter = colIndexToLetter(conn.colStart)
            val endLetter   = colIndexToLetter(conn.colEnd)
            val sRow = conn.startRow?.takeIf { it > 0 } ?: 1
            val eRow = conn.endRow?.takeIf   { it > 0 }
            val rangeStr = if (eRow != null)
                "${conn.tabName}!${startLetter}${sRow}:${endLetter}${eRow}"
            else
                "${conn.tabName}!${startLetter}${sRow}:${endLetter}"
            val encodedRange = java.net.URLEncoder.encode(rangeStr, "UTF-8")
            val url = "https://sheets.googleapis.com/v4/spreadsheets/${conn.sheetId}/values/$encodedRange"

            val allRows = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url)
                    .header("Authorization", "Bearer $token").build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string() ?: return@withContext null
                    val arr  = org.json.JSONObject(body).optJSONArray("values")
                        ?: return@withContext emptyList<List<String>>()
                    (0 until arr.length()).map { i ->
                        val row = arr.getJSONArray(i)
                        (0 until row.length()).map { j -> row.optString(j, "") }
                    }
                }
            }

            if (allRows == null) { setBusy(false); toast("⚠ Sheet fetch failed"); return }

            // First row = header, rest = data
            val dataRows = if (allRows.size > 1) allRows.drop(1) else allRows

            if (dataRows.isEmpty()) { setBusy(false); toast("⚠ Sheet এ কোনো data নেই"); return }

            // ── 3. Build colLetter → index map ────────────────────────
            fun letterToIndex(letter: String): Int {
                val idx = parseColInput(letter) ?: return -1
                return idx - conn.colStart  // 0-based within fetched range
            }

            val conIdIdx = letterToIndex(consignmentCol)
            if (conIdIdx < 0) { setBusy(false); toast("⚠ consignmentId column invalid"); return }

            // ── 4. Process rows ───────────────────────────────────────
            setBusy(true, "Firebase sync করছে...")

            var inserted = 0; var updated = 0; var skipped = 0
            val basePath = conn.targetNode.trimEnd('/')

            for (row in dataRows) {
                val conId = row.getOrElse(conIdIdx) { "" }.trim()
                if (conId.isBlank()) { skipped++; continue }

                // Build field map from column mapping
                val fieldMap = mutableMapOf<String, Any>()
                conn.columnMapping.forEach { (field, colLetter) ->
                    if (field == "consignmentId") return@forEach // key, not a value field
                    if (field == "createdAt" || field == "updatedAt") return@forEach // handled below with validation
                    val idx = letterToIndex(colLetter)
                    if (idx < 0) return@forEach
                    val value = row.getOrElse(idx) { "" }.trim()
                    if (value.isNotBlank()) fieldMap[field] = value
                }

                // ── createdAt / updatedAt — parsed from sheet with safety checks:
                //    1) must be a parseable date/timestamp (else ignored — not pushed)
                //    2) must not be in the future
                //    3) createdAt must not be after updatedAt
                val nowMillis = System.currentTimeMillis()
                var createdAtMillis: Long? = conn.columnMapping["createdAt"]?.let { colLetter ->
                    val idx = letterToIndex(colLetter)
                    if (idx < 0) null else parseSheetTimestamp(row.getOrElse(idx) { "" }.trim())
                }
                var updatedAtMillis: Long? = conn.columnMapping["updatedAt"]?.let { colLetter ->
                    val idx = letterToIndex(colLetter)
                    if (idx < 0) null else parseSheetTimestamp(row.getOrElse(idx) { "" }.trim())
                }
                if (createdAtMillis != null && createdAtMillis!! > nowMillis) createdAtMillis = null
                if (updatedAtMillis != null && updatedAtMillis!! > nowMillis) updatedAtMillis = null
                if (createdAtMillis != null && updatedAtMillis != null && createdAtMillis!! > updatedAtMillis!!) {
                    createdAtMillis = null // createdAt can never be after updatedAt
                }
                createdAtMillis?.let { fieldMap["createdAt"] = it }
                updatedAtMillis?.let { fieldMap["updatedAt"] = it }

                // Normalize phone
                val phoneField = conn.columnMapping["recipientPhone"]?.let { colLetter ->
                    val idx = letterToIndex(colLetter)
                    if (idx >= 0) row.getOrElse(idx) { "" }.trim() else ""
                }
                val normalizedPhone = normalizePhone(phoneField ?: "")
                if (normalizedPhone.isNotBlank()) fieldMap["recipientPhone"] = normalizedPhone

                // ── Check Firebase exist ──────────────────────────────
                val existSnap = withContext(Dispatchers.IO) {
                    try { db.reference.child("$basePath/$conId").get().await() }
                    catch (e: Exception) { null }
                }

                val multiUpdate = mutableMapOf<String, Any>()

                if (existSnap == null || !existSnap.exists()) {
                    // INSERT
                    fieldMap.forEach { (k, v) -> multiUpdate["$basePath/$conId/$k"] = v }
                    // consignments_by_phone
                    if (normalizedPhone.isNotBlank()) {
                        val status = fieldMap["status"]?.toString() ?: ""
                        multiUpdate["courier/consignments_by_phone/$normalizedPhone/$conId"] = status
                    }
                    inserted++
                } else {
                    // COMPARE & UPDATE changed fields only
                    val changedFields = mutableMapOf<String, Any>()
                    fieldMap.forEach { (k, v) ->
                        val firebaseVal = existSnap.child(k).value
                        val same = when {
                            v is Long && firebaseVal is Number -> firebaseVal.toLong() == v
                            else -> (firebaseVal?.toString() ?: "") == v.toString()
                        }
                        if (!same) changedFields[k] = v
                    }
                    if (changedFields.isNotEmpty()) {
                        changedFields.forEach { (k, v) -> multiUpdate["$basePath/$conId/$k"] = v }
                        // Update consignments_by_phone if status changed
                        if ("status" in changedFields && normalizedPhone.isNotBlank()) {
                            multiUpdate["courier/consignments_by_phone/$normalizedPhone/$conId"] =
                                changedFields["status"].toString()
                        }
                        updated++
                    } else {
                        skipped++
                    }
                }

                // Multi-path write
                if (multiUpdate.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        try { db.reference.updateChildren(multiUpdate).await() }
                        catch (_: Exception) {}
                    }
                }
            }

            // ── 5. Summary dialog ─────────────────────────────────────
            setBusy(false)
            if (!isAdded) return
            android.app.AlertDialog.Builder(ctx)
                .setTitle("✅ Sync Complete")
                .setMessage(
                    "Inserted : $inserted\n" +
                    "Updated  : $updated\n" +
                    "Skipped  : $skipped\n" +
                    "Total    : ${dataRows.size}"
                )
                .setPositiveButton("OK", null)
                .show()

        } catch (e: Exception) {
            setBusy(false)
            toast("⚠ Sync error: ${e.message?.take(60)}")
        }
    }

    /**
     * Parses a raw sheet cell value into an epoch-millis timestamp.
     * Returns null if the value can't be confidently parsed as a date/time —
     * callers should then skip pushing that field rather than writing garbage.
     * Accepts: epoch seconds (10 digits), epoch millis (13 digits), or common date/date-time strings.
     */
    private fun parseSheetTimestamp(raw: String): Long? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim()

        // Numeric epoch
        trimmed.toLongOrNull()?.let { num ->
            return when (trimmed.length) {
                10 -> num * 1000L   // epoch seconds
                13 -> num           // epoch millis
                else -> null        // ambiguous digit count — don't guess
            }
        }

        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy",
            "MM/dd/yyyy HH:mm:ss",
            "MM/dd/yyyy",
            "dd-MM-yyyy",
            "yyyy/MM/dd",
        )
        for (pattern in patterns) {
            try {
                val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.ENGLISH)
                sdf.isLenient = false
                val date = sdf.parse(trimmed) ?: continue
                return date.time
            } catch (_: Exception) { /* try next pattern */ }
        }
        return null // unparseable — caller should skip this field
    }

    private fun normalizePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return when {
            digits.isBlank()          -> ""
            digits.startsWith("880")  -> digits
            digits.startsWith("0")    -> "88" + digits
            else                      -> "88" + digits
        }
    }

    private fun renderSyncTab(conn: SheetConn) {
        switchAutoSync?.setOnCheckedChangeListener(null)
        switchAutoSync?.isChecked = conn.autoSync
        switchAutoSync?.setOnCheckedChangeListener { _, isChecked ->
            val c = activeConn() ?: return@setOnCheckedChangeListener
            val updated = c.copy(autoSync = isChecked)
            updateActiveConn(updated)
            updateSyncGearState(isChecked)
            saveSyncSettings(updated)
        }
        updateSyncGearState(conn.autoSync)
        tvSyncIntervalLabel?.text = "প্রতি ${conn.syncIntervalMin} মিনিট"
        tvLastSynced?.text = "Last sync: কখনো না"
    }

    private fun updateSyncGearState(enabled: Boolean) {
        btnSyncGear?.alpha = if (enabled) 1f else 0.4f
        btnSyncGear?.isEnabled = enabled
        tvSyncIntervalLabel?.setTextColor(
            android.graphics.Color.parseColor(if (enabled) "#E8380D" else "#6B7280")
        )
    }

    private fun openIntervalPickerDialog(conn: SheetConn) {
        val options = arrayOf("15 মিনিট", "30 মিনিট", "60 মিনিট", "120 মিনিট", "Custom...")
        val values  = intArrayOf(15, 30, 60, 120, -1)
        val current = values.indexOfFirst { it == conn.syncIntervalMin }.let {
            if (it < 0) options.size - 1 else it // custom if not in list
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Auto Sync Interval")
            .setSingleChoiceItems(options, current) { dialog, which ->
                if (values[which] == -1) {
                    // Custom input
                    dialog.dismiss()
                    showCustomIntervalInput(conn)
                } else {
                    val newInterval = values[which]
                    val updated = conn.copy(syncIntervalMin = newInterval)
                    updateActiveConn(updated)
                    tvSyncIntervalLabel?.text = "প্রতি $newInterval মিনিট"
                    saveSyncSettings(updated)
                    dialog.dismiss()
                }
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun showCustomIntervalInput(conn: SheetConn) {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "মিনিট লিখুন (1-1440)"
            textSize = 14f
            setPadding(48, 32, 48, 32)
            if (conn.syncIntervalMin !in listOf(15, 30, 60, 120)) {
                setText(conn.syncIntervalMin.toString())
            }
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Custom Interval")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val minutes = input.text.toString().trim().toIntOrNull()
                if (minutes == null || minutes < 1 || minutes > 1440) {
                    toast("⚠ 1 থেকে 1440 মিনিটের মধ্যে দিন")
                    return@setPositiveButton
                }
                val updated = conn.copy(syncIntervalMin = minutes)
                updateActiveConn(updated)
                tvSyncIntervalLabel?.text = "প্রতি $minutes মিনিট"
                saveSyncSettings(updated)
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun saveSyncSettings(conn: SheetConn) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val path = "config/sheets/${conn.branchId}/current"
                db.reference.child(path).updateChildren(mapOf(
                    "autoSync"        to conn.autoSync,
                    "syncIntervalMin" to conn.syncIntervalMin,
                )).await()
            } catch (e: Exception) {
                Log.e("ConfigSheet", "saveSyncSettings failed: ${e.message}")
            }
        }
    }

    /** Renders column letter header row in the Manage → Columns tab table */
    private fun renderManageColTable(colStart: Int, colEnd: Int) {
        val table = tableColPreviewMgr ?: return
        table.removeAllViews()
        val colCount = (colEnd - colStart + 1).coerceAtLeast(1)
        val letters  = List(colCount) { c -> colIndexToLetter(colStart + c) }
        val nums     = List(colCount) { c -> "${colStart + c}" }
        table.addView(tableRow(letters, "#F3F4F6", "#6B7280", bold = true, compact = true))
        table.addView(tableRow(nums,    "#FFF7ED", "#E8380D", bold = true, compact = true))
        scrollColPreviewMgr?.visibility = View.VISIBLE
    }

    private fun tableRow(
        cells: List<String>,
        bgColor: String,
        textColor: String,
        bold: Boolean,
        compact: Boolean = false,
    ): TableRow {
        val row = TableRow(requireContext())
        cells.forEach { value ->
            row.addView(tableCell(value, bgColor, textColor, bold, compact))
        }
        return row
    }

    private fun tableCell(
        value: String,
        bgColor: String,
        textColor: String,
        bold: Boolean,
        compact: Boolean,
    ): TextView {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        return TextView(requireContext()).apply {
            text = value.ifBlank { " " }
            textSize = if (compact) 10f else 11f
            setTextColor(android.graphics.Color.parseColor(textColor))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            minWidth = dp(96)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), dp(if (compact) 5 else 8), dp(8), dp(if (compact) 5 else 8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor(bgColor))
                setStroke(dp(1), android.graphics.Color.parseColor("#E5E7EB"))
            }
        }
    }

    private fun advanceStep() {
        tvConnError?.visibility = View.GONE
        when (connectStep) {
            1 -> {
                if (googleAccount == null) { showErr("Google account select করুন"); return }
            }
            2 -> {
                if (selectedSheet == null) { showErr("Sheet select করুন"); return }
            }
            3 -> {
                if (selectedTab.isBlank()) { showErr("Tab select করুন"); return }
                selectedNickname = etNickname?.text?.toString()?.trim() ?: ""
                if (selectedNickname.isBlank()) { showErr("Nickname দিন — এটা required"); return }
            }
            4 -> {
                val s = parseColInput(etColStart?.text?.toString() ?: "") ?: run { showErr("Valid start column দিন (A বা 1)"); return }
                val e = parseColInput(etColEnd?.text?.toString() ?: "") ?: run { showErr("Valid end column দিন (J বা 10)"); return }
                if (s < 1 || e < s) { showErr("start ≤ end হতে হবে"); return }
                // Auto-detect column mapping from fetched headers
                autoDetectMapping()
            }
        }
        connectStep++
        renderConnectStep()
    }

    private fun showErr(msg: String) {
        tvConnError?.text = "⚠ $msg"
        tvConnError?.visibility = View.VISIBLE
    }

    private fun handleConnect() {
        if (!pendingMapping.containsKey("consignmentId")) { showErr("consignmentId column map করুন — এটা required"); return }
        val sheet   = selectedSheet ?: run { showErr("Sheet নেই"); return }
        if (selectedTab.isBlank())  { showErr("Tab নেই"); return }
        val s = parseColInput(etColStart?.text?.toString() ?: "") ?: run { showErr("Valid start column দিন (A বা 1)"); return }
        val e = parseColInput(etColEnd?.text?.toString() ?: "")   ?: run { showErr("Valid end column দিন (J বা 10)"); return }
        if (s < 1 || e < s) { showErr("start ≤ end হতে হবে"); return }

        val existing = activeConn()
        val sRow = etStartRow?.text?.toString()?.trim()?.toIntOrNull()
        val eRow = etEndRow?.text?.toString()?.trim()?.toIntOrNull()

        // Reuse existing connectionId when editing; generate new push key when creating
        val connId = if (isRangeEdit && existing != null) existing.connectionId
                     else db.reference.child("config/sheets/$activeBranch/connections").push().key ?: java.util.UUID.randomUUID().toString()

        val conn = SheetConn(
            connectionId= connId,
            branchId    = activeBranch,
            sheetId     = sheet.id,
            sheetName   = sheet.name,
            tabName     = selectedTab,
            colStart    = s,
            colEnd      = e,
            startRow    = sRow,
            endRow      = eRow,
            nickname    = selectedNickname,
            googleEmail = googleAccount?.email ?: existing?.googleEmail ?: "",
            connectedBy = auth.currentUser?.uid ?: existing?.connectedBy ?: "",
            connectedAt = existing?.connectedAt ?: System.currentTimeMillis(),
            columnMapping = pendingMapping.toMap(),
            targetNode    = etTargetNode?.text?.toString()?.trim()?.ifBlank { "courier/consignments" } ?: "courier/consignments",
        )
        val connList = connections.getOrPut(activeBranch) { mutableListOf() }
        val idx = connList.indexOfFirst { it.connectionId == conn.connectionId }
        if (idx >= 0) connList[idx] = conn else connList.add(conn)
        activeConnectionId = conn.connectionId
        // Expand this branch so newly added sheet card is visible
        expandedBranch = activeBranch
        saveToFirebase(conn)
        toast(if (existing == null) "✅ $activeBranch connected!" else "✅ Range updated")
        screen = if (isRangeEdit) Screen.MANAGING else Screen.BRANCH_SELECT
        isRangeEdit = false
        render()
    }

    private fun autoDetectMapping() {
        pendingMapping.clear()
        if (sheetHeaders.isEmpty()) return
        // Match each Firebase key against sheet headers by similarity
        val allKeys = fetchedNodeKeys + customMappingFields.map { it.first }
        allKeys.forEach { firebaseKey ->
            val keyLower = firebaseKey.lowercase()
            // Split camelCase: "recipientName" → ["recipient", "name"]
            val keyParts = keyLower.replace(Regex("([a-z])([A-Z])"), "$1 $2")
                .lowercase().split(" ", "_", "-").filter { it.isNotBlank() }
            val matched = sheetHeaders.entries.firstOrNull { (_, header) ->
                val h = header.lowercase().trim()
                keyParts.any { part -> h.contains(part) || part.contains(h) }
            }
            if (matched != null) pendingMapping[firebaseKey] = matched.key
        }
    }

    private var nodePreviewData: Map<String, String> = emptyMap()
    private var nodePreviewExpanded = true

    private fun fetchNodeKeys(node: String) {
        pbFetchFields?.visibility = View.VISIBLE
        tvFetchStatus?.text = "Fetching..."
        btnFetchFields?.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val snap = withContext(Dispatchers.IO) {
                    db.reference.child(node).limitToFirst(1).get().await()
                }
                if (!isAdded) return@launch
                pbFetchFields?.visibility = View.GONE
                btnFetchFields?.isEnabled = true

                if (!snap.exists()) {
                    tvFetchStatus?.text = "⚠ Data নেই — manually field add করুন"
                    tvFetchStatus?.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
                    nodePreviewData = emptyMap()
                    fetchedNodeKeys.clear()
                    renderMappingStep()
                    return@launch
                }

                val firstChild = if (snap.hasChildren()) snap.children.first() else snap
                val keys = firstChild.children.mapNotNull { it.key }.toList()

                // Store preview values
                nodePreviewData = firstChild.children.mapNotNull { child ->
                    val k = child.key ?: return@mapNotNull null
                    val v = child.value?.toString()?.take(40) ?: ""
                    k to v
                }.toMap()

                if (keys.isEmpty()) {
                    tvFetchStatus?.text = "⚠ Keys পাওয়া যায়নি — manually add করুন"
                    tvFetchStatus?.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
                    return@launch
                }

                fetchedNodeKeys.clear()
                fetchedNodeKeys.addAll(keys)
                customMappingFields.clear()
                autoDetectMapping()
                nodePreviewExpanded = true
                renderMappingStep()

                tvFetchStatus?.text = "✅ ${keys.size} fields found"
                tvFetchStatus?.setTextColor(android.graphics.Color.parseColor("#16A34A"))

            } catch (e: Exception) {
                if (!isAdded) return@launch
                pbFetchFields?.visibility = View.GONE
                btnFetchFields?.isEnabled = true
                tvFetchStatus?.text = "⚠ Fetch failed: ${e.message?.take(40)}"
                tvFetchStatus?.setTextColor(android.graphics.Color.parseColor("#EF4444"))
            }
        }
    }

    private fun showAddFieldDialog() {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24.dp(), 16.dp(), 24.dp(), 8.dp())
        }
        val etFieldName = EditText(ctx).apply {
            hint = "Field name (e.g. recipientName)"
            background = resources.getDrawable(R.drawable.bg_input_rounded, null)
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            textSize = 13f
        }
        layout.addView(etFieldName)

        android.app.AlertDialog.Builder(ctx)
            .setTitle("New Field যোগ করুন")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val name = etFieldName.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                if (!fetchedNodeKeys.contains(name) &&
                    customMappingFields.none { it.first == name }) {
                    customMappingFields.add(name to name)
                    renderMappingStep()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderMappingStep() {
        val ctx = context ?: return
        val container = containerMapping ?: return
        container.removeAllViews()

        // Pre-fill target node if saved
        if (etTargetNode?.text.isNullOrBlank()) {
            etTargetNode?.setText(targetNode)
        }

        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        // ── Node preview tree ─────────────────────────────────────────
        if (nodePreviewData.isNotEmpty()) {
            val treeCard = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                background  = resources.getDrawable(R.drawable.bg_card_rounded, null)
                setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 14.dp() }
            }
            // Header row with expand/collapse
            val treeHeader = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity     = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
            }
            val tvTreeArrow = TextView(ctx).apply {
                text     = if (nodePreviewExpanded) "▼" else "▶"
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#6B7280"))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8.dp() }
            }
            val tvTreeTitle = TextView(ctx).apply {
                text     = "📁 ${targetNode.trimEnd('/')}/${nodePreviewData.values.firstOrNull() ?: "..."}"
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor("#374151"))
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            treeHeader.addView(tvTreeArrow)
            treeHeader.addView(tvTreeTitle)

            // Tree body
            val treeBody = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(16.dp(), 8.dp(), 0, 0)
                visibility  = if (nodePreviewExpanded) android.view.View.VISIBLE else android.view.View.GONE
            }
            nodePreviewData.entries.forEachIndexed { idx, (key, value) ->
                val isLast = idx == nodePreviewData.size - 1
                val tvRow = TextView(ctx).apply {
                    text      = "${if (isLast) "└─" else "├─"} $key: \"$value\""
                    textSize  = 11f
                    setTextColor(android.graphics.Color.parseColor("#6B7280"))
                    typeface = android.graphics.Typeface.MONOSPACE
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 2.dp() }
                }
                treeBody.addView(tvRow)
            }

            treeHeader.setOnClickListener {
                nodePreviewExpanded = !nodePreviewExpanded
                tvTreeArrow.text = if (nodePreviewExpanded) "▼" else "▶"
                treeBody.visibility = if (nodePreviewExpanded) android.view.View.VISIBLE else android.view.View.GONE
            }

            treeCard.addView(treeHeader)
            treeCard.addView(treeBody)
            container.addView(treeCard)
        }

        // ── Empty state ───────────────────────────────────────────────
        val allFields = fetchedNodeKeys.map { it to it } + customMappingFields
        if (allFields.isEmpty()) {
            val tvEmpty = TextView(ctx).apply {
                text      = "Node fetch করুন অথবা নিচে manually field add করুন"
                textSize  = 12f
                setTextColor(android.graphics.Color.parseColor("#9CA3AF"))
                gravity   = android.view.Gravity.CENTER
                setPadding(0, 16.dp(), 0, 16.dp())
            }
            container.addView(tvEmpty)
            return
        }

        val headerOptions = listOf("— Skip —") + sheetHeaders.map { (letter, text) ->
            "$letter: $text"
        }
        val headerLetters = listOf("") + sheetHeaders.keys.toList()

        // All fields already set above
        val allFields2 = allFields

        allFields2.forEach { (field, label) ->
            val isCustom = customMappingFields.any { it.first == field }
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity     = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10.dp() }
            }

            // Field label
            val tvLabel = TextView(ctx).apply {
                text     = label
                textSize = 12f
                setTypeface(null, if (field == "consignmentId") android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(android.graphics.Color.parseColor(if (isCustom) "#3B82F6" else "#374151"))
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // Auto-matched indicator
            val isAutoMatched = pendingMapping.containsKey(field)
            val tvStatus = TextView(ctx).apply {
                text      = if (isAutoMatched) "✓" else ""
                textSize  = 13f
                setTextColor(android.graphics.Color.parseColor("#16A34A"))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    20.dp(), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 6.dp() }
            }

            // Spinner
            val spinner = Spinner(ctx).apply {
                background = resources.getDrawable(R.drawable.bg_input_rounded, null)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, 44.dp(), 1.2f)
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, headerOptions)
                val matchedLetter = pendingMapping[field]
                val selIdx = if (matchedLetter != null) headerLetters.indexOf(matchedLetter).coerceAtLeast(0) else 0
                setSelection(selIdx)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        val letter = headerLetters.getOrElse(pos) { "" }
                        if (letter.isBlank()) pendingMapping.remove(field)
                        else pendingMapping[field] = letter
                        tvStatus.text = if (pendingMapping.containsKey(field)) "✓" else ""
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            }

            // Delete button for custom fields
            if (isCustom) {
                val btnDelete = TextView(ctx).apply {
                    text     = "✕"
                    textSize = 13f
                    setTextColor(android.graphics.Color.parseColor("#EF4444"))
                    setPadding(8.dp(), 0, 0, 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        customMappingFields.removeAll { it.first == field }
                        pendingMapping.remove(field)
                        renderMappingStep()
                    }
                }
                row.addView(tvLabel)
                row.addView(tvStatus)
                row.addView(spinner)
                row.addView(btnDelete)
            } else {
                row.addView(tvLabel)
                row.addView(tvStatus)
                row.addView(spinner)
            }

            container.addView(row)
        }
    }


    private fun clearConnectForm() {
        availableSheets = emptyList(); selectedSheet = null
        availableTabs   = emptyList(); selectedTab   = ""
        etColStart?.setText("1"); etColEnd?.setText("10")
        isRowRangeVisible = false
        layoutRowRange?.visibility = View.GONE
        btnDefineRow?.text = "+ Define Row Range"
        etStartRow?.setText(""); etEndRow?.setText("")
        customMappingFields.clear()
        fetchedNodeKeys.clear()
        nodePreviewData = emptyMap()
        pendingMapping.clear()
        targetNode = "courier/consignments"
        etTargetNode?.setText("")
        tvFetchStatus?.text = ""
        if (googleAccount != null) loadSheetsForAccount()
    }

    private fun prefillConnectForm() {
        val conn = activeConn() ?: return
        selectedSheet = DriveFile(conn.sheetId, conn.sheetName)
        selectedTab = conn.tabName
        selectedNickname = conn.nickname
        etNickname?.setText(conn.nickname)
        availableTabs = listOf(conn.tabName)
        updateSheetPickerLabel()
        etColStart?.setText(conn.colStart.toString())
        etColEnd?.setText(conn.colEnd.toString())
        // Show row range fields if previously saved
        val hasSavedRows = (conn.startRow != null && conn.startRow != 1) || (conn.endRow != null && conn.endRow != 0)
        isRowRangeVisible = hasSavedRows
        layoutRowRange?.visibility = if (hasSavedRows) View.VISIBLE else View.GONE
        btnDefineRow?.text = if (hasSavedRows) "− Hide Row Range" else "+ Define Row Range"
        if (hasSavedRows) {
            conn.startRow?.let { etStartRow?.setText(it.toString()) }
            conn.endRow?.takeIf { it > 0 }?.let { etEndRow?.setText(it.toString()) }
        }
        if (googleAccount != null) loadSheetsForAccount()
        targetNode = conn.targetNode
        etTargetNode?.setText(conn.targetNode)
        pendingMapping.clear()
        pendingMapping.putAll(conn.columnMapping)
    }

    private fun openRangeEditor() {
        val conn = activeConn() ?: return
        activeConnectionId = conn.connectionId

        selectedSheet = DriveFile(conn.sheetId, conn.sheetName)
        selectedTab = conn.tabName
        selectedNickname = conn.nickname
        etNickname?.setText(conn.nickname)
        availableTabs = listOf(conn.tabName)
        etColStart?.setText(conn.colStart.toString())
        etColEnd?.setText(conn.colEnd.toString())
        // Show row range if previously saved
        val hasSavedRows = (conn.startRow != null && conn.startRow != 1) || (conn.endRow != null && conn.endRow != 0)
        isRowRangeVisible = hasSavedRows
        layoutRowRange?.visibility = if (hasSavedRows) View.VISIBLE else View.GONE
        btnDefineRow?.text = if (hasSavedRows) "− Hide Row Range" else "+ Define Row Range"
        if (hasSavedRows) {
            conn.startRow?.let { etStartRow?.setText(it.toString()) }
            conn.endRow?.takeIf { it > 0 }?.let { etEndRow?.setText(it.toString()) }
        }
        isRangeEdit = true
        screen = Screen.CONNECTING
        connectStep = 4
        render()
    }

    // ── Account picker (JSX showPicker equivalent) ────────────────────
    private fun pickGoogleAccount() {
        val client = googleSignInClient ?: run {
            toast("Google Sign-In initialize হয়নি")
            return
        }
        // Sign out first → forces the account chooser to show every time
        client.signOut().addOnCompleteListener {
            try {
                signInLauncher.launch(client.signInIntent)
            } catch (e: Exception) {
                toast("Sign-In launch failed: ${e.message}")
            }
        }
    }

    private fun handleSignInResult(data: Intent?) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val acc = task.getResult(ApiException::class.java)
            googleAccount = acc
            // Reset downstream
            availableSheets = emptyList(); selectedSheet = null
            availableTabs   = emptyList(); selectedTab   = ""
            updateAccountStep()
            loadSheetsForAccount()
        } catch (e: ApiException) {
            showErr("Sign-in failed (code ${e.statusCode})")
        } catch (e: Exception) {
            showErr("Sign-in error: ${e.message}")
        }
    }

    private fun updateAccountStep() {
        val acc = googleAccount
        if (acc == null) {
            cardSelectedAccount?.visibility = View.GONE
            tvPickAccountLabel?.text = "Sign in with Google"
            // Hide Next until account is selected
            if (connectStep == 1) btnNext?.visibility = View.GONE
        } else {
            cardSelectedAccount?.visibility = View.VISIBLE
            tvSelectedAccountName?.text  = acc.displayName ?: acc.email ?: "Google User"
            tvSelectedAccountEmail?.text = acc.email ?: ""
            tvPickAccountLabel?.text = "Switch account"
            // Show Next when account is ready
            if (connectStep == 1) btnNext?.visibility = View.VISIBLE
        }
    }

    // ── Drive API: list user's spreadsheets ──────────────────────────
    private fun loadSheetsForAccount() {
        val account = googleAccount ?: return
        val acctObj = account.account ?: run {
            showErr("Account info নেই")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                pbSheetLoad?.visibility = View.VISIBLE
                val ctx = context ?: return@launch
                val token = withContext(Dispatchers.IO) {
                    try {
                        GoogleAuthUtil.getToken(ctx, acctObj, OAUTH_SCOPE)
                    } catch (e: UserRecoverableAuthException) {
                        // Launch consent screen
                        withContext(Dispatchers.Main) {
                            try { recoverableLauncher.launch(e.intent) } catch (_: Exception) {}
                        }
                        null
                    }
                } ?: return@launch
                val sheets = withContext(Dispatchers.IO) { fetchDriveSpreadsheets(token) }
                availableSheets = sheets
                if (connectStep == 2 || stepView2?.visibility == View.VISIBLE) updateSheetPickerLabel()
            } catch (e: Exception) {
                showErr("Sheet load failed: ${e.message ?: "unknown"}")
            } finally {
                pbSheetLoad?.visibility = View.GONE
            }
        }
    }

    private fun fetchDriveSpreadsheets(accessToken: String): List<DriveFile> {
        val url = "https://www.googleapis.com/drive/v3/files" +
                "?q=" + java.net.URLEncoder.encode("mimeType='application/vnd.google-apps.spreadsheet' and trashed=false", "UTF-8") +
                "&fields=" + java.net.URLEncoder.encode("files(id,name)", "UTF-8") +
                "&pageSize=200" +
                "&orderBy=" + java.net.URLEncoder.encode("modifiedTime desc", "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Drive API ${resp.code}")
            val body = resp.body?.string() ?: return emptyList()
            val obj = JSONObject(body)
            val files = obj.optJSONArray("files") ?: return emptyList()
            val out = mutableListOf<DriveFile>()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val id   = f.optString("id", "")
                val name = f.optString("name", "")
                if (id.isNotEmpty()) out.add(DriveFile(id, name))
            }
            return out
        }
    }

    private fun updateSheetPickerLabel() {
        tvSelectedSheet?.text = selectedSheet?.name ?: "— Sheet বেছে নিন —"
        tvSelectedSheet?.setTextColor(
            android.graphics.Color.parseColor(if (selectedSheet != null) "#111827" else "#6B7280")
        )
    }

    private fun openSheetPickerDialog() {
        val ctx = context ?: return
        if (availableSheets.isEmpty()) {
            toast("Sheet লোড হচ্ছে, একটু অপেক্ষা করুন")
            return
        }

        val dp = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        // ── Root container ────────────────────────────────────────────
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
        }

        // ── Drag handle ───────────────────────────────────────────────
        val handle = android.view.View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E5E7EB"))
                cornerRadius = 4.dp().toFloat()
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(48.dp(), 4.dp()).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = 10.dp()
                bottomMargin = 10.dp()
            }
        }
        val handleWrapper = android.widget.LinearLayout(ctx).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            addView(handle)
        }
        root.addView(handleWrapper)

        // ── Header ────────────────────────────────────────────────────
        val headerRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(20.dp(), 4.dp(), 12.dp(), 12.dp())
        }
        val tvTitle = android.widget.TextView(ctx).apply {
            text = "Google Sheet বেছে নিন"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#111827"))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvCount = android.widget.TextView(ctx).apply {
            text = "${availableSheets.size} sheets"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#9CA3AF"))
            setPadding(0, 0, 8.dp(), 0)
        }
        headerRow.addView(tvTitle)
        headerRow.addView(tvCount)
        root.addView(headerRow)

        // ── Search bar ────────────────────────────────────────────────
        val searchWrapper = android.widget.FrameLayout(ctx).apply {
            setPadding(16.dp(), 0, 16.dp(), 12.dp())
        }
        val searchRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#F9FAFB"))
                setStroke(2, android.graphics.Color.parseColor("#E5E7EB"))
                cornerRadius = 12.dp().toFloat()
            }
            setPadding(14.dp(), 0, 14.dp(), 0)
        }
        // Search icon
        val tvSearchIcon = android.widget.TextView(ctx).apply {
            text = "🔍"
            textSize = 14f
            setPadding(0, 0, 8.dp(), 0)
        }
        val etSearch = android.widget.EditText(ctx).apply {
            hint = "Sheet এর নাম লিখুন..."
            setSingleLine(true)
            background = null
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#111827"))
            setHintTextColor(android.graphics.Color.parseColor("#9CA3AF"))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, 48.dp(), 1f)
        }
        // Clear button
        val tvClear = android.widget.TextView(ctx).apply {
            text = "✕"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#9CA3AF"))
            setPadding(8.dp(), 0, 0, 0)
            visibility = android.view.View.GONE
            isClickable = true
            isFocusable = true
        }
        searchRow.addView(tvSearchIcon)
        searchRow.addView(etSearch)
        searchRow.addView(tvClear)
        searchWrapper.addView(searchRow)
        root.addView(searchWrapper)

        // ── Divider ───────────────────────────────────────────────────
        root.addView(android.view.View(ctx).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#F3F4F6"))
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        // ── Sheet list ────────────────────────────────────────────────
        var filteredSheets = availableSheets.toMutableList()

        val scrollView = android.widget.ScrollView(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val listContainer = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16.dp(), 10.dp(), 16.dp(), 24.dp())
        }
        scrollView.addView(listContainer)
        root.addView(scrollView)

        // Empty state
        val tvEmpty = android.widget.TextView(ctx).apply {
            text = "🔍 কোনো sheet পাওয়া যায়নি"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#9CA3AF"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32.dp(), 0, 0)
            visibility = android.view.View.GONE
        }
        listContainer.addView(tvEmpty)

        fun buildSheetItem(sheet: DriveFile, isSelected: Boolean): android.widget.LinearLayout {
            return android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = if (isSelected)
                    resources.getDrawable(R.drawable.bg_sheet_item_selected, null)
                else
                    resources.getDrawable(R.drawable.bg_sheet_item_normal, null)
                setPadding(14.dp(), 14.dp(), 14.dp(), 14.dp())
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 8.dp()
                layoutParams = lp
                isClickable = true
                isFocusable = true

                // Spreadsheet emoji icon
                val tvIcon = android.widget.TextView(ctx).apply {
                    text = "📊"
                    textSize = 18f
                    setPadding(0, 0, 12.dp(), 0)
                }
                // Sheet name
                val tvName = android.widget.TextView(ctx).apply {
                    text = sheet.name
                    textSize = 13f
                    setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    setTextColor(if (isSelected) android.graphics.Color.parseColor("#E8380D") else android.graphics.Color.parseColor("#111827"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                // Checkmark if selected
                val tvCheck = android.widget.TextView(ctx).apply {
                    text = "✓"
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.parseColor("#E8380D"))
                    visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.GONE
                }
                addView(tvIcon)
                addView(tvName)
                addView(tvCheck)
            }
        }

        fun rebuildList(dialog: android.app.Dialog) {
            // Remove all except tvEmpty
            while (listContainer.childCount > 1) listContainer.removeViewAt(1)
            if (filteredSheets.isEmpty()) {
                tvEmpty.visibility = android.view.View.VISIBLE
                return
            }
            tvEmpty.visibility = android.view.View.GONE
            filteredSheets.forEach { sheet ->
                val isSelected = selectedSheet?.id == sheet.id
                val item = buildSheetItem(sheet, isSelected)
                item.setOnClickListener {
                    if (selectedSheet?.id != sheet.id) {
                        selectedSheet = sheet
                        selectedTab   = ""
                        availableTabs = emptyList()
                        updateSheetPickerLabel()
                        loadTabsForSheet()
                    } else {
                        updateSheetPickerLabel()
                    }
                    dialog.dismiss()
                }
                listContainer.addView(item)
            }
        }

        // ── Dialog ────────────────────────────────────────────────────
        val dialog = android.app.Dialog(ctx, com.google.android.material.R.style.Theme_MaterialComponents_Dialog)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.85).toInt())
            setGravity(android.view.Gravity.BOTTOM)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            // Rounded top corners
            decorView.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadii = floatArrayOf(24.dp().toFloat(), 24.dp().toFloat(), 24.dp().toFloat(), 24.dp().toFloat(), 0f, 0f, 0f, 0f)
            }
            attributes?.windowAnimations = android.R.style.Animation_InputMethod
        }

        rebuildList(dialog)

        // ── Search watcher ────────────────────────────────────────────
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                tvClear.visibility = if (query.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                filteredSheets = if (query.isEmpty()) availableSheets.toMutableList()
                else availableSheets.filter { it.name.lowercase().contains(query) }.toMutableList()
                rebuildList(dialog)
            }
        })

        tvClear.setOnClickListener {
            etSearch.setText("")
            etSearch.requestFocus()
        }

        dialog.show()
        etSearch.requestFocus()
    }

    // ── Sheets API: list tabs ────────────────────────────────────────
    private fun loadTabsForSheet() {
        val account = googleAccount ?: return
        val acctObj = account.account ?: return
        val sheet   = selectedSheet ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                pbTabLoad?.visibility = View.VISIBLE
                val ctx = context ?: return@launch
                val token = withContext(Dispatchers.IO) {
                    try { GoogleAuthUtil.getToken(ctx, acctObj, OAUTH_SCOPE) }
                    catch (e: UserRecoverableAuthException) {
                        withContext(Dispatchers.Main) {
                            try { recoverableLauncher.launch(e.intent) } catch (_: Exception) {}
                        }
                        null
                    }
                } ?: return@launch
                val tabs = withContext(Dispatchers.IO) { fetchSheetTabs(token, sheet.id) }
                availableTabs = tabs
                if (connectStep == 3 || stepView3?.visibility == View.VISIBLE) updateTabSpinner()
            } catch (e: Exception) {
                showErr("Tab load failed: ${e.message ?: "unknown"}")
            } finally {
                pbTabLoad?.visibility = View.GONE
            }
        }
    }

    private fun fetchSheetTabs(accessToken: String, sheetId: String): List<String> {
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$sheetId" +
                "?fields=" + java.net.URLEncoder.encode("sheets(properties(title))", "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Sheets API ${resp.code}")
            val body = resp.body?.string() ?: return emptyList()
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("sheets") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val props = s.optJSONObject("properties") ?: continue
                val title = props.optString("title", "")
                if (title.isNotEmpty()) out.add(title)
            }
            return out
        }
    }

    private fun updateTabSpinner() {
        val ctx = context ?: return
        val opts = listOf("— Tab বেছে নিন —") + availableTabs
        spinnerTab?.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, opts)
        val sel = availableTabs.indexOf(selectedTab)
        if (sel >= 0) spinnerTab?.setSelection(sel + 1) else spinnerTab?.setSelection(0)
    }

    // ── ManagePanel ───────────────────────────────────────────────────
    private fun renderManagePanel() {
        val conn = activeConn() ?: return
        val connNickname = conn.nickname.ifBlank { conn.sheetName }
        tvManageBranch?.text = "${branchLabel(activeBranch)}  ·  $connNickname"

        // Branch switcher removed — Manage shows only the specific sheet/branch user navigated to
        spinnerManageBranch?.visibility = View.GONE

        activeManageTab = "overview"
        renderManageTabs()
        renderSyncTab(conn)

        tvOvSheet?.text = conn.sheetName
        tvOvTab?.text   = conn.tabName
        tvOvCols?.text  = "${conn.columns.firstOrNull() ?: "A"}–${conn.columns.lastOrNull() ?: "J"} (${conn.columns.size}টি)"
        tvColPreviewMgr?.text = "${conn.columns.firstOrNull() ?: "A"} → ${conn.columns.lastOrNull() ?: "J"}  (${conn.columns.size} columns)"
        if (activeManageTab == "columns") fetchManageColPreview()
    }

    private fun renderManageTabs() {
        val red  = android.graphics.Color.parseColor("#E8380D")
        val grey = android.graphics.Color.parseColor("#6B7280")
        tabOverview?.setTextColor(if (activeManageTab == "overview") red else grey)
        tabColumns?.setTextColor (if (activeManageTab == "columns")  red else grey)
        tabSync?.setTextColor    (if (activeManageTab == "sync")     red else grey)
        indOverview?.visibility = if (activeManageTab == "overview") View.VISIBLE else View.INVISIBLE
        indColumns?.visibility  = if (activeManageTab == "columns")  View.VISIBLE else View.INVISIBLE
        indSync?.visibility     = if (activeManageTab == "sync")     View.VISIBLE else View.INVISIBLE
        cardOverview?.visibility = if (activeManageTab == "overview") View.VISIBLE else View.GONE
        cardColumns?.visibility  = if (activeManageTab == "columns")  View.VISIBLE else View.GONE
        cardSync?.visibility     = if (activeManageTab == "sync")     View.VISIBLE else View.GONE
    }

    private fun handleDisconnect() {
        val branch = activeBranch
        val connId = activeConnectionId
        connections[branch]?.removeAll { it.connectionId == connId }
        if (connections[branch].isNullOrEmpty()) connections.remove(branch)
        deleteFromFirebase(branch, connId)
        toast("🗑 Sheet disconnected")
        screen = Screen.BRANCH_SELECT
        render()
    }

    // ── Firebase ──────────────────────────────────────────────────────
    private fun activeConn(): SheetConn? =
        connections[activeBranch]?.find { it.connectionId == activeConnectionId }
            ?: connections[activeBranch]?.firstOrNull()

    private fun updateActiveConn(updated: SheetConn) {
        val list = connections.getOrPut(updated.branchId) { mutableListOf() }
        val idx = list.indexOfFirst { it.connectionId == updated.connectionId }
        if (idx >= 0) list[idx] = updated else list.add(updated)
    }

    private fun loadFromFirebase() {
        val owner = viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch {
            setBusy(true, "Loading...")
            try {
                val uid = auth.currentUser?.uid.orEmpty()
                if (uid.isBlank()) {
                    branches = emptyList()
                    branchInfos = emptyMap()
                    connections.clear()
                } else {
                    val branchIdsPath = "users/$uid/profile/company_info/branch_ids"
                    val userSnap = db.reference.child(branchIdsPath).get().await()
                    val assignedBranchIds = readBranchIds(userSnap)

                    val infos = mutableMapOf<String, BranchInfo>()
                    assignedBranchIds.forEach { id ->
                        val branchPath = "branches/$id"
                        val b = db.reference.child(branchPath).get().await()
                        val name = b.child("name").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: id
                        val code = b.child("branch_code").getValue(String::class.java).orEmpty()
                        val address = b.child("address").getValue(String::class.java).orEmpty()
                        val type = b.child("branch_type").getValue(String::class.java).orEmpty()
                        val status = b.child("status").getValue(String::class.java).orEmpty()
                        infos[id] = BranchInfo(id, name, code, address, type, status)
                    }

                    branches = assignedBranchIds
                    branchInfos = infos
                    activeBranch = when {
                        branches.size == 1 -> branches.first()
                        branches.contains(activeBranch) -> activeBranch
                        else -> ""
                    }
                    connections.clear()

                    val sheetSnap = db.reference.child("config/sheets").get().await()
                    // Note: load from connections/{push_id} new structure
                    sheetSnap.children.forEach { bs ->
                        val branchId = bs.key ?: return@forEach
                        if (!branches.contains(branchId)) return@forEach
                        val list = mutableListOf<SheetConn>()
                        bs.child("connections").children.forEach { connSnap ->
                            val connId    = connSnap.key ?: return@forEach
                            val sheetId   = connSnap.child("sheetId")  .getValue(String::class.java) ?: return@forEach
                            val sheetName = connSnap.child("sheetName").getValue(String::class.java) ?: ""
                            val tabName   = connSnap.child("tabName")  .getValue(String::class.java) ?: ""
                            val nickname  = connSnap.child("nickname") .getValue(String::class.java) ?: ""
                            val colS      = connSnap.child("colStart") .getValue(Int::class.java)    ?: 1
                            val colE      = connSnap.child("colEnd")   .getValue(Int::class.java)    ?: 10
                            val email     = connSnap.child("googleEmail").getValue(String::class.java) ?: ""
                            val by        = connSnap.child("connectedBy").getValue(String::class.java) ?: ""
                            val at        = connSnap.child("connectedAt").getValue(Long::class.java)   ?: 0L
                            val sRow      = connSnap.child("startRow")       .getValue(Int::class.java)
                            val eRow      = connSnap.child("endRow")         .getValue(Int::class.java)
                            val autoSync  = connSnap.child("autoSync")       .getValue(Boolean::class.java) ?: false
                            val interval  = connSnap.child("syncIntervalMin").getValue(Int::class.java)    ?: 30
                            @Suppress("UNCHECKED_CAST")
                            val colMap     = (connSnap.child("columnMapping").value as? Map<String, String>) ?: emptyMap()
                            val tgtNode    = connSnap.child("targetNode").getValue(String::class.java) ?: "courier/consignments"
                            list.add(SheetConn(connId, nickname, branchId, sheetId, sheetName, tabName, colS, colE, sRow, eRow, autoSync, interval, email, by, at, colMap, tgtNode))
                        }
                        if (list.isNotEmpty()) connections[branchId] = list
                    }
                }
            } catch (e: Exception) {
                Log.e("ConfigSheet", "Failed to load sheet config", e)
                toast("Sheet config load failed")
            } finally {
                if (isAdded) {
                    // Always show BRANCH_SELECT — user chooses which sheet to manage
                    screen = Screen.BRANCH_SELECT
                    render()
                    setBusy(false)
                }
            }
        }
    }

    private fun readBranchIds(snap: com.google.firebase.database.DataSnapshot): List<String> {
        if (!snap.exists()) return emptyList()
        return when (val raw = snap.value) {
            is String -> listOf(raw.trim()).filter { it.isNotBlank() }
            is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf { v -> v.isNotBlank() } }
            is Map<*, *> -> raw.mapNotNull { (key, value) ->
                when (value) {
                    is String -> value.trim().takeIf { it.isNotBlank() }
                    false, null -> null
                    else -> key?.toString()?.trim()?.takeIf { it.isNotBlank() }
                }
            }
            else -> snap.children.mapNotNull { child ->
                child.value?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    ?: child.key?.trim()?.takeIf { it.isNotBlank() }
            }
        }.distinct()
    }

    private fun saveToFirebase(conn: SheetConn) {
        val owner = viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch {
            try {
                val data = mapOf(
                    "nickname"        to conn.nickname,
                    "sheetId"         to conn.sheetId,
                    "sheetName"       to conn.sheetName,
                    "tabName"         to conn.tabName,
                    "colStart"        to conn.colStart,
                    "colEnd"          to conn.colEnd,
                    "startRow"        to (conn.startRow ?: 1),
                    "endRow"          to (conn.endRow   ?: 0),
                    "autoSync"        to conn.autoSync,
                    "syncIntervalMin" to conn.syncIntervalMin,
                    "googleEmail"     to conn.googleEmail,
                    "connectedBy"     to conn.connectedBy,
                    "connectedAt"     to conn.connectedAt,
                    "columnMapping"   to conn.columnMapping,
                    "targetNode"      to conn.targetNode,
                )
                val basePath = "config/sheets/${conn.branchId}/connections"
                val connId = conn.connectionId.ifBlank { db.reference.child(basePath).push().key ?: return@launch }
                db.reference.child("$basePath/$connId").setValue(data).await()
                db.reference.child("config/sheets/${conn.branchId}/history").push()
                    .setValue(data + mapOf("action" to "connected", "connectionId" to connId)).await()
            } catch (_: Exception) {}
        }
    }

    private fun deleteFromFirebase(branchId: String, connectionId: String) {
        val owner = viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch {
            try {
                db.reference.child("config/sheets/$branchId/connections/$connectionId").removeValue().await()
                db.reference.child("config/sheets/$branchId/history").push().setValue(mapOf(
                    "action"         to "disconnected",
                    "connectionId"   to connectionId,
                    "disconnectedBy" to (auth.currentUser?.uid ?: ""),
                    "disconnectedAt" to System.currentTimeMillis(),
                )).await()
            } catch (_: Exception) {}
        }
    }

    // ── Helper ────────────────────────────────────────────────────────
    private fun toast(msg: String) {
        try { Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }
        catch (_: Exception) {}
    }

    private fun setBusy(show: Boolean, text: String = "Loading...") {
        tvSheetBusy?.text = text
        sheetBusyOverlay?.visibility = if (show) View.VISIBLE else View.GONE
    }
}
