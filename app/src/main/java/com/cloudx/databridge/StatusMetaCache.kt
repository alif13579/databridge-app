package com.cloudx.databridge

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * 🏷️ StatusMetaCache — shared, independently-refreshable cache of config/statusMeta.
 *
 * WorkerSpaceFragment and CallCenterFragment don't necessarily visit the Config screens
 * (ConfigState may be empty for them), so each fetches this on its own via refresh().
 * Both screens then read from the same in-memory cache when resolving a status's
 * label/color, instead of relying on a hardcoded switch-case.
 */
object StatusMetaCache {

    data class Entry(
        val bn: String,
        val en: String,
        val color: Int,
        val bg: Int
    )

    @Volatile
    var entries: Map<String, Entry> = emptyMap()
        private set

    suspend fun refresh() {
        try {
            val snap = FirebaseDatabase.getInstance().reference.child("config/statusMeta").get().await()
            val map = mutableMapOf<String, Entry>()
            snap.children.forEach { s ->
                val key = s.key ?: return@forEach
                val bn = s.child("bn").getValue(String::class.java)?.trim().orEmpty().ifBlank { key }
                val en = s.child("en").getValue(String::class.java)?.trim().orEmpty().ifBlank { key }
                val colorHex = s.child("color").getValue(String::class.java)?.trim().orEmpty()
                val bgHex = s.child("bg").getValue(String::class.java)?.trim().orEmpty()
                val color = try {
                    android.graphics.Color.parseColor(colorHex.ifBlank { "#6B7280" })
                } catch (_: Exception) {
                    android.graphics.Color.GRAY
                }
                val bg = try {
                    android.graphics.Color.parseColor(bgHex.ifBlank { "#F3F4F6" })
                } catch (_: Exception) {
                    android.graphics.Color.LTGRAY
                }
                map[key] = Entry(bn, en, color, bg)
            }
            if (map.isNotEmpty()) entries = map
        } catch (_: Exception) {
            // Keep whatever was cached before (or the empty default) — callers fall back gracefully.
        }
    }

    /** Picks the bn or en label for [statusKey] per [statusLang] ("bn"/"en"). Null if not in cache. */
    fun labelOrNull(statusKey: String, statusLang: String): String? {
        val e = entries[statusKey] ?: return null
        return if (statusLang == "en") e.en else e.bn
    }
}

/** Parses a "{remarkLang}_{statusLang}" language value (e.g. "bn_en") into its two parts. */
fun parseLangPair(value: String): Pair<String, String> {
    val parts = value.split("_")
    val remarkLang = parts.getOrNull(0)?.takeIf { it == "bn" || it == "en" } ?: "bn"
    val statusLang = parts.getOrNull(1)?.takeIf { it == "bn" || it == "en" } ?: "bn"
    return remarkLang to statusLang
}
