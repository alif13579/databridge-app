package com.cloudx.databridge

/**
 * Neutral sheet library + per-fragment bindings.
 *
 * A [SheetLibrary] is just a sheet: which sheet/tab, which columns can be
 * used for lookup, which columns can be written. It is NOT tied to any
 * fragment at creation time — the same library can be reused from every
 * fragment, for read or for write.
 *
 * WHAT data goes through those columns is defined where the sheet is USED,
 * via a binding (scanner-first: [ScannerBinding], edited from the Scanner
 * fragment's socket icon). Admin configures bindings, users just scan.
 *
 * Legacy [ScannerSheetConn] rows (with purpose/kinds) keep working
 * untouched — see [ScannerSheetConn.isLibrary].
 */

/** One usable column: ref (letter/number/header text) + mode. No kind. */
data class SheetColRef(
    val colRef: String = "",
    val mode: String = SheetColMode.INDEX,
)

/** Neutral sheet entry in the library. Mirrors the [ScannerSheetConn]
 *  transport shape minus purpose/kinds so both can share UI + repo helpers.
 *
 *  Column scope is a RANGE (sheets-tab style), not per-column rules:
 *  [colStart]..[colEnd] (1-based, letters or numbers in the wizard),
 *  headers in [headerRow], data from [dataStartRow]. Bindings map their own
 *  columns (letters from this range, or exact header text) per fragment.
 *
 *  Stored at `config/connectors/{branchId}/current/{libraryId}` with
 *  `isLibrary = true` (same node as legacy connectors — one list, two
 *  badges). History mirrors connectors: `config/connectors/{branchId}/history`.
 */
data class SheetLibrary(
    val libraryId: String = "",
    val nickname: String = "",
    val branchId: String = "",
    val sheetId: String = "",
    val sheetName: String = "",
    val tabPattern: String = "Day {dd}",
    val googleEmail: String = "",
    val connectedBy: String = "",
    val connectedByName: String = "",
    val connectedAt: Long = 0L,
    /** Legacy per-column lists (pre-range wizard). Ignored for new saves;
     *  kept only so old rows still derive a range. */
    val lookupCols: List<SheetColRef> = emptyList(),
    /** Legacy per-column lists (pre-range wizard). See [lookupCols]. */
    val writeCols: List<SheetColRef> = emptyList(),
    val headerRow: Int = 1,
    /** Range start (1-based). 0 = unset → derived from legacy cols, else 1. */
    val colStart: Int = 0,
    /** Range end (1-based, inclusive). 0 = unset → derived, else 10. */
    val colEnd: Int = 0,
    /** First data row (1-based). 0 = unset → headerRow + 1. */
    val dataStartRow: Int = 0,
    /** Header labels captured at save time (letter → header text, non-blank
     *  only). Socket dropdowns show "B — Consignment" from this; best-effort
     *  (tab may not exist yet) — letters always work as fallback. */
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val scopeType: String = SheetScope.GLOBAL,
    val scopeMonth: String = "",
    val scopeFrom: String = "",
    val scopeTo: String = "",
) {
    fun effectiveLookupCols(): List<SheetColRef> =
        lookupCols.filter { it.colRef.isNotBlank() }

    fun effectiveWriteCols(): List<SheetColRef> =
        writeCols.filter { it.colRef.isNotBlank() }

    fun resolvedHeaderRow(): Int = if (headerRow in 1..20) headerRow else 1

    private fun legacyColIndices(): List<Int> =
        (lookupCols + writeCols).mapNotNull { ref ->
            val t = ref.colRef.trim()
            if (t.isEmpty()) return@mapNotNull null
            if (ref.mode == SheetColMode.TEXT) return@mapNotNull null
            if (Regex("^[A-Za-z]{1,3}$").matches(t)) {
                var n = 0
                t.uppercase().forEach { ch -> n = n * 26 + (ch - 'A' + 1) }
                n
            } else ConfigSheetParseUtil.parseColInput(t)
        }

    fun effectiveColStart(): Int {
        if (colStart >= 1) return colStart
        return legacyColIndices().minOrNull() ?: 1
    }

    fun effectiveColEnd(): Int {
        val s = effectiveColStart()
        if (colEnd >= s) return colEnd
        return legacyColIndices().maxOrNull()?.coerceAtLeast(s) ?: 10
    }

    fun effectiveDataStartRow(): Int {
        if (dataStartRow >= 1) return dataStartRow
        return (resolvedHeaderRow() + 1).coerceAtMost(50)
    }

    /** Column letters in range (A..K) — binding dropdown source. Capped. */
    fun columnLetters(): List<String> {
        val s = effectiveColStart().coerceIn(1, 200)
        val e = effectiveColEnd().coerceIn(s, (s + 51).coerceAtMost(200))
        return (s..e).map { ConfigSheetParseUtil.colIndexToLetter(it) }
    }

    /** Dropdown label: "B — Consignment" or plain "B" when unknown. */
    fun colLabel(letter: String): String {
        val l = letter.trim().uppercase()
        if (l.isEmpty()) return l
        val h = headers[l]?.trim().orEmpty()
        return if (h.isBlank()) l else "$l — $h"
    }
}

/** Scanner's bindable data fields (the registry other fragments copy).
 *  A binding maps library columns <-> these field keys. */
object ScannerField {
    const val EMPLOYEE_ID = "employee_id" // agent's employee ID (lookup + write)
    const val SCAN_VALUE = "scan_value"   // scanned code text (lookup + write)
    const val SCAN_AT = "scan_at"         // scan time, Dhaka "yyyy-MM-dd HH:mm" (write only)
    const val AGENT_NAME = "agent_name"   // agent display name (write only)

    fun label(field: String): String = when (field) {
        EMPLOYEE_ID -> "Employee ID"
        SCAN_VALUE -> "Scan text"
        SCAN_AT -> "Scan time"
        AGENT_NAME -> "Agent name"
        else -> field.ifBlank { "— select a field —" }
    }

    /** Fields allowed on the lookup side (exact-trim match). */
    val LOOKUP_FIELDS = listOf(EMPLOYEE_ID, SCAN_VALUE)

    /** Fields allowed on the write side. */
    val WRITE_FIELDS = listOf(SCAN_VALUE, SCAN_AT, EMPLOYEE_ID, AGENT_NAME)

    fun isKnown(field: String): Boolean = field in (LOOKUP_FIELDS + WRITE_FIELDS)
}

/** CC remark's bindable data fields. Keys intentionally equal the legacy
 *  [SheetLookupKind]/[SheetWriteKind] strings, so a binding converts 1:1
 *  into the mirror's existing rule shape — the executor doesn't care whether
 *  the mapping came from a legacy conn or a fragment binding. */
object CcField {
    const val CONSIGNMENT = SheetLookupKind.CONSIGNMENT
    const val TODAY = SheetLookupKind.TODAY
    const val FEEDBACK = SheetLookupKind.FEEDBACK
    const val VALIDATION = SheetLookupKind.VALIDATION
    const val VALIDATOR_NAME = SheetLookupKind.VALIDATOR_NAME
    const val CREATED_AT = SheetLookupKind.CREATED_AT
    const val AUTHOR_NAME = SheetLookupKind.AUTHOR_NAME

    fun label(field: String): String = when (field) {
        CONSIGNMENT -> "Consignment ID"
        TODAY -> "Today (date match)"
        FEEDBACK -> "Feedback"
        VALIDATION -> "Validation"
        VALIDATOR_NAME -> "Validator name"
        CREATED_AT -> "Created at (date)"
        AUTHOR_NAME -> "Author name"
        else -> field.ifBlank { "— select a field —" }
    }

    /** Lookup side: everything except the scanner-only field. */
    val LOOKUP_FIELDS = listOf(
        CONSIGNMENT, TODAY, FEEDBACK, VALIDATION, VALIDATOR_NAME,
        CREATED_AT, AUTHOR_NAME,
    )

    /** Write side: mirror writes feedback / validation / validator_name only. */
    val WRITE_FIELDS = listOf(FEEDBACK, VALIDATION, VALIDATOR_NAME)
}

/** One column <-> CC-field pair inside a binding. */
data class CcFieldMap(
    val colRef: String = "",
    val mode: String = SheetColMode.INDEX,
    val field: String = "",
)

/** Fetch-filter operators for the Live ID list. */
object CcFilterOp {
    const val BLANK = "blank"         // cell khali
    const val NOT_BLANK = "notblank"  // cell bhora
    const val EQUALS = "equals"       // cell == value
    const val NOT_EQUALS = "notequals" // cell != value
    const val GT = "gt"               // cell > value (number hole numeric)
    const val GTE = "gte"             // cell >= value
    const val LT = "lt"               // cell < value
    const val LTE = "lte"             // cell <= value
    val ALL = listOf(BLANK, NOT_BLANK, EQUALS, NOT_EQUALS, GT, GTE, LT, LTE)
    fun label(op: String): String = when (op) {
        BLANK -> "When empty"
        NOT_BLANK -> "When filled"
        EQUALS -> "When equal"
        NOT_EQUALS -> "When not equal"
        GT -> "When greater (>) "
        GTE -> "When greater or equal (≥)"
        LT -> "When smaller (<)"
        LTE -> "When smaller or equal (≤)"
        else -> op.ifBlank { "— condition —" }
    }
    fun needsValue(op: String): Boolean = when (op) {
        EQUALS, NOT_EQUALS, GT, GTE, LT, LTE -> true
        else -> false
    }
}

/** Filter value types: value field-e ki bosbe. */
object CcValueType {
    const val TEXT = "text"     // free text
    const val NUMBER = "number" // numeric input (>, < numeric compare)
    const val DATE = "date"     // calendar theke specific date
    const val TODAY = "today"   // ajker date (auto, Dhaka)
    val ALL = listOf(TEXT, NUMBER, DATE, TODAY)
    fun label(t: String): String = when (t) {
        NUMBER -> "Number"
        DATE -> "Date"
        TODAY -> "Today (auto)"
        else -> "লেখা"
    }
    fun isKnown(t: String): Boolean = t in ALL
}

/** How multiple fetch filters combine. */
object CcFilterLogic {
    const val AND = "AND" // SOB filter milte hobe
    const val OR = "OR"   // JE KONO ekta millei hobe
    val ALL = listOf(AND, OR)
    fun label(logic: String): String = when (logic) {
        OR -> "যেকোনো একটা (OR)"
        else -> "সবগুলো (AND)"
    }
}

/** One Live-list filter: column + operator (+ value). */
data class CcFetchFilter(
    val colRef: String = "",
    val mode: String = SheetColMode.INDEX,
    val op: String = CcFilterOp.BLANK,
    val value: String = "",
    /** [CcValueType]: text (default) / number / date / today. */
    val valueType: String = CcValueType.TEXT,
)

/** Cell comparison shared by every executor (fetch + write, all fragments).
 *  [valueType] decides how value/equals work: number/date compare by value,
 *  text compares exact strings. Unknown op never blocks. */
object SheetCellCompare {
    private val ZONE_DHAKA: java.time.ZoneId by lazy { java.time.ZoneId.of("Asia/Dhaka") }

    fun dhakaToday(): java.time.LocalDate =
        java.time.LocalDate.now(ZONE_DHAKA)

    /** "2026-09-10", "10/09/2026", "09/10/2026" (M/d/yyyy), "9/10/26" (M/d/yy),
     *  "10-09-2026", "10.09.2026", "10-Sep-2026"/"10-Sep-26", "2026/09/10",
     *  Supabase ISO ("...+00:00"/"...Z"/space "+00" variant),
     *  Dhaka stamp ("dd-MM-yyyy HH:mm") — else null.
     *  NOTE: slash cells like 09/10 are ambiguous — this returns the FIRST
     *  successful reading (d/M before M/d for 4-digit, kept for existing
     *  dd/MM sheets). Date-EQUALITY checks (mirror lookup) try both
     *  readings; only ordering (>/<) uses this single value. */
    fun parseDate(raw: String): java.time.LocalDate? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val fmts = listOf(
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE,
            java.time.format.DateTimeFormatter.ofPattern("d/M/yyyy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("M/d/yy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("d/M/yy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("d-M-yyyy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("d.M.yyyy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("d-MMM-yyyy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("d-MMM-yy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd", java.util.Locale.ENGLISH),
        )
        for (f in fmts) {
            try {
                var d = java.time.LocalDate.parse(t, f)
                if (d.year < 100) d = d.plusYears(2000)
                return d
            } catch (_: Exception) { }
        }
        // Dhaka stamp "dd-MM-yyyy HH:mm" → date part.
        try {
            var d = java.time.LocalDate.parse(t.substringBefore(" "),
                java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy", java.util.Locale.ENGLISH))
            if (d.year < 100) d = d.plusYears(2000)
            return d
        } catch (_: Exception) { }
        // Supabase ISO offset ("...+00:00", space "+00" variant) → Dhaka date.
        try { return java.time.OffsetDateTime.parse(normalizeIso(t)).atZoneSameInstant(ZONE_DHAKA).toLocalDate() }
        catch (_: Exception) { }
        try { return java.time.Instant.parse(t).atZone(ZONE_DHAKA).toLocalDate() }
        catch (_: Exception) { }
        return null
    }

    /** "2026-09-10 14:41:38.608242+00" → "2026-09-10T14:41:38.608242+00:00". */
    private fun normalizeIso(raw: String): String {
        var s = raw.trim()
        if (s.length > 10 && s[10] == ' ' && Regex("^\\d{4}-\\d{2}-\\d{2} ").containsMatchIn(s)) {
            s = s.substring(0, 10) + "T" + s.substring(11)
        }
        if (s.endsWith("+00") || s.endsWith("-00")) s += ":00"
        val compactTz = Regex("([+-])(\\d{2})(\\d{2})$")
        compactTz.find(s)?.let { m ->
            s = s.dropLast(5) + "${m.groupValues[1]}${m.groupValues[2]}:${m.groupValues[3]}"
        }
        return s
    }

    fun pass(op: String, cell: String, value: String, valueType: String = CcValueType.TEXT): Boolean {
        val c = cell.trim()
        val t = if (valueType == CcValueType.TODAY) dhakaToday().toString() else value.trim()
        return when (op) {
            CcFilterOp.BLANK -> c.isBlank()
            CcFilterOp.NOT_BLANK -> c.isNotBlank()
            CcFilterOp.EQUALS ->
                if (valueType == CcValueType.TEXT) c == t else compareOrdered(c, t) == 0
            CcFilterOp.NOT_EQUALS ->
                if (valueType == CcValueType.TEXT) c != t else compareOrdered(c, t) != 0
            CcFilterOp.GT -> compareOrdered(c, t) > 0
            CcFilterOp.GTE -> compareOrdered(c, t) >= 0
            CcFilterOp.LT -> compareOrdered(c, t) < 0
            CcFilterOp.LTE -> compareOrdered(c, t) <= 0
            else -> true
        }
    }

    fun compareOrdered(a: String, b: String): Int {
        val an = a.replace(",", "").trim().toDoubleOrNull()
        val bn = b.replace(",", "").trim().toDoubleOrNull()
        if (an != null && bn != null) return an.compareTo(bn)
        val ad = parseDate(a)
        val bd = parseDate(b)
        if (ad != null && bd != null) return ad.compareTo(bd)
        return a.trim().compareTo(b.trim(), ignoreCase = true)
    }

}

/** Call Center's use of one library: which columns match a remark, which
 *  columns receive feedback/validation/validator_name. ALL lookups must
 *  match one row (mirror never appends).
 *
 *  Stored at `config/sheetBindings/{branchId}/cc/{bindingId}`
 *  (one per library per branch — save upserts by libraryId).
 */
data class CcBinding(
    val bindingId: String = "",
    val libraryId: String = "",
    val branchId: String = "",
    val lookups: List<CcFieldMap> = emptyList(),
    val writes: List<CcFieldMap> = emptyList(),
    val enabled: Boolean = true,
    val updatedBy: String = "",
    val updatedByName: String = "",
    val updatedAt: Long = 0L,
    /** Fetch criteria (Live ID list) — blank = default (prothom lookup
     *  column theke ID, prothom write column blank filter). */
    val fetchColRef: String = "",
    val fetchColMode: String = SheetColMode.INDEX,
    /** AND / OR — multiple filters kivabe combine hobe. Filters drive
     *  BOTH fetch (Live list) and write targeting (mirror/bulk): row asbe
     *  + likhbe sudhu filter pass korle. */
    val filterLogic: String = CcFilterLogic.AND,
    val filters: List<CcFetchFilter> = emptyList(),
) {
    fun effectiveLookups(): List<CcFieldMap> =
        lookups.filter { it.colRef.isNotBlank() && it.field.isNotBlank() }

    fun effectiveWrites(): List<CcFieldMap> =
        writes.filter { it.colRef.isNotBlank() && it.field.isNotBlank() }

    fun effectiveFilters(): List<CcFetchFilter> =
        filters.filter { it.colRef.isNotBlank() && it.op.isNotBlank() }

    /** Human summary: "C=Consignment ID → K=Feedback". */
    fun summary(): String {
        val l = effectiveLookups().joinToString(" + ") { "${it.colRef.trim()}=${CcField.label(it.field)}" }
        val w = effectiveWrites().joinToString(", ") { "${it.colRef.trim()}←${CcField.label(it.field)}" }
        val base = "$l → $w"
        val n = effectiveFilters().size
        return if (n > 0) "$base • $n filter" else base
    }

    /** Human fetch summary: "IDs from B • K blank (AND)". */
    fun fetchSummary(): String {
        val f = effectiveFilters()
        if (fetchColRef.isBlank() && f.isEmpty()) return "default (range start, no filter)"
        val col = fetchColRef.trim().ifBlank { "range start" }
        if (f.isEmpty()) return "IDs from $col • no filter"
        val logic = if (f.size > 1) " [${CcFilterLogic.label(filterLogic)}]" else ""
        val rules = f.joinToString(if (filterLogic == CcFilterLogic.OR) " OR " else " + ") {
            val v = if (CcFilterOp.needsValue(it.op)) " “${it.value.trim()}”" else ""
            "${it.colRef.trim()} ${CcFilterOp.label(it.op)}$v"
        }
        return "IDs from $col • $rules$logic"
    }

    /** Executor shape: field keys ARE kind strings, so the mirror runs
     *  unchanged on the synthesized connection. */
    fun toConn(lib: SheetLibrary): ScannerSheetConn =
        ScannerSheetConn(
            connectionId = "binding:$bindingId",
            nickname = lib.nickname,
            branchId = branchId,
            sheetId = lib.sheetId,
            sheetName = lib.sheetName,
            tabPattern = lib.tabPattern,
            googleEmail = lib.googleEmail,
            lookups = effectiveLookups().map {
                SheetLookupRule(it.colRef, it.field, it.mode)
            },
            writes = effectiveWrites().map {
                SheetWriteRule(it.colRef, it.field, it.mode)
            },
            headerRow = lib.headerRow,
            enabled = enabled && lib.enabled,
            purpose = SheetPurpose.REMARK,
            scopeType = lib.scopeType,
            scopeMonth = lib.scopeMonth,
            scopeFrom = lib.scopeFrom,
            scopeTo = lib.scopeTo,
        )
}
/** One column <-> scanner-field pair inside a binding. */
data class ScannerFieldMap(
    val colRef: String = "",
    val mode: String = SheetColMode.INDEX,
    val field: String = "",
)

/** Scanner's use of one library: which columns match on which scan data,
 *  which columns receive which scan data. ALL lookups must match one row.
 *
 *  Stored at `config/sheetBindings/{branchId}/scanner/{bindingId}`
 *  (one binding per library per branch — save upserts by libraryId).
 */
data class ScannerBinding(
    val bindingId: String = "",
    val libraryId: String = "",
    val branchId: String = "",
    val lookups: List<ScannerFieldMap> = emptyList(),
    val writes: List<ScannerFieldMap> = emptyList(),
    val enabled: Boolean = true,
    val updatedBy: String = "",
    val updatedByName: String = "",
    val updatedAt: Long = 0L,
    /** AND / OR — multiple filters kivabe combine hobe. Filters drive
     *  write targeting: row-te likhbe sudhu filter pass korle. */
    val filterLogic: String = CcFilterLogic.AND,
    val filters: List<CcFetchFilter> = emptyList(),
) {
    fun effectiveLookups(): List<ScannerFieldMap> =
        lookups.filter { it.colRef.isNotBlank() && it.field.isNotBlank() }

    fun effectiveWrites(): List<ScannerFieldMap> =
        writes.filter { it.colRef.isNotBlank() && it.field.isNotBlank() }

    /** Human summary: "B=Employee ID → K=Scan text". */
    fun summary(): String {
        val l = effectiveLookups().joinToString(" + ") { "${it.colRef.trim()}=${ScannerField.label(it.field)}" }
        val w = effectiveWrites().joinToString(", ") { "${it.colRef.trim()}←${ScannerField.label(it.field)}" }
        val base = "$l → $w"
        val n = effectiveFilters().size
        return if (n > 0) "$base • $n filter" else base
    }

    fun effectiveFilters(): List<CcFetchFilter> =
        filters.filter { it.colRef.isNotBlank() && it.op.isNotBlank() }
}

/** Routing Approval's use of one library: which columns carry the
 *  consignment ID, the from-hub, the to-hub and the confirm/decision cell
 *  (extension parity: ID=D, From=C, To=E, Confirm=K — configurable here).
 *  Incoming vs outgoing is derived per row: To == own branch → incoming,
 *  else From == own branch → outgoing (destination wins, same as extension).
 *
 *  Stored at `config/sheetBindings/{branchId}/routing/{bindingId}`
 *  (one binding per library per branch — save upserts by libraryId).
 */
data class RoutingBinding(
    val bindingId: String = "",
    val libraryId: String = "",
    val branchId: String = "",
    val idCol: SheetColRef = SheetColRef("D"),
    val fromCol: SheetColRef = SheetColRef("C"),
    val toCol: SheetColRef = SheetColRef("E"),
    val confirmCol: SheetColRef = SheetColRef("K"),
    val enabled: Boolean = true,
    val updatedBy: String = "",
    val updatedByName: String = "",
    val updatedAt: Long = 0L,
) {
    /** Human summary: "D=ID, C=From, E=To, K=Confirm". */
    fun summary(): String {
        fun s(r: SheetColRef) = r.colRef.trim().ifBlank { "—" }
        return "${s(idCol)}=ID, ${s(fromCol)}=From, ${s(toCol)}=To, ${s(confirmCol)}=Confirm"
    }

    /** Executor shape: ID column rides as the consignment lookup so generic
     *  match-by-ID executors keep working; from/to/confirm stay binding-side
     *  (the routing fetch reads them directly). */
    fun toRoutingConn(lib: SheetLibrary): ScannerSheetConn =
        ScannerSheetConn(
            connectionId = "routing:$bindingId",
            nickname = lib.nickname,
            branchId = branchId,
            sheetId = lib.sheetId,
            sheetName = lib.sheetName,
            tabPattern = lib.tabPattern,
            googleEmail = lib.googleEmail,
            lookups = listOf(SheetLookupRule(idCol.colRef, SheetLookupKind.CONSIGNMENT, idCol.mode)),
            writes = emptyList(),
            headerRow = lib.headerRow,
            enabled = enabled && lib.enabled,
            purpose = SheetPurpose.ROUTING,
            scopeType = lib.scopeType,
            scopeMonth = lib.scopeMonth,
            scopeFrom = lib.scopeFrom,
            scopeTo = lib.scopeTo,
        )
}
