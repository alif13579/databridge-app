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
        val googleEmail: String = "",
        val connectedBy: String = "",
        val connectedAt: Long   = 0L,
    ) {
        val columns: List<String> get() = ('A'..'Z').toList()
            .subList((colStart - 1).coerceIn(0, 25), colEnd.coerceIn(colStart, 26))
            .map { it.toString() }
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

    // Step 4 - column range + summary
    private var etColStart:   EditText? = null
    private var etColEnd:     EditText? = null
    private var tvColPreview: TextView? = null
    private var tvSummary:    TextView? = null

    // Nav buttons
    private var btnBack:    Button? = null
    private var btnNext:    Button? = null
    private var btnConnect: Button? = null
    private var btnCancelConn: View? = null
    private var tvConnError: TextView? = null

    /* ManagePanel */
    private var panelManage:     View? = null
    private var tvManageBranch:  TextView? = null
    private var tabOverview:     TextView? = null; private var tabColumns: TextView? = null; private var tabSync: TextView? = null
    private var indOverview:     View? = null;     private var indColumns: View? = null;     private var indSync: View? = null
    private var cardOverview:    View? = null
    private var cardColumns:     View? = null
    private var cardSync:        View? = null
    private var tvOvSheet:       TextView? = null; private var tvOvTab: TextView? = null; private var tvOvCols: TextView? = null
    private var tvColPreviewMgr: TextView? = null
    private var btnManReconnect: Button? = null;   private var btnManDisconn: Button? = null
    private var btnManBack:      View? = null
    private var btnSyncNow:      Button? = null

    private var activeManageTab = "overview"

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

        etColStart   = view.findViewById(R.id.etColStart)
        etColEnd     = view.findViewById(R.id.etColEnd)
        tvColPreview = view.findViewById(R.id.tvColPreview)
        tvSummary    = view.findViewById(R.id.tvSummary)

        btnBack       = view.findViewById(R.id.btnStepBack)
        btnNext       = view.findViewById(R.id.btnStepNext)
        btnConnect    = view.findViewById(R.id.btnStepConnect)
        btnCancelConn = view.findViewById(R.id.btnCancelConnect)
        tvConnError   = view.findViewById(R.id.tvConnectError)

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
                val branch = branches.getOrNull(pos - 1) ?: return
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

        btnManReconnect?.setOnClickListener { screen = Screen.CONNECTING; connectStep = 1; prefillConnectForm(); render() }
        btnManDisconn?.setOnClickListener   { handleDisconnect() }
        btnSyncNow?.setOnClickListener      { toast("🔄 Sync শুরু হয়েছে...") }

        etColStart?.addTextChangedListener(colWatcher); etColEnd?.addTextChangedListener(colWatcher)
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
            activeBranch = ""
            tvBranchLabel?.visibility = View.GONE
            spinnerBranch?.visibility = View.GONE
            tvSingleBranch?.visibility = View.GONE
            tvBranchEmpty?.visibility = View.VISIBLE
            cardBranchInfo?.visibility = View.GONE
            btnBranchAction?.visibility = View.GONE
            cardConnInfo?.visibility = View.GONE
            return
        }

        tvBranchLabel?.visibility = View.VISIBLE
        tvBranchEmpty?.visibility = View.GONE

        if (branches.size == 1) {
            activeBranch = branches.first()
            spinnerBranch?.visibility = View.GONE
            tvSingleBranch?.visibility = View.VISIBLE
            tvSingleBranch?.text = branchLabel(activeBranch)
            updateBranchActionCard()
            return
        }

        spinnerBranch?.visibility = View.VISIBLE
        tvSingleBranch?.visibility = View.GONE
        val opts = listOf("শাখা বেছে নিন...") + branches.map { branchLabel(it) }
        spinnerBranch?.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, opts)
        val sel = branches.indexOf(activeBranch)
        if (sel >= 0) spinnerBranch?.setSelection(sel + 1)
        updateBranchActionCard()
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

        tvConnError?.visibility = View.GONE

        // Per-step UI
        when (connectStep) {
            1 -> updateAccountStep()
            2 -> updateSheetPickerLabel()
            3 -> updateTabSpinner()
            4 -> { updateColPreview(); updateSummary() }
        }
    }

    private val colWatcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            if (connectStep == 4) { updateColPreview(); updateSummary() }
        }
    }

    private fun updateColPreview() {
        val s = etColStart?.text?.toString()?.toIntOrNull() ?: return
        val e = etColEnd?.text?.toString()?.toIntOrNull()   ?: return
        if (s < 1 || e < s || e > 26) { tvColPreview?.text = "⚠ Invalid range (1–26)"; return }
        val cols = ('A'..'Z').toList().subList(s - 1, e).map { it.toString() }
        tvColPreview?.text = "Columns: ${cols.joinToString(", ")} (${cols.size}টি)"
    }

    private fun updateSummary() {
        val sheetName = selectedSheet?.name ?: ""
        val sheetId   = selectedSheet?.id ?: ""
        val tab       = selectedTab
        val email     = googleAccount?.email ?: ""
        val s = etColStart?.text?.toString()?.toIntOrNull() ?: 1
        val e = etColEnd?.text?.toString()?.toIntOrNull() ?: 10
        val cols = ('A'..'Z').toList().subList((s - 1).coerceIn(0, 25), e.coerceIn(s, 26)).map { it.toString() }
        tvSummary?.text = "✅ Summary\n\nAccount: $email\nSheet: $sheetName\nSheet ID: ${if (sheetId.length > 24) sheetId.take(24) + "…" else sheetId}\nTab: $tab\nColumns: ${cols.firstOrNull() ?: "A"}–${cols.lastOrNull() ?: "J"} (${cols.size}টি)\nBranch: ${branchLabel(activeBranch)}"
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
        val account = googleAccount ?: run { showErr("Account নেই"); return }
        val sheet   = selectedSheet ?: run { showErr("Sheet নেই"); return }
        if (selectedTab.isBlank())  { showErr("Tab নেই"); return }
        val s = etColStart?.text?.toString()?.toIntOrNull() ?: 1
        val e = etColEnd?.text?.toString()?.toIntOrNull() ?: 10
        if (s < 1 || e < s || e > 26) { showErr("Valid column range দিন (1–26)"); return }

        val conn = SheetConn(
            branchId    = activeBranch,
            sheetId     = sheet.id,
            sheetName   = sheet.name,
            tabName     = selectedTab,
            colStart    = s,
            colEnd      = e,
            googleEmail = account.email ?: "",
            connectedBy = auth.currentUser?.uid ?: "",
            connectedAt = System.currentTimeMillis(),
        )
        connections[activeBranch] = conn
        saveToFirebase(conn)
        toast("✅ $activeBranch connected!")
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
        etColStart?.setText(conn.colStart.toString())
        etColEnd?.setText(conn.colEnd.toString())
        // Note: googleAccount + selectedSheet + tab will need re-selection
        if (googleAccount != null) loadSheetsForAccount()
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

        // Build dialog view: search EditText + ListView
        val dialogView = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 8, 0, 0)
        }
        val etSearch = android.widget.EditText(ctx).apply {
            hint = "Sheet খুঁজুন..."
            setSingleLine(true)
            setPadding(48, 24, 48, 16)
            textSize = 14f
        }
        val listView = android.widget.ListView(ctx)
        dialogView.addView(etSearch)
        dialogView.addView(listView)

        // Adapter backed by filtered list
        var filteredSheets = availableSheets.toMutableList()
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, filteredSheets.map { it.name }.toMutableList())
        listView.adapter = adapter

        val dialog = android.app.AlertDialog.Builder(ctx)
            .setTitle("Google Sheet বেছে নিন")
            .setView(dialogView)
            .setNegativeButton("বাতিল", null)
            .create()

        // Real-time filter
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                filteredSheets = if (query.isEmpty()) {
                    availableSheets.toMutableList()
                } else {
                    availableSheets.filter { it.name.lowercase().contains(query) }.toMutableList()
                }
                adapter.clear()
                adapter.addAll(filteredSheets.map { it.name })
                adapter.notifyDataSetChanged()
            }
        })

        listView.setOnItemClickListener { _, _, pos, _ ->
            val picked = filteredSheets.getOrNull(pos) ?: return@setOnItemClickListener
            if (selectedSheet?.id != picked.id) {
                selectedSheet = picked
                selectedTab   = ""
                availableTabs = emptyList()
                updateSheetPickerLabel()
                loadTabsForSheet()
            } else {
                updateSheetPickerLabel()
            }
            dialog.dismiss()
        }

        dialog.show()
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
        activeManageTab = "overview"
        renderManageTabs()

        tvOvSheet?.text = conn.sheetName
        tvOvTab?.text   = conn.tabName
        tvOvCols?.text  = "${conn.columns.firstOrNull() ?: "A"}–${conn.columns.lastOrNull() ?: "J"} (${conn.columns.size}টি)"
        tvColPreviewMgr?.text = conn.columns.mapIndexed { i, c -> "$c: Col${i+1}" }.joinToString("  ·  ")
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
                        connections[branchId] = SheetConn(branchId, sheetId, sheetName, tabName, colS, colE, email, by, at)
                    }
                }
            } catch (e: Exception) {
                Log.e("ConfigSheet", "Failed to load sheet config", e)
                toast("Sheet config load failed")
            } finally {
                if (isAdded) {
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
