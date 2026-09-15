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
        // AUTHORITY level for courier-sync vs remark-status conflicts — NOT display order
        // (see sortOrder below for that). Mirrors config/statusMeta/{key}/priority, the same
        // field ConfigStatusesFragment's admin panel edits. Used by
        // ConfigSheetWizardSteps.kt's propagation to decide whether an incoming
        // courier/consignments/status change should overwrite an existing
        // remarks_by_userId/.../final_status: higher authority wins, so e.g. a
        // human-verified "Return Verified" outcome isn't silently overwritten by a lower-
        // authority courier sync status. Defaults to 0 (loses every genuine comparison) for
        // any status that doesn't set this field — an unconfigured status should never
        // silently outrank a configured one.
        val priority: Int = 0,
        // Chip / worklist DISPLAY order — higher sorts first. This is what priority used to
        // mean before that field was repurposed for authority above; every call site that
        // used to read .priority for sorting (CallCenterFragment's status chips, Worker's
        // Priority Queue mode, WorkerParcelAdapter's card ordering, the dashboard breakdown
        // order) now reads .sortOrder instead. Mirrors config/statusMeta/{key}/sortOrder,
        // a separate admin-edited field from priority/authority. Defaults to 0.
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
                val priority = s.child("priority").getValue(Int::class.java) ?: 0
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
                map[key] = Entry(bn, en, color, bg, updatesParcelStatus, priority, sortOrder, ignored, hasIgnored)
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

    /** Authority level for [statusKey], for deciding whether an incoming status should
     *  overwrite an existing one in a courier-sync-vs-remark conflict. Unconfigured
     *  statuses get 0, same as Entry's default — see Entry.priority's doc for why an
     *  unconfigured status must never outrank a configured one. */
    fun authorityOf(statusKey: String): Int =
        entries[statusKey]?.priority ?: 0
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
    val entry = entries[remarkStatus]
        ?: entries.entries.firstOrNull { it.key.equals(remarkStatus, ignoreCase = true) }?.value
        ?: return null
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
    return configured.any { it.equals(actualStatus, ignoreCase = true) }
}
