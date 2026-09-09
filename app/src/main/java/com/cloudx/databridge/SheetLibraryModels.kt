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
    /** Columns a binding may match rows on. */
    val lookupCols: List<SheetColRef> = emptyList(),
    /** Columns a binding may write into. */
    val writeCols: List<SheetColRef> = emptyList(),
    val headerRow: Int = 1,
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
        else -> field.ifBlank { "— field বেছে নিন —" }
    }

    /** Fields allowed on the lookup side (exact-trim match). */
    val LOOKUP_FIELDS = listOf(EMPLOYEE_ID, SCAN_VALUE)

    /** Fields allowed on the write side. */
    val WRITE_FIELDS = listOf(SCAN_VALUE, SCAN_AT, EMPLOYEE_ID, AGENT_NAME)

    fun isKnown(field: String): Boolean = field in (LOOKUP_FIELDS + WRITE_FIELDS)
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
) {
    fun effectiveLookups(): List<ScannerFieldMap> =
        lookups.filter { it.colRef.isNotBlank() && it.field.isNotBlank() }

    fun effectiveWrites(): List<ScannerFieldMap> =
        writes.filter { it.colRef.isNotBlank() && it.field.isNotBlank() }

    /** Human summary: "B=Employee ID → K=Scan text". */
    fun summary(): String {
        val l = effectiveLookups().joinToString(" + ") { "${it.colRef.trim()}=${ScannerField.label(it.field)}" }
        val w = effectiveWrites().joinToString(", ") { "${it.colRef.trim()}←${ScannerField.label(it.field)}" }
        return "$l → $w"
    }
}
