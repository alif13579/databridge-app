package com.cloudx.databridge

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistence + write-time logic for the Scanner Sheet Connector.
 *
 * Firebase layout:
 *   config/connectors/{branchId}/current/{connectionId}   ← active configs (a branch can
 *                                                             have multiple, same UX as
 *                                                             ConfigSheetFragment's SheetConn)
 *   config/connectors/{branchId}/history/{pushId}         ← immutable audit log, one
 *                                                             entry per create/update/delete
 */
object ScannerSheetRepository {

    private val db get() = FirebaseDatabase.getInstance()
    private val httpClient = OkHttpClient()

    // ── Firebase CRUD ──────────────────────────────────────────────────────

    suspend fun loadConnections(branchId: String): List<ScannerSheetConn> = withContext(Dispatchers.IO) {
        val snap = db.reference.child("config/connectors/$branchId/current").get().await()
        snap.children.mapNotNull { child ->
            val id = child.key ?: return@mapNotNull null
            ScannerSheetConn(
                connectionId    = id,
                nickname        = child.child("nickname").getValue(String::class.java).orEmpty(),
                branchId        = branchId,
                sheetId         = child.child("sheetId").getValue(String::class.java).orEmpty(),
                sheetName       = child.child("sheetName").getValue(String::class.java).orEmpty(),
                tabPattern      = child.child("tabPattern").getValue(String::class.java) ?: "Day {dd}",
                matchColumn     = child.child("matchColumn").getValue(String::class.java).orEmpty(),
                dateMatchColumn = child.child("dateMatchColumn").getValue(String::class.java).orEmpty(),
                writeColumn     = child.child("writeColumn").getValue(String::class.java).orEmpty(),
                googleEmail     = child.child("googleEmail").getValue(String::class.java).orEmpty(),
                connectedBy     = child.child("connectedBy").getValue(String::class.java).orEmpty(),
                connectedByName = child.child("connectedByName").getValue(String::class.java).orEmpty(),
                connectedAt     = child.child("connectedAt").getValue(Long::class.java) ?: 0L,
                lookups         = child.child("lookups").children.mapNotNull { r ->
                    val ref = r.child("colRef").getValue(String::class.java).orEmpty()
                    if (ref.isBlank()) null else SheetLookupRule(
                        colRef = ref,
                        kind = r.child("kind").getValue(String::class.java)
                            ?.takeIf { it in SheetLookupKind.ALL } ?: SheetLookupKind.CONSIGNMENT,
                        mode = r.child("mode").getValue(String::class.java)
                            ?.takeIf { it == SheetColMode.TEXT } ?: SheetColMode.INDEX,
                    )
                },
                writes          = child.child("writes").children.mapNotNull { r ->
                    val ref = r.child("colRef").getValue(String::class.java).orEmpty()
                    if (ref.isBlank()) null else SheetWriteRule(
                        colRef = ref,
                        kind = r.child("kind").getValue(String::class.java)
                            ?.takeIf { it in SheetWriteKind.ALL } ?: SheetWriteKind.VERDICT,
                        mode = r.child("mode").getValue(String::class.java)
                            ?.takeIf { it == SheetColMode.TEXT } ?: SheetColMode.INDEX,
                    )
                },
                headerRow       = child.child("headerRow").getValue(Long::class.java)?.toInt() ?: 1,
            )
        }
    }

    /** Saves (creates or updates) a connection and appends one audit-history entry.
     *  Returns the connectionId (newly generated if [conn].connectionId was blank). */
    suspend fun saveConnection(
        conn: ScannerSheetConn,
        actingUid: String,
        actingName: String,
        isNew: Boolean
    ): String = withContext(Dispatchers.IO) {
        val branchRef = db.reference.child("config/connectors/${conn.branchId}")
        val connectionId = conn.connectionId.ifBlank {
            branchRef.child("current").push().key ?: System.currentTimeMillis().toString()
        }
        val now = System.currentTimeMillis()
        val data = mapOf(
            "nickname"        to conn.nickname,
            "sheetId"         to conn.sheetId,
            "sheetName"       to conn.sheetName,
            "tabPattern"      to conn.tabPattern,
            "matchColumn"     to conn.matchColumn,
            "dateMatchColumn" to conn.dateMatchColumn,
            "writeColumn"     to conn.writeColumn,
            "lookups"         to conn.lookups.filter { it.colRef.isNotBlank() }
                .map { mapOf("colRef" to it.colRef.trim(), "kind" to it.kind, "mode" to it.mode) },
            "writes"          to conn.writes.filter { it.colRef.isNotBlank() }
                .map { mapOf("colRef" to it.colRef.trim(), "kind" to it.kind, "mode" to it.mode) },
            "headerRow"       to conn.resolvedHeaderRow(),
            "googleEmail"     to conn.googleEmail,
            "connectedBy"     to actingUid,
            "connectedByName" to actingName,
            "connectedAt"     to (if (isNew) now else conn.connectedAt),
        )
        branchRef.child("current").child(connectionId).setValue(data).await()

        val historyEntry = data + mapOf(
            "connectionId" to connectionId,
            "action"       to if (isNew) "created" else "updated",
            "changedAt"    to now,
            "changedBy"    to actingUid,
            "changedByName" to actingName,
        )
        branchRef.child("history").push().setValue(historyEntry).await()

        connectionId
    }

    suspend fun deleteConnection(branchId: String, connectionId: String, actingUid: String, actingName: String) =
        withContext(Dispatchers.IO) {
            val branchRef = db.reference.child("config/connectors/$branchId")
            branchRef.child("current").child(connectionId).removeValue().await()
            branchRef.child("history").push().setValue(
                mapOf(
                    "connectionId"  to connectionId,
                    "action"        to "deleted",
                    "changedAt"     to System.currentTimeMillis(),
                    "changedBy"     to actingUid,
                    "changedByName" to actingName,
                )
            ).await()
        }

    /** Resolves one rule's colRef to a letter (mode-aware, same semantics as
     *  RemarkSheetMirror): INDEX = letter/number, TEXT = exact header match in
     *  [headerRow]. Null when unresolvable. */
    suspend fun resolveRuleLetter(
        accessToken: String, sheetId: String, tab: String,
        rule: SheetLookupRule, headerRow: Int
    ): String? = resolveRef(accessToken, sheetId, tab, rule.colRef, rule.mode, headerRow)

    suspend fun resolveRuleLetter(
        accessToken: String, sheetId: String, tab: String,
        rule: SheetWriteRule, headerRow: Int
    ): String? = resolveRef(accessToken, sheetId, tab, rule.colRef, rule.mode, headerRow)

    private suspend fun resolveRef(
        accessToken: String, sheetId: String, tab: String,
        ref: String, mode: String, headerRow: Int
    ): String? = withContext(Dispatchers.IO) {
        val t = ref.trim()
        if (t.isEmpty()) return@withContext null
        if (mode != SheetColMode.TEXT) {
            if (Regex("^[A-Za-z]{1,3}$").matches(t)) return@withContext t.uppercase()
            val idx = ConfigSheetParseUtil.parseColInput(t) ?: return@withContext null
            return@withContext ConfigSheetParseUtil.colIndexToLetter(idx)
        }
        val data = ConfigSheetDriveApi.fetchRowValues(accessToken, sheetId, tab, headerRow, httpClient)
        val idx = data.indexOfFirst { it.trim() == t }
        if (idx < 0) return@withContext null
        ConfigSheetParseUtil.colIndexToLetter(idx + 1)
    }

    // ── Scan-time write logic ──────────────────────────────────────────────

    /** Resolves a tab pattern like "Day {dd}" against today's date. Zero-padded day-of-month,
     *  e.g. "Day 16", "Day 03" — matching the exact format confirmed for this connector. */
    fun resolveTabName(pattern: String, atDate: Date = Date()): String {
        val dd = SimpleDateFormat("dd", Locale.ENGLISH).format(atDate)
        return pattern.replace("{dd}", dd)
    }

    sealed class WriteResult {
        data class Success(val row: Int, val appended: Boolean) : WriteResult()
        data class Failure(val message: String) : WriteResult()
    }

    /**
     * Writes [value] into a blank slot for [employeeId] in the connected sheet:
     *   1. Resolve today's tab name from the connection's tabPattern.
     *   2. Read the match column (employee_id) in full.
     *   3. Find the FIRST row where match column == employeeId AND the write column is blank.
     *      Re-reads the write column fresh for that check — never assumes a cached blank state,
     *      so concurrent scans (this device or another) can't silently overwrite each other.
     *   4. If found: write [value] into that row's write column.
     *   5. If every matching row already has a value: append a new row — writing employeeId
     *      into the match column and [value] into the write column, both on the same new row.
     *
     * Runs on Dispatchers.IO. Never throws — failures come back as [WriteResult.Failure] with
     * a human-readable message, since this is called from UI-facing scan-submit code that
     * needs to show the agent something meaningful rather than crash.
     */
    suspend fun writeScannedValue(
        conn: ScannerSheetConn,
        accessToken: String,
        employeeId: String,
        value: String
    ): WriteResult = withContext(Dispatchers.IO) {
        try {
            val tabName = resolveTabName(conn.tabPattern)
            val headerRow = conn.resolvedHeaderRow()
            // Scanner rules: lookup kind=employee, write kind=value. Legacy
            // conns auto-convert from matchColumn/writeColumn (see model).
            val lookupRule = conn.effectiveScannerLookup()
                ?: return@withContext WriteResult.Failure("এই connection-এ lookup rule নেই")
            val writeRule = conn.effectiveScannerWrite()
                ?: return@withContext WriteResult.Failure("এই connection-এ write rule নেই")
            val matchLetter = resolveRuleLetter(accessToken, conn.sheetId, tabName, lookupRule, headerRow)
                ?: return@withContext WriteResult.Failure("lookup column '${lookupRule.colRef.trim()}' পাওয়া যায়নি")
            val writeLetter = resolveRuleLetter(accessToken, conn.sheetId, tabName, writeRule, headerRow)
                ?: return@withContext WriteResult.Failure("write column '${writeRule.colRef.trim()}' পাওয়া যায়নি")

            val matchValues = ConfigSheetDriveApi.fetchColumnValues(
                accessToken, conn.sheetId, tabName, matchLetter, httpClient
            )
            val writeValues = ConfigSheetDriveApi.fetchColumnValues(
                accessToken, conn.sheetId, tabName, writeLetter, httpClient
            )

            // matchValues[i] / writeValues[i] correspond to sheet row (i + 1). The write-column
            // fetch may be shorter than the match-column fetch (trailing blanks are omitted by
            // the Sheets API) — index safely rather than assuming equal length.
            var targetRow = -1
            for (i in matchValues.indices) {
                if (matchValues[i].trim() != employeeId.trim()) continue
                val existingWrite = writeValues.getOrNull(i)?.trim().orEmpty()
                if (existingWrite.isBlank()) {
                    targetRow = i + 1 // convert 0-index to 1-indexed sheet row
                    break
                }
            }

            if (targetRow > 0) {
                ConfigSheetDriveApi.writeCellValue(
                    accessToken, conn.sheetId, tabName, writeLetter, targetRow, value, httpClient
                )
                return@withContext WriteResult.Success(row = targetRow, appended = false)
            }

            // No blank slot for this employeeId — append a new row with both columns set.
            val newRow = ConfigSheetDriveApi.appendRowValue(
                accessToken, conn.sheetId, tabName, writeLetter, value, httpClient
            )
            ConfigSheetDriveApi.writeCellValue(
                accessToken, conn.sheetId, tabName, matchLetter, newRow, employeeId, httpClient
            )
            WriteResult.Success(row = newRow, appended = true)
        } catch (e: Exception) {
            WriteResult.Failure(e.message ?: "Unknown error writing to sheet")
        }
    }
}
