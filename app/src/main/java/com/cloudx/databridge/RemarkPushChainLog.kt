package com.cloudx.databridge

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device, in-memory trace of the remark push/Realtime delivery chain
 * (FCM receipt -> scope match -> allParcels lookup -> fetchHistory -> card update).
 *
 * Exists so this can be diagnosed on a real device without Android Studio/Logcat attached —
 * every step still also goes through android.util.Log for anyone who IS on Logcat, but the
 * same lines are kept here so WorkerSpaceFragment can show + copy them on-screen (long-press
 * the "Sort by" label). Process-scoped only; clears when the app process is killed.
 */
object RemarkPushChainLog {
    private const val MAX_LINES = 300
    private val lines = mutableListOf<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    @Synchronized
    fun log(tag: String, message: String, isWarning: Boolean = false) {
        val line = "[${fmt.format(Date())}] $tag: $message"
        lines.add(line)
        if (lines.size > MAX_LINES) lines.removeAt(0)
        if (isWarning) android.util.Log.w(tag, message) else android.util.Log.d(tag, message)
    }

    @Synchronized
    fun snapshot(): String = if (lines.isEmpty()) {
        "এখনো কোনো log নেই — CC থেকে remark save করার পর এখানে ফিরে আসুন।"
    } else {
        lines.joinToString("\n")
    }

    @Synchronized
    fun clear() = lines.clear()
}
