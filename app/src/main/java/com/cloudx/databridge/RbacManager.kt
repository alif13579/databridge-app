package com.cloudx.databridge

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * 🔐 Role-Based Access Control Manager
 *
 * Firebase structure expected:
 *   users/{uid}/company_info/branch_ids → List<String>
 *   users/{uid}/company_info/role_id    → String
 *   branches/{branch_id}/name           → String
 *   roles/{role_id}/name         → String
 *   roles/{role_id}/permissions/ → Map<String, Boolean>
 *      e.g. nav_dashboard: true, nav_team: false
 *
 * Permission keys match drawer/bottom nav menu item ID names:
 *   nav_dashboard, nav_my_tasks, nav_team, nav_reports,
 *   nav_settings, nav_support, nav_connect, nav_history, nav_space,
 *   nav_scanner, nav_access_manager, nav_memory, nav_salary_manager
 */
object RbacManager {

    data class UserRbacInfo(
        val roleId: String = "",
        val branchName: String = "",
        val roleName: String = "",
        val permissions: Map<String, Boolean> = emptyMap(),
        val branchIds: List<String> = emptyList(),
        val overridePages: List<String> = emptyList(),
        val overrideActive: Boolean = false
    )

    var current: UserRbacInfo = UserRbacInfo()
        private set

    private var cachedGuest: UserRbacInfo? = null
    private var anonInitTried = false

    private val db = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private fun parsePermissions(permNode: DataSnapshot?): Map<String, Boolean> {
        if (permNode == null || !permNode.exists()) return emptyMap()
        val mapPerms: Map<String, Boolean>? = runCatching {
            permNode.getValue(object : com.google.firebase.database.GenericTypeIndicator<Map<String, Boolean>>() {})
        }.getOrNull()
        if (mapPerms != null) return mapPerms

        val listPerms: List<String>? = runCatching {
            permNode.getValue(object : com.google.firebase.database.GenericTypeIndicator<List<String>>() {})
        }.getOrNull()
        if (listPerms != null) return listPerms.associateWith { true }

        val permissions = mutableMapOf<String, Boolean>()
        permNode.children.forEach { child ->
            child.key?.let { key ->
                permissions[key] = child.getValue(Boolean::class.java) ?: false
            }
        }
        return permissions
    }

    /**
     * Loads branch + role + permissions from Firebase for the given uid.
     * Returns the loaded info (also stored in [current]).
     */
    suspend fun load(uid: String): UserRbacInfo {
        return try {
            val profileSnap = db.reference.child("users/$uid/profile").get().await()
            val branchIds = profileSnap.child("company_info/branch_ids").children.mapNotNull { it.getValue(String::class.java) }
            val primaryId = branchIds.firstOrNull().orEmpty()
            var roleIdVal: String? = profileSnap.child("company_info/role_id").getValue(String::class.java)
            if (roleIdVal.isNullOrBlank()) roleIdVal = profileSnap.child("company_info/role").getValue(String::class.java)
            if (roleIdVal.isNullOrBlank()) {
                roleIdVal = runCatching {
                    db.reference.child("users/$uid/role").get().await().getValue(String::class.java)
                }.getOrNull()
            }
            val roleId = roleIdVal?.trim().orEmpty()

            val branchName = if (primaryId.isNotBlank()) {
                runCatching {
                    db.reference.child("branches/$primaryId/name").get().await()
                        .getValue(String::class.java) ?: primaryId
                }.getOrDefault(primaryId)
            } else ""

            val roleSnap = if (roleId.isNotBlank()) {
                runCatching { db.reference.child("roles/$roleId").get().await() }.getOrNull()
            } else null

            val roleName = roleSnap?.child("name")?.getValue(String::class.java).orEmpty()
            val permissions = parsePermissions(roleSnap?.child("permissions"))

            val (overridePages, overrideActive) = runCatching {
                val node = profileSnap.child("company_info/access_overrides/permissions")
                if (!node.exists()) Pair(emptyList<String>(), false) else {
                    val map: Map<String, Boolean>? = node.getValue(object : com.google.firebase.database.GenericTypeIndicator<Map<String, Boolean>>() {})
                    if (map != null) {
                        Pair(map.filterValues { it == true }.keys.toList(), true)
                    } else {
                        // Fallback: legacy list of strings
                        Pair(node.children.mapNotNull { it.getValue(String::class.java) }, true)
                    }
                }
            }.getOrDefault(Pair(emptyList(), false))

            current = UserRbacInfo(roleId, branchName, roleName, permissions, branchIds, overridePages, overrideActive)
            // Also refresh guest cache while authenticated, so logout can use cached guest permissions if rules block unauth reads
            runCatching { primeGuestCache() }
            current
        } catch (_: Exception) {
            current = UserRbacInfo()
            current
        }
    }

    suspend fun loadGuest(): UserRbacInfo {
        return try {
            ensureAnonymousAuth()
            val roleSnap = runCatching { db.reference.child("roles/guest").get().await() }.getOrNull()
            val roleName = roleSnap?.child("name")?.getValue(String::class.java).orEmpty()
            val permissions = parsePermissions(roleSnap?.child("permissions"))
            current = UserRbacInfo(roleId = "guest", roleName = roleName.ifBlank { "Guest" }, permissions = permissions)
            cachedGuest = current
            current
        } catch (_: Exception) {
            cachedGuest?.let {
                current = it
                return it
            }
            current = UserRbacInfo(roleId = "guest", roleName = "Guest")
            current
        }
    }

    suspend fun primeGuestCache() {
        runCatching {
            ensureAnonymousAuth()
            val roleSnap = db.reference.child("roles/guest").get().await()
            val roleName = roleSnap.child("name").getValue(String::class.java).orEmpty()
            val permissions = parsePermissions(roleSnap.child("permissions"))
            cachedGuest = UserRbacInfo(roleId = "guest", roleName = roleName.ifBlank { "Guest" }, permissions = permissions)
        }
    }

    private suspend fun ensureAnonymousAuth() {
        val user = auth.currentUser
        if (user != null && !user.isAnonymous) return
        if (user?.isAnonymous == true) return
        if (anonInitTried) return
        anonInitTried = true
        runCatching { auth.signInAnonymously().await() }
    }

    /**
     * Returns true if the user has the given permission.
     */
    fun hasPermission(key: String): Boolean {
        if (current.overrideActive) return current.overridePages.contains(key)
        if (current.permissions.isNotEmpty()) return current.permissions[key] ?: false
        // Fallback: if role is admin but no permissions are loaded (roles node missing), allow ONLY Access Manager so roles can be created.
        if (current.roleId == "admin" && key == "nav_access_manager") return true
        return false
    }

    /** Call on logout to reset state. */
    fun clear() {
        current = UserRbacInfo()
    }

    // Debug helpers
    fun debugSummary(): String {
        val allowed = if (current.overrideActive) current.overridePages.size else current.permissions.count { it.value }
        return "role=${current.roleId}, overrideActive=${current.overrideActive}, allowedCount=$allowed"
    }

    fun allowedKeys(): List<String> = if (current.overrideActive) current.overridePages else current.permissions.filterValues { it }.keys.toList()
}
