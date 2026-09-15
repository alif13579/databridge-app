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
        // Chip / worklist DISPLAY order — higher sorts first. Mirrors
        // config/statusMeta/{key}/sortOrder (admin-edited in ConfigStatusesFragment).
        // Defaults to 0.
        val sortOrder: Int = 0,
        // Actual statuses this remark status is IGNORED in — i.e. when the parcel's
        // real status is one of these, the card chip / filter key shows the actual
        // status instead of this remark (e.g. hold_verified ignored in Delivered).
        // Mirrors config/statusMeta/{key}/ignoredWhenActual (admin-edited, multi-add
        // in ConfigStatusesFragment). Empty set + hasIgnoredWhenActual=false means the
        // field was never configured → never ignored (remark always wins).
        // Compared case-insensitively.
        val ignoredWhenActual: Set<String> = emptySet(),
        // True only when the ignoredWhenActual node EXISTS in Firebase — distinguishes
        // "admin configured" (exact list wins) from "never configured" (default wins).
        // Note: an emptied list is dropped by RTDB on save, so empty also = default.
        val hasIgnoredWhenActual: Boolean = false,
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
                val sortOrder = s.child("sortOrder").getValue(Int::class.java) ?: 0
                val ignoredNode = s.child("ignoredWhenActual")
                val hasIgnored = ignoredNode.exists()
                // RTDB stores lists as numeric-keyed maps; also accept a hand-typed
                // comma/newline-separated string for console edits.
                val ignoredFromChildren = ignoredNode.children
                    .mapNotNull { it.getValue(String::class.java)?.trim() }
                    .filter { it.isNotEmpty() }
                val ignoredFromString = ignoredNode.getValue(String::class.java)
                    ?.split(',', '\n').orEmpty()
                    .map { it.trim() }.filter { it.isNotEmpty() }
                val ignored = (ignoredFromChildren + ignoredFromString).toSet()
                map[key] = Entry(bn, en, color, bg, updatesParcelStatus, sortOrder, ignored, hasIgnored)
            }
            if (map.isNotEmpty()) entries = map
        } catch (_: Exception) {
            // Keep whatever was cached before (or the empty default) — callers fall back gracefully.
        }
    }

    /** Picks the bn or en label for [statusKey] per [statusLang] ("bn"/"en"). Null if not in cache.
     *  Strictly config-defined — no hardcoded guess. Missing config returns null so the
     *  caller shows the raw key (admin gap signal, fix in Config → Statuses). */
    fun labelOrNull(statusKey: String, statusLang: String): String? {
        val e = findEntry(statusKey) ?: return null
        return if (statusLang == "en") e.en else e.bn
    }

    /**
     * Whether selecting [statusKey] should overwrite courier/consignments/{id}/status (the
     * parcel's actual delivery status) and consignments_by_phone. True unless the status is
     * explicitly configured with updatesParcelStatus=false in config/statusMeta (e.g.
     * "verify_request" — a remark/attempt outcome, not a real delivery-status change).
     */
    fun updatesParcelStatus(statusKey: String): Boolean {
        return findEntry(statusKey)?.updatesParcelStatus ?: true
    }

    /** Admin key rule — identical to ConfigStatusesFragment create
     *  (trim + UPPER + whitespace→_). Parcel actuals arrive Title Case with
     *  spaces ("Assigned for Delivery") while config nodes are UPPER_SNAKE
     *  ("ASSIGNED_FOR_DELIVERY"); norm makes them meet. */
    fun normKey(s: String): String = s.trim().uppercase().replace("\\s+".toRegex(), "_")

    /** True when [a] and [b] are the same status under the admin key rule
     *  (case + space/underscore-insensitive). Use for every status
     *  comparison instead of == / equals(ignoreCase). */
    fun sameStatus(a: String, b: String): Boolean = normKey(a) == normKey(b)

    /** Single lookup for config/statusMeta: exact → norm-match (covers case
     *  AND space/underscore variants) → legacy ignoreCase. Null = unconfigured. */
    fun findEntry(statusKey: String): Entry? {
        if (statusKey.isBlank()) return null
        entries[statusKey]?.let { return it }
        val n = normKey(statusKey)
        entries.entries.firstOrNull { normKey(it.key) == n }?.let { return it.value }
        return entries.entries.firstOrNull { it.key.equals(statusKey, ignoreCase = true) }?.value
    }

    /** Config node's own key casing for [statusKey], or null when unconfigured. */
    fun configKeyFor(statusKey: String): String? {
        if (statusKey.isBlank()) return null
        if (entries.containsKey(statusKey)) return statusKey
        val n = normKey(statusKey)
        entries.keys.firstOrNull { normKey(it) == n }?.let { return it }
        return entries.keys.firstOrNull { it.equals(statusKey, ignoreCase = true) }
    }

    /** Canonical grouping key for chips/filters: the config key's own casing when
     *  known (exact, norm, else case-insensitive), otherwise the raw key trimmed
     *  with ORIGINAL casing preserved — never lowercased (lowercasing is what
     *  made "Assigned for Delivery" render as "assigned for delivery").
     *  Bucketing stays case/space-insensitive via normKey at the call sites.
     *  Display label/color still come from getStatusConfig(). */
    fun canonicalStatusKey(raw: String): String {
        if (raw.isBlank()) return raw
        configKeyFor(raw)?.let { return it }
        return raw.trim()
    }

    /** config/statusMeta/{key}/priority is intentionally UNREAD — its only consumer
     *  (sheet-sync authority comparison into the retired courier/remarks_by_userId
     *  index) was removed, and the ignore-list system replaced authority rules.
     *  The field is no longer parsed, saved, or shown anywhere; stale `priority`
     *  nodes left in Firebase are simply ignored (and dropped on the next save). */
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

/**
 * The configured ignore list for [remarkStatus], or null when that status never
 * configured one. Lookup is exact-first, then case-insensitive (remark keys vary
 * in case: VERIFY_REQUEST vs verify_req). Null = never ignored (remark wins).
 */
fun StatusMetaCache.ignoredActualsFor(remarkStatus: String): Set<String>? {
    if (remarkStatus.isBlank()) return null
    val entry = findEntry(remarkStatus) ?: return null
    return if (entry.hasIgnoredWhenActual) entry.ignoredWhenActual else null
}

/**
 * Single source of truth for effectiveStatus: should [remarkStatus] be IGNORED
 * (actual status shown instead) when the parcel's actual status is [actualStatus]?
 *
 * Fully config-driven: the remark status's own ignoredWhenActual list decides
 * (case-insensitive exact match). Never configured (or empty) → never ignored,
 * remark always wins. There is deliberately NO hardcoded fallback — every ignore
 * is an admin-saved custom text in config/statusMeta.
 *
 * Remark text on the card is unaffected — only the chip/filter key changes.
 */
fun StatusMetaCache.isRemarkIgnoredInActual(remarkStatus: String, actualStatus: String): Boolean {
    if (remarkStatus.isBlank() || actualStatus.isBlank()) return false
    val configured = ignoredActualsFor(remarkStatus) ?: return false
    return configured.any { sameStatus(it, actualStatus) }
}
