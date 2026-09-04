package com.cloudx.databridge

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
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
    // dd/MM/yyyy is the local norm; the rest cover common sheet locales.
    private val datePatterns = listOf(
        "yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "d/M/yyyy", "M/d/yyyy",
        "yyyy/MM/dd", "dd.MM.yyyy", "dd-MMM-yyyy", "d-MMM-yyyy"
    ).map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    fun mirror(appContext: Context, branchId: String, consignmentId: String, verdict: String) {
        if (branchId.isBlank() || consignmentId.isBlank() || verdict.isBlank()) return
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val conns = ScannerSheetRepository.loadConnections(branchId)
                    .filter { it.dateMatchColumn.isNotBlank() && it.matchColumn.isNotBlank() && it.writeColumn.isNotBlank() }
                if (conns.isEmpty()) {
                    FirebaseErrorLogger.log("RemarkSheetMirror", "no_remark_connection",
                        "No remark sheet connection for branch", mapOf("branchId" to branchId))
                    return@launch
                }
                val token = silentWriteToken(appContext.applicationContext)
                if (token.isNullOrBlank()) {
                    FirebaseErrorLogger.log("RemarkSheetMirror", "no_write_token",
                        "No silent Sheets write token (connect a Google account in Config → Connectors)",
                        mapOf("branchId" to branchId))
                    return@launch
                }
                val today = LocalDate.now(opsZone)
                conns.forEach { conn ->
                    runCatching { mirrorOne(conn, token, consignmentId, verdict, today) }
                        .onFailure {
                            FirebaseErrorLogger.log("RemarkSheetMirror", "mirror_failed",
                                it.message ?: "Sheet write failed",
                                mapOf("branchId" to branchId, "consignment" to consignmentId))
                        }
                }
            } catch (e: Exception) {
                FirebaseErrorLogger.log("RemarkSheetMirror", "mirror_error",
                    e.message ?: "Mirror failed", mapOf("branchId" to branchId))
            }
        }
    }

    private suspend fun mirrorOne(
        conn: ScannerSheetConn,
        accessToken: String,
        consignmentId: String,
        verdict: String,
        today: LocalDate
    ) = withContext(Dispatchers.IO) {
        val tabName = ScannerSheetRepository.resolveTabName(conn.tabPattern)
        val consignmentValues = ConfigSheetDriveApi.fetchColumnValues(
            accessToken, conn.sheetId, tabName, conn.matchColumn, httpClient
        )
        val dateValues = ConfigSheetDriveApi.fetchColumnValues(
            accessToken, conn.sheetId, tabName, conn.dateMatchColumn, httpClient
        )
        var targetRow = -1
        for (i in consignmentValues.indices) {
            if (consignmentValues[i].trim() != consignmentId.trim()) continue
            if (!isToday(dateValues.getOrNull(i).orEmpty(), today)) continue
            targetRow = i + 1 // 0-index → 1-indexed sheet row
            break
        }
        if (targetRow <= 0) {
            FirebaseErrorLogger.log("RemarkSheetMirror", "no_matching_row",
                "Consignment+today matched no row — skipped (never appended)",
                mapOf("sheet" to conn.sheetName, "consignment" to consignmentId))
            return@withContext
        }
        ConfigSheetDriveApi.writeCellValue(
            accessToken, conn.sheetId, tabName, conn.writeColumn, targetRow, verdict, httpClient
        )
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
