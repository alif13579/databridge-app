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
     * later (e.g. "{ddMMyy}") without another schema migration.
     */
    val tabPattern:     String = "Day {dd}",
    /** Column letter to search for a matching employee_id (e.g. "T" for column 20). */
    val matchColumn:    String = "",
    /** Column letter to write the scanned value into, on the same row as the match (e.g. "K"
     *  for column 11). */
    val writeColumn:    String = "",
    val googleEmail:    String = "",
    val connectedBy:    String = "",
    val connectedByName: String = "",
    val connectedAt:    Long = 0L,
)
