package com.cloudx.databridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
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
import java.util.concurrent.TimeUnit

/**
 * Connectors tab — branch-wise list of configured connectors, matching
 * ConfigSheetFragment's exact pattern: tapping a branch card (scoped to
 * RbacManager.current.branchIds — this user's assigned branches only,
 * not every branch in the system) opens the connector wizard already
 * scoped to that branch. There is no separate "pick a branch" step
 * inside the wizard, and no generic "+ Add Connector" button.
 *
 * Wizard (currently: Sheet-type connectors only — reuses the exact
 * Google Sign-In + Drive/Sheets API plumbing already proven in
 * ConfigSheetFragment/ConfigSheetDriveApi, rather than a second
 * parallel implementation):
 *   Step 1 — Google Sign-In (Drive+Sheets readonly scopes)
 *   Step 2 — Pick spreadsheet, then pick a tab within it
 *   Step 3 — Confirm & save to config/connectors/{branchId}/{connectorId}
 *
 * Column mapping / primary key / sync scheduling are intentionally NOT
 * part of this wizard — those belong to the (not yet built) per-connector
 * management screen. This wizard only creates the basic branch+sheet+tab
 * link, same scope as the original stub's design intent.
 */
class ConfigConnectorsFragment : Fragment() {

    private lateinit var layoutBranchList:   LinearLayout
    private lateinit var layoutWizard:       LinearLayout
    private lateinit var tvWizardTitle:      TextView
    private lateinit var tvWizardBranch:     TextView
    private lateinit var layoutStep1:        LinearLayout
    private lateinit var layoutStep2:        LinearLayout
    private lateinit var layoutStep3:        LinearLayout
    private lateinit var btnBack:            Button
    private lateinit var btnNext:            Button
    private lateinit var btnCancel:          Button
    private lateinit var tvConnectorsEmpty:  TextView
    private lateinit var btnGoogleSignIn:    Button
    private lateinit var tvSignedInAs:       TextView
    private lateinit var spinnerTarget:      Spinner
    private lateinit var pbTargetLoad:       ProgressBar
    private lateinit var spinnerTab:         Spinner
    private lateinit var tvSummary:          TextView

    private val db   by lazy { FirebaseDatabase.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private var googleSignInClient: GoogleSignInClient? = null
    private var googleAccount: GoogleSignInAccount? = null

    private var currentStep = 1
    private val TOTAL_STEPS = 3

    // RBAC-scoped branches for this user, cached id -> name (same convention as
    // CallCenterFragment's branchIdToName).
    private val branchIdToName = mutableMapOf<String, String>()

    // Branch is picked by tapping its card in the list — not re-selected inside
    // the wizard (matches ConfigSheetFragment's activeBranch pattern).
    private var selectedBranchId   = ""
    private var selectedBranchName = ""

    private var availableSheets: List<DriveFile> = emptyList()
    private var selectedSheet: DriveFile? = null
    private var availableTabs: List<String> = emptyList()
    private var selectedTab: String = ""

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: androidx.activity.result.ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            handleSignInResult(result.data)
        } // cancelled — silently ignore, matching ConfigSheetFragment
    }

    // Recovery launcher (in case getToken throws UserRecoverableAuthException)
    private val recoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) loadSheetsForAccount()
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

            GoogleSignIn.getLastSignedInAccount(requireContext())?.let { acc ->
                if (GoogleSignIn.hasPermissions(acc, Scope(ConfigSheetDriveApi.SCOPE_DRIVE), Scope(ConfigSheetDriveApi.SCOPE_SHEETS))) {
                    googleAccount = acc
                }
            }
        } catch (_: Exception) { /* defensive: never crash on init */ }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_config_connectors, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── Bind every view first — all of it, before any listener wiring below,
        //    to avoid a lateinit-accessed-before-assignment crash. ──
        layoutBranchList  = view.findViewById(R.id.layoutConnectorBranchList)
        layoutWizard      = view.findViewById(R.id.layoutConnectorWizard)
        tvWizardTitle     = view.findViewById(R.id.tvConnectorWizardTitle)
        tvWizardBranch    = view.findViewById(R.id.tvConnectorWizardBranch)
        layoutStep1       = view.findViewById(R.id.layoutConnectorStep1)
        layoutStep2       = view.findViewById(R.id.layoutConnectorStep2)
        layoutStep3       = view.findViewById(R.id.layoutConnectorStep3)
        btnBack           = view.findViewById(R.id.btnConnectorBack)
        btnNext           = view.findViewById(R.id.btnConnectorNext)
        btnCancel         = view.findViewById(R.id.btnConnectorCancel)
        tvConnectorsEmpty = view.findViewById(R.id.tvConnectorsEmpty)
        btnGoogleSignIn   = view.findViewById(R.id.btnConnectorGoogleSignIn)
        tvSignedInAs      = view.findViewById(R.id.tvConnectorSignedInAs)
        spinnerTarget     = view.findViewById(R.id.spinnerConnectorTarget)
        pbTargetLoad      = view.findViewById(R.id.pbConnectorTargetLoad)
        spinnerTab        = view.findViewById(R.id.spinnerConnectorTab)
        tvSummary         = view.findViewById(R.id.tvConnectorSummary)

        btnBack  .setOnClickListener { navigateWizard(-1) }
        btnNext  .setOnClickListener { navigateWizard(+1) }
        btnCancel.setOnClickListener { closeWizard() }
        btnGoogleSignIn.setOnClickListener { pickGoogleAccount() }

        spinnerTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val sheet = availableSheets.getOrNull(position) ?: return
                if (sheet.id == selectedSheet?.id) return
                selectedSheet = sheet
                selectedTab   = ""
                availableTabs = emptyList()
                val ctx = context ?: return
                spinnerTab.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, listOf("Loading tabs…"))
                loadTabsForSheet(sheet.id)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerTab.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                selectedTab = availableTabs.getOrNull(position) ?: ""
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadBranchConnectors()
    }

    // ── Branch-connector list (scoped to THIS user's RBAC branches) ────────
    // Tapping a card opens the wizard for that branch — same entry point as
    // ConfigSheetFragment (no separate branch-selection step inside the wizard).

    private fun loadBranchConnectors() {
        layoutBranchList.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val branchIds = RbacManager.current.branchIds
                tvConnectorsEmpty.isVisible = branchIds.isEmpty()

                branchIds.forEach { branchId ->
                    val branchName = resolveBranchName(branchId)

                    val connectorSnap = withContext(Dispatchers.IO) {
                        db.reference.child("config/connectors/$branchId").get().await()
                    }
                    val connectorCount = connectorSnap.childrenCount.toInt()

                    val card = layoutInflater.inflate(
                        R.layout.item_connector_branch_card,
                        layoutBranchList, false
                    )
                    card.findViewById<TextView>(R.id.tvConnectorBranchName).text = branchName
                    card.findViewById<TextView>(R.id.tvConnectorCount).text =
                        if (connectorCount > 0) "$connectorCount connector${if (connectorCount > 1) "s" else ""}"
                        else "No connectors"

                    // Whole card opens the wizard, scoped to this branch — the
                    // "Manage" button is reserved for an existing-connector
                    // management screen (still a stub, separate future task).
                    card.findViewById<View>(R.id.rootConnectorBranchCard)
                        .setOnClickListener { openWizard(branchId, branchName) }
                    card.findViewById<Button>(R.id.btnManageConnector)
                        .setOnClickListener {
                            toast("Manage: $branchName — coming soon")
                        }

                    layoutBranchList.addView(card)
                }
            } catch (e: Exception) {
                toast("Load failed: ${e.message}")
            }
        }
    }

    private suspend fun resolveBranchName(branchId: String): String {
        branchIdToName[branchId]?.let { return it }
        val name = withContext(Dispatchers.IO) {
            runCatching { db.reference.child("branches/$branchId/name").get().await().getValue(String::class.java) }
                .getOrNull()
        }?.takeIf { it.isNotBlank() } ?: branchId
        branchIdToName[branchId] = name
        return name
    }

    // ── Wizard navigation ────────────────────────────────────────────────

    private fun openWizard(branchId: String, branchName: String) {
        selectedBranchId   = branchId
        selectedBranchName = branchName
        currentStep = 1
        selectedSheet = null
        selectedTab   = ""
        availableSheets = emptyList()
        availableTabs   = emptyList()

        tvWizardBranch.text = "Branch: $branchName"
        layoutBranchList.isVisible = false
        layoutWizard    .isVisible = true
        updateSignInStep()
        renderStep()
    }

    private fun closeWizard() {
        layoutWizard    .isVisible = false
        layoutBranchList.isVisible = true
        loadBranchConnectors()
    }

    private fun navigateWizard(direction: Int) {
        if (direction > 0 && !validateCurrentStep()) return
        if (direction > 0 && currentStep == TOTAL_STEPS) {
            saveConnector()
            return
        }
        currentStep = (currentStep + direction).coerceIn(1, TOTAL_STEPS)
        if (currentStep == 3) updateSummary()
        renderStep()
    }

    private fun renderStep() {
        tvWizardTitle.text = when (currentStep) {
            1 -> "Step 1 of $TOTAL_STEPS — Authenticate"
            2 -> "Step 2 of $TOTAL_STEPS — Configure"
            3 -> "Step 3 of $TOTAL_STEPS — Confirm"
            else -> ""
        }
        layoutStep1.isVisible = currentStep == 1
        layoutStep2.isVisible = currentStep == 2
        layoutStep3.isVisible = currentStep == 3
        btnBack.isVisible = currentStep > 1
        btnNext.text = if (currentStep == TOTAL_STEPS) "Save" else "Next →"
    }

    private fun validateCurrentStep(): Boolean = when (currentStep) {
        1 -> if (googleAccount == null) { toast("Google দিয়ে sign in করুন"); false } else true
        2 -> when {
            selectedSheet == null -> { toast("একটা sheet বেছে নিন"); false }
            selectedTab.isBlank() -> { toast("একটা tab বেছে নিন"); false }
            else -> true
        }
        else -> true
    }

    // ── Step 1: Google Sign-In (same scopes/flow as ConfigSheetFragment) ──

    private fun updateSignInStep() {
        val acc = googleAccount
        if (acc == null) {
            btnGoogleSignIn.text  = "Sign in with Google"
            tvSignedInAs.isVisible = false
        } else {
            btnGoogleSignIn.text  = "Switch account"
            tvSignedInAs.isVisible = true
            tvSignedInAs.text = "Signed in as ${acc.displayName ?: acc.email ?: "Google User"} (${acc.email ?: ""})"
            if (availableSheets.isEmpty()) loadSheetsForAccount()
        }
    }

    private fun pickGoogleAccount() {
        val client = googleSignInClient ?: run { toast("Google Sign-In initialize হয়নি"); return }
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
            availableSheets = emptyList(); selectedSheet = null
            availableTabs   = emptyList(); selectedTab   = ""
            updateSignInStep()
        } catch (e: ApiException) {
            toast("Sign-in failed (code ${e.statusCode})")
        } catch (e: Exception) {
            toast("Sign-in error: ${e.message}")
        }
    }

    // ── Step 2: pick spreadsheet, then tab (ConfigSheetDriveApi) ──

    private fun loadSheetsForAccount() {
        val account = googleAccount ?: return
        val acctObj = account.account ?: run { toast("Account info নেই"); return }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                pbTargetLoad.isVisible = true
                val ctx = context ?: return@launch
                val token = withContext(Dispatchers.IO) {
                    try {
                        GoogleAuthUtil.getToken(ctx, acctObj, ConfigSheetDriveApi.OAUTH_SCOPE)
                    } catch (e: UserRecoverableAuthException) {
                        withContext(Dispatchers.Main) {
                            try { recoverableLauncher.launch(e.intent) } catch (_: Exception) {}
                        }
                        null
                    }
                } ?: return@launch
                val sheets = withContext(Dispatchers.IO) { ConfigSheetDriveApi.fetchDriveSpreadsheets(token, httpClient) }
                availableSheets = sheets
                spinnerTarget.adapter = ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_dropdown_item, sheets.map { it.name }
                )
            } catch (e: Exception) {
                toast("Sheet load failed: ${e.message ?: "unknown"}")
            } finally {
                pbTargetLoad.isVisible = false
            }
        }
    }

    private fun loadTabsForSheet(sheetId: String) {
        val account = googleAccount ?: return
        val acctObj = account.account ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ctx = context ?: return@launch
                val token = withContext(Dispatchers.IO) {
                    try {
                        GoogleAuthUtil.getToken(ctx, acctObj, ConfigSheetDriveApi.OAUTH_SCOPE)
                    } catch (e: UserRecoverableAuthException) {
                        withContext(Dispatchers.Main) {
                            try { recoverableLauncher.launch(e.intent) } catch (_: Exception) {}
                        }
                        null
                    }
                } ?: return@launch
                val tabs = withContext(Dispatchers.IO) { ConfigSheetDriveApi.fetchSheetTabs(token, sheetId, httpClient) }
                availableTabs = tabs
                spinnerTab.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, tabs)
                selectedTab = tabs.firstOrNull() ?: ""
            } catch (e: Exception) {
                toast("Tab load failed: ${e.message ?: "unknown"}")
            }
        }
    }

    // ── Step 3: confirm + save ─────────────────────────────────

    private fun updateSummary() {
        tvSummary.text = buildString {
            append("Branch: $selectedBranchName\n")
            append("Google account: ${googleAccount?.email ?: "-"}\n")
            append("Sheet: ${selectedSheet?.name ?: "-"}\n")
            append("Tab: $selectedTab")
        }
    }

    private fun saveConnector() {
        val branchId = selectedBranchId
        val sheet    = selectedSheet
        val account  = googleAccount
        if (branchId.isBlank() || sheet == null || selectedTab.isBlank() || account == null) {
            toast("সব ধাপ সম্পূর্ণ করুন")
            return
        }
        val uid = auth.currentUser?.uid ?: ""
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ref = db.reference.child("config/connectors/$branchId").push()
                val payload = mapOf(
                    "type"        to "sheet",
                    "sheetId"     to sheet.id,
                    "sheetName"   to sheet.name,
                    "tabName"     to selectedTab,
                    "googleEmail" to (account.email ?: ""),
                    "connectedBy" to uid,
                    "connectedAt" to System.currentTimeMillis()
                )
                withContext(Dispatchers.IO) { ref.setValue(payload).await() }
                toast("Saved ✓")
                closeWizard()
            } catch (e: Exception) {
                toast("Save failed: ${e.message}")
            }
        }
    }

    private fun toast(msg: String) {
        context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
    }
}
