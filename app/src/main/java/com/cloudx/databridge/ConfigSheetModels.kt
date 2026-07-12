package com.cloudx.databridge

/**
 * Domain models for ConfigSheetFragment.
 *
 * Extracted from ConfigSheetFragment to keep the Fragment class focused
 * on UI coordination. All classes here are pure data — no Android or
 * Firebase dependencies.
 */

// ── Screen state ──────────────────────────────────────────────────────────────
internal enum class ConfigScreen { BRANCH_SELECT, CONNECTING, MANAGING }
internal enum class ConfigPrimaryAction { NEW, SAVE, EXIT }

// ── Drive / Sheets API models ─────────────────────────────────────────────────
data class DriveFile(val id: String, val name: String) {
    override fun toString() = name
}

data class BranchInfo(
    val id:      String = "",
    val name:    String = "",
    val code:    String = "",
    val address: String = "",
    val type:    String = "",
    val status:  String = "",
)

// ── Sheet connection models ───────────────────────────────────────────────────

/**
 * A single component of a composite primary key.
 *
 * type = "fixed" → `value` is literal text (e.g. "run_")
 * type = "col"   → `value` is a sheet column letter whose row-value is read dynamically
 * type = "date"  → `value` is a sheet column letter; the cell value is parsed and
 *                  formatted as ddMMyy (e.g. "040726"), always 6 digits.
 */
data class PkPart(
    val type:   String = "col",  // "col" | "fixed" | "date"
    val value:  String = "",
    val header: String = "",     // sheet header at connect time — for drift detection
)

/** Maps a Firebase field to a sheet column — stores both letter AND header for drift detection. */
data class ColMapping(
    val col:    String = "",  // sheet column letter (e.g. "F")
    val header: String = "",  // exact sheet header text at time of connect (e.g. "Status")
)

/**
 * Maps a Firebase object field (e.g. "consignments") to two sheet columns.
 * Both column letters AND header names are stored for drift detection.
 */
data class ObjectColMapping(
    val keyCol:      String = "",  // col letter for the key column
    val keyHeader:   String = "",  // header text at connect time
    val valueCol:    String = "",  // col letter for the value column
    val valueHeader: String = "",  // header text at connect time
) {
    /** Converts to the "col:X" / "fixed:X" spec strings used during sync. */
    fun keySpec()   = if (keyCol.startsWith("fixed:"))   keyCol   else "col:$keyCol"
    fun valueSpec() = if (valueCol.startsWith("fixed:")) valueCol else "col:$valueCol"
}

/**
 * A fully-described sheet→Firebase connection stored in Firebase under
 * `config/sheets/{branchId}/{connectionId}`.
 */
data class SheetConn(
    val connectionId:         String  = "",  // Firebase push key
    val nickname:             String  = "",  // user-defined label
    val branchId:             String  = "",
    val sheetId:              String  = "",
    val sheetName:            String  = "",
    val tabName:              String  = "",
    val colStart:             Int     = 1,
    val colEnd:               Int     = 10,
    val startRow:             Int?    = null,
    val endRow:               Int?    = null,
    val autoSync:             Boolean = false,
    val syncIntervalMin:      Int     = 30,
    val googleEmail:          String  = "",
    val connectedBy:          String  = "",
    val connectedAt:          Long    = 0L,
    val columnMapping:        Map<String, ColMapping>       = emptyMap(),
    val objectColumnMapping:  Map<String, ObjectColMapping> = emptyMap(),
    val primaryKeyField:      String  = "",  // LEGACY — single col letter for Firebase node key
    val targetNode:           String  = "courier/consignments",
    // Composite key: ordered list of prefix (fixed) + column parts.
    // e.g. [Fixed("run_"), Column("B")] → "run_FDA009"
    // Falls back to `primaryKeyField` (single column) when empty.
    val primaryKeyParts:      List<PkPart> = emptyList(),
) {
    /** Resolves the effective composite key parts, migrating legacy single-column configs. */
    fun effectivePkParts(): List<PkPart> = when {
        primaryKeyParts.isNotEmpty() -> primaryKeyParts
        primaryKeyField.isNotBlank() -> listOf(PkPart("col", primaryKeyField))
        else                         -> emptyList()
    }

    /** Ordered list of column letters in the configured range (A, B, C …). */
    val columns: List<String> get() = (colStart..colEnd).map { n ->
        var num = n; var result = ""
        while (num > 0) {
            val rem = (num - 1) % 26
            result = ('A' + rem) + result
            num = (num - 1) / 26
        }
        result
    }
}
