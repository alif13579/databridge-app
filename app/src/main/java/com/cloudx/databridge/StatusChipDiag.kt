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

    // In-app copy buffer (CC 📋 button) — mirrors the logcat lines so the user can
    // paste diagnostics straight into chat. Capped; headers always kept, per-chip
    // detail only when changed (same rule as logcat).
    private const val MAX_LINES = 400
    private val buffer = ArrayDeque<String>()
    private fun store(line: String) = synchronized(buffer) {
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
    }

    /** Full buffered diagnostics for the clipboard copy button. */
    fun dump(): String = synchronized(buffer) {
        buildString {
            appendLine("StatusChipDiag ${buildTag()}")
            if (buffer.isEmpty()) appendLine("(no diagnostics captured yet — reload the screen)")
            else buffer.forEach { appendLine(it) }
        }
    }

    fun refreshStart() {
        val line = "refresh START build=${buildTag()}"
        Log.d(TAG, line)
        store(line)
    }

    fun refreshOk(count: Int, keys: Collection<String>) {
        val line = "refresh OK build=${buildTag()} entries=$count keys=${keys.sorted().joinToString(",")}"
        Log.d(TAG, line)
        store(line)
    }

    fun refreshFail(err: String) {
        val line = "refresh FAILED build=${buildTag()} err='$err'"
        Log.e(TAG, line)
        store(line)
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
        val header = "$screen build=${buildTag()} scoped=$scoped cache=$cacheSize " +
            "refreshOk=$refreshOk err='$refreshErr' lang=$lang chips=${chips.size}"
        Log.d(TAG, header)
        store(header)
        val sig = "$cacheSize|$refreshOk|$lang|" +
            chips.joinToString(";") { "${it.raw}=${it.count}>${it.label}" }
        if (lastSig[screen] == sig) {
            Log.d(TAG, "$screen UNCHANGED")
            return
        }
        lastSig[screen] = sig
        chips.forEach { c ->
            val line = "$screen chip raw='${c.raw}' count=${c.count} cacheHit=${c.hit} label='${c.label}'"
            Log.d(TAG, line)
            store(line)
        }
    }
}
