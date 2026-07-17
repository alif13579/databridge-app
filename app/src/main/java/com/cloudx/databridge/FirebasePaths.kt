package com.cloudx.databridge

/**
 * 🔹 Centralized Firebase Realtime Database path constants.
 * ✅ Eliminates scattered hardcoded strings across fragments
 * ✅ Reduces typo risk & makes path changes trivial
 * ✅ Easy to unit-test path generation logic
 */
object FirebasePaths {

    /* ── Users ─────────────────────────────────────────────────────── */
    fun userProfile(uid: String) = "users/$uid/profile"
    fun userCompanyInfo(uid: String) = "users/$uid/company_info"
    fun userRole(uid: String) = "users/$uid/role"
    fun userRunRoutes(uid: String) = "users/$uid/run-routes"
    fun userConnectionsAndroid(uid: String) = "users/$uid/connections/androids"
    fun userConnectionsExtension(uid: String) = "users/$uid/connections/extensions"

    /* ── Roles ─────────────────────────────────────────────────────── */
    fun role(roleId: String) = "roles/$roleId"
    fun rolePermissions(roleId: String) = "roles/$roleId/permissions"

    /* ── Branches ──────────────────────────────────────────────────── */
    fun branch(branchId: String) = "branches/$branchId"
    fun branchName(branchId: String) = "branches/$branchId/name"

    /* ── Config ────────────────────────────────────────────────────── */
    fun configRemarks(statusKey: String) = "config/remarks/$statusKey"
    fun configLanguageWorker() = "config/language/workerLang"
    fun configLanguageCallCenter() = "config/language/ccLang"
    fun configStatusMeta(key: String) = "config/statusMeta/$key"
    fun configSheet(branchId: String) = "config/sheets/$branchId"
    fun configSheetCurrent(branchId: String) = "config/sheets/$branchId/current"
    fun configSheetHistory(branchId: String) = "config/sheets/$branchId/history"
    fun configSheetDataRows(branchId: String) = "config/sheets/$branchId/data/rows"

    /* ── Courier / Run Routes ──────────────────────────────────────── */
    fun runRoutes(runType: String, runId: String) = "courier/run_routes/$runType/$runId"
    fun runRoutesConsignments(runType: String, runId: String) = "courier/run_routes/$runType/$runId/consignments"
    fun runRoutesStatus(runType: String, runId: String) = "courier/run_routes/$runType/$runId/status"
    fun runRoutesCreatedAt(runType: String, runId: String) = "courier/run_routes/$runType/$runId/created_at"

    /* ── Run Routes by Agent ───────────────────────────────────────── */
    fun runsByAgent(systemId: String) = "courier/runs_by_agent/$systemId"

    /* ── Error Logs ────────────────────────────────────────────────── */
    fun errorLogs(uid: String) = "error_logs/$uid"

    /* ── Sessions / Extensions ─────────────────────────────────────── */
    fun session(extId: String) = "sessions/$extId"
    fun sessionRecords(extId: String) = "sessions/$extId/records"

    /* ── Container ─────────────────────────────────────────────────── */
    fun containerRecords(uid: String) = "container/container_$uid/records"

    /* ── Valid Remarks ─────────────────────────────────────────────── */
    fun validRemarks() = "valid_remarks"
    fun remarksOptions() = "remarks_options"

    /* ── Number Entries ────────────────────────────────────────────── */
    fun numberEntries(phone: String) = "number_entries/$phone"
}
