package com.cloudx.databridge

import android.app.Application
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Updates users/{uid}/connections/androids/{androidId}/status:
 * active (foreground), away (background), inactive (onDisconnect when process dies).
 * Node removed only on logout — not when app is closed.
 */
class AppLifecycleObserver(private val application: Application) : DefaultLifecycleObserver {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseDatabase.getInstance()
    private var statusUpdateJob: Job? = null
    private val TAG = "AppLifecycleObserver"

    override fun onStart(owner: LifecycleOwner) {
        updateDeviceStatus(UserRepository.STATUS_ACTIVE, attachInactiveHook = true)
    }

    override fun onStop(owner: LifecycleOwner) {
        updateDeviceStatus(UserRepository.STATUS_AWAY, attachInactiveHook = false)
    }

    private fun updateDeviceStatus(status: String, attachInactiveHook: Boolean) {
        statusUpdateJob?.cancel()
        statusUpdateJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val uid = auth.currentUser?.uid ?: return@launch
                val androidId = Settings.Secure.getString(
                    application.applicationContext.contentResolver,
                    Settings.Secure.ANDROID_ID
                )
                val path = "users/$uid/${UserRepository.PATH_ANDROIDS}/$androidId"
                db.reference.child(path).updateChildren(
                    mapOf("status" to status, "last_active" to System.currentTimeMillis())
                ).await()

                if (attachInactiveHook) {
                    db.reference.child("$path/status").onDisconnect()
                        .setValue(UserRepository.STATUS_INACTIVE)
                        .await()
                }
                Log.d(TAG, "Device status: $status")
            } catch (e: Exception) {
                Log.e(TAG, "Status update failed: ${e.message}")
            }
        }
    }

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    fun unregister() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        statusUpdateJob?.cancel()
    }
}
