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

    /** True when the mirror should process this connection. */
    fun isRemarkConnection(): Boolean =
        effectiveLookups().isNotEmpty() && effectiveWrites().isNotEmpty()
}

/** Lookup value sources for dynamic remark-connection rules. */
object SheetLookupKind {
    const val CONSIGNMENT = "consignment"
    const val TODAY = "today"
    val ALL = listOf(CONSIGNMENT, TODAY)
}

/** Write value sources for dynamic remark-connection rules. */
object SheetWriteKind {
    const val VERDICT = "verdict"
    const val REMARK = "remark"
    const val NOTE = "note"
    const val STATUS = "status"
    const val TODAY = "today"
    val ALL = listOf(VERDICT, REMARK, NOTE, STATUS, TODAY)
}

/** One lookup criterion: column [colRef] (letter like "C" or header text like
 *  "Consignment ID") must match [kind] (consignment/today) on the same row. */
data class SheetLookupRule(
    val colRef: String = "",
    val kind: String = SheetLookupKind.CONSIGNMENT,
)

/** One write target: [kind] value goes into column [colRef] on the matched row. */
data class SheetWriteRule(
    val colRef: String = "",
    val kind: String = SheetWriteKind.VERDICT,
)
