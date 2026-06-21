package com.cloudx.databridge

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

data class SpaceNumberEntry(
    val actionId: String,
    val remarks: String,
    val timestamp: Long
)

/** Worker assignment under users/{uid}/run-routes/{run_id} */
data class UserRunAssignment(
    val runId: String,
    val runid: String,
    val createdAt: Long,
    val status: String // "open" | "close"
)

data class SpaceConsignmentItem(
    val runId: String,
    val consignmentId: String,
    val phone: String,
    val cleanPhone: String,
    val status: String,
    val runStatus: String,
    val runCreatedAt: Long,
    val numberEntries: List<SpaceNumberEntry>,
    val isValid: Boolean
)

object SpaceRepository {
    private val db = FirebaseDatabase.getInstance()
    /** Metadata keys on run-routes/{run_id} — not consignment children */
    private val runRouteMetaKeys = setOf("runid", "created_at", "status")

    suspend fun loadValidRemarks(): Set<String> {
        val fromValid = runCatching {
            db.reference.child("valid_remarks").get().await()
        }.getOrNull()?.let { snap ->
            snap.children.mapNotNull { child ->
                child.getValue(String::class.java)?.trim()
                    ?: child.key?.trim()
            }.filter { it.isNotBlank() }
        }.orEmpty()

        if (fromValid.isNotEmpty()) {
            return fromValid.map { normalizeRemark(it) }.toSet()
        }

        val options = runCatching {
            db.reference.child("remarks_options").get().await()
        }.getOrNull()

        val fromOptions = options?.children?.mapNotNull { child ->
            when (val v = child.value) {
                is String -> v
                is Map<*, *> -> (v["label"] as? String) ?: (v["text"] as? String)
                else -> child.key
            }
        }?.filter { it.isNotBlank() }.orEmpty()

        return fromOptions.map { normalizeRemark(it) }.toSet()
    }

    suspend fun loadConsignments(
        uid: String,
        validRemarks: Set<String>,
        dayStartMs: Long
    ): List<SpaceConsignmentItem> {
        val assignments = loadAssignmentsForDay(uid, dayStartMs)

        val result = mutableListOf<SpaceConsignmentItem>()

        for (assignment in assignments) {
            val runId = assignment.runId
            val runSnap = runCatching {
                db.reference.child("run-routes/$runId").get().await()
            }.getOrNull() ?: continue

            val createdAt = resolveRunCreatedAt(assignment, runSnap)

            for (child in runSnap.children) {
                val key = child.key ?: continue
                if (key.lowercase() in runRouteMetaKeys) continue
                if (!child.hasChild("phone")) continue

                val consignmentId = resolveConsignmentId(key, child) ?: continue
                val phone = child.child("phone").getValue(String::class.java)?.trim().orEmpty()
                if (phone.isBlank()) continue

                val consignmentStatus = child.child("status").getValue(String::class.java)?.trim().orEmpty()
                val cleanPhone = IdUtils.cleanPhoneNumber(phone)
                val entries = if (cleanPhone.length >= 7) {
                    loadNumberEntries(cleanPhone)
                } else {
                    emptyList()
                }
                val isValid = entries.any { validRemarks.contains(normalizeRemark(it.remarks)) }

                result.add(
                    SpaceConsignmentItem(
                        runId = runId,
                        consignmentId = consignmentId,
                        phone = phone,
                        cleanPhone = cleanPhone,
                        status = consignmentStatus,
                        runStatus = assignment.status,
                        runCreatedAt = createdAt,
                        numberEntries = entries,
                        isValid = isValid
                    )
                )
            }
        }

        return result.sortedByDescending { it.runCreatedAt }
    }

    private suspend fun loadAssignmentsForDay(uid: String, dayStartMs: Long): List<UserRunAssignment> {
        val dayEndMs = endOfLocalDay(dayStartMs)
        val ref = db.reference.child("users/$uid/run-routes")

        val snap = runCatching {
            ref.orderByChild("created_at")
                .startAt(dayStartMs.toDouble())
                .endAt(dayEndMs.toDouble())
                .get()
                .await()
        }.getOrNull() ?: return emptyList()

        return snap.children.mapNotNull { parseUserRunAssignment(it) }
    }

    private fun resolveRunCreatedAt(assignment: UserRunAssignment, runSnap: DataSnapshot?): Long {
        return assignment.createdAt.takeIf { it > 0L }
            ?: IdUtils.parseRunTimestampMs(assignment.runId)
            ?: runSnap?.child("created_at")?.getValue(Long::class.java)
            ?: 0L
    }

    /** Key এবং `consignment_id` field একই হতে হবে। */
    private fun resolveConsignmentId(key: String, snap: DataSnapshot): String? {
        val keyId = key.trim()
        if (keyId.isBlank()) return null
        val fieldId = snap.child("consignment_id").getValue(String::class.java)?.trim().orEmpty()
            .ifBlank { snap.child("consignmentid").getValue(String::class.java)?.trim().orEmpty() }
            .ifBlank { snap.child("consignmentId").getValue(String::class.java)?.trim().orEmpty() }
        if (fieldId.isBlank() || keyId != fieldId) return null
        return keyId
    }

    private fun parseUserRunAssignment(snap: DataSnapshot): UserRunAssignment? {
        val runId = snap.key?.trim().orEmpty()
        if (!IdUtils.isValidRunId(runId)) return null
        if (snap.value !is Map<*, *>) return null

        val runid = snap.child("runid").getValue(String::class.java)?.trim().orEmpty().ifBlank { runId }
        if (!IdUtils.isValidRunId(runid)) return null

        val createdAt = snap.child("created_at").getValue(Long::class.java)
            ?: IdUtils.parseRunTimestampMs(runId)
            ?: return null

        val status = snap.child("status").getValue(String::class.java)?.trim()?.lowercase() ?: "open"

        return UserRunAssignment(
            runId = runId,
            runid = runid,
            createdAt = createdAt,
            status = status
        )
    }

    private suspend fun loadNumberEntries(cleanPhone: String): List<SpaceNumberEntry> {
        val snap = runCatching {
            db.reference.child("numbers/$cleanPhone").get().await()
        }.getOrNull() ?: return emptyList()

        return snap.children.mapNotNull { child ->
            val actionId = child.key ?: return@mapNotNull null
            SpaceNumberEntry(
                actionId = actionId,
                remarks = child.child("remarks").getValue(String::class.java)?.trim().orEmpty(),
                timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L
            )
        }.sortedByDescending { it.timestamp }
    }

    fun normalizeRemark(text: String): String = text.trim().lowercase()

    fun startOfLocalDay(anchorMs: Long = System.currentTimeMillis()): Long {
        return java.util.Calendar.getInstance().apply {
            timeInMillis = anchorMs
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun endOfLocalDay(dayStartMs: Long): Long {
        return java.util.Calendar.getInstance().apply {
            timeInMillis = dayStartMs
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 59)
            set(java.util.Calendar.SECOND, 59)
            set(java.util.Calendar.MILLISECOND, 999)
        }.timeInMillis
    }
}
