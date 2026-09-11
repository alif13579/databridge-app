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
    // (incl. short-year "03-Jul-26" the sheet often renders, and US-style
    // "M/d/yy" like "9/10/26").
    private val datePatterns = listOf(
        "yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "d/M/yyyy", "M/d/yyyy",
        "d/M/yy", "M/d/yy", "dd/MM/yy", "MM/dd/yy",
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
                // All-in-one: CC bindings only (CallCenter 🔌). Scope covering
                // today wins (range > month > global).
                val targets = SheetLibraryRepository.resolveCcTargets(
                    branchId, LocalDate.now(opsZone))
                if (targets.isEmpty()) {
                    FirebaseErrorLogger.log("RemarkSheetMirror", "no_cc_binding",
                        "No CC binding for branch", mapOf("branchId" to branchId))
                    toastMain(appContext, "Sheet: no CC binding for this branch — bind a sheet from the CallCenter socket")
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
                targets.forEach { target ->
                    when (val out = mirrorOneWithRetry(
                        target.conn, token, ctx,
                        filters = target.binding.effectiveFilters(),
                        filterLogic = target.binding.filterLogic,
                    )) {
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
        // created_at compares by DATE (sheet "09/10/2026" vs stamp
        // "2026-09-10 14:41:38.608242+00") — slash cells like 09/10 are
        // ambiguous (M/d vs d/M), so EITHER reading matching counts.
        // Everything else exact trim match (blank == blank).
        if (kind == SheetLookupKind.CREATED_AT) {
            val rawWant = lookupValue(kind, ctx)
            if (rawWant.isBlank()) return cell.trim().isBlank()
            val want = tryParseDate(rawWant) ?: return false
            if (tryParseDate(cell.trim()) == want) return true
            return want in slashCandidates(cell.trim())
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
        SheetLookupKind.TODAY -> "Today's date"
        SheetLookupKind.EMPLOYEE -> "(scanner)"
        // Show the PARSED date, not the raw ISO stamp — readable + tells
        // whether the want itself was understood.
        SheetLookupKind.CREATED_AT -> createdAtWant(ctx)
        else -> lookupValue(kind, ctx).ifBlank { "(blank)" }
    }

    private fun createdAtWant(ctx: MirrorCtx): String {
        val raw = lookupValue(SheetLookupKind.CREATED_AT, ctx)
        if (raw.isBlank()) return "(blank)"
        return tryParseDate(raw)?.toString() ?: "$raw (date bojha jayni!)"
    }

    private fun writeValue(kind: String, ctx: MirrorCtx): String = when (kind) {
        SheetWriteKind.FEEDBACK -> ctx.feedback
        SheetWriteKind.VALIDATION -> ctx.validation
        SheetWriteKind.VALIDATOR_NAME -> ctx.validatorName
        else -> ""
    }

    private val nameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun String.encodeParam(): String = java.net.URLEncoder.encode(this, "UTF-8")

    /** system_id → display name via Supabase users (source of truth) with
     *  Firebase fallback (shared resolver, cached). Falls back to the raw
     *  system_id — never blank. */
    private suspend fun resolveAgentName(systemId: String): String {
        val sid = systemId.trim()
        if (sid.isEmpty()) return ""
        nameCache[sid]?.let { return it }
        val name = UserNameResolver.resolveNameBySystemId(sid).ifBlank { sid }
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
            val createdDhaka = parseSupabaseInstantToDhaka(createdIso)
                ?: createdIso
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
        conn: ScannerSheetConn, accessToken: String, ctx: MirrorCtx, attempts: Int = 3,
        filters: List<CcFetchFilter> = emptyList(),
        filterLogic: String = CcFilterLogic.AND,
    ): MirrorOutcome {
        var lastError = ""
        repeat(attempts) { n ->
            try {
                return mirrorOne(conn, accessToken, ctx, filters, filterLogic)
            } catch (e: Exception) {
                lastError = e.message?.take(120) ?: "sheet write failed"
                if (n < attempts - 1) delay(if (n == 0) 2000L else 4000L)
            }
        }
        return MirrorOutcome.Skipped("$lastError ($attempts attempts)")
    }

    // mirrorOne throws on transport/write failures (retried above) and returns
    // Skipped only for deliberate no-match skips.
    private suspend fun mirrorOne(
        conn: ScannerSheetConn, accessToken: String, ctx: MirrorCtx,
        filters: List<CcFetchFilter> = emptyList(),
        filterLogic: String = CcFilterLogic.AND,
    ): MirrorOutcome = withContext(Dispatchers.IO) {
        // Row extras FIRST: lookups may point at row data (created_at,
        // author_name...) so values must exist before matching.
        val ctx2 = ctx.copy(extras = fetchRowExtras(ctx.consignmentId))
        when (val found = findTargetRow(conn, accessToken, ctx2, filters, filterLogic)) {
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
        conn: ScannerSheetConn, accessToken: String, ctx: MirrorCtx,
        filters: List<CcFetchFilter> = emptyList(),
        filterLogic: String = CcFilterLogic.AND,
    ): FindResult = withContext(Dispatchers.IO) {
        // Mirror enforces remark-kind lookups only (employee belongs to the
        // scanner flow) and skips scanner value writes.
        val lookups = conn.effectiveLookups()
            .filter { it.kind in SheetLookupKind.REMARK_KINDS }
        val writes = conn.effectiveWrites()
            .filter { it.kind in SheetWriteKind.REMARK_KINDS }
        if (lookups.isEmpty() || writes.isEmpty()) {
            return@withContext FindResult.Miss("no remark lookup/write rule — configure the connection")
        }
        val tabName = ScannerSheetRepository.resolveTabName(conn.tabPattern)
        val headerRow = conn.resolvedHeaderRow()
        val headerCache = mutableMapOf<String, List<String>>()
        // Resolve lookup columns first (fail fast with WHICH ref broke).
        val lookupCols = lookups.map { rule ->
            val letter = resolveLetter(accessToken, conn.sheetId, tabName,
                rule.colRef, rule.mode, headerRow, headerCache)
                ?: return@withContext FindResult.Miss(
                    "lookup column '${rule.colRef.trim()}' not found" +
                        if (rule.mode == SheetColMode.TEXT) " (no exact header in header row $headerRow)" else " (letter/number invalid)")
            rule to letter
        }
        val writeCols = writes.map { rule ->
            val letter = resolveLetter(accessToken, conn.sheetId, tabName,
                rule.colRef, rule.mode, headerRow, headerCache)
                ?: return@withContext FindResult.Miss(
                    "write column '${rule.colRef.trim()}' not found" +
                        if (rule.mode == SheetColMode.TEXT) " (no exact header in header row $headerRow)" else " (letter/number invalid)")
            rule to letter
        }
        val columns = lookupCols.map { (_, letter) ->
            letter to ConfigSheetDriveApi.fetchColumnValues(accessToken, conn.sheetId, tabName, letter, httpClient)
        }.toMap()
        // Targeting filters (same as Live fetch): unresolvable refs never
        // block — noted in diag, skipped silently.
        val ignoreCols = filters.mapNotNull { rule ->
            val letter = resolveLetter(accessToken, conn.sheetId, tabName,
                rule.colRef, rule.mode, headerRow, headerCache) ?: return@mapNotNull null
            rule to letter
        }
        val ignoreValues = ignoreCols.map { (_, letter) ->
            letter to ConfigSheetDriveApi.fetchColumnValues(accessToken, conn.sheetId, tabName, letter, httpClient)
        }.toMap()
        fun rowFilteredOut(i: Int): Boolean {
            if (ignoreCols.isEmpty()) return false
            val hits = ignoreCols.map { (rule, letter) ->
                SheetCellCompare.pass(
                    rule.op, ignoreValues[letter].orEmpty().getOrNull(i).orEmpty(), rule.value,
                    rule.valueType)
            }
            val pass = if (filterLogic == CcFilterLogic.OR) hits.any { it } else hits.all { it }
            return !pass
        }
        val scanned = columns.values.maxOfOrNull { it.size } ?: 0
        if (scanned == 0) {
            return@withContext FindResult.Miss("tab '$tabName' is empty — tab/column mismatch")
        }
        val diag = StringBuilder()
        var filtered = 0
        for (i in 0 until scanned) {
            val fails = lookupCols.filter { (rule, letter) ->
                !lookupMatches(rule.kind, columns[letter].orEmpty().getOrNull(i).orEmpty(), ctx)
            }
            if (fails.isEmpty()) {
                if (rowFilteredOut(i)) {
                    filtered++
                    continue
                }
                val writePairs = writeCols.map { (rule, letter) -> letter to rule.kind }
                lookupCols.forEach { (rule, letter) ->
                    diag.append("${rule.colRef.trim()}(${letter})='${lookupWant(rule.kind, ctx)}' ✓; ")
                }
                return@withContext FindResult.Hit(tabName, i + 1, writePairs, diag.toString(), scanned)
            }
        }
        // No exact row: say WHICH rule never matched + WHAT the column
        // actually holds (blank count + samples) so a format/empty mismatch
        // is obvious without opening the sheet.
        val wantList = lookupCols.joinToString(", ") { (rule, _) ->
            "${rule.colRef.trim()}='${lookupWant(rule.kind, ctx)}'"
        }
        val sampleTxt = lookupCols.firstOrNull()?.let { (rule, letter) ->
            val cells = columns[letter].orEmpty()
            if (cells.isEmpty()) "" else {
                val blanks = cells.count { it.trim().isBlank() }
                val samples = cells.map { it.trim() }.filter { it.isNotBlank() }
                    .distinct().take(3).joinToString(" | ")
                " ${letter} col: $blanks khali" +
                    (samples.ifBlank { "" }.let { if (it.isBlank()) "" else ", found: $it" })
            }
        }.orEmpty()
        val filterTxt = if (filtered > 0) " ($filtered row filter-e bad)" else ""
        return@withContext FindResult.Miss(
            "no exact match ($wantList — $scanned rows scanned$filterTxt$sampleTxt). Never appended")
    }

    /** True when a Sheets date cell (formatted text) falls on [today].
     *  Slash cells like 09/10 are ambiguous (M/d vs d/M) — either reading
     *  matching today counts. */
    private fun isToday(cell: String, today: LocalDate): Boolean = isDateOn(cell, today)

    /** Same as [isToday] for an arbitrary ops day (range syncs). */
    private fun isDateOn(cell: String, date: LocalDate): Boolean {
        val t = cell.trim()
        if (t.isEmpty()) return false
        if (tryParseDate(t) == date) return true
        return date in slashCandidates(t)
    }

    /** Parses the sheet's zoo of date formats (plus our Dhaka stamp and ISO)
     *  to a LocalDate. Null when unparseable. */
    private fun tryParseDate(raw: String): LocalDate? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        for (fmt in datePatterns) {
            runCatching {
                var d = LocalDate.parse(t, fmt)
                // Short-year cells ("10-Sep-26", "9/10/26") parse to year 26 — roll forward.
                if (d.year < 100) d = d.plusYears(2000)
                return d
            }
        }
        // Extra shapes sheets actually contain: "Sep 10, 2026",
        // "10 Sep 2026", "2026.09.10", plus our Dhaka stamp
        // ("dd-MM-yyyy HH:mm") and ISO instants.
        val extras = listOf("MMM d, yyyy", "d MMM yyyy", "yyyy.MM.dd")
            .map { java.time.format.DateTimeFormatter.ofPattern(it, java.util.Locale.ENGLISH) }
        for (fmt in extras) {
            runCatching {
                var d = LocalDate.parse(t, fmt)
                if (d.year < 100) d = d.plusYears(2000)
                return d
            }
        }
        runCatching {
            var d = LocalDate.parse(t.substringBefore(" "),
                java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy", java.util.Locale.ENGLISH))
            if (d.year < 100) d = d.plusYears(2000)
            return d
        }
        // Supabase created_at shapes: "2026-09-10T14:41:38.608242+00:00",
        // "...Z", and the space variant "2026-09-10 14:41:38.608242+00".
        // Instant.parse rejects +00:00, OffsetDateTime rejects the space
        // form — normalizeIso fixes both before parsing (Dhaka date).
        runCatching { return java.time.OffsetDateTime.parse(normalizeIso(t))
            .atZoneSameInstant(java.time.ZoneId.of("Asia/Dhaka")).toLocalDate() }
        runCatching { return java.time.Instant.parse(t)
            .atZone(java.time.ZoneId.of("Asia/Dhaka")).toLocalDate() }
        // Bare "yyyy-MM-ddTHH:mm:ss" without zone (or space variant).
        runCatching { return java.time.LocalDateTime.parse(t,
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ENGLISH)).toLocalDate() }
        runCatching { return java.time.LocalDateTime.parse(t.substringBefore("."),
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate() }
        runCatching { return java.time.LocalDateTime.parse(normalizeIso(t).substringBefore("+").substringBefore("Z"),
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate() }
        return null
    }

    /** "2026-09-10 14:41:38.608242+00" → "2026-09-10T14:41:38.608242+00:00"
     *  so OffsetDateTime.parse accepts every Supabase created_at shape
     *  (space/T separator, +00/+0000/+00:00/Z suffix). */
    private fun normalizeIso(raw: String): String {
        var s = raw.trim()
        // Space separator → T ("2026-09-10 14:41:..." → "2026-09-10T14:41:...").
        if (s.length > 10 && s[10] == ' ' && Regex("^\\d{4}-\\d{2}-\\d{2} ").containsMatchIn(s)) {
            s = s.substring(0, 10) + "T" + s.substring(11)
        }
        // Bare trailing +00 / -00 (no minutes) → +00:00.
        if (s.endsWith("+00") || s.endsWith("-00")) s += ":00"
        // Compact +0000 / -0530 → +00:00 / -05:30.
        val compactTz = Regex("([+-])(\\d{2})(\\d{2})$")
        compactTz.find(s)?.let { m ->
            s = s.dropLast(5) + "${m.groupValues[1]}${m.groupValues[2]}:${m.groupValues[3]}"
        }
        return s
    }

    /** Both slash readings of an ambiguous cell ("09/10/2026" → Sep 10 AND
     *  Oct 9; "9/10/26" likewise). Unambiguous cells (day > 12, ISO, MMM)
     *  yield ≤ 1 date. Used ONLY for date-equality checks, never ordering. */
    private fun slashCandidates(raw: String): Set<LocalDate> {
        val t = raw.trim()
        if (t.isEmpty() || !t.contains('/')) return emptySet()
        val out = LinkedHashSet<LocalDate>()
        val slashFmts = listOf(
            "M/d/yyyy", "d/M/yyyy", "MM/dd/yyyy", "dd/MM/yyyy",
            "M/d/yy", "d/M/yy", "MM/dd/yy", "dd/MM/yy",
        ).map { java.time.format.DateTimeFormatter.ofPattern(it, java.util.Locale.ENGLISH) }
        for (f in slashFmts) {
            runCatching {
                var d = LocalDate.parse(t, f)
                if (d.year < 100) d = d.plusYears(2000)
                out.add(d)
            }
        }
        return out
    }

    /** Supabase created_at ("...+00:00", "...Z", or space "+00" variant)
     *  → Dhaka "dd-MM-yyyy HH:mm". Null when unparseable (caller falls
     *  back to raw). */
    private fun parseSupabaseInstantToDhaka(createdIso: String): String? {
        val t = createdIso.trim()
        if (t.isEmpty()) return null
        val fmt = java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")
            .withZone(java.time.ZoneId.of("Asia/Dhaka"))
        runCatching { return fmt.format(java.time.OffsetDateTime.parse(normalizeIso(t)).toInstant()) }
        runCatching { return fmt.format(java.time.Instant.parse(t)) }
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
        var ignored: Int = 0,
    )

    /** Structured progress for a range bulk sync (service notification +
     *  dialog). [pending] = rows of the current sheet still to process. */
    data class BulkProgress(
        val day: LocalDate,
        val dayIndex: Int, // 1-based
        val dayCount: Int,
        val label: String, // sheet/connection label
        val rowsDone: Int,
        val rowsTotal: Int,
    ) {
        val pending: Int get() = (rowsTotal - rowsDone).coerceAtLeast(0)
    }

    /**
     * Bulk Sync to Sheet (Call Center header button, same as the extension's
     * ⇪ Sheet): branch-wise — every branch uses ONLY its own remark
     * connections → its own sheet. Sheet-driven: read each connection's tab
     * for EACH day in [startDate]..[endDate] (default: today only), take rows
     * whose write cells are blank, match by consignment id against Supabase's
     * consolidated CC (latest CC remark per consignment per day), fill ONLY
     * the blank cells. Never overwrites filled cells, never appends. Returns
     * a human-readable total summary.
     *
     * [onProgress] fires on the caller's thread (IO when called from a
     * coroutine) with short labels — post to main before touching views.
     * [onProgressDetail] carries per-sheet row counts for notifications.
     */
    suspend fun bulkSyncToSheet(
        appContext: Context,
        branchIds: List<String>,
        onProgress: (String) -> Unit = {},
        onAuthNeeded: (() -> Unit)? = null,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
        onProgressDetail: ((BulkProgress) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val branches = branchIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (branches.isEmpty()) return@withContext "no branch found"
        val token = silentWriteToken(appContext.applicationContext)
        if (token.isNullOrBlank()) {
            if (onAuthNeeded != null) onAuthNeeded()
            return@withContext "Google account not connected — connect and tap Sync again"
        }
        val today = LocalDate.now(opsZone)
        var start = startDate ?: today
        var end = endDate ?: start
        if (end.isBefore(start)) { val t = start; start = end; end = t }
        val days = mutableListOf<LocalDate>()
        var d = start
        while (!d.isAfter(end) && days.size < 32) { days.add(d); d = d.plusDays(1) }
        if (!d.isAfter(end)) return@withContext "Date range too large — max 31 days at once"
        val rangeLabel = if (days.size == 1) days.first().toString()
            else "${days.first()} → ${days.last()} (${days.size} days)"
        val rangeStartIso = start.atStartOfDay(opsZone).toInstant().toString()
        val rangeEndIso = end.plusDays(1).atStartOfDay(opsZone).toInstant().toString()

        // 1. Catalog: english remark → feedback (category).
        val catalog: Map<String, String> = try {
            SupabaseClientManager.fetchRemarkOptions("RemarkSheetMirror", "CC")
                .associate { it.textEn.trim() to it.category.trim() }
                .filterKeys { it.isNotEmpty() }
        } catch (_: Exception) { emptyMap() }

        // 2. Consolidated CC per (branch, consignment, DAY): one Supabase read
        // for the whole range, bucketed by the row's own Dhaka date (latest
        // CC remark of that day wins — a parcel remarked on two days syncs
        // each day separately).
        val consolidated = mutableMapOf<String, BulkVals>()
        for (branchId in branches) {
            val rows = try {
                SupabaseClientManager.fetchValidations(
                    "RemarkSheetMirror", "bulk_sync", listOf(
                        "branch_id" to "eq.$branchId",
                        "created_at" to "gte.$rangeStartIso",
                        "created_at" to "lt.$rangeEndIso",
                        "order" to "created_at.desc",
                    )
                )
            } catch (_: Exception) { emptyList() }
            val latestCcByKey = mutableMapOf<String, org.json.JSONObject>()
            val latestMsByKey = mutableMapOf<String, Long>()
            rows.forEach { row ->
                if (row.optString("source") != "CC") return@forEach
                val cid = row.optString("consignment").trim()
                if (cid.isEmpty()) return@forEach
                val ms = SupabaseRemarkValidationWriter.parseDbTimestampMillis(row.optString("created_at"))
                if (ms <= 0L) return@forEach
                val rowDay = java.time.Instant.ofEpochMilli(ms).atZone(opsZone).toLocalDate()
                if (rowDay.isBefore(start) || rowDay.isAfter(end)) return@forEach
                val key = "${branchId}__${rowDay}__$cid"
                if (ms >= (latestMsByKey[key] ?: -1L)) {
                    latestMsByKey[key] = ms
                    latestCcByKey[key] = row
                }
            }
            latestCcByKey.forEach { (key, row) ->
                val fb = catalog[row.optString("remarks").trim()].orEmpty()
                consolidated[key] = BulkVals(
                    feedback = fb,
                    validation = deriveValidation(fb),
                    validatorName = resolveAgentName(row.optString("author_system_id")),
                )
            }
        }
        if (consolidated.isEmpty())
            return@withContext "No CC remarks in $rangeLabel in Supabase — nothing to write"

        // 3. Per day → per branch → its bound sheets → its own sheet.
        var totConns = 0
        val tot = BulkCounts()
        var totNoCc = 0
        val errs = mutableListOf<String>()
        days.forEachIndexed { dayIdx, day ->
            for (branchId in branches) {
                val targets = try {
                    SheetLibraryRepository.resolveCcTargets(branchId, day)
                } catch (e: Exception) {
                    errs.add("$branchId: binding unreadable")
                    continue
                }
                if (targets.isEmpty()) continue
                for (target in targets) {
                    val conn = target.conn
                    totConns++
                    val label = conn.sheetName.ifBlank { conn.sheetId.ifBlank { branchId } }
                    onProgress(label)
                    try {
                        val c = bulkSyncOneConnection(token, branchId, conn, consolidated, day,
                            target.binding.effectiveFilters(), target.binding.filterLogic,
                            tabName = tabForDay(conn.tabPattern, day),
                            onRow = { done, total ->
                                onProgressDetail?.invoke(
                                    BulkProgress(day, dayIdx + 1, days.size, label, done, total))
                            })
                        tot.scanned += c.scanned; tot.filled += c.filled
                        tot.syncedRows += c.syncedRows; tot.syncedCells += c.syncedCells
                        totNoCc += c.noCc; tot.ignored += c.ignored
                    } catch (e: Exception) {
                        errs.add("${conn.sheetName.ifBlank { branchId }} ($day): ${e.message?.take(80) ?: "sync failed"}")
                    }
                }
            }
        }
        if (totConns == 0) return@withContext "No CC binding in any branch for $rangeLabel — bind a sheet from the CallCenter socket (check scope)"
        var msg = "✓ $rangeLabel: ${tot.syncedRows} rows synced (${tot.syncedCells} cells) · " +
            "${tot.filled} already filled · $totNoCc no CC yet · " +
            "${tot.ignored} filtered out · " +
            "${tot.scanned} sheet rows scanned ($totConns connections)"
        if (errs.isNotEmpty()) msg += " · ⚠ ${errs.size} error: ${errs.take(2).joinToString("; ")}" +
            if (errs.size > 2) "…" else ""
        msg
    }

    /** Resolves a tab pattern for a specific ops day (noon Dhaka pins the
     *  Y/M/D fields regardless of the phone's zone). */
    private fun tabForDay(tabPattern: String, day: LocalDate): String {
        val atNoon = java.util.Date.from(day.atTime(12, 0).atZone(opsZone).toInstant())
        return ScannerSheetRepository.resolveTabName(tabPattern, atNoon)
    }

    /** One connection → its own sheet: blank write cells × consolidated CC.
     *  [today] is the ops day being synced (date lookups + consolidated keys
     *  are scoped to it); [tabName] overrides the tab when the caller already
     *  resolved it for that day. [onRow] reports row progress. */
    private suspend fun bulkSyncOneConnection(
        accessToken: String,
        branchId: String,
        conn: ScannerSheetConn,
        consolidated: Map<String, BulkVals>,
        today: LocalDate,
        filters: List<CcFetchFilter> = emptyList(),
        filterLogic: String = CcFilterLogic.AND,
        tabName: String? = null,
        onRow: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BulkCounts = withContext(Dispatchers.IO) {
        val res = BulkCounts()
        val lookups = conn.effectiveLookups()
        val writes = conn.effectiveWrites().filter {
            it.kind == SheetWriteKind.FEEDBACK ||
                it.kind == SheetWriteKind.VALIDATION ||
                it.kind == SheetWriteKind.VALIDATOR_NAME
        }
        if (lookups.isEmpty() || writes.isEmpty())
            throw IllegalStateException("no lookup/write rule")
        val cidRule = lookups.firstOrNull { it.kind == SheetLookupKind.CONSIGNMENT }
            ?: throw IllegalStateException("no consignment lookup")
        val tab = tabName ?: ScannerSheetRepository.resolveTabName(conn.tabPattern)
        val headerRow = conn.resolvedHeaderRow()
        val headerCache = mutableMapOf<String, List<String>>()
        val cidLetter = resolveLetter(accessToken, conn.sheetId, tab,
            cidRule.colRef, cidRule.mode, headerRow, headerCache)
            ?: throw IllegalStateException("consignment column '${cidRule.colRef.trim()}' not found")
        // Date lookups verify the row really belongs to the synced day
        // (tab-scoped safety).
        // Other lookup kinds (feedback/validation/...) are the values being
        // filled, so matching on them would never hit a blank row — skipped.
        val dateRules = lookups.filter {
            it.kind == SheetLookupKind.TODAY || it.kind == SheetLookupKind.CREATED_AT
        }
        val dateLetters = mutableMapOf<SheetLookupRule, String>()
        dateRules.forEach { rule ->
            dateLetters[rule] = resolveLetter(accessToken, conn.sheetId, tab,
                rule.colRef, rule.mode, headerRow, headerCache)
                ?: throw IllegalStateException("lookup column '${rule.colRef.trim()}' not found")
        }
        val writeLetters = writes.map { rule ->
            rule to (resolveLetter(accessToken, conn.sheetId, tab,
                rule.colRef, rule.mode, headerRow, headerCache)
                ?: throw IllegalStateException("write column '${rule.colRef.trim()}' not found"))
        }
        suspend fun colValues(letter: String): List<String> =
            ConfigSheetDriveApi.fetchColumnValues(accessToken, conn.sheetId, tab, letter, httpClient)
        val cidCol = colValues(cidLetter)
        // Targeting filters (same as Live fetch): unresolvable refs never block.
        val ignoreCols = filters.mapNotNull { rule ->
            val letter = resolveLetter(accessToken, conn.sheetId, tab,
                rule.colRef, rule.mode, headerRow, headerCache) ?: return@mapNotNull null
            rule to letter
        }
        val ignoreValues = mutableMapOf<String, List<String>>()
        ignoreCols.map { it.second }.distinct().forEach { letter ->
            ignoreValues[letter] = colValues(letter)
        }
        fun rowFilteredOut(i: Int): Boolean {
            if (ignoreCols.isEmpty()) return false
            val hits = ignoreCols.map { (rule, letter) ->
                SheetCellCompare.pass(
                    rule.op, ignoreValues[letter].orEmpty().getOrNull(i).orEmpty(), rule.value,
                    rule.valueType)
            }
            val pass = if (filterLogic == CcFilterLogic.OR) hits.any { it } else hits.all { it }
            return !pass
        }
        val dateCols = mutableMapOf<String, List<String>>()
        dateLetters.values.distinct().forEach { letter -> dateCols[letter] = colValues(letter) }
        val writeCols = mutableMapOf<String, MutableList<String>>()
        writeLetters.map { it.second }.distinct().forEach { letter ->
            writeCols[letter] = colValues(letter).toMutableList()
        }
        res.scanned = cidCol.size
        for (i in cidCol.indices) {
            val cid = cidCol[i].trim()
            if (cid.isEmpty()) {
                if ((i + 1) % 20 == 0) onRow(i + 1, cidCol.size)
                continue
            }
            if (rowFilteredOut(i)) { res.ignored++; continue }
            var dateOk = true
            dateLetters.forEach { (_, letter) ->
                if (!isDateOn((dateCols[letter].orEmpty().getOrNull(i).orEmpty()).trim(), today)) dateOk = false
            }
            if (!dateOk) continue
            val blanks = writeLetters.filter { (_, letter) ->
                (writeCols[letter].orEmpty().getOrNull(i).orEmpty()).trim().isEmpty()
            }
            if (blanks.isEmpty()) { res.filled++; continue }
            val vals = consolidated["${branchId}__${today}__$cid"] ?: run { res.noCc++; return@run null }
                ?: continue
            for ((rule, letter) in blanks) {
                val v = when (rule.kind) {
                    SheetWriteKind.FEEDBACK -> vals.feedback
                    SheetWriteKind.VALIDATION -> vals.validation
                    else -> vals.validatorName
                }
                ConfigSheetDriveApi.writeCellValue(
                    accessToken, conn.sheetId, tab, letter, i + 1, v, httpClient
                )
                val col = writeCols[letter]!!
                while (col.size <= i) col.add("")
                col[i] = v
                res.syncedCells++
            }
            res.syncedRows++
            if (res.syncedRows % 10 == 0) delay(300) // Sheets quota safety
            if ((i + 1) % 10 == 0) onRow(i + 1, cidCol.size)
        }
        onRow(cidCol.size, cidCol.size)
        res
    }

    /** Dry-run for the Connectors Test button: same match as the live mirror
     *  but writes NOTHING. Returns a human-readable report. */
    suspend fun dryRunReport(
        appContext: Context, conn: ScannerSheetConn, consignmentId: String,
        feedback: String = "TEST", validatorName: String = "TEST",
    ): String =
        withContext(Dispatchers.IO) {
            if (consignmentId.isBlank()) return@withContext "Enter a consignment ID"
            val token = silentWriteToken(appContext.applicationContext)
                ?: return@withContext "Google account not connected — connect an account from Connectors"
            val fb = feedback.trim()
            val ctx = MirrorCtx(consignmentId.trim(), fb, deriveValidation(fb), validatorName.trim(), LocalDate.now(opsZone))
            try {
                // Extras first (see mirrorOne): lookups may reference row data.
                val ctx2 = ctx.copy(extras = fetchRowExtras(ctx.consignmentId))
                when (val found = findTargetRow(conn, token, ctx2)) {
                    is FindResult.Hit -> {
                        val w = found.writes.joinToString(", ") { (l, k) ->
                            val v = writeValue(k, ctx2)
                            "$l$k='${v.ifBlank { "(blank)" }}'"
                        }
                        "✓ Row ${found.row} (tab '${found.tab}') matched\n${found.detail}\nWriting: $w\n${found.scanned} rows scanned. (Nothing written)"
                    }
                    is FindResult.Miss -> "✕ ${found.reason}"
                }
            } catch (e: Exception) {
                "✕ Could not read sheet: ${e.message?.take(100) ?: "error"}"
            }
        }

    /** Read token for Live CC mode (Call Center reads today's consignment IDs
     *  from the branch's live sheet). The write scope includes read. */
    suspend fun readToken(appContext: Context): String? = silentWriteToken(appContext)

    /** Today's consignment IDs from one branch's live sheet ([config/liveCc]).
     *  [note] explains skips (no live sheet set, conn gone, no consignment
     *  lookup, empty) — callers surface it, never crash. */
    /** One sheet-row ID plus its row-date candidates (yyyyMMdd, primary
     *  first). Empty [dateKeys] = row date unknown (no date lookup mapped
     *  or unparseable) — date-wise run matching is impossible for it, so a
     *  remark save for it must block instead of guessing a branch. */
    data class LiveId(
        val cid: String,
        val dateKeys: List<String> = emptyList(),
    )

    data class LiveBranchIds(
        val branchId: String,
        val ids: List<LiveId>,
        val note: String?,
    )

    suspend fun fetchLiveConsignments(
        accessToken: String,
        branchIds: List<String>,
    ): List<LiveBranchIds> = withContext(Dispatchers.IO) {
        val today = LocalDate.now(opsZone)
        branchIds.map { it.trim() }.filter { it.isNotBlank() }.distinct().map { branchId ->
            try {
                // All-in-one: every ENABLED CC binding's sheet contributes IDs,
                // each with its own fetch criteria (socket 🔌 Step 3).
                // Which sheet Live reads is defined ONLY by bindings now —
                // the old LIVE CC SHEET dropdown is gone.
                val bindings = SheetLibraryRepository.loadCcBindings(branchId)
                    .filter { it.enabled }
                if (bindings.isEmpty())
                    return@map LiveBranchIds(branchId, emptyList(),
                        "No CC binding — bind a sheet from the CallCenter socket")
                val libraries = SheetLibraryRepository.loadLibraries(branchId)
                    .filter { it.enabled }.associateBy { it.libraryId }
                val targets = bindings.mapNotNull { b ->
                    val lib = libraries[b.libraryId] ?: return@mapNotNull null
                    if (!SheetScope.covers(lib.scopeType, lib.scopeMonth,
                            lib.scopeFrom, lib.scopeTo, today)
                    ) return@mapNotNull null
                    b to lib
                }
                if (targets.isEmpty())
                    return@map LiveBranchIds(branchId, emptyList(),
                        "No bound sheet in today's scope")
                val ids = mutableListOf<LiveId>()
                val seenIds = mutableSetOf<String>()
                var scanned = 0
                var filtered = 0
                val notes = mutableListOf<String>()
                val headerCache = mutableMapOf<String, List<String>>()
                for ((binding, lib) in targets) {
                    val (got, seen, dropped, note) = fetchLiveIdsForBinding(
                        accessToken, binding, lib, headerCache)
                    scanned += seen
                    filtered += dropped
                    note?.let { notes.add("${lib.nickname.ifBlank { lib.sheetName }}: $it") }
                    got.forEach { e -> if (seenIds.add(e.cid)) ids.add(e) }
                }
                val why = when {
                    ids.isNotEmpty() && notes.isNotEmpty() -> notes.joinToString("; ")
                    ids.isNotEmpty() -> null
                    else -> notes.firstOrNull()
                        ?: "No consignments today ($scanned rows scanned)"
                }
                LiveBranchIds(branchId, ids, why)
            } catch (e: Exception) {
                LiveBranchIds(branchId, emptyList(),
                    e.message?.take(80) ?: "could not read sheet")
            }
        }
    }

    /** One binding's Live IDs: fetch column theke ID, filter rules (AND/OR)
     *  pass kora row sudhu. Defaults (socket-e kichu set na korle): prothom
     *  lookup column theke ID + prothom write column blank filter. */
    private suspend fun fetchLiveIdsForBinding(
        accessToken: String,
        binding: CcBinding,
        lib: SheetLibrary,
        headerCache: MutableMap<String, List<String>>,
    ): LiveFetch {
        val tabName = ScannerSheetRepository.resolveTabName(lib.tabPattern)
        val headerRow = lib.resolvedHeaderRow()
        suspend fun letterOf(ref: String, mode: String): String? {
            val t = ref.trim()
            if (t.isEmpty()) return null
            return resolveLetter(accessToken, lib.sheetId, tabName, t, mode, headerRow, headerCache)
        }
        // Fetch column: socket choice, else range-er prothom column.
        val rangeStart = lib.columnLetters().firstOrNull().orEmpty()
        val wantFetchRef = binding.fetchColRef.trim().ifBlank { rangeStart }
        val wantFetchMode = if (binding.fetchColRef.trim().isNotBlank()) binding.fetchColMode
            else SheetColMode.INDEX
        if (wantFetchRef.isBlank())
            return LiveFetch(emptyList(), 0, 0, "No lookup column")
        // Filters: socket rules (na thakle filter nei — sob row).
        val socketRules = binding.effectiveFilters()
        val rules: List<CcFetchFilter> = socketRules
        // Resolve + fetch every needed column once (header rows cached).
        val colValues = mutableMapOf<String, List<String>>()
        suspend fun colOf(ref: String, mode: String): List<String>? {
            val letter = letterOf(ref, mode) ?: return null
            return colValues.getOrPut(letter) {
                ConfigSheetDriveApi.fetchColumnValues(
                    accessToken, lib.sheetId, tabName, letter, httpClient)
            }
        }
        data class RuleCol(val filter: CcFetchFilter, val values: List<String>?)
        val missing = mutableListOf<String>()
        val ruleCols = rules.map { r ->
            val values = colOf(r.colRef, r.mode)
            if (values == null) missing.add(r.colRef.trim())
            RuleCol(r, values)
        }
        val idCol = colOf(wantFetchRef, wantFetchMode)
            ?: return LiveFetch(emptyList(), 0, 0, "ID column '$wantFetchRef' paini")
        val useOr = socketRules.isNotEmpty() && binding.filterLogic == CcFilterLogic.OR
        // Row-date columns (lookup date column — TODAY / CREATED_AT kinds):
        // per-row date for date-wise run matching. Unresolvable refs are
        // skipped (that row's date just stays unknown, never blocks fetch).
        // Fetched through the same once-per-letter cache above, so this adds
        // no extra sheet reads when the column was already fetched.
        val dateKeyFmt = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
        val dateCols = binding.effectiveLookups()
            .filter { it.field == CcField.TODAY || it.field == CcField.CREATED_AT }
            .mapNotNull { rule -> colOf(rule.colRef, rule.mode) }
        fun rowDateKeys(i: Int): List<String> {
            val keys = mutableListOf<String>()
            for (col in dateCols) {
                val cell = col.getOrNull(i).orEmpty()
                if (cell.isBlank()) continue
                val primary = tryParseDate(cell) ?: continue
                keys.add(primary.format(dateKeyFmt))
                // Ambiguous slash cells (09/10 → Sep 10 AND Oct 9): keep both
                // readings as fallback candidates, primary first.
                slashCandidates(cell).map { it.format(dateKeyFmt) }
                    .filter { it !in keys }.forEach { keys.add(it) }
                break // first date column that yields a date wins
            }
            return keys
        }
        val ids = mutableListOf<LiveId>()
        val seen = mutableSetOf<String>()
        var dropped = 0
        idCol.forEachIndexed { i, cell ->
            val cid = cell.trim()
            if (cid.isEmpty()) return@forEachIndexed
            val results = ruleCols.map { (r, values) ->
                if (values == null) true // unresolvable rule never blocks
                else SheetCellCompare.pass(r.op, values.getOrNull(i).orEmpty(), r.value, r.valueType)
            }
            val pass = if (useOr && results.isNotEmpty()) results.any { it } else results.all { it }
            if (!pass) {
                dropped++
                return@forEachIndexed
            }
            if (seen.add(cid)) ids.add(LiveId(cid, rowDateKeys(i)))
        }
        val note = when {
            missing.isNotEmpty() -> "column ${missing.distinct().joinToString(",")} paini (skip)"
            else -> null
        }
        return LiveFetch(ids, idCol.size, dropped, note)
    }

    private data class LiveFetch(
        val ids: List<LiveId>,
        val scanned: Int,
        val dropped: Int,
        val note: String?,
    )

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
