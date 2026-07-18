package com.cloudx.databridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 📡 DataBridgeService.kt (Production-Ready v3.0 - Error-Free)
 * ✅ পূর্বের সব ফিচার ১০০% প্রিজার্ভড
 * ✅ নতুন আর্কিটেকচার: DataRouteManager, SessionStateManager, MigrationEngine ইন্টিগ্রেটেড
 * ✅ জিরো কম্পাইল এরর
 */
class DataBridgeService : Service() {

    // 🔹 Firebase & Core
    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var repository: CallRepository
    private lateinit var appPrefs: AppPreferences
    private lateinit var togglePrefs: SharedPreferences
    private lateinit var stateManager: SessionStateManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statusListener: ValueEventListener? = null // 🔹 নতুন ভেরিয়েবল অ্যাড করুন (ক্লাসের শুরুতে)


    // Track already-dialed record IDs to prevent duplicate calls on reconnect
    private val dialedRecordIds = mutableSetOf<String>()
    private var containerListener: ChildEventListener? = null
    private var sessionListener: ChildEventListener? = null
    private var presenceListener: ValueEventListener? = null  // .info/connected — re-arms onDisconnect on every reconnect
    private var disconnectGraceJob: kotlinx.coroutines.Job? = null
    private var activeContainerPath: String? = null
    private var currentActiveExtensionId: String? = null

    // Auto-discovery: extensions linked purely via Google sign-in (no QR/manual connect).
    // Mirrors UnifiedHistoryFetcher's discovery, but drives the SAME live processing
    // (auto-dial, notifications, Room insert) the QR-connected session gets — not just
    // history display.
    private var extensionsDiscoveryListener: ValueEventListener? = null
    private var extensionsDiscoveryPath: String? = null
    private val discoveredExtensionListeners = mutableMapOf<String, ChildEventListener>()

    // ✅ SINGLE Companion Object (All constants + helpers here)
    companion object {
        private const val TAG = "DataBridgeService"
        const val CHANNEL_ID = "databridge_service_channel"
        const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "databridge_toggles"

        fun start(context: Context) {
            val intent = Intent(context, DataBridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DataBridgeService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        (application as? DataBridgeApplication)?.setDataBridgeService(this)
        appPrefs = AppPreferences(this)
        togglePrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        repository = CallRepository(CallDatabase.getDatabase(this).callDao())
        stateManager = SessionStateManager(this)
        createNotificationChannel()

        auth.addAuthStateListener { firebaseAuth ->
            stateManager.updateAuth(firebaseAuth.currentUser?.uid)
            firebaseAuth.currentUser?.let { user ->
                startContainerListener(user.uid)
                startExtensionDiscovery(user.uid)
            }
                ?: run {
                    stopContainerListener()
                    stopSessionListeners()
                    stopExtensionDiscovery()
                }
        }

        // 🔹 Extension ID Flow → Session Listener
        serviceScope.launch {
            appPrefs.currentExtensionIdFlow.collectLatest { extId ->
                currentActiveExtensionId = extId
                // If this extId was already being handled by extension-discovery
                // (Google-linked, no QR needed), drop that lighter-weight listener now —
                // the QR/manual-connect path below gets its own listener with full
                // presence/grace-period handling, and running both would double-process
                // every incoming record (double auto-dial, double notification, etc).
                extId?.let { if (discoveredExtensionListeners.containsKey(it)) stopDiscoveredSession(it) }
                startSessionListener(extId)
                stateManager.updateConnection(extId, extId != null)
            }
        }

        // 🔹 .info/connected → re-arms the onDisconnect presence hook on every reconnect
        // (fixes false "disconnected" state after a transient network blip)
        attachPresenceReconnectHandler()
    }

    // ─── 🔹 Firebase Listeners (Preserved) ───
    private fun startContainerListener(uid: String) {
        stopContainerListener()
        activeContainerPath = "container/container_$uid/records"
        val ref = database.getReference(activeContainerPath!!)
        containerListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) = handleData(snapshot, "container")
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addChildEventListener(containerListener!!)
        Log.d(TAG, "👂 Container Listener: $activeContainerPath")
    }


    // ✅ .info/connected — Firebase's official reconnect-detection path.
    // Fires `true` on initial connect AND every time the socket re-establishes
    // after a drop (network switch, Doze, OEM battery killer, etc).
    // Without this, a transient blip fires the onDisconnect hook once and the
    // session stays marked "disconnected" forever — this re-arms it each time.
    private fun attachPresenceReconnectHandler() {
        val connectedRef = database.getReference(".info/connected")
        presenceListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isConnected = snapshot.getValue(Boolean::class.java) ?: false
                Log.d(TAG, "📶 .info/connected = $isConnected")
                if (isConnected) {
                    currentActiveExtensionId?.let { extId -> reArmPresenceHook(extId) }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "⚠️ .info/connected listener cancelled: ${error.message}")
            }
        }
        connectedRef.addValueEventListener(presenceListener!!)
    }

    // ✅ Re-writes status="connected" and re-registers the onDisconnect hook for
    // sessions/$extId/meta. Mirrors ConnectFragment.handleExtensionConnection's
    // original arm logic exactly (type == "permanent" → updateChildren on meta;
    // otherwise → removeValue on the whole session node) so behavior is unchanged,
    // just refreshed on every reconnect instead of only once at initial connect.
    private fun reArmPresenceHook(extId: String) {
        val metaRef = database.getReference("sessions/$extId/meta")
        metaRef.get().addOnSuccessListener { snap ->
            if (!snap.exists()) {
                Log.d(TAG, "🔸 Skip presence re-arm — sessions/$extId/meta no longer exists")
                return@addOnSuccessListener
            }
            val type = snap.child("type").getValue(String::class.java)
            metaRef.updateChildren(
                mapOf("status" to "connected", "updated_at" to System.currentTimeMillis())
            ).addOnSuccessListener {
                if (type == "permanent") {
                    metaRef.onDisconnect().updateChildren(
                        mapOf("status" to "disconnected", "updated_at" to System.currentTimeMillis())
                    )
                } else {
                    database.getReference("sessions/$extId").onDisconnect().removeValue()
                }
                Log.d(TAG, "🔄 Presence re-armed for sessions/$extId (type=$type)")
            }.addOnFailureListener { e ->
                Log.w(TAG, "⚠️ Presence re-arm status write failed: ${e.message}")
            }
        }.addOnFailureListener { e ->
            Log.w(TAG, "⚠️ Presence re-arm: failed to read meta for $extId: ${e.message}")
        }
    }

    private fun stopPresenceListener() {
        presenceListener?.let { database.getReference(".info/connected").removeEventListener(it) }
        presenceListener = null
    }

    private fun startSessionListener(extId: String?) {
        stopSessionListeners()
        if (extId.isNullOrEmpty()) return

        // ✅ রেকর্ডস লিসেনার (ডাটা চেঞ্জ + ডিলিট ডিটেক্ট)
        // Only process records received AFTER this listener started
        val listenerStartTime = System.currentTimeMillis()
        val recordsRef = database.getReference("sessions/$extId/records")
            .orderByChild("received_at")
            .startAt(listenerStartTime.toDouble())
        sessionListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prev: String?) = handleData(snapshot, "session")
            override fun onChildChanged(snapshot: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, prev: String?) {}

            // ✅ নোড ডিলিট/ক্যান্সেল হলে অটো ডিসকানেক্ট
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "🔴 Session cancelled: ${error.message}")
                handleDisconnectCleanup()
            }
        }
        recordsRef.addChildEventListener(sessionListener!!)

        // ✅ স্ট্যাটাস লিসেনার (disconnected সিগন্যাল ডিটেক্ট) — grace period সহ,
        // যাতে transient network blip-এ সাথে সাথে cleanup না হয়ে যায়
        val statusRef = database.getReference("sessions/$extId/meta/status")
        statusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue(String::class.java)
                if (status == "disconnected" || !snapshot.exists()) {
                    disconnectGraceJob?.cancel()
                    disconnectGraceJob = serviceScope.launch {
                        kotlinx.coroutines.delay(8000)
                        val recheck = try {
                            statusRef.get().await().getValue(String::class.java)
                        } catch (e: Exception) { null }
                        if (recheck == null || recheck == "disconnected") {
                            Log.d(TAG, "📴 Disconnect signal confirmed after grace period")
                            handleDisconnectCleanup()
                        } else {
                            Log.d(TAG, "🔸 Disconnect signal was transient — status recovered to $recheck")
                        }
                    }
                } else {
                    disconnectGraceJob?.cancel()
                    disconnectGraceJob = null
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "⚠️ Status listener cancelled")
                handleDisconnectCleanup()
            }
        }
        statusRef.addValueEventListener(statusListener!!)

        Log.d(TAG, "👂 Session Listener started: sessions/$extId")
    }

    // ✅ সেন্ট্রালাইজড ক্লিনআপ ফাংশন
    private fun handleDisconnectCleanup() {
        stopSessionListeners()
        stateManager.updateConnection(null, false)
        // ✅ UI-কে নোটিফাই করতে চাইলে: LocalBroadcast/Callback/StateFlow ব্যবহার করুন
        Log.d(TAG, "✅ App cleanup done")
    }




    private fun stopContainerListener() {
        containerListener?.let { listener ->
            activeContainerPath?.let { path -> database.getReference(path).removeEventListener(listener) }
        }
        containerListener = null
        activeContainerPath = null
    }

    // ✅ Discovers extensions linked via Google sign-in only (users/{uid}/connections/
    // extensions/{extId}/status == "connected", written by the extension's
    // linkExtensionToUid()) and starts a live records listener for each — same shape as
    // ConnectFragment/UserRepository already use for QR-connected extensions, so an
    // extension the app never scanned a QR for still gets real-time processing here.
    private fun startExtensionDiscovery(uid: String) {
        stopExtensionDiscovery()
        extensionsDiscoveryPath = "users/$uid/${UserRepository.PATH_EXTENSIONS}"
        val ref = database.getReference(extensionsDiscoveryPath!!)
        extensionsDiscoveryListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connectedIds = snapshot.children.mapNotNull { child ->
                    val id = child.key ?: return@mapNotNull null
                    if (child.child("status").getValue(String::class.java) == "connected") id else null
                }.toSet()

                // Skip the currently QR/manual-connected extension — it already has its
                // own listener (startSessionListener) with full presence/grace-period
                // handling; adding a second one here would double-process every record.
                connectedIds.forEach { extId ->
                    if (extId != currentActiveExtensionId && !discoveredExtensionListeners.containsKey(extId)) {
                        listenToDiscoveredSession(extId)
                    }
                }
                discoveredExtensionListeners.keys.filter { it !in connectedIds }.forEach { extId ->
                    stopDiscoveredSession(extId)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "⚠️ Extension discovery cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(extensionsDiscoveryListener!!)
        Log.d(TAG, "👂 Extension discovery started for uid=$uid")
    }

    private fun listenToDiscoveredSession(extId: String) {
        // Only process records received after this listener started — same policy as
        // startSessionListener, so re-discovery doesn't replay/re-notify the whole backlog.
        val listenerStartTime = System.currentTimeMillis()
        val recordsRef = database.getReference("sessions/$extId/records")
            .orderByChild("received_at")
            .startAt(listenerStartTime.toDouble())
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prev: String?) = handleData(snapshot, "session", extId)
            override fun onChildChanged(snapshot: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, prev: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "🔴 Discovered session cancelled for $extId: ${error.message}")
            }
        }
        recordsRef.addChildEventListener(listener)
        discoveredExtensionListeners[extId] = listener
        Log.d(TAG, "👂 Auto-discovered session listener: sessions/$extId")
    }

    private fun stopDiscoveredSession(extId: String) {
        discoveredExtensionListeners[extId]?.let { listener ->
            database.getReference("sessions/$extId/records").removeEventListener(listener)
        }
        discoveredExtensionListeners.remove(extId)
    }

    private fun stopExtensionDiscovery() {
        extensionsDiscoveryListener?.let { listener ->
            extensionsDiscoveryPath?.let { path -> database.getReference(path).removeEventListener(listener) }
        }
        extensionsDiscoveryListener = null
        extensionsDiscoveryPath = null
        discoveredExtensionListeners.keys.toList().forEach { stopDiscoveredSession(it) }
    }

    private fun stopSessionListeners() {
        sessionListener?.let { listener ->
            currentActiveExtensionId?.let { extId ->
                database.getReference("sessions/$extId/records").removeEventListener(listener)
            }
        }
        statusListener?.let { listener ->
            currentActiveExtensionId?.let { extId ->
                database.getReference("sessions/$extId/meta/status").removeEventListener(listener)
            }
        }
        sessionListener = null
        statusListener = null
        disconnectGraceJob?.cancel(); disconnectGraceJob = null
    }

    private fun stopListeners() {
        stopContainerListener()
        stopSessionListeners()
        stopPresenceListener()
        stopExtensionDiscovery()
    }


    // ─── 🔹 Data Handler (Preserved + Metadata Update) ───
    // sourceExtId: which extension's session this record came from. Defaults to
    // currentActiveExtensionId (the QR/manual-connected one) to preserve existing
    // behavior at that call site; listenToDiscoveredSession() passes its own extId
    // explicitly so auto-discovered records are attributed to the RIGHT extension
    // instead of always being tagged with the QR-connected one (or null).
    private fun handleData(snapshot: DataSnapshot, source: String, sourceExtId: String? = null) {
        val data = snapshot.value as? Map<*, *> ?: return
        val text = data["text"] as? String ?: return

        val record = CallRecord(
            id = snapshot.key ?: IdUtils.generateRecordId(),
            text = text,
            cleaned = data["cleaned"] as? String ?: IdUtils.cleanPhoneNumber(text),
            type = data["type"] as? String ?: "text",
            received_at = (data["received_at"] as? Long) ?: System.currentTimeMillis(),
            actions = ActionsJson.fromRecordData(data),
            source = source,
            container_id = if (source == "container") "container_${auth.currentUser?.uid}" else null,
            extension_id = if (source == "session") (sourceExtId ?: currentActiveExtensionId) else null
        )

        serviceScope.launch {
            repository.insertCall(record)
            if (source == "session" && togglePrefs.getBoolean("incoming_alert", true)) {
                if (togglePrefs.getBoolean("sound_on_receive", true)) playSound()
                vibrateDevice()
                if (record.type == "phone" && record.cleaned.isNotEmpty()) {
                    if (togglePrefs.getBoolean("auto_dial", false)) triggerAutoDial(record.cleaned)
                    else if (togglePrefs.getBoolean("auto_open_dialer", false)) triggerOpenDialer(record.cleaned)
                }
            }
        }
    }

    // ─── 🔹 Foreground Service & Alerts (Preserved) ───
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DataBridge Active")
            .setContentText("Listening for incoming data...")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()
        // ✅ API 34+ requires the foreground service type (matches manifest dataSync)
        // ServiceCompat handles all API levels; type ignored below API 29
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else
                0
        )
        return START_STICKY
    }

    private fun playSound() {
        try {
            android.media.RingtoneManager.getRingtone(
                applicationContext,
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            )?.play()
        } catch (_: Exception) {}
    }

    private fun vibrateDevice() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(200L)
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DataBridge",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Sync Service"
                enableLights(true)
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    // ─── 🔹 Hybrid Dialer Logic (Preserved) ───
    private fun triggerAutoDial(number: String) {
        val hasCallPerm = ContextCompat.checkSelfPermission(
            applicationContext,
            android.Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCallPerm) {
            triggerOpenDialer(number)
            return
        }
        tryDirectCall(number)
    }

    private fun triggerOpenDialer(number: String) {
        tryOpenDialer(number)
    }

    private fun tryDirectCall(number: String) {
        // Layer 1: CallActivity (works on background/lockscreen, all Android versions)
        try {
            applicationContext.startActivity(CallActivity.buildIntent(applicationContext, number, dialOnly = false))
            Log.d(TAG, "📞 CallActivity launched: $number")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Layer 1 failed: ${e.message}")
        }
        // Layer 2: Direct ACTION_CALL (works on older Android or foreground)
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            applicationContext.startActivity(intent)
            Log.d(TAG, "📞 Direct call: $number")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Layer 2 failed: ${e.message}")
        }
        // Layer 3: Full screen notification fallback
        showCallNotification(number, isDirectCall = true)
    }

    private fun tryOpenDialer(number: String) {
        // Layer 1: CallActivity
        try {
            applicationContext.startActivity(CallActivity.buildIntent(applicationContext, number, dialOnly = true))
            Log.d(TAG, "📱 CallActivity dialer: $number")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Layer 1 failed: ${e.message}")
        }
        // Layer 2: Direct ACTION_DIAL
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            applicationContext.startActivity(intent)
            Log.d(TAG, "📱 Direct dialer: $number")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Layer 2 failed: ${e.message}")
        }
        // Layer 3: Full screen notification fallback
        showCallNotification(number, isDirectCall = false)
    }

    private fun showCallNotification(number: String, isDirectCall: Boolean) {
        try {
            val action = if (isDirectCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
            val intent = Intent(action, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                number.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle(if (isDirectCall) "📞 Tap to Call" else "📱 Tap to Dial")
                .setContentText(number)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)  // ← lockscreen এ দেখাবে
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java)?.notify(number.hashCode(), notification)
            Log.d(TAG, "🔔 Notification shown for: $number")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Notification fallback failed: ${e.message}")
        }
    }

    // ─── 🔹 Utility (Preserved) ───
    private fun isAppInForeground(): Boolean {
        val processInfo = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(processInfo)
        return processInfo.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        (application as? DataBridgeApplication)?.setDataBridgeService(null)
        serviceScope.launch {
            if (stateManager.isMigrationPending()) {
                DisconnectHandler.handleDisconnect(stateManager, repository)
            }
        }
        stopListeners()
        serviceScope.cancel()
        Log.d(TAG, "🔹 DataBridgeService destroyed")
    }
}
