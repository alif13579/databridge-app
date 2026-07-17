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
 *   config/scanner_sheets/{branchId}/current/{connectionId}   ← active configs (a branch can
 *                                                                 have multiple, same UX as
 *                                                                 ConfigSheetFragment's SheetConn)
 *   config/scanner_sheets/{branchId}/history/{pushId}         ← immutable audit log, one
 *                                                                 entry per create/update/delete
 */
object ScannerSheetRepository {

    private val db get() = FirebaseDatabase.getInstance()
    private val httpClient = OkHttpClient()

    // ── Firebase CRUD ──────────────────────────────────────────────────────

    suspend fun loadConnections(branchId: String): List<ScannerSheetConn> = withContext(Dispatchers.IO) {
        val snap = db.reference.child("config/scanner_sheets/$branchId/current").get().await()
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
                writeColumn     = child.child("writeColumn").getValue(String::class.java).orEmpty(),
                googleEmail     = child.child("googleEmail").getValue(String::class.java).orEmpty(),
                connectedBy     = child.child("connectedBy").getValue(String::class.java).orEmpty(),
                connectedByName = child.child("connectedByName").getValue(String::class.java).orEmpty(),
                connectedAt     = child.child("connectedAt").getValue(Long::class.java) ?: 0L,
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
        val branchRef = db.reference.child("config/scanner_sheets/${conn.branchId}")
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
            "writeColumn"     to conn.writeColumn,
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
            val branchRef = db.reference.child("config/scanner_sheets/$branchId")
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

            val matchValues = ConfigSheetDriveApi.fetchColumnValues(
                accessToken, conn.sheetId, tabName, conn.matchColumn, httpClient
            )
            val writeValues = ConfigSheetDriveApi.fetchColumnValues(
                accessToken, conn.sheetId, tabName, conn.writeColumn, httpClient
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
                    accessToken, conn.sheetId, tabName, conn.writeColumn, targetRow, value, httpClient
                )
                return@withContext WriteResult.Success(row = targetRow, appended = false)
            }

            // No blank slot for this employeeId — append a new row with both columns set.
            val newRow = ConfigSheetDriveApi.appendRowValue(
                accessToken, conn.sheetId, tabName, conn.writeColumn, value, httpClient
            )
            ConfigSheetDriveApi.writeCellValue(
                accessToken, conn.sheetId, tabName, conn.matchColumn, newRow, employeeId, httpClient
            )
            WriteResult.Success(row = newRow, appended = true)
        } catch (e: Exception) {
            WriteResult.Failure(e.message ?: "Unknown error writing to sheet")
        }
    }
}
