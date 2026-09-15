package com.cloudx.databridge

import android.util.Log

/**
 * Temporary diagnostics for the "status chip shows raw key" issue (see StatusMetaCache).
 *
 * Logcat filter:  StatusChipDiag
 *
 * Copy-paste friendly — one header line per chip rebuild (screen, app build, parcel
 * count, cache size, last-refresh outcome, label language) plus one line per chip:
 * the raw status seen in data, its count, whether the config cache had it
 * (cacheHit=true but label still raw = blank en/bn in config; cacheHit=false =
 * key missing from config OR cache not loaded), and the final label drawn.
 * No PII — status keys + counts only, never phones/parcel ids.
 * TODO(diag): remove once the raw-key cause is confirmed fixed.
 */
object StatusChipDiag {
    const val TAG = "StatusChipDiag"

    fun buildTag(): String =
        "v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"

    fun refreshStart() {
        Log.d(TAG, "refresh START build=${buildTag()}")
    }

    fun refreshOk(count: Int, keys: Collection<String>) {
        Log.d(TAG, "refresh OK build=${buildTag()} entries=$count keys=${keys.sorted().joinToString(",")}")
    }

    fun refreshFail(err: String) {
        Log.e(TAG, "refresh FAILED build=${buildTag()} err='$err'")
    }

    data class Chip(
        val raw: String,
        val count: Int,
        val label: String,
        val hit: Boolean,
    )

    private val lastSig = mutableMapOf<String, String>()

    /** Call once per chip rebuild; per-chip detail is re-logged only when it changes. */
    fun logBuild(
        screen: String,
        scoped: Int,
        cacheSize: Int,
        refreshOk: Boolean,
        refreshErr: String,
        lang: String,
        chips: List<Chip>,
    ) {
        Log.d(TAG, "$screen build=${buildTag()} scoped=$scoped cache=$cacheSize " +
            "refreshOk=$refreshOk err='$refreshErr' lang=$lang chips=${chips.size}")
        val sig = "$cacheSize|$refreshOk|$lang|" +
            chips.joinToString(";") { "${it.raw}=${it.count}>${it.label}" }
        if (lastSig[screen] == sig) {
            Log.d(TAG, "$screen UNCHANGED")
            return
        }
        lastSig[screen] = sig
        chips.forEach { c ->
            Log.d(TAG, "$screen chip raw='${c.raw}' count=${c.count} cacheHit=${c.hit} label='${c.label}'")
        }
    }
}
