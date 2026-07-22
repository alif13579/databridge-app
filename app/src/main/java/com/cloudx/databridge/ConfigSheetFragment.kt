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
    internal var step1Dot:  TextView? = null; internal var step2Dot:  TextView? = null
    internal var step3Dot:  TextView? = null; internal var step4Dot:  TextView? = null; internal var step5Dot: TextView? = null
    internal var step1Line: View? = null; internal var step2Line: View? = null; internal var step3Line: View? = null; internal var step4Line: View? = null
    internal var step1Lbl:  TextView? = null; internal var step2Lbl:  TextView? = null
    internal var step3Lbl:  TextView? = null; internal var step4Lbl:  TextView? = null; internal var step5Lbl: TextView? = null
    internal var stepView1: View? = null; internal var stepView2: View? = null
    internal var stepView3: View? = null; internal var stepView4: View? = null; internal var stepView5: View? = null
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
    // Moved here from mid-file (was declared just above updateColPreview) as part of the
    // ConfigSheetWizardSteps.kt module split — extension functions can't hold their own
    // backing-field state, so this stays on the fragment like the rest of wizard state.
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
    internal var tabOverview:     TextView? = null; internal var tabColumns: TextView? = null; internal var tabSync: TextView? = null
    internal var indOverview:     View? = null;     internal var indColumns: View? = null;     internal var indSync: View? = null
    internal var cardOverview:    View? = null
    internal var cardColumns:     View? = null
    internal var cardSync:        View? = null
    internal var tvOvSheet:       TextView? = null; internal var tvOvTab: TextView? = null; internal var tvOvCols: TextView? = null
    internal var tvColPreviewMgr:     TextView? = null
    internal var scrollColPreviewMgr: android.widget.HorizontalScrollView? = null
    internal var tableColPreviewMgr:  android.widget.TableLayout? = null
    internal var btnColChange:    Button? = null
    internal var btnManReconnect: Button? = null;   internal var btnManDisconn: Button? = null
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

    // Wizard Steps render logic (renderConnectStep, live preview, sync-to-Firebase, etc.)
    // now lives in ConfigSheetWizardSteps.kt — see that file's header comment for why.

    // parseSheetTimestamp / normalizePhone → ConfigSheetParseUtil
    internal fun parseSheetTimestamp(raw: String) = ConfigSheetParseUtil.parseSheetTimestamp(raw)
    internal fun normalizePhone(phone: String)     = ConfigSheetParseUtil.normalizePhone(phone)

    // Wizard control (sync tab, advance/connect/dirty-detection, account picker, etc.)
    // now lives in ConfigSheetWizardControl.kt — see that file's header comment for why.

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
