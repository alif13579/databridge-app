package com.cloudx.databridge

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import coil.load
import coil.transform.CircleCropTransformation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.cloudx.databridge.ScannerFragment
import com.cloudx.databridge.AccessManagerFragment
import com.cloudx.databridge.MemoryFragment
import com.cloudx.databridge.SalaryManagerFragment

class MainActivity : AppCompatActivity(), AuthUiHost {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var navView: NavigationView
    private lateinit var statusDot: View
    private lateinit var tvTopBarUser: TextView
    private lateinit var ivUserAvatar: ImageView
    private lateinit var appPrefs: AppPreferences
    private lateinit var googleSignInClient: GoogleSignInClient

    private val auth = FirebaseAuth.getInstance()
    private val firebaseDb = FirebaseDatabase.getInstance()
    private var sessionMonitorRef: DatabaseReference? = null
    private var sessionMonitorListener: ValueEventListener? = null
    private var disconnectGraceJob: kotlinx.coroutines.Job? = null  // grace period before treating "disconnected" as real
    private var permissionStep = 0

    private var layoutNoInternet: View? = null
    private var layoutFragmentLoading: View? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private val authStateListener = FirebaseAuth.AuthStateListener { refreshAuthUi() }

    private val callLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) nextPermissionStep() else showPermissionDialog("Call Permission", "অটো ডায়াল ফিচার কাজ করবে না।")
    }
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) nextPermissionStep() else showPermissionDialog("Camera Permission", "QR স্ক্যান ফিচার কাজ করবে না।")
    }

    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(ApiException::class.java)
                    lifecycleScope.launch {
                        try {
                            AuthManager.completeGoogleSignIn(this@MainActivity, account)
                            auth.currentUser?.uid?.let { uid ->
                                val db = CallDatabase.getDatabase(this@MainActivity)
                                SyncManager(CallRepository(db.callDao()), appPrefs, this@MainActivity)
                                    .startSync(uid)
                            }
                            Toast.makeText(this@MainActivity, "Cloud sync active", Toast.LENGTH_SHORT).show()
                            refreshAuthUi()
                            notifySettingsFragmentAuthChanged()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Login failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: ApiException) {
                    Toast.makeText(this, "Sign-in failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val togglePrefs = getSharedPreferences("databridge_toggles", MODE_PRIVATE)
        val isDark = togglePrefs.getBoolean("dark_mode", true)
        if (isDark) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()
        appPrefs = AppPreferences(this)
        setupGoogleSignIn()
        DataBridgeService.start(this)
        auth.addAuthStateListener(authStateListener)
        lifecycleScope.launch { RbacManager.primeGuestCache() }
        initViews()
        initDrawer()
        setupNetworkMonitor()
        if (appPrefs.isPermissionsSetupComplete()) {
            initApp(savedInstanceState == null)
        } else {
            permissionStep = 0
            nextPermissionStep()
        }
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
            return
        }
        if (supportFragmentManager.backStackEntryCount > 1) {
            supportFragmentManager.popBackStack()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("App বন্ধ করবেন?")
            .setMessage("DataBridge বন্ধ করতে চান?")
            .setPositiveButton("হ্যাঁ, বন্ধ করুন") { _, _ -> super.onBackPressed() }
            .setNegativeButton("না") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    override fun onDestroy() {
        auth.removeAuthStateListener(authStateListener)
        stopSessionMonitor()
        networkCallback?.let {
            try {
                val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun setupNetworkMonitor() {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager

        // Check current state immediately
        val isConnected = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        layoutNoInternet?.visibility = if (isConnected) View.GONE else View.VISIBLE

        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                runOnUiThread { layoutNoInternet?.visibility = View.GONE }
            }
            override fun onLost(network: android.net.Network) {
                runOnUiThread { layoutNoInternet?.visibility = View.VISIBLE }
            }
        }
        cm.registerNetworkCallback(request, networkCallback!!)
    }

    override fun onResume() {
        super.onResume()
        if (appPrefs.isPermissionsSetupComplete()) {
            refreshAuthUi()
        }
        if (permissionStep == 2 && !appPrefs.isPermissionsSetupComplete()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                permissionStep++
                nextPermissionStep()
            } else {
                appPrefs.setPermissionsSetupComplete(true)
                initApp(isFirstLaunch = false)
            }
        }
    }

    private fun setupGoogleSignIn() {
        val webClientId = try { getString(R.string.default_web_client_id) } catch (_: Exception) { "" }
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun initViews() {
        drawerLayout     = findViewById(R.id.drawer_layout)
        bottomNav        = findViewById(R.id.bottom_nav)
        navView          = findViewById(R.id.nav_view)
        layoutNoInternet    = findViewById(R.id.layoutNoInternet)
        layoutFragmentLoading = findViewById(R.id.layoutFragmentLoading)
        statusDot = findViewById(R.id.statusDot)
        tvTopBarUser = findViewById(R.id.tvTopBarUser)
        ivUserAvatar = findViewById(R.id.ivUserAvatar)

        findViewById<ImageView>(R.id.ivMenuToggle)?.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        // ── Notification bell ────────────────────────────────────────────
        val tvNotifBadge = findViewById<TextView>(R.id.tvNotifBadge)
        findViewById<FrameLayout>(R.id.layoutNotifBell)?.setOnClickListener {
            AppNotificationManager.markAllRead()
            tvNotifBadge?.visibility = View.GONE
            val sheet = NotificationListBottomSheet()
            sheet.onParcelClick = { parcelId, scope ->
                when (scope) {
                    "worker" -> navigateToWorkerSpaceWithParcel(parcelId)
                    else     -> navigateToCallCenterWithParcel(parcelId)
                }
            }
            sheet.show(supportFragmentManager, "notif_list")
        }
        AppNotificationManager.setBadgeListener { count ->
            runOnUiThread {
                tvNotifBadge?.text = if (count > 99) "99+" else count.toString()
                tvNotifBadge?.visibility = if (count > 0) View.VISIBLE else View.GONE
            }
        }
    }

    private fun initDrawer() {
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_dashboard -> loadFragment(DashboardFragment())
                R.id.nav_my_tasks  -> loadFragment(MyTasksFragment())
                R.id.nav_approvals -> loadFragment(ApprovalsFragment())
                R.id.nav_space     -> loadFragment(WorkerSpaceFragment())
                R.id.nav_call_center -> loadFragment(CallCenterFragment())
                R.id.nav_scanner   -> loadFragment(ScannerFragment())
                R.id.nav_memory    -> loadFragment(MemoryFragment())
                R.id.nav_chat      -> loadFragment(ChatFragment())
                R.id.nav_salary_manager -> loadFragment(SalaryManagerFragment())
                R.id.nav_config         -> loadFragment(ConfigFragment())
                R.id.nav_access_manager -> loadFragment(AccessManagerFragment())
                R.id.nav_reports   -> loadFragment(ReportsFragment())
                R.id.nav_settings  -> loadFragment(SettingsFragment())
                R.id.nav_support   -> loadFragment(SupportFragment())
                R.id.nav_connect   -> loadFragment(ConnectFragment())
                R.id.nav_history   -> loadFragment(HistoryFragment())
                R.id.nav_branches  -> {
                    val role     = RbacManager.current.roleId
                    val branchId = RbacManager.current.branchIds.firstOrNull().orEmpty()
                    if (role == "admin") loadFragment(BranchListFragment())
                    else if (branchId.isNotBlank()) loadFragment(BranchDetailFragment.newInstance(branchId))
                }
                R.id.nav_team      -> {
                    val role     = RbacManager.current.roleId
                    val branchId = RbacManager.current.branchIds.firstOrNull().orEmpty()
                    if (role == "admin") loadFragment(EmployeeFragment())
                    else loadFragment(EmployeeFragment.forBranch(branchId))
                }
                R.id.nav_login     -> launchGoogleSignIn()
                R.id.nav_logout    -> confirmLogout()
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    override fun launchGoogleSignIn() {
        googleSignInLauncher.launch(googleSignInClient.signInIntent)
    }

    override fun refreshAuthUi(forceReload: Boolean) {
        updateUserName()
        updateDrawerAuthItems()
        updateDrawerHeader()
        val uid = auth.currentUser?.uid
        if (uid != null) {
            lifecycleScope.launch {
                val info = if (forceReload) RbacManager.load(uid) else RbacManager.load(uid)
                applyRbacToMenu(info)
                applyBottomNavForRole(info.roleId)
                updateDrawerRbacInfo(info)
                ensureNavMatchesRole(info.roleId)
                updateBottomNavVisibility()
            }
        } else {
            lifecycleScope.launch {
                RbacManager.clear()
                val guestInfo = RbacManager.loadGuest()
                applyRbacToMenu(guestInfo)
                applyBottomNavForRole(guestInfo.roleId)
                ensureNavMatchesRole(guestInfo.roleId)
                updateDrawerRbacInfo(guestInfo)
                // After logout, close any restricted page (e.g., Access Manager) and go to guest default
                loadDefaultStartFragment()
                updateBottomNavVisibility()
            }
        }
    }

    private fun updateUserName() {
        val loggedIn = AuthManager.isLoggedIn()
        tvTopBarUser.text = AuthManager.displayName()
        tvTopBarUser.setTextColor(
            ContextCompat.getColor(
                this,
                if (loggedIn) R.color.theme_text_primary else R.color.theme_text_secondary
            )
        )
        val photoUrl = auth.currentUser?.photoUrl
        if (loggedIn && photoUrl != null) {
            ivUserAvatar.visibility = View.VISIBLE
            ivUserAvatar.load(photoUrl) {
                transformations(CircleCropTransformation())
                placeholder(android.R.drawable.ic_menu_myplaces)
            }
        } else {
            ivUserAvatar.load(null as android.net.Uri?)
            ivUserAvatar.visibility = View.GONE
        }
    }

    private fun updateDrawerAuthItems() {
        val loggedIn = AuthManager.isLoggedIn()
        val menu = navView.menu
        menu.findItem(R.id.nav_login)?.isVisible = !loggedIn
        menu.findItem(R.id.nav_logout)?.isVisible = loggedIn
    }

    private fun updateDrawerHeader() {
        val header      = navView.getHeaderView(0) ?: return
        val tvName      = header.findViewById<TextView>(R.id.tvDrawerUserName)
        val tvId        = header.findViewById<TextView>(R.id.tvDrawerEmployeeId)
        val ivAvatar    = header.findViewById<ImageView>(R.id.ivDrawerAvatar)
        val btnCopyUid  = header.findViewById<TextView>(R.id.btnCopyUid)
        val tvLastActive = header.findViewById<TextView>(R.id.tvDrawerLastActive)
        val tvEmpSysId  = header.findViewById<TextView>(R.id.tvDrawerEmpSysId)
        val user = auth.currentUser
        if (user != null) {
            tvName.text = user.displayName ?: user.email?.substringBefore("@") ?: "User"
            tvId.text   = user.email ?: user.uid.take(8)
            val photoUrl = user.photoUrl
            if (photoUrl != null) {
                ivAvatar.load(photoUrl) {
                    transformations(CircleCropTransformation())
                    placeholder(android.R.drawable.ic_menu_myplaces)
                }
            } else {
                ivAvatar.setImageResource(android.R.drawable.ic_menu_myplaces)
            }
            btnCopyUid.visibility = View.VISIBLE
            btnCopyUid.setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("UID", user.uid))
                btnCopyUid.text = "Copied ✓"
                btnCopyUid.setTextColor(0xFF00D4FF.toInt())
                btnCopyUid.compoundDrawables.forEach {
                    it?.setTint(0xFF00D4FF.toInt())
                }
                btnCopyUid.postDelayed({
                    btnCopyUid.text = "Your UID"
                    btnCopyUid.setTextColor(0xFF666666.toInt())
                    btnCopyUid.compoundDrawables.forEach {
                        it?.setTint(0xFF888888.toInt())
                    }
                }, 1500)
            }
            lifecycleScope.launch {
                val ciSnap = runCatching {
                    firebaseDb.getReference("users/${user.uid}/profile/company_info").get().await()
                }.getOrNull()
                val empId = ciSnap?.child("employee_id")?.getValue(String::class.java)?.takeIf { it.isNotBlank() }
                val sysId = ciSnap?.child("system_id")?.getValue(String::class.java)?.takeIf { it.isNotBlank() }
                if (empId != null || sysId != null) {
                    val parts = mutableListOf<String>()
                    empId?.let { parts.add("Emp: $it") }
                    sysId?.let { parts.add("Sys: $it") }
                    tvEmpSysId.text = parts.joinToString("  ·  ")
                    tvEmpSysId.visibility = View.VISIBLE
                } else {
                    tvEmpSysId.visibility = View.GONE
                }
            }

            lifecycleScope.launch {
                val snap = runCatching {
                    firebaseDb.getReference("users/${user.uid}/profile/lastActive").get().await()
                }.getOrNull()
                val ts = snap?.getValue(Long::class.java) ?: 0L
                if (ts > 0L) {
                    tvLastActive.text = formatLastActive(ts)
                    tvLastActive.visibility = View.VISIBLE
                } else {
                    tvLastActive.visibility = View.GONE
                }
            }
        } else {
            tvName.text = "Guest"
            tvId.text   = "Login to enable cloud sync"
            ivAvatar.load(android.R.drawable.ic_menu_myplaces) {
                memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                diskCachePolicy(coil.request.CachePolicy.DISABLED)
            }
            btnCopyUid.visibility  = View.GONE
            tvLastActive.visibility = View.GONE
        }
    }

    private fun formatLastActive(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000L          -> "Active just now"
            diff < 3_600_000L       -> "Active ${diff / 60_000}m ago"
            diff < 86_400_000L      -> "Active ${diff / 3_600_000}h ago"
            diff < 604_800_000L     -> "Active ${diff / 86_400_000}d ago"
            else                    -> "Active ${diff / 604_800_000}w ago"
        }
    }

    private fun updateLastActive() {
        val uid = auth.currentUser?.uid ?: return
        firebaseDb.getReference("users/$uid/profile/lastActive")
            .setValue(System.currentTimeMillis())
    }

    private fun applyRbacToMenu(info: RbacManager.UserRbacInfo) {
        val menu = navView.menu
        listOf(
            R.id.nav_dashboard to "nav_dashboard",
            R.id.nav_my_tasks  to "nav_my_tasks",
            R.id.nav_approvals to "nav_approvals",
            R.id.nav_reports   to "nav_reports",
            R.id.nav_settings  to "nav_settings",
            R.id.nav_support   to "nav_support"
        ).forEach { (itemId, key) ->
            menu.findItem(itemId)?.isVisible = RbacManager.hasPermission(key)
        }

        menu.findItem(R.id.nav_scanner)?.isVisible = RbacManager.hasPermission("nav_scanner")
        menu.findItem(R.id.nav_memory)?.isVisible = RbacManager.hasPermission("nav_memory")
        menu.findItem(R.id.nav_chat)?.isVisible   = RbacManager.hasPermission("nav_chat")
        menu.findItem(R.id.nav_connect)?.isVisible = RbacManager.hasPermission("nav_connect")
        menu.findItem(R.id.nav_history)?.isVisible = RbacManager.hasPermission("nav_history")

        // Items fully controlled by permissions
        menu.findItem(R.id.nav_config)?.isVisible = RbacManager.hasPermission("nav_config")
        menu.findItem(R.id.nav_access_manager)?.isVisible = RbacManager.hasPermission("nav_access_manager")
        menu.findItem(R.id.nav_salary_manager)?.isVisible = RbacManager.hasPermission("nav_salary_manager")

        menu.findItem(R.id.nav_branches)?.apply {
            isVisible = RbacManager.hasPermission("nav_branches")
            title     = "Branches"
        }
        menu.findItem(R.id.nav_team)?.apply {
            isVisible = RbacManager.hasPermission("nav_team")
        }

        // Primary nav items – no role gating; only permission-based
        menu.findItem(R.id.nav_space)?.isVisible = RbacManager.hasPermission("nav_space")
        menu.findItem(R.id.nav_call_center)?.isVisible = RbacManager.hasPermission("nav_call_center")
    }

    private fun applyBottomNavForRole(roleId: String) {
        val menu = bottomNav.menu
        val showWorkerSpace = RbacManager.hasPermission("nav_space")
        val showCallCenterItem = RbacManager.hasPermission("nav_call_center")
        val showConnect = RbacManager.hasPermission("nav_connect")
        val showHistory = RbacManager.hasPermission("nav_history")

        menu.findItem(R.id.nav_space)?.isVisible = showWorkerSpace
        menu.findItem(R.id.nav_call_center)?.isVisible = showCallCenterItem
        menu.findItem(R.id.nav_connect)?.isVisible = showConnect
        menu.findItem(R.id.nav_history)?.isVisible = showHistory
        updateBottomNavVisibility()
    }

    private fun updateBottomNavVisibility() {
        val anyVisible = (0 until bottomNav.menu.size()).any { bottomNav.menu.getItem(it).isVisible }
        bottomNav.visibility = if (anyVisible) View.VISIBLE else View.GONE
    }

    private fun ensureNavMatchesRole(roleId: String) {
        val current = supportFragmentManager.findFragmentById(R.id.container) ?: return
        val canSpace    = RbacManager.hasPermission("nav_space")
        val canCall     = RbacManager.hasPermission("nav_call_center")
        val canConnect  = RbacManager.hasPermission("nav_connect")
        val canHistory  = RbacManager.hasPermission("nav_history")

        // Only auto-navigate if current fragment is a bottom-nav managed fragment
        val isBottomNavFragment = current is WorkerSpaceFragment ||
                current is CallCenterFragment ||
                current is ConnectFragment ||
                current is HistoryFragment

        if (!isBottomNavFragment) return

        fun goFallback() {
            when {
                canSpace -> { loadFragment(WorkerSpaceFragment()); bottomNav.selectedItemId = R.id.nav_space }
                canCall  -> { loadFragment(CallCenterFragment()); bottomNav.selectedItemId = R.id.nav_call_center }
                canConnect -> { loadFragment(ConnectFragment()); bottomNav.selectedItemId = R.id.nav_connect }
                canHistory -> { loadFragment(HistoryFragment()); bottomNav.selectedItemId = R.id.nav_history }
                else -> loadFragment(DashboardFragment())
            }
        }

        when (current) {
            is WorkerSpaceFragment -> if (!canSpace) goFallback()
            is CallCenterFragment  -> if (!canCall) goFallback()
            is ConnectFragment     -> if (!canConnect) goFallback()
            is HistoryFragment     -> if (!canHistory) goFallback()
        }
    }

    private fun loadDefaultStartFragment() {
        val canSpace    = RbacManager.hasPermission("nav_space")
        val canCall     = RbacManager.hasPermission("nav_call_center")
        val canConnect  = RbacManager.hasPermission("nav_connect")
        val canHistory  = RbacManager.hasPermission("nav_history")
        when {
            canSpace   -> { loadFragment(WorkerSpaceFragment()); bottomNav.selectedItemId = R.id.nav_space }
            canCall    -> { loadFragment(CallCenterFragment()); bottomNav.selectedItemId = R.id.nav_call_center }
            canConnect -> { loadFragment(ConnectFragment()); bottomNav.selectedItemId = R.id.nav_connect }
            canHistory -> { loadFragment(HistoryFragment()); bottomNav.selectedItemId = R.id.nav_history }
            else       -> loadFragment(DashboardFragment())
        }
    }

    private fun updateDrawerRbacInfo(info: RbacManager.UserRbacInfo) {
        val header      = navView.getHeaderView(0) ?: return
        val tvRole      = header.findViewById<TextView>(R.id.tvDrawerRoleBadge)
        val tvBranch    = header.findViewById<TextView>(R.id.tvDrawerBranchName)
        val layoutBranch = header.findViewById<View>(R.id.layoutBranchSwitcher)
        val ivArrow     = header.findViewById<View>(R.id.ivBranchDropdownArrow)
        val roleLabel = when {
            info.roleName.isNotBlank() -> info.roleName
            info.roleId.isNotBlank() -> info.roleId
            else -> "Guest"
        }
        tvRole?.text = roleLabel
        tvRole?.visibility = View.VISIBLE
        if (info.branchName.isNotBlank()) {
            tvBranch?.text          = info.branchName
            layoutBranch?.visibility = View.VISIBLE
            val hasMultipleBranches = info.branchIds.size > 1
            ivArrow?.visibility = if (hasMultipleBranches) View.VISIBLE else View.GONE
            layoutBranch?.isClickable = hasMultipleBranches
            layoutBranch?.isFocusable = hasMultipleBranches
            if (hasMultipleBranches) {
                val outValue = TypedValue()
                header.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                layoutBranch?.setBackgroundResource(outValue.resourceId)
            } else {
                layoutBranch?.background = null
            }
        } else {
            layoutBranch?.visibility = View.GONE
        }
    }

    private fun notifySettingsFragmentAuthChanged() {
        val settings = supportFragmentManager.findFragmentById(R.id.container)
        if (settings is SettingsFragment) {
            settings.onAuthStateChanged()
        }
    }

    private fun nextPermissionStep() {
        when (permissionStep) {
            0 -> callLauncher.launch(android.Manifest.permission.CALL_PHONE)
            1 -> cameraLauncher.launch(android.Manifest.permission.CAMERA)
            2 -> requestOverlayPermission()
            3 -> {
                appPrefs.setPermissionsSetupComplete(true)
                initApp(isFirstLaunch = false)
            }
        }
        permissionStep++
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Overlay Permission Required")
                    .setMessage("ব্যাকগ্রাউন্ডে অটো ডায়ালার ওপেন করতে 'Draw over other apps' পারমিশন প্রয়োজন।")
                    .setPositiveButton("Allow") { _, _ ->
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                    .setNegativeButton("Skip") { _, _ ->
                        permissionStep++
                        nextPermissionStep()
                    }
                    .setCancelable(false)
                    .show()
            } else nextPermissionStep()
        } else nextPermissionStep()
    }

    private fun showPermissionDialog(permName: String, impact: String) {
        AlertDialog.Builder(this)
            .setTitle("$permName Denied")
            .setMessage("You denied $permName. $impact\n\nOpen Settings?")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
                permissionStep = 3
                appPrefs.setPermissionsSetupComplete(true)
            }
            .setNegativeButton("Continue Anyway") { _, _ ->
                permissionStep = 3
                appPrefs.setPermissionsSetupComplete(true)
                initApp(isFirstLaunch = false)
            }
            .setCancelable(false)
            .show()
    }

    private fun initApp(isFirstLaunch: Boolean = false) {
        initTopBar()
        setupBottomNavigation()
        refreshAuthUi()
        if (isFirstLaunch && supportFragmentManager.fragments.isEmpty()) {
            loadDefaultStartFragment()
        }
    }

    private fun setupBottomNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_space -> loadFragment(WorkerSpaceFragment())
                R.id.nav_call_center -> loadFragment(CallCenterFragment())
                R.id.nav_connect -> loadFragment(ConnectFragment())
                R.id.nav_history -> loadFragment(HistoryFragment())
            }
            true
        }
    }

    private fun initTopBar() {
        updateConnectionStatus(false)
    }

    // ── Session Monitor ──────
    fun startSessionMonitor(extId: String) {
        Log.d("SessionMonitor", "startSessionMonitor: extId=$extId")
        stopSessionMonitor()
        val ref = firebaseDb.reference.child("sessions/$extId/meta/status")
        sessionMonitorRef = ref
        sessionMonitorListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue(String::class.java)
                Log.d("SessionMonitor", "onDataChange: status=$status")
                if (status == null || status == "disconnected") {
                    // Could be a transient network blip (onDisconnect fires on any
                    // socket drop, not just a genuine user disconnect). Wait and
                    // re-check the live value before tearing anything down.
                    disconnectGraceJob?.cancel()
                    disconnectGraceJob = lifecycleScope.launch {
                        kotlinx.coroutines.delay(8000)
                        val recheck = try {
                            sessionMonitorRef?.get()?.await()?.getValue(String::class.java)
                        } catch (e: Exception) {
                            Log.w("SessionMonitor", "recheck failed: ${e.message}")
                            null
                        }
                        Log.d("SessionMonitor", "grace period elapsed, recheck status=$recheck")
                        if (recheck == null || recheck == "disconnected") {
                            onExtensionDisconnected()
                        }
                    }
                } else {
                    disconnectGraceJob?.cancel()
                    disconnectGraceJob = null
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w("SessionMonitor", "onCancelled: ${error.message}")
                onExtensionDisconnected()
            }
        }
        ref.addValueEventListener(sessionMonitorListener!!)
    }

    fun stopSessionMonitor() {
        sessionMonitorListener?.let { sessionMonitorRef?.removeEventListener(it) }
        sessionMonitorListener = null; sessionMonitorRef = null
        disconnectGraceJob?.cancel(); disconnectGraceJob = null
    }

    private fun onExtensionDisconnected() {
        stopSessionMonitor()
        runOnUiThread {
            updateConnectionStatus(false)
            DataBridgeService.stop(this)
            val connectFrag = supportFragmentManager.fragments
                .filterIsInstance<ConnectFragment>().firstOrNull()
            if (connectFrag != null) {
                connectFrag.onExternalDisconnect()
            } else {
                lifecycleScope.launch {
                    val prefs = AppPreferences(this@MainActivity)
                    val extId = try { prefs.getCurrentExtensionId() } catch (_: Exception) { null }
                    try { prefs.clearExtensionId() } catch (e: Exception) { Log.e("SessionMonitor", "clearExtensionId failed: $e") }
                    if (!extId.isNullOrEmpty()) {
                        try {
                            val type = firebaseDb.reference.child("sessions/$extId/meta/type").get().await().getValue(String::class.java)
                            val uid  = firebaseDb.reference.child("sessions/$extId/meta/user_id").get().await().getValue(String::class.java)
                            if (type == "permanent" && !uid.isNullOrEmpty()) {
                                val repo = CallRepository(CallDatabase.getDatabase(this@MainActivity).callDao())
                                FirebaseContainerManager.verifyAndMigrate(extId, uid, repo)
                            } else {
                                firebaseDb.reference.child("sessions/$extId").removeValue().await()
                            }
                        } catch (e: Exception) {
                            try { firebaseDb.reference.child("sessions/$extId").removeValue().await() } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

    fun updateConnectionStatus(isConnected: Boolean) {
        statusDot.setBackgroundResource(
            if (isConnected) R.drawable.circle_green else R.drawable.circle_red
        )
    }

    private fun loadFragment(fragment: androidx.fragment.app.Fragment) {
        layoutFragmentLoading?.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
        // Do NOT call executePendingTransactions() here — it can throw
        // IllegalStateException when a BottomSheet dismiss is already in-flight
        // (the fragment manager is mid-transaction). The loading overlay is
        // hidden once the container's next layout pass completes instead.
        layoutFragmentLoading?.post { layoutFragmentLoading?.visibility = View.GONE }
    }

    fun navigateToHistory() {
        when {
            RoleNavPolicy.showWorkerSpace(RbacManager.current.roleId) -> {
                navigateToWorkerSpace()
            }
            RoleNavPolicy.showCallCenter(RbacManager.current.roleId) -> {
                navigateToCallCenter()
            }
            else -> {
                loadFragment(HistoryFragment())
                bottomNav.selectedItemId = R.id.nav_history
            }
        }
    }

    fun navigateToWorkerSpace() {
        loadFragment(WorkerSpaceFragment())
        if (bottomNav.menu.findItem(R.id.nav_space) != null) {
            bottomNav.selectedItemId = R.id.nav_space
        }
    }

    fun navigateToCallCenter() {
        loadFragment(CallCenterFragment())
        if (bottomNav.menu.findItem(R.id.nav_call_center) != null) {
            bottomNav.selectedItemId = R.id.nav_call_center
        }
    }

    fun navigateToCallCenterWithParcel(parcelId: String) {
        loadFragment(ParcelDetailFragment.newInstance(parcelId, "cc"))
    }

    fun navigateToWorkerSpaceWithParcel(parcelId: String) {
        loadFragment(ParcelDetailFragment.newInstance(parcelId, "worker"))
    }

    fun navigateToChangelog() {
        loadFragment(ChangelogFragment())
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout?")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Yes") { _, _ -> performLogout() }
            .setNegativeButton("No", null)
            .show()
    }

    override fun onStop() {
        super.onStop()
        updateLastActive()
    }

    private fun performLogout() {
        lifecycleScope.launch {
            val wasConnected = !appPrefs.getCurrentExtensionId().isNullOrEmpty()
            try {
                updateLastActive()
                AuthManager.signOut(this@MainActivity, googleSignInClient)
                updateConnectionStatus(false)
                refreshAuthUi()
                notifySettingsFragmentAuthChanged()
                redirectFromProtectedIfNeeded(wasConnected)
                Toast.makeText(this@MainActivity, "Logged out", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Logout failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun redirectFromProtectedIfNeeded(wasConnected: Boolean) {
        val protectedClasses = listOf(
            BranchListFragment::class.java, BranchDetailFragment::class.java,
            EmployeeFragment::class.java, BranchEditFragment::class.java,
            BranchCreateFragment::class.java, EmployeeEditFragment::class.java,
            WorkerSpaceFragment::class.java, CallCenterFragment::class.java
        )
        val isOnProtected = supportFragmentManager.fragments.any { f ->
            f.isVisible && protectedClasses.any { it.isInstance(f) }
        }
        if (!isOnProtected) return
        supportFragmentManager.popBackStack(null, 1)
        val role = RbacManager.current.roleId
        when {
            RoleNavPolicy.showWorkerSpace(role) -> navigateToWorkerSpace()
            RoleNavPolicy.showCallCenter(role) -> navigateToCallCenter()
            wasConnected -> navigateToHistory()
            RoleNavPolicy.showConnectHistory(role) -> {
                loadFragment(ConnectFragment())
                bottomNav.selectedItemId = R.id.nav_connect
            }
            else -> loadFragment(DashboardFragment())
        }
    }

    fun logoutAndRefreshUi() {
        performLogout()
    }
}
