package com.cloudx.databridge

/**
 * Bangladesh time (GMT+6) — single source of truth for every day-boundary,
 * day-key (yyyyMMdd run IDs) and user-visible date/time in the app.
 *
 * Why this exists: `Calendar.getInstance()` / `SimpleDateFormat(...)` use the
 * PHONE's zone. On a Dhaka-set phone that happens to equal GMT+6, but any
 * drift (travel, manual zone, emulator UTC) silently shifts "today": run IDs
 * (`run_20260911_*`), Live date matching, scanner date filters and CSV
 * timestamps all move by a day around midnight. Pinning Asia/Dhaka here
 * keeps every one of those consistent no matter the device setting.
 */
object DhakaTime {
    val ZONE: java.time.ZoneId = java.time.ZoneId.of("Asia/Dhaka")
    val TZ: java.util.TimeZone = java.util.TimeZone.getTimeZone("Asia/Dhaka")

    private val DAY_KEY_FMT: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.BASIC_ISO_DATE // yyyyMMdd

    /** Today as yyyyMMdd in Dhaka — the run-ID date key shape. */
    fun todayKey(): String = java.time.LocalDate.now(ZONE).format(DAY_KEY_FMT)

    /** Any instant's date as yyyyMMdd in Dhaka. */
    fun dayKey(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate().format(DAY_KEY_FMT)

    /** Dhaka-zone SimpleDateFormat (formatting AND parsing). */
    fun sdf(pattern: String, locale: java.util.Locale = java.util.Locale.ENGLISH): java.text.SimpleDateFormat =
        java.text.SimpleDateFormat(pattern, locale).apply { timeZone = TZ }

    /** Dhaka-zone Calendar. */
    fun calendar(): java.util.Calendar = java.util.Calendar.getInstance(TZ)

    /** Start of the Dhaka day containing [ts] (00:00:00.000). */
    fun dayStartMillis(ts: Long = System.currentTimeMillis()): Long =
        calendar().apply {
            timeInMillis = ts
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** End of the Dhaka day containing [ts] (23:59:59.999). */
    fun dayEndMillis(ts: Long = System.currentTimeMillis()): Long =
        calendar().apply {
            timeInMillis = ts
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 59)
            set(java.util.Calendar.SECOND, 59)
            set(java.util.Calendar.MILLISECOND, 999)
        }.timeInMillis
}
