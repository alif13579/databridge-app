package com.cloudx.databridge

/**
 * Config for the Scanner Sheet Connector — a simpler sibling to [SheetConn] used specifically
 * by ScannerFragment to write scanned values directly into a Google Sheet, instead of the
 * sync/mapping-oriented flow ConfigSheetFragment provides.
 *
 * No periodic sync, no field mapping, no primary-key parts — just enough to answer
 * "which sheet, which tab pattern, which column has the agent's employee_id, which column
 * gets the scanned value".
 *
 * Stored at `config/connectors/{branchId}/{connectionId}`.
 */
data class ScannerSheetConn(
    val connectionId: String = "",   // Firebase push key
    val nickname:      String = "",  // user-defined label, shown in the branch's connection list
    val branchId:      String = "",
    val sheetId:        String = "",
    val sheetName:      String = "",
    /**
     * Tab-name pattern — currently always "Day {dd}" (day-of-month, zero-padded, no leading
     * zero stripped — e.g. "Day 16", "Day 03"), resolved at scan-time from the current date.
     * Stored as a pattern string (not just a flag) so a different pattern could be supported
     * later (e.g. "{yyyyMMdd}") without another schema migration.
     */
    val tabPattern:     String = "Day {dd}",
    /** Column letter to search for a matching employee_id (e.g. "T" for column 20). */
    val matchColumn:    String = "",
    /** Second match column: a DATE column (e.g. "A"). When non-blank this is a
     *  REMARK connection — RemarkSheetMirror finds the row where [matchColumn]
     *  == consignmentId AND this column == today, then writes the verdict into
     *  [writeColumn]. Blank = scanner connection (single employee_id match). */
    val dateMatchColumn: String = "",
    /** Column letter to write the scanned value into, on the same row as the match (e.g. "K"
     *  for column 11). */
    val writeColumn:    String = "",
    val googleEmail:    String = "",
    val connectedBy:    String = "",
    val connectedByName: String = "",
    val connectedAt:    Long = 0L,
    /** Dynamic lookup rules (remark mirror). Empty = legacy 3-field mode:
     *  effective rules auto-convert from [matchColumn]/[dateMatchColumn]
     *  (see effectiveLookups). Scanner connections keep these empty. */
    val lookups: List<SheetLookupRule> = emptyList(),
    /** Dynamic write rules (remark mirror). Empty = legacy [writeColumn]. */
    val writes: List<SheetWriteRule> = emptyList(),
    /** Header row (1-based) that TEXT-mode refs match against. One per
     *  connection — all text refs on every rule resolve from this row. */
    val headerRow: Int = 1,
    /** Disabled connections are skipped by mirror/sync/test (kept for record). */
    val enabled: Boolean = true,
    /** Explicit purpose (step-1 selector). "" = legacy → inferred from rules. */
    val purpose: String = "",
) {
    /** Lookup rules actually used: dynamic list, else legacy conversion
     *  (matchColumn→consignment, dateMatchColumn→today) for remark conns. */
    fun effectiveLookups(): List<SheetLookupRule> {
        val dynamic = lookups.filter { it.colRef.isNotBlank() }
        if (dynamic.isNotEmpty()) return dynamic
        if (dateMatchColumn.isBlank()) return emptyList()
        return buildList {
            if (matchColumn.isNotBlank()) add(SheetLookupRule(matchColumn, SheetLookupKind.CONSIGNMENT))
            add(SheetLookupRule(dateMatchColumn, SheetLookupKind.TODAY))
        }
    }

    /** Write rules actually used: dynamic list, else legacy [writeColumn]. */
    fun effectiveWrites(): List<SheetWriteRule> {
        val dynamic = writes.filter { it.colRef.isNotBlank() }
        if (dynamic.isNotEmpty()) return dynamic
        if (dateMatchColumn.isBlank() || writeColumn.isBlank()) return emptyList()
        return listOf(SheetWriteRule(writeColumn, SheetWriteKind.VERDICT))
    }

    /** True when the mirror should process this connection (remark kinds). */
    fun isRemarkConnection(): Boolean {
        if (purpose == SheetPurpose.SCANNER) return false
        if (purpose == SheetPurpose.REMARK) {
            return effectiveLookups().isNotEmpty() && effectiveWrites().isNotEmpty()
        }
        val kinds = effectiveWrites().map { it.kind }
        return effectiveLookups().any { it.kind in SheetLookupKind.REMARK_KINDS } &&
            kinds.any { it in SheetWriteKind.REMARK_KINDS }
    }

    /** True when the scanner should use this connection (employee→value). */
    fun isScannerConnection(): Boolean {
        if (purpose == SheetPurpose.REMARK) return false
        if (purpose == SheetPurpose.SCANNER) {
            return effectiveScannerLookup() != null && effectiveScannerWrite() != null
        }
        return effectiveLookups().any { it.kind == SheetLookupKind.EMPLOYEE } &&
            effectiveWrites().any { it.kind == SheetWriteKind.VALUE }
    }

    /** Human label for lists: explicit purpose, else inferred. */
    fun purposeLabel(): String = when {
        purpose == SheetPurpose.SCANNER -> "Scanner"
        purpose == SheetPurpose.REMARK -> "Call Center"
        isScannerConnection() && !isRemarkConnection() -> "Scanner"
        else -> "Call Center"
    }

    /** Scanner-effective lookup: dynamic employee rule, else legacy
     *  matchColumn (scanner conns never set dateMatchColumn). */
    fun effectiveScannerLookup(): SheetLookupRule? {
        effectiveLookups().firstOrNull { it.kind == SheetLookupKind.EMPLOYEE }?.let { return it }
        return matchColumn.takeIf { it.isNotBlank() }?.let { SheetLookupRule(it, SheetLookupKind.EMPLOYEE) }
    }

    /** Scanner-effective write: dynamic value rule, else legacy writeColumn. */
    fun effectiveScannerWrite(): SheetWriteRule? {
        effectiveWrites().firstOrNull { it.kind == SheetWriteKind.VALUE }?.let { return it }
        return writeColumn.takeIf { it.isNotBlank() }?.let { SheetWriteRule(it, SheetWriteKind.VALUE) }
    }

    fun resolvedHeaderRow(): Int = if (headerRow in 1..20) headerRow else 1
}

/** Column reference mode: TEXT = exact header text in [ScannerSheetConn.headerRow],
 *  INDEX = letter ("C") or 1-based number ("3"). */
object SheetColMode {
    const val TEXT = "text"
    const val INDEX = "index"
}

/** Connection purpose — chosen at connect time (step 1) so every sheet's
 *  job is explicit: scanner sheets take scans, remark sheets take mirrors. */
object SheetPurpose {
    const val SCANNER = "scanner"
    const val REMARK = "remark"
}

/** Lookup value sources: every write source except the scanner's value, plus
 *  the scanner's employee. A lookup compares the sheet cell against the
 *  event's value for that source (today = date compare, rest = exact trim
 *  match). This is the "validations er kon column er sathe milbe" mapping:
 *  e.g. lookup {Text "Date", created_at} + lookup {Text "Consignment",
 *  consignment} finds the row whose Date cell == the remark's created date
 *  AND whose Consignment cell == its consignment. */
object SheetLookupKind {
    const val CONSIGNMENT = "consignment"
    const val TODAY = "today"
    const val EMPLOYEE = "employee" // scanner: the scanned employee ID
    val ALL = (listOf(CONSIGNMENT, TODAY, EMPLOYEE) + SheetWriteKind.REMARK_KINDS).distinct()
    val REMARK_KINDS = ALL - EMPLOYEE
}

/** Write value sources for dynamic connection rules. Either caller-passed
 *  semantic values (verdict/remark/note/status/today/consignment), live
 *  validations-table columns, resolved names, or the scanner's value. */
object SheetWriteKind {
    const val VERDICT = "verdict" // caller-passed (validation_remarks.category — not a validations column)
    const val REMARK = "remark" // caller-passed english remarks
    const val NOTE = "note" // caller-passed note
    const val STATUS = "status" // caller-passed remarks_status
    const val TODAY = "today" // yyyy-MM-dd (device, Asia/Dhaka)
    const val CONSIGNMENT = "consignment"
    // Live validations row columns (fetched at mirror time):
    const val REMARKS_STATUS_COL = "remarks_status"
    const val REMARKS_COL = "remarks"
    const val NOTE_COL = "note"
    const val SOURCE_COL = "source"
    const val CREATED_AT = "created_at"
    const val CUSTOMER_PHONE = "customer_phone"
    const val CONSIGNMENT_STATUS = "consignment_status"
    const val BRANCH_ID = "branch_id"
    const val AUTHOR_SYSTEM_ID = "author_system_id"
    const val ASSIGNED_TO_SYSTEM_ID = "assigned_to_system_id"
    // Resolved via users index (fallback: raw system_id):
    const val AUTHOR_NAME = "author_name"
    const val ASSIGNED_NAME = "assigned_name"
    const val VALUE = "value" // scanner: the scanned value
    val ALL = listOf(
        VERDICT, REMARK, NOTE, STATUS, TODAY, CONSIGNMENT,
        AUTHOR_NAME, ASSIGNED_NAME,
        REMARKS_STATUS_COL, REMARKS_COL, NOTE_COL, SOURCE_COL, CREATED_AT,
        CUSTOMER_PHONE, CONSIGNMENT_STATUS, BRANCH_ID,
        AUTHOR_SYSTEM_ID, ASSIGNED_TO_SYSTEM_ID,
        VALUE,
    )
    /** Sources the mirror processes (everything except the scanner's value —
     *  value rules are skipped, never blank-written). */
    val REMARK_KINDS = ALL - VALUE
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
    val kind: String = SheetWriteKind.VERDICT,
    val mode: String = SheetColMode.INDEX,
)
