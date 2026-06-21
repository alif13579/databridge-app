package com.cloudx.databridge

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

object FirebaseActionSync {
    private const val TAG = "FirebaseActionSync"
    private val db = FirebaseDatabase.getInstance()

    suspend fun saveAction(
        record: CallRecord,
        uid: String?,
        extId: String?,
        actionId: String,
        actionType: String,
        remarks: String,
        source: String = "app"
    ) {
        val route = DataRouteManager.resolveForExistingRecord(record, uid, extId) ?: return
        val timestamp = System.currentTimeMillis()
        val actionData = mapOf(
            "remarks" to remarks,
            "timestamp" to timestamp,
            "type" to actionType,
            "source" to source
        )
        db.reference.child(route.getWritePathForAction(actionId)).setValue(actionData).await()
        syncNumbersIndex(record, route, actionId, remarks, timestamp, source, actionType)
        Log.d(TAG, "Action saved: $actionId on ${record.id}")
    }

    suspend fun updateAction(
        record: CallRecord,
        uid: String?,
        extId: String?,
        actionId: String,
        remarks: String,
        actionType: String? = null
    ) {
        val route = DataRouteManager.resolveForExistingRecord(record, uid, extId) ?: return
        val path = route.getWritePathForAction(actionId)
        val snap = db.reference.child(path).get().await()
        if (!snap.exists()) return
        val updates = mutableMapOf<String, Any>(
            "remarks" to remarks,
            "timestamp" to System.currentTimeMillis()
        )
        if (actionType != null) updates["type"] = actionType
        db.reference.child(path).updateChildren(updates).await()
        val type = actionType ?: snap.child("type").getValue(String::class.java) ?: ""
        syncNumbersIndex(record, route, actionId, remarks, System.currentTimeMillis(), "app", type)
    }

    suspend fun deleteAction(
        record: CallRecord,
        uid: String?,
        extId: String?,
        actionId: String
    ) {
        val route = DataRouteManager.resolveForExistingRecord(record, uid, extId) ?: return
        val path = route.getWritePathForAction(actionId)
        val snap = db.reference.child(path).get().await()
        val actionType = snap.child("type").getValue(String::class.java) ?: ""
        db.reference.child(path).removeValue().await()
        if (shouldIndexInNumbers(record, actionType)) {
            removeNumbersIndex(record, actionId)
        }
        Log.d(TAG, "Action deleted: $actionId")
    }

    private fun shouldIndexInNumbers(record: CallRecord, actionType: String): Boolean {
        if (record.type != "phone" || actionType != "remark") return false
        return IdUtils.cleanPhoneNumber(record.cleaned).length >= 7
    }

    private suspend fun removeNumbersIndex(record: CallRecord, actionId: String) {
        val cleanPhone = IdUtils.cleanPhoneNumber(record.cleaned)
        if (cleanPhone.length < 7) return
        try {
            db.reference.child("numbers/$cleanPhone/$actionId").removeValue().await()
        } catch (_: Exception) { }
    }

    private suspend fun syncNumbersIndex(
        record: CallRecord,
        route: DataRouteManager.RouteInfo,
        actionId: String,
        remarks: String,
        timestamp: Long,
        source: String,
        actionType: String
    ) {
        if (!shouldIndexInNumbers(record, actionType)) {
            removeNumbersIndex(record, actionId)
            return
        }
        val cleanPhone = IdUtils.cleanPhoneNumber(record.cleaned)
        val storageRef = when (route.lifecycle) {
            DataRouteManager.DataLifecycle.AUTHENTICATED_PERSISTENT ->
                record.container_id ?: route.basePath.removePrefix("container/")
            DataRouteManager.DataLifecycle.EPHEMERAL_SESSION ->
                record.extension_id ?: route.basePath.removePrefix("sessions/")
        }
        val numberData = mapOf(
            "record_id" to record.id,
            "storage_ref" to storageRef,
            "lifecycle" to route.lifecycle.name,
            "timestamp" to timestamp,
            "remarks" to remarks,
            "source" to source
        )
        db.reference.child(route.getWritePathForNumbers(cleanPhone, actionId)).setValue(numberData).await()
    }
}
