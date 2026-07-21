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
 *
 * Data models   → ConfigSheetModels.kt  (DriveFile, SheetConn, ColMapping, …)
 * Drive/Sheets API → ConfigSheetDriveApi.kt  (fetchDriveSpreadsheets, fetchSheetTabs)
 * Parse utilities  → ConfigSheetParseUtil.kt (parseColInput, colIndexToLetter, …)
 */
class ConfigSheetFragment : Fragment() {

    internal val db   = FirebaseDatabase.getInstance()
    internal val auth = FirebaseAuth.getInstance()

    internal val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    internal var googleSignInClient: GoogleSignInClient? = null

    // ── State machine ─────────────────────────────────────────────────
    // (ConfigScreen / ConfigPrimaryAction enums → ConfigSheetModels.kt)
    internal var screen       = ConfigScreen.BRANCH_SELECT
    internal var activeBranch = ""
    internal var connectStep  = 1   // 1=Account  2=Sheet  3=Tab  4=Columns

    internal var branches:    List<String>                  = emptyList()
    internal var branchInfos: Map<String, BranchInfo>       = emptyMap()
    internal var connections: MutableMap<String, MutableList<SheetConn>> = mutableMapOf()
    internal var activeConnectionId = ""   // connectionId of the conn being managed

    // ── Connect flow state (mirrors JSX) ──────────────────────────────
    internal var googleAccount:    GoogleSignInAccount? = null
    internal var availableSheets:  List<DriveFile>      = emptyList()
    internal var selectedSheet:    DriveFile?           = null
    internal var availableTabs:    List<String>         = emptyList()
    internal var selectedTab:      String               = ""

    // ── Views ─────────────────────────────────────────────────────────
    internal var root: FrameLayout? = null

    /* Branch select panel */
    internal var panelBranch:     View? = null
    internal var tvBranchLabel:   TextView? = null
    internal var spinnerBranch:   Spinner? = null
    internal var tvSingleBranch:  TextView? = null
    internal var tvBranchEmpty:   TextView? = null
    internal var layoutBranchTabs:     View? = null
    internal var tabBranchConnected:   TextView? = null
    internal var tabBranchUnconnected: TextView? = null
    internal var activeBranchTab = "connected" // "connected" | "unconnected"
    internal var expandedBranch: String? = null  // accordion: which branch is expanded
    internal var cardBranchInfo:  LinearLayout? = null
    internal var tvBranchInfoName: TextView? = null
    internal var tvBranchInfoCode: TextView? = null
    internal var tvBranchInfoAddress: TextView? = null
    internal var tvBranchInfoType: TextView? = null
    internal var tvBranchInfoStatus: TextView? = null
    internal var cardConnInfo:    LinearLayout? = null
    internal var tvConnInfoSheet: TextView? = null
    internal var tvConnInfoTab:   TextView? = null
    internal var tvConnInfoCols:  TextView? = null
    internal var btnBranchAction: Button? = null
    internal var sheetBusyOverlay: View? = null
    internal var tvSheetBusy: TextView? = null
    // Branch sections
    internal var sectionConnected:          LinearLayout? = null
    internal var containerConnectedBranches: LinearLayout? = null
    internal var sectionUnconnected:        LinearLayout? = null

    /* ConnectFlow */
    internal var panelConnect:        View? = null
    internal var tvConnBranchSub:     TextView? = null
    internal var step1Dot:  TextView? = null; private var step2Dot:  TextView? = null
    internal var step3Dot:  TextView? = null; private var step4Dot:  TextView? = null; private var step5Dot: TextView? = null
    internal var step1Line: View? = null; private var step2Line: View? = null; private var step3Line: View? = null; private var step4Line: View? = null
    internal var step1Lbl:  TextView? = null; private var step2Lbl:  TextView? = null
    internal var step3Lbl:  TextView? = null; private var step4Lbl:  TextView? = null; private var step5Lbl: TextView? = null
    internal var stepView1: View? = null; private var stepView2: View? = null
    internal var stepView3: View? = null; private var stepView4: View? = null; private var stepView5: View? = null
    internal var containerMapping: android.widget.LinearLayout? = null
    internal var tvNodeBreadcrumb: TextView? = null
    internal var containerNodeDropdowns: android.widget.LinearLayout? = null
    internal var layoutCreateNewNode: View? = null
    internal var etNewNodeName: EditText? = null
    internal var btnConfirmNewNode: Button? = null
    internal var btnCancelNewNode: Button? = null
    internal var btnResetNodePicker: TextView? = null

    // Path segments chosen so far, e.g. ["run_routes", "delivery_run"]
    internal var nodePickerPath = mutableListOf<String>()
    // Beyond this many children, a level is treated as a dynamic-key collection (e.g. run IDs)
    // rather than a meaningful category list — offer the tree preview instead of a dropdown.
    internal val MAX_DRILLABLE_CHILDREN = 15
    // Hidden marker child written under a "+ Create New" node so the otherwise-empty node
    // physically persists in courier/ (Firebase drops childless nodes) and shows up on the
    // next shallow fetch. Filtered out of every wizard listing/preview so it's never shown
    // as a selectable child, an example record, or a mappable field.
    internal val NODE_META_KEY = "__meta__"
    // Deepest dropdown row currently rendered — advanced only via the "+ Next level?" button,
    // so a new dropdown never appears automatically after a selection/confirm.
    internal var nodePickerRevealedDepth = 0
    // True once the user has explicitly confirmed a target node (via the tree-preview "Yes"
    // button, the true-leaf auto-commit, "+ Create New", or the manual Fetch Fields button).
    // Primary Key / Fields sections stay hidden until this is true.
    internal var nodeMappingConfirmed = false
    // Cache of fetched children per path (path joined with "/" -> list of child keys)
    internal val nodeChildrenCache = mutableMapOf<String, List<String>>()
    // Registry of node paths "created" via the wizard's "+ Create New" box but not yet
    // holding real synced data — so they still show up in the picker dropdown next time
    // instead of vanishing (Firebase treats an empty node as non-existent). Stored OUTSIDE
    // courier/ entirely (config/known_nodes) so it never pollutes the actual data tree that
    // CallCenterFragment/WorkerSpaceFragment iterate as real consignment/run records.
    // null = not yet loaded this session; loaded once and cached.
    internal var knownNodePaths: MutableList<String>? = null
    internal var courierChildNodes: List<String> = emptyList()
    // Moved here from mid-file (was declared just above fetchChildKeysAt) as part of the
    // ConfigSheetNodePicker.kt module split — extension functions can't hold their own
    // backing-field state, so these two stay on the fragment like the rest of node-picker state.
    internal var nodePreviewData: Map<String, String> = emptyMap()
    internal var nodePreviewExpanded = true
    internal var etTargetNode:     EditText? = null
    internal var btnFetchFields:   android.widget.Button? = null
    internal var pbFetchFields:    ProgressBar? = null
    internal var tvFetchStatus:    TextView? = null
    internal var btnAddMappingField: TextView? = null
    internal var spinnerPrimaryKey: Spinner? = null  // LEGACY — no longer bound, kept to avoid touching unrelated code
    internal var containerPkBuilder: android.widget.LinearLayout? = null
    internal var btnAddPkPart: TextView? = null
    internal var tvPkPreview: TextView? = null

    // Step 1 - Account picker
    internal var cardSelectedAccount:   View? = null
    internal var tvSelectedAccountName: TextView? = null
    internal var tvSelectedAccountEmail:TextView? = null
    internal var btnPickAccount:        View? = null
    internal var tvPickAccountLabel:    TextView? = null

    // Step 2 - Sheet picker (searchable dialog)
    internal var tvSelectedSheet: TextView? = null
    internal var pbSheetLoad:     ProgressBar? = null

    // Step 3 - Tab spinner
    internal var spinnerTab: Spinner? = null
    internal var pbTabLoad:  ProgressBar? = null

    // Step 4 - column range + live preview + summary
    internal var etColStart:      EditText? = null
    internal var etColEnd:        EditText? = null
    internal var btnDefineRow:    TextView? = null
    internal var layoutRowRange:  View? = null
    internal var etStartRow:      EditText? = null
    internal var etEndRow:        EditText? = null
    internal var isRowRangeVisible = false
    internal var tvColPreview:    TextView? = null
    internal var tvLivePreview:   TextView? = null
    internal var scrollLivePreview: HorizontalScrollView? = null
    internal var tableLivePreview: TableLayout? = null
    internal var pbPreviewLoad:   ProgressBar? = null
    internal var pbColPreviewMgr: ProgressBar? = null
    internal var tvSummary:       TextView? = null

    // Nav buttons
    internal var btnBack:    Button? = null
    internal var btnNext:    Button? = null
    internal var btnConnect: Button? = null
    internal var btnCancelConn: View? = null
    internal var etNickname:    EditText? = null
    internal var tvConnError: TextView? = null

    /* ManagePanel */
    internal var panelManage:     View? = null
    internal var tvManageBranch:  TextView? = null
    internal var spinnerManageBranch: Spinner? = null
    internal var tabOverview:     TextView? = null; private var tabColumns: TextView? = null; private var tabSync: TextView? = null
    internal var indOverview:     View? = null;     private var indColumns: View? = null;     private var indSync: View? = null
    internal var cardOverview:    View? = null
    internal var cardColumns:     View? = null
    internal var cardSync:        View? = null
    internal var tvOvSheet:       TextView? = null; private var tvOvTab: TextView? = null; private var tvOvCols: TextView? = null
    internal var tvColPreviewMgr:     TextView? = null
    internal var scrollColPreviewMgr: android.widget.HorizontalScrollView? = null
    internal var tableColPreviewMgr:  android.widget.TableLayout? = null
    internal var btnColChange:    Button? = null
    internal var btnManReconnect: Button? = null;   private var btnManDisconn: Button? = null
    internal var btnManBack:      View? = null
    internal var btnSyncNow:      Button? = null
    internal var switchAutoSync:  android.widget.Switch? = null
    internal var btnSyncGear:     android.widget.ImageView? = null
    internal var tvSyncIntervalLabel: TextView? = null
    internal var tvLastSynced:    TextView? = null

    internal var activeManageTab = "overview"
    internal var previewJob: kotlinx.coroutines.Job? = null
    internal var isRangeEdit = false   // true = opened from Manage → Positioning, not full reconnect
    internal var selectedNickname = ""   // nickname entered in step 3

    // Step 5 primary button can behave as Connect (new), Save (edited existing), or Exit Wizard
    // (existing connection reopened with zero changes). Decided centrally in
    // updateConnectButtonState() and read by the click handler.
    // (ConfigPrimaryAction enum → ConfigSheetModels.kt)
    internal var primaryAction = ConfigPrimaryAction.NEW

    // Step 5 — column mapping
    // Firebase field → column letter selected by user
    internal val pendingMapping = mutableMapOf<String, ColMapping>()
    // Object-type fields: fieldName → Pair(keySpec, valueSpec)
    // spec format: "col:A" (dynamic, column letter) or "fixed:someText" (constant value)
    internal val pendingObjectMapping = mutableMapOf<String, ObjectColMapping>()
    // Track which custom fields are "object" type (vs default "key"/flat type)
    internal val objectTypeFields = mutableSetOf<String>()
    internal var targetNode = "courier/consignments"
    internal var primaryKeyField = ""  // LEGACY — colLetter selected as node key
    // NEW — composite primary key builder state (prefix + column parts, in order)
    internal val pendingPkParts = mutableListOf<PkPart>()
    // Custom fields added manually via "+ Add Field" — fieldName to label
    internal val customMappingFields = mutableListOf<Pair<String, String>>()
    // Headers fetched from sheet (letter → header text)
    internal var sheetHeaders: Map<String, String> = emptyMap()
    // Once true, we stop auto-adjusting Col End from detected headers — either the user typed
    // in it themselves, or it was restored from an already-saved connection.
    internal var colEndUserModified = false
    internal var isAutoAdjustingColEnd = false
    // First actual data row from the sheet (colLetter -> cell text), used to preview
    // primary key / field mapping with real values instead of placeholders.
    internal var sampleSheetRow: Map<String, String> = emptyMap()

    // All Firebase fields for orders/
    // Dynamic keys fetched from Firebase node — replaces hardcoded mappingFields
    internal val fetchedNodeKeys = mutableListOf<String>()  // keys from Firebase first record

    // Activity-result launcher for Google Sign-In
    internal val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: androidx.activity.result.ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            handleSignInResult(result.data)
        } else {
            // User cancelled — silently
        }
    }

    // Recovery launcher (in case getToken throws UserRecoverableAuthException)
    internal val recoverableLauncher = registerForActivityResult(
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
                .requestScopes(Scope(ConfigSheetDriveApi.SCOPE_DRIVE), Scope(ConfigSheetDriveApi.SCOPE_SHEETS))
                .build()
            googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)

            // Restore last signed-in account ONLY if its email matches what THIS feature
            // (Sheets) was last connected with — see PREFS_FILE_NAME doc comment below for
            // why we can't just trust GoogleSignIn.getLastSignedInAccount() alone (it's a
            // device-wide cache shared with ConfigConnectorsFragment's Connectors tab).
            // Logic lives in GoogleSignInHelper (shared with ConfigConnectorsFragment).
            googleAccount = GoogleSignInHelper.restoreOwnAccountIfMatching(
                context = requireContext(),
                prefsFileName = PREFS_FILE_NAME,
                requiredScopes = listOf(Scope(ConfigSheetDriveApi.SCOPE_DRIVE), Scope(ConfigSheetDriveApi.SCOPE_SHEETS))
            )
        } catch (_: Exception) { /* defensive: never crash on init */ }
    }

    /**
     * Per-feature Google-account isolation — mirrors ConfigConnectorsFragment's identical
     * guard. GoogleSignIn.getLastSignedInAccount() / GoogleSignInClient.signOut() are both
     * DEVICE-WIDE, shared with the Connectors tab's own GoogleSignInClient. Without this,
     * switching accounts in one tab would silently affect the other. Each feature now
     * remembers, in its OWN SharedPreferences file, the email it last connected with, and
     * only trusts the shared cache when it matches.
     */
    internal val PREFS_FILE_NAME = "sheets_google_account"

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

    internal fun bindViews(view: View) {
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
        tvNodeBreadcrumb        = view.findViewById(R.id.tvNodeBreadcrumb)
        containerNodeDropdowns  = view.findViewById(R.id.containerNodeDropdowns)
        layoutCreateNewNode     = view.findViewById(R.id.layoutCreateNewNode)
        etNewNodeName           = view.findViewById(R.id.etNewNodeName)
        btnConfirmNewNode       = view.findViewById(R.id.btnConfirmNewNode)
        btnCancelNewNode        = view.findViewById(R.id.btnCancelNewNode)
        btnResetNodePicker      = view.findViewById(R.id.btnResetNodePicker)
        etTargetNode        = view.findViewById(R.id.etTargetNode)
        btnFetchFields      = view.findViewById(R.id.btnFetchFields)
        pbFetchFields       = view.findViewById(R.id.pbFetchFields)
        tvFetchStatus       = view.findViewById(R.id.tvFetchStatus)
        btnAddMappingField  = view.findViewById(R.id.btnAddMappingField)
        spinnerPrimaryKey   = view.findViewById(R.id.spinnerPrimaryKey)
        containerPkBuilder  = view.findViewById(R.id.containerPkBuilder)
        btnAddPkPart        = view.findViewById(R.id.btnAddPkPart)
        tvPkPreview         = view.findViewById(R.id.tvPkPreview)
        btnAddPkPart?.setOnClickListener {
            pendingPkParts.add(PkPart("fixed", ""))
            renderPkBuilder()
        }

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

    internal fun attachListeners() {
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
            if (conn != null) { activeConnectionId = conn.connectionId; screen = ConfigScreen.MANAGING; render() }
            else              { screen = ConfigScreen.CONNECTING; connectStep = 1; clearConnectForm(); render() }
        }

        btnCancelConn?.setOnClickListener {
            if (isRangeEdit) {
                isRangeEdit = false
                screen = ConfigScreen.MANAGING
            } else {
                screen = ConfigScreen.BRANCH_SELECT
            }
            render()
        }
        btnManBack?.setOnClickListener    { screen = ConfigScreen.BRANCH_SELECT; render() }

        btnNext?.setOnClickListener { advanceStep() }
        btnBack?.setOnClickListener { if (connectStep > 1) { connectStep--; renderConnectStep() } }
        btnConnect?.setOnClickListener {
            if (primaryAction == ConfigPrimaryAction.EXIT) exitWizardNoChanges() else handleConnect()
        }

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

        btnManReconnect?.setOnClickListener { screen = ConfigScreen.CONNECTING; connectStep = 1; prefillConnectForm(); render() }
        btnColChange?.setOnClickListener { openRangeEditor() }
        btnManDisconn?.setOnClickListener   { handleDisconnect() }

        btnFetchFields?.setOnClickListener {
            val suffix = etTargetNode?.text?.toString()?.trim()?.trim('/') ?: ""
            if (suffix.isBlank()) { showErr("Node path দিন (courier/ এর পরের অংশ)"); return@setOnClickListener }
            val node = "courier/$suffix"
            targetNode = node
            nodeMappingConfirmed = true
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
        etColEnd?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!isAutoAdjustingColEnd) colEndUserModified = true
            }
        })

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
    internal fun render() {
        if (!isAdded) return
        panelBranch?.visibility  = if (screen == ConfigScreen.BRANCH_SELECT) View.VISIBLE else View.GONE
        panelConnect?.visibility = if (screen == ConfigScreen.CONNECTING)    View.VISIBLE else View.GONE
        panelManage?.visibility  = if (screen == ConfigScreen.MANAGING)      View.VISIBLE else View.GONE

        when (screen) {
            ConfigScreen.BRANCH_SELECT -> updateBranchSpinner()
            ConfigScreen.CONNECTING    -> { tvConnBranchSub?.text = "Branch: ${branchLabel(activeBranch)}"; renderConnectStep() }
            ConfigScreen.MANAGING      -> renderManagePanel()
        }
    }

    // Branch select screen (updateBranchSpinner, renderBranchSections, etc.) now lives in
    // ConfigSheetBranchUi.kt — see that file's header comment for why.

    // ── ConnectFlow steps ─────────────────────────────────────────────
    internal fun renderConnectStep() {
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
                    dot?.background = roundBg(context!!.getColor(R.color.theme_border))
                    dot?.text = "$n"
                    dot?.setTextColor(context!!.getColor(R.color.theme_text_muted))
                }
            }
        }
        styleDot(step1Dot, 1); styleDot(step2Dot, 2); styleDot(step3Dot, 3); styleDot(step4Dot, 4); styleDot(step5Dot, 5)

        // Step lines
        val lineColor = android.graphics.Color.parseColor("#16A34A")
        val lineGrey  = context!!.getColor(R.color.theme_border)
        step1Line?.setBackgroundColor(if (connectStep > 1) lineColor else lineGrey)
        step2Line?.setBackgroundColor(if (connectStep > 2) lineColor else lineGrey)
        step3Line?.setBackgroundColor(if (connectStep > 3) lineColor else lineGrey)
        step4Line?.setBackgroundColor(if (connectStep > 4) lineColor else lineGrey)

        // Step labels
        val green = android.graphics.Color.parseColor("#16A34A")
        val dark  = context!!.getColor(R.color.theme_text_primary)
        val grey  = context!!.getColor(R.color.theme_text_muted)
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
        // Text + enabled state (Connect / Save / Exit Wizard) is decided centrally so it stays in
        // sync with live dirty-detection.
        if (connectStep == 5) updateConnectButtonState()

        // Cancel button label changes in range edit mode
        (btnCancelConn as? TextView)?.text = if (isRangeEdit) "Cancel" else "✕"

        tvConnError?.visibility = View.GONE

        // Per-step UI
        when (connectStep) {
            1 -> updateAccountStep()
            2 -> updateSheetPickerLabel()
            3 -> updateTabSpinner()
            4 -> { updateColPreview(); updateSummary(); scheduleLivePreview() }
            5 -> {
                fetchCourierChildNodes()
                // On reconnect, sheetHeaders may be empty (not fetched yet) even though
                // pendingMapping is already populated. Fetch the header row first so saved
                // column letters can be pre-selected in the mapping spinners.
                if (sheetHeaders.isEmpty()
                    && (pendingMapping.isNotEmpty() || pendingObjectMapping.isNotEmpty())
                    && googleAccount != null && selectedSheet != null && selectedTab.isNotBlank()) {
                    val account = googleAccount!!
                    val sheet   = selectedSheet!!
                    val tab     = selectedTab
                    val conn    = activeConn()
                    val s       = conn?.colStart ?: etColStart?.text?.toString()?.trim()?.toIntOrNull() ?: 1
                    val e       = conn?.colEnd   ?: etColEnd?.text?.toString()?.trim()?.toIntOrNull() ?: 10
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val token = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                try { com.google.android.gms.auth.GoogleAuthUtil.getToken(requireContext(), account.account!!, ConfigSheetDriveApi.OAUTH_SCOPE) }
                                catch (_: Exception) { null }
                            }
                            if (token != null) {
                                val startLetter = colIndexToLetter(s)
                                val endLetter   = colIndexToLetter(e)
                                val sRow = etStartRow?.text?.toString()?.trim()?.toIntOrNull() ?: 1
                                val range = "$tab!${startLetter}${sRow}:${endLetter}${sRow}"
                                val encoded = java.net.URLEncoder.encode(range, "UTF-8")
                                val url = "https://sheets.googleapis.com/v4/spreadsheets/${sheet.id}/values/$encoded"
                                val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val req = okhttp3.Request.Builder().url(url)
                                        .header("Authorization", "Bearer $token").build()
                                    httpClient.newCall(req).execute().use { resp ->
                                        if (!resp.isSuccessful) return@withContext null
                                        val arr = org.json.JSONObject(resp.body?.string() ?: "").optJSONArray("values")
                                            ?: return@withContext null
                                        if (arr.length() == 0) return@withContext null
                                        val row = arr.getJSONArray(0)
                                        (0 until row.length()).map { j -> row.optString(j, "") }
                                    }
                                }
                                val newHeaders = mutableMapOf<String, String>()
                                rows?.forEachIndexed { idx, header ->
                                    val letter = colIndexToLetter(s + idx)
                                    if (header.isNotBlank()) newHeaders[letter] = header
                                }
                                if (newHeaders.isNotEmpty()) sheetHeaders = newHeaders
                            }
                        } catch (_: Exception) { }
                        if (isAdded) renderMappingStep()
                    }
                } else {
                    renderMappingStep()
                }
            }
        }
    }

    internal val colWatcher = object : android.text.TextWatcher {
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
    // parseColInput / colIndexToLetter → ConfigSheetParseUtil
    internal fun parseColInput(raw: String)  = ConfigSheetParseUtil.parseColInput(raw)
    internal fun colIndexToLetter(n: Int)    = ConfigSheetParseUtil.colIndexToLetter(n)

    internal fun updateColPreview() {
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

    internal fun updateSummary() {
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

    internal fun scheduleLivePreview() {
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

    internal suspend fun fetchAndShowLivePreview(
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
                try { GoogleAuthUtil.getToken(ctx, acctObj, ConfigSheetDriveApi.OAUTH_SCOPE) }
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

                // Default Col End to the last non-blank header cell (e.g. header data only in
                // col 1-3 → default end = 3), instead of whatever wide range was requested.
                // Only when the user hasn't manually set Col End themselves.
                if (!colEndUserModified) {
                    val lastNonBlankOffset = headerRow.indexOfLast { it.isNotBlank() }
                    if (lastNonBlankOffset >= 0) {
                        val autoEnd = colStart + lastNonBlankOffset
                        val currentEnd = parseColInput(etColEnd?.text?.toString() ?: "") ?: colEnd
                        if (autoEnd in colStart..colEnd && autoEnd != currentEnd) {
                            isAutoAdjustingColEnd = true
                            etColEnd?.setText(autoEnd.toString())
                            isAutoAdjustingColEnd = false
                            updateColPreview()
                            updateSummary()
                            scheduleLivePreview()
                            return
                        }
                    }
                }
            }
            // Capture first actual data row (row after header) so Step 5 can preview
            // primary key / field mapping with real values instead of placeholders.
            val firstDataRow = rows.getOrNull(1)
            if (firstDataRow != null) {
                val newSample = mutableMapOf<String, String>()
                firstDataRow.forEachIndexed { idx, text ->
                    val letter = colIndexToLetter(colStart + idx)
                    newSample[letter] = text
                }
                sampleSheetRow = newSample
            }
            renderLivePreviewTable(rows, colStart, colEnd)

        } catch (e: Exception) {
            tvLivePreview?.text = "⚠ Preview error: ${e.message?.take(60)}"
            scrollLivePreview?.visibility = View.GONE
        } finally {
            pbPreviewLoad?.visibility = View.GONE
        }
    }

    internal fun renderLivePreviewTable(
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
    internal fun fetchManageColPreview() {
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
                    try { GoogleAuthUtil.getToken(ctx, acctObj, ConfigSheetDriveApi.OAUTH_SCOPE) }
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

    internal suspend fun syncSheetToFirebase(conn: SheetConn) {
        val ctx = context ?: return
        val account = googleAccount ?: run { toast("Google account নেই"); return }
        val acctObj = account.account ?: return

        if (conn.columnMapping.isEmpty()) {
            toast("⚠ Column mapping নেই — Step 5 complete করুন")
            return
        }
        val pkParts: List<PkPart> = conn.effectivePkParts().ifEmpty {
            conn.columnMapping["consignmentId"]?.col?.let { listOf(PkPart("col", it)) } ?: emptyList()
        }
        if (pkParts.isEmpty()) {
            toast("⚠ Primary key select করা নেই — Step 5 এ select করুন")
            return
        }

        setBusy(true, "Sheet fetch করছে...")

        try {
            // ── 1. Get token ─────────────────────────────────────────
            val token = withContext(Dispatchers.IO) {
                try { GoogleAuthUtil.getToken(ctx, acctObj, ConfigSheetDriveApi.OAUTH_SCOPE) }
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
            val headerRow = allRows.firstOrNull() ?: emptyList()
            val dataRows  = if (allRows.size > 1) allRows.drop(1) else allRows

            // ── 3. Column drift detection ─────────────────────────────
            val currentHeaders = headerRow.mapIndexed { idx, header ->
                colIndexToLetter(conn.colStart + idx) to header.trim()
            }.toMap()

            /** Levenshtein similarity 0..1 between two strings */
            fun similarity(a: String, b: String): Float {
                if (a == b) return 1f
                if (a.isEmpty() || b.isEmpty()) return 0f
                val la = a.lowercase(); val lb = b.lowercase()
                val dp = Array(la.length + 1) { IntArray(lb.length + 1) }
                for (i in 0..la.length) dp[i][0] = i
                for (j in 0..lb.length) dp[0][j] = j
                for (i in 1..la.length) for (j in 1..lb.length) {
                    dp[i][j] = if (la[i-1] == lb[j-1]) dp[i-1][j-1]
                    else minOf(dp[i-1][j], dp[i][j-1], dp[i-1][j-1]) + 1
                }
                return 1f - dp[la.length][lb.length].toFloat() / maxOf(la.length, lb.length)
            }

            val FUZZY_WARN  = 0.80f  // ≥ 80% similar → yellow warning, sync proceeds
            val FUZZY_MATCH = 0.95f  // ≥ 95% similar → treat as "moved" (auto-correct col)

            val driftFields    = mutableListOf<Triple<String, String, String>>()
            val resolvedMapping = mutableMapOf<String, String>()

            conn.columnMapping.forEach { (field, cm) ->
                if (cm.col.isBlank()) return@forEach
                val currentHeader = currentHeaders[cm.col]
                when {
                    // ✅ Exact match at same column
                    currentHeader != null && currentHeader.equals(cm.header, ignoreCase = true) ->
                        resolvedMapping[field] = cm.col

                    // ✅ Header moved to a different column (exact match elsewhere)
                    cm.header.isNotBlank() -> {
                        val exactNewCol = currentHeaders.entries
                            .firstOrNull { it.value.equals(cm.header, ignoreCase = true) }?.key
                        if (exactNewCol != null) {
                            resolvedMapping[field] = exactNewCol
                            driftFields.add(Triple(field, cm.header, "moved: ${cm.col} → $exactNewCol"))
                        } else {
                            // ⚠ No exact match — look for fuzzy match
                            val best = currentHeaders.entries
                                .map { it to similarity(cm.header, it.value) }
                                .maxByOrNull { it.second }
                            val bestSim = best?.second ?: 0f
                            val bestCol = best?.first?.key ?: ""
                            val bestHdr = best?.first?.value ?: ""
                            when {
                                bestSim >= FUZZY_MATCH -> {
                                    // Very close match — auto-correct silently
                                    resolvedMapping[field] = bestCol
                                    driftFields.add(Triple(field, cm.header,
                                        "moved~: ${cm.col} → $bestCol (\"$bestHdr\", ${(bestSim*100).toInt()}%)"))
                                }
                                bestSim >= FUZZY_WARN -> {
                                    // Fuzzy match — warn but still sync with original col
                                    resolvedMapping[field] = cm.col
                                    driftFields.add(Triple(field, cm.header,
                                        "fuzzy: \"${cm.header}\" ≈ \"$bestHdr\" ($bestCol, ${(bestSim*100).toInt()}%) — মূল column ${cm.col} ব্যবহার করা হচ্ছে"))
                                }
                                else ->
                                    driftFields.add(Triple(field, cm.header, "missing"))
                                    .also { resolvedMapping[field] = cm.col }
                            }
                        }
                    }
                    else -> resolvedMapping[field] = cm.col
                }
            }

            // Object field drift check (key col + value col separately)
            conn.objectColumnMapping.forEach { (field, ocm) ->
                listOf(ocm.keyCol to ocm.keyHeader, ocm.valueCol to ocm.valueHeader).forEach { (col, savedHdr) ->
                    if (col.isBlank() || savedHdr.isBlank() || col.startsWith("fixed:")) return@forEach
                    val currentHdr = currentHeaders[col]
                    if (currentHdr == null || !currentHdr.equals(savedHdr, ignoreCase = true)) {
                        val sim = if (currentHdr != null) similarity(savedHdr, currentHdr) else 0f
                        val label = if (col == ocm.keyCol) "$field.key" else "$field.value"
                        when {
                            sim >= FUZZY_WARN ->
                                driftFields.add(Triple(label, savedHdr,
                                    "fuzzy: \"$savedHdr\" ≈ \"${currentHdr ?: ""}\" ($col, ${(sim*100).toInt()}%)"))
                            else ->
                                driftFields.add(Triple(label, savedHdr, "missing"))
                        }
                    }
                }
            }

            // Primary key part drift check
            conn.effectivePkParts().forEach { part ->
                if (part.type != "col" && part.type != "date") return@forEach
                if (part.header.isBlank()) return@forEach
                val currentHdr = currentHeaders[part.value]
                if (currentHdr == null || !currentHdr.equals(part.header, ignoreCase = true)) {
                    val sim = if (currentHdr != null) similarity(part.header, currentHdr) else 0f
                    driftFields.add(Triple("primaryKey[${part.value}]", part.header,
                        if (sim >= FUZZY_WARN) "fuzzy: \"${part.header}\" ≈ \"${currentHdr ?: ""}\" (${(sim*100).toInt()}%)"
                        else "missing"))
                }
            }

            if (driftFields.isNotEmpty()) {
                setBusy(false)
                val moved   = driftFields.filter { it.third.startsWith("moved:") }
                val movedF  = driftFields.filter { it.third.startsWith("moved~:") }
                val fuzzy   = driftFields.filter { it.third.startsWith("fuzzy:") }
                val missing = driftFields.filter { it.third == "missing" }
                val message = buildString {
                    if (moved.isNotEmpty()) {
                        append("📍 Column সরে গেছে (auto-corrected):\n")
                        moved.forEach { (f, h, i) -> append("  • \"$h\" ($f): ${i.removePrefix("moved: ")}\n") }
                        append("\n")
                    }
                    if (movedF.isNotEmpty()) {
                        append("📍 Column খুব কাছাকাছি (auto-corrected):\n")
                        movedF.forEach { (f, h, i) -> append("  • \"$h\" ($f): ${i.removePrefix("moved~: ")}\n") }
                        append("\n")
                    }
                    if (fuzzy.isNotEmpty()) {
                        append("⚠ Header পরিবর্তন সন্দেহ (fuzzy match — সতর্কতার সাথে confirm করুন):\n")
                        fuzzy.forEach { (f, _, i) -> append("  • $f: ${i.removePrefix("fuzzy: ")}\n") }
                        append("\n")
                    }
                    if (missing.isNotEmpty()) {
                        append("❌ Header পাওয়া যায়নি (column পরীক্ষা করুন):\n")
                        missing.forEach { (f, h, _) -> append("  • \"$h\" ($f)\n") }
                    }
                }
                val hasMissing = missing.isNotEmpty()
                if (!isAdded) return
                val proceed = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle(if (hasMissing) "❌ Column Drift সনাক্ত!" else "⚠ Column পরিবর্তন")
                        .setMessage(message.trim())
                        .setPositiveButton(if (hasMissing) "Reposition করুন" else "এভাবেই Sync করুন") { _, _ ->
                            if (hasMissing) { openRangeEditor(); cont.resume(false) {} }
                            else cont.resume(true) {}
                        }
                        .setNegativeButton("বাতিল") { _, _ -> cont.resume(false) {} }
                        .setCancelable(false)
                        .show()
                }
                if (!proceed) return
                setBusy(true, "Sync করছে...")
            }

            if (dataRows.isEmpty()) { setBusy(false); toast("⚠ Sheet এ কোনো data নেই"); return }

            // ── 3. Build colLetter → index map ────────────────────────
            fun letterToIndex(letter: String): Int {
                val idx = parseColInput(letter) ?: return -1
                return idx - conn.colStart  // 0-based within fetched range
            }

            // Builds the composite primary key for one row by concatenating its parts in order.
            // If ANY part fails to resolve (a "date" part that won't parse, OR a "col" part whose
            // cell is blank — e.g. agentSystemId missing) the whole key comes back blank so the
            // existing conId.isBlank() check skips the row — a malformed/incomplete key must never
            // be produced (e.g. "run_040726_" with the agent segment silently missing).
            fun buildPrimaryKey(row: List<String>): String {
                var partFailed = false
                val key = pkParts.joinToString("") { part ->
                    when (part.type) {
                        "fixed" -> part.value
                        "col" -> {
                            val idx = letterToIndex(part.value)
                            val v = if (idx < 0) "" else row.getOrElse(idx) { "" }.trim()
                            if (v.isBlank()) partFailed = true
                            v
                        }
                        "date" -> {
                            val idx = letterToIndex(part.value)
                            val raw = if (idx < 0) "" else row.getOrElse(idx) { "" }.trim()
                            val millis = if (raw.isNotBlank()) parseSheetTimestamp(raw) else null
                            if (millis == null) {
                                partFailed = true
                                ""
                            } else {
                                java.text.SimpleDateFormat("ddMMyy", java.util.Locale.ENGLISH)
                                    .format(java.util.Date(millis))
                            }
                        }
                        else -> ""
                    }
                }
                return if (partFailed) "" else key
            }

            // ── 4. Process rows ───────────────────────────────────────
            setBusy(true, "Firebase sync করছে...")

            var inserted = 0; var updated = 0; var skipped = 0
            val dateIssues = mutableListOf<String>()
            // Captures the ACTUAL exception from a failed updateChildren() call, keyed by
            // conId — previously swallowed silently (catch (_: Exception) {}), which is why
            // sync could report "Inserted/Updated" counts while Firebase showed no change:
            // inserted++/updated++ happen based on what we INTENDED to write, not on
            // confirmation the write succeeded. Now surfaced in the summary dialog so a
            // permission-denied or validation-rule rejection is visible instead of silent.
            val writeFailures = mutableListOf<String>()
            // systemIds seen in this sync that had NO branch_ids on their users/{uid} profile —
            // means runs_by_branchId was silently never written for their runs. Deduped (Set)
            // since the same agent can appear across many rows.
            val branchlessAgentSystemIds = mutableSetOf<String>()
            val basePath = conn.targetNode.trimEnd('/')

            // ── runs_by_branchId support: resolve each agent's branch_ids ON DEMAND, cached
            // per systemId — NOT a bulk scan of the whole users/ tree. For each distinct
            // agentSystemId encountered in the sheet: users_by_systemId/{sysId}/uid (O(1)
            // reverse-index lookup) -> users/{uid}/profile/company_info/branch_ids (O(1)
            // direct read). Cached so an agent with many rows/consignments in this sync is
            // only fetched once, not once per row.
            val agentBranchCache = mutableMapOf<String, List<String>>()
            val branchResolveFailures = mutableListOf<String>()     // the lookup itself threw

            suspend fun resolveAgentBranchIds(systemId: String): List<String> {
                agentBranchCache[systemId]?.let { return it }
                val branchIds = try {
                    val uid = withContext(Dispatchers.IO) {
                        db.reference.child("users_by_systemId/$systemId/uid").get().await()
                            .getValue(String::class.java)?.trim()
                    }
                    if (uid.isNullOrBlank()) {
                        branchlessAgentSystemIds.add(systemId)
                        emptyList()
                    } else {
                        val ids = withContext(Dispatchers.IO) {
                            db.reference.child("users/$uid/profile/company_info/branch_ids")
                                .get().await().children.mapNotNull { it.getValue(String::class.java) }
                        }
                        if (ids.isEmpty()) branchlessAgentSystemIds.add(systemId)
                        ids
                    }
                } catch (e: Exception) {
                    branchResolveFailures.add("$systemId → ${e.message?.take(80) ?: e.javaClass.simpleName}")
                    emptyList()
                }
                agentBranchCache[systemId] = branchIds
                return branchIds
            }

            for (row in dataRows) {
                val conId = buildPrimaryKey(row)
                if (conId.isBlank()) { skipped++; continue }

                // Build field map from column mapping (drift-corrected: resolvedMapping)
                val fieldMap = mutableMapOf<String, Any>()
                resolvedMapping.forEach { (field, colLetter) ->
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
                val createdRaw = resolvedMapping["createdAt"]?.let { colLetter ->
                    val idx = letterToIndex(colLetter)
                    if (idx >= 0) row.getOrElse(idx) { "" }.trim() else ""
                } ?: ""
                val updatedRaw = resolvedMapping["updatedAt"]?.let { colLetter ->
                    val idx = letterToIndex(colLetter)
                    if (idx >= 0) row.getOrElse(idx) { "" }.trim() else ""
                } ?: ""

                var createdAtMillis: Long? = if (createdRaw.isNotBlank()) parseSheetTimestamp(createdRaw) else null
                var updatedAtMillis: Long? = if (updatedRaw.isNotBlank()) parseSheetTimestamp(updatedRaw) else null

                if (createdRaw.isNotBlank() && createdAtMillis == null) {
                    dateIssues.add("$conId → createdAt: বোঝা যায়নি (\"$createdRaw\")")
                }
                if (updatedRaw.isNotBlank() && updatedAtMillis == null) {
                    dateIssues.add("$conId → updatedAt: বোঝা যায়নি (\"$updatedRaw\")")
                }
                if (createdAtMillis != null && createdAtMillis!! > nowMillis) {
                    dateIssues.add("$conId → createdAt: ভবিষ্যতের তারিখ, বাদ দেওয়া হয়েছে")
                    createdAtMillis = null
                }
                if (updatedAtMillis != null && updatedAtMillis!! > nowMillis) {
                    dateIssues.add("$conId → updatedAt: ভবিষ্যতের তারিখ, বাদ দেওয়া হয়েছে")
                    updatedAtMillis = null
                }
                if (createdAtMillis != null && updatedAtMillis != null && createdAtMillis!! > updatedAtMillis!!) {
                    dateIssues.add("$conId → createdAt, updatedAt-এর পরে হওয়ায় বাদ দেওয়া হয়েছে")
                    createdAtMillis = null
                }
                createdAtMillis?.let { fieldMap["createdAt"] = it }
                updatedAtMillis?.let { fieldMap["updatedAt"] = it }

                // Normalize phone
                val phoneField = resolvedMapping["recipientPhone"]?.let { colLetter ->
                    val idx = letterToIndex(colLetter)
                    if (idx >= 0) row.getOrElse(idx) { "" }.trim() else ""
                }
                val normalizedPhone = normalizePhone(phoneField ?: "")
                if (normalizedPhone.isNotBlank()) fieldMap["recipientPhone"] = normalizedPhone

                // userSystemId — used for runs_by_agentSystemId reverse-index
                // Looks for agentSystemId or agent_system_id field in mapping (preferred over employee_id)
                val userSystemId = fieldMap["agentSystemId"]?.toString()?.trim().orEmpty()
                    .ifBlank { fieldMap["agent_system_id"]?.toString()?.trim().orEmpty() }

                // ── Object-type fields: build key-value pair from two specs ────
                // spec: "col:A" (dynamic, read from that column) or "fixed:text" (constant)
                // writes to: {basePath}/{conId}/{field}/{keyValue} = value
                fun resolveSpec(spec: String): String {
                    return when {
                        spec.startsWith("fixed:") -> spec.removePrefix("fixed:")
                        spec.startsWith("col:") -> {
                            val letter = spec.removePrefix("col:")
                            val idx = letterToIndex(letter)
                            if (idx < 0) "" else row.getOrElse(idx) { "" }.trim()
                        }
                        else -> "" // legacy: bare column letter (backward compat)
                    }
                }
                val objectFieldWrites = mutableMapOf<String, Any>()
                conn.objectColumnMapping.forEach { (field, ocm) ->
                    val keyVal   = resolveSpec(ocm.keySpec())
                    val valueVal = resolveSpec(ocm.valueSpec())
                    if (keyVal.isNotBlank() && valueVal.isNotBlank()) {
                        objectFieldWrites["$field/$keyVal"] = valueVal
                    }
                }

                // ── Check Firebase exist ──────────────────────────────
                var existReadFailed = false
                val existSnap = withContext(Dispatchers.IO) {
                    try { db.reference.child("$basePath/$conId").get().await() }
                    catch (e: Exception) {
                        existReadFailed = true
                        writeFailures.add("$conId → read check failed: ${e.message?.take(80) ?: e.javaClass.simpleName}")
                        null
                    }
                }
                if (existReadFailed) { skipped++; continue }

                val multiUpdate = mutableMapOf<String, Any>()

                if (existSnap == null || !existSnap.exists()) {
                    // INSERT
                    fieldMap.forEach { (k, v) -> multiUpdate["$basePath/$conId/$k"] = v }
                    objectFieldWrites.forEach { (k, v) -> multiUpdate["$basePath/$conId/$k"] = v }
                    // consignments_by_phone — legacy secondary index, only relevant for the
                    // default courier/consignments flow. Guarded so other sheet types
                    // (e.g. courier/run_routes/...) never write into this unrelated index.
                    if (basePath == "courier/consignments" && normalizedPhone.isNotBlank()) {
                        val status = fieldMap["status"]?.toString() ?: ""
                        multiUpdate["courier/consignments_by_phone/$normalizedPhone/$conId"] = status
                    }
                    // runs_by_agentSystemId — same reverse-index pattern, generalized for ANY run type
                    // under courier/run_routes/{runType}/ (delivery_run, pickup_run, return_run, etc.)
                    // so future run types work automatically without code changes.
                    val runTypeMatch = Regex("^courier/run_routes/([^/]+)$").find(basePath)
                    if (runTypeMatch != null && userSystemId.isNotBlank()) {
                        val runType = runTypeMatch.groupValues[1]
                        val status = fieldMap["status"]?.toString() ?: ""
                        multiUpdate["courier/runs_by_agentSystemId/$userSystemId/$runType/$conId"] = status

                        // runs_by_branchId — branch is resolved ONCE at run-creation time (the agent's
                        // branch_ids *right now*), then locked in via resolvedBranchIds on the run
                        // node itself. If the agent switches branch tomorrow, tomorrow's runId is a
                        // different key entirely (date-scoped), so it re-resolves naturally — today's
                        // already-created run intentionally stays put.
                        val agentBranchIds = resolveAgentBranchIds(userSystemId)
                        if (agentBranchIds.isNotEmpty()) {
                            multiUpdate["$basePath/$conId/resolvedBranchIds"] = agentBranchIds
                            agentBranchIds.forEach { branchId ->
                                multiUpdate["courier/runs_by_branchId/$branchId/$runType/$conId"] = status
                            }
                        }
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
                    // Object fields: compare each key-value pair individually
                    objectFieldWrites.forEach { (path, v) ->
                        val firebaseVal = existSnap.child(path).value
                        if ((firebaseVal?.toString() ?: "") != v.toString()) changedFields[path] = v
                    }
                    if (changedFields.isNotEmpty()) {
                        changedFields.forEach { (k, v) -> multiUpdate["$basePath/$conId/$k"] = v }
                        // Update consignments_by_phone if status changed (guarded, see note above)
                        if (basePath == "courier/consignments" && "status" in changedFields && normalizedPhone.isNotBlank()) {
                            multiUpdate["courier/consignments_by_phone/$normalizedPhone/$conId"] =
                                changedFields["status"].toString()
                        }
                        // Update runs_by_agentSystemId if status changed (same guarded pattern, generalized run type)
                        val runTypeMatchUpd = Regex("^courier/run_routes/([^/]+)$").find(basePath)
                        if (runTypeMatchUpd != null && "status" in changedFields && userSystemId.isNotBlank()) {
                            val runType = runTypeMatchUpd.groupValues[1]
                            multiUpdate["courier/runs_by_agentSystemId/$userSystemId/$runType/$conId"] =
                                changedFields["status"].toString()

                            // runs_by_branchId — branch was already locked in at INSERT time
                            // (resolvedBranchIds on the run node). We only propagate the new
                            // status here; we deliberately do NOT re-resolve the agent's branch,
                            // since today's run must stay attached to the branch it was created in
                            // even if the agent's branch assignment changes later today.
                            val lockedBranchIds = existSnap?.child("resolvedBranchIds")
                                ?.children?.mapNotNull { it.getValue(String::class.java) }.orEmpty()
                            lockedBranchIds.forEach { branchId ->
                                multiUpdate["courier/runs_by_branchId/$branchId/$runType/$conId"] =
                                    changedFields["status"].toString()
                            }
                        }
                        updated++
                    } else {
                        skipped++
                    }
                }

                // Multi-path write
                if (multiUpdate.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        try {
                            db.reference.updateChildren(multiUpdate).await()
                        } catch (e: Exception) {
                            writeFailures.add("$conId → ${e.message?.take(80) ?: e.javaClass.simpleName}")
                        }
                    }
                }

                // Live progress — updates the busy overlay after every row, like a running counter
                val processedSoFar = inserted + updated + skipped
                setBusy(
                    true,
                    "Firebase sync করছে...\n\n" +
                    "✅ Inserted: $inserted   🔄 Updated: $updated   ⏭ Skipped: $skipped\n" +
                    "📦 Processed: $processedSoFar / ${dataRows.size}"
                )
            }

            // ── 5. Summary dialog ─────────────────────────────────────
            setBusy(false)
            if (!isAdded) return
            val issuesText = if (dateIssues.isNotEmpty()) {
                val shown = dateIssues.take(10).joinToString("\n") { "• $it" }
                val more  = if (dateIssues.size > 10) "\n…আরও ${dateIssues.size - 10}টি" else ""
                "\n\n⚠ Date সংক্রান্ত সমস্যা (${dateIssues.size}টি):\n$shown$more"
            } else ""
            // writeFailures means the row was COUNTED as inserted/updated above but the actual
            // Firebase write was rejected (permission-denied, validation rule, etc.) — shown
            // separately and loudly so this doesn't look like a silent success.
            val failuresText = if (writeFailures.isNotEmpty()) {
                val shown = writeFailures.take(10).joinToString("\n") { "• $it" }
                val more  = if (writeFailures.size > 10) "\n…আরও ${writeFailures.size - 10}টি" else ""
                "\n\n❌ Firebase-এ write ব্যর্থ হয়েছে (${writeFailures.size}টি) — এই row গুলো Inserted/Updated " +
                "count-এ ধরা হয়েছে কিন্তু আসলে save হয়নি:\n$shown$more\n\n" +
                "সাধারণত Firebase Security Rules-এ এই path-এ write permission নেই।"
            } else ""
            val branchlessText = when {
                branchResolveFailures.isNotEmpty() -> {
                    val shown = branchResolveFailures.take(10).joinToString("\n") { "• $it" }
                    val more  = if (branchResolveFailures.size > 10) "\n…আরও ${branchResolveFailures.size - 10}টি" else ""
                    "\n\n⚠ runs_by_branchId resolve করতে ব্যর্থ (${branchResolveFailures.size}টি agent) — " +
                    "users_by_systemId বা users/ read সমস্যা:\n$shown$more"
                }
                branchlessAgentSystemIds.isNotEmpty() -> {
                    val shown = branchlessAgentSystemIds.take(10).joinToString(", ")
                    val more  = if (branchlessAgentSystemIds.size > 10) " …আরও ${branchlessAgentSystemIds.size - 10}টি" else ""
                    "\n\n⚠ এই agent-দের uid পাওয়া যায়নি বা branch_ids assign করা নেই বলে runs_by_branchId তৈরি হয়নি " +
                    "(${branchlessAgentSystemIds.size}টি systemId): $shown$more\n" +
                    "Employee edit থেকে এদের branch assign করুন।"
                }
                else -> ""
            }
            android.app.AlertDialog.Builder(ctx)
                .setTitle(if (writeFailures.isEmpty()) "✅ Sync Complete" else "⚠ Sync Complete — with errors")
                .setMessage(
                    "Inserted : $inserted\n" +
                    "Updated  : $updated\n" +
                    "Skipped  : $skipped\n" +
                    "Total    : ${dataRows.size}" +
                    issuesText + failuresText + branchlessText
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
     * Accepts: epoch seconds (10-digit), epoch millis (13-digit), Google Sheets/Excel
     * date-serial numbers (e.g. 46204 — days since Dec 30 1899, decimal = time of day),
     * ISO-style date/date-time strings (yyyy-MM-dd, yyyy/MM/dd, with optional time),
     * or day-month-name values from Sheets (dd-MMM-yy / dd-MMM-yyyy, e.g. 03-Jul-26).
     * Slash/dash formats like "7/1/2026" are intentionally NOT accepted since
     * day-vs-month order can't be reliably determined — better to skip than guess wrong.
     */
    // parseSheetTimestamp / normalizePhone → ConfigSheetParseUtil
    internal fun parseSheetTimestamp(raw: String) = ConfigSheetParseUtil.parseSheetTimestamp(raw)
    internal fun normalizePhone(phone: String)     = ConfigSheetParseUtil.normalizePhone(phone)

    internal fun renderSyncTab(conn: SheetConn) {
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

    internal fun updateSyncGearState(enabled: Boolean) {
        btnSyncGear?.alpha = if (enabled) 1f else 0.4f
        btnSyncGear?.isEnabled = enabled
        tvSyncIntervalLabel?.setTextColor(
            android.graphics.Color.parseColor(if (enabled) "#E8380D" else "#6B7280")
        )
    }

    internal fun openIntervalPickerDialog(conn: SheetConn) {
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

    internal fun showCustomIntervalInput(conn: SheetConn) {
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

    internal fun saveSyncSettings(conn: SheetConn) {
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
    internal fun renderManageColTable(colStart: Int, colEnd: Int) {
        val table = tableColPreviewMgr ?: return
        table.removeAllViews()
        val colCount = (colEnd - colStart + 1).coerceAtLeast(1)
        val letters  = List(colCount) { c -> colIndexToLetter(colStart + c) }
        val nums     = List(colCount) { c -> "${colStart + c}" }
        table.addView(tableRow(letters, "#F3F4F6", "#6B7280", bold = true, compact = true))
        table.addView(tableRow(nums,    "#FFF7ED", "#E8380D", bold = true, compact = true))
        scrollColPreviewMgr?.visibility = View.VISIBLE
    }

    internal fun tableRow(
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

    internal fun tableCell(
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
                setStroke(dp(1), context!!.getColor(R.color.theme_border))
            }
        }
    }

    internal fun advanceStep() {
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

                // If sheetHeaders not yet populated, fetch header row first then proceed
                if (sheetHeaders.isEmpty() && googleAccount != null && selectedSheet != null && selectedTab.isNotBlank()) {
                    val account = googleAccount!!
                    val sheet   = selectedSheet!!
                    val tab     = selectedTab
                    viewLifecycleOwner.lifecycleScope.launch {
                        setBusy(true, "Header fetch করছে...")
                        try {
                            val token = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                try { com.google.android.gms.auth.GoogleAuthUtil.getToken(requireContext(), account.account!!, ConfigSheetDriveApi.OAUTH_SCOPE) }
                                catch (ex: Exception) { null }
                            }
                            if (token != null) {
                                val startLetter = colIndexToLetter(s)
                                val endLetter   = colIndexToLetter(e)
                                val sRow = etStartRow?.text?.toString()?.trim()?.toIntOrNull() ?: 1
                                val range = "$tab!${startLetter}${sRow}:${endLetter}${sRow}"
                                val encoded = java.net.URLEncoder.encode(range, "UTF-8")
                                val url = "https://sheets.googleapis.com/v4/spreadsheets/${sheet.id}/values/$encoded"
                                val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val req = okhttp3.Request.Builder().url(url)
                                        .header("Authorization", "Bearer $token").build()
                                    httpClient.newCall(req).execute().use { resp ->
                                        if (!resp.isSuccessful) return@withContext null
                                        val arr = org.json.JSONObject(resp.body?.string() ?: "").optJSONArray("values")
                                            ?: return@withContext null
                                        if (arr.length() == 0) return@withContext null
                                        val row = arr.getJSONArray(0)
                                        (0 until row.length()).map { j -> row.optString(j, "") }
                                    }
                                }
                                val newHeaders = mutableMapOf<String, String>()
                                rows?.forEachIndexed { idx, header ->
                                    val letter = colIndexToLetter(s + idx)
                                    if (header.isNotBlank()) newHeaders[letter] = header
                                }
                                sheetHeaders = newHeaders
                            }
                        } catch (_: Exception) {}
                        finally { setBusy(false) }
                        autoDetectMapping()
                        connectStep++
                        renderConnectStep()
                    }
                    return // coroutine handles the rest
                }

                autoDetectMapping()
            }
        }
        connectStep++
        renderConnectStep()
    }

    internal fun showErr(msg: String) {
        tvConnError?.text = "⚠ $msg"
        tvConnError?.visibility = View.VISIBLE
    }

    internal fun handleConnect() {
        if (!nodeMappingConfirmed) { showErr("আগে উপরে node পিক করে confirm করুন"); return }
        if (pendingPkParts.isEmpty()) { showErr("Primary key এ কমপক্ষে একটা part (prefix/column) যোগ করুন — required"); return }
        if (pendingPkParts.any { (it.type == "col" || it.type == "date") && it.value.isBlank() }) { showErr("Primary key এর Column/Date part-এ কলাম select করুন"); return }
        val sheet   = selectedSheet ?: run { showErr("Sheet নেই"); return }
        if (selectedTab.isBlank())  { showErr("Tab নেই"); return }
        val s = parseColInput(etColStart?.text?.toString() ?: "") ?: run { showErr("Valid start column দিন (A বা 1)"); return }
        val e = parseColInput(etColEnd?.text?.toString() ?: "")   ?: run { showErr("Valid end column দিন (J বা 10)"); return }
        if (s < 1 || e < s) { showErr("start ≤ end হতে হবে"); return }
        if (pendingMapping.isEmpty() && pendingObjectMapping.isEmpty()) { showErr("কমপক্ষে একটা field map করুন"); return }

        // Match by the exact connectionId currently being managed/edited — do NOT fall back
        // to the first connection in the branch, or a fresh "+ New Sheet" flow would
        // incorrectly overwrite an existing entry.
        val existing = connections[activeBranch]?.find { it.connectionId == activeConnectionId }
        val sRow = etStartRow?.text?.toString()?.trim()?.toIntOrNull()
        val eRow = etEndRow?.text?.toString()?.trim()?.toIntOrNull()

        // Reuse existing connectionId whenever we're editing a known connection
        // (covers both the row-range-only edit AND the full Reconnect wizard flow);
        // only generate a brand-new push key when there's genuinely no existing match.
        val connId = existing?.connectionId
            ?: (db.reference.child("config/sheets/$activeBranch/connections").push().key ?: java.util.UUID.randomUUID().toString())

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
            objectColumnMapping = pendingObjectMapping.toMap(),
            primaryKeyField = "",  // legacy field left blank for connections saved via new builder
            primaryKeyParts = pendingPkParts.toList(),
            targetNode    = "courier/" + (etTargetNode?.text?.toString()?.trim()?.trim('/')?.ifBlank { "consignments" } ?: "consignments"),
        )

        showReviewDialog(conn, isNew = existing == null)
    }

    /** Final review before committing — shows target node, primary key format, and every
     *  mapped field with real sample data, so a mistaken mapping is caught before it triggers
     *  a potentially large sync against live Firebase data. */
    internal fun showReviewDialog(conn: SheetConn, isNew: Boolean) {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val scroll = android.widget.ScrollView(ctx)
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24.dp(), 16.dp(), 24.dp(), 8.dp())
        }
        scroll.addView(root)

        fun sectionTitle(text: String) = TextView(ctx).apply {
            this.text = text; textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#6B7280"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10.dp(); bottomMargin = 4.dp() }
        }
        fun valueLine(text: String, color: String = "#111827") = TextView(ctx).apply {
            this.text = text; textSize = 13f
            setTextColor(android.graphics.Color.parseColor(color))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 2.dp() }
        }

        root.addView(sectionTitle("TARGET NODE"))
        root.addView(valueLine(conn.targetNode, "#1D4ED8"))

        root.addView(sectionTitle("PRIMARY KEY (sample থেকে তৈরি)"))
        root.addView(valueLine(tvPkPreview?.text?.toString()?.removePrefix("Preview (1st row): ")?.removePrefix("Preview: ") ?: "—"))

        root.addView(sectionTitle("MAPPED FIELDS (${conn.columnMapping.size + conn.objectColumnMapping.size})"))
        if (conn.columnMapping.isEmpty() && conn.objectColumnMapping.isEmpty()) {
            root.addView(valueLine("⚠ কোনো field map করা হয়নি", "#F59E0B"))
        } else {
            conn.columnMapping.forEach { (field, colMap) ->
                val sample = sampleSheetRow[colMap.col]?.takeIf { it.isNotBlank() } ?: "(খালি)"
                root.addView(valueLine("• $field  →  ${colMap.header.ifBlank { colMap.col }}  =  \"$sample\""))
            }
            conn.objectColumnMapping.forEach { (field, spec) ->
                fun resolve(s: String): String = when {
                    s.startsWith("fixed:") -> "\"${s.removePrefix("fixed:")}\" (fixed)"
                    s.startsWith("col:") -> {
                        val letter = s.removePrefix("col:")
                        val sample = sampleSheetRow[letter]?.takeIf { it.isNotBlank() } ?: "(খালি)"
                        "${sheetHeaders[letter] ?: letter} = \"$sample\""
                    }
                    else -> s
                }
                root.addView(valueLine("• $field { key: ${resolve(spec.keySpec())}, value: ${resolve(spec.valueSpec())} }"))
            }
        }

        android.app.AlertDialog.Builder(ctx)
            .setTitle(if (isNew) "নতুন Sheet Connect করার আগে যাচাই করুন" else "Update করার আগে যাচাই করুন")
            .setView(scroll)
            .setPositiveButton(if (isNew) "✅ Connect করুন" else "✅ Update করুন") { _, _ -> commitConnection(conn, isNew) }
            .setNegativeButton("সম্পাদনা চালিয়ে যান", null)
            .show()
    }

    /** Actually persists the connection after the user has reviewed and confirmed it. */
    internal fun commitConnection(conn: SheetConn, isNew: Boolean) {
        val connList = connections.getOrPut(activeBranch) { mutableListOf() }
        val idx = connList.indexOfFirst { it.connectionId == conn.connectionId }
        if (idx >= 0) connList[idx] = conn else connList.add(conn)
        activeConnectionId = conn.connectionId
        // Expand this branch so newly added sheet card is visible
        expandedBranch = activeBranch
        screen = if (isRangeEdit) ConfigScreen.MANAGING else ConfigScreen.BRANCH_SELECT
        isRangeEdit = false
        render()

        // Wait for the actual Firebase write before claiming success — previously this fired
        // saveToFirebase() without waiting and showed "✅ connected!" unconditionally, so a
        // failed write (permission denied, network drop) looked identical to a real success
        // and the connection silently never made it to Firebase.
        val owner = viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch {
            val ok = saveToFirebase(conn)
            if (ok) {
                toast(if (isNew) "✅ $activeBranch connected!" else "✅ Range updated")
            } else {
                toast("⚠ Firebase-এ save ব্যর্থ হয়েছে — নেটওয়ার্ক/permission চেক করে আবার চেষ্টা করুন")
            }
        }
    }

    internal fun autoDetectMapping() {
        val editing = isEditingExistingConn()
        // For a NEW connection, recompute cleanly (original behaviour). For an EXISTING connection
        // being reconnected/edited, NEVER wipe the saved mapping — only fill unmapped gaps, so the
        // user's saved column↔header choices are preserved for both preview and sync.
        if (!editing) pendingMapping.clear()
        if (sheetHeaders.isEmpty()) return
        // Match each Firebase key against sheet headers by similarity
        val allKeys = fetchedNodeKeys + customMappingFields.map { it.first }
        allKeys.forEach { firebaseKey ->
            // Editing existing: skip any key that's already mapped (saved or user-set) or is an
            // object field — auto-detect must only touch fields with no mapping yet.
            if (editing && (pendingMapping.containsKey(firebaseKey) ||
                    pendingObjectMapping.containsKey(firebaseKey) ||
                    firebaseKey in objectTypeFields)) return@forEach
            val keyLower = firebaseKey.lowercase()
            // Split camelCase: "recipientName" → ["recipient", "name"]
            val keyParts = keyLower.replace(Regex("([a-z])([A-Z])"), "$1 $2")
                .lowercase().split(" ", "_", "-").filter { it.isNotBlank() }
            val matched = sheetHeaders.entries.firstOrNull { (_, header) ->
                val h = header.lowercase().trim()
                keyParts.any { part -> h.contains(part) || part.contains(h) }
            }
            if (matched != null) pendingMapping[firebaseKey] = ColMapping(col = matched.key, header = matched.value)
        }
    }

    // ── Dirty detection (Exit Wizard vs Save) ──────────────────────────────────────────────
    // Signatures are LETTER-INSENSITIVE: they key on header text, not column letters, so the
    // automatic drift-relocation done while rendering step 5 is NOT mistaken for a user edit.
    // A genuine remap (field pointed at a different header), add/remove field, range/tab/sheet/
    // nickname/target-node/primary-key change all DO change the signature. The comparison mirrors
    // exactly what handleConnect() would persist. Safety rule: if anything can't be resolved we
    // return "dirty", so we never show "Exit Wizard" while a real change is pending.
    internal fun sigCol(cm: ColMapping): String =
        if (cm.header.isNotBlank()) "h:${cm.header.trim().lowercase()}" else "c:${cm.col}"

    internal fun sigObj(o: ObjectColMapping): String {
        fun p(header: String, col: String) =
            if (header.isNotBlank()) "h:${header.trim().lowercase()}" else "c:$col"
        return "${p(o.keyHeader, o.keyCol)}~${p(o.valueHeader, o.valueCol)}"
    }

    internal fun sigPk(part: PkPart): String = when (part.type) {
        "fixed" -> "fixed:${part.value.trim()}"
        else    -> "${part.type}:" +
            if (part.header.isNotBlank()) "h:${part.header.trim().lowercase()}" else "v:${part.value}"
    }

    internal fun buildEditSignature(
        sheetId: String, tabName: String, colStart: Int, colEnd: Int,
        startRow: Int?, endRow: Int?, nickname: String, targetNode: String,
        colMap: Map<String, ColMapping>, objMap: Map<String, ObjectColMapping>,
        pkParts: List<PkPart>
    ): String {
        val sRow = startRow?.takeIf { it > 1 } ?: 0   // null / 1 → "no custom start"
        val eRow = endRow?.takeIf { it > 0 } ?: 0     // null / 0 → "no custom end"
        val node = targetNode.trim().trimEnd('/')
        val flat = colMap.entries
            .filter { it.value.col.isNotBlank() || it.value.header.isNotBlank() }
            .map { "${it.key}=${sigCol(it.value)}" }.sorted().joinToString("|")
        val obj = objMap.entries
            .map { "${it.key}=${sigObj(it.value)}" }.sorted().joinToString("|")
        val pk = pkParts.joinToString(">") { sigPk(it) }   // order matters for a composite key
        return listOf(
            "sheet=$sheetId", "tab=${tabName.trim()}",
            "cols=$colStart:$colEnd", "rows=$sRow:$eRow",
            "nick=${nickname.trim()}", "node=$node",
            "flat=$flat", "obj=$obj", "pk=$pk"
        ).joinToString("§")
    }

    /** Signature of the saved connection exactly as it lives in memory/Firebase. */
    internal fun savedSignatureOf(conn: SheetConn): String = buildEditSignature(
        conn.sheetId, conn.tabName, conn.colStart, conn.colEnd,
        conn.startRow, conn.endRow, conn.nickname, conn.targetNode,
        conn.columnMapping, conn.objectColumnMapping, conn.effectivePkParts()
    )

    /** Signature of the in-progress wizard state, from the SAME sources handleConnect() reads.
     *  Returns null when the state can't be fully resolved → caller treats that as dirty. */
    internal fun currentSignatureOrNull(): String? {
        val sheet = selectedSheet ?: return null
        if (selectedTab.isBlank()) return null
        val cs = parseColInput(etColStart?.text?.toString() ?: "") ?: return null
        val ce = parseColInput(etColEnd?.text?.toString() ?: "") ?: return null
        val sRow = etStartRow?.text?.toString()?.trim()?.toIntOrNull()
        val eRow = etEndRow?.text?.toString()?.trim()?.toIntOrNull()
        val node = "courier/" +
            (etTargetNode?.text?.toString()?.trim()?.trim('/')?.ifBlank { "consignments" } ?: "consignments")
        return buildEditSignature(
            sheet.id, selectedTab, cs, ce, sRow, eRow, selectedNickname, node,
            pendingMapping, pendingObjectMapping, pendingPkParts
        )
    }

    /** True only when reconnecting an EXISTING connection and nothing a Save would write changed. */
    internal fun isReconnectUnchanged(): Boolean {
        if (isRangeEdit) return false            // range-edit keeps its own Cancel/Save
        val conn = editingConn() ?: return false // new connection → never "unchanged"
        val current = currentSignatureOrNull() ?: return false
        return current == savedSignatureOf(conn)
    }

    /** Leaves the reconnect wizard WITHOUT saving. Mirrors the ✕ cancel path so no partial state
     *  leaks, and never writes to Firebase — the saved connection stays exactly as it was. */
    internal fun exitWizardNoChanges() {
        isRangeEdit = false
        screen = ConfigScreen.BRANCH_SELECT
        render()
    }

    /** Decides the step-5 primary button: Exit Wizard (existing + no change), Save (existing +
     *  changed & ready), or Connect (new & ready). Enable/alpha mirror handleConnect()'s checks so
     *  the button reflects readiness before it's tapped. Called from every place that can change
     *  the wizard state (mapping render, pk edits, step navigation). */
    internal fun updateConnectButtonState() {
        val hasValidPk = pendingPkParts.isNotEmpty() &&
            pendingPkParts.none { (it.type == "col" || it.type == "date") && it.value.isBlank() }
        val hasAtLeastOneField = pendingMapping.isNotEmpty() || pendingObjectMapping.isNotEmpty()
        val ready = nodeMappingConfirmed && hasValidPk && hasAtLeastOneField

        when {
            isReconnectUnchanged() -> {
                primaryAction = ConfigPrimaryAction.EXIT
                btnConnect?.text = "Exit Wizard"
                btnConnect?.isEnabled = true
                btnConnect?.alpha = 1.0f
            }
            isEditingExistingConn() -> {
                primaryAction = ConfigPrimaryAction.SAVE
                btnConnect?.text = "Save"
                btnConnect?.isEnabled = ready
                btnConnect?.alpha = if (ready) 1.0f else 0.45f
            }
            else -> {
                primaryAction = ConfigPrimaryAction.NEW
                btnConnect?.text = "Connect"
                btnConnect?.isEnabled = ready
                btnConnect?.alpha = if (ready) 1.0f else 0.45f
            }
        }
    }

    internal fun clearConnectForm() {
        availableSheets = emptyList(); selectedSheet = null
        availableTabs   = emptyList(); selectedTab   = ""
        etColStart?.setText("1"); etColEnd?.setText("10")
        colEndUserModified = false // let header-detection auto-set Col End for this fresh connection
        isRowRangeVisible = false
        layoutRowRange?.visibility = View.GONE
        btnDefineRow?.text = "+ Define Row Range"
        etStartRow?.setText(""); etEndRow?.setText("")
        customMappingFields.clear()
        fetchedNodeKeys.clear()
        nodePreviewData = emptyMap()
        pendingMapping.clear()
        pendingObjectMapping.clear()
        objectTypeFields.clear()
        primaryKeyField = ""
        pendingPkParts.clear()
        targetNode = "courier/consignments"
        etTargetNode?.setText("")
        nodePickerPath.clear()
        nodePickerRevealedDepth = 0
        nodeMappingConfirmed = false
        nodeChildrenCache.clear()
        knownNodePaths = null
        tvFetchStatus?.text = ""
        if (googleAccount != null) loadSheetsForAccount()
    }

    internal fun prefillConnectForm() {
        val conn = activeConn() ?: return
        // Reset transient node/field state so stale keys from a PREVIOUS connect/reconnect in the
        // same session can't survive into this one and cause duplicate rows (see allFields dedup).
        fetchedNodeKeys.clear()
        customMappingFields.clear()
        selectedSheet = DriveFile(conn.sheetId, conn.sheetName)
        selectedTab = conn.tabName
        selectedNickname = conn.nickname
        etNickname?.setText(conn.nickname)
        availableTabs = listOf(conn.tabName)
        updateSheetPickerLabel()
        etColStart?.setText(conn.colStart.toString())
        etColEnd?.setText(conn.colEnd.toString())
        colEndUserModified = true // saved value is authoritative — don't auto-override it
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
        selectedNickname = conn.nickname
        etNickname?.setText(conn.nickname)
        targetNode = conn.targetNode
        etTargetNode?.setText(conn.targetNode.removePrefix("courier/"))
        primaryKeyField = conn.primaryKeyField
        pendingPkParts.clear()
        pendingPkParts.addAll(conn.effectivePkParts())
        pendingMapping.clear()
        pendingMapping.putAll(conn.columnMapping)
        pendingObjectMapping.clear()
        pendingObjectMapping.putAll(conn.objectColumnMapping)
        objectTypeFields.clear()
        objectTypeFields.addAll(conn.objectColumnMapping.keys)
        // Existing connection — target node was already confirmed when it was saved
        nodeMappingConfirmed = conn.targetNode.isNotBlank()
        // Re-add object fields as custom fields so they render in the mapping step. Guard against
        // BOTH lists so an object key already present in fetchedNodeKeys is never double-added.
        conn.objectColumnMapping.keys.forEach { key ->
            if (fetchedNodeKeys.none { it == key } && customMappingFields.none { it.first == key })
                customMappingFields.add(key to key)
        }
    }

    internal fun openRangeEditor() {
        val conn = activeConn() ?: return
        activeConnectionId = conn.connectionId

        selectedSheet = DriveFile(conn.sheetId, conn.sheetName)
        selectedTab = conn.tabName
        selectedNickname = conn.nickname
        etNickname?.setText(conn.nickname)
        availableTabs = listOf(conn.tabName)
        etColStart?.setText(conn.colStart.toString())
        etColEnd?.setText(conn.colEnd.toString())
        colEndUserModified = true // saved value is authoritative — don't auto-override it
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
        screen = ConfigScreen.CONNECTING
        connectStep = 4
        render()
    }

    // ── Account picker (JSX showPicker equivalent) ────────────────────
    internal fun pickGoogleAccount() {
        val client = googleSignInClient ?: run {
            toast("Google Sign-In initialize হয়নি")
            return
        }
        // Sign out first → forces the account chooser to show every time. Shared logic
        // lives in GoogleSignInHelper (used identically by ConfigConnectorsFragment).
        GoogleSignInHelper.pickAccount(
            fragment = this,
            client = client,
            signInLauncher = signInLauncher,
            onLaunchFailed = { msg -> toast("Sign-In launch failed: $msg") }
        )
    }

    internal fun handleSignInResult(data: Intent?) {
        val acc = GoogleSignInHelper.parseSignInResult(data) { msg -> showErr(msg) } ?: return
        googleAccount = acc
        // Remember THIS feature's connected email — see PREFS_FILE_NAME doc comment.
        GoogleSignInHelper.rememberConnectedEmail(requireContext(), PREFS_FILE_NAME, acc.email)
        // Reset downstream
        availableSheets = emptyList(); selectedSheet = null
        availableTabs   = emptyList(); selectedTab   = ""
        updateAccountStep()
        loadSheetsForAccount()
    }

    internal fun updateAccountStep() {
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
    internal fun loadSheetsForAccount() {
        val account = googleAccount ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                pbSheetLoad?.visibility = View.VISIBLE
                val token = GoogleSignInHelper.fetchAccessToken(
                    context = requireContext(),
                    fragment = this@ConfigSheetFragment,
                    account = account,
                    scope = ConfigSheetDriveApi.OAUTH_SCOPE,
                    recoverableLauncher = recoverableLauncher,
                    onError = { msg -> showErr(msg) }
                ) ?: return@launch
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

    // fetchDriveSpreadsheets → ConfigSheetDriveApi
    internal fun fetchDriveSpreadsheets(accessToken: String) =
        ConfigSheetDriveApi.fetchDriveSpreadsheets(accessToken, httpClient)

    internal fun updateSheetPickerLabel() {
        tvSelectedSheet?.text = selectedSheet?.name ?: "— Sheet বেছে নিন —"
        tvSelectedSheet?.setTextColor(
            android.graphics.Color.parseColor(if (selectedSheet != null) "#111827" else "#6B7280")
        )
    }

    internal fun openSheetPickerDialog() {
        val ctx = context ?: return
        if (availableSheets.isEmpty()) {
            toast("Sheet লোড হচ্ছে, একটু অপেক্ষা করুন")
            return
        }
        SheetPickerDialog.show(ctx, availableSheets, selectedSheet) { sheet ->
            if (selectedSheet?.id != sheet.id) {
                selectedSheet = sheet
                selectedTab   = ""
                availableTabs = emptyList()
                updateSheetPickerLabel()
                loadTabsForSheet()
            } else {
                updateSheetPickerLabel()
            }
        }
    }

    internal fun loadTabsForSheet() {
        val account = googleAccount ?: return
        val acctObj = account.account ?: return
        val sheet   = selectedSheet ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                pbTabLoad?.visibility = View.VISIBLE
                val ctx = context ?: return@launch
                val token = withContext(Dispatchers.IO) {
                    try { GoogleAuthUtil.getToken(ctx, acctObj, ConfigSheetDriveApi.OAUTH_SCOPE) }
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

    // fetchSheetTabs → ConfigSheetDriveApi
    internal fun fetchSheetTabs(accessToken: String, sheetId: String) =
        ConfigSheetDriveApi.fetchSheetTabs(accessToken, sheetId, httpClient)

    internal fun updateTabSpinner() {
        val ctx = context ?: return
        val opts = listOf("— Tab বেছে নিন —") + availableTabs
        spinnerTab?.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, opts)
        val sel = availableTabs.indexOf(selectedTab)
        if (sel >= 0) spinnerTab?.setSelection(sel + 1) else spinnerTab?.setSelection(0)
    }

    // ── ManagePanel ───────────────────────────────────────────────────
    internal fun renderManagePanel() {
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

    internal fun renderManageTabs() {
        val red  = android.graphics.Color.parseColor("#E8380D")
        val grey = context!!.getColor(R.color.theme_text_secondary)
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

    internal fun handleDisconnect() {
        val branch = activeBranch
        val connId = activeConnectionId
        connections[branch]?.removeAll { it.connectionId == connId }
        if (connections[branch].isNullOrEmpty()) connections.remove(branch)
        deleteFromFirebase(branch, connId)
        toast("🗑 Sheet disconnected")
        screen = ConfigScreen.BRANCH_SELECT
        render()
    }

    // ── Firebase ──────────────────────────────────────────────────────
    internal fun activeConn(): SheetConn? =
        connections[activeBranch]?.find { it.connectionId == activeConnectionId }
            ?: connections[activeBranch]?.firstOrNull()

    internal fun updateActiveConn(updated: SheetConn) {
        val list = connections.getOrPut(updated.branchId) { mutableListOf() }
        val idx = list.indexOfFirst { it.connectionId == updated.connectionId }
        if (idx >= 0) list[idx] = updated else list.add(updated)
    }

    internal fun loadFromFirebase() {
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
                            val colMap: Map<String, ColMapping> = connSnap.child("columnMapping").children.associate { fieldSnap ->
                                val k = fieldSnap.key ?: ""
                                val v = fieldSnap.value
                                val cm = when (v) {
                                    is Map<*, *> -> ColMapping(
                                        col    = v["col"]?.toString() ?: "",
                                        header = v["header"]?.toString() ?: ""
                                    )
                                    is String -> ColMapping(col = v, header = "") // legacy
                                    else -> ColMapping()
                                }
                                k to cm
                            }
                            val objMapRaw = connSnap.child("objectColumnMapping").children.associate { fieldSnap ->
                                fieldSnap.key.orEmpty() to ObjectColMapping(
                                    keyCol      = fieldSnap.child("keyCol").getValue(String::class.java)
                                        ?: fieldSnap.child("key").getValue(String::class.java) ?: "",
                                    keyHeader   = fieldSnap.child("keyHeader").getValue(String::class.java) ?: "",
                                    valueCol    = fieldSnap.child("valueCol").getValue(String::class.java)
                                        ?: fieldSnap.child("value").getValue(String::class.java) ?: "",
                                    valueHeader = fieldSnap.child("valueHeader").getValue(String::class.java) ?: "",
                                )
                            }.filterKeys { it.isNotBlank() }
                            val tgtNode    = connSnap.child("targetNode").getValue(String::class.java) ?: "courier/consignments"
                            val pkField    = connSnap.child("primaryKeyField").getValue(String::class.java) ?: ""
                            val pkParts    = connSnap.child("primaryKeyParts").children.mapNotNull { partSnap ->
                                val t = partSnap.child("type").getValue(String::class.java) ?: return@mapNotNull null
                                val v = partSnap.child("value").getValue(String::class.java) ?: ""
                                val h = partSnap.child("header").getValue(String::class.java) ?: ""
                                PkPart(t, v, h)
                            }
                            list.add(SheetConn(connId, nickname, branchId, sheetId, sheetName, tabName, colS, colE, sRow, eRow, autoSync, interval, email, by, at, colMap, objMapRaw, pkField, tgtNode, pkParts))
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
                    screen = ConfigScreen.BRANCH_SELECT
                    render()
                    setBusy(false)
                }
            }
        }
    }

    internal fun readBranchIds(snap: com.google.firebase.database.DataSnapshot): List<String> {
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

    internal suspend fun saveToFirebase(conn: SheetConn): Boolean {
        return try {
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
                "columnMapping"   to conn.columnMapping.mapValues { (_, cm) ->
                    mapOf("col" to cm.col, "header" to cm.header)
                },
                "objectColumnMapping" to conn.objectColumnMapping.mapValues { (_, ocm) ->
                    mapOf(
                        "keyCol"      to ocm.keyCol,
                        "keyHeader"   to ocm.keyHeader,
                        "valueCol"    to ocm.valueCol,
                        "valueHeader" to ocm.valueHeader,
                    )
                },
                "primaryKeyField" to conn.primaryKeyField,
                "primaryKeyParts" to conn.primaryKeyParts.map { part ->
                    mapOf("type" to part.type, "value" to part.value, "header" to part.header)
                },
                "targetNode"      to conn.targetNode,
            )
            val basePath = "config/sheets/${conn.branchId}/connections"
            val connId = conn.connectionId.ifBlank { db.reference.child(basePath).push().key ?: return false }
            db.reference.child("$basePath/$connId").setValue(data).await()
            db.reference.child("config/sheets/${conn.branchId}/history").push()
                .setValue(data + mapOf("action" to "connected", "connectionId" to connId)).await()
            true
        } catch (e: Exception) {
            FirebaseErrorLogger.log(
                screen = "ConfigSheetFragment", action = "save_connection",
                errorMessage = e.message ?: "unknown",
                extra = mapOf("branchId" to conn.branchId, "connectionId" to conn.connectionId)
            )
            false
        }
    }

    internal fun deleteFromFirebase(branchId: String, connectionId: String) {
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
    internal fun toast(msg: String) {
        try { Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }
        catch (_: Exception) {}
    }

    internal fun setBusy(show: Boolean, text: String = "Loading...") {
        tvSheetBusy?.text = text
        sheetBusyOverlay?.visibility = if (show) View.VISIBLE else View.GONE
    }
}
