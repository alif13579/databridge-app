package com.cloudx.databridge

/**
 * 🆔 IdUtils.kt (Foundation)
 * ✅ সেন্ট্রালাইজড আইডি প্রিফিক্স কনস্ট্যান্ট
 * ✅ থ্রেড-সেফ টাইমস্ট্যাম্প-ভিত্তিক আইডি জেনারেটর
 * ✅ ফোন নম্বর ক্লিনার (Regex অপ্টিমাইজড)
 */
object IdPrefix {
    const val RECORD = "record_"
    const val CONTAINER = "container_"
    const val ACTION = "action_"
    const val RUN = "run_"
}

object IdUtils {
    /** 🔹 নতুন রেকর্ড আইডি: record_{timestamp} */
    fun generateRecordId(): String = "${IdPrefix.RECORD}${System.currentTimeMillis()}"

    /** 🔹 নতুন অ্যাকশন আইডি: action_{timestamp} */
    fun generateActionId(): String = "${IdPrefix.ACTION}${System.currentTimeMillis()}"

    private val RUN_ID_REGEX = Regex("^run_\\d+$")

    /** 🔹 নতুন run আইডি: run_{timestampMs} — sortable, unique per run */
    fun generateRunId(atMs: Long = System.currentTimeMillis()): String =
        "${IdPrefix.RUN}$atMs"

    /** শুধু `run_{digits}` — অন্য format (যেমন run_20260524_morning) গ্রহণযোগ্য নয় */
    fun isValidRunId(runId: String): Boolean = RUN_ID_REGEX.matches(runId.trim())

    /** run_{timestampMs} থেকে ms; invalid format হলে null */
    fun parseRunTimestampMs(runId: String): Long? {
        if (!isValidRunId(runId)) return null
        return runId.trim().removePrefix(IdPrefix.RUN).toLong()
    }

    /** 🔹 কন্টেইনার আইডি: container_{uid} */
    fun generateContainerId(uid: String): String = "${IdPrefix.CONTAINER}$uid"

    /** 🔹 ফোন নম্বর নর্মালাইজ করে numbers/ নোড কী তৈরি করে */
    fun cleanPhoneNumber(text: String): String {
        var s = text.replace(Regex("[^0-9+]"), "").trim()
        if (s.startsWith("+")) s = s.drop(1)
        if (s.startsWith("00")) s = s.drop(2)
        if (s.startsWith("0") && s.length == 11) s = "880" + s.drop(1)
        return s.replace(Regex("[^0-9]"), "")
    }
}