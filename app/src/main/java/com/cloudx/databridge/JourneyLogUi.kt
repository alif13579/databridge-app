package com.cloudx.databridge

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * Shared Journey Log UI rules — one place so Call Center, Worker, View Orders
 * and Parcel Detail all render the same timeline.
 *
 * Covers the user's journey-log revamp:
 *  1. Parcel Status = consignment (actual) status; latest remark status on the
 *     next row; effective status stays as the header badge.
 *  2. Chat-style: worker/agent left, CC right, system centered.
 *  3. Date dividers: one per Dhaka day, labelled "Attempt N" (each day = 1 attempt).
 *  4. Summary: average handoff response time (from [HistoryEntry.responseGapMinutes]).
 *  5. No fake times: a DATE-only DB value renders as date-only (never "06:00 AM"
 *     from a UTC-midnight parse); a datetime renders full. Epochs that land
 *     exactly on UTC midnight (sheet-imported date-only) also render date-only.
 */
object JourneyLogUi {

    private fun fullFmt() = DhakaTime.sdf("dd-MM-yy hh:mm:ss a", Locale.getDefault())
    private fun dateOnlyFmt() = DhakaTime.sdf("dd-MM-yy", Locale.getDefault())
    private fun dayLabelFmt() = DhakaTime.sdf("dd MMM yyyy", Locale.ENGLISH)

    /** True when [raw] carries no time part — bare "YYYY-MM-DD". */
    fun isDateOnlyRaw(raw: String?): Boolean {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return false
        if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(v)) return true
        // "YYYY-MM-DD ..." without a clock time (no HH:MM) is also date-only.
        if (Regex("^\\d{4}-\\d{2}-\\d{2}[T ].*$").matches(v)) {
            return !v.contains(":")
        }
        return false
    }

    private fun isUtcMidnight(millis: Long): Boolean {
        return runCatching {
            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalTime() == LocalTime.MIDNIGHT
        }.getOrDefault(false)
    }

    /**
     * Smart time for a Supabase timestamptz raw string + its parsed millis.
     * Date-only → "12-09-26" (never a fake 06:00 AM). Datetime → full stamp
     * in Dhaka zone. 0/blank → "—".
     */
    fun formatValidationTime(raw: String?, millis: Long): String {
        if (millis <= 0L) return "—"
        if (isDateOnlyRaw(raw)) return dateOnlyFmt().format(java.util.Date(millis))
        return fullFmt().format(java.util.Date(millis))
    }

    /**
     * Smart time for Firebase epoch millis (no raw string available).
     * Values landing exactly on UTC midnight are sheet-imported date-only
     * values — render date-only instead of a misleading "06:00 AM".
     */
    fun formatEpochFull(millis: Long): String {
        if (millis <= 0L) return "—"
        return if (isUtcMidnight(millis)) dateOnlyFmt().format(java.util.Date(millis))
        else fullFmt().format(java.util.Date(millis))
    }

    /** "12 Sep 2026" label for a divider, Dhaka zone. */
    fun dayLabel(millis: Long): String =
        if (millis <= 0L) "—" else dayLabelFmt().format(java.util.Date(millis))

    /**
     * True for the synthetic "Parcel created" starting row — [actionOrStatus] is
     * HistoryEntry.action in the dialog renderers ("CREATED"), but ParcelDetail's
     * own Entry model stores "" there, so the system-role + remark pair is the
     * fallback signal. Callers skip the date divider for these rows (no
     * "───── date ─────" on the created date) while still advancing lastDayKey
     * so same-day remarks don't re-introduce one either.
     */
    fun isCreatedEntry(actionOrStatus: String?, remark: String?, role: String?): Boolean {
        if (actionOrStatus?.trim().equals("CREATED", ignoreCase = true)) return true
        return role?.trim().equals("system", ignoreCase = true) &&
            remark?.trim() == "Parcel created"
    }

    /**
     * Average of all handoff gaps. "—" when nothing to average.
     * e.g. "2h 15m avg (5)" / "45m avg (2)" / "3d 2h avg (4)".
     */
    fun avgResponseText(entries: List<HistoryEntry>): String {
        val gaps = entries.mapNotNull { it.responseGapMinutes }.filter { it >= 0 }
        if (gaps.isEmpty()) return "—"
        val avg = gaps.sum() / gaps.size
        return "${formatMinutes(avg)} avg (${gaps.size})"
    }

    /** m:ss for recording chips, e.g. 0:47 / 3:05 / 12:40. */
    fun formatDurationSec(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        return "${s / 60}:${String.format(java.util.Locale.ENGLISH, "%02d", s % 60)}"
    }

    /** 1.2 MB / 800 KB / 2.1 GB for storage + cleanup UI. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.ENGLISH, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.ENGLISH, "%.1f MB", mb)
        return String.format(java.util.Locale.ENGLISH, "%.2f GB", mb / 1024.0)
    }

    /**
     * call_recordings rows → timeline entries, merged with remark history and
     * sorted by time by the caller. The remark text carries the uploader's
     * note (if any); playback reads [HistoryEntry.recordingR2Key].
     */
    fun recordingsToHistory(recordings: List<SupabaseCallRecordings.Recording>): List<HistoryEntry> =
        recordings.map { rec ->
            val label = if (rec.note.isNotBlank()) "🎙 Call recording\nNote: ${rec.note}"
            else "🎙 Call recording"
            HistoryEntry(
                action = "CALL RECORDING",
                remark = label,
                time = formatEpochFull(rec.createdAt),
                author = rec.authorName + if (rec.source.equals("CC", ignoreCase = true)) " · CC" else "",
                authorRole = if (rec.source.equals("CC", ignoreCase = true)) "cc" else "agent",
                authorPhotoUrl = rec.authorPhotoUrl,
                createdAt = rec.createdAt,
                recordingR2Key = rec.r2Key,
                recordingDurationSec = rec.durationSec,
                recordingId = rec.id,
                authorSystemId = rec.authorSystemId,
            )
        }

    fun formatMinutes(totalMin: Long): String {        if (totalMin < 1) return "0m"
        if (totalMin < 60) return "${totalMin}m"
        val h = totalMin / 60
        val m = totalMin % 60
        if (h < 24) return if (m == 0L) "${h}h" else "${h}h ${m}m"
        val d = h / 24
        val rh = h % 24
        return if (rh == 0L) "${d}d" else "${d}d ${rh}h"
    }

    /**
     * Attempt number per Dhaka day, in chronological order of first appearance.
     * Every distinct day = 1 attempt: first day → 1, second day → 2, ...
     */
    fun attemptByDay(entriesSortedAsc: List<HistoryEntry>): Map<String, Int> {
        val map = LinkedHashMap<String, Int>()
        var n = 0
        for (e in entriesSortedAsc) {
            if (e.createdAt <= 0L) continue
            val key = DhakaTime.dayKey(e.createdAt)
            if (!map.containsKey(key)) {
                n += 1
                map[key] = n
            }
        }
        return map
    }

    /** Centered date divider: "───── 12 Sep 2026 ─────". */
    fun makeDateDivider(context: Context, dayMillis: Long): TextView {
        return TextView(context).apply {
            text = "───── ${dayLabel(dayMillis)} ─────"
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF6B7280.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    @Deprecated("Attempt count removed — use makeDateDivider(context, dayMillis)")
    fun makeDateDivider(context: Context, dayMillis: Long, attemptNo: Int): TextView =
        makeDateDivider(context, dayMillis)

    /**
     * Chat-style alignment for an inflated R.layout.item_timeline_entry root.
     * Root children: [0] avatar column (LinearLayout), [1] content column.
     *  - agent/worker → avatar left, grey bubble, START.
     *  - cc → avatar moved right, blue bubble, END.
     *  - system → avatar hidden, centered neutral bubble.
     */
    fun applyChatStyle(root: View, authorRole: String) {
        val container = root as? LinearLayout ?: return
        if (container.childCount < 2) return
        val avatarCol = container.getChildAt(0)
        val content = container.getChildAt(1) as? LinearLayout ?: return

        // Detach avatar first so re-ordering never duplicates it on recycled views.
        container.removeView(avatarCol)

        val bubble = GradientDrawable().apply {
            cornerRadius = 14f * root.resources.displayMetrics.density
            when (authorRole) {
                "cc" -> setColor(0xFFDBEAFE.toInt())      // light blue, CC right
                "agent" -> setColor(0xFFF3F4F6.toInt())   // light grey, worker left
                else -> setColor(0xFFF9FAFB.toInt())      // neutral, system center
            }
        }
        content.background = bubble
        val pad = (10f * root.resources.displayMetrics.density).toInt()
        content.setPadding(pad, (8f * root.resources.displayMetrics.density).toInt(), pad, (8f * root.resources.displayMetrics.density).toInt())

        val contentParams = content.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        when (authorRole) {
            "cc" -> {
                // Avatar on the right for CC.
                container.addView(avatarCol)
                (avatarCol.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    marginStart = (10f * root.resources.displayMetrics.density).toInt()
                    marginEnd = 0
                }
                container.gravity = Gravity.END
                contentParams.gravity = Gravity.END
                content.gravity = Gravity.END
            }
            "agent" -> {
                container.addView(avatarCol, 0)
                (avatarCol.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    marginEnd = (10f * root.resources.displayMetrics.density).toInt()
                    marginStart = 0
                }
                container.gravity = Gravity.START
                contentParams.gravity = Gravity.START
                content.gravity = Gravity.START
            }
            else -> {
                // System: keep avatar column attached (so findViewById still
                // resolves ivTimelineAvatar/viewTimelineLine) but hidden —
                // detaching it here caused NPE in callers on CREATED/System rows.
                container.addView(avatarCol, 0)
                container.gravity = Gravity.CENTER
                contentParams.gravity = Gravity.CENTER
                content.gravity = Gravity.CENTER
            }
        }
        content.layoutParams = contentParams
        if (authorRole == "system") {
            avatarCol.visibility = View.GONE
        } else {
            avatarCol.visibility = View.VISIBLE
        }
        // Gap between chat cards — without this consecutive bubbles touch
        // each other (bubble bg sits on content, so inner padding is not enough).
        val density = root.resources.displayMetrics.density
        (root.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.bottomMargin = (10f * density).toInt()
            root.layoutParams = it
        } ?: run {
            root.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10f * density).toInt() }
        }
    }
}
