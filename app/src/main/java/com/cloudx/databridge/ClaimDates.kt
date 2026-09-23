package com.cloudx.databridge

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Bangladesh time for every claims date path — display, pickers, ranges and
 * the report/export filters. Device timezone is not trusted: a bill day
 * (26Aug–25Sep) and every expense date must mean the same calendar day on
 * every phone, so Asia/Dhaka (GMT+6, no DST) is hardcoded here.
 */
internal object BdTime {
    val ZONE: TimeZone = TimeZone.getTimeZone("Asia/Dhaka")

    fun cal(): Calendar = Calendar.getInstance(ZONE)

    fun format(pattern: String, millis: Long, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat(pattern, locale).apply { timeZone = ZONE }.format(java.util.Date(millis))

    fun startOfDay(millis: Long): Long = cal().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun endOfDay(millis: Long): Long = cal().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
    }.timeInMillis

    /** yyyy-MM-dd calendar day of an ISO instant in Dhaka. Null when unparseable. */
    fun dhakaDate(isoInstant: String): String? {
        if (isoInstant.isBlank()) return null
        val millis = runCatching {
            // Tolerant ISO parse: fractional seconds of any length.
            val norm = isoInstant.trim().replace(Regex("""\.(\d+)""")) { m ->
                "." + (m.groupValues[1] + "000000").take(6)
            }
            java.time.Instant.parse(norm).toEpochMilli()
        }.getOrNull() ?: runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = ZONE }.parse(isoInstant)?.time
        }.getOrNull() ?: return null
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = ZONE }.format(java.util.Date(millis))
    }
}

/** Dhaka-local expense day (yyyy-MM-dd) of a report row — the basis every
 *  range filter (dashboard, All Requests, report PDF/Excel) agrees on. */
internal fun SupabaseClaimsReader.ClaimRow.dhakaDate(): String =
    BdTime.dhakaDate(raw.optStr("requested_at")) ?: placedDate
