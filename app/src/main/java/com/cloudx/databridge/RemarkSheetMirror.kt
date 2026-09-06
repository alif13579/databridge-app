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
import kotlinx.coroutines.tasks.await
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

    fun mirror(
        appContext: Context, branchId: String, consignmentId: String, verdict: String,
        remark: String = "", note: String = "", status: String = ""
    ) {
        if (branchId.isBlank() || consignmentId.isBlank() || verdict.isBlank()) return
        val ctx = MirrorCtx(consignmentId.trim(), verdict, remark, note, status, LocalDate.now(opsZone))
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val conns = ScannerSheetRepository.loadConnections(branchId)
                    .filter { it.enabled && it.isRemarkConnection() }
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
                var okRows = 0
                var lastSkip = ""
                conns.forEach { conn ->
                    when (val out = mirrorOneWithRetry(conn, token, ctx)) {
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

    /** Values available to lookup/write rules for one remark save. verdict /
     *  remark / note / status ride along from the caller (guaranteed fresh —
     *  the just-saved row may not be readable yet); everything else comes
     *  from [extras], fetched from the latest validations row after the
     *  sheet row matches. */
    data class MirrorCtx(
        val consignmentId: String,
        val verdict: String,
        val remark: String,
        val note: String,
        val status: String,
        val today: LocalDate,
        val extras: Map<String, String> = emptyMap(),
    )

    private val LETTER_RE = Regex("^[A-Za-z]{1,3}$")

    /** Resolves a rule's column ref to a letter. INDEX mode: letter ("C") or
     *  1-based number ("3", normalized). TEXT mode: exact header match
     *  (trimmed, case-sensitive) against [headerRow]. Returns null when a
     *  header text finds no column. */
    private suspend fun resolveLetter(
        accessToken: String, sheetId: String, tab: String,
        ref: String, mode: String, headerRow: Int,
        headerCache: MutableMap<String, List<String>>
    ): String? {
        val t = ref.trim()
        if (t.isEmpty()) return null
        if (mode != SheetColMode.TEXT) {
            if (LETTER_RE.matches(t)) return t.uppercase()
            // Numbers ("3" → "C") via the same parser the wizard uses.
            val idx = ConfigSheetParseUtil.parseColInput(t) ?: return null
            return ConfigSheetParseUtil.colIndexToLetter(idx)
        }
        val key = "$tab#$headerRow"
        val headers = headerCache.getOrPut(key) {
            ConfigSheetDriveApi.fetchRowValues(accessToken, sheetId, tab, headerRow, httpClient)
        }
        val idx = headers.indexOfFirst { it.trim() == t }
        if (idx < 0) return null
        return ConfigSheetParseUtil.colIndexToLetter(idx + 1) // 1-based
    }

    private fun lookupMatches(kind: String, cell: String, ctx: MirrorCtx): Boolean {
        if (kind == SheetLookupKind.TODAY) return isToday(cell, ctx.today)
        if (kind == SheetLookupKind.EMPLOYEE) return false // scanner-only, filtered before match
        // created_at compares by DATE (sheet "03-Jul-2026" vs stamp
        // "03-07-2026 14:30") — everything else exact trim match.
        if (kind == SheetWriteKind.CREATED_AT) {
            val want = tryParseDate(lookupValue(kind, ctx)) ?: return false
            return tryParseDate(cell.trim()) == want
        }
        return cell.trim() == lookupValue(kind, ctx)
    }

    /** Event's value for a lookup source: caller values first, then the
     *  fetched validations row (extras), so a lookup can point at ANY of them
     *  — e.g. {Date header, created_at} or {Agent header, author_name}. */
    private fun lookupValue(kind: String, ctx: MirrorCtx): String = when (kind) {
        SheetLookupKind.CONSIGNMENT -> ctx.consignmentId
        SheetWriteKind.VERDICT -> ctx.verdict
        SheetWriteKind.REMARK -> ctx.remark
        SheetWriteKind.NOTE -> ctx.note
        SheetWriteKind.STATUS -> ctx.status
        else -> ctx.extras[kind].orEmpty()
    }

    private fun lookupWant(kind: String, ctx: MirrorCtx): String = when (kind) {
        SheetLookupKind.CONSIGNMENT -> ctx.consignmentId
        SheetLookupKind.TODAY -> "আজকের তারিখ"
        SheetLookupKind.EMPLOYEE -> "(scanner)"
        else -> lookupValue(kind, ctx).ifBlank { "(খালি)" }
    }

    private fun writeValue(kind: String, ctx: MirrorCtx): String = when (kind) {
        SheetWriteKind.VERDICT -> ctx.verdict
        SheetWriteKind.REMARK -> ctx.remark
        SheetWriteKind.NOTE -> ctx.note
        SheetWriteKind.STATUS -> ctx.status
        SheetWriteKind.TODAY -> ctx.today.toString() // yyyy-MM-dd
        SheetWriteKind.CONSIGNMENT -> ctx.consignmentId
        // Row extras ("" when the fetch failed); caller values stay primary.
        SheetWriteKind.REMARKS_STATUS_COL -> ctx.extras[kind] ?: ctx.status
        SheetWriteKind.REMARKS_COL -> ctx.extras[kind] ?: ctx.remark
        SheetWriteKind.NOTE_COL -> ctx.extras[kind] ?: ctx.note
        else -> ctx.extras[kind].orEmpty()
    }

    private val nameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun String.encodeParam(): String = java.net.URLEncoder.encode(this, "UTF-8")

    /** system_id → display name via the users_by_systemId index + Firebase
     *  profile (cached). Falls back to the raw system_id — never blank. */
    private suspend fun resolveAgentName(systemId: String): String {
        val sid = systemId.trim()
        if (sid.isEmpty()) return ""
        nameCache[sid]?.let { return it }
        val name = try {
            val db = com.google.firebase.database.FirebaseDatabase.getInstance()
            val uid = withContext(Dispatchers.IO) {
                db.reference.child("users_by_systemId/$sid/uid").get().await()
                    .getValue(String::class.java)?.trim().orEmpty()
            }
            val full = if (uid.isBlank()) "" else withContext(Dispatchers.IO) {
                db.reference.child("users/$uid/profile/name").get().await()
                    .getValue(String::class.java)?.trim().orEmpty()
            }
            full.ifBlank { sid }
        } catch (_: Exception) { sid }
        nameCache[sid] = name
        return name
    }

    /** Latest validations row for [cid] → extras map (row columns + resolved
     *  names). Best-effort: empty map on any failure (caller values cover the
     *  core kinds). */
    private suspend fun fetchRowExtras(cid: String): Map<String, String> {
        return try {
            val token = SupabaseClientManager.getAccessToken() ?: return emptyMap()
            // Plain columns only: names resolve via Firebase (resolveAgentName)
            // so an RLS-sensitive users join can never sink this read.
            val plainUrl = "${SupabaseConfig.PROJECT_URL}/rest/v1/validations" +
                "?select=consignment,branch_id,assigned_to_system_id,author_system_id," +
                "remarks_status,remarks,created_at,customer_phone,note,source,consignment_status" +
                "&consignment=eq.${cid.encodeParam()}" +
                "&order=created_at.desc&limit=1"
            val text = withContext(Dispatchers.IO) {
                SupabaseClientManager.httpClient.newCall(
                    okhttp3.Request.Builder().url(plainUrl)
                        .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Accept", "application/json")
                        .get().build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.string()
                }
            } ?: return emptyMap()
            val arr = org.json.JSONArray(text)
            if (arr.length() == 0) return emptyMap()
            val o = arr.getJSONObject(0)
            fun s(k: String) = o.optString(k, "")
            val createdIso = s("created_at")
            val createdDhaka = runCatching {
                val instant = java.time.Instant.parse(createdIso)
                java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")
                    .withZone(java.time.ZoneId.of("Asia/Dhaka")).format(instant)
            }.getOrDefault(createdIso)
            val authorSid = s("author_system_id")
            val assignedSid = s("assigned_to_system_id")
            mapOf(
                SheetWriteKind.REMARKS_STATUS_COL to s("remarks_status"),
                SheetWriteKind.REMARKS_COL to s("remarks"),
                SheetWriteKind.NOTE_COL to s("note"),
                SheetWriteKind.SOURCE_COL to s("source"),
                SheetWriteKind.CREATED_AT to createdDhaka,
                SheetWriteKind.CUSTOMER_PHONE to s("customer_phone"),
                SheetWriteKind.CONSIGNMENT_STATUS to s("consignment_status"),
                SheetWriteKind.BRANCH_ID to s("branch_id"),
                SheetWriteKind.AUTHOR_SYSTEM_ID to authorSid,
                SheetWriteKind.ASSIGNED_TO_SYSTEM_ID to assignedSid,
                SheetWriteKind.AUTHOR_NAME to resolveAgentName(authorSid),
                SheetWriteKind.ASSIGNED_NAME to resolveAgentName(assignedSid),
            )
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** mirrorOne with retry: transient transport/write failures get 3 attempts
     *  (2s, 4s backoff). Deliberate skips (no matching row) throw nothing and
     *  are returned at once — retrying changes nothing. */
    private suspend fun mirrorOneWithRetry(
        conn: ScannerSheetConn, accessToken: String, ctx: MirrorCtx, attempts: Int = 3
    ): MirrorOutcome {
        var lastError = ""
        repeat(attempts) { n ->
            try {
                return mirrorOne(conn, accessToken, ctx)
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
        conn: ScannerSheetConn, accessToken: String, ctx: MirrorCtx
    ): MirrorOutcome = withContext(Dispatchers.IO) {
        // Row extras FIRST: lookups may point at row data (created_at,
        // author_name...) so values must exist before matching.
        val ctx2 = ctx.copy(extras = fetchRowExtras(ctx.consignmentId))
        when (val found = findTargetRow(conn, accessToken, ctx2)) {
            is FindResult.Miss -> MirrorOutcome.Skipped(found.reason)
            is FindResult.Hit -> {
                found.writes.forEach { (letter, kind) ->
                    ConfigSheetDriveApi.writeCellValue(
                        accessToken, conn.sheetId, found.tab, letter, found.row,
                        writeValue(kind, ctx2), httpClient
                    )
                }
                MirrorOutcome.Done(found.row)
            }
        }
    }

    private sealed class FindResult {
        data class Hit(
            val tab: String, val row: Int,
            val writes: List<Pair<String, String>>, // (letter, source kind)
            val detail: String, // per-rule match diagnostics
            val scanned: Int
        ) : FindResult()
        data class Miss(val reason: String) : FindResult()
    }

    /** Shared row-match used by both the live mirror and the dry-run test:
     *  today's tab → resolve every lookup colRef (letter or header) → fetch
     *  each column once → first row where ALL rules match exactly. Each write
     *  colRef resolves the same way; unresolvable write columns fail the whole
     *  match (writing half the rules would corrupt the row). Exact match or
     *  nothing — never appended. */
    private suspend fun findTargetRow(
        conn: ScannerSheetConn, accessToken: String, ctx: MirrorCtx
    ): FindResult = withContext(Dispatchers.IO) {
        // Mirror enforces remark-kind lookups only (employee belongs to the
        // scanner flow) and skips scanner value writes.
        val lookups = conn.effectiveLookups()
            .filter { it.kind in SheetLookupKind.REMARK_KINDS }
        val writes = conn.effectiveWrites()
            .filter { it.kind in SheetWriteKind.REMARK_KINDS }
        if (lookups.isEmpty() || writes.isEmpty()) {
            return@withContext FindResult.Miss("remark lookup/write rule নেই — connection configure করুন")
        }
        val tabName = ScannerSheetRepository.resolveTabName(conn.tabPattern)
        val headerRow = conn.resolvedHeaderRow()
        val headerCache = mutableMapOf<String, List<String>>()
        // Resolve lookup columns first (fail fast with WHICH ref broke).
        val lookupCols = lookups.map { rule ->
            val letter = resolveLetter(accessToken, conn.sheetId, tabName,
                rule.colRef, rule.mode, headerRow, headerCache)
                ?: return@withContext FindResult.Miss(
                    "lookup column '${rule.colRef.trim()}' পাওয়া যায়নি" +
                        if (rule.mode == SheetColMode.TEXT) " (header row $headerRow-তে exact header নেই)" else " (letter/number ঠিক নেই)")
            rule to letter
        }
        val writeCols = writes.map { rule ->
            val letter = resolveLetter(accessToken, conn.sheetId, tabName,
                rule.colRef, rule.mode, headerRow, headerCache)
                ?: return@withContext FindResult.Miss(
                    "write column '${rule.colRef.trim()}' পাওয়া যায়নি" +
                        if (rule.mode == SheetColMode.TEXT) " (header row $headerRow-তে exact header নেই)" else " (letter/number ঠিক নেই)")
            rule to letter
        }
        val columns = lookupCols.map { (_, letter) ->
            letter to ConfigSheetDriveApi.fetchColumnValues(accessToken, conn.sheetId, tabName, letter, httpClient)
        }.toMap()
        val scanned = columns.values.maxOfOrNull { it.size } ?: 0
        if (scanned == 0) {
            return@withContext FindResult.Miss("tab '$tabName' খালি — tab/column মিলছে না")
        }
        val diag = StringBuilder()
        for (i in 0 until scanned) {
            val fails = lookupCols.filter { (rule, letter) ->
                !lookupMatches(rule.kind, columns[letter].orEmpty().getOrNull(i).orEmpty(), ctx)
            }
            if (fails.isEmpty()) {
                val writePairs = writeCols.map { (rule, letter) -> letter to rule.kind }
                lookupCols.forEach { (rule, letter) ->
                    diag.append("${rule.colRef.trim()}(${letter})='${lookupWant(rule.kind, ctx)}' ✓; ")
                }
                return@withContext FindResult.Hit(tabName, i + 1, writePairs, diag.toString(), scanned)
            }
        }
        // No exact row: say WHICH rule never matched (first failing rule's want).
        val wantList = lookupCols.joinToString(", ") { (rule, _) ->
            "${rule.colRef.trim()}='${lookupWant(rule.kind, ctx)}'"
        }
        return@withContext FindResult.Miss(
            "exact match নেই ($wantList — $scanned row দেখা হয়েছে)। কখনো append হয় না")
    }

    /** True when a Sheets date cell (formatted text) falls on [today]. */
    private fun isToday(cell: String, today: LocalDate): Boolean =
        tryParseDate(cell.trim()) == today

    /** Parses the sheet's zoo of date formats (plus our Dhaka stamp and ISO)
     *  to a LocalDate. Null when unparseable. */
    private fun tryParseDate(raw: String): LocalDate? {
        if (raw.isEmpty()) return null
        for (fmt in datePatterns) {
            runCatching { return LocalDate.parse(raw, fmt) }
        }
        // Our own Dhaka stamp ("dd-MM-yyyy HH:mm") and ISO instants.
        runCatching {
            return LocalDate.parse(raw.substringBefore(" "),
                java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy", java.util.Locale.ENGLISH))
        }
        runCatching { return java.time.Instant.parse(raw)
            .atZone(java.time.ZoneId.of("Asia/Dhaka")).toLocalDate() }
        return null
    }

    /** Dry-run for the Connectors Test button: same match as the live mirror
     *  but writes NOTHING. Returns a human-readable report. */
    suspend fun dryRunReport(
        appContext: Context, conn: ScannerSheetConn, consignmentId: String,
        verdict: String = "TEST", remark: String = "", note: String = "", status: String = ""
    ): String =
        withContext(Dispatchers.IO) {
            if (consignmentId.isBlank()) return@withContext "Consignment ID দিন"
            val token = silentWriteToken(appContext.applicationContext)
                ?: return@withContext "Google account connected নেই — Connectors থেকে account connect করুন"
            val ctx = MirrorCtx(consignmentId.trim(), verdict, remark, note, status, LocalDate.now(opsZone))
            try {
                // Extras first (see mirrorOne): lookups may reference row data.
                val ctx2 = ctx.copy(extras = fetchRowExtras(ctx.consignmentId))
                when (val found = findTargetRow(conn, token, ctx2)) {
                    is FindResult.Hit -> {
                        val w = found.writes.joinToString(", ") { (l, k) ->
                            val v = writeValue(k, ctx2)
                            "$l$k='${v.ifBlank { "(খালি)" }}'"
                        }
                        "✓ Row ${found.row} (tab '${found.tab}') মিলেছে\n${found.detail}\nলিখবে: $w\n${found.scanned} row দেখা হয়েছে। (কিছু লেখা হয়নি)"
                    }
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
