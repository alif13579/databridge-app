package com.cloudx.databridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Agent live tracking (worker role): foreground service pushing FusedLocation
 * fixes to `courier/live_locations/{systemId}` every ~30s (20m displacement)
 * while logged in. Foreground-only — no background-location permission.
 *
 * Stops on logout (AuthManager) / when location is revoked. Supervisors read
 * the node in LiveTrackingFragment. Last point stays (shows last-seen).
 */
class LiveLocationService : Service() {

    companion object {
        const val ACTION_START = "com.cloudx.databridge.liveloc.START"
        const val ACTION_STOP = "com.cloudx.databridge.liveloc.STOP"
        const val EXTRA_SYSTEM_ID = "system_id"
        const val EXTRA_NAME = "agent_name"
        const val EXTRA_BRANCH = "branch_id"

        private const val CHANNEL_ID = "live_location"
        private const val NOTIF_ID = 9401
        private const val INTERVAL_MS = 30_000L
        private const val FASTEST_MS = 15_000L
        private const val DISPLACEMENT_M = 20f

        fun start(context: Context, systemId: String, name: String, branchId: String) {
            val intent = Intent(context.applicationContext, LiveLocationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SYSTEM_ID, systemId)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_BRANCH, branchId)
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            try {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, LiveLocationService::class.java)
                )
            } catch (_: Exception) {}
        }
    }

    private var systemId: String = ""
    private var agentName: String = ""
    private var branchId: String = ""
    private var callback: LocationCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun identityPrefs() =
        getSharedPreferences("live_location", Context.MODE_PRIVATE)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                try {
                    identityPrefs().edit().clear().apply()
                } catch (_: Exception) {}
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                systemId = intent.getStringExtra(EXTRA_SYSTEM_ID).orEmpty()
                agentName = intent.getStringExtra(EXTRA_NAME).orEmpty()
                branchId = intent.getStringExtra(EXTRA_BRANCH).orEmpty()
                if (systemId.isBlank() || !hasLocationPermission() ||
                    FirebaseAuth.getInstance().currentUser == null
                ) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    identityPrefs().edit()
                        .putString(EXTRA_SYSTEM_ID, systemId)
                        .putString(EXTRA_NAME, agentName)
                        .putString(EXTRA_BRANCH, branchId)
                        .apply()
                } catch (_: Exception) {}
                startForegroundWithType()
                beginUpdates()
                return START_STICKY
            }
        }
        // Process-death restart (null intent): resume with the saved identity
        // so tracking survives without waiting for the next login.
        val saved = identityPrefs().getString(EXTRA_SYSTEM_ID, null).orEmpty()
        if (saved.isNotBlank() && hasLocationPermission() &&
            FirebaseAuth.getInstance().currentUser != null
        ) {
            systemId = saved
            agentName = identityPrefs().getString(EXTRA_NAME, null).orEmpty()
            branchId = identityPrefs().getString(EXTRA_BRANCH, null).orEmpty()
            startForegroundWithType()
            beginUpdates()
            return START_STICKY
        }
        stopSelf()
        return START_NOT_STICKY
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun startForegroundWithType() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mgr?.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Live location", NotificationManager.IMPORTANCE_LOW)
                )
            }
        } catch (_: Exception) {}
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, LiveLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📍 Live location sharing")
            .setContentText("Supervisor can see your location while on duty")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun beginUpdates() {
        // Already running (repeat START, e.g. resume re-check) — don't double-register.
        if (callback != null) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(FASTEST_MS)
            .setMinUpdateDistanceMeters(DISPLACEMENT_M)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                pushPoint(loc.latitude, loc.longitude, loc.accuracy)
            }
        }
        callback = cb
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun pushPoint(lat: Double, lng: Double, accuracy: Float) {
        if (systemId.isBlank() || FirebaseAuth.getInstance().currentUser == null) return
        val point = mapOf(
            "lat" to lat,
            "lng" to lng,
            "accuracy" to accuracy.toDouble(),
            "timestamp" to System.currentTimeMillis(),
            "name" to agentName,
            "branch_id" to branchId
        )
        try {
            FirebaseDatabase.getInstance()
                .getReference("courier/live_locations/$systemId")
                .setValue(point)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try {
            callback?.let {
                LocationServices.getFusedLocationProviderClient(this)
                    .removeLocationUpdates(it)
            }
        } catch (_: Exception) {}
        callback = null
        super.onDestroy()
    }
}

/**
 * Decides whether this install should share live location (logged-in worker
 * role + location granted) and starts/stops the service. Role + identity come
 * straight from the Firebase profile (RbacManager cache may not be loaded at
 * auth time yet).
 */
object LiveLocationTracker {

    /** Roles whose devices share live location. */
    private val TRACKED_ROLES = setOf("worker", "agent", "rider", "delivery_agent")

    /** onResume re-checks (admin may grant while installed) — throttled. */
    @Volatile private var lastAttemptMs: Long = 0L
    private const val RETRY_MS = 5 * 60 * 1000L

    fun startIfEligible(context: Context, force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastAttemptMs < RETRY_MS) return
        lastAttemptMs = System.currentTimeMillis()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val appCtx = context.applicationContext
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
                val fine = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                val coarse = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                if (!fine && !coarse) return@launch
                val profile = FirebaseDatabase.getInstance()
                    .getReference("users/$uid/profile").get().await()
                if (!profile.exists()) return@launch
                val role = profile.child("company_info/role_id").getValue(String::class.java)
                    ?.trim()?.lowercase().orEmpty()
                if (role !in TRACKED_ROLES) return@launch
                val systemId = profile.child("company_info/system_id").getValue(String::class.java)
                    ?.trim().orEmpty()
                if (systemId.isBlank()) return@launch
                val name = profile.child("name").getValue(String::class.java)?.trim().orEmpty()
                val branchIds = profile.child("company_info/branch_ids").children
                    .mapNotNull { it.getValue(String::class.java)?.trim() }
                    .filter { it.isNotBlank() }
                LiveLocationService.start(appCtx, systemId, name, branchIds.firstOrNull().orEmpty())
            } catch (_: Exception) {}
        }
    }

    fun stop(context: Context) {
        try {
            LiveLocationService.stop(context.applicationContext)
        } catch (_: Exception) {}
    }

    /** Re-check after the onboarding permission grant (role-gated inside). */
    fun onPermissionGranted(context: Context) = startIfEligible(context)
}
