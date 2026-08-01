package com.cloudx.databridge

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * 🏷️ RoleLevelCache — shared, independently-refreshable cache of roles/{roleId}/level.
 *
 * Phase 1 of the dynamic role-hierarchy plan (see RbacManager.kt's comment block above
 * [RbacManager]): replaces EmployeeFragment.ROLE_LEVELS, a hardcoded Kotlin map, with an
 * admin-configurable value set via Access Manager's role form. Mirrors StatusMetaCache's
 * exact refresh/cache shape — admin-config-sized (one read for the whole roles/ node,
 * never per-user or per-consignment), refreshed alongside other loads, keeps the last-good
 * cache on failure instead of clearing it.
 *
 * This is for resolving OTHER roles' levels (e.g. "does this role outrank that one" when
 * deciding who can manage whom). The CURRENT logged-in user's own level is read directly by
 * RbacManager.load() from the single-role snapshot it already fetches — not from here.
 */
object RoleLevelCache {

    // Missing/unset level defaults to this — the LOWEST possible rank, not 0 (0 means
    // admin/highest rank today), so a role with no level set yet never silently grants top
    // access. Matches RbacManager.UserRbacInfo's own default for the same reason.
    const val DEFAULT_LEVEL = 999

    @Volatile
    var levels: Map<String, Int> = emptyMap()
        private set

    suspend fun refresh() {
        try {
            val snap = FirebaseDatabase.getInstance().reference.child("roles").get().await()
            val map = mutableMapOf<String, Int>()
            snap.children.forEach { s ->
                val key = s.key ?: return@forEach
                val level = s.child("level").getValue(Int::class.java)
                    ?: s.child("level").getValue(Long::class.java)?.toInt()
                if (level != null) map[key] = level
            }
            if (map.isNotEmpty()) levels = map
        } catch (_: Exception) {
            // Keep whatever was cached before (or the empty default) — callers fall back gracefully.
        }
    }

    /** [roleId]'s configured level, or DEFAULT_LEVEL (lowest rank) if unset or not yet loaded. */
    fun levelOf(roleId: String): Int = levels[roleId] ?: DEFAULT_LEVEL
}
