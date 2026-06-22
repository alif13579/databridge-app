package com.cloudx.databridge

/**
 * Run routes — canonical IDs and Firebase paths.
 *
 * Run ID format: `run_{timestampMs}` (see [IdUtils.generateRunId]).
 *
 * ```
 * users/{uid}/run-routes/run_1716543210000/
 *   runid, created_at, status: open|close
 *
 * run-routes/run_1716543210000/
 *   runid, created_at
 *   {consignment_id}/ consignment_id (same value), phone, status
 * ```
 *
 * Firebase index (rules): `users/$uid/run-routes` → `.indexOn: ["created_at"]`
 */
object RunRoutePaths {
    fun userRun(uid: String, runId: String) = "users/$uid/run-routes/$runId"
    fun runDetails(runId: String) = "run-routes/$runId"
    fun scannedRoot() = "run-routes/scanned"
    fun userScans(uid: String) = "${scannedRoot()}/${uid.trim()}"
    fun scanItem(uid: String, scanKey: String) = "${userScans(uid)}/${scanKey.trim()}"

    fun newRunId(atMs: Long = System.currentTimeMillis()): String = IdUtils.generateRunId(atMs)

    fun assignmentMap(runId: String, status: String = "open"): Map<String, Any> {
        val createdAt = requireRunTimestamp(runId)
        return mapOf(
            "runid" to runId.trim(),
            "created_at" to createdAt,
            "status" to status
        )
    }

    fun runHeaderMap(runId: String): Map<String, Any> {
        val createdAt = requireRunTimestamp(runId)
        return mapOf(
            "runid" to runId.trim(),
            "created_at" to createdAt
        )
    }

    private fun requireRunTimestamp(runId: String): Long =
        IdUtils.parseRunTimestampMs(runId)
            ?: throw IllegalArgumentException("Run ID must be run_{timestampMs}, got: $runId")

    fun consignmentRef(runId: String, consignmentId: String) =
        "run-routes/$runId/${consignmentId.trim()}"

    /** Key = consignment_id; ভিতরে একই মানের `consignment_id` field। */
    fun consignmentMap(consignmentId: String, phone: String, status: String): Map<String, Any> {
        val id = consignmentId.trim()
        require(id.isNotBlank()) { "consignment_id is required" }
        return mapOf(
            "consignment_id" to id,
            "phone" to phone.trim(),
            "status" to status.trim()
        )
    }
}
