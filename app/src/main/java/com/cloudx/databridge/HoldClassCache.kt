package com.cloudx.databridge

/**
 * validation_remarks catalog → hold class ("strict" / "non-strict" / "").
 *
 * Hold class lives ONLY in the catalog (validations rows never carry it —
 * see BranchSummaryViewModel) — map the winning remark's ENGLISH text
 * (validations.remarks) to its class. Cached 10 min; [peek] serves live
 * single-card updates without a suspend.
 */
object HoldClassCache {
    private var cached: Map<String, String>? = null
    private var cachedAt = 0L
    private const val TTL_MS = 10 * 60 * 1000L

    /** english-lowercase → class-lowercase. Empty map offline (never throws). */
    suspend fun get(): Map<String, String> {
        val c = cached
        if (c != null && System.currentTimeMillis() - cachedAt < TTL_MS) return c
        val fresh = runCatching {
            SupabaseClientManager.fetchRemarkOptions("HoldClassCache", "CC")
                .filter { it.textEn.isNotBlank() }
                .associate { it.textEn.trim().lowercase() to it.holdClass.trim().lowercase() }
        }.getOrDefault(emptyMap())
        cached = fresh
        cachedAt = System.currentTimeMillis()
        return fresh
    }

    /** Last fetched map (possibly empty) — for non-suspend live-update paths. */
    fun peek(): Map<String, String> = cached.orEmpty()

    fun classOf(map: Map<String, String>, englishRemark: String): String =
        map[englishRemark.trim().lowercase()].orEmpty()

    fun isValidated(remarkStatus: String): Boolean =
        remarkStatus.equals("hold_verified", ignoreCase = true) ||
            remarkStatus.equals("return_verified", ignoreCase = true)

    fun isDeliveryRequest(remarkStatus: String): Boolean =
        remarkStatus.equals("delivery_request", ignoreCase = true)

    fun isNonStrict(holdClass: String): Boolean =
        holdClass.equals(ConfigState.HOLD_CLASS_NON_STRICT, ignoreCase = true)
}
