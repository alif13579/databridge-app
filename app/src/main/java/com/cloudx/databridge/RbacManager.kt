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
/**
 * ═══════════════════════════════════════════════════════════════════════
 * 🚧 PLANNED, NOT YET BUILT: dynamic, arbitrary-depth role hierarchy
 * ═══════════════════════════════════════════════════════════════════════
 *
 * GOAL (app owner's stated intent, kept close to verbatim): every person
 * should be able to see their own subordinates' data — individually OR
 * grouped, whichever they want — with NO hardcoded role names and NO
 * fixed number of hierarchy levels anywhere in code. Adding a brand-new
 * role (e.g. "incharge") and slotting it into the hierarchy must be
 * possible from the Access Manager screen alone, zero code changes or
 * app rebuild. 100% dynamic / future-proof is the actual bar — not just
 * "make incharge work as a one-off."
 *
 * CURRENT STATE (why this isn't dynamic today):
 *   - Hierarchy rank is EmployeeFragment.ROLE_LEVELS — a hardcoded Kotlin
 *     map (admin=0 ... guest=5, lower = higher rank). Adding a role means
 *     editing this map and shipping a new app build.
 *   - "Who can see whom" is decided by hardcoded role-name checks in two
 *     places that don't share logic:
 *       - EmployeeFragment.canManageRole() / manageableRoleIds() — reads
 *         ROLE_LEVELS directly, myLevel < targetLevel.
 *       - The old dashboard's stat-loading logic (removed along with
 *         DashboardViewModel) hardcoded `roleId == "worker"` for
 *         self-only view and `roleId == "manager" && rollupMode` for a
 *         2-level supervisor rollup; everything else falls into
 *         loadWorkerAgentStats(), which only ever resolves role_id ==
 *         "worker" candidates — i.e. it assumes exactly 2 levels
 *         (worker, and everyone above worker), not N levels.
 *   - No person-to-person "reports_to" link exists anywhere (checked —
 *     grep for reports_to/manager_id/supervisor_id across the whole
 *     codebase returns nothing). Visibility today is branch_ids
 *     membership + rank comparison, not an explicit assignment, and
 *     that's staying true going forward too — an explicit reporting-
 *     chain field would be a separate, much bigger undertaking and
 *     isn't what's being asked for; branch+rank is the existing,
 *     working mechanism, it just needs to stop being hardcoded.
 *
 * ─── PHASE 1 — make level itself dynamic (foundation for everything else) ───
 *   1. New Firebase field: roles/{roleId}/level (Int). Lower = higher
 *      rank — same convention ROLE_LEVELS already uses, so the mental
 *      model stays consistent even though the storage location moves.
 *   2. AccessManagerFragment already has full role create/edit/delete UI
 *      (its role-save code, ~lines 645-700) — add ONE number input for
 *      level to that existing form. This is what makes level admin-
 *      configurable with no code change: creating "incharge" and giving
 *      it level=75 becomes a normal admin action, not a redeploy.
 *   3. RbacManager.UserRbacInfo gets a new `level: Int` field, default
 *      a safe LOWEST possible rank (e.g. 999) — NOT 0, since 0 currently
 *      means admin/highest rank, so an unresolved/missing level must
 *      never silently grant top access. RbacManager.load() reads
 *      roles/$roleId/level alongside name/permissions — same roleSnap
 *      fetch already happening around line 92, no extra read needed.
 *   4. EmployeeFragment.ROLE_LEVELS hardcoded map gets removed.
 *      canManageRole() / manageableRoleIds() switch to
 *      RbacManager.current.level plus a small levels-by-roleId cache —
 *      mirror StatusMetaCache.kt's exact pattern (refresh() fetches all
 *      of roles/ once, admin-config-sized, never per-user or per-
 *      consignment; entries exposed as a Map<String, Int>; refreshed the
 *      same "call .refresh() alongside other loads, keep last-good cache
 *      on failure" way StatusMetaCache already does). Copy that pattern,
 *      don't reinvent it.
 *
 *   ⚠️ CRITICAL SAFETY GAP, MUST BE RESOLVED BEFORE STEP 4 SHIPS: this is
 *   an access-control change, not a business-data one — the "no backfill,
 *   fix going forward" precedent used elsewhere in this codebase (ddMMyy,
 *   the remarks_by_userId/users_by_consignment courier/ path move) does
 *   NOT safely apply here. Right now none of the 6 existing roles
 *   (admin/manager/supervisor/stuff/worker/guest) have roles/{roleId}/level
 *   set in Firebase. If step 4 ships and reads ONLY from Firebase with a
 *   "default to lowest rank when missing" fallback (as step 3 says), every
 *   existing admin would silently drop to lowest access the moment this
 *   ships, until someone manually sets level for all 6 roles — a real
 *   lockout, not a cosmetic bug.
 *
 *   Proposed fix (STILL OPEN — not yet decided with the app owner, ask
 *   before implementing step 4): keep a small, code-level fallback map for
 *   ONLY those exact 6 known role names, holding today's ROLE_LEVELS
 *   values, used ONLY when roles/{roleId}/level is unset in Firebase for
 *   that specific role. A brand-new role (e.g. "incharge") has no entry in
 *   this fallback — its level MUST come from the Access Manager form,
 *   which is the actual dynamic part. Existing roles keep working
 *   unchanged; an admin can later re-save any of the 6 in Access Manager
 *   to give it a real Firebase-stored level, at which point the fallback
 *   for that specific role stops mattering. The alternative — skip the
 *   code fallback, have the app owner set roles/{roleId}/level for all 6
 *   directly in Firebase Console before step 4 ships — was also offered
 *   and is still on the table; whichever is chosen, step 4 must not ship
 *   without one of these two in place first.
 *
 * ─── PHASE 2 — generic "who is my subordinate" rule ───
 *   Replace every hardcoded role-name branch (the old dashboard's former
 *   `roleId == "worker"` / `roleId == "manager"` checks, and anywhere else a
 *   role name currently decides visibility — audit for this, there may
 *   be more than the two call sites already found) with ONE generic
 *   rule, computed from the same levels cache Phase 1 builds:
 *
 *     subordinatePool(viewer) = every user where
 *       theirLevel > viewer.level  AND  branch_ids intersects viewer's
 *       (viewer's own uid excluded from their own pool)
 *
 *   Must work for however many levels of hierarchy exist — do NOT
 *   hardcode "exactly 2 levels" the way loadWorkerAgentStats()/
 *   loadSupervisorRollups() do today (they only handle worker <->
 *   {supervisor, stuff} <-> manager, nothing deeper). Unlimited, unnamed
 *   levels in code is the whole point of this phase.
 *
 * ─── PHASE 3 — flexible viewing: flat, grouped, or drill-down (app
 *      owner's explicit answer: "whichever way I want — individual or
 *      group") ───
 *   - FLAT: subordinatePool(viewer), one row per person, regardless of
 *     how many levels separate them from viewer. Generalizes
 *     loadWorkerAgentStats() — same bounded-read-per-candidate shape,
 *     candidates = subordinatePool(viewer) instead of "role_id ==
 *     worker" users.
 *   - GROUPED: one row per person at viewer.level's NEXT level down
 *     only, each row an aggregate of THAT person's own subordinatePool
 *     (recursively — their subordinates' subordinates, all the way
 *     down, not just their direct level). Generalizes
 *     loadSupervisorRollups() from hardcoded 2 levels to N — the
 *     aggregation math (summing delivered/onHold/returned/pending/
 *     earnings/openRuns/closedRuns across matched people) already works
 *     for any group size; it just needs subordinatePool() recursion
 *     instead of a fixed worker-role filter.
 *   - DRILL-DOWN: tapping a GROUPED row re-opens the same flat/grouped
 *     choice, scoped to THAT person as the new viewer — explore
 *     arbitrary depth without a dedicated screen per level. A UI/
 *     navigation feature on top of the same subordinatePool() primitive,
 *     not new data-loading logic.
 *   Keep today's rollupMode default (grouped) as the initial view, but
 *   make BOTH flat and grouped always available to ANYONE with at least
 *   one subordinate — not role-gated the way rollupMode only matters for
 *   "manager" today.
 *
 * ─── CONSTRAINTS TO CARRY THROUGH EVERY PHASE (established elsewhere in
 *      this codebase — don't relitigate these) ───
 *   - Every read stays bounded (per-candidate, parallel via async/
 *     awaitAll — never a company-wide scan). This is THE reason
 *     courier/remarks_by_userId, courier/users_by_consignment, and the
 *     eventual removal of DashboardViewModel exist — the old loadBranchView()/
 *     loadWorkerView() were removed specifically for violating this.
 *     Whatever subordinatePool() resolves to, each person's stat load
 *     stays its own bounded courier/remarks_by_userId/{uid} read, same
 *     as today.
 *   - Firebase reads that can fail (permission, network) get
 *     runCatching{}.onFailure { FirebaseErrorLogger.log(...) }.getOrNull()
 *     — the pattern the old dashboard's per-agent stat loader used before
 *     it (and the rest of DashboardViewModel) was removed —
 *     not a silent getOrNull() with no trace.
 *   - No backfill of existing data for anything in this plan unless
 *     explicitly asked — matches the ddMMyy format fix and the
 *     remarks_by_userId/users_by_consignment courier/ path-prefix move
 *     earlier: fix the mechanism going forward, don't migrate history
 *     unless told to.
 *   - Lower level number = higher rank, consistently, everywhere new
 *     code touches level — the existing ROLE_LEVELS convention, don't
 *     flip it.
 *
 * NONE OF THIS IS BUILT YET as of this comment. If you're picking this
 * up cold: start at Phase 1 step 1 (add roles/{roleId}/level to Firebase
 * and to AccessManagerFragment's role form) — every later phase depends
 * on level actually being readable from Firebase before it can stop
 * being hardcoded anywhere else.
 */
object RbacManager {

    data class UserRbacInfo(
        val roleId: String = "",
        val branchName: String = "",
        val roleName: String = "",
        val permissions: Map<String, Boolean> = emptyMap(),
        val branchIds: List<String> = emptyList(),
        val overridePages: List<String> = emptyList(),
        val overrideActive: Boolean = false,
        // Lower = higher rank (0 = admin today) — same convention EmployeeFragment.
        // ROLE_LEVELS used. Defaults to RoleLevelCache.DEFAULT_LEVEL (lowest possible rank),
        // never 0, so a role whose roles/{roleId}/level isn't set in Firebase yet never
        // silently gets top access.
        val level: Int = RoleLevelCache.DEFAULT_LEVEL,
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

            // Branch directory lives in Supabase now (Firebase branches/ was
            // deleted after migration) — resolve the display name there,
            // falling back to the raw id exactly like before.
            val branchName = if (primaryId.isNotBlank()) {
                runCatching {
                    SupabaseBranchReader.getBranch(primaryId).name.ifBlank { primaryId }
                }.getOrDefault(primaryId)
            } else ""

            val roleSnap = if (roleId.isNotBlank()) {
                runCatching { db.reference.child("roles/$roleId").get().await() }.getOrNull()
            } else null

            val roleName = roleSnap?.child("name")?.getValue(String::class.java).orEmpty()
            val permissions = parsePermissions(roleSnap?.child("permissions"))
            val level = roleSnap?.child("level")?.getValue(Int::class.java)
                ?: roleSnap?.child("level")?.getValue(Long::class.java)?.toInt()
                ?: RoleLevelCache.DEFAULT_LEVEL

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

            current = UserRbacInfo(roleId, branchName, roleName, permissions, branchIds, overridePages, overrideActive, level)
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
            val level = roleSnap?.child("level")?.getValue(Int::class.java)
                ?: roleSnap?.child("level")?.getValue(Long::class.java)?.toInt()
                ?: RoleLevelCache.DEFAULT_LEVEL
            current = UserRbacInfo(roleId = "guest", roleName = roleName.ifBlank { "Guest" }, permissions = permissions, level = level)
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
