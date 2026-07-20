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
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient

/**
 * 🔗 Connectors — Config tab (Scanner Sheet Connector)
 *
 * A simpler, standalone sibling to ConfigSheetFragment's Sheet connector: same 4-step
 * connect wizard (Account → Sheet → Tab → Columns), exactly copied per explicit instruction,
 * with the 5th "Mapping" step intentionally omitted — this connector has no field mapping,
 * no periodic sync. It's just enough config for ScannerFragment to know which sheet, which
 * tab-name pattern, and which two columns (match/write) to use when writing a scanned value.
 *
 * Branch-wise, multiple connections per branch allowed (same UX pattern as ConfigSheetFragment's
 * connected-branches list) — see ScannerSheetModels.kt / ScannerSheetRepository.kt for the data
 * model and Firebase persistence (config/connectors/{branchId}/{connectionId}).
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

    // Step 1
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
    private var etScTabPattern: EditText? = null
    private var tvScTabPreview: TextView? = null

    // Step 4
    private var etScMatchColumn: EditText? = null
    private var etScWriteColumn: EditText? = null
    private var tvScSummary:     TextView? = null

    private var tvScConnectError: TextView? = null
    private var btnScStepBack:    Button? = null
    private var btnScStepNext:    Button? = null
    private var btnScStepConnect: Button? = null

    // ── State ────────────────────────────────────────────────────────────────
    private var myBranches: List<Pair<String, String>> = emptyList() // (branchId, branchName)
    private var selectedBranchId: String = ""
    private var branchConnections: List<ScannerSheetConn> = emptyList()

    private var connectStep = 1
    private var editingConnectionId: String = "" // blank = new connection

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
            // (Connectors) was last connected with — see PREFS_KEY_EMAIL doc comment below
            // for why we can't just trust GoogleSignIn.getLastSignedInAccount() alone.
            val last = GoogleSignIn.getLastSignedInAccount(requireContext())
            val savedEmail = accountPrefs().getString(PREFS_KEY_EMAIL, null)
            if (last != null &&
                savedEmail != null &&
                last.email.equals(savedEmail, ignoreCase = true) &&
                GoogleSignIn.hasPermissions(
                    last,
                    Scope(ConfigSheetDriveApi.SCOPE_DRIVE_FILE),
                    Scope(ConfigSheetDriveApi.SCOPE_SHEETS_WRITE)
                )
            ) {
                googleAccount = last
            }
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
     * Fix: each feature remembers, in its OWN SharedPreferences key, the email of the
     * account IT last connected with. On create, we only trust the device-wide cached
     * account if its email matches our own saved one. A "Switch account" in the other
     * tab still has to call signOut() to force Google's account chooser to appear (no
     * way around that with this API) — but that no longer matters to us: next time this
     * fragment is created, the cache might be empty or hold a different account, but
     * since it won't match our saved email either way, we correctly show "not connected"
     * only when the user actually switched THIS feature's account, not someone else's.
     */
    private fun accountPrefs() =
        requireContext().getSharedPreferences("connectors_google_account", android.content.Context.MODE_PRIVATE)

    private val PREFS_KEY_EMAIL = "connected_email"

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
        etScTabPattern = view.findViewById(R.id.etScTabPattern)
        tvScTabPreview = view.findViewById(R.id.tvScTabPreview)

        etScMatchColumn = view.findViewById(R.id.etScMatchColumn)
        etScWriteColumn = view.findViewById(R.id.etScWriteColumn)
        tvScSummary     = view.findViewById(R.id.tvScSummary)

        tvScConnectError = view.findViewById(R.id.tvScConnectError)
        btnScStepBack    = view.findViewById(R.id.btnScStepBack)
        btnScStepNext    = view.findViewById(R.id.btnScStepNext)
        btnScStepConnect = view.findViewById(R.id.btnScStepConnect)

        btnScAddConnection?.setOnClickListener { startNewConnection() }
        btnScCancelConnect?.setOnClickListener { exitWizardToBranchSelect() }
        btnScPickAccount?.setOnClickListener { pickGoogleAccount() }
        tvScSelectedSheet?.setOnClickListener { showSheetPicker() }
        btnScStepBack?.setOnClickListener { goToStep(connectStep - 1) }
        btnScStepNext?.setOnClickListener { attemptGoToStep(connectStep + 1) }
        btnScStepConnect?.setOnClickListener { saveConnection() }

        etScTabPattern?.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { updateTabPreview() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

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
            val db = com.google.firebase.database.FirebaseDatabase.getInstance()
            val resolved = ids.map { id ->
                async(Dispatchers.IO) {
                    val name = try {
                        db.reference.child("branches/$id/name").get().await()
                            .getValue(String::class.java).orEmpty().ifBlank { id }
                    } catch (_: Exception) { id }
                    id to name
                }
            }.map { it.await() }
            if (!isAdded) return@launch
            myBranches = resolved
            val adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_item, resolved.map { it.second }
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerScBranch?.adapter = adapter
            if (resolved.isNotEmpty()) {
                selectedBranchId = resolved.first().first
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

        branchConnections.forEach { conn ->
            val row = LayoutInflater.from(ctx).inflate(
                android.R.layout.simple_list_item_2, container, false
            ) as LinearLayout
            val title = row.findViewById<TextView>(android.R.id.text1)
            val sub   = row.findViewById<TextView>(android.R.id.text2)
            title.text = conn.nickname.ifBlank { conn.sheetName.ifBlank { "(নাম নেই)" } }
            title.textSize = 14f
            title.setTextColor(ctx.getColor(R.color.theme_text_primary))
            sub.text = "${conn.sheetName}  •  Match: ${conn.matchColumn}  Write: ${conn.writeColumn}"
            sub.textSize = 11f
            sub.setTextColor(ctx.getColor(R.color.theme_text_secondary))
            row.setPadding(14, 12, 14, 12)
            row.setBackgroundResource(R.drawable.bg_card_rounded)
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20 }
            row.setOnClickListener { startEditConnection(conn) }
            row.setOnLongClickListener { confirmDeleteConnection(conn); true }
            container.addView(row)
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

    // ── Wizard entry/exit ────────────────────────────────────────────────────
    private fun startNewConnection() {
        editingConnectionId = ""
        selectedSheet = null
        etScNickname?.setText("")
        etScTabPattern?.setText("Day {dd}")
        etScMatchColumn?.setText("")
        etScWriteColumn?.setText("")
        tvScSelectedSheet?.text = "— Sheet বেছে নিন —"
        enterWizard()
    }

    private fun startEditConnection(conn: ScannerSheetConn) {
        editingConnectionId = conn.connectionId
        selectedSheet = DriveFile(conn.sheetId, conn.sheetName)
        etScNickname?.setText(conn.nickname)
        etScTabPattern?.setText(conn.tabPattern.ifBlank { "Day {dd}" })
        etScMatchColumn?.setText(conn.matchColumn)
        etScWriteColumn?.setText(conn.writeColumn)
        tvScSelectedSheet?.text = conn.sheetName.ifBlank { "— Sheet বেছে নিন —" }
        enterWizard()
    }

    private fun enterWizard() {
        val branchName = myBranches.firstOrNull { it.first == selectedBranchId }?.second ?: selectedBranchId
        tvScConnBranchSub?.text = branchName
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
            1 -> if (googleAccount == null) { showScErr("প্রথমে Google account select করুন"); return }
            2 -> if (selectedSheet == null) { showScErr("একটি Sheet বেছে নিন"); return }
            3 -> {
                if (etScTabPattern?.text?.toString()?.trim().isNullOrBlank()) {
                    showScErr("Tab name pattern দিন"); return
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
        if (connectStep == 4) { updateColumnSummary() }

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
        // entirely, which is exactly the "no popup appears" symptom. Matches
        // ConfigSheetFragment's pickGoogleAccount() pattern.
        client.signOut().addOnCompleteListener {
            try {
                if (isAdded) signInLauncher.launch(client.signInIntent)
            } catch (e: Exception) {
                if (isAdded) showScErr("Sign-In launch failed: ${e.message}")
            }
        }
    }

    private fun handleSignInResult(data: Intent?) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            googleAccount = account
            // Remember THIS feature's connected email so onCreate() can tell (next time
            // this fragment is created) whether the device-wide cached account is still
            // ours, or belongs to a switch made from the Sheets tab. See accountPrefs()
            // doc comment above for the full reasoning.
            account.email?.let { email ->
                accountPrefs().edit().putString(PREFS_KEY_EMAIL, email).apply()
            }
            // Reset downstream — a newly-picked account's sheet list shouldn't inherit the
            // previous account's selection.
            availableSheets = emptyList()
            selectedSheet = null
            updateAccountStepUi()
            updateSheetLabel()
            loadSheetsForAccount()
        } catch (e: ApiException) {
            showScErr("Sign-in failed (code ${e.statusCode})")
        } catch (e: Exception) {
            showScErr("Sign-in error: ${e.message}")
        }
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
        return withContext(Dispatchers.IO) {
            try {
                val token = GoogleAuthUtil.getToken(
                    requireContext(), acct.account!!, ConfigSheetDriveApi.OAUTH_SCOPE_WRITE
                )
                cachedAccessToken = token
                token
            } catch (e: UserRecoverableAuthException) {
                withContext(Dispatchers.Main) {
                    if (isAdded) recoverableLauncher.launch(e.intent)
                }
                null
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded) showScErr("Token fetch failed: ${e.message}")
                }
                null
            }
        }
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
    private fun updateTabPreview() {
        val pattern = etScTabPattern?.text?.toString()?.trim().orEmpty().ifBlank { "Day {dd}" }
        val resolved = resolveTabPattern(pattern)
        tvScTabPreview?.text = "আজকের Tab নাম হবে:  \"$resolved\""
    }

    /** {dd} -> current day-of-month, e.g. "16" (no leading zero — matches the "Day 16" example
     *  the user gave, not "Day 06" zero-padded). If a genuinely different pattern is entered
     *  with no {dd} token, it's used literally (future-proofing per tabPattern's doc comment). */
    private fun resolveTabPattern(pattern: String): String {
        val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
        return pattern.replace("{dd}", day.toString())
    }

    // ── Step 4: Columns ──────────────────────────────────────────────────────
    private fun updateColumnSummary() {
        val match = etScMatchColumn?.text?.toString()?.trim().orEmpty()
        val write = etScWriteColumn?.text?.toString()?.trim().orEmpty()
        if (match.isBlank() || write.isBlank()) {
            tvScSummary?.text = "Column দুটো পূরণ করলে এখানে summary দেখা যাবে।"
            return
        }
        val matchIdx = ConfigSheetParseUtil.parseColInput(match)
        val writeIdx = ConfigSheetParseUtil.parseColInput(write)
        if (matchIdx == null || writeIdx == null) {
            tvScSummary?.text = "⚠ Column ঠিক আছে কিনা check করুন (যেমন: T অথবা 20)"
            return
        }
        val matchNorm = ConfigSheetParseUtil.colIndexToLetter(matchIdx)
        val writeNorm = ConfigSheetParseUtil.colIndexToLetter(writeIdx)
        tvScSummary?.text =
            "✅ Scan হলে Column $matchNorm-এ agent-এর Employee ID খোঁজা হবে, " +
            "এবং সেই row-এর Column $writeNorm-এ (যদি খালি থাকে) scan করা data বসবে। " +
            "সব row ভরা থাকলে নতুন row যোগ হবে।"
    }

    // ── Save ─────────────────────────────────────────────────────────────────
    private fun saveConnection() {
        val sheet = selectedSheet
        val acct  = googleAccount
        if (sheet == null || acct == null) { showScErr("Account এবং Sheet select করা আবশ্যক"); return }

        val nickname = etScNickname?.text?.toString()?.trim().orEmpty()
        val tabPattern = etScTabPattern?.text?.toString()?.trim().orEmpty().ifBlank { "Day {dd}" }
        val matchRaw = etScMatchColumn?.text?.toString()?.trim().orEmpty()
        val writeRaw = etScWriteColumn?.text?.toString()?.trim().orEmpty()

        val matchIdx = ConfigSheetParseUtil.parseColInput(matchRaw)
        val writeIdx = ConfigSheetParseUtil.parseColInput(writeRaw)
        if (matchIdx == null) { showScErr("Match column ঠিক নেই (যেমন: T অথবা 20)"); return }
        if (writeIdx == null) { showScErr("Write column ঠিক নেই (যেমন: K অথবা 11)"); return }
        val matchCol = ConfigSheetParseUtil.colIndexToLetter(matchIdx)
        val writeCol = ConfigSheetParseUtil.colIndexToLetter(writeIdx)
        if (matchCol == writeCol) { showScErr("Match এবং Write column একই হতে পারবে না"); return }

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
                    branchId     = selectedBranchId,
                    sheetId      = sheet.id,
                    sheetName    = sheet.name,
                    tabPattern   = tabPattern,
                    matchColumn  = matchCol,
                    writeColumn  = writeCol,
                    googleEmail  = acct.email.orEmpty(),
                )
                ScannerSheetRepository.saveConnection(conn, uid, actingName, isNew)
                if (!isAdded) return@launch
                Toast.makeText(context, "✅ Sheet connected", Toast.LENGTH_SHORT).show()
                exitWizardToBranchSelect()
            } catch (e: Exception) {
                if (isAdded) showScErr("Save failed: ${e.message}")
            } finally {
                btnScStepConnect?.isEnabled = true
            }
        }
    }
}
