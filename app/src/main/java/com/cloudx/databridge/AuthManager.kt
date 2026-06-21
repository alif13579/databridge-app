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

        // Read existing profile to preserve all admin-set fields
        val existingProfileSnap = runCatching {
            FirebaseDatabase.getInstance().getReference("users/$uid/profile").get().await()
        }.getOrNull()
        val existingRoleId      = existingProfileSnap?.child("company_info/role_id")?.getValue(String::class.java)
        val existingBranchIds   = existingProfileSnap?.child("company_info/branch_ids")?.children?.mapNotNull { it.getValue(String::class.java) } ?: emptyList()
        val existingEmpId       = existingProfileSnap?.child("company_info/employee_id")?.getValue(String::class.java).orEmpty()
        val existingDesig       = existingProfileSnap?.child("company_info/designation")?.getValue(String::class.java).orEmpty()
        val existingAgentType   = existingProfileSnap?.child("company_info/agent_type")?.getValue(String::class.java).orEmpty()
        val existingSalaryModel = existingProfileSnap?.child("company_info/salary_model")?.getValue(String::class.java).orEmpty()
        val existingSalaryType  = existingProfileSnap?.child("company_info/salary_type")?.getValue(String::class.java).orEmpty()
        val existingFixedAmount = existingProfileSnap?.child("company_info/fixed_amount")?.getValue(String::class.java).orEmpty()
        val existingStatus      = existingProfileSnap?.child("company_info/status")?.getValue(String::class.java).orEmpty()
        val existingPhoto       = existingProfileSnap?.child("photo_url")?.getValue(String::class.java).orEmpty()
        val existingPhoneNumber = existingProfileSnap?.child("phone_number")?.getValue(String::class.java).orEmpty()
        val existingCreatedAt   = existingProfileSnap?.child("createdAt")?.getValue(Long::class.java)
        val existingLastActive  = existingProfileSnap?.child("lastActive")?.getValue(Long::class.java)
        val authPhone           = auth.currentUser?.phoneNumber?.orEmpty()
        val phoneToKeep         = when {
            existingPhoneNumber.isNotBlank() -> existingPhoneNumber
            !authPhone.isNullOrBlank() -> authPhone
            else -> null
        }
        val photoUrl            = if (existingPhoto.isNotBlank()) existingPhoto else account.photoUrl?.toString().orEmpty()

        userRepo.saveProfileMerged(
            UserProfile(
                name           = account.displayName ?: "User",
                email          = account.email ?: "",
                phone_number   = existingPhoneNumber,
                containerId    = "container_$uid",
                user_id        = uid,
                photo_url      = photoUrl,
                company_info   = CompanyInfo(
                    role_id       = if (existingRoleId.isNullOrEmpty()) "guest" else existingRoleId,
                    branch_ids    = existingBranchIds,
                    employee_id   = existingEmpId,
                    designation   = existingDesig,
                    agent_type    = existingAgentType,
                    salary_model  = existingSalaryModel,
                    salary_type   = existingSalaryType,
                    fixed_amount  = existingFixedAmount,
                    status        = existingStatus
                )
            ),
            phoneNumber = phoneToKeep,
            existingCreatedAt = existingCreatedAt,
            existingLastActive = existingLastActive
        )
        userRepo.saveAndroidConnection(androidId, model)

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
                    "user_id" to uid,
                    "type" to "permanent",
                    "updated_at" to System.currentTimeMillis()
                )
            ).await()
        }
        appPrefs.saveAuthState(uid)
    }
}
