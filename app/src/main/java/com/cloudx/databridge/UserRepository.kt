package com.cloudx.databridge

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

class UserRepository(private val uid: String) {
    private val usersRef = FirebaseDatabase.getInstance().getReference("users/$uid")

    companion object {
        const val PATH_ANDROIDS = "connections/androids"
        const val PATH_EXTENSIONS = "connections/extensions"

        const val STATUS_ACTIVE = "active"
        const val STATUS_AWAY = "away"
        const val STATUS_INACTIVE = "inactive"
    }

    suspend fun saveProfile(profile: UserProfile) = usersRef.child("profile").setValue(profile).await()

    suspend fun saveProfileMerged(profile: UserProfile, phoneNumber: String?, existingCreatedAt: Long? = null, existingLastActive: Long? = null) {
        val createdAtFinal = existingCreatedAt ?: profile.createdAt
        val lastActiveFinal = existingLastActive ?: profile.lastActive
        val data = mutableMapOf<String, Any?>(
            "name" to profile.name,
            "email" to profile.email,
            "containerId" to profile.containerId,
            "user_id" to profile.user_id,
            "photo_url" to profile.photo_url,
            "createdAt" to createdAtFinal,
            "lastActive" to lastActiveFinal,
            "company_info" to mapOf(
                "role_id" to profile.company_info.role_id,
                "branch_ids" to profile.company_info.branch_ids,
                "employee_id" to profile.company_info.employee_id,
                "designation" to profile.company_info.designation,
                "agent_type" to profile.company_info.agent_type,
                "salary_model" to profile.company_info.salary_model,
                "salary_type" to profile.company_info.salary_type,
                "fixed_amount" to profile.company_info.fixed_amount,
                "status" to profile.company_info.status
            )
        )
        if (!phoneNumber.isNullOrBlank()) data["phone_number"] = phoneNumber
        usersRef.child("profile").updateChildren(data).await()
    }

    suspend fun saveAndroidConnection(androidId: String, model: String) {
        val now = System.currentTimeMillis()
        usersRef.child("$PATH_ANDROIDS/$androidId").setValue(
            mapOf(
                "status" to STATUS_ACTIVE,
                "model" to model,
                "created_at" to now,
                "last_active" to now
            )
        ).await()
    }

    suspend fun removeAndroidConnection(androidId: String) =
        usersRef.child("$PATH_ANDROIDS/$androidId").removeValue().await()

    suspend fun saveExtensionConnection(extensionId: String, type: String, androidId: String) {
        val now = System.currentTimeMillis()
        usersRef.child("$PATH_EXTENSIONS/$extensionId").setValue(
            mapOf(
                "status" to "connected",
                "type" to type,
                "android_id" to androidId,
                "connected_at" to now,
                "last_sync" to now
            )
        ).await()
    }

    suspend fun removeExtensionConnection(extensionId: String) =
        usersRef.child("$PATH_EXTENSIONS/$extensionId").removeValue().await()

    suspend fun updateLastActive() = usersRef.child("profile/lastActive").setValue(System.currentTimeMillis()).await()

    suspend fun getConnectedExtensionIds(): List<String> {
        val snap = usersRef.child(PATH_EXTENSIONS).get().await()
        return snap.children.mapNotNull { child ->
            child.key?.takeIf { child.child("status").getValue(String::class.java) == "connected" }
        }
    }

    fun getContainerId(): String = "container_$uid"
}
