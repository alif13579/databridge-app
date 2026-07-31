package com.cloudx.databridge

import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat

/**
 * 📋 CallLogHelper — reads CallLog.Calls to tell whether an outgoing call was actually
 * answered, for Auto Call's no-answer detection.
 *
 * Android's live telephony state (TelephonyManager / CallStateWatcher) can't distinguish
 * "ringing, not yet answered" from "answered and talking" — CALL_STATE_OFFHOOK covers both
 * (official docs: "at least one call exists that is dialing, active, or on hold"). The call
 * log's DURATION field is the practical workaround: for an outgoing call, Android measures
 * duration from connect to disconnect, so duration == 0 means it was never answered.
 */
object CallLogHelper {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Looks up the most recent OUTGOING call log entry matching [phone] (BD-normalized
     * comparison — same normalizeBdPhone() AutoDialHelper dials with — so format differences
     * between what was dialed and what the OS logged don't cause a false miss), that started
     * at or after [sinceEpochMs]. Returns its talk duration in seconds, or null if no
     * matching entry is found (permission missing, log not written yet, or genuinely no
     * match).
     *
     * There's a brief OS-side delay between a call ending and its log entry being written —
     * callers should allow a short buffer (~1s) after the call ends before calling this.
     */
    /**
     * All of TODAY's OUTGOING call log entries matching [phone] (BD-normalized comparison,
     * same as getLastCallDurationSeconds above), each as a (timestamp millis, duration
     * seconds) pair, most recent first. For worker remark verification — lets call-center
     * see exactly how many times + how long a number was called before a remark was saved.
     * Empty if permission is missing, nothing matches, or the query fails — never throws.
     */
    fun getTodaysCallLogs(context: Context, phone: String): List<Pair<Long, Int>> {
        if (!hasPermission(context)) return emptyList()
        val targetDigits = AutoDialHelper.normalizeBdPhone(phone)
        if (targetDigits.isBlank()) return emptyList()

        val todayStartMs = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION, CallLog.Calls.DATE)
        val selection = "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?"
        val selectionArgs = arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), todayStartMs.toString())

        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, selection, selectionArgs,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val durationIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val results = mutableListOf<Pair<Long, Int>>()
                while (cursor.moveToNext()) {
                    val loggedNumber = cursor.getString(numberIdx) ?: continue
                    if (AutoDialHelper.normalizeBdPhone(loggedNumber) == targetDigits) {
                        results.add(cursor.getLong(dateIdx) to cursor.getInt(durationIdx))
                    }
                }
                results
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getLastCallDurationSeconds(context: Context, phone: String, sinceEpochMs: Long): Int? {
        if (!hasPermission(context)) return null
        val targetDigits = AutoDialHelper.normalizeBdPhone(phone)
        if (targetDigits.isBlank()) return null

        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION)
        val selection = "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?"
        val selectionArgs = arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), sinceEpochMs.toString())

        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, selection, selectionArgs,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val durationIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                while (cursor.moveToNext()) {
                    val loggedNumber = cursor.getString(numberIdx) ?: continue
                    if (AutoDialHelper.normalizeBdPhone(loggedNumber) == targetDigits) {
                        return cursor.getInt(durationIdx)
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
