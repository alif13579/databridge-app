package com.cloudx.databridge

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * One-time Firebase admin utility for the old claims_by_employeeId index —
 * NOT part of the live claim flow (ClaimsRepository is Supabase-only).
 *
 * Backfills claims_by_employeeId (old, space-unsafe key) to
 * claims_by_systemId, and can delete the old index once spot-checked.
 * Kept isolated in this Firebase-only object so the live Petty Cash path
 * has zero Firebase references; the admin entry points live in
 * ClaimsReportFragment (remove those UI hooks once the migration has been
 * run and spot-checked).
 */
object FirebaseClaimsIndexMigration {

    private val db: FirebaseDatabase get() = FirebaseDatabase.getInstance()

    /** One-time backfill: claims_by_employeeId (old, space-unsafe key) -> claims_by_systemId.
     *  dryRun=true writes nothing and only reports what *would* happen — run this first.
     *  Idempotent: safe to re-run (e.g. after a partial/interrupted run) since it always
     *  re-derives the same mapping and re-writes the same values.
     *  The old claims_by_employeeId index is left untouched either way; deleting it is a
     *  separate, deliberate step once the new index has been spot-checked. */
    suspend fun migrateEmployeeIndexToSystemId(dryRun: Boolean): EmployeeIndexMigrationResult {
        val usersSnap = db.reference.child("users").get().await()
        val employeeIdToSystemId = mutableMapOf<String, String>()
        usersSnap.children.forEach { u ->
            val info = u.child("profile/company_info")
            val empId = info.child("employee_id").getValue(String::class.java)?.trim().orEmpty()
            val sysId = info.child("system_id").getValue(String::class.java)?.trim().orEmpty()
            if (empId.isNotBlank() && sysId.isNotBlank()) employeeIdToSystemId[empId] = sysId
        }

        val oldIndexSnap = db.reference.child("claims/indexes/claims_by_employeeId").get().await()
        val updates = mutableMapOf<String, Any?>()
        var matched = 0
        val unresolved = mutableListOf<Pair<String, String>>()
        oldIndexSnap.children.forEach { employeeGroup ->
            val oldEmployeeId = employeeGroup.key.orEmpty()
            val systemId = employeeIdToSystemId[oldEmployeeId]
            employeeGroup.children.forEach { claimEntry ->
                val claimId = claimEntry.key.orEmpty()
                if (systemId.isNullOrBlank()) {
                    unresolved += oldEmployeeId to claimId
                    return@forEach
                }
                matched++
                if (!dryRun) {
                    updates["claims/$claimId/info/agentSystemId"] = systemId
                    updates["${FirebasePaths.claimsBySystemId(systemId)}/$claimId"] = true
                }
            }
        }
        if (!dryRun && updates.isNotEmpty()) db.reference.updateChildren(updates).await()
        return EmployeeIndexMigrationResult(dryRun, matched, unresolved)
    }

    /** Deletes the old claims_by_employeeId index outright. Deliberately a separate call from
     *  migrateEmployeeIndexToSystemId — bundling delete into the migration would mean a bug in
     *  the migration's mapping has no way back. Call this only after spot-checking the new
     *  claims_by_systemId index looks right. */
    suspend fun deleteOldEmployeeIndex() {
        db.reference.child("claims/indexes/claims_by_employeeId").removeValue().await()
    }
}
