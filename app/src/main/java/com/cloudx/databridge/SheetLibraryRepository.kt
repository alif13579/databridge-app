package com.cloudx.databridge

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Neutral sheet libraries + Scanner bindings.
 *
 * Libraries live on the SAME node as legacy connectors
 * (`config/connectors/{branchId}/current`, `isLibrary = true`) so the
 * Connectors list shows one unified list with two badges (📚 / legacy).
 * Scanner bindings live at `config/sheetBindings/{branchId}/scanner/{id}`
 * (one per library per branch — save upserts by libraryId).
 *
 * The write executor is fragment-agnostic: match ALL lookup columns
 * (exact trim match), write into the matched row's blank write columns,
 * else append one row carrying every pair. Other fragments reuse it with
 * their own field values — only the binding UI differs per fragment.
 */
object SheetLibraryRepository {

    private val db get() = FirebaseDatabase.getInstance()

    // ── Library <-> legacy-conn transport ──────────────────────────────
    // Libraries reuse ScannerSheetConn as the Firebase shape (same node);
    // kinds are meaningless for libraries and stored as neutral defaults.

    fun toConn(lib: SheetLibrary, libraryId: String): ScannerSheetConn =
        ScannerSheetConn(
            connectionId = libraryId,
            nickname = lib.nickname,
            branchId = lib.branchId,
            sheetId = lib.sheetId,
            sheetName = lib.sheetName,
            tabPattern = lib.tabPattern,
            googleEmail = lib.googleEmail,
            connectedBy = lib.connectedBy,
            connectedByName = lib.connectedByName,
            connectedAt = lib.connectedAt,
            lookups = lib.lookupCols.map {
                SheetLookupRule(it.colRef, SheetLookupKind.CONSIGNMENT, it.mode)
            },
            writes = lib.writeCols.map {
                SheetWriteRule(it.colRef, SheetWriteKind.FEEDBACK, it.mode)
            },
            headerRow = lib.headerRow,
            enabled = lib.enabled,
            purpose = "",
            isLibrary = true,
            scopeType = lib.scopeType,
            scopeMonth = lib.scopeMonth,
            scopeFrom = lib.scopeFrom,
            scopeTo = lib.scopeTo,
        )

    fun toLibrary(conn: ScannerSheetConn): SheetLibrary =
        SheetLibrary(
            libraryId = conn.connectionId,
            nickname = conn.nickname,
            branchId = conn.branchId,
            sheetId = conn.sheetId,
            sheetName = conn.sheetName,
            tabPattern = conn.tabPattern,
            googleEmail = conn.googleEmail,
            connectedBy = conn.connectedBy,
            connectedByName = conn.connectedByName,
            connectedAt = conn.connectedAt,
            lookupCols = conn.lookups.map { SheetColRef(it.colRef, it.mode) },
            writeCols = conn.writes.map { SheetColRef(it.colRef, it.mode) },
            headerRow = conn.headerRow,
            enabled = conn.enabled,
            scopeType = conn.scopeType,
            scopeMonth = conn.scopeMonth,
            scopeFrom = conn.scopeFrom,
            scopeTo = conn.scopeTo,
        )

    suspend fun loadLibraries(branchId: String): List<SheetLibrary> =
        withContext(Dispatchers.IO) {
            runCatching {
                ScannerSheetRepository.loadConnections(branchId)
                    .filter { it.isLibrary }
                    .map { toLibrary(it) }
            }.getOrDefault(emptyList())
        }

    // ── Scanner bindings ───────────────────────────────────────────────

    private fun bindingsRef(branchId: String) =
        db.reference.child("config/sheetBindings/$branchId/scanner")

    suspend fun loadScannerBindings(branchId: String): List<ScannerBinding> =
        withContext(Dispatchers.IO) {
            runCatching {
                val snap = bindingsRef(branchId).get().await()
                snap.children.mapNotNull { child ->
                    val id = child.key ?: return@mapNotNull null
                    ScannerBinding(
                        bindingId = id,
                        libraryId = child.child("libraryId").getValue(String::class.java).orEmpty(),
                        branchId = branchId,
                        lookups = child.child("lookups").children.mapNotNull { r ->
                            val ref = r.child("colRef").getValue(String::class.java).orEmpty()
                            val field = r.child("field").getValue(String::class.java).orEmpty()
                            if (ref.isBlank() || field.isBlank()) null else ScannerFieldMap(
                                colRef = ref,
                                mode = r.child("mode").getValue(String::class.java)
                                    ?.takeIf { it == SheetColMode.TEXT } ?: SheetColMode.INDEX,
                                field = field,
                            )
                        },
                        writes = child.child("writes").children.mapNotNull { r ->
                            val ref = r.child("colRef").getValue(String::class.java).orEmpty()
                            val field = r.child("field").getValue(String::class.java).orEmpty()
                            if (ref.isBlank() || field.isBlank()) null else ScannerFieldMap(
                                colRef = ref,
                                mode = r.child("mode").getValue(String::class.java)
                                    ?.takeIf { it == SheetColMode.TEXT } ?: SheetColMode.INDEX,
                                field = field,
                            )
                        },
                        enabled = child.child("enabled").getValue(Boolean::class.java) ?: true,
                        updatedBy = child.child("updatedBy").getValue(String::class.java).orEmpty(),
                        updatedByName = child.child("updatedByName").getValue(String::class.java).orEmpty(),
                        updatedAt = child.child("updatedAt").getValue(Long::class.java) ?: 0L,
                    )
                }.filter { it.libraryId.isNotBlank() }
            }.getOrDefault(emptyList())
        }

    /** Saves (creates or updates) a binding. One per library per branch:
     *  a blank bindingId reuses the existing binding for [binding.libraryId]
     *  when one exists. Returns the bindingId. */
    suspend fun saveScannerBinding(
        binding: ScannerBinding,
        actingUid: String,
        actingName: String,
    ): String = withContext(Dispatchers.IO) {
        val ref = bindingsRef(binding.branchId)
        val existingId = if (binding.bindingId.isBlank()) {
            runCatching {
                ref.orderByChild("libraryId").equalTo(binding.libraryId)
                    .get().await().children.firstOrNull()?.key
            }.getOrNull()
        } else binding.bindingId
        val bindingId = existingId
            ?: ref.push().key
            ?: System.currentTimeMillis().toString()
        val now = System.currentTimeMillis()
        val data = mapOf(
            "libraryId" to binding.libraryId,
            "lookups" to binding.lookups.filter { it.colRef.isNotBlank() && it.field.isNotBlank() }
                .map { mapOf("colRef" to it.colRef.trim(), "mode" to it.mode, "field" to it.field) },
            "writes" to binding.writes.filter { it.colRef.isNotBlank() && it.field.isNotBlank() }
                .map { mapOf("colRef" to it.colRef.trim(), "mode" to it.mode, "field" to it.field) },
            "enabled" to binding.enabled,
            "updatedBy" to actingUid,
            "updatedByName" to actingName,
            "updatedAt" to now,
        )
        ref.child(bindingId).setValue(data).await()
        bindingId
    }

    suspend fun deleteScannerBinding(branchId: String, bindingId: String) =
        withContext(Dispatchers.IO) {
            bindingsRef(branchId).child(bindingId).removeValue().await()
        }

    // ── CC bindings ────────────────────────────────────────────────────

    private fun ccBindingsRef(branchId: String) =
        db.reference.child("config/sheetBindings/$branchId/cc")

    suspend fun loadCcBindings(branchId: String): List<CcBinding> =
        withContext(Dispatchers.IO) {
            runCatching {
                val snap = ccBindingsRef(branchId).get().await()
                snap.children.mapNotNull { child ->
                    val id = child.key ?: return@mapNotNull null
                    fun maps(node: String): List<CcFieldMap> =
                        child.child(node).children.mapNotNull { r ->
                            val ref = r.child("colRef").getValue(String::class.java).orEmpty()
                            val field = r.child("field").getValue(String::class.java).orEmpty()
                            if (ref.isBlank() || field.isBlank()) null else CcFieldMap(
                                colRef = ref,
                                mode = r.child("mode").getValue(String::class.java)
                                    ?.takeIf { it == SheetColMode.TEXT } ?: SheetColMode.INDEX,
                                field = field,
                            )
                        }
                    val binding = CcBinding(
                        bindingId = id,
                        libraryId = child.child("libraryId").getValue(String::class.java).orEmpty(),
                        branchId = branchId,
                        lookups = maps("lookups"),
                        writes = maps("writes"),
                        enabled = child.child("enabled").getValue(Boolean::class.java) ?: true,
                        updatedBy = child.child("updatedBy").getValue(String::class.java).orEmpty(),
                        updatedByName = child.child("updatedByName").getValue(String::class.java).orEmpty(),
                        updatedAt = child.child("updatedAt").getValue(Long::class.java) ?: 0L,
                    )
                    if (binding.libraryId.isBlank()) null else binding
                }
            }.getOrDefault(emptyList())
        }

    /** Saves (creates or updates) a CC binding. One per library per branch:
     *  a blank bindingId reuses the existing binding for [binding.libraryId]
     *  when one exists. Returns the bindingId. */
    suspend fun saveCcBinding(
        binding: CcBinding,
        actingUid: String,
        actingName: String,
    ): String = withContext(Dispatchers.IO) {
        val ref = ccBindingsRef(binding.branchId)
        val existingId = if (binding.bindingId.isBlank()) {
            runCatching {
                ref.orderByChild("libraryId").equalTo(binding.libraryId)
                    .get().await().children.firstOrNull()?.key
            }.getOrNull()
        } else binding.bindingId
        val bindingId = existingId
            ?: ref.push().key
            ?: System.currentTimeMillis().toString()
        val data = mapOf(
            "libraryId" to binding.libraryId,
            "lookups" to binding.lookups.filter { it.colRef.isNotBlank() && it.field.isNotBlank() }
                .map { mapOf("colRef" to it.colRef.trim(), "mode" to it.mode, "field" to it.field) },
            "writes" to binding.writes.filter { it.colRef.isNotBlank() && it.field.isNotBlank() }
                .map { mapOf("colRef" to it.colRef.trim(), "mode" to it.mode, "field" to it.field) },
            "enabled" to binding.enabled,
            "updatedBy" to actingUid,
            "updatedByName" to actingName,
            "updatedAt" to System.currentTimeMillis(),
        )
        ref.child(bindingId).setValue(data).await()
        bindingId
    }

    suspend fun deleteCcBinding(branchId: String, bindingId: String) =
        withContext(Dispatchers.IO) {
            ccBindingsRef(branchId).child(bindingId).removeValue().await()
        }

    /**
     * Remark connections for [branchId] covering [date], from CC bindings
     * only (all-in-one: legacy purpose/kind connections are no longer read
     * — convert them to libraries from the Connectors list).
     */
    suspend fun resolveRemarkConns(
        branchId: String,
        date: java.time.LocalDate,
    ): List<ScannerSheetConn> = withContext(Dispatchers.IO) {
        val bindings = loadCcBindings(branchId)
            .filter { it.enabled && it.effectiveLookups().isNotEmpty() && it.effectiveWrites().isNotEmpty() }
        if (bindings.isEmpty()) return@withContext emptyList()
        val libraries = loadLibraries(branchId)
            .filter { it.enabled }.associateBy { it.libraryId }
        bindings.mapNotNull { b ->
            val lib = libraries[b.libraryId] ?: return@mapNotNull null
            if (!SheetScope.covers(lib.scopeType, lib.scopeMonth, lib.scopeFrom, lib.scopeTo, date)) {
                return@mapNotNull null
            }
            b.toConn(lib)
        }
    }

    // ── Scan field values ──────────────────────────────────────────────

    private val SCAN_AT_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("Asia/Dhaka")
    }

    /** Resolves one scanner field key against a scan. Unknown key = "". */
    fun scanFieldValue(
        field: String,
        scanCode: String,
        employeeId: String,
        agentName: String,
        scanAt: Long,
    ): String = when (field) {
        ScannerField.EMPLOYEE_ID -> employeeId.trim()
        ScannerField.SCAN_VALUE -> scanCode.trim()
        ScannerField.SCAN_AT -> if (scanAt > 0) SCAN_AT_FMT.format(Date(scanAt)) else ""
        ScannerField.AGENT_NAME -> agentName.trim()
        else -> ""
    }

    // ── Generalized write executor ─────────────────────────────────────
    // Same blank-slot rule as the legacy scanner write, but N lookups × M
    // writes: first row where EVERY lookup matches and EVERY write cell is
    // blank wins; else append one row carrying every pair.

    data class ColValue(val ref: SheetColRef, val letter: String, val value: String)

    suspend fun writeBoundValues(
        library: SheetLibrary,
        accessToken: String,
        lookupPairs: List<Pair<SheetColRef, String>>,
        writePairs: List<Pair<SheetColRef, String>>,
        http: okhttp3.OkHttpClient,
    ): ScannerSheetRepository.WriteResult = withContext(Dispatchers.IO) {
        try {
            if (!library.enabled)
                return@withContext ScannerSheetRepository.WriteResult.Failure("Sheet library disabled")
            if (lookupPairs.isEmpty())
                return@withContext ScannerSheetRepository.WriteResult.Failure("এই binding-এ lookup নেই")
            if (writePairs.isEmpty())
                return@withContext ScannerSheetRepository.WriteResult.Failure("এই binding-এ write নেই")
            val tabName = ScannerSheetRepository.resolveTabName(library.tabPattern)
            val headerRow = library.resolvedHeaderRow()

            suspend fun letterOf(ref: SheetColRef, what: String): String? {
                val t = ref.colRef.trim()
                if (t.isEmpty()) return null
                if (ref.mode != SheetColMode.TEXT) {
                    if (Regex("^[A-Za-z]{1,3}$").matches(t)) return t.uppercase()
                    val idx = ConfigSheetParseUtil.parseColInput(t) ?: return null
                    return ConfigSheetParseUtil.colIndexToLetter(idx)
                }
                val data = ConfigSheetDriveApi.fetchRowValues(
                    accessToken, library.sheetId, tabName, headerRow, http
                )
                val idx = data.indexOfFirst { it.trim() == t }
                if (idx < 0) return null
                return ConfigSheetParseUtil.colIndexToLetter(idx + 1)
            }

            val lookups = mutableListOf<ColValue>()
            for ((ref, value) in lookupPairs) {
                val letter = letterOf(ref, "lookup")
                    ?: return@withContext ScannerSheetRepository.WriteResult.Failure(
                        "lookup column '${ref.colRef.trim()}' পাওয়া যায়নি")
                lookups.add(ColValue(ref, letter, value))
            }
            val writes = mutableListOf<ColValue>()
            for ((ref, value) in writePairs) {
                val letter = letterOf(ref, "write")
                    ?: return@withContext ScannerSheetRepository.WriteResult.Failure(
                        "write column '${ref.colRef.trim()}' পাওয়া যায়নি")
                writes.add(ColValue(ref, letter, value))
            }

            // Column fetches may differ in length (Sheets omits trailing
            // blanks) — index safely.
            val columns = mutableMapOf<String, List<String>>()
            for (cv in (lookups + writes).distinctBy { it.letter }) {
                columns[cv.letter] = ConfigSheetDriveApi.fetchColumnValues(
                    accessToken, library.sheetId, tabName, cv.letter, http
                )
            }
            val rowCount = columns.values.maxOfOrNull { it.size } ?: 0
            var targetRow = -1
            for (i in 0 until rowCount) {
                if (!lookups.all { (columns[it.letter]?.getOrNull(i)?.trim().orEmpty()) == it.value }) continue
                if (!writes.all { columns[it.letter]?.getOrNull(i)?.trim().isNullOrBlank() }) continue
                targetRow = i + 1
                break
            }
            if (targetRow > 0) {
                for (w in writes) {
                    ConfigSheetDriveApi.writeCellValue(
                        accessToken, library.sheetId, tabName, w.letter, targetRow, w.value, http
                    )
                }
                return@withContext ScannerSheetRepository.WriteResult.Success(row = targetRow, appended = false)
            }

            // No blank slot — append one row carrying every lookup + write.
            val first = (writes + lookups).first()
            val newRow = ConfigSheetDriveApi.appendRowValue(
                accessToken, library.sheetId, tabName, first.letter, first.value, http
            )
            for (cv in (lookups + writes).drop(1)) {
                ConfigSheetDriveApi.writeCellValue(
                    accessToken, library.sheetId, tabName, cv.letter, newRow, cv.value, http
                )
            }
            ScannerSheetRepository.WriteResult.Success(row = newRow, appended = true)
        } catch (e: Exception) {
            ScannerSheetRepository.WriteResult.Failure(e.message ?: "Unknown error writing to sheet")
        }
    }
}
