package com.cloudx.databridge

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 📦 AppPreferences.kt (Production-Ready Hybrid Version)
 * ✅ Critical Data (UID, Extension, Toggles) → Jetpack DataStore (Flow-based)
 * ✅ Simple Flags (perms_setup_done) → SharedPreferences (Instant access)
 * ✅ Type-safe keys, clean architecture, no red lines
 */

// ✅ DataStore ইনস্ট্যান্স (সিঙ্গেলটন প্যাটার্ন)
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

// ✅ SharedPreferences for simple flags (non-critical, instant access)
private const val PREFS_SIMPLE_NAME = "app_simple_prefs"

class AppPreferences(private val context: Context) {

    // ✅ Simple SharedPreferences instance for flags
    private val simplePrefs: SharedPreferences = context.getSharedPreferences(PREFS_SIMPLE_NAME, Context.MODE_PRIVATE)

    companion object {
        // 🔹 UID Keys (DataStore)
        val KEY_CURRENT_UID = stringPreferencesKey("current_uid")
        val KEY_AUTH_UID = stringPreferencesKey("auth_uid")

        // 🔹 Extension ID Key (DataStore)
        val KEY_CURRENT_EXTENSION_ID = stringPreferencesKey("current_extension_id")

        // 🔹 Simple Flag Key (SharedPreferences - not in DataStore)
        private const val KEY_PERMS_SETUP_DONE = "perms_setup_done"
    }

    // ══════════════════════════════
    // 🔹 Extension ID Flow
    // ══════════════════════════════
    val currentExtensionIdFlow: Flow<String?> = context.dataStore.data.map { prefs -> prefs[KEY_CURRENT_EXTENSION_ID] }

    // ══════════════════════════════
    // 🔹 Simple Flag Methods (SharedPreferences - Instant, Non-Suspend)
    // ══════════════════════════════

    /** ✅ পারমিশন সেটআপ কমপ্লিট কিনা চেক করুন (Instant) */
    fun isPermissionsSetupComplete(): Boolean {
        return simplePrefs.getBoolean(KEY_PERMS_SETUP_DONE, false)
    }

    /** ✅ পারমিশন সেটআপ কমপ্লিট মার্ক করুন (Instant) */
    fun setPermissionsSetupComplete(done: Boolean) {
        simplePrefs.edit().putBoolean(KEY_PERMS_SETUP_DONE, done).apply()
    }

    // ══════════════════════════════
    // 🔹 Save Methods (Suspend Functions for DataStore)
    // ══════════════════════════════

    /** ✅ UID ক্লিয়ার করুন */
    suspend fun clearUid() {
        context.dataStore.edit { prefs -> prefs.remove(KEY_CURRENT_UID) }
    }

    /** ✅ Auth State সেভ করুন */
    suspend fun saveAuthState(uid: String) {
        context.dataStore.edit { prefs -> prefs[KEY_AUTH_UID] = uid }
    }

    /** ✅ Auth State ক্লিয়ার করুন */
    suspend fun clearAuthState() {
        context.dataStore.edit { prefs -> prefs.remove(KEY_AUTH_UID) }
    }

    // ══════════════════════════════
    // 🔹 Extension ID Methods
    // ══════════════════════════════

    /** ✅ বর্তমান এক্সটেনশন আইডি সেভ করুন */
    suspend fun setCurrentExtensionId(extId: String) {
        context.dataStore.edit { prefs -> prefs[KEY_CURRENT_EXTENSION_ID] = extId }
    }

    /** ✅ বর্তমান এক্সটেনশন আইডি রিড করুন (Suspend) */
    suspend fun getCurrentExtensionId(): String? {
        return context.dataStore.data.map { prefs -> prefs[KEY_CURRENT_EXTENSION_ID] }.first()
    }

    /** ✅ এক্সটেনশন আইডি ক্লিয়ার করুন */
    suspend fun clearExtensionId() {
        context.dataStore.edit { prefs -> prefs.remove(KEY_CURRENT_EXTENSION_ID) }
    }

}