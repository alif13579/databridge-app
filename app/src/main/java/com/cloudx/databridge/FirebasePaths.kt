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

    /* ── Merchants (courier-wide directory, used by Petty Cash's Pickup category) ── */
    fun stores() = "courier/stores"
    fun store(storeId: String) = "courier/stores/$storeId"

    /* ── Areas — courier-wide directories, managed from Config's Areas tab
     * (see ConfigAreasFragment). delivery_area is destinations a parcel can
     * be routed to (Virtual Routing's Select Area picker reads this, once
     * wired to real data); pickup_area is zones a pickup run collects from. ── */
    fun deliveryAreas() = "courier/areas/delivery_area"
    fun deliveryArea(areaId: String) = "courier/areas/delivery_area/$areaId"
    fun pickupAreas() = "courier/areas/pickup_area"
    fun pickupArea(areaId: String) = "courier/areas/pickup_area/$areaId"

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

    /* ── Cash Management ───────────────────────────────────────────── */
    // cash_management/{branchId}/collections/{entryId} -> {amount, timestamp, enteredByName, enteredByUid}
    fun cashManagementCollections(branchId: String) = "cash_management/$branchId/collections"
    // cash_management/{branchId}/providers/{providerName} -> true  (which MFS providers this branch uses)
    fun cashManagementProviders(branchId: String) = "cash_management/$branchId/providers"
    // cash_management/{branchId}/ledger/{providerName}/handovers|hub_payments/{entryId}
    fun cashManagementLedger(branchId: String) = "cash_management/$branchId/ledger"
    // cash_management/{branchId}/defaultProvider -> provider name string
    fun cashManagementDefaultProvider(branchId: String) = "cash_management/$branchId/defaultProvider"

    /* ── Petty Cash wallet ─────────────────────────────────────────── */
    // petty_cash/{branchId}/wallet/balance -> Double
    fun pettyCashWalletBalance(branchId: String) = "petty_cash/$branchId/wallet/balance"
    // petty_cash/{branchId}/wallet/deposits/{depositId} -> PettyCashDeposit
    fun pettyCashDeposits(branchId: String) = "petty_cash/$branchId/wallet/deposits"
    // petty_cash_roles/{branchId} — REMOVED: branch role assignment (Team
    // Aligned / Petty Cash POC / Accounts) lives on Branch.kt fields instead
    // (staff_uid, petty_cash_poc_uid, accountant_uid), same as manager_uid.

    /* ── Claims (Petty Cash requests) ────────────────────────────────
     * Claim data is deliberately stored once at claims/{claimId}/info.  The
     * two lookup trees only contain {claimId}: true, so reporting can use a
     * server-side orderByKey()/startAt()/endAt() range without duplicating a
     * complete claim document. */
    fun claims() = "claims"
    fun claim(claimId: String) = "claims/$claimId"
    fun claimInfo(claimId: String) = "claims/$claimId/info"
    fun claimsByBranch(branchId: String) = "claims/indexes/claims_by_branchId/$branchId"
    fun claimsByEmployee(employeeId: String) = "claims/indexes/claims_by_employeeId/$employeeId"

    /* ── Leave Management ─────────────────────────────────────────────
     * Flow: Requester -> any Incharge (branch) acknowledges -> any Shift
     * Lead (branch) approves. No per-branch fixed assignee field — see
     * LEAVE_ACKNOWLEDGER_ROLE_NAME / LEAVE_APPROVER_ROLE_NAME in
     * LeaveModels.kt for the role-based queue this resolves against.
     */
    // leave_management/{branchId}/requests/{requestId} -> LeaveRequest
    fun leaveManagementRequests(branchId: String) = "leave_management/$branchId/requests"
    fun leaveManagementRequest(branchId: String, requestId: String) = "leave_management/$branchId/requests/$requestId"
}
