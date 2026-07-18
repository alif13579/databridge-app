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
        val bg: Int,
        // false only when config/statusMeta/{key}/updatesParcelStatus is explicitly set to
        // false — e.g. "verify_request" (a remark/attempt outcome, not a real delivery
        // outcome) shouldn't overwrite the parcel's actual courier/consignments/{id}/status.
        // Defaults to true (old behavior) for any status that doesn't set this field.
        val updatesParcelStatus: Boolean = true,
        // Chip sort order — higher priority sorts first. Mirrors config/statusMeta/{key}/priority,
        // the same field ConfigStatusesFragment's admin panel edits. Defaults to 0 for any
        // status that doesn't set this field (sorts after anything with an explicit priority).
        val priority: Int = 0
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
                val updatesParcelStatus = s.child("updatesParcelStatus")
                    .getValue(Boolean::class.java) ?: true
                val priority = s.child("priority").getValue(Int::class.java) ?: 0
                map[key] = Entry(bn, en, color, bg, updatesParcelStatus, priority)
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

    /**
     * Whether selecting [statusKey] should overwrite courier/consignments/{id}/status (the
     * parcel's actual delivery status) and consignments_by_phone. True unless the status is
     * explicitly configured with updatesParcelStatus=false in config/statusMeta (e.g.
     * "verify_request" — a remark/attempt outcome, not a real delivery-status change).
     */
    fun updatesParcelStatus(statusKey: String): Boolean =
        entries[statusKey]?.updatesParcelStatus ?: true
}

/**
 * Whether [status] (a remark's own status key, e.g. from remarkStatus/lastRemarkStatus)
 * represents a verify/validation request.
 *
 * Confirmed via a live courier/remarks_by_consignment export that the actual value written
 * by the app (sourced from the admin-configured config/remarks_worker node key) is
 * "VERIFY_REQUEST" (uppercase, full word) — NOT the "verify_req" (lowercase, abbreviated)
 * literal every validationRequest/Priority-Queue check in CallCenterFragment and
 * WorkerSpaceFragment was comparing against with case-sensitive `==`. That mismatch meant
 * validationRequest could never become true from real data: Priority Queue mode showed
 * nothing, and the validation badge/notification never fired.
 *
 * Case-insensitive, and also accepts "verify_req" so any differently-configured or
 * historical data still matches — this is the single source of truth for the check,
 * used everywhere instead of a hardcoded string literal.
 */
fun isVerifyRequestStatus(status: String): Boolean {
    val s = status.trim()
    return s.equals("VERIFY_REQUEST", ignoreCase = true) || s.equals("verify_req", ignoreCase = true)
}

/** Parses a "{remarkLang}_{statusLang}" language value (e.g. "bn_en") into its two parts. */
fun parseLangPair(value: String): Pair<String, String> {
    val parts = value.split("_")
    val remarkLang = parts.getOrNull(0)?.takeIf { it == "bn" || it == "en" } ?: "bn"
    val statusLang = parts.getOrNull(1)?.takeIf { it == "bn" || it == "en" } ?: "bn"
    return remarkLang to statusLang
}
