package com.cloudx.databridge

/**
 * 🧭 DataRouteManager.kt
 * ✅ নির্ধারণ করে: কোথা থেকে ফেচ করবে, কোথায় রাইট/ক্রিয়েট করবে, মাইগ্রেশন প্রয়োজন কিনা
 */
object DataRouteManager {
    enum class DataLifecycle { EPHEMERAL_SESSION, AUTHENTICATED_PERSISTENT }

    data class RouteInfo(
        val basePath: String,
        val recordPath: String,
        val actionsPath: String,
        val recordId: String,
        val lifecycle: DataLifecycle
    ) {
        fun getWritePathForAction(actionId: String) = "$actionsPath/$actionId"
        fun getWritePathForNumbers(cleanPhone: String, actionId: String) = "numbers/$cleanPhone/$actionId"
    }

    fun resolveForNewData(authUid: String?, extId: String?): RouteInfo? {
        return when {
            authUid != null -> RouteInfo(
                basePath = "container/container_$authUid",
                recordPath = "container/container_$authUid/records",
                actionsPath = "container/container_$authUid/records",
                recordId = "",
                lifecycle = DataLifecycle.AUTHENTICATED_PERSISTENT
            )
            !extId.isNullOrEmpty() -> RouteInfo(
                basePath = "sessions/$extId",
                recordPath = "sessions/$extId/records",
                actionsPath = "sessions/$extId/records",
                recordId = "",
                lifecycle = DataLifecycle.EPHEMERAL_SESSION
            )
            else -> null
        }
    }

    fun resolveForExistingRecord(record: CallRecord, authUid: String?, extId: String?): RouteInfo? {
        return when (record.source) {
            "container" -> {
                val cid = record.container_id ?: "container_$authUid"
                RouteInfo("container/$cid", "container/$cid/records/${record.id}",
                    "container/$cid/records/${record.id}/actions", record.id, DataLifecycle.AUTHENTICATED_PERSISTENT)
            }
            "session" -> {
                val eid = record.extension_id ?: extId
                if (eid.isNullOrEmpty()) return null
                RouteInfo("sessions/$eid", "sessions/$eid/records/${record.id}",
                    "sessions/$eid/records/${record.id}/actions", record.id, DataLifecycle.EPHEMERAL_SESSION)
            }
            else -> resolveForNewData(authUid, extId)?.copy(recordId = record.id)
        }
    }

    fun requiresMigration(record: CallRecord, authUid: String?): Boolean = authUid != null && record.source == "session"
}