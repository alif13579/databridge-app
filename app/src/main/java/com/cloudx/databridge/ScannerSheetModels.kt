package com.cloudx.databridge

/**
 * Config for a sheet connector: which sheet/tab, which rules find the row,
 * which values land in which columns.
 *
 * Two jobs, chosen at connect time ([purpose]):
 * - Scanner: a scan finds the row where the employee lookup matches and
 *   writes the scanned value (appending a row when no blank slot exists).
 * - Call Center remark mirror: a saved CC remark finds the row where ALL
 *   lookups match exactly, then writes every write rule (never appended).
 *
 * Stored at `config/connectors/{branchId}/current/{connectionId}`.
 */
data class ScannerSheetConn(
    val connectionId: String = "",   // Firebase push key
    val nickname:      String = "",  // user-defined label, shown in the branch's connection list
    val branchId:      String = "",
    val sheetId:        String = "",
    val sheetName:      String = "",
    /**
     * Tab-name pattern — three ways to use it:
     * - Fixed text: "Routing" (no token → used literally, same tab every day).
     * - Dynamic: tokens resolve against the write date — {dd} zero-padded day
     *   ("09"), {d} plain day ("9"), {mm} zero-padded month ("09"), {m} plain
     *   month ("9"), {yyyy} year ("2026"), {yy} short year ("26").
     * - Mixed: literal + tokens, e.g. "Routing {dd}" → "Routing 09".
     * Default "Day {dd}" (e.g. "Day 09"). Single source of truth is
     * ScannerSheetRepository.resolveTabName — every reader (mirror, scanner
     * write, dry-run, wizard preview) must go through it.
     */
    val tabPattern:     String = "Day {dd}",
    val googleEmail:    String = "",
    val connectedBy:    String = "",
    val connectedByName: String = "",
    val connectedAt:    Long = 0L,
    /** Lookup rules — ALL must match exactly on one row. */
    val lookups: List<SheetLookupRule> = emptyList(),
    /** Write rules — every rule writes into the matched row. */
    val writes: List<SheetWriteRule> = emptyList(),
    /** Header row (1-based) that TEXT-mode refs match against. One per
     *  connection — all text refs on every rule resolve from this row. */
    val headerRow: Int = 1,
    /** Disabled connections are skipped by mirror/sync/test (kept for record). */
    val enabled: Boolean = true,
    /** Which fragment this sheet serves ([SheetPurpose]). Required. */
    val purpose: String = "",
    /** Date scope — which dates this sheet covers ([SheetScope]). Missing =
     *  global (old connections predate scopes). */
    val scopeType: String = SheetScope.GLOBAL,
    /** Month scope "yyyy-MM" (e.g. "2026-09"). Only for [SheetScope.MONTH]. */
    val scopeMonth: String = "",
    /** Range scope "yyyy-MM-dd" bounds. Only for [SheetScope.RANGE]. */
    val scopeFrom: String = "",
    val scopeTo: String = "",
) {
    fun effectiveLookups(): List<SheetLookupRule> =
        lookups.filter { it.colRef.isNotBlank() }

    fun effectiveWrites(): List<SheetWriteRule> =
        writes.filter { it.colRef.isNotBlank() }

    /** True when the mirror should process this connection. */
    fun isRemarkConnection(): Boolean {
        if (purpose == SheetPurpose.SCANNER || purpose == SheetPurpose.ROUTING) return false
        if (purpose == SheetPurpose.REMARK) {
            return effectiveLookups().isNotEmpty() && effectiveWrites().isNotEmpty()
        }
        val kinds = effectiveWrites().map { it.kind }
        return effectiveLookups().any { it.kind in SheetLookupKind.REMARK_KINDS } &&
            kinds.any { it in SheetWriteKind.REMARK_KINDS }
    }

    /** True when the scanner should use this connection (employee→value). */
    fun isScannerConnection(): Boolean {
        if (purpose == SheetPurpose.REMARK || purpose == SheetPurpose.ROUTING) return false
        if (purpose == SheetPurpose.SCANNER) {
            return effectiveScannerLookup() != null && effectiveScannerWrite() != null
        }
        return effectiveLookups().any { it.kind == SheetLookupKind.EMPLOYEE } &&
            effectiveWrites().any { it.kind == SheetWriteKind.VALUE }
    }

    /** True when the routing-approval flow should use this connection. */
    fun isRoutingConnection(): Boolean {
        if (purpose == SheetPurpose.ROUTING) {
            return effectiveLookups().isNotEmpty() && effectiveWrites().isNotEmpty()
        }
        return false
    }

    /** Human label for lists: explicit purpose, else inferred. */
    fun purposeLabel(): String = when {
        purpose == SheetPurpose.SCANNER -> "Scanner"
        purpose == SheetPurpose.REMARK -> "Call Center"
        purpose == SheetPurpose.ROUTING -> "Routing Approval"
        isScannerConnection() && !isRemarkConnection() -> "Scanner"
        else -> "Call Center"
    }

    /** Scanner-effective lookup: the employee rule. */
    fun effectiveScannerLookup(): SheetLookupRule? =
        effectiveLookups().firstOrNull { it.kind == SheetLookupKind.EMPLOYEE }

    /** Scanner-effective write: the scanned-value rule. */
    fun effectiveScannerWrite(): SheetWriteRule? =
        effectiveWrites().firstOrNull { it.kind == SheetWriteKind.VALUE }

    fun resolvedHeaderRow(): Int = if (headerRow in 1..20) headerRow else 1
}

/** Column reference mode: TEXT = exact header text in [ScannerSheetConn.headerRow],
 *  INDEX = letter ("C") or 1-based number ("3"). */
object SheetColMode {
    const val TEXT = "text"
    const val INDEX = "index"
}

/** Connection purpose — which fragment this sheet serves, chosen at connect
 *  time (step 1, Branch + Fragment dropdowns) so every sheet's job is
 *  explicit: scanner sheets take scans, remark sheets take mirrors, routing
 *  sheets take routing-approval decisions.
 *  Branch + fragment-wise MULTIPLE sheets allowed (each save = new
 *  connection). Future fragments: just add a const + label here — stored
 *  values stay stable (Firebase compat), the wizard dropdown picks them up. */
object SheetPurpose {
    const val SCANNER = "scanner"
    const val REMARK = "remark"
    const val ROUTING = "routing"
    /** All known fragments, in wizard order. */
    val ALL = listOf(SCANNER, REMARK, ROUTING)
    fun label(purpose: String): String = when (purpose) {
        SCANNER -> "📷 Scanner (Scan → sheet)"
        REMARK -> "☎️ Call Center (Remark → sheet)"
        ROUTING -> "🛣️ Routing Approval (Routing → sheet)"
        else -> purpose.ifBlank { "— Fragment বেছে নিন —" }
    }
    fun isKnown(purpose: String): Boolean = purpose in ALL
}

/** Date scope — which dates a connection covers, chosen at connect time
 *  (step 1, Scope dropdown). Reading/writing for a branch + date resolves to
 *  the MOST SPECIFIC covering scope (range > month > global) so the
 *  appropriate sheet is found without ambiguity. */
object SheetScope {
    const val RANGE = "range"
    const val MONTH = "month"
    const val GLOBAL = "global"
    val ALL = listOf(RANGE, MONTH, GLOBAL)
    fun label(type: String): String = when (type) {
        RANGE -> "🗓 Date range"
        MONTH -> "📅 Month + Year"
        GLOBAL -> "🌍 Global (sob date)"
        else -> "— Scope বেছে নিন —"
    }
    fun isKnown(type: String): Boolean = type in ALL

    private val MONTH_FMT = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")
    private val DAY_FMT = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE

    /** Normalizes wizard inputs: month "2026-9" → "2026-09", day trims. */
    fun normMonth(raw: String): String? {
        try {
            return java.time.YearMonth.parse(raw.trim(), MONTH_FMT).toString()
        } catch (_: Exception) { /* fall through to lenient parse */ }
        return try {
            val p = raw.trim().split("-")
            if (p.size != 2) null
            else java.time.YearMonth.of(p[0].toInt(), p[1].toInt()).toString()
        } catch (_: Exception) { null }
    }

    fun normDay(raw: String): String? = try {
        java.time.LocalDate.parse(raw.trim(), DAY_FMT).toString()
    } catch (_: Exception) { null }

    /** True when this scope covers [date]. Invalid params → false. */
    fun covers(
        scopeType: String, scopeMonth: String, scopeFrom: String, scopeTo: String,
        date: java.time.LocalDate,
    ): Boolean = when (scopeType) {
        MONTH -> try {
            java.time.YearMonth.parse(scopeMonth.trim(), MONTH_FMT) ==
                java.time.YearMonth.from(date)
        } catch (_: Exception) { false }
        RANGE -> try {
            val from = java.time.LocalDate.parse(scopeFrom.trim(), DAY_FMT)
            val to = java.time.LocalDate.parse(scopeTo.trim(), DAY_FMT)
            !date.isBefore(from) && !date.isAfter(to)
        } catch (_: Exception) { false }
        else -> true // GLOBAL + legacy blank
    }

    /** Specificity rank: lower wins (range 0, month 1, global 2). */
    fun rank(scopeType: String): Int = when (scopeType) {
        RANGE -> 0
        MONTH -> 1
        else -> 2
    }

    private val SHORT_MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    /** Short badge: "🌍 Global" / "📅 Sep 2026" / "📅 01 Sep → 15 Sep 2026". */
    fun badge(scopeType: String, scopeMonth: String, scopeFrom: String, scopeTo: String): String {
        if (scopeType == MONTH) {
            val ym = try {
                java.time.YearMonth.parse(scopeMonth.trim(), MONTH_FMT)
            } catch (_: Exception) { return "📅 ${scopeMonth.trim()}" }
            return "📅 ${SHORT_MONTHS[ym.monthValue - 1]} ${ym.year}"
        }
        if (scopeType == RANGE) {
            fun fmt(raw: String): String = try {
                val d = java.time.LocalDate.parse(raw.trim(), DAY_FMT)
                "%02d %s".format(d.dayOfMonth, SHORT_MONTHS[d.monthValue - 1])
            } catch (_: Exception) { raw.trim() }
            val year = try {
                java.time.LocalDate.parse(scopeTo.trim(), DAY_FMT).year.toString()
            } catch (_: Exception) { "" }
            return "📅 ${fmt(scopeFrom)} → ${fmt(scopeTo)}${if (year.isNotBlank()) " $year" else ""}"
        }
        return "🌍 Global"
    }
}

/** Most-specific covering connections for [date] (range > month > global).
 *  Empty when nothing covers the date — readers/writers then skip. */
fun List<ScannerSheetConn>.selectForDate(date: java.time.LocalDate): List<ScannerSheetConn> {
    val covering = filter {
        SheetScope.covers(it.scopeType, it.scopeMonth, it.scopeFrom, it.scopeTo, date)
    }
    if (covering.isEmpty()) return emptyList()
    val best = covering.minOf { SheetScope.rank(it.scopeType) }
    return covering.filter { SheetScope.rank(it.scopeType) == best }
}

/** Lookup value sources: the remark event's data. A lookup compares the sheet
 *  cell against the event's value for that source (today/created_at = date
 *  compare, rest = exact trim match). */
object SheetLookupKind {
    const val CONSIGNMENT = "consignment"
    const val TODAY = "today"
    const val FEEDBACK = "feedback"
    const val VALIDATION = "validation"
    const val VALIDATOR_NAME = "validator_name"
    const val CREATED_AT = "created_at"
    const val AUTHOR_NAME = "author_name"
    const val EMPLOYEE = "employee" // scanner: the scanned employee ID
    val ALL = listOf(
        CONSIGNMENT, TODAY, FEEDBACK, VALIDATION, VALIDATOR_NAME,
        CREATED_AT, AUTHOR_NAME, EMPLOYEE,
    )
    val REMARK_KINDS = ALL - EMPLOYEE
}

/** Write value sources: exactly three for Call Center (feedback, validation,
 *  validator_name) plus the scanner's value. */
object SheetWriteKind {
    const val FEEDBACK = "feedback" // validation_remarks.category of the saved option
    const val VALIDATION = "validation" // derived: Invalid iff feedback is Willing to receive today (blank stays blank), else Valid
    const val VALIDATOR_NAME = "validator_name" // CC agent who saved the remark
    const val VALUE = "value" // scanner: the scanned value
    val ALL = listOf(FEEDBACK, VALIDATION, VALIDATOR_NAME, VALUE)
    /** Sources the mirror processes (everything except the scanner's value —
     *  value rules are skipped, never blank-written). */
    val REMARK_KINDS = ALL - VALUE
}

/** Derives Validation from Feedback: only "Willing to receive today" is
 *  Invalid; blank stays blank; everything else is Valid. */
fun deriveValidation(feedback: String): String {
    val f = feedback.trim()
    if (f.isEmpty()) return ""
    return if (f.equals("Willing to receive today", ignoreCase = true)) "Invalid" else "Valid"
}

/** One lookup criterion: column [colRef] (per [mode]) must match [kind] on the
 *  same row. All lookups on a connection AND together. */
data class SheetLookupRule(
    val colRef: String = "",
    val kind: String = SheetLookupKind.CONSIGNMENT,
    val mode: String = SheetColMode.INDEX,
)

/** One write target: [kind] value goes into column [colRef] on the matched row. */
data class SheetWriteRule(
    val colRef: String = "",
    val kind: String = SheetWriteKind.FEEDBACK,
    val mode: String = SheetColMode.INDEX,
)
