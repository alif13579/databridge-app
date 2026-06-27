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
    private var connections: MutableMap<String, SheetConn> = mutableMapOf()

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
        val branchId:    String = "",
        val sheetId:     String = "",
        val sheetName:   String = "",
        val tabName:     String = "",
        val colStart:    Int    = 1,
        val colEnd:      Int    = 10,
        val startRow:    Int?   = null,  // null = default (1)
        val endRow:      Int?   = null,  // null = last row
        val googleEmail: String = "",
        val connectedBy: String = "",
        val connectedAt: Long   = 0L,
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
    private var step1Dot:  View? = null; private var step2Dot:  View? = null
    private var step3Dot:  View? = null; private var step4Dot:  View? = null
    private var step1Line: View? = null; private var step2Line: View? = null; private var step3Line: View? = null
    private var step1Lbl:  TextView? = null; private var step2Lbl:  TextView? = null
    private var step3Lbl:  TextView? = null; private var step4Lbl:  TextView? = null
    private var stepView1: View? = null; private var stepView2: View? = null
    private var stepView3: View? = null; private var stepView4: View? = null

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
    private var tvSummary:       TextView? = null

    // Nav buttons
    private var btnBack:    Button? = null
    private var btnNext:    Button? = null
    private var btnConnect: Button? = null
    private var btnCancelConn: View? = null
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

    private var activeManageTab = "overview"
    private var previewJob: kotlinx.coroutines.Job? = null

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
        tvSummary      = view.findViewById(R.id.tvSummary)

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
        btnSyncNow       = view.findViewById(R.id.btnSyncNow)
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
                val unconnected = branches.filter { !connections.containsKey(it) }
                val branch = unconnected.getOrNull(pos - 1) ?: return
                activeBranch = branch
                updateBranchActionCard()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnBranchAction?.setOnClickListener {
            if (activeBranch.isEmpty()) return@setOnClickListener
            val conn = connections[activeBranch]
            if (conn != null) { screen = Screen.MANAGING; render() }
            else              { screen = Screen.CONNECTING; connectStep = 1; clearConnectForm(); render() }
        }

        btnCancelConn?.setOnClickListener { screen = Screen.BRANCH_SELECT; render() }
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
        tabColumns?.setOnClickListener  { activeManageTab = "columns";  renderManageTabs() }
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
        btnSyncNow?.setOnClickListener      { toast("🔄 Sync শুরু হয়েছে...") }

        etColStart?.addTextChangedListener(colWatcher); etColEnd?.addTextChangedListener(colWatcher)

        btnDefineRow?.setOnClickListener {
            isRowRangeVisible = !isRowRangeVisible
            layoutRowRange?.visibility = if (isRowRangeVisible) View.VISIBLE else View.GONE
            btnDefineRow?.text = if (isRowRangeVisible) "− Hide Row Range" else "+ Define Row Range"
            if (!isRowRangeVisible) {
                etStartRow?.setText("")
                etEndRow?.setText("")
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
            tvBranchEmpty?.visibility          = View.VISIBLE
            sectionConnected?.visibility       = View.GONE
            sectionUnconnected?.visibility     = View.GONE
            return
        }

        tvBranchEmpty?.visibility = View.GONE

        val connectedBranches   = branches.filter {  connections.containsKey(it) }
        val unconnectedBranches = branches.filter { !connections.containsKey(it) }

        // ── Connected section ─────────────────────────────────────────
        if (connectedBranches.isNotEmpty()) {
            sectionConnected?.visibility = View.VISIBLE
            containerConnectedBranches?.removeAllViews()
            connectedBranches.forEach { branchId ->
                val conn = connections[branchId]!!
                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    background = resources.getDrawable(R.drawable.bg_card_rounded, null)
                    setPadding(36, 28, 36, 28)
                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = 20
                    layoutParams = lp
                }
                val tvName = TextView(ctx).apply {
                    text = branchLabel(branchId)
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.parseColor("#111827"))
                }
                val tvSheet = TextView(ctx).apply {
                    text = "📄 ${conn.sheetName}  ·  📑 ${conn.tabName}"
                    textSize = 11f
                    setTextColor(android.graphics.Color.parseColor("#6B7280"))
                    val lp2 = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp2.topMargin = 4
                    layoutParams = lp2
                }
                val btnManage = android.widget.Button(ctx).apply {
                    text = "Manage"
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.WHITE)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#16A34A")
                    )
                    val lp3 = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 96
                    )
                    lp3.topMargin = 16
                    layoutParams = lp3
                    setOnClickListener {
                        activeBranch = branchId
                        screen = Screen.MANAGING
                        render()
                    }
                }
                row.addView(tvName)
                row.addView(tvSheet)
                row.addView(btnManage)
                containerConnectedBranches?.addView(row)
            }
        } else {
            sectionConnected?.visibility = View.GONE
        }

        // ── Unconnected section ───────────────────────────────────────
        if (unconnectedBranches.isNotEmpty()) {
            sectionUnconnected?.visibility = View.VISIBLE

            if (unconnectedBranches.size == 1) {
                activeBranch = unconnectedBranches.first()
                spinnerBranch?.visibility  = View.GONE
                tvSingleBranch?.visibility = View.VISIBLE
                tvSingleBranch?.text       = branchLabel(activeBranch)
            } else {
                spinnerBranch?.visibility  = View.VISIBLE
                tvSingleBranch?.visibility = View.GONE
                val opts = listOf("শাখা বেছে নিন...") + unconnectedBranches.map { branchLabel(it) }
                spinnerBranch?.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, opts)
                val sel = unconnectedBranches.indexOf(activeBranch)
                if (sel >= 0) spinnerBranch?.setSelection(sel + 1)
            }
            updateBranchActionCard()
        } else {
            sectionUnconnected?.visibility = View.GONE
        }
    }

    private fun updateBranchActionCard() {
        val conn = connections[activeBranch]
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

        // Step dots — done=red, active=orange ring, future=grey
        val done   = android.graphics.Color.parseColor("#E8380D")
        val active = android.graphics.Color.parseColor("#FFF3F0")
        val future = android.graphics.Color.parseColor("#F3F4F6")
        fun styleDot(dot: View?, n: Int) {
            val c = when { connectStep > n -> done; connectStep == n -> active; else -> future }
            dot?.setBackgroundColor(c)
        }
        styleDot(step1Dot, 1); styleDot(step2Dot, 2); styleDot(step3Dot, 3); styleDot(step4Dot, 4)

        // Step lines
        val lineColor = android.graphics.Color.parseColor("#E8380D")
        val lineGrey  = android.graphics.Color.parseColor("#E5E7EB")
        step1Line?.setBackgroundColor(if (connectStep > 1) lineColor else lineGrey)
        step2Line?.setBackgroundColor(if (connectStep > 2) lineColor else lineGrey)
        step3Line?.setBackgroundColor(if (connectStep > 3) lineColor else lineGrey)

        // Step labels
        val red  = android.graphics.Color.parseColor("#E8380D")
        val dark = android.graphics.Color.parseColor("#111827")
        val grey = android.graphics.Color.parseColor("#9CA3AF")
        fun styleLbl(lbl: TextView?, n: Int) {
            lbl?.setTextColor(when { connectStep > n -> red; connectStep == n -> dark; else -> grey })
        }
        styleLbl(step1Lbl, 1); styleLbl(step2Lbl, 2); styleLbl(step3Lbl, 3); styleLbl(step4Lbl, 4)

        // Nav buttons
        btnBack?.visibility    = if (connectStep > 1) View.VISIBLE else View.GONE
        // Step 1: Next only visible when account is selected
        btnNext?.visibility    = when {
            connectStep == 1 -> if (googleAccount != null) View.VISIBLE else View.GONE
            connectStep < 4  -> View.VISIBLE
            else             -> View.GONE
        }
        btnConnect?.visibility = if (connectStep == 4) View.VISIBLE else View.GONE
        btnConnect?.text = if (connections.containsKey(activeBranch)) "Save Range" else "Connect"

        tvConnError?.visibility = View.GONE

        // Per-step UI
        when (connectStep) {
            1 -> updateAccountStep()
            2 -> updateSheetPickerLabel()
            3 -> updateTabSpinner()
            4 -> { updateColPreview(); updateSummary(); scheduleLivePreview() }
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
            fetchAndShowLivePreview(account, sheet.id, tab, s, e)
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
            // Fetch header row + 5 data rows = rows 1–6
            val range = "$tab!${startLetter}1:${endLetter}6"
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

            tvLivePreview?.text = "Preview: Row 1 header + next 5 rows"
            renderLivePreviewTable(rows, colStart, colEnd)

        } catch (e: Exception) {
            tvLivePreview?.text = "⚠ Preview error: ${e.message?.take(60)}"
            scrollLivePreview?.visibility = View.GONE
        } finally {
            pbPreviewLoad?.visibility = View.GONE
        }
    }

    private fun renderLivePreviewTable(rows: List<List<String>>, colStart: Int, colEnd: Int) {
        val table = tableLivePreview ?: return
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
        scrollLivePreview?.visibility = View.VISIBLE
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
        val existing = connections[activeBranch]
        val account = googleAccount
        val sheet   = selectedSheet ?: run { showErr("Sheet নেই"); return }
        if (selectedTab.isBlank())  { showErr("Tab নেই"); return }
        val s = parseColInput(etColStart?.text?.toString() ?: "") ?: run { showErr("Valid start column দিন (A বা 1)"); return }
        val e = parseColInput(etColEnd?.text?.toString() ?: "")   ?: run { showErr("Valid end column দিন (J বা 10)"); return }
        if (s < 1 || e < s) { showErr("start ≤ end হতে হবে"); return }

        val sRow = etStartRow?.text?.toString()?.trim()?.toIntOrNull()
        val eRow = etEndRow?.text?.toString()?.trim()?.toIntOrNull()

        val conn = SheetConn(
            branchId    = activeBranch,
            sheetId     = sheet.id,
            sheetName   = sheet.name,
            tabName     = selectedTab,
            colStart    = s,
            colEnd      = e,
            startRow    = sRow,
            endRow      = eRow,
            googleEmail = account?.email ?: existing?.googleEmail ?: "",
            connectedBy = auth.currentUser?.uid ?: existing?.connectedBy ?: "",
            connectedAt = System.currentTimeMillis(),
        )
        connections[activeBranch] = conn
        saveToFirebase(conn)
        toast(if (existing == null) "✅ $activeBranch connected!" else "✅ Range updated")
        screen = Screen.BRANCH_SELECT
        render()
    }

    private fun clearConnectForm() {
        // googleAccount রেখে দিচ্ছি (যাতে আবার লগইন না করতে হয়)
        availableSheets = emptyList(); selectedSheet = null
        availableTabs   = emptyList(); selectedTab   = ""
        etColStart?.setText("1"); etColEnd?.setText("10")
        // যদি account থাকে, sheets লোড করো
        if (googleAccount != null) loadSheetsForAccount()
    }

    private fun prefillConnectForm() {
        val conn = connections[activeBranch] ?: return
        selectedSheet = DriveFile(conn.sheetId, conn.sheetName)
        selectedTab = conn.tabName
        availableTabs = listOf(conn.tabName)
        updateSheetPickerLabel()
        etColStart?.setText(conn.colStart.toString())
        etColEnd?.setText(conn.colEnd.toString())
        if (googleAccount != null) loadSheetsForAccount()
    }

    private fun openRangeEditor() {
        val conn = connections[activeBranch] ?: return
        selectedSheet = DriveFile(conn.sheetId, conn.sheetName)
        selectedTab = conn.tabName
        availableTabs = listOf(conn.tabName)
        etColStart?.setText(conn.colStart.toString())
        etColEnd?.setText(conn.colEnd.toString())
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
        val conn = connections[activeBranch] ?: return
        tvManageBranch?.text = "Branch: ${branchLabel(activeBranch)}"

        // Branch switcher — only show when multiple connected branches
        val connectedBranches = branches.filter { connections.containsKey(it) }
        val ctx = context
        if (connectedBranches.size > 1 && ctx != null) {
            spinnerManageBranch?.visibility = View.VISIBLE
            val opts = connectedBranches.map { branchLabel(it) }
            spinnerManageBranch?.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, opts)
            val sel = connectedBranches.indexOf(activeBranch)
            if (sel >= 0) spinnerManageBranch?.setSelection(sel)
        } else {
            spinnerManageBranch?.visibility = View.GONE
        }

        activeManageTab = "overview"
        renderManageTabs()

        tvOvSheet?.text = conn.sheetName
        tvOvTab?.text   = conn.tabName
        tvOvCols?.text  = "${conn.columns.firstOrNull() ?: "A"}–${conn.columns.lastOrNull() ?: "J"} (${conn.columns.size}টি)"
        tvColPreviewMgr?.text = "${conn.columns.firstOrNull() ?: "A"} → ${conn.columns.lastOrNull() ?: "J"}  (${conn.columns.size} columns)"
        renderManageColTable(conn.colStart, conn.colEnd)
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
        connections.remove(branch)
        deleteFromFirebase(branch)
        toast("🗑 $branch disconnected")
        screen = Screen.BRANCH_SELECT
        render()
    }

    // ── Firebase ──────────────────────────────────────────────────────
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
                    sheetSnap.children.forEach { bs ->
                        val branchId  = bs.key ?: return@forEach
                        if (!branches.contains(branchId)) return@forEach
                        val cur       = bs.child("current")
                        val sheetId   = cur.child("sheetId")  .getValue(String::class.java) ?: return@forEach
                        val sheetName = cur.child("sheetName").getValue(String::class.java) ?: ""
                        val tabName   = cur.child("tabName")  .getValue(String::class.java) ?: ""
                        val colS      = cur.child("colStart") .getValue(Int::class.java)    ?: 1
                        val colE      = cur.child("colEnd")   .getValue(Int::class.java)    ?: 10
                        val email     = cur.child("googleEmail").getValue(String::class.java) ?: ""
                        val by        = cur.child("connectedBy").getValue(String::class.java) ?: ""
                        val at        = cur.child("connectedAt").getValue(Long::class.java)   ?: 0L
                        val sRow      = cur.child("startRow") .getValue(Int::class.java)
                        val eRow      = cur.child("endRow")   .getValue(Int::class.java)
                        connections[branchId] = SheetConn(branchId, sheetId, sheetName, tabName, colS, colE, sRow, eRow, email, by, at)
                    }
                }
            } catch (e: Exception) {
                Log.e("ConfigSheet", "Failed to load sheet config", e)
                toast("Sheet config load failed")
            } finally {
                if (isAdded) {
                    // Auto-decide screen based on connection state
                    val connectedBranches = branches.filter { connections.containsKey(it) }
                    val allConnected = branches.isNotEmpty() && connectedBranches.size == branches.size
                    if (allConnected) {
                        screen = Screen.MANAGING
                        activeBranch = if (branches.contains(activeBranch)) activeBranch else branches.first()
                    } else {
                        screen = Screen.BRANCH_SELECT
                    }
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
                    "sheetId"     to conn.sheetId,
                    "sheetName"   to conn.sheetName,
                    "tabName"     to conn.tabName,
                    "colStart"    to conn.colStart,
                    "colEnd"      to conn.colEnd,
                    "startRow"    to (conn.startRow ?: 1),
                    "endRow"      to (conn.endRow   ?: 0),  // 0 = শেষ পর্যন্ত
                    "googleEmail" to conn.googleEmail,
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
        val owner = viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch {
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
