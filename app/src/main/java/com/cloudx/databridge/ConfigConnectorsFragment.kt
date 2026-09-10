package com.cloudx.databridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient

/**
 * 🔗 Connectors — Config tab (Sheet Library).
 *
 * Every save is a neutral 📚 sheet library: 4-step wizard (Account →
 * Sheet → Tab → Columns) with NO fragment choice and NO kind spinners —
 * just which columns can match and which can receive writes. WHAT data
 * flows through those columns is bound per-fragment where the sheet is
 * used (Scanner/CC 🔌 socket). See SheetLibraryModels.kt /
 * SheetLibraryRepository.kt.
 *
 * Branch-wise, multiple entries per branch allowed (same UX pattern as
 * ConfigSheetFragment's connected-branches list) — data model and Firebase
 * persistence (config/connectors/{branchId}/{connectionId}).
 *
 * Google Sheets/Drive API calls reuse ConfigSheetDriveApi.kt's functions (fetchDriveSpreadsheets,
 * fetchSheetTabs) — but with WRITE-capable OAuth scopes (OAUTH_SCOPE_WRITE), since this
 * connector needs to write scanned values into cells, not just read for sync.
 */
class ConfigConnectorsFragment : Fragment() {

    // ── Panels ───────────────────────────────────────────────────────────────
    private var panelBranchSelect: View? = null
    private var panelScConnect:    View? = null

    // ── Panel 1: Branch select ──────────────────────────────────────────────
    private var tvScBranchEmpty:       TextView? = null
    private var tvScBranchLabel:       TextView? = null
    private var spinnerScBranch:       Spinner?  = null
    private var tvScConnectionsLabel:  TextView? = null
    private var containerScConnections: LinearLayout? = null
    private var tvScNoConnections:     TextView? = null
    private var btnScAddConnection:    Button?   = null

    // ── Panel 2: Connect wizard ─────────────────────────────────────────────
    private var btnScCancelConnect: View? = null
    private var tvScConnBranchSub:  TextView? = null

    private var scStep1Dot: TextView? = null; private var scStep1Lbl: TextView? = null; private var scStep1Line: View? = null
    private var scStep2Dot: TextView? = null; private var scStep2Lbl: TextView? = null; private var scStep2Line: View? = null
    private var scStep3Dot: TextView? = null; private var scStep3Lbl: TextView? = null; private var scStep3Line: View? = null
    private var scStep4Dot: TextView? = null; private var scStep4Lbl: TextView? = null

    private var scStepView1: View? = null
    private var scStepView2: View? = null
    private var scStepView3: View? = null
    private var scStepView4: View? = null

    // Step 1: Branch + Fragment dropdowns, then Google account.
    // Branch + fragment-wise MULTIPLE sheets allowed — every save creates a
    // new connection under config/connectors/{branch}/current.
    private var spinnerScWizardBranch: Spinner? = null
    private var spinnerScPurpose:      Spinner? = null
    private var tvScPurposeLabel:      TextView? = null
    private var scCardSelectedAccount:   View? = null
    private var tvScSelectedAccountName:  TextView? = null
    private var tvScSelectedAccountEmail: TextView? = null
    private var btnScPickAccount:         View? = null
    private var tvScPickAccountLabel:     TextView? = null

    // Step 2
    private var tvScSelectedSheet: TextView? = null
    private var pbScSheetLoad:     ProgressBar? = null

    // Step 3
    private var etScNickname:   EditText? = null
    private var spinnerScTabMode: Spinner? = null
    private var layoutScTabPlain: View? = null
    private var layoutScTabDynamic: View? = null
    private var layoutScTabHybrid: View? = null
    private var etScTabFixed: EditText? = null
    private var spinnerScTabToken: Spinner? = null
    private var etScTabHybrid: EditText? = null
    private var spinnerScTabHybridToken: Spinner? = null
    private var tvScTabPreview: TextView? = null

    // Step 4
    private var etScHeaderRow: EditText? = null
    private var etScColStart: EditText? = null
    private var etScColEnd: EditText? = null
    private var etScDataStart: EditText? = null
    private var tvScRangePreview: TextView? = null
    private var tvScSummary:     TextView? = null
    private var tvScRulePreview: TextView? = null
    private var scrollScRulePreview: android.widget.HorizontalScrollView? = null
    private var tableScRulePreview: android.widget.TableLayout? = null

    private var tvScConnectError: TextView? = null
    private var btnScStepBack:    Button? = null
    private var btnScStepNext:    Button? = null
    private var btnScStepConnect: Button? = null

    // ── State ────────────────────────────────────────────────────────────────
    private var myBranches: List<Pair<String, String>> = emptyList() // (branchId, branchName)
    private var selectedBranchId: String = ""
    private var branchConnections: List<ScannerSheetConn> = emptyList()
    private var branchScannerBindings: List<ScannerBinding> = emptyList()
    private var branchCcBindings: List<CcBinding> = emptyList()

    private var connectStep = 1
    private var editingConnectionId: String = "" // blank = new connection
    // Step-1 selections: which branch + which fragment + which date scope this
    // sheet serves. (Wizard dropdowns — outer spinner only filters Panel-1.)
    private var connPurpose: String = SheetPurpose.REMARK
    private var wizardBranchId: String = ""
    private var connScopeType: String = SheetScope.GLOBAL
    private var spinnerScScope: Spinner? = null
    private var layoutScScopeMonth: View? = null
    private var spinnerScScopeMonth: Spinner? = null
    private var etScScopeYear: EditText? = null
    private var layoutScScopeRange: View? = null
    private var etScScopeFrom: EditText? = null
    private var etScScopeTo: EditText? = null

    private var googleSignInClient: GoogleSignInClient? = null
    private var googleAccount: GoogleSignInAccount? = null
    private var cachedAccessToken: String? = null
    /** Set if GoogleSignInClient construction failed in onCreate() — surfaced to the user
     *  when they tap "Sign in with Google" instead of the button silently doing nothing. */
    private var initError: String? = null

    private var availableSheets: List<DriveFile> = emptyList()
    private var selectedSheet: DriveFile? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    // ── Activity-result launchers (must be registered before onCreate completes) ──
    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleSignInResult(result.data)
    }

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
                .requestScopes(
                    Scope(ConfigSheetDriveApi.SCOPE_DRIVE_FILE),
                    Scope(ConfigSheetDriveApi.SCOPE_SHEETS_WRITE)
                )
                .build()
            googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)

            // Restore last signed-in account ONLY if its email matches what THIS feature
            // (Connectors) was last connected with — see PREFS_FILE_NAME doc comment
            // below for why we can't just trust GoogleSignIn.getLastSignedInAccount()
            // alone. Logic lives in GoogleSignInHelper (shared with ConfigSheetFragment).
            googleAccount = GoogleSignInHelper.restoreOwnAccountIfMatching(
                context = requireContext(),
                prefsFileName = PREFS_FILE_NAME,
                requiredScopes = listOf(
                    Scope(ConfigSheetDriveApi.SCOPE_DRIVE_FILE),
                    Scope(ConfigSheetDriveApi.SCOPE_SHEETS_WRITE)
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("ConfigConnectors", "Google Sign-In init failed", e)
            initError = e.message ?: e.javaClass.simpleName
        }
    }

    /**
     * Per-feature Google-account isolation.
     *
     * GoogleSignIn.getLastSignedInAccount() and GoogleSignInClient.signOut() are both
     * DEVICE-WIDE — Google Play Services caches one signed-in account across the whole
     * app, not per-fragment. ConfigSheetFragment (Sheets tab) and this fragment
     * (Connectors tab) each build their own GoogleSignInClient, but without this guard
     * they'd silently share that one cached account: switching accounts in one tab would
     * make the other tab appear logged out too, since its own in-memory `googleAccount`
     * only gets re-derived from the shared cache the next time that fragment is created
     * (i.e. next time the user opens that tab).
     *
     * Fix: each feature remembers, in its OWN SharedPreferences file, the email of the
     * account IT last connected with. On create, we only trust the device-wide cached
     * account if its email matches our own saved one. A "Switch account" in the other
     * tab still has to call signOut() to force Google's account chooser to appear (no
     * way around that with this API) — but that no longer matters to us: next time this
     * fragment is created, the cache might be empty or hold a different account, but
     * since it won't match our saved email either way, we correctly show "not connected"
     * only when the user actually switched THIS feature's account, not someone else's.
     */
    private val PREFS_FILE_NAME = "connectors_google_account"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_config_scanner_sheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        panelBranchSelect = view.findViewById(R.id.panelBranchSelect)
        panelScConnect    = view.findViewById(R.id.panelScConnect)

        tvScBranchEmpty        = view.findViewById(R.id.tvScBranchEmpty)
        tvScBranchLabel        = view.findViewById(R.id.tvScBranchLabel)
        spinnerScBranch        = view.findViewById(R.id.spinnerScBranch)
        tvScConnectionsLabel   = view.findViewById(R.id.tvScConnectionsLabel)
        containerScConnections = view.findViewById(R.id.containerScConnections)
        tvScNoConnections      = view.findViewById(R.id.tvScNoConnections)
        btnScAddConnection     = view.findViewById(R.id.btnScAddConnection)

        btnScCancelConnect = view.findViewById(R.id.btnScCancelConnect)
        tvScConnBranchSub  = view.findViewById(R.id.tvScConnBranchSub)

        scStep1Dot = view.findViewById(R.id.scStep1Dot); scStep1Lbl = view.findViewById(R.id.scStep1Lbl); scStep1Line = view.findViewById(R.id.scStep1Line)
        scStep2Dot = view.findViewById(R.id.scStep2Dot); scStep2Lbl = view.findViewById(R.id.scStep2Lbl); scStep2Line = view.findViewById(R.id.scStep2Line)
        scStep3Dot = view.findViewById(R.id.scStep3Dot); scStep3Lbl = view.findViewById(R.id.scStep3Lbl); scStep3Line = view.findViewById(R.id.scStep3Line)
        scStep4Dot = view.findViewById(R.id.scStep4Dot); scStep4Lbl = view.findViewById(R.id.scStep4Lbl)

        scStepView1 = view.findViewById(R.id.scStepView1)
        scStepView2 = view.findViewById(R.id.scStepView2)
        scStepView3 = view.findViewById(R.id.scStepView3)
        scStepView4 = view.findViewById(R.id.scStepView4)

        scCardSelectedAccount   = view.findViewById(R.id.scCardSelectedAccount)
        tvScSelectedAccountName  = view.findViewById(R.id.tvScSelectedAccountName)
        tvScSelectedAccountEmail = view.findViewById(R.id.tvScSelectedAccountEmail)
        btnScPickAccount         = view.findViewById(R.id.btnScPickAccount)
        tvScPickAccountLabel     = view.findViewById(R.id.tvScPickAccountLabel)

        tvScSelectedSheet = view.findViewById(R.id.tvScSelectedSheet)
        pbScSheetLoad     = view.findViewById(R.id.pbScSheetLoad)

        etScNickname   = view.findViewById(R.id.etScNickname)
        spinnerScTabMode = view.findViewById(R.id.spinnerScTabMode)
        layoutScTabPlain = view.findViewById(R.id.layoutScTabPlain)
        layoutScTabDynamic = view.findViewById(R.id.layoutScTabDynamic)
        layoutScTabHybrid = view.findViewById(R.id.layoutScTabHybrid)
        etScTabFixed = view.findViewById(R.id.etScTabFixed)
        spinnerScTabToken = view.findViewById(R.id.spinnerScTabToken)
        etScTabHybrid = view.findViewById(R.id.etScTabHybrid)
        spinnerScTabHybridToken = view.findViewById(R.id.spinnerScTabHybridToken)
        tvScTabPreview = view.findViewById(R.id.tvScTabPreview)
        setupTabBuilder()

        etScHeaderRow = view.findViewById(R.id.etScHeaderRow)
        etScColStart = view.findViewById(R.id.etScColStart)
        etScColEnd = view.findViewById(R.id.etScColEnd)
        etScDataStart = view.findViewById(R.id.etScDataStart)
        tvScRangePreview = view.findViewById(R.id.tvScRangePreview)
        tvScRulePreview = view.findViewById(R.id.tvScRulePreview)
        scrollScRulePreview = view.findViewById(R.id.scrollScRulePreview)
        tableScRulePreview = view.findViewById(R.id.tableScRulePreview)
        val rangeWatcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = updateRangeSummary()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        etScHeaderRow?.addTextChangedListener(rangeWatcher)
        etScColStart?.addTextChangedListener(rangeWatcher)
        etScColEnd?.addTextChangedListener(rangeWatcher)
        etScDataStart?.addTextChangedListener(rangeWatcher)
        view.findViewById<View>(R.id.btnScPreviewRules)?.setOnClickListener { previewRules() }
        tvScSummary     = view.findViewById(R.id.tvScSummary)

        tvScConnectError = view.findViewById(R.id.tvScConnectError)
        btnScStepBack    = view.findViewById(R.id.btnScStepBack)
        btnScStepNext    = view.findViewById(R.id.btnScStepNext)
        btnScStepConnect = view.findViewById(R.id.btnScStepConnect)

        btnScAddConnection?.setOnClickListener { startNewConnection() }
        btnScCancelConnect?.setOnClickListener { exitWizardToBranchSelect() }
        spinnerScWizardBranch = view.findViewById(R.id.spinnerScWizardBranch)
        spinnerScPurpose = view.findViewById(R.id.spinnerScPurpose)
        tvScPurposeLabel = view.findViewById(R.id.tvScPurposeLabel)
        // Libraries are fragment-neutral: the Fragment dropdown stays
        // hidden — WHAT data flows is bound per-fragment later (Scanner 🔌).
        spinnerScPurpose?.visibility = View.GONE
        tvScPurposeLabel?.visibility = View.GONE
        spinnerScScope = view.findViewById(R.id.spinnerScScope)
        layoutScScopeMonth = view.findViewById(R.id.layoutScScopeMonth)
        spinnerScScopeMonth = view.findViewById(R.id.spinnerScScopeMonth)
        etScScopeYear = view.findViewById(R.id.etScScopeYear)
        layoutScScopeRange = view.findViewById(R.id.layoutScScopeRange)
        etScScopeFrom = view.findViewById(R.id.etScScopeFrom)
        etScScopeTo = view.findViewById(R.id.etScScopeTo)
        // Bind AFTER the fields above exist — earlier wiring silently no-ops.
        setupScopeDatePickers()
        setupWizardSpinners()
        btnScPickAccount?.setOnClickListener { pickGoogleAccount() }
        tvScSelectedSheet?.setOnClickListener { showSheetPicker() }
        btnScStepBack?.setOnClickListener { goToStep(connectStep - 1) }
        btnScStepNext?.setOnClickListener { attemptGoToStep(connectStep + 1) }
        btnScStepConnect?.setOnClickListener { saveConnection() }

        spinnerScBranch?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val branch = myBranches.getOrNull(position) ?: return
                if (branch.first == selectedBranchId) return
                selectedBranchId = branch.first
                loadConnectionsForSelectedBranch()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadMyBranches()
    }

    // ── Branch loading ──────────────────────────────────────────────────────
    private fun loadMyBranches() {
        val ids = RbacManager.current.branchIds
        if (ids.isEmpty()) {
            tvScBranchEmpty?.visibility = View.VISIBLE
            tvScBranchLabel?.visibility = View.GONE
            spinnerScBranch?.visibility = View.GONE
            return
        }
        tvScBranchEmpty?.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            // Names from Supabase (source of truth); unknown ids fall back
            // to the raw id (no Firebase — the branches mirror is legacy).
            val supaNames = runCatching { SupabaseBranchReader.listBranches() }
                .getOrNull().orEmpty().associate { it.branchId to it.name }
            val resolved = ids.map { id ->
                id to (supaNames[id]?.takeIf { it.isNotBlank() } ?: id)
            }
            if (!isAdded) return@launch
            myBranches = resolved
            val adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_item, resolved.map { it.second }
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerScBranch?.adapter = adapter
            if (resolved.isNotEmpty()) {
                selectedBranchId = resolved.first().first
                if (wizardBranchId.isBlank()) wizardBranchId = selectedBranchId
                refreshWizardBranchSpinner()
                loadConnectionsForSelectedBranch()
            }
        }
    }

    // ── Connections list (Panel 1) ──────────────────────────────────────────
    private fun loadConnectionsForSelectedBranch() {
        if (selectedBranchId.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            branchConnections = try {
                ScannerSheetRepository.loadConnections(selectedBranchId)
            } catch (e: Exception) {
                emptyList()
            }
            branchScannerBindings = try {
                SheetLibraryRepository.loadScannerBindings(selectedBranchId)
            } catch (e: Exception) {
                emptyList()
            }
            branchCcBindings = try {
                SheetLibraryRepository.loadCcBindings(selectedBranchId)
            } catch (e: Exception) {
                emptyList()
            }
            if (!isAdded) return@launch
            renderConnectionsList()
        }
    }

    private fun renderConnectionsList() {
        val container = containerScConnections ?: return
        container.removeAllViews()
        val ctx = context ?: return

        if (branchConnections.isEmpty()) {
            tvScConnectionsLabel?.visibility = View.GONE
            tvScNoConnections?.visibility = View.VISIBLE
            return
        }
        tvScConnectionsLabel?.visibility = View.VISIBLE
        tvScNoConnections?.visibility = View.GONE

        val libraries = branchConnections.filter { it.isLibrary }

        fun actionBtn(label: String, bg: String, fg: String, onTap: () -> Unit): TextView =
            TextView(ctx).apply {
                text = label
                textSize = 12.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor(fg))
                setBackgroundColor(android.graphics.Color.parseColor(bg))
                setPadding(28, 16, 28, 16)
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = 16
                layoutParams = lp
                setOnClickListener { onTap() }
            }

        fun sectionHeader(text: String) {
            container.addView(TextView(ctx).apply {
                this.text = text
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ctx.getColor(R.color.theme_text_secondary))
                setPadding(4, 16, 4, 8)
            })
        }

        fun badgeView(text: String, fg: String, bg: String): TextView =
            TextView(ctx).apply {
                this.text = text
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor(fg))
                setBackgroundColor(android.graphics.Color.parseColor(bg))
                setPadding(20, 8, 20, 8)
            }

        // ── 📚 Libraries: full cards with per-sheet Used-by summary ──
        if (libraries.isNotEmpty()) {
            sectionHeader("📚 Sheet library (${libraries.size}) — kon sheet kothay use hocche")
            libraries.forEach { conn ->
                val card = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(32, 28, 32, 24)
                    setBackgroundResource(R.drawable.bg_card_rounded)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 24 }
                    alpha = if (conn.enabled) 1f else 0.55f
                }
                val topRow = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val title = TextView(ctx).apply {
                    text = conn.nickname.ifBlank { conn.sheetName.ifBlank { "(নাম নেই)" } }
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ctx.getColor(R.color.theme_text_primary))
                    layoutParams = android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                topRow.addView(title)
                topRow.addView(badgeView("📚 Library", "#2563EB", "#DBEAFE"))
                val scopeBadge = badgeView(
                    SheetScope.badge(conn.scopeType, conn.scopeMonth, conn.scopeFrom, conn.scopeTo),
                    "#0369A1", "#E0F2FE")
                (scopeBadge.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let {
                    it.marginStart = 12
                } ?: scopeBadge.apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = 12
                    }
                }
                topRow.addView(scopeBadge)
                card.addView(topRow)
                val libRange = SheetLibraryRepository.toLibrary(conn)
                val rangeStart = ConfigSheetParseUtil.colIndexToLetter(libRange.effectiveColStart())
                val rangeEnd = ConfigSheetParseUtil.colIndexToLetter(libRange.effectiveColEnd())
                val colsLine = "header ${libRange.resolvedHeaderRow()} • " +
                    "$rangeStart–$rangeEnd (${libRange.effectiveColEnd() - libRange.effectiveColStart() + 1}টি) • " +
                    "data row ${libRange.effectiveDataStartRow()} থেকে"
                // Used-by: every binding on this sheet in one place.
                val scannerBinding = branchScannerBindings.firstOrNull { it.libraryId == conn.connectionId }
                val ccBinding = branchCcBindings.firstOrNull { it.libraryId == conn.connectionId }
                val usedBy = buildString {
                    append("USED BY\n")
                    append(if (scannerBinding != null && scannerBinding.enabled)
                        "🔌 Scanner: ${scannerBinding.summary()}"
                    else "➖ Scanner: bind hoyni")
                    append("\n")
                    append(if (ccBinding != null && ccBinding.enabled)
                        "☎️ CC mirror: ${ccBinding.summary()}\n📡 Fetch: ${ccBinding.fetchSummary()}"
                    else "➖ CC mirror: bind hoyni")
                }
                card.addView(TextView(ctx).apply {
                    text = buildString {
                        append(conn.sheetName)
                        if (!conn.enabled) append("  •  disabled")
                        append("\n$colsLine")
                        append("\n$usedBy")
                    }
                    textSize = 12f
                    setTextColor(ctx.getColor(R.color.theme_text_secondary))
                    setPadding(0, 8, 0, 4)
                })
                val btnRow = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(0, 12, 0, 0)
                }
                btnRow.addView(actionBtn("✏️ Edit", "#EFF6FF", "#1D4ED8") { startEditConnection(conn) })
                btnRow.addView(actionBtn(
                    if (conn.enabled) "⏸ Disable" else "▶ Enable", "#F1F5F9", "#475569") {
                    setConnectionEnabled(conn, !conn.enabled)
                })
                btnRow.addView(actionBtn("🗑 Delete", "#FEF2F2", "#B91C1C") { confirmDeleteConnection(conn) })
                card.addView(btnRow)
                card.setOnClickListener { startEditConnection(conn) }
                container.addView(card)
            }
        }
    }

    /** Enable/disable toggle per connection (disabled = skipped by mirror,
     *  sync and test, kept for record). */
    private fun setConnectionEnabled(conn: ScannerSheetConn, enabled: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val updated = conn.copy(enabled = enabled)
                val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                ScannerSheetRepository.saveConnectionFields(
                    conn.branchId, conn.connectionId, mapOf("enabled" to enabled), uid)
                branchConnections = branchConnections.map { if (it.connectionId == conn.connectionId) updated else it }
                if (isAdded) renderConnectionsList()
            } catch (e: Exception) {
                if (isAdded) Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDeleteConnection(conn: ScannerSheetConn) {
        val ctx = context ?: return
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Delete connection?")
            .setMessage("\"${conn.nickname.ifBlank { conn.sheetName }}\" মুছে ফেলা হবে। এটা undo করা যাবে না।")
            .setPositiveButton("Delete") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                    val actingName = withContext(Dispatchers.IO) {
                        try {
                            com.google.firebase.database.FirebaseDatabase.getInstance()
                                .reference.child("users/$uid/profile/name")
                                .get().await().getValue(String::class.java).orEmpty()
                        } catch (_: Exception) { "" }
                    }
                    try {
                        ScannerSheetRepository.deleteConnection(conn.branchId, conn.connectionId, uid, actingName)
                        loadConnectionsForSelectedBranch()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Step 4: header + data range (sheets-tab style) ─────────────────────
    // Letter OR number (A / 1), live summary + sheet preview. Column-level
    // mapping lives in the fragment sockets, not here.

    private data class RangeSel(
        val headerRow: Int, val colStart: Int, val colEnd: Int, val dataStart: Int,
    )

    private fun collectHeaderRow(): Int =
        etScHeaderRow?.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, 20) ?: 1

    private fun collectColStart(): Int? =
        ConfigSheetParseUtil.parseColInput(etScColStart?.text?.toString().orEmpty())

    private fun collectColEnd(): Int? =
        ConfigSheetParseUtil.parseColInput(etScColEnd?.text?.toString().orEmpty())

    private fun collectDataStart(headerRow: Int): Int =
        etScDataStart?.text?.toString()?.trim()?.toIntOrNull()
            ?.coerceIn(headerRow + 1, 200) ?: (headerRow + 1).coerceAtMost(200)

    /** Normalized range, or null (error already shown when [loud]). */
    private fun collectRange(loud: Boolean = false): RangeSel? {
        val headerRow = collectHeaderRow()
        val s = collectColStart()
        if (s == null || s < 1) {
            if (loud) showScErr("শুরুর column দিন (A বা 1)")
            return null
        }
        val e = collectColEnd()
        if (e == null || e < s) {
            if (loud) showScErr("শেষ column দিন (${ConfigSheetParseUtil.colIndexToLetter(s)} বা $s থেকে বড়)")
            return null
        }
        return RangeSel(headerRow, s, e, collectDataStart(headerRow))
    }

    private fun updateRangeSummary() {
        val headerRow = collectHeaderRow()
        val s = collectColStart()
        val e = collectColEnd()
        if (s == null || s < 1 || e == null || e < s) {
            tvScRangePreview?.text = "⚠ Column range দিন (A → K বা 1 → 11)"
            tvScSummary?.text = "Header + column range + data row ঠিক করুন।"
            return
        }
        val startLetter = ConfigSheetParseUtil.colIndexToLetter(s)
        val endLetter = ConfigSheetParseUtil.colIndexToLetter(e)
        val count = e - s + 1
        val dataStart = collectDataStart(headerRow)
        tvScRangePreview?.text =
            "Columns: $startLetter ($s) – $endLetter ($e)  ·  মোট $count টি"
        tvScSummary?.text =
            "📚 Library: header row $headerRow • columns $startLetter–$endLetter (${count}টি) • data row $dataStart থেকে। " +
                "Kon column-e ki jabe সেটা fragment-এর 🔌 থেকে ঠিক হবে।"
    }

    /** Step-1 dropdowns: Branch + Fragment (extensible via SheetPurpose.ALL).
     *  Outer spinner only filters the Panel-1 list; the wizard's own branch
     *  decides where the connection is actually saved (branch-wise single /
     *  multiple sheets per fragment). */
    private fun setupWizardSpinners() {
        val ctx = context ?: return
        spinnerScPurpose?.adapter = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_item,
            SheetPurpose.ALL.map { SheetPurpose.label(it) }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerScPurpose?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                connPurpose = SheetPurpose.ALL.getOrElse(position) { SheetPurpose.REMARK }
                updateWizardSubtitle()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerScScope?.adapter = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_item,
            SheetScope.ALL.map { SheetScope.label(it) }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerScScope?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                connScopeType = SheetScope.ALL.getOrElse(position) { SheetScope.GLOBAL }
                updateScopeRows()
                updateWizardSubtitle()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val monthNames = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )
        spinnerScScopeMonth?.adapter = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_item, monthNames
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        refreshWizardBranchSpinner()
        spinnerScWizardBranch?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                wizardBranchId = myBranches.getOrNull(position)?.first.orEmpty()
                updateWizardSubtitle()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        refreshWizardBranchSpinner()
    }

    private fun refreshWizardBranchSpinner() {
        val ctx = context ?: return
        val branchNames = myBranches.map { it.second }
        spinnerScWizardBranch?.adapter = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_item, branchNames
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val pos = myBranches.indexOfFirst { it.first == wizardBranchId }
            .takeIf { it >= 0 } ?: myBranches.indexOfFirst { it.first == selectedBranchId }
            .takeIf { it >= 0 } ?: 0
        if (myBranches.isNotEmpty()) {
            spinnerScWizardBranch?.setSelection(pos.coerceIn(myBranches.indices))
            wizardBranchId = myBranches[pos.coerceIn(myBranches.indices)].first
        }
        val purposePos = SheetPurpose.ALL.indexOf(connPurpose).takeIf { it >= 0 } ?: 0
        spinnerScPurpose?.setSelection(purposePos)
        val scopePos = SheetScope.ALL.indexOf(connScopeType).takeIf { it >= 0 }
            ?: SheetScope.ALL.indexOf(SheetScope.GLOBAL)
        spinnerScScope?.setSelection(scopePos)
        updateScopeRows()
        updateWizardSubtitle()
    }

    private fun updateWizardSubtitle() {
        val branchName = myBranches.firstOrNull { it.first == wizardBranchId }?.second
            ?: myBranches.firstOrNull { it.first == selectedBranchId }?.second
            ?: wizardBranchId.ifBlank { selectedBranchId }
        tvScConnBranchSub?.text = listOfNotNull(
            branchName.takeIf { it.isNotBlank() },
            SheetPurpose.label(connPurpose).takeIf { SheetPurpose.isKnown(connPurpose) },
            scopeBadgeForInputs().takeIf { it.isNotBlank() },
        ).joinToString(" • ").ifBlank { "—" }
    }

    /** Shows month OR range inputs depending on the scope dropdown. */
    private fun updateScopeRows() {
        layoutScScopeMonth?.visibility =
            if (connScopeType == SheetScope.MONTH) View.VISIBLE else View.GONE
        layoutScScopeRange?.visibility =
            if (connScopeType == SheetScope.RANGE) View.VISIBLE else View.GONE
    }

    private fun defaultScopeInputs() {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Dhaka"))
        spinnerScScopeMonth?.setSelection((cal.get(java.util.Calendar.MONTH)).coerceIn(0, 11))
        if (etScScopeYear?.text?.toString()?.trim().isNullOrBlank()) {
            etScScopeYear?.setText(cal.get(java.util.Calendar.YEAR).toString())
        }
    }

    private data class ScopeSel(
        val type: String, val month: String, val from: String, val to: String,
    )

    /** Normalized scope from the Step-1 inputs, or null (error already shown). */
    private fun collectScope(): ScopeSel? {
        if (!SheetScope.isKnown(connScopeType)) { showScErr("Scope বেছে নিন"); return null }
        if (connScopeType == SheetScope.MONTH) {
            val month = (spinnerScScopeMonth?.selectedItemPosition ?: 0) + 1
            val year = etScScopeYear?.text?.toString()?.trim()?.toIntOrNull()
            if (year == null || year !in 2000..2100) { showScErr("Year ঠিক দিন (2000–2100)"); return null }
            val norm = SheetScope.normMonth("%04d-%02d".format(year, month))
                ?: run { showScErr("Month ঠিক নেই"); return null }
            return ScopeSel(connScopeType, norm, "", "")
        }
        if (connScopeType == SheetScope.RANGE) {
            val from = SheetScope.normDay(etScScopeFrom?.text?.toString().orEmpty())
                ?: run { showScErr("From date ঠিক দিন (yyyy-MM-dd)"); return null }
            val to = SheetScope.normDay(etScScopeTo?.text?.toString().orEmpty())
                ?: run { showScErr("To date ঠিক দিন (yyyy-MM-dd)"); return null }
            if (from > to) { showScErr("From date To date-er pore hote parbena"); return null }
            return ScopeSel(connScopeType, "", from, to)
        }
        return ScopeSel(SheetScope.GLOBAL, "", "", "")
    }

    private fun scopeBadgeForInputs(): String {
        val s = collectScopeQuiet() ?: return ""
        return SheetScope.badge(s.type, s.month, s.from, s.to)
    }

    /** collectScope() without error UI (subtitle preview only). */
    private fun collectScopeQuiet(): ScopeSel? {
        if (!SheetScope.isKnown(connScopeType)) return null
        if (connScopeType == SheetScope.MONTH) {
            val month = (spinnerScScopeMonth?.selectedItemPosition ?: 0) + 1
            val year = etScScopeYear?.text?.toString()?.trim()?.toIntOrNull()
                ?: return null
            if (year !in 2000..2100) return null
            val norm = SheetScope.normMonth("%04d-%02d".format(year, month)) ?: return null
            return ScopeSel(connScopeType, norm, "", "")
        }
        if (connScopeType == SheetScope.RANGE) {
            val from = SheetScope.normDay(etScScopeFrom?.text?.toString().orEmpty()) ?: return null
            val to = SheetScope.normDay(etScScopeTo?.text?.toString().orEmpty()) ?: return null
            if (from > to) return null
            return ScopeSel(connScopeType, "", from, to)
        }
        return ScopeSel(SheetScope.GLOBAL, "", "", "")
    }

    // ── Wizard entry/exit ────────────────────────────────────────────────────
    private fun startNewConnection() {
        editingConnectionId = ""
        selectedSheet = null
        connPurpose = ""
        wizardBranchId = selectedBranchId
        connScopeType = SheetScope.GLOBAL
        etScScopeFrom?.setText("")
        etScScopeTo?.setText("")
        defaultScopeInputs()
        refreshWizardBranchSpinner()
        etScNickname?.setText("")
        seedTabBuilder("Day {dd}")
        etScHeaderRow?.setText("1")
        etScColStart?.setText("A")
        etScColEnd?.setText("K")
        etScDataStart?.setText("2")
        tvScSelectedSheet?.text = "— Sheet বেছে নিন —"
        enterWizard()
    }

    private fun startEditConnection(conn: ScannerSheetConn) {
        editingConnectionId = conn.connectionId
        selectedSheet = DriveFile(conn.sheetId, conn.sheetName)
        connPurpose = ""
        wizardBranchId = conn.branchId.ifBlank { selectedBranchId }
        connScopeType = conn.scopeType.takeIf { SheetScope.isKnown(it) } ?: SheetScope.GLOBAL
        // Seed scope inputs: month → month spinner + year; range → from/to.
        if (connScopeType == SheetScope.MONTH) {
            try {
                val ym = java.time.YearMonth.parse(conn.scopeMonth.trim())
                spinnerScScopeMonth?.setSelection((ym.monthValue - 1).coerceIn(0, 11))
                etScScopeYear?.setText(ym.year.toString())
            } catch (_: Exception) { defaultScopeInputs() }
        } else {
            defaultScopeInputs()
        }
        if (connScopeType == SheetScope.RANGE) {
            etScScopeFrom?.setText(conn.scopeFrom)
            etScScopeTo?.setText(conn.scopeTo)
        }
        refreshWizardBranchSpinner()
        etScNickname?.setText(conn.nickname)
        seedTabBuilder(conn.tabPattern)
        etScHeaderRow?.setText(conn.resolvedHeaderRow().toString())
        // Seed range from stored values (letters); old column-rule rows are
        // not seeded anymore — mapping lives in the fragment sockets.
        val libRange = SheetLibraryRepository.toLibrary(conn)
        etScColStart?.setText(ConfigSheetParseUtil.colIndexToLetter(libRange.effectiveColStart()))
        etScColEnd?.setText(ConfigSheetParseUtil.colIndexToLetter(libRange.effectiveColEnd()))
        etScDataStart?.setText(libRange.effectiveDataStartRow().toString())
        tvScSelectedSheet?.text = conn.sheetName.ifBlank { "— Sheet বেছে নিন —" }
        enterWizard()
    }

    private fun enterWizard() {
        if (wizardBranchId.isBlank()) wizardBranchId = selectedBranchId
        refreshWizardBranchSpinner()
        updateWizardSubtitle()
        panelBranchSelect?.visibility = View.GONE
        panelScConnect?.visibility = View.VISIBLE
        goToStep(1)
    }

    private fun exitWizardToBranchSelect() {
        panelScConnect?.visibility = View.GONE
        panelBranchSelect?.visibility = View.VISIBLE
        loadConnectionsForSelectedBranch()
    }

    // ── Step navigation ──────────────────────────────────────────────────────
    /** Validates the CURRENT step before allowing Next to advance. Called by the Next button;
     *  goToStep() itself (used for Back and direct jumps) does no validation. */
    private fun attemptGoToStep(target: Int) {
        when (connectStep) {
            1 -> {
                if (wizardBranchId.isBlank()) { showScErr("Branch বেছে নিন"); return }
                if (collectScope() == null) return
                if (googleAccount == null) { showScErr("প্রথমে Google account select করুন"); return }
            }
            2 -> if (selectedSheet == null) { showScErr("একটি Sheet বেছে নিন"); return }
            3 -> {
                if (collectTabPattern().isBlank()) {
                    showScErr("Tab name দিন (type অনুযায়ী text/token)"); return
                }
            }
        }
        goToStep(target)
    }

    private fun goToStep(target: Int) {
        connectStep = target.coerceIn(1, 4)
        renderStep()
    }

    private fun showScErr(msg: String) {
        tvScConnectError?.text = msg
        tvScConnectError?.visibility = View.VISIBLE
    }

    private fun renderStep() {
        scStepView1?.visibility = if (connectStep == 1) View.VISIBLE else View.GONE
        scStepView2?.visibility = if (connectStep == 2) View.VISIBLE else View.GONE
        scStepView3?.visibility = if (connectStep == 3) View.VISIBLE else View.GONE
        scStepView4?.visibility = if (connectStep == 4) View.VISIBLE else View.GONE

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
                    dot?.background = roundBg(android.graphics.Color.parseColor("#16A34A"))
                    dot?.text = "✓"
                    dot?.setTextColor(android.graphics.Color.WHITE)
                }
                connectStep == n -> {
                    dot?.background = roundBg(
                        android.graphics.Color.WHITE,
                        android.graphics.Color.parseColor("#16A34A"), 2
                    )
                    dot?.text = "$n"
                    dot?.setTextColor(android.graphics.Color.parseColor("#16A34A"))
                }
                else -> {
                    dot?.background = roundBg(context!!.getColor(R.color.theme_border))
                    dot?.text = "$n"
                    dot?.setTextColor(context!!.getColor(R.color.theme_text_muted))
                }
            }
        }
        styleDot(scStep1Dot, 1); styleDot(scStep2Dot, 2); styleDot(scStep3Dot, 3); styleDot(scStep4Dot, 4)

        val lineColor = android.graphics.Color.parseColor("#16A34A")
        val lineGrey  = context!!.getColor(R.color.theme_border)
        scStep1Line?.setBackgroundColor(if (connectStep > 1) lineColor else lineGrey)
        scStep2Line?.setBackgroundColor(if (connectStep > 2) lineColor else lineGrey)
        scStep3Line?.setBackgroundColor(if (connectStep > 3) lineColor else lineGrey)

        val green = android.graphics.Color.parseColor("#16A34A")
        val dark  = context!!.getColor(R.color.theme_text_primary)
        val grey  = context!!.getColor(R.color.theme_text_muted)
        fun styleLbl(lbl: TextView?, n: Int) {
            lbl?.setTextColor(when { connectStep > n -> green; connectStep == n -> dark; else -> grey })
        }
        styleLbl(scStep1Lbl, 1); styleLbl(scStep2Lbl, 2); styleLbl(scStep3Lbl, 3); styleLbl(scStep4Lbl, 4)

        btnScStepBack?.visibility    = if (connectStep > 1) View.VISIBLE else View.GONE
        btnScStepNext?.visibility    = when {
            connectStep == 1 -> if (googleAccount != null) View.VISIBLE else View.GONE
            connectStep < 4  -> View.VISIBLE
            else             -> View.GONE
        }
        btnScStepConnect?.visibility = if (connectStep == 4) View.VISIBLE else View.GONE
        if (connectStep == 4) {
            updateRangeSummary()
            // Sheet is already picked (step 2) — show its structure right away
            // so headers are visible WHILE defining the range, like the sheets tab.
            previewRules()
        }

        tvScConnectError?.visibility = View.GONE

        when (connectStep) {
            1 -> updateAccountStepUi()
            2 -> updateSheetLabel()
            3 -> updateTabPreview()
        }
    }

    // ── Step 1: Google account ──────────────────────────────────────────────
    private fun pickGoogleAccount() {
        val client = googleSignInClient
        if (client == null) {
            showScErr("Google Sign-In শুরু করা যায়নি" + (initError?.let { ": $it" } ?: ""))
            return
        }
        // Sign out first — otherwise Google Sign-In silently reuses whatever account is
        // already cached (GoogleSignIn.getLastSignedInAccount()) and skips the chooser UI
        // entirely, which is exactly the "no popup appears" symptom. Shared logic lives
        // in GoogleSignInHelper (used identically by ConfigSheetFragment).
        GoogleSignInHelper.pickAccount(
            fragment = this,
            client = client,
            signInLauncher = signInLauncher,
            onLaunchFailed = { msg -> showScErr("Sign-In launch failed: $msg") }
        )
    }

    private fun handleSignInResult(data: Intent?) {
        val account = GoogleSignInHelper.parseSignInResult(data) { msg -> showScErr(msg) } ?: return
        googleAccount = account
        // Remember THIS feature's connected email so onCreate() can tell (next time
        // this fragment is created) whether the device-wide cached account is still
        // ours, or belongs to a switch made from the Sheets tab. See PREFS_FILE_NAME
        // doc comment above for the full reasoning.
        GoogleSignInHelper.rememberConnectedEmail(requireContext(), PREFS_FILE_NAME, account.email)
        // Reset downstream — a newly-picked account's sheet list shouldn't inherit the
        // previous account's selection.
        availableSheets = emptyList()
        selectedSheet = null
        updateAccountStepUi()
        updateSheetLabel()
        loadSheetsForAccount()
    }

    private fun updateAccountStepUi() {
        val acct = googleAccount
        if (acct == null) {
            scCardSelectedAccount?.visibility = View.GONE
            tvScPickAccountLabel?.text = "Sign in with Google"
        } else {
            scCardSelectedAccount?.visibility = View.VISIBLE
            tvScSelectedAccountName?.text = acct.displayName.orEmpty().ifBlank { "Google Account" }
            tvScSelectedAccountEmail?.text = acct.email.orEmpty()
            tvScPickAccountLabel?.text = "Switch account"
        }
        btnScStepNext?.visibility = if (acct != null && connectStep == 1) View.VISIBLE else btnScStepNext?.visibility ?: View.GONE
    }

    /** Fetches a fresh OAuth access token for the current account with the write-capable
     *  scope, handling the UserRecoverableAuthException consent-screen flow the same way
     *  ConfigSheetFragment does. Returns null (and shows the consent screen, or an error) if
     *  a token couldn't be obtained synchronously — caller should retry after the
     *  recoverableLauncher's result comes back (loadSheetsForAccount() re-runs on success). */
    private suspend fun fetchAccessToken(): String? {
        val acct = googleAccount ?: return null
        val token = GoogleSignInHelper.fetchAccessToken(
            context = requireContext(),
            fragment = this,
            account = acct,
            scope = ConfigSheetDriveApi.OAUTH_SCOPE_WRITE,
            recoverableLauncher = recoverableLauncher,
            onError = { msg -> showScErr(msg) }
        )
        // Only overwrite the cache on success — matches the original behavior, where
        // cachedAccessToken was assigned inside the try block only (a failed/recoverable
        // fetch left whatever token was cached from before untouched).
        if (token != null) cachedAccessToken = token
        return token
    }

    // ── Step 2: Sheet picker ────────────────────────────────────────────────
    private fun loadSheetsForAccount() {
        if (googleAccount == null) return
        pbScSheetLoad?.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val token = fetchAccessToken()
            if (!isAdded) return@launch
            pbScSheetLoad?.visibility = View.GONE
            if (token == null) return@launch
            try {
                availableSheets = withContext(Dispatchers.IO) {
                    ConfigSheetDriveApi.fetchDriveSpreadsheets(token, httpClient)
                }
            } catch (e: Exception) {
                if (isAdded) showScErr("Sheet list load failed: ${e.message}")
            }
        }
    }

    private fun showSheetPicker() {
        if (googleAccount == null) { showScErr("প্রথমে Google account select করুন"); return }
        val ctx = context ?: return
        if (availableSheets.isEmpty()) {
            Toast.makeText(ctx, "Sheet লোড হচ্ছে, একটু অপেক্ষা করুন", Toast.LENGTH_SHORT).show()
            loadSheetsForAccount()
            return
        }
        SheetPickerDialog.show(ctx, availableSheets, selectedSheet) { sheet ->
            if (selectedSheet?.id != sheet.id) {
                selectedSheet = sheet
                updateSheetLabel()
            }
        }
    }

    private fun updateSheetLabel() {
        tvScSelectedSheet?.text = selectedSheet?.name ?: "— Sheet বেছে নিন —"
        tvScSelectedSheet?.setTextColor(
            context?.getColor(
                if (selectedSheet != null) R.color.theme_text_primary else R.color.theme_text_secondary
            ) ?: android.graphics.Color.GRAY
        )
    }

    // ── Step 3: Tab pattern preview ─────────────────────────────────────────
    // ── Step 3: dropdown-based tab builder ─────────────────────────────────
    // Type dropdown controls the input: Plain = fixed text (locked as-is),
    // Dynamic = date token with live today-example (locked to that token),
    // Hybrid = fixed text + token append. Preview always shows today's
    // resolved name. Storage stays a plain pattern string (resolveTabName).

    private val TAB_TOKENS = listOf("{dd}", "{d}", "{mm}", "{m}", "{yyyy}", "{yy}")
    private val TAB_MODES = listOf("Plain text (fixed)", "Dynamic date", "Hybrid (fixed + dynamic)")

    private fun tabTokenExample(token: String): String {
        val resolved = runCatching {
            ScannerSheetRepository.resolveTabName(token)
        }.getOrDefault(token)
        return "$token → $resolved"
    }

    private fun tabMode(): Int = spinnerScTabMode?.selectedItemPosition ?: 0

    private fun setupTabBuilder() {
        val ctx = context ?: return
        spinnerScTabMode?.adapter = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_item, TAB_MODES
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val tokenLabels = TAB_TOKENS.map { tabTokenExample(it) }
        val tokenAdapter = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_item, tokenLabels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerScTabToken?.adapter = tokenAdapter
        spinnerScTabHybridToken?.adapter = tokenAdapter
        spinnerScTabMode?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                updateTabModeRows()
                updateTabPreview()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = updateTabPreview()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        etScTabFixed?.addTextChangedListener(watcher)
        etScTabHybrid?.addTextChangedListener(watcher)
        val tokenListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = updateTabPreview()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spinnerScTabToken?.onItemSelectedListener = tokenListener
        spinnerScTabHybridToken?.onItemSelectedListener = tokenListener
        view?.findViewById<View>(R.id.btnScTabHybridAdd)?.setOnClickListener {
            val token = TAB_TOKENS.getOrNull(spinnerScTabHybridToken?.selectedItemPosition ?: 0).orEmpty()
            val cur = etScTabHybrid?.text?.toString().orEmpty()
            val sep = if (cur.isBlank() || cur.endsWith(" ")) "" else " "
            etScTabHybrid?.setText("$cur$sep$token")
            etScTabHybrid?.setSelection(etScTabHybrid?.text?.length ?: 0)
            updateTabPreview()
        }
        updateTabModeRows()
    }

    private fun updateTabModeRows() {
        val mode = tabMode()
        layoutScTabPlain?.visibility = if (mode == 0) View.VISIBLE else View.GONE
        layoutScTabDynamic?.visibility = if (mode == 1) View.VISIBLE else View.GONE
        layoutScTabHybrid?.visibility = if (mode == 2) View.VISIBLE else View.GONE
    }

    /** Effective pattern from the builder (locked value that gets saved). */
    private fun collectTabPattern(): String {
        return when (tabMode()) {
            1 -> TAB_TOKENS.getOrNull(spinnerScTabToken?.selectedItemPosition ?: 0).orEmpty()
            2 -> etScTabHybrid?.text?.toString()?.trim().orEmpty()
            else -> etScTabFixed?.text?.toString()?.trim().orEmpty()
        }
    }

    /** Seeds the builder from a stored pattern (edit flow). */
    private fun seedTabBuilder(pattern: String) {
        val p = pattern.ifBlank { "Day {dd}" }
        val hasToken = TAB_TOKENS.any { p.contains(it) }
        // Literal left after stripping every token.
        var literal = p
        TAB_TOKENS.forEach { literal = literal.replace(it, "") }
        when {
            !hasToken -> {
                spinnerScTabMode?.setSelection(0)
                etScTabFixed?.setText(p)
            }
            literal.isBlank() && TAB_TOKENS.contains(p.trim()) -> {
                spinnerScTabMode?.setSelection(1)
                spinnerScTabToken?.setSelection(TAB_TOKENS.indexOf(p.trim()))
            }
            else -> {
                spinnerScTabMode?.setSelection(2)
                etScTabHybrid?.setText(p)
            }
        }
        updateTabModeRows()
        updateTabPreview()
    }

    private fun updateTabPreview() {
        val pattern = collectTabPattern().ifBlank { "Day {dd}" }
        val resolved = ScannerSheetRepository.resolveTabName(pattern)
        tvScTabPreview?.text = "আজকের Tab নাম হবে:  \"$resolved\""
    }

    // ── Step 1: scope date pickers (calendar view, yyyy-MM-dd) ──────────────
    private fun setupScopeDatePickers() {
        etScScopeFrom?.setOnClickListener { pickScopeDate(etScScopeFrom) }
        etScScopeTo?.setOnClickListener { pickScopeDate(etScScopeTo) }
    }

    private fun pickScopeDate(target: EditText?) {
        val ctx = context ?: return
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Dhaka"))
        // Preselect the field's current value when parseable.
        runCatching {
            val parts = target?.text?.toString()?.trim()?.split("-") ?: return@runCatching
            if (parts.size == 3) {
                cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
            }
        }
        android.app.DatePickerDialog(
            ctx,
            { _, y, m, d -> target?.setText("%04d-%02d-%02d".format(y, m + 1, d)) },
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        ).show()
    }

    // ── Step 4: range summary is live (updateRangeSummary, above) ─────────

    // ── Save ─────────────────────────────────────────────────────────────────
    // Branch comes from the Step-1 wizard dropdown (NOT the outer list
    // filter) — branch-wise multiple sheets allowed. Every save is a neutral
    // library (no purpose, no column rules — range only).
    private fun saveConnection() {
        val sheet = selectedSheet
        val acct  = googleAccount
        if (sheet == null || acct == null) { showScErr("Account এবং Sheet select করা আবশ্যক"); return }
        val saveBranchId = wizardBranchId.ifBlank { selectedBranchId }
        if (saveBranchId.isBlank()) { showScErr("Branch বেছে নিন (Step 1)"); return }
        val scope = collectScope() ?: return
        val scopeType = scope.type
        val scopeMonth = scope.month
        val scopeFrom = scope.from
        val scopeTo = scope.to

        val nickname = etScNickname?.text?.toString()?.trim().orEmpty()
        val tabPattern = collectTabPattern().ifBlank { "Day {dd}" }
        val headerRow = collectHeaderRow()
        val range = collectRange(loud = true) ?: return

        btnScStepConnect?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                val actingName = withContext(Dispatchers.IO) {
                    try {
                        com.google.firebase.database.FirebaseDatabase.getInstance()
                            .reference.child("users/$uid/profile/name")
                            .get().await().getValue(String::class.java).orEmpty()
                    } catch (_: Exception) { "" }
                }
                val isNew = editingConnectionId.isBlank()
                val conn = ScannerSheetConn(
                    connectionId = editingConnectionId,
                    nickname     = nickname,
                    branchId     = saveBranchId,
                    sheetId      = sheet.id,
                    sheetName    = sheet.name,
                    tabPattern   = tabPattern,
                    headerRow    = headerRow,
                    colStart     = range.colStart,
                    colEnd       = range.colEnd,
                    dataStartRow = range.dataStart,
                    purpose      = "",
                    isLibrary    = true,
                    scopeType    = scopeType,
                    scopeMonth   = scopeMonth,
                    scopeFrom    = scopeFrom,
                    scopeTo      = scopeTo,
                    lookups      = emptyList(),
                    writes       = emptyList(),
                    googleEmail  = acct.email.orEmpty(),
                )
                ScannerSheetRepository.saveConnection(conn, uid, actingName, isNew)
                if (!isAdded) return@launch
                Toast.makeText(context, "📚 Library saved", Toast.LENGTH_SHORT).show()
                // Saved branch may differ from the outer list filter — point the
                // list at the saved branch so the new connection is visible.
                if (saveBranchId != selectedBranchId) {
                    selectedBranchId = saveBranchId
                    val pos = myBranches.indexOfFirst { it.first == selectedBranchId }
                    if (pos >= 0) spinnerScBranch?.setSelection(pos)
                }
                exitWizardToBranchSelect()
            } catch (e: Exception) {
                if (isAdded) showScErr("Save failed: ${e.message}")
            } finally {
                btnScStepConnect?.isEnabled = true
            }
        }
    }

    // ── Range preview (like the config/sheets tab): fetches the range and
    //  renders header row + 5 data rows in a table. Read-only. ──────────
    private fun previewRules() {
        val sheet = selectedSheet
        val acct = googleAccount
        if (sheet == null || acct == null) { showScErr("Account এবং Sheet select করা আবশ্যক"); return }
        val range = collectRange(loud = true) ?: return
        val tab = ScannerSheetRepository.resolveTabName(
            collectTabPattern().ifBlank { "Day {dd}" })
        tvScRulePreview?.visibility = View.GONE
        scrollScRulePreview?.visibility = View.GONE
        tableScRulePreview?.removeAllViews()
        tvScRulePreview?.visibility = View.VISIBLE
        tvScRulePreview?.text = "⏳ Preview আনছে..."
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = fetchAccessToken() ?: run {
                    if (isAdded) tvScRulePreview?.text = "⚠ Token পাওয়া যায়নি"
                    return@launch
                }
                // Header row + 5 data rows after it, clipped to the range.
                val headerRow = range.headerRow
                val startLetter = ConfigSheetParseUtil.colIndexToLetter(range.colStart)
                val endLetter = ConfigSheetParseUtil.colIndexToLetter(range.colEnd)
                val endRow = headerRow + 5
                val rangeStr = "$tab!$startLetter$headerRow:$endLetter$endRow"
                val rows = withContext(Dispatchers.IO) {
                    val url = "https://sheets.googleapis.com/v4/spreadsheets/${sheet.id}/values/" +
                        java.net.URLEncoder.encode(rangeStr, "UTF-8")
                    val req = okhttp3.Request.Builder().url(url)
                        .header("Authorization", "Bearer $token").build()
                    okhttp3.OkHttpClient().newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("Sheets API ${resp.code}")
                        val arr = org.json.JSONObject(resp.body?.string().orEmpty())
                            .optJSONArray("values") ?: return@withContext emptyList<List<String>>()
                        (0 until arr.length()).map { i ->
                            val r = arr.optJSONArray(i) ?: org.json.JSONArray()
                            (0 until r.length()).map { j -> r.optString(j, "") }
                        }
                    }
                }
                if (!isAdded) return@launch
                if (rows.isEmpty()) {
                    tvScRulePreview?.text = "⚠ Tab '$tab'-এ row $headerRow থেকে data নেই"
                    return@launch
                }
                tvScRulePreview?.text =
                    "Tab '$tab' • header row $headerRow • $startLetter–$endLetter • " +
                        "data row ${range.dataStart} থেকে"
                // Table: letter header + fetched rows (first = header row tint).
                renderPreviewTable(rows, range.colStart, range.colEnd)
                scrollScRulePreview?.visibility = View.VISIBLE
            } catch (e: Exception) {
                if (isAdded) tvScRulePreview?.text = "⚠ Preview error: ${e.message?.take(80)}"
            }
        }
    }

    private fun renderPreviewTable(
        rows: List<List<String>>, startCol: Int, endCol: Int,
    ) {
        val table = tableScRulePreview ?: return
        val ctx = context ?: return
        table.removeAllViews()
        fun cell(text: String, bg: String, bold: Boolean): TextView = TextView(ctx).apply {
            this.text = text
            textSize = 11f
            setPadding(18, 10, 18, 10)
            setBackgroundColor(android.graphics.Color.parseColor(bg))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#111827"))
        }
        val s = startCol.coerceIn(1, 200)
        val e = endCol.coerceIn(s, (s + 51).coerceAtMost(200))
        val letters = (s..e).map { ConfigSheetParseUtil.colIndexToLetter(it) }
        val headRow = android.widget.TableRow(ctx)
        letters.forEach { headRow.addView(cell(it, "#F3F4F6", true)) }
        table.addView(headRow)
        rows.take(6).forEachIndexed { ri, row ->
            val tr = android.widget.TableRow(ctx)
            letters.forEachIndexed { ci, letter ->
                val bg = when {
                    ri == 0 -> "#FFF7ED"
                    else -> if (ri % 2 == 0) "#FFFFFF" else "#F9FAFB"
                }
                tr.addView(cell(row.getOrElse(ci) { "" }, bg, false))
            }
            table.addView(tr)
        }
    }
}
