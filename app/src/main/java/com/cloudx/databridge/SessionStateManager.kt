package com.cloudx.databridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 📡 SessionStateManager.kt
 * ✅ কানেকশন/অথেন্টিকেশন স্টেট ট্র্যাক করে, ডিসকানেক্ট ইভেন্ট ট্রিগার করে
 */
class SessionStateManager(private val context: Context) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    data class SessionState(
        val extensionId: String? = null,
        val authUid: String? = null,
        val isConnected: Boolean = false,
        val isAuthenticatedDuringSession: Boolean = false
    )

    fun updateConnection(extId: String?, isConnected: Boolean) {
        _state.value = _state.value.copy(extensionId = extId, isConnected = isConnected)
    }
    fun updateAuth(uid: String?) {
        _state.value = _state.value.copy(authUid = uid)
        if (uid != null && _state.value.isConnected) {
            _state.value = _state.value.copy(isAuthenticatedDuringSession = true)
            Log.d("SessionManager", "🔐 Authenticated during active session. Migration queued on disconnect.")
        }
    }
    fun isMigrationPending(): Boolean = _state.value.isAuthenticatedDuringSession && _state.value.authUid != null && _state.value.extensionId != null
    fun getPendingExtId(): String? = _state.value.extensionId
    fun getPendingAuthUid(): String? = _state.value.authUid
}