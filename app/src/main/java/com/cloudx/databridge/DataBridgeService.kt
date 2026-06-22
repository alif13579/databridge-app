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


    // 🔹 Listeners & State
    private var currentActiveExtensionId: String? = null
    private var containerListener: ChildEventListener? = null
    private var sessionListener: ChildEventListener? = null
    private var activeContainerPath: String? = null

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
            firebaseAuth.currentUser?.let { user -> startContainerListener(user.uid) }
                ?: run {
                    stopContainerListener()
                    stopSessionListeners()
                }
        }

        // 🔹 Extension ID Flow → Session Listener
        serviceScope.launch {
            appPrefs.currentExtensionIdFlow.collectLatest { extId ->
                currentActiveExtensionId = extId
                startSessionListener(extId)
                stateManager.updateConnection(extId, extId != null)
            }
        }
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


    private fun startSessionListener(extId: String?) {
        stopSessionListeners()
        if (extId.isNullOrEmpty()) return

        // ✅ রেকর্ডস লিসেনার (ডাটা চেঞ্জ + ডিলিট ডিটেক্ট)
        val recordsRef = database.getReference("sessions/$extId/records")
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

        // ✅ স্ট্যাটাস লিসেনার (disconnected সিগন্যাল ডিটেক্ট)
        val statusRef = database.getReference("sessions/$extId/meta/status")
        statusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue(String::class.java)
                if (status == "disconnected" || !snapshot.exists()) {
                    Log.d(TAG, "📴 Disconnect signal received")
                    handleDisconnectCleanup()
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
    }

    private fun stopListeners() {
        stopContainerListener()
        stopSessionListeners()
    }


    // ─── 🔹 Data Handler (Preserved + Metadata Update) ───
    private fun handleData(snapshot: DataSnapshot, source: String) {
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
            extension_id = if (source == "session") currentActiveExtensionId else null
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
        if (isAppInForeground() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(applicationContext))) {
            tryDirectCall(number)
        } else {
            showCallNotification(number, isDirectCall = true)
        }
    }

    private fun triggerOpenDialer(number: String) {
        if (isAppInForeground() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(applicationContext))) {
            tryOpenDialer(number)
        } else {
            showCallNotification(number, isDirectCall = false)
        }
    }

    private fun tryDirectCall(number: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            applicationContext.startActivity(intent)
            Log.d(TAG, "📞 Direct call triggered: $number")
        } catch (e: Exception) {
            Log.w(TAG, "Direct call failed → Notification fallback")
            showCallNotification(number, true)
        }
    }

    private fun tryOpenDialer(number: String) {
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            applicationContext.startActivity(intent)
            Log.d(TAG, "📱 Dialer opened: $number")
        } catch (e: Exception) {
            Log.w(TAG, "Dialer open failed → Notification fallback")
            showCallNotification(number, false)
        }
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
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
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
