package com.cloudx.databridge

import android.util.Log
import com.google.firebase.database.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UnifiedHistoryFetcher {

    private val _records = MutableStateFlow<List<CallRecord>>(emptyList())
    val recordsFlow: StateFlow<List<CallRecord>> = _records.asStateFlow()

    private var containerListener: ValueEventListener? = null
    private var extensionsListener: ValueEventListener? = null
    private val sessionListeners = mutableMapOf<String, ValueEventListener>()

    private val containerCache = mutableMapOf<String, CallRecord>()
    private val sessionsCache = mutableMapOf<String, MutableMap<String, CallRecord>>()

    companion object {
        private const val TAG = "HistoryFetcher"
    }

    fun startFetching(uid: String?, extId: String?) {
        stopFetching()
        if (uid.isNullOrEmpty()) {
            if (!extId.isNullOrEmpty()) listenToSession(extId)
        } else {
            listenToContainer(uid)
            listenToConnectedExtensions(uid)
            if (!extId.isNullOrEmpty()) listenToSession(extId)
        }
    }

    fun stopFetching() {
        containerListener?.let {
            FirebaseDatabase.getInstance().getReference("container").removeEventListener(it)
        }
        extensionsListener?.let {
            FirebaseDatabase.getInstance().getReference("users").removeEventListener(it)
        }
        sessionListeners.forEach { (extId, listener) ->
            FirebaseDatabase.getInstance().getReference("sessions/$extId/records").removeEventListener(listener)
        }
        containerListener = null
        extensionsListener = null
        sessionListeners.clear()
        containerCache.clear()
        sessionsCache.clear()
        _records.value = emptyList()
    }

    private fun listenToContainer(uid: String) {
        val path = "container/container_$uid/records"
        val ref = FirebaseDatabase.getInstance().getReference(path)
        containerListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                containerCache.clear()
                for (child in snapshot.children) {
                    parseRecord(child, "container", "container_$uid")?.let { containerCache[it.id] = it }
                }
                emitMergedRecords()
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Container listener cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(containerListener!!)
    }

    private fun listenToConnectedExtensions(uid: String) {
        val path = "users/$uid/${UserRepository.PATH_EXTENSIONS}"
        val ref = FirebaseDatabase.getInstance().getReference(path)
        extensionsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connectedIds = snapshot.children.mapNotNull { child ->
                    val id = child.key ?: return@mapNotNull null
                    if (child.child("status").getValue(String::class.java) == "connected") id else null
                }
                connectedIds.forEach { extId ->
                    if (!sessionListeners.containsKey(extId)) listenToSession(extId)
                }
                sessionListeners.keys.filter { it !in connectedIds }.forEach { stopListeningToSession(it) }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Extensions listener cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(extensionsListener!!)
    }

    private fun listenToSession(extId: String) {
        val ref = FirebaseDatabase.getInstance().getReference("sessions/$extId/records")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val cache = sessionsCache.getOrPut(extId) { mutableMapOf() }
                cache.clear()
                for (child in snapshot.children) {
                    parseRecord(child, "session", extId)?.let { cache[it.id] = it }
                }
                emitMergedRecords()
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Session listener cancelled for $extId: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        sessionListeners[extId] = listener
    }

    private fun stopListeningToSession(extId: String) {
        sessionListeners[extId]?.let { listener ->
            FirebaseDatabase.getInstance().getReference("sessions/$extId/records").removeEventListener(listener)
        }
        sessionListeners.remove(extId)
        sessionsCache.remove(extId)
        emitMergedRecords()
    }

    private fun emitMergedRecords() {
        val allRecords = mutableListOf<CallRecord>()
        allRecords.addAll(containerCache.values)
        sessionsCache.values.forEach { cache -> allRecords.addAll(cache.values) }
        val byId = linkedMapOf<String, CallRecord>()
        allRecords.sortedByDescending { it.received_at }.forEach { incoming ->
            val existing = byId[incoming.id]
            if (existing == null || incoming.source == "container") {
                byId[incoming.id] = incoming
            }
        }
        _records.value = byId.values.sortedByDescending { it.received_at }
    }

    private fun parseRecord(child: DataSnapshot, source: String, contextId: String): CallRecord? {
        val data = child.value as? Map<*, *> ?: return null
        return CallRecord(
            id = child.key ?: return null,
            text = data["text"] as? String ?: "",
            cleaned = data["cleaned"] as? String ?: "",
            type = data["type"] as? String ?: "text",
            received_at = (data["received_at"] as? Number)?.toLong() ?: 0L,
            actions = ActionsJson.fromRecordData(data),
            source = source,
            container_id = if (source == "container") contextId else null,
            extension_id = if (source == "session") contextId else null
        )
    }
}
