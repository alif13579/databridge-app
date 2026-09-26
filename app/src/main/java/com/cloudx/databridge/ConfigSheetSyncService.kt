package com.cloudx.databridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.common.api.Scope
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Sheets → Firebase sync running in a foreground dataSync service, so the sync
 * keeps going when the app is backgrounded, the screen is off, or the user
 * leaves the Config → Sheets tab.
 *
 * Why this exists: the Sheets tab previously ran
 * [syncSheetToFirebase][ConfigSheetFragment.syncSheetToFirebase] inside
 * `viewLifecycleOwner.lifecycleScope` — tied to the fragment's VIEW. Switching
 * tabs, rotating, or backgrounding long enough to destroy the view cancelled
 * the in-flight coroutine mid-loop (plus the drift AlertDialog could suspend
 * forever with no Activity to show it). A foreground service has no such tie.
 *
 * Speed: the old loop did 2+ sequential Firebase RPCs per row
 * (exist-check `get()` + `updateChildren()`, plus per-row helper reads).
 * This engine:
 *  - pre-fetches ALL reads in parallel (exist checks, agent branches,
 *    consignment phones, dupe index, phone-status guards — max 10 in flight),
 *  - computes diffs locally, and
 *  - flushes writes in multi-path batches (≤120 rows / ≤900 paths per RPC).
 * A 500-row sync drops from ~2000 sequential RPCs to ~2 batches + parallel reads.
 *
 * Drift policy (non-interactive — no dialog possible in background):
 * moved / fuzzy columns auto-resolve exactly like tapping "Sync anyway" did;
 * MISSING headers abort with a clear error notification (same outcome as the
 * old Cancel/Reposition path — open the tab and fix the range).
 *
 * Single-flight: a second start while running is refused ([start] returns
 * false → caller toasts "already running").
 */
class ConfigSheetSyncService : Service() {

    companion object {
        const val ACTION_START = "com.cloudx.databridge.CONFIG_SHEET_SYNC_START"
        const val EXTRA_BRANCH_ID = "branch_id"
        const val EXTRA_CONNECTION_ID = "connection_id"

        private const val CHANNEL_PROGRESS = "sheet_sync_progress"
        private const val CHANNEL_SUMMARY = "sheet_sync_summary"
        private const val NOTIF_PROGRESS_ID = 5121
        private const val NOTIF_SUMMARY_ID = 5122

        private const val READ_PARALLELISM = 10
        private const val FLUSH_ROW_EVERY = 120
        private const val FLUSH_PATH_EVERY = 900

        @Volatile var isRunning: Boolean = false
            private set

        /** Live progress mirror for an open Sheets tab (main thread). Null-safe. */
        @Volatile var onProgress: ((done: Int, total: Int, inserted: Int, updated: Int, skipped: Int) -> Unit)? = null

        /** One-shot finish hook for an open Sheets tab. Cleared after delivery. */
        @Volatile var onFinish: ((ok: Boolean, summary: String) -> Unit)? = null

        /** Starts a sync; false when one is already running. */
        fun start(context: Context, branchId: String, connectionId: String): Boolean {
            if (isRunning) return false
            if (branchId.isBlank() || connectionId.isBlank()) return false
            val intent = Intent(context.applicationContext, ConfigSheetSyncService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_BRANCH_ID, branchId)
                putExtra(EXTRA_CONNECTION_ID, connectionId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            } catch (_: Exception) {
                return false
            }
            return true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastNotifyMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY
        val branchId = intent.getStringExtra(EXTRA_BRANCH_ID).orEmpty()
        val connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID).orEmpty()
        if (branchId.isBlank() || connectionId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        ensureChannels()
        ServiceCompat.startForeground(
            this,
            NOTIF_PROGRESS_ID,
            progressNotification("Starting…", 0, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else 0
        )
        scope.launch {
            val result = try {
                runSync(branchId, connectionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncResult(
                    ok = false,
                    summary = "⚠ Sync error: ${e.message?.take(200) ?: e.javaClass.simpleName}",
                    inserted = 0, updated = 0, skipped = 0, runIndexCount = 0
                )
            }
            finishWithResult(result)
        }
        return START_NOT_STICKY
    }

    // ── Engine ────────────────────────────────────────────────────────

    private data class SyncResult(
        val ok: Boolean,
        val summary: String,
        val inserted: Int,
        val updated: Int,
        val skipped: Int,
        val runIndexCount: Int,
    )

    private data class RowWork(
        val conId: String,
        val fieldMap: Map<String, Any>,
        val objectWrites: Map<String, Any>,
        val phone: String,
        val userSystemId: String,
        val runCids: List<String>,
    )

    private suspend fun runSync(branchId: String, connectionId: String): SyncResult {
        val db = FirebaseDatabase.getInstance()

        // ── 0. Load connection (fresh from Firebase, not a stale UI copy) ──
        val conn = loadConn(db, branchId, connectionId)
            ?: return SyncResult(false, "⚠ Sync aborted: connection not found (deleted?)", 0, 0, 0, 0)
        val pkParts = conn.effectivePkParts().ifEmpty {
            conn.columnMapping["consignmentId"]?.col?.let { listOf(PkPart("col", it)) } ?: emptyList()
        }
        if (conn.columnMapping.isEmpty()) {
            return SyncResult(false, "⚠ No column mapping — complete Step 5 in the Sheets tab", 0, 0, 0, 0)
        }
        if (pkParts.isEmpty()) {
            return SyncResult(false, "⚠ No primary key selected — select it in Step 5", 0, 0, 0, 0)
        }

        // ── 1. Silent token (no Activity possible here) ──
        val token = withContext(Dispatchers.IO) {
            try {
                val account = GoogleSignInHelper.restoreOwnAccountIfMatching(
                    applicationContext, "sheets_google_account",
                    listOf(
                        Scope(ConfigSheetDriveApi.SCOPE_DRIVE),
                        Scope(ConfigSheetDriveApi.SCOPE_SHEETS)
                    )
                ) ?: return@withContext null
                val acctObj = account.account ?: return@withContext null
                GoogleAuthUtil.getToken(applicationContext, acctObj, ConfigSheetDriveApi.OAUTH_SCOPE)
            } catch (_: Exception) {
                null // incl. UserRecoverableAuthException — consent needs UI
            }
        } ?: return SyncResult(
            false,
            "⚠ Google token unavailable — open Config → Sheets once to reconnect the Google account",
            0, 0, 0, 0
        )

        // ── 2. Fetch all sheet rows ──
        updateProgressNotif("Fetching sheet…", 0, 0, 0, 0, 0)
        val startLetter = ConfigSheetParseUtil.colIndexToLetter(conn.colStart)
        val endLetter = ConfigSheetParseUtil.colIndexToLetter(conn.colEnd)
        val sRow = conn.startRow?.takeIf { it > 0 } ?: 1
        val eRow = conn.endRow?.takeIf { it > 0 }
        val rangeStr = if (eRow != null) "${conn.tabName}!${startLetter}${sRow}:${endLetter}${eRow}"
        else "${conn.tabName}!${startLetter}${sRow}:${endLetter}"
        val encodedRange = java.net.URLEncoder.encode(rangeStr, "UTF-8")
        val url = "https://sheets.googleapis.com/v4/spreadsheets/${conn.sheetId}/values/$encodedRange"
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val allRows: List<List<String>>? = withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url)
                    .header("Authorization", "Bearer $token").build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string() ?: return@withContext null
                    val arr = org.json.JSONObject(body).optJSONArray("values")
                        ?: return@withContext emptyList()
                    (0 until arr.length()).map { i ->
                        val row = arr.getJSONArray(i)
                        (0 until row.length()).map { j -> row.optString(j, "") }
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
        if (allRows == null) {
            return SyncResult(false, "⚠ Sheet fetch failed — check network / sheet sharing", 0, 0, 0, 0)
        }
        val headerRow = allRows.firstOrNull() ?: emptyList()
        val dataRows = if (allRows.size > 1) allRows.drop(1) else allRows
        if (dataRows.isEmpty()) {
            return SyncResult(false, "⚠ No data in sheet", 0, 0, 0, 0)
        }

        // ── 3. Column drift detection (non-interactive) ──
        val currentHeaders = headerRow.mapIndexed { idx, header ->
            ConfigSheetParseUtil.colIndexToLetter(conn.colStart + idx) to header.trim()
        }.toMap()
        val resolvedMapping = mutableMapOf<String, String>()
        val driftNotes = mutableListOf<String>()
        var hasMissing = false
        val missingHeaders = mutableListOf<String>()
        conn.columnMapping.forEach { (field, cm) ->
            if (cm.col.isBlank()) return@forEach
            val currentHeader = currentHeaders[cm.col]
            when {
                currentHeader != null && currentHeader.equals(cm.header, ignoreCase = true) ->
                    resolvedMapping[field] = cm.col
                cm.header.isNotBlank() -> {
                    val exactNewCol = currentHeaders.entries
                        .firstOrNull { it.value.equals(cm.header, ignoreCase = true) }?.key
                    if (exactNewCol != null) {
                        resolvedMapping[field] = exactNewCol
                        driftNotes.add("• \"${cm.header}\" ($field): ${cm.col} → $exactNewCol (auto-corrected)")
                    } else {
                        val best = currentHeaders.entries
                            .map { it to similarity(cm.header, it.value) }
                            .maxByOrNull { it.second }
                        val bestSim = best?.second ?: 0f
                        val bestCol = best?.first?.key ?: ""
                        val bestHdr = best?.first?.value ?: ""
                        when {
                            bestSim >= 0.95f -> {
                                resolvedMapping[field] = bestCol
                                driftNotes.add("• \"${cm.header}\" ($field): ${cm.col} → $bestCol (\"$bestHdr\", auto-corrected)")
                            }
                            bestSim >= 0.80f -> {
                                resolvedMapping[field] = cm.col
                                driftNotes.add("• \"${cm.header}\" ($field): ≈ \"$bestHdr\" ($bestCol) — using original ${cm.col}")
                            }
                            else -> {
                                resolvedMapping[field] = cm.col
                                hasMissing = true
                                missingHeaders.add("• \"${cm.header}\" ($field)")
                            }
                        }
                    }
                }
                else -> resolvedMapping[field] = cm.col
            }
        }
        conn.objectColumnMapping.forEach { (field, ocm) ->
            listOf(ocm.keyCol to ocm.keyHeader, ocm.valueCol to ocm.valueHeader).forEach { (col, savedHdr) ->
                if (col.isBlank() || savedHdr.isBlank() || col.startsWith("fixed:")) return@forEach
                val currentHdr = currentHeaders[col]
                if (currentHdr == null || !currentHdr.equals(savedHdr, ignoreCase = true)) {
                    val sim = if (currentHdr != null) similarity(savedHdr, currentHdr) else 0f
                    if (sim < 0.80f) {
                        hasMissing = true
                        missingHeaders.add("• \"$savedHdr\" ($field)")
                    } else {
                        driftNotes.add("• \"$savedHdr\" ($field): ≈ \"${currentHdr ?: ""}\" — kept")
                    }
                }
            }
        }
        conn.effectivePkParts().forEach { part ->
            if (part.type != "col" && part.type != "date") return@forEach
            if (part.header.isBlank()) return@forEach
            val currentHdr = currentHeaders[part.value]
            if (currentHdr == null || !currentHdr.equals(part.header, ignoreCase = true)) {
                val sim = if (currentHdr != null) similarity(part.header, currentHdr) else 0f
                if (sim < 0.80f) {
                    hasMissing = true
                    missingHeaders.add("• \"${part.header}\" (primaryKey[${part.value}])")
                }
            }
        }
        if (hasMissing) {
            return SyncResult(
                false,
                "❌ Column drift — sync stopped, no rows written.\n" +
                    "Header not found (check columns / reposition range):\n" +
                    missingHeaders.take(15).joinToString("\n") +
                    if (missingHeaders.size > 15) "\n…${missingHeaders.size - 15} more" else "",
                0, 0, 0, 0
            )
        }

        fun letterToIndex(letter: String): Int {
            val idx = ConfigSheetParseUtil.parseColInput(letter) ?: return -1
            return idx - conn.colStart
        }
        fun buildPrimaryKey(row: List<String>): String {
            var partFailed = false
            val key = pkParts.joinToString("") { part ->
                when (part.type) {
                    "fixed" -> part.value
                    "col" -> {
                        val idx = letterToIndex(part.value)
                        val v = if (idx < 0) "" else row.getOrElse(idx) { "" }.trim()
                        if (v.isBlank()) partFailed = true
                        v
                    }
                    "date" -> {
                        val idx = letterToIndex(part.value)
                        val raw = if (idx < 0) "" else row.getOrElse(idx) { "" }.trim()
                        val millis = if (raw.isNotBlank()) ConfigSheetParseUtil.parseSheetTimestamp(raw) else null
                        if (millis == null) {
                            partFailed = true
                            ""
                        } else {
                            DhakaTime.dayKey(millis)
                        }
                    }
                    else -> ""
                }
            }
            return if (partFailed) "" else key
        }

        // ── 4. Build row work locally (zero network) ──
        val basePath = conn.targetNode.trimEnd('/')
        val runType = Regex("^courier/run_routes/([^/]+)$").find(basePath)?.groupValues?.get(1)
        val nowMillis = System.currentTimeMillis()
        val dateIssues = mutableListOf<String>()
        val works = ArrayList<RowWork>(dataRows.size)
        var preSkipped = 0
        for (row in dataRows) {
            val conId = buildPrimaryKey(row)
            if (conId.isBlank()) {
                preSkipped++
                continue
            }
            val fieldMap = mutableMapOf<String, Any>()
            resolvedMapping.forEach { (field, colLetter) ->
                if (field == "createdAt" || field == "updatedAt") return@forEach
                val idx = letterToIndex(colLetter)
                if (idx < 0) return@forEach
                val value = row.getOrElse(idx) { "" }.trim()
                if (value.isNotBlank()) fieldMap[field] = value
            }
            val createdRaw = resolvedMapping["createdAt"]?.let { colLetter ->
                val idx = letterToIndex(colLetter)
                if (idx >= 0) row.getOrElse(idx) { "" }.trim() else ""
            } ?: ""
            val updatedRaw = resolvedMapping["updatedAt"]?.let { colLetter ->
                val idx = letterToIndex(colLetter)
                if (idx >= 0) row.getOrElse(idx) { "" }.trim() else ""
            } ?: ""
            var createdAtMillis: Long? = if (createdRaw.isNotBlank()) ConfigSheetParseUtil.parseSheetTimestamp(createdRaw) else null
            var updatedAtMillis: Long? = if (updatedRaw.isNotBlank()) ConfigSheetParseUtil.parseSheetTimestamp(updatedRaw) else null
            if (createdRaw.isNotBlank() && createdAtMillis == null) {
                dateIssues.add("$conId → createdAt: unparseable (\"$createdRaw\")")
            }
            if (updatedRaw.isNotBlank() && updatedAtMillis == null) {
                dateIssues.add("$conId → updatedAt: unparseable (\"$updatedRaw\")")
            }
            if (createdAtMillis != null && createdAtMillis > nowMillis) {
                dateIssues.add("$conId → createdAt: future date, skipped")
                createdAtMillis = null
            }
            if (updatedAtMillis != null && updatedAtMillis > nowMillis) {
                dateIssues.add("$conId → updatedAt: future date, skipped")
                updatedAtMillis = null
            }
            if (createdAtMillis != null && updatedAtMillis != null && createdAtMillis > updatedAtMillis) {
                dateIssues.add("$conId → createdAt is after updatedAt, skipped")
                createdAtMillis = null
            }
            createdAtMillis?.let { fieldMap["createdAt"] = it }
            updatedAtMillis?.let { fieldMap["updatedAt"] = it }

            val phoneRaw = resolvedMapping["recipientPhone"]?.let { colLetter ->
                val idx = letterToIndex(colLetter)
                if (idx >= 0) row.getOrElse(idx) { "" }.trim() else ""
            }
            val normalizedPhone = ConfigSheetParseUtil.normalizePhone(phoneRaw ?: "")
            if (normalizedPhone.isNotBlank()) fieldMap["recipientPhone"] = normalizedPhone

            val userSystemId = fieldMap["agentSystemId"]?.toString()?.trim().orEmpty()
                .ifBlank { fieldMap["agent_system_id"]?.toString()?.trim().orEmpty() }

            fun resolveSpec(spec: String): String {
                return when {
                    spec.startsWith("fixed:") -> spec.removePrefix("fixed:")
                    spec.startsWith("col:") -> {
                        val letter = spec.removePrefix("col:")
                        val idx = letterToIndex(letter)
                        if (idx < 0) "" else row.getOrElse(idx) { "" }.trim()
                    }
                    else -> ""
                }
            }
            val objectWrites = mutableMapOf<String, Any>()
            conn.objectColumnMapping.forEach { (field, ocm) ->
                val keyVal = resolveSpec(ocm.keySpec())
                val valueVal = resolveSpec(ocm.valueSpec())
                if (keyVal.isNotBlank() && valueVal.isNotBlank()) {
                    objectWrites["$field/$keyVal"] = valueVal
                }
            }
            val runCids = if (runType != null) {
                objectWrites.keys.mapNotNull { k ->
                    if (k.substringBefore("/") == "consignments") {
                        k.substringAfter("/").takeIf { it.isNotBlank() }
                    } else null
                }
            } else emptyList()
            works.add(RowWork(conId, fieldMap, objectWrites, normalizedPhone, userSystemId, runCids))
        }

        // ── 5. Parallel pre-fetch (all network reads, max 10 in flight) ──
        updateProgressNotif("Checking Firebase…", 0, dataRows.size, 0, 0, preSkipped)
        val sem = Semaphore(READ_PARALLELISM)
        val agentBranchCache = mutableMapOf<String, List<String>>()
        val branchlessAgentSystemIds = mutableSetOf<String>()
        val branchResolveFailures = mutableListOf<String>()
        if (runType != null) {
            val systemIds = works.map { it.userSystemId }.filter { it.isNotBlank() }.distinct()
            coroutineScope {
            systemIds.map { sys ->
                async {
                    sem.withPermit {
                        val branchIds = try {
                            val uid = db.reference.child("users_by_systemId/$sys/uid").get().await()
                                .getValue(String::class.java)?.trim()
                            if (uid.isNullOrBlank()) {
                                synchronized(branchlessAgentSystemIds) { branchlessAgentSystemIds.add(sys) }
                                emptyList()
                            } else {
                                val ids = db.reference.child("users/$uid/profile/company_info/branch_ids")
                                    .get().await().children.mapNotNull { it.getValue(String::class.java) }
                                if (ids.isEmpty()) synchronized(branchlessAgentSystemIds) { branchlessAgentSystemIds.add(sys) }
                                ids
                            }
                        } catch (e: Exception) {
                            synchronized(branchResolveFailures) {
                                branchResolveFailures.add("$sys → ${e.message?.take(80) ?: e.javaClass.simpleName}")
                            }
                            emptyList()
                        }
                        synchronized(agentBranchCache) { agentBranchCache[sys] = branchIds }
                    }
                }
            }.awaitAll()
            }
        }

        val existSnaps = mutableMapOf<String, DataSnapshot?>()
        val readFailedIds = mutableSetOf<String>()
        val writeFailures = mutableListOf<String>()
        coroutineScope {
        works.map { w ->
            async {
                sem.withPermit {
                    try {
                        val snap = db.reference.child("$basePath/${w.conId}").get().await()
                        synchronized(existSnaps) { existSnaps[w.conId] = snap }
                    } catch (e: Exception) {
                        synchronized(readFailedIds) { readFailedIds.add(w.conId) }
                        synchronized(writeFailures) {
                            writeFailures.add("${w.conId} → read check failed: ${e.message?.take(80) ?: e.javaClass.simpleName}")
                        }
                        synchronized(existSnaps) { existSnaps[w.conId] = null }
                    }
                }
            }
        }.awaitAll()
        }

        // Consignment phone-status guards (only for courier/consignments with a phone)
        // true = safe to write status; false = keep existing run pointer / read failed.
        val phoneStatusOk = mutableMapOf<Pair<String, String>, Boolean>()
        if (basePath == "courier/consignments") {
            coroutineScope {
            works.filter { it.phone.isNotBlank() }.map { w ->
                async {
                    sem.withPermit {
                        val ok = try {
                            val snap = db.reference
                                .child("courier/consignments_by_phone/${w.phone}/${w.conId}").get().await()
                            if (!snap.exists()) true
                            else {
                                val v = snap.getValue(String::class.java)?.trim().orEmpty()
                                !(v.contains("/run_") || v.startsWith("run_"))
                            }
                        } catch (_: Exception) {
                            false
                        }
                        synchronized(phoneStatusOk) { phoneStatusOk[w.phone to w.conId] = ok }
                    }
                }
            }.awaitAll()
            }
        }

        // Run-consignment helpers (only for run routes)
        val consignmentPhoneCache = mutableMapOf<String, String>()
        val dupeIndexCache = mutableMapOf<String, DataSnapshot?>()
        if (runType != null) {
            val allCids = works.flatMap { it.runCids }.distinct()
            coroutineScope {
            allCids.map { cid ->
                async {
                    sem.withPermit {
                        try {
                            val raw = db.reference.child("courier/consignments/$cid/recipientPhone").get().await()
                                .getValue(String::class.java)?.trim().orEmpty()
                            val phone = ConfigSheetParseUtil.normalizePhone(raw)
                            synchronized(consignmentPhoneCache) { consignmentPhoneCache[cid] = phone }
                        } catch (_: Exception) {
                            synchronized(consignmentPhoneCache) { consignmentPhoneCache[cid] = "" }
                        }
                    }
                }
            }.awaitAll()
            }
            coroutineScope {
            allCids.map { cid ->
                async {
                    sem.withPermit {
                        val snap = try {
                            db.reference.child("courier/runs_by_consignmentId/$cid").get().await()
                        } catch (_: Exception) {
                            null
                        }
                        synchronized(dupeIndexCache) { dupeIndexCache[cid] = snap }
                    }
                }
            }.awaitAll()
            }
        }

        // ── 6. Local diff + batched writes ──
        var inserted = 0
        var updated = 0
        var skipped = preSkipped
        val duplicateRuns = mutableMapOf<String, MutableList<Pair<String, String>>>()
        val syncInsertedRuns = mutableMapOf<String, MutableList<Triple<String, String, String>>>()
        val runIndexUpdates = mutableMapOf<String, Any>()
        var runIndexCount = 0

        val pendingWrites = mutableMapOf<String, Any>()
        val pendingRows = mutableListOf<String>()
        suspend fun flushWrites() {
            if (pendingWrites.isEmpty()) {
                pendingRows.clear()
                return
            }
            val chunk = HashMap(pendingWrites)
            val rows = ArrayList(pendingRows)
            pendingWrites.clear()
            pendingRows.clear()
            try {
                db.reference.updateChildren(chunk).await()
            } catch (e: Exception) {
                val msg = e.message?.take(80) ?: e.javaClass.simpleName
                synchronized(writeFailures) {
                    rows.take(5).forEach { id -> writeFailures.add("$id → $msg") }
                    if (rows.size > 5) writeFailures.add("…and ${rows.size - 5} more rows in the same batch → $msg")
                }
            }
        }

        fun parseRunDateAgent(runId: String): Pair<String, String>? {
            val parts = runId.split("_")
            if (parts.size < 3 || parts[0] != "run") return null
            val date = parts[1].trim()
            if (date.length != 8 || !date.all { it.isDigit() }) return null
            val agent = parts.drop(2).joinToString("_").trim()
            if (agent.isBlank()) return null
            return date to agent
        }
        fun recordDupe(cid: String, otherAgent: String, otherRunId: String) {
            val list = duplicateRuns.getOrPut(cid) { mutableListOf() }
            if (list.none { it.first.equals(otherAgent, ignoreCase = true) && it.second == otherRunId }) {
                list.add(otherAgent to otherRunId)
            }
        }
        fun collectDuplicates(runTypeName: String, runId: String, agentSys: String, cids: List<String>) {
            val my = parseRunDateAgent(runId) ?: return
            val effAgent = agentSys.ifBlank { my.second }
            if (effAgent.isBlank()) return
            cids.forEach { cid ->
                syncInsertedRuns[cid]?.forEach { (rt, rid, ag) ->
                    if (rt == runTypeName && rid == runId) return@forEach
                    val o = parseRunDateAgent(rid) ?: return@forEach
                    if (o.first == my.first && !o.second.equals(effAgent, ignoreCase = true)) {
                        recordDupe(cid, o.second, rid)
                    }
                }
                val snap = dupeIndexCache[cid] ?: return@forEach
                snap.children.forEach { rtNode ->
                    val rt = rtNode.key ?: return@forEach
                    rtNode.children.forEach { ridNode ->
                        val rid = ridNode.key ?: return@forEach
                        if (rt == runTypeName && rid == runId) return@forEach
                        val o = parseRunDateAgent(rid) ?: return@forEach
                        if (o.first == my.first && !o.second.equals(effAgent, ignoreCase = true)) {
                            recordDupe(cid, o.second, rid)
                        }
                    }
                }
                syncInsertedRuns.getOrPut(cid) { mutableListOf() }.add(Triple(runTypeName, runId, effAgent))
            }
        }
        fun queueRunIndex(runTypeName: String, runId: String, status: String, cids: List<String>) {
            cids.forEach { cid ->
                runIndexUpdates["courier/runs_by_consignmentId/$cid/$runTypeName/$runId"] = status
                runIndexCount++
                val phone = consignmentPhoneCache[cid].orEmpty()
                if (phone.isNotBlank()) {
                    runIndexUpdates["courier/consignments_by_phone/$phone/$cid"] = "$runTypeName/$runId"
                }
            }
        }

        var processed = 0
        for (w in works) {
            val conId = w.conId
            if (conId in readFailedIds) {
                skipped++
                processed++
                continue
            }
            val existSnap = existSnaps[conId]
            val multiUpdate = mutableMapOf<String, Any>()

            if (existSnap == null || !existSnap.exists()) {
                // INSERT
                w.fieldMap.forEach { (k, v) -> multiUpdate["$basePath/$conId/$k"] = v }
                w.objectWrites.forEach { (k, v) -> multiUpdate["$basePath/$conId/$k"] = v }
                if (basePath == "courier/consignments" && w.phone.isNotBlank()) {
                    val status = w.fieldMap["status"]?.toString() ?: ""
                    if (phoneStatusOk[w.phone to conId] == true) {
                        multiUpdate["courier/consignments_by_phone/${w.phone}/$conId"] = status
                    }
                }
                if (runType != null && w.userSystemId.isNotBlank()) {
                    val status = w.fieldMap["status"]?.toString() ?: ""
                    multiUpdate["courier/runs_by_agentSystemId/${w.userSystemId}/$runType/$conId"] = status
                    if (w.runCids.isNotEmpty() && status.isNotBlank()) {
                        queueRunIndex(runType, conId, status, w.runCids)
                        collectDuplicates(runType, conId, w.userSystemId, w.runCids)
                    }
                    val agentBranchIds = agentBranchCache[w.userSystemId].orEmpty()
                    if (agentBranchIds.isNotEmpty()) {
                        multiUpdate["$basePath/$conId/resolvedBranchIds"] = agentBranchIds
                        agentBranchIds.forEach { branchIdOf ->
                            multiUpdate["courier/runs_by_branchId/$branchIdOf/$runType/$conId"] = status
                        }
                    }
                }
                inserted++
            } else {
                // COMPARE & UPDATE changed fields only
                val changedFields = mutableMapOf<String, Any>()
                w.fieldMap.forEach { (k, v) ->
                    val firebaseVal = existSnap.child(k).value
                    val same = when {
                        v is Long && firebaseVal is Number -> firebaseVal.toLong() == v
                        else -> (firebaseVal?.toString() ?: "") == v.toString()
                    }
                    if (!same) changedFields[k] = v
                }
                w.objectWrites.forEach { (path, v) ->
                    val firebaseVal = existSnap.child(path).value
                    if ((firebaseVal?.toString() ?: "") != v.toString()) changedFields[path] = v
                }

                var branchIdsForIndex: List<String> = emptyList()
                var branchBackfilled = false
                if (runType != null && w.userSystemId.isNotBlank()) {
                    branchIdsForIndex = existSnap.child("resolvedBranchIds")
                        .children.mapNotNull { it.getValue(String::class.java) }
                    if (branchIdsForIndex.isEmpty()) {
                        val resolvedNow = agentBranchCache[w.userSystemId].orEmpty()
                        if (resolvedNow.isNotEmpty()) {
                            multiUpdate["$basePath/$conId/resolvedBranchIds"] = resolvedNow
                            branchIdsForIndex = resolvedNow
                            branchBackfilled = true
                        }
                    }
                }

                if (changedFields.isNotEmpty()) {
                    changedFields.forEach { (k, v) -> multiUpdate["$basePath/$conId/$k"] = v }
                    if (basePath == "courier/consignments" && "status" in changedFields && w.phone.isNotBlank()) {
                        if (phoneStatusOk[w.phone to conId] == true) {
                            multiUpdate["courier/consignments_by_phone/${w.phone}/$conId"] =
                                changedFields["status"].toString()
                        }
                    }
                    if (runType != null && "status" in changedFields && w.userSystemId.isNotBlank()) {
                        multiUpdate["courier/runs_by_agentSystemId/${w.userSystemId}/$runType/$conId"] =
                            changedFields["status"].toString()
                    }
                }

                if (runType != null && branchIdsForIndex.isNotEmpty() &&
                    ("status" in changedFields || branchBackfilled)
                ) {
                    val statusForIndex = (changedFields["status"] as? String)
                        ?: w.fieldMap["status"]?.toString()
                        ?: existSnap.child("status").getValue(String::class.java) ?: ""
                    if (statusForIndex.isNotBlank()) {
                        branchIdsForIndex.forEach { branchIdOf ->
                            multiUpdate["courier/runs_by_branchId/$branchIdOf/$runType/$conId"] = statusForIndex
                        }
                    }
                }

                if (runType != null && w.userSystemId.isNotBlank() &&
                    ("status" in changedFields || branchBackfilled)
                ) {
                    val updStatus = (changedFields["status"] as? String)
                        ?: w.fieldMap["status"]?.toString()
                        ?: existSnap.child("status").getValue(String::class.java) ?: ""
                    if (updStatus.isNotBlank()) {
                        var updCids = w.objectWrites.keys.mapNotNull { k ->
                            if (k.substringBefore("/") == "consignments") {
                                k.substringAfter("/").takeIf { it.isNotBlank() }
                            } else null
                        }
                        if (updCids.isEmpty() && branchBackfilled) {
                            updCids = try {
                                db.reference.child("$basePath/$conId/consignments").get().await()
                                    .children.mapNotNull { it.key?.takeIf { id -> id.isNotBlank() } }
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                        if (updCids.isNotEmpty()) {
                            queueRunIndex(runType, conId, updStatus, updCids)
                            collectDuplicates(runType, conId, w.userSystemId, updCids)
                        }
                    }
                }

                if (changedFields.isNotEmpty() || branchBackfilled) updated++ else skipped++
            }

            if (multiUpdate.isNotEmpty()) {
                multiUpdate.forEach { (k, v) -> pendingWrites[k] = v }
                pendingRows.add(conId)
                if (pendingRows.size >= FLUSH_ROW_EVERY || pendingWrites.size >= FLUSH_PATH_EVERY) {
                    flushWrites()
                }
            }
            processed++
            // Old-style running counter: report EVERY row like before the
            // bulk refactor (notification itself throttles to 1.5s in
            // updateProgressNotif; the UI mirror is cheap text).
            updateProgressNotif(
                "Syncing Firebase…", processed, works.size, inserted, updated, skipped
            )
            postProgress(processed, works.size, inserted, updated, skipped)
        }
        flushWrites()

        // ── Run-consignment index flush (isolated batch, same as before) ──
        if (runIndexUpdates.isNotEmpty()) {
            updateProgressNotif("Writing run index…", works.size, works.size, inserted, updated, skipped)
            try {
                db.reference.updateChildren(runIndexUpdates).await()
            } catch (e: Exception) {
                writeFailures.add("run-index batch → ${e.message?.take(80) ?: e.javaClass.simpleName}")
            }
        }

        // ── Summary ──
        val issuesText = if (dateIssues.isNotEmpty()) {
            val shown = dateIssues.take(10).joinToString("\n") { "• $it" }
            val more = if (dateIssues.size > 10) "\n…${dateIssues.size - 10} more" else ""
            "\n\n⚠ Date issues (${dateIssues.size}):\n$shown$more"
        } else ""
        val failuresText = if (writeFailures.isNotEmpty()) {
            val shown = writeFailures.take(10).joinToString("\n") { "• $it" }
            val more = if (writeFailures.size > 10) "\n…${writeFailures.size - 10} more" else ""
            "\n\n❌ Firebase write failed (${writeFailures.size}):\n$shown$more\n\n" +
                "Usually this path has no write permission in Firebase Security Rules."
        } else ""
        val branchlessText = when {
            branchResolveFailures.isNotEmpty() -> {
                val shown = branchResolveFailures.take(10).joinToString("\n") { "• $it" }
                val more = if (branchResolveFailures.size > 10) "\n…${branchResolveFailures.size - 10} more" else ""
                "\n\n⚠ Failed to resolve runs_by_branchId (${branchResolveFailures.size} agents):\n$shown$more"
            }
            branchlessAgentSystemIds.isNotEmpty() -> {
                val shown = branchlessAgentSystemIds.take(10).joinToString(", ")
                val more = if (branchlessAgentSystemIds.size > 10) " …${branchlessAgentSystemIds.size - 10} more" else ""
                "\n\n⚠ runs_by_branchId was not created because these agents' uids were not found or have no branch_ids assigned " +
                    "(${branchlessAgentSystemIds.size} systemIds): $shown$more\n" +
                    "Assign them branches from Employee edit."
            }
            else -> ""
        }
        val duplicatesText = if (duplicateRuns.isNotEmpty()) {
            val agentIds = duplicateRuns.values.flatten().map { it.first }.distinct()
            val nameMap = mutableMapOf<String, String>()
            agentIds.forEach { sys ->
                val n = runCatching { UserNameResolver.resolveNameBySystemId(sys) }.getOrNull()?.trim().orEmpty()
                nameMap[sys] = n.ifBlank { sys }
            }
            val shown = duplicateRuns.entries.take(10).joinToString("\n") { (cid, others) ->
                "• $cid → " + others.distinct().joinToString(", ") { (sys, rid) -> "${nameMap[sys] ?: sys} ($rid)" }
            }
            val more = if (duplicateRuns.size > 10) "\n…${duplicateRuns.size - 10} more" else ""
            "\n\n🔁 Duplicate parcels (${duplicateRuns.size}) — also in another agent's run on the same day:\n$shown$more"
        } else ""
        val driftText = if (driftNotes.isNotEmpty()) {
            "\n\n📍 Columns auto-corrected (${driftNotes.size}):\n" +
                driftNotes.take(10).joinToString("\n") +
                if (driftNotes.size > 10) "\n…${driftNotes.size - 10} more" else ""
        } else ""
        val summary = "Inserted : $inserted\n" +
            "Updated  : $updated\n" +
            "Skipped  : $skipped\n" +
            "Total    : ${dataRows.size}" +
            (if (runIndexCount > 0) "\nRun index: $runIndexCount parcel entries" else "") +
            driftText + issuesText + failuresText + branchlessText + duplicatesText
        val ok = writeFailures.isEmpty()
        return SyncResult(ok, summary, inserted, updated, skipped, runIndexCount)
    }

    private suspend fun loadConn(db: FirebaseDatabase, branchId: String, connectionId: String): SheetConn? {
        return try {
            val snap = db.reference.child("config/sheets/$branchId/connections/$connectionId").get().await()
            if (!snap.exists()) return null
            val sheetId = snap.child("sheetId").getValue(String::class.java) ?: return null
            val sheetName = snap.child("sheetName").getValue(String::class.java) ?: ""
            val tabName = snap.child("tabName").getValue(String::class.java) ?: ""
            val nickname = snap.child("nickname").getValue(String::class.java) ?: ""
            val colS = snap.child("colStart").getValue(Int::class.java) ?: 1
            val colE = snap.child("colEnd").getValue(Int::class.java) ?: 10
            val email = snap.child("googleEmail").getValue(String::class.java) ?: ""
            val by = snap.child("connectedBy").getValue(String::class.java) ?: ""
            val at = snap.child("connectedAt").getValue(Long::class.java) ?: 0L
            val sRow = snap.child("startRow").getValue(Int::class.java)
            val eRow = snap.child("endRow").getValue(Int::class.java)
            val autoSync = snap.child("autoSync").getValue(Boolean::class.java) ?: false
            val interval = snap.child("syncIntervalMin").getValue(Int::class.java) ?: 30
            @Suppress("UNCHECKED_CAST")
            val colMap: Map<String, ColMapping> = snap.child("columnMapping").children.associate { fieldSnap ->
                val k = fieldSnap.key ?: ""
                val v = fieldSnap.value
                val cm = when (v) {
                    is Map<*, *> -> ColMapping(
                        col = v["col"]?.toString() ?: "",
                        header = v["header"]?.toString() ?: ""
                    )
                    is String -> ColMapping(col = v, header = "")
                    else -> ColMapping()
                }
                k to cm
            }
            val objMap = snap.child("objectColumnMapping").children.associate { fieldSnap ->
                fieldSnap.key.orEmpty() to ObjectColMapping(
                    keyCol = fieldSnap.child("keyCol").getValue(String::class.java)
                        ?: fieldSnap.child("key").getValue(String::class.java) ?: "",
                    keyHeader = fieldSnap.child("keyHeader").getValue(String::class.java) ?: "",
                    valueCol = fieldSnap.child("valueCol").getValue(String::class.java)
                        ?: fieldSnap.child("value").getValue(String::class.java) ?: "",
                    valueHeader = fieldSnap.child("valueHeader").getValue(String::class.java) ?: "",
                )
            }.filterKeys { it.isNotBlank() }
            val tgtNode = snap.child("targetNode").getValue(String::class.java) ?: "courier/consignments"
            val pkField = snap.child("primaryKeyField").getValue(String::class.java) ?: ""
            val pkParts = snap.child("primaryKeyParts").children.mapNotNull { partSnap ->
                val t = partSnap.child("type").getValue(String::class.java) ?: return@mapNotNull null
                val v = partSnap.child("value").getValue(String::class.java) ?: ""
                val h = partSnap.child("header").getValue(String::class.java) ?: ""
                PkPart(t, v, h)
            }
            SheetConn(
                connectionId, nickname, branchId, sheetId, sheetName, tabName, colS, colE,
                sRow, eRow, autoSync, interval, email, by, at, colMap, objMap, pkField, tgtNode, pkParts
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Levenshtein similarity 0..1 (same thresholds as the tab's drift check). */
    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val la = a.lowercase()
        val lb = b.lowercase()
        val dp = Array(la.length + 1) { IntArray(lb.length + 1) }
        for (i in 0..la.length) dp[i][0] = i
        for (j in 0..lb.length) dp[0][j] = j
        for (i in 1..la.length) for (j in 1..lb.length) {
            dp[i][j] = if (la[i - 1] == lb[j - 1]) dp[i - 1][j - 1]
            else minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]) + 1
        }
        return 1f - dp[la.length][lb.length].toFloat() / maxOf(la.length, lb.length)
    }

    // ── Notifications + mirrors ───────────────────────────────────────

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS, "Sheet sync progress", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Ongoing sheet-sync progress — required while sync runs"
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SUMMARY, "Sheet sync summary", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Final sheet-sync total summary" }
        )
    }

    private fun openAppIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this, 78, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun progressNotification(
        text: String, done: Int, total: Int,
    ): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle("Syncing sheet → Firebase")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .setProgress(if (total > 0) total else 0, done, total <= 0)
            .build()
    }

    private fun updateProgressNotif(
        text: String, done: Int, total: Int, inserted: Int, updated: Int, skipped: Int,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyMs < 1500 && done != total) return
        lastNotifyMs = now
        try {
            val full = if (total > 0) "$text\n✅ $inserted · 🔄 $updated · ⏭ $skipped ($done/$total)" else text
            NotificationManagerCompat.from(this)
                .notify(NOTIF_PROGRESS_ID, progressNotification(full, done, total))
        } catch (_: Exception) {
        }
    }

    private fun postProgress(done: Int, total: Int, inserted: Int, updated: Int, skipped: Int) {
        try {
            val cb = onProgress ?: return
            mainHandler.post { try { cb(done, total, inserted, updated, skipped) } catch (_: Exception) { } }
        } catch (_: Exception) {
        }
    }

    private fun finishWithResult(result: SyncResult) {
        try {
            val flash = NotificationCompat.Builder(this, CHANNEL_SUMMARY)
                .setContentTitle(if (result.ok) "✓ Sheet sync done" else "⚠ Sheet sync finished")
                .setContentText(
                    "Inserted ${result.inserted} · Updated ${result.updated} · " +
                        "Skipped ${result.skipped}" +
                        if (result.runIndexCount > 0) " · Index ${result.runIndexCount}" else ""
                )
                .setStyle(NotificationCompat.BigTextStyle().bigText(result.summary))
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION") stopForeground(true)
                }
            } catch (_: Exception) {
                try {
                    @Suppress("DEPRECATION") stopForeground(true)
                } catch (_: Exception) {
                }
            }
            NotificationManagerCompat.from(this).notify(NOTIF_SUMMARY_ID, flash)
            try {
                val cb = onFinish
                onFinish = null
                onProgress = null
                cb?.let { mainHandler.post { try { it(result.ok, result.summary) } catch (_: Exception) { } } }
            } catch (_: Exception) {
            }
        } finally {
            isRunning = false
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }
}
