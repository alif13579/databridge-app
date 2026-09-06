package com.cloudx.databridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CC-remark → Google Sheet verdict mirror (best-effort, never blocks).
 *
 * After a CC remark with a non-blank verdict (validation_remarks.category)
 * saves to Supabase, this writes the verdict into the branch's connected
 * remark sheet: the row where the consignment column == consignmentId AND
 * the date column == today. No matching row → skip + log (never append —
 * a consignment-only match could land on a previous day's row, and an
 * append would fabricate sheet rows the sheet owner never created).
 *
 * Remark connections are the same connector list as the scanner
 * (config/connectors/{branchId}, ConfigConnectorsFragment) — a connection
 * with a non-blank dateMatchColumn IS a remark connection. Scanner
 * connections (dateMatchColumn blank) are ignored here.
 *
 * Auth reuses the connectors feature's own connected Google account
 * (SharedPreferences "connectors_google_account" — see
 * ConfigConnectorsFragment.PREFS_FILE_NAME) with the write scope, fetched
 * silently: no consent UI is possible from a background save callback, so a
 * missing/expired grant just skips the mirror (logged, FirebaseErrorLogger).
 */
object RemarkSheetMirror {

    private val httpClient = OkHttpClient()
    private val opsZone = ZoneId.of("Asia/Dhaka")

    // Display formats a Sheets date cell can come back as (FORMATTED_VALUE).
    // dd/MM/yyyy is the local norm; the rest cover common sheet locales
    // (incl. short-year "03-Jul-26" the sheet often renders).
    private val datePatterns = listOf(
        "yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "d/M/yyyy", "M/d/yyyy",
        "yyyy/MM/dd", "dd.MM.yyyy", "dd-MMM-yyyy", "d-MMM-yyyy",
        "dd-MMM-yy", "d-MMM-yy"
    ).map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    sealed class MirrorOutcome {
        data class Done(val row: Int) : MirrorOutcome()
        data class Skipped(val reason: String) : MirrorOutcome()
    }

    private fun toastMain(appContext: Context, msg: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                runCatching { Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show() }
            }
        } catch (_: Exception) { }
    }

    fun mirror(appContext: Context, branchId: String, consignmentId: String, verdict: String) {
        if (branchId.isBlank() || consignmentId.isBlank() || verdict.isBlank()) return
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val conns = ScannerSheetRepository.loadConnections(branchId)
                    .filter { it.dateMatchColumn.isNotBlank() && it.matchColumn.isNotBlank() && it.writeColumn.isNotBlank() }
                if (conns.isEmpty()) {
                    FirebaseErrorLogger.log("RemarkSheetMirror", "no_remark_connection",
                        "No remark sheet connection for branch", mapOf("branchId" to branchId))
                    toastMain(appContext, "Sheet: no remark connection for this branch — verdict not mirrored")
                    return@launch
                }
                val token = silentWriteToken(appContext.applicationContext)
                if (token.isNullOrBlank()) {
                    FirebaseErrorLogger.log("RemarkSheetMirror", "no_write_token",
                        "No silent Sheets write token (connect a Google account in Config → Connectors)",
                        mapOf("branchId" to branchId))
                    toastMain(appContext, "Sheet: Google account not connected — verdict not mirrored")
                    return@launch
                }
                val today = LocalDate.now(opsZone)
                var okRows = 0
                var lastSkip = ""
                conns.forEach { conn ->
                    when (val out = mirrorOneWithRetry(conn, token, consignmentId, verdict, today)) {
                        is MirrorOutcome.Done -> okRows++
                        is MirrorOutcome.Skipped -> {
                            lastSkip = out.reason
                            FirebaseErrorLogger.log("RemarkSheetMirror", "mirror_skipped", out.reason,
                                mapOf("branchId" to branchId, "consignment" to consignmentId))
                        }
                    }
                }
                if (okRows > 0) toastMain(appContext, "✓ Sheet verdict updated")
                else if (lastSkip.isNotBlank()) toastMain(appContext, "Sheet: $lastSkip")
            } catch (e: Exception) {
                FirebaseErrorLogger.log("RemarkSheetMirror", "mirror_error",
                    e.message ?: "Mirror failed", mapOf("branchId" to branchId))
                toastMain(appContext, "Sheet: mirror failed — ${e.message?.take(60) ?: "error"}")
            }
        }
    }

    /** mirrorOne with retry: transient transport/write failures get 3 attempts
     *  (2s, 4s backoff). Deliberate skips (no matching row) throw nothing and
     *  are returned at once — retrying changes nothing. */
    private suspend fun mirrorOneWithRetry(
        conn: ScannerSheetConn, accessToken: String, consignmentId: String,
        verdict: String, today: LocalDate, attempts: Int = 3
    ): MirrorOutcome {
        var lastError = ""
        repeat(attempts) { n ->
            try {
                return mirrorOne(conn, accessToken, consignmentId, verdict, today)
            } catch (e: Exception) {
                lastError = e.message?.take(120) ?: "sheet write failed"
                if (n < attempts - 1) delay(if (n == 0) 2000L else 4000L)
            }
        }
        return MirrorOutcome.Skipped("$lastError ($attempts বার চেষ্টা করা হয়েছে)")
    }

    // mirrorOne throws on transport/write failures (retried above) and returns
    // Skipped only for deliberate no-match skips.
    private suspend fun mirrorOne(
        conn: ScannerSheetConn,
        accessToken: String,
        consignmentId: String,
        verdict: String,
        today: LocalDate
    ): MirrorOutcome = withContext(Dispatchers.IO) {
        when (val found = findTargetRow(conn, accessToken, consignmentId, today)) {
            is FindResult.Miss -> MirrorOutcome.Skipped(found.reason)
            is FindResult.Hit -> {
                ConfigSheetDriveApi.writeCellValue(
                    accessToken, conn.sheetId, found.tab, conn.writeColumn, found.row, verdict, httpClient
                )
                MirrorOutcome.Done(found.row)
            }
        }
    }

    private sealed class FindResult {
        data class Hit(val tab: String, val row: Int, val dateCell: String, val scanned: Int) : FindResult()
        data class Miss(val reason: String) : FindResult()
    }

    /** Shared row-match used by both the live mirror and the dry-run test:
     *  today's tab → consignment column + date column → row where
     *  matchColumn == consignmentId AND dateMatchColumn == today. */
    private suspend fun findTargetRow(
        conn: ScannerSheetConn, accessToken: String, consignmentId: String, today: LocalDate
    ): FindResult = withContext(Dispatchers.IO) {
        val tabName = ScannerSheetRepository.resolveTabName(conn.tabPattern)
        val consignmentValues = ConfigSheetDriveApi.fetchColumnValues(
            accessToken, conn.sheetId, tabName, conn.matchColumn, httpClient
        )
        val dateValues = ConfigSheetDriveApi.fetchColumnValues(
            accessToken, conn.sheetId, tabName, conn.dateMatchColumn, httpClient
        )
        if (consignmentValues.isEmpty()) {
            return@withContext FindResult.Miss(
                "tab '$tabName'-এ ${conn.matchColumn} কলাম খালি — tab/column মিলছে না")
        }
        var consignmentHits = 0
        for (i in consignmentValues.indices) {
            if (consignmentValues[i].trim() != consignmentId.trim()) continue
            consignmentHits++
            if (!isToday(dateValues.getOrNull(i).orEmpty(), today)) continue
            return@withContext FindResult.Hit(tabName, i + 1, dateValues.getOrNull(i).orEmpty(), consignmentValues.size)
        }
        if (consignmentHits == 0) {
            return@withContext FindResult.Miss(
                "consignment $consignmentId tab '$tabName'-এ পাওয়া যায়নি (${consignmentValues.size} row দেখা হয়েছে)")
        }
        return@withContext FindResult.Miss(
            "consignment পাওয়া গেছে কিন্তু আজকের তারিখের row নেই — কখনো append হয় না")
    }

    /** True when a Sheets date cell (formatted text) falls on [today]. */
    private fun isToday(cell: String, today: LocalDate): Boolean {
        val raw = cell.trim()
        if (raw.isEmpty()) return false
        for (fmt in datePatterns) {
            runCatching { if (LocalDate.parse(raw, fmt) == today) return true }
        }
        return false
    }

    /** Dry-run for the Connectors Test button: same match as the live mirror
     *  but writes NOTHING. Returns a human-readable report. */
    suspend fun dryRunReport(appContext: Context, conn: ScannerSheetConn, consignmentId: String): String =
        withContext(Dispatchers.IO) {
            if (consignmentId.isBlank()) return@withContext "Consignment ID দিন"
            val token = silentWriteToken(appContext.applicationContext)
                ?: return@withContext "Google account connected নেই — Connectors থেকে account connect করুন"
            val today = LocalDate.now(opsZone)
            try {
                when (val found = findTargetRow(conn, token, consignmentId.trim(), today)) {
                    is FindResult.Hit ->
                        "✓ Row ${found.row} (tab '${found.tab}') — verdict যাবে ${conn.writeColumn}${found.row}-এ।\nDate cell: '${found.dateCell}'\n${found.scanned} row দেখা হয়েছে।"
                    is FindResult.Miss -> "✕ ${found.reason}"
                }
            } catch (e: Exception) {
                "✕ Sheet পড়া যায়নি: ${e.message?.take(100) ?: "error"}"
            }
        }

    /** Write-scope token for the connectors feature's own connected account —
     *  silent only. Any failure (no account, scope revoked, network) → null. */
    private suspend fun silentWriteToken(appContext: Context): String? = withContext(Dispatchers.IO) {
        try {
            val account = GoogleSignInHelper.restoreOwnAccountIfMatching(
                appContext, "connectors_google_account",
                listOf(com.google.android.gms.common.api.Scope(ConfigSheetDriveApi.SCOPE_SHEETS_WRITE))
            ) ?: return@withContext null
            val acctObj = account.account ?: return@withContext null
            GoogleAuthUtil.getToken(appContext, acctObj, ConfigSheetDriveApi.OAUTH_SCOPE_WRITE)
        } catch (_: Exception) {
            // Includes UserRecoverableAuthException (consent needed) — no UI
            // is possible here, so the mirror just skips.
            try {
                FirebaseErrorLogger.log("RemarkSheetMirror", "token_failed", "Silent write token unavailable")
            } catch (_: Exception) { }
            null
        }
    }
}
