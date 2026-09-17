package com.cloudx.databridge

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Customer-promised delivery date ("20 tarikh e nibo") stored on the
 * Firebase consignment node — NOT in the Supabase remark rows.
 *
 * Firebase shape (courier/consignments/{cid}/):
 *   scheduled_date = "2026-09-20" (Dhaka calendar day, yyyy-MM-dd)
 *   scheduled_by   = CC agent system_id who set it
 *   scheduled_at   = server timestamp of when it was set
 *
 * Lock rule: the card shows 🔒 while today's Dhaka date is BEFORE the
 * scheduled date (scheduled 20th → locked through the 19th, free on the
 * 20th). Pure date comparison, so expiry is automatic — no cleanup job,
 * no timezone drift (everything pinned to Asia/Dhaka).
 */
object ScheduledLock {

    const val FIELD_DATE = "scheduled_date"
    const val FIELD_BY = "scheduled_by"
    const val FIELD_AT = "scheduled_at"

    private val STORE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)
    private val SHORT_FMT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /** Blank unless [raw] is a real calendar day in yyyy-MM-dd shape. */
    fun normalize(raw: String?): String {
        val v = raw?.trim().orEmpty()
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(v)) return ""
        return runCatching { LocalDate.parse(v, STORE_FMT).format(STORE_FMT) }.getOrDefault("")
    }

    /** True when a valid date is set and today (Dhaka) hasn't reached it yet. */
    fun isLocked(scheduledDate: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val s = normalize(scheduledDate)
        if (s.isEmpty()) return false
        return s.replace("-", "") > DhakaTime.dayKey(nowMs)
    }

    /** "🔒 20 Sep" for cards — blank when no valid date. */
    fun shortLabel(scheduledDate: String): String {
        val s = normalize(scheduledDate)
        if (s.isEmpty()) return ""
        val label = runCatching { LocalDate.parse(s, STORE_FMT).format(SHORT_FMT) }.getOrNull()
            ?: return ""
        return "🔒 $label"
    }

    /** "20 Sep 2026" for the journey overview — blank when no valid date. */
    fun fullLabel(scheduledDate: String): String {
        val s = normalize(scheduledDate)
        if (s.isEmpty()) return ""
        return JourneyLogUi.dayLabel(toMillis(s))
    }

    /** Start of that Dhaka day as epoch millis. 0 when invalid. */
    fun toMillis(scheduledDate: String): Long {
        val s = normalize(scheduledDate)
        if (s.isEmpty()) return 0L
        return runCatching {
            LocalDate.parse(s, STORE_FMT).atStartOfDay(DhakaTime.ZONE).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    /** Epoch millis (e.g. picker selection) → yyyy-MM-dd in Dhaka. "" when invalid. */
    fun fromMillis(millis: Long): String {
        if (millis <= 0L) return ""
        return runCatching {
            java.time.Instant.ofEpochMilli(millis).atZone(DhakaTime.ZONE).toLocalDate().format(STORE_FMT)
        }.getOrDefault("")
    }

    /** Today as yyyy-MM-dd in Dhaka — for pre-filling the picker. */
    fun todayStoreKey(): String =
        runCatching {
            java.time.Instant.now().atZone(DhakaTime.ZONE).toLocalDate().format(STORE_FMT)
        }.getOrDefault("")
}
