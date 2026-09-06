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
 * CC-remark → Google Sheet mirror (best-effort, never blocks).
 *
 * After a CC remark saves to Supabase, this writes Feedback / Validation /
 * Validator Name into the branch's connected remark sheet: the row where ALL
 * lookup rules match exactly. No matching row → skip + log (never append).
 *
 * Feedback = validation_remarks.category of the saved option (blank stays
 * blank). Validation = derived from Feedback (Willing to receive today →
 * Invalid, blank → blank, else Valid). Validator Name = CC agent who saved.
 *
 * Remark connections are the same connector list as the scanner
 * (config/connectors/{branchId}/current, ConfigConnectorsFragment) —
 * purpose == "remark" with lookup + write rules. Scanner connections are
 * ignored here.
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
        appContext: Context, branchId: String, consignmentId: String,
        feedback: String, validatorName: String,
        onAuthNeeded: (() -> Unit)? = null,
    ) {
        if (branchId.isBlank() || consignmentId.isBlank()) return
        val fb = feedback.trim()
        val vn = validatorName.trim()
        val ctx = MirrorCtx(
            consignmentId.trim(), fb, deriveValidation(fb), vn,
            LocalDate.now(opsZone),
        )
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Most-specific scope covering today wins (range > month >
                // global) so the appropriate sheet gets the write.
                val conns = ScannerSheetRepository.loadConnections(branchId)
                    .filter { it.enabled && it.isRemarkConnection() }
                    .selectForDate(LocalDate.now(opsZone))
                if (conns.isEmpty()) {
                    FirebaseErrorLogger.log("RemarkSheetMirror", "no_remark_connection",
                        "No remark sheet connection for branch", mapOf("branchId" to branchId))
                    toastMain(appContext, "Sheet: no remark connection for this branch — feedback not mirrored")
                    return@launch
                }
                val token = silentWriteToken(appContext.applicationContext)
                if (token.isNullOrBlank()) {
                    FirebaseErrorLogger.log("RemarkSheetMirror", "no_write_token",
                        "No silent Sheets write token (one-time Google auth pending)",
                        mapOf("branchId" to branchId))
                    // Config access charai one-time auth popup (MainActivity) —
                    // na thakle ager toast- i thakbe.
                    if (onAuthNeeded != null) onAuthNeeded()
                    else toastMain(appContext, "Sheet: Google account not connected — feedback not mirrored")
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
                if (okRows > 0) toastMain(appContext, "✓ Sheet feedback updated")
                else if (lastSkip.isNotBlank()) toastMain(appContext, "Sheet: $lastSkip")
            } catch (e: Exception) {
                FirebaseErrorLogger.log("RemarkSheetMirror", "mirror_error",
                    e.message ?: "Mirror failed", mapOf("branchId" to branchId))
                toastMain(appContext, "Sheet: mirror failed — ${e.message?.take(60) ?: "error"}")
            }
        }
    }

    /** Values available to lookup/write rules for one remark save. feedback /
     *  validation / validatorName ride along from the caller (guaranteed
     *  fresh — the just-saved row may not be readable yet); created_at /
     *  author_name come from [extras], fetched from the latest validations
     *  row after the sheet row matches. Blank stays blank — never skipped. */
    data class MirrorCtx(
        val consignmentId: String,
        val feedback: String,
        val validation: String,
        val validatorName: String,
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
        // "03-07-2026 14:30") — everything else exact trim match (blank == blank).
        if (kind == SheetLookupKind.CREATED_AT) {
            val want = tryParseDate(lookupValue(kind, ctx)) ?: return lookupValue(kind, ctx).isBlank() && cell.trim().isBlank()
            return tryParseDate(cell.trim()) == want
        }
        return cell.trim() == lookupValue(kind, ctx).trim()
    }

    /** Event's value for a lookup source: caller values (feedback/validation/
     *  validator_name/consignment) first, then the fetched validations row
     *  (created_at/author_name). */
    private fun lookupValue(kind: String, ctx: MirrorCtx): String = when (kind) {
        SheetLookupKind.CONSIGNMENT -> ctx.consignmentId
        SheetLookupKind.FEEDBACK -> ctx.feedback
        SheetLookupKind.VALIDATION -> ctx.validation
        SheetLookupKind.VALIDATOR_NAME -> ctx.validatorName
        SheetLookupKind.CREATED_AT -> ctx.extras[SheetLookupKind.CREATED_AT].orEmpty()
        SheetLookupKind.AUTHOR_NAME -> ctx.extras[SheetLookupKind.AUTHOR_NAME].orEmpty()
        else -> ctx.extras[kind].orEmpty()
    }

    private fun lookupWant(kind: String, ctx: MirrorCtx): String = when (kind) {
        SheetLookupKind.CONSIGNMENT -> ctx.consignmentId
        SheetLookupKind.TODAY -> "আজকের তারিখ"
        SheetLookupKind.EMPLOYEE -> "(scanner)"
        else -> lookupValue(kind, ctx).ifBlank { "(খালি)" }
    }

    private fun writeValue(kind: String, ctx: MirrorCtx): String = when (kind) {
        SheetWriteKind.FEEDBACK -> ctx.feedback
        SheetWriteKind.VALIDATION -> ctx.validation
        SheetWriteKind.VALIDATOR_NAME -> ctx.validatorName
        else -> ""
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
            // Only what lookups can reference: created_at + author_name.
            // Names resolve via Firebase (resolveAgentName) so an
            // RLS-sensitive users join can never sink this read.
            val plainUrl = "${SupabaseConfig.PROJECT_URL}/rest/v1/validations" +
                "?select=author_system_id,created_at" +
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
            mapOf(
                SheetLookupKind.CREATED_AT to createdDhaka,
                SheetLookupKind.AUTHOR_NAME to resolveAgentName(authorSid),
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

    /** Consolidated CC per consignment for bulk sync: latest CC row wins. */
    private data class BulkVals(
        val feedback: String,
        val validation: String,
        val validatorName: String,
    )

    private data class BulkCounts(
        var scanned: Int = 0,
        var filled: Int = 0,
        var syncedRows: Int = 0,
        var syncedCells: Int = 0,
        var noCc: Int = 0,
    )

    /**
     * Bulk Sync to Sheet (Call Center header button, same as the extension's
     * ⇪ Sheet): branch-wise — every branch uses ONLY its own remark
     * connections → its own sheet. Sheet-driven: read each connection's today
     * tab, take rows whose write cells are blank, match by consignment id
     * against Supabase's consolidated CC (latest CC remark per consignment
     * today), fill ONLY the blank cells. Never overwrites filled cells,
     * never appends. Returns a human-readable summary (Bangla).
     *
     * [onProgress] fires on the caller's thread (IO when called from a
     * coroutine) with short labels — post to main before touching views.
     */
    suspend fun bulkSyncToSheet(
        appContext: Context,
        branchIds: List<String>,
        onProgress: (String) -> Unit = {},
        onAuthNeeded: (() -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val branches = branchIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (branches.isEmpty()) return@withContext "কোনো branch পাওয়া যায়নি"
        val token = silentWriteToken(appContext.applicationContext)
        if (token.isNullOrBlank()) {
            if (onAuthNeeded != null) onAuthNeeded()
            return@withContext "Google account connected নেই — connect kore abar Sync chapun"
        }
        val today = LocalDate.now(opsZone)
        val todayStartIso = today.atStartOfDay(opsZone).toInstant().toString()

        // 1. Catalog: english remark → feedback (category).
        val catalog: Map<String, String> = try {
            SupabaseClientManager.fetchRemarkOptions("RemarkSheetMirror", "CC")
                .associate { it.textEn.trim() to it.category.trim() }
                .filterKeys { it.isNotEmpty() }
        } catch (_: Exception) { emptyMap() }

        // 2. Consolidated CC per (branch, consignment) for today.
        val consolidated = mutableMapOf<String, BulkVals>()
        for (branchId in branches) {
            val rows = try {
                SupabaseClientManager.fetchValidations(
                    "RemarkSheetMirror", "bulk_sync", listOf(
                        "branch_id" to "eq.$branchId",
                        "created_at" to "gte.$todayStartIso",
                        "order" to "created_at.desc",
                    )
                )
            } catch (_: Exception) { emptyList() }
            val latestCcByCid = mutableMapOf<String, org.json.JSONObject>()
            val latestMsByCid = mutableMapOf<String, Long>()
            rows.forEach { row ->
                if (row.optString("source") != "CC") return@forEach
                val cid = row.optString("consignment").trim()
                if (cid.isEmpty()) return@forEach
                val ms = SupabaseRemarkValidationWriter.parseDbTimestampMillis(row.optString("created_at"))
                if (ms >= (latestMsByCid[cid] ?: -1L)) {
                    latestMsByCid[cid] = ms
                    latestCcByCid[cid] = row
                }
            }
            latestCcByCid.forEach { (cid, row) ->
                val fb = catalog[row.optString("remarks").trim()].orEmpty()
                consolidated["${branchId}__$cid"] = BulkVals(
                    feedback = fb,
                    validation = deriveValidation(fb),
                    validatorName = resolveAgentName(row.optString("author_system_id")),
                )
            }
        }
        if (consolidated.isEmpty())
            return@withContext "Supabase-এ আজকের কোনো CC remark নেই — লেখার কিছু নেই"

        // 3. Per branch → its own connections → its own sheet.
        var totConns = 0
        val tot = BulkCounts()
        var totNoCc = 0
        val errs = mutableListOf<String>()
        for (branchId in branches) {
            val conns = try {
                ScannerSheetRepository.loadConnections(branchId)
                    .filter { it.enabled && it.isRemarkConnection() }
                    .selectForDate(today)
            } catch (e: Exception) {
                errs.add("$branchId: connection পড়া যায়নি")
                continue
            }
            if (conns.isEmpty()) continue
            for (conn in conns) {
                totConns++
                onProgress(conn.sheetName.ifBlank { conn.sheetId.ifBlank { branchId } })
                try {
                    val c = bulkSyncOneConnection(token, branchId, conn, consolidated, today)
                    tot.scanned += c.scanned; tot.filled += c.filled
                    tot.syncedRows += c.syncedRows; tot.syncedCells += c.syncedCells
                    totNoCc += c.noCc
                } catch (e: Exception) {
                    errs.add("${conn.sheetName.ifBlank { branchId }}: ${e.message?.take(80) ?: "sync failed"}")
                }
            }
        }
        if (totConns == 0) return@withContext "আজকের জন্য কোনো branch-এ remark connection নেই (scope দেখুন)"
        var msg = "✓ ${tot.syncedRows} row synced (${tot.syncedCells} cells) · " +
            "${tot.filled} already filled · $totNoCc no CC yet · " +
            "${tot.scanned} sheet rows দেখা ($totConns connection)"
        if (errs.isNotEmpty()) msg += " · ⚠ ${errs.size} error: ${errs.take(2).joinToString("; ")}" +
            if (errs.size > 2) "…" else ""
        msg
    }

    /** One connection → its own sheet: blank write cells × consolidated CC. */
    private suspend fun bulkSyncOneConnection(
        accessToken: String,
        branchId: String,
        conn: ScannerSheetConn,
        consolidated: Map<String, BulkVals>,
        today: LocalDate,
    ): BulkCounts = withContext(Dispatchers.IO) {
        val res = BulkCounts()
        val lookups = conn.effectiveLookups()
        val writes = conn.effectiveWrites().filter {
            it.kind == SheetWriteKind.FEEDBACK ||
                it.kind == SheetWriteKind.VALIDATION ||
                it.kind == SheetWriteKind.VALIDATOR_NAME
        }
        if (lookups.isEmpty() || writes.isEmpty())
            throw IllegalStateException("lookup/write rule নেই")
        val cidRule = lookups.firstOrNull { it.kind == SheetLookupKind.CONSIGNMENT }
            ?: throw IllegalStateException("consignment lookup নেই")
        val tabName = ScannerSheetRepository.resolveTabName(conn.tabPattern)
        val headerRow = conn.resolvedHeaderRow()
        val headerCache = mutableMapOf<String, List<String>>()
        val cidLetter = resolveLetter(accessToken, conn.sheetId, tabName,
            cidRule.colRef, cidRule.mode, headerRow, headerCache)
            ?: throw IllegalStateException("consignment column '${cidRule.colRef.trim()}' পাওয়া যায়নি")
        // Date lookups verify the row is really today's (tab-scoped safety).
        // Other lookup kinds (feedback/validation/...) are the values being
        // filled, so matching on them would never hit a blank row — skipped.
        val dateRules = lookups.filter {
            it.kind == SheetLookupKind.TODAY || it.kind == SheetLookupKind.CREATED_AT
        }
        val dateLetters = mutableMapOf<SheetLookupRule, String>()
        dateRules.forEach { rule ->
            dateLetters[rule] = resolveLetter(accessToken, conn.sheetId, tabName,
                rule.colRef, rule.mode, headerRow, headerCache)
                ?: throw IllegalStateException("lookup column '${rule.colRef.trim()}' পাওয়া যায়নি")
        }
        val writeLetters = writes.map { rule ->
            rule to (resolveLetter(accessToken, conn.sheetId, tabName,
                rule.colRef, rule.mode, headerRow, headerCache)
                ?: throw IllegalStateException("write column '${rule.colRef.trim()}' পাওয়া যায়নি"))
        }
        suspend fun colValues(letter: String): List<String> =
            ConfigSheetDriveApi.fetchColumnValues(accessToken, conn.sheetId, tabName, letter, httpClient)
        val cidCol = colValues(cidLetter)
        val dateCols = mutableMapOf<String, List<String>>()
        dateLetters.values.distinct().forEach { letter -> dateCols[letter] = colValues(letter) }
        val writeCols = mutableMapOf<String, MutableList<String>>()
        writeLetters.map { it.second }.distinct().forEach { letter ->
            writeCols[letter] = colValues(letter).toMutableList()
        }
        res.scanned = cidCol.size
        for (i in cidCol.indices) {
            val cid = cidCol[i].trim()
            if (cid.isEmpty()) continue
            var dateOk = true
            dateLetters.forEach { (_, letter) ->
                if (!isToday((dateCols[letter].orEmpty().getOrNull(i).orEmpty()).trim(), today)) dateOk = false
            }
            if (!dateOk) continue
            val blanks = writeLetters.filter { (_, letter) ->
                (writeCols[letter].orEmpty().getOrNull(i).orEmpty()).trim().isEmpty()
            }
            if (blanks.isEmpty()) { res.filled++; continue }
            val vals = consolidated["${branchId}__$cid"] ?: run { res.noCc++; return@run null }
                ?: continue
            for ((rule, letter) in blanks) {
                val v = when (rule.kind) {
                    SheetWriteKind.FEEDBACK -> vals.feedback
                    SheetWriteKind.VALIDATION -> vals.validation
                    else -> vals.validatorName
                }
                ConfigSheetDriveApi.writeCellValue(
                    accessToken, conn.sheetId, tabName, letter, i + 1, v, httpClient
                )
                val col = writeCols[letter]!!
                while (col.size <= i) col.add("")
                col[i] = v
                res.syncedCells++
            }
            res.syncedRows++
            if (res.syncedRows % 10 == 0) delay(300) // Sheets quota safety
        }
        res
    }

    /** Dry-run for the Connectors Test button: same match as the live mirror
     *  but writes NOTHING. Returns a human-readable report. */
    suspend fun dryRunReport(
        appContext: Context, conn: ScannerSheetConn, consignmentId: String,
        feedback: String = "TEST", validatorName: String = "TEST",
    ): String =
        withContext(Dispatchers.IO) {
            if (consignmentId.isBlank()) return@withContext "Consignment ID দিন"
            val token = silentWriteToken(appContext.applicationContext)
                ?: return@withContext "Google account connected নেই — Connectors থেকে account connect করুন"
            val fb = feedback.trim()
            val ctx = MirrorCtx(consignmentId.trim(), fb, deriveValidation(fb), validatorName.trim(), LocalDate.now(opsZone))
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

    /** Read token for Live CC mode (Call Center reads today's consignment IDs
     *  from the branch's live sheet). The write scope includes read. */
    suspend fun readToken(appContext: Context): String? = silentWriteToken(appContext)

    /** Today's consignment IDs from one branch's live sheet ([config/liveCc]).
     *  [note] explains skips (no live sheet set, conn gone, no consignment
     *  lookup, empty) — callers surface it, never crash. */
    data class LiveBranchIds(
        val branchId: String,
        val ids: List<String>,
        val note: String?,
    )

    suspend fun fetchLiveConsignments(
        accessToken: String,
        branchIds: List<String>,
    ): List<LiveBranchIds> = withContext(Dispatchers.IO) {
        val today = LocalDate.now(opsZone)
        branchIds.map { it.trim() }.filter { it.isNotBlank() }.distinct().map { branchId ->
            try {
                val ref = ScannerSheetRepository.loadLiveCc(branchId)
                    ?: return@map LiveBranchIds(branchId, emptyList(), "Live sheet set kora nei")
                val conn = ScannerSheetRepository.loadConnection(branchId, ref.connectionId)
                    ?: return@map LiveBranchIds(branchId, emptyList(), "Live sheet connection paini")
                if (!conn.enabled)
                    return@map LiveBranchIds(branchId, emptyList(), "Live sheet disabled")
                val lookups = conn.effectiveLookups()
                val cidRule = lookups.firstOrNull { it.kind == SheetLookupKind.CONSIGNMENT }
                    ?: return@map LiveBranchIds(branchId, emptyList(), "Consignment lookup nei")
                val tabName = ScannerSheetRepository.resolveTabName(conn.tabPattern)
                val headerRow = conn.resolvedHeaderRow()
                val headerCache = mutableMapOf<String, List<String>>()
                val cidLetter = resolveLetter(accessToken, conn.sheetId, tabName,
                    cidRule.colRef, cidRule.mode, headerRow, headerCache)
                    ?: return@map LiveBranchIds(branchId, emptyList(),
                        "Consignment column '${cidRule.colRef.trim()}' paini")
                val dateRules = lookups.filter {
                    it.kind == SheetLookupKind.TODAY || it.kind == SheetLookupKind.CREATED_AT
                }
                val dateCols = mutableMapOf<String, List<String>>()
                dateRules.map { it to resolveLetter(accessToken, conn.sheetId, tabName,
                    it.colRef, it.mode, headerRow, headerCache) }.forEach { (rule, letter) ->
                    if (letter == null) return@map LiveBranchIds(branchId, emptyList(),
                        "Lookup column '${rule.colRef.trim()}' paini")
                    if (!dateCols.containsKey(letter)) {
                        dateCols[letter] = ConfigSheetDriveApi.fetchColumnValues(
                            accessToken, conn.sheetId, tabName, letter, httpClient)
                    }
                }
                // dateLetters in rule order for row checks:
                val dateLetters = dateRules.map { rule ->
                    resolveLetter(accessToken, conn.sheetId, tabName,
                        rule.colRef, rule.mode, headerRow, headerCache)!!
                }
                val cidCol = ConfigSheetDriveApi.fetchColumnValues(
                    accessToken, conn.sheetId, tabName, cidLetter, httpClient)
                val ids = mutableListOf<String>()
                cidCol.forEachIndexed { i, cell ->
                    val cid = cell.trim()
                    if (cid.isEmpty()) return@forEachIndexed
                    val dateOk = dateLetters.all { letter ->
                        isToday((dateCols[letter].orEmpty().getOrNull(i).orEmpty()).trim(), today)
                    }
                    if (dateOk && cid !in ids) ids.add(cid)
                }
                if (ids.isEmpty()) LiveBranchIds(branchId, emptyList(), "Ajker kono consignment nei")
                else LiveBranchIds(branchId, ids, null)
            } catch (e: Exception) {
                LiveBranchIds(branchId, emptyList(),
                    e.message?.take(80) ?: "sheet পড়া যায়নি")
            }
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
