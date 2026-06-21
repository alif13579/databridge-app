package com.cloudx.databridge

import com.google.firebase.database.DataSnapshot
import com.google.gson.Gson
import org.json.JSONObject

/**
 * Firebase actions map ↔ JSON string used in Room.
 */
object ActionsJson {
    private val gson = Gson()

    fun emptyObject(): String = "{}"

    fun fromSnapshot(snapshot: DataSnapshot): String {
        if (!snapshot.exists()) return emptyObject()
        val raw = snapshot.value ?: return emptyObject()
        return fromAny(raw)
    }

    fun fromAny(value: Any?): String {
        when (value) {
            null -> return emptyObject()
            is String -> {
                val t = value.trim()
                if (t.isBlank() || t == "[]") return emptyObject()
                if (t.startsWith("{")) return t
                return emptyObject()
            }
            is Map<*, *> -> {
                val filtered = value.filterKeys { (it as? String)?.startsWith("action_") == true }
                return if (filtered.isEmpty()) emptyObject() else gson.toJson(filtered)
            }
            else -> return emptyObject()
        }
    }

    fun fromRecordData(data: Map<*, *>): String = fromAny(data["actions"])

    fun parseActionItems(json: String): List<ActionItem> {
        val items = mutableListOf<ActionItem>()
        try {
            val raw = json.trim()
            if (raw.isBlank() || raw == "[]" || raw == "{}") return items
            val obj = JSONObject(raw)
            obj.keys().forEach { key ->
                if (!key.startsWith("action_")) return@forEach
                val a = obj.optJSONObject(key) ?: return@forEach
                items.add(
                    ActionItem(
                        id = key,
                        remarks = a.optString("remarks", a.optString("remark", "")),
                        timestamp = a.optLong("timestamp", 0L),
                        type = a.optString("type", "unknown"),
                        source = a.optString("source", "unknown")
                    )
                )
            }
        } catch (_: Exception) { }
        return items.sortedByDescending { it.timestamp }
    }
}
