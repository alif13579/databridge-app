package com.cloudx.databridge

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

object AuthManager {

    private val auth = FirebaseAuth.getInstance()

    fun currentUser() = auth.currentUser

    private fun activeUser() = auth.currentUser?.takeUnless { it.isAnonymous }

    fun displayName(): String {
        val user = activeUser() ?: return "Guest"
        return user.displayName
            ?: user.email?.substringBefore("@")
            ?: "User"
    }

    fun isLoggedIn(): Boolean = activeUser() != null

    suspend fun signOut(context: Context, googleSignInClient: GoogleSignInClient?) {
        val user = activeUser()
        val uid = user?.uid
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val appPrefs = AppPreferences(context)
        if (uid != null) {
            val userRepo = UserRepository(uid)
            try { userRepo.removeAndroidConnection(androidId) } catch (_: Exception) {}
            try { userRepo.updateLastActive() } catch (_: Exception) {}
            val extId = appPrefs.getCurrentExtensionId()
            if (!extId.isNullOrEmpty()) {
                try { userRepo.removeExtensionConnection(extId) } catch (_: Exception) {}
                // Reset the session's meta back to guest — without this, sessions/$extId/meta
                // keeps this (now logged-out) uid and type="permanent" forever. The extension
                // resolves its active container from that meta, so it would keep silently
                // writing new history/scans into this account's container even after the app
                // has logged out of it, with no way for the user to notice.
                try {
                    FirebaseDatabase.getInstance().getReference("sessions/$extId/meta").updateChildren(
                        mapOf(
                            "user_id"    to "",
                            "type"       to "temporary",
                            "updated_at" to System.currentTimeMillis()
                        )
                    ).await()
                } catch (_: Exception) {}
            }
        }
        auth.signOut()
        googleSignInClient?.signOut()?.await()
        appPrefs.clearUid()
        appPrefs.clearAuthState()
        appPrefs.clearExtensionId()
    }

    suspend fun completeGoogleSignIn(context: Context, account: GoogleSignInAccount) {
        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        auth.signInWithCredential(credential).await()
        val uid = auth.currentUser?.uid ?: return
        val appPrefs = AppPreferences(context)
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val model = "${Build.MANUFACTURER} ${Build.MODEL}"
        val userRepo = UserRepository(uid)

        // ✅ Check if this is an existing user or a brand-new one
        val existingProfileSnap = try {
            FirebaseDatabase.getInstance().getReference("users/$uid/profile").get().await()
        } catch (_: Exception) {
            null
        }

        val profileExists = existingProfileSnap?.exists() == true

        if (profileExists) {
            // ✅ Existing user — do NOT touch profile at all
            // Only update device connection so the device is tracked
            userRepo.saveAndroidConnection(androidId, model)
        } else {
            // ✅ New user — create fresh profile with guest role
            val photoUrl = account.photoUrl?.toString().orEmpty()
            val authPhone = auth.currentUser?.phoneNumber.orEmpty()
            userRepo.createNewProfile(
                name         = account.displayName ?: "User",
                email        = account.email ?: "",
                photoUrl     = photoUrl,
                phoneNumber  = authPhone.ifBlank { null },
                androidId    = androidId,
                androidModel = model
            )
        }

        // Always attach onDisconnect hook for device status
        FirebaseDatabase.getInstance()
            .getReference("users/$uid/${UserRepository.PATH_ANDROIDS}/$androidId/status")
            .onDisconnect()
            .setValue(UserRepository.STATUS_INACTIVE)

        FirebaseContainerManager.verifyAndMigrate("", uid)

        val extId = appPrefs.getCurrentExtensionId()
        if (!extId.isNullOrEmpty()) {
            userRepo.saveExtensionConnection(extId, "permanent", androidId)
            FirebaseDatabase.getInstance().getReference("sessions/$extId/meta").updateChildren(
                mapOf(
                    "user_id"    to uid,
                    "type"       to "permanent",
                    "updated_at" to System.currentTimeMillis()
                )
            ).await()
        }
        appPrefs.saveAuthState(uid)
    }
}

