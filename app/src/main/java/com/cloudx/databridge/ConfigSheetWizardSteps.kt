package com.cloudx.databridge

import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TableLayout
import android.widget.TextView
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * ConfigSheetFragment's ConnectFlow wizard steps — the render logic for Step 4 (column
 * range, live preview from the actual sheet, and the manage-panel's column preview),
 * plus the "sync sheet → Firebase" background job used both by the initial connect and
 * by periodic sync.
 *
 * Extracted from ConfigSheetFragment.kt as part of breaking that ~4500-line file into
 * modules. Written as extension functions on ConfigSheetFragment for the same reason as
 * the other Config-Sheet module splits — this section's state (connectStep,
 * sheetHeaders, availableSheets, colWatcher, etc.) is read/written from other wizard
 * sections too.
 */
// ── ConnectFlow steps ─────────────────────────────────────────────
internal fun ConfigSheetFragment.renderConnectStep() {
    // Step views
    stepView1?.visibility = if (connectStep == 1) View.VISIBLE else View.GONE
    stepView2?.visibility = if (connectStep == 2) View.VISIBLE else View.GONE
    stepView3?.visibility = if (connectStep == 3) View.VISIBLE else View.GONE
    stepView4?.visibility = if (connectStep == 4) View.VISIBLE else View.GONE
    stepView5?.visibility = if (connectStep == 5) View.VISIBLE else View.GONE

    // Step dots — done=green circle + tick, active=white circle + step number + border, future=grey circle + step number
    val density = resources.displayMetrics.density
    fun roundBg(fillColor: Int, strokeColor: Int? = null, strokeDp: Int = 2) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(fillColor)
            strokeColor?.let { setStroke((strokeDp * density).toInt(), it) }
        }
    fun styleDot(dot: TextView?, n: Int) {
        when {
            connectStep > n -> {
                // Done — green fill, white tick
                dot?.background = roundBg(android.graphics.Color.parseColor("#16A34A"))
                dot?.text = "✓"
                dot?.setTextColor(android.graphics.Color.WHITE)
            }
            connectStep == n -> {
                // Active — white fill, green border, dark number
                dot?.background = roundBg(
                    android.graphics.Color.WHITE,
                    android.graphics.Color.parseColor("#16A34A"), 2
                )
                dot?.text = "$n"
                dot?.setTextColor(android.graphics.Color.parseColor("#16A34A"))
            }
            else -> {
                // Future — light grey fill, grey number
                dot?.background = roundBg(context!!.getColor(R.color.theme_border))
                dot?.text = "$n"
                dot?.setTextColor(context!!.getColor(R.color.theme_text_muted))
            }
        }
    }
    styleDot(step1Dot, 1); styleDot(step2Dot, 2); styleDot(step3Dot, 3); styleDot(step4Dot, 4); styleDot(step5Dot, 5)

    // Step lines
    val lineColor = android.graphics.Color.parseColor("#16A34A")
    val lineGrey  = context!!.getColor(R.color.theme_border)
    step1Line?.setBackgroundColor(if (connectStep > 1) lineColor else lineGrey)
    step2Line?.setBackgroundColor(if (connectStep > 2) lineColor else lineGrey)
    step3Line?.setBackgroundColor(if (connectStep > 3) lineColor else lineGrey)
    step4Line?.setBackgroundColor(if (connectStep > 4) lineColor else lineGrey)

    // Step labels
    val green = android.graphics.Color.parseColor("#16A34A")
    val dark  = context!!.getColor(R.color.theme_text_primary)
    val grey  = context!!.getColor(R.color.theme_text_muted)
    fun styleLbl(lbl: TextView?, n: Int) {
        lbl?.setTextColor(when { connectStep > n -> green; connectStep == n -> dark; else -> grey })
    }
    styleLbl(step1Lbl, 1); styleLbl(step2Lbl, 2); styleLbl(step3Lbl, 3); styleLbl(step4Lbl, 4); styleLbl(step5Lbl, 5)

    // Nav buttons
    // Range edit mode: no back (can't go to step 3), only Cancel + Save
    btnBack?.visibility    = if (!isRangeEdit && connectStep > 1) View.VISIBLE else View.GONE
    // Step 1: Next only visible when account is selected
    btnNext?.visibility    = when {
        isRangeEdit      -> View.GONE
        connectStep == 1 -> if (googleAccount != null) View.VISIBLE else View.GONE
        connectStep < 5  -> View.VISIBLE
        else             -> View.GONE
    }
    btnConnect?.visibility = if (connectStep == 5) View.VISIBLE else View.GONE
    // Text + enabled state (Connect / Save / Exit Wizard) is decided centrally so it stays in
    // sync with live dirty-detection.
    if (connectStep == 5) updateConnectButtonState()

    // Cancel button label changes in range edit mode
    (btnCancelConn as? TextView)?.text = if (isRangeEdit) "Cancel" else "✕"

    tvConnError?.visibility = View.GONE

    // Per-step UI
    when (connectStep) {
        1 -> updateAccountStep()
        2 -> updateSheetPickerLabel()
        3 -> updateTabSpinner()
        4 -> { updateColPreview(); updateSummary(); scheduleLivePreview() }
        5 -> {
            fetchCourierChildNodes()
            // On reconnect, sheetHeaders may be empty (not fetched yet) even though
            // pendingMapping is already populated. Fetch the header row first so saved
            // column letters can be pre-selected in the mapping spinners.
            if (sheetHeaders.isEmpty()
                && (pendingMapping.isNotEmpty() || pendingObjectMapping.isNotEmpty())
                && googleAccount != null && selectedSheet != null && selectedTab.isNotBlank()) {
                val account = googleAccount!!
                val sheet   = selectedSheet!!
                val tab     = selectedTab
                val conn    = activeConn()
                val s       = conn?.colStart ?: etColStart?.text?.toString()?.trim()?.toIntOrNull() ?: 1
                val e       = conn?.colEnd   ?: etColEnd?.text?.toString()?.trim()?.toIntOrNull() ?: 10
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val token = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try { com.google.android.gms.auth.GoogleAuthUtil.getToken(requireContext(), account.account!!, ConfigSheetDriveApi.OAUTH_SCOPE) }
                            catch (_: Exception) { null }
                        }
                        if (token != null) {
                            val startLetter = colIndexToLetter(s)
                            val endLetter   = colIndexToLetter(e)
                            val sRow = etStartRow?.text?.toString()?.trim()?.toIntOrNull() ?: 1
                            val range = "$tab!${startLetter}${sRow}:${endLetter}${sRow}"
                            val encoded = java.net.URLEncoder.encode(range, "UTF-8")
                            val url = "https://sheets.googleapis.com/v4/spreadsheets/${sheet.id}/values/$encoded"
                            val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val req = okhttp3.Request.Builder().url(url)
                                    .header("Authorization", "Bearer $token").build()
                                httpClient.newCall(req).execute().use { resp ->
                                    if (!resp.isSuccessful) return@withContext null
                                    val arr = org.json.JSONObject(resp.body?.string() ?: "").optJSONArray("values")
                                        ?: return@withContext null
                                    if (arr.length() == 0) return@withContext null
                                    val row = arr.getJSONArray(0)
                                    (0 until row.length()).map { j -> row.optString(j, "") }
                                }
                            }
                            val newHeaders = mutableMapOf<String, String>()
                            rows?.forEachIndexed { idx, header ->
                                val letter = colIndexToLetter(s + idx)
                                if (header.isNotBlank()) newHeaders[letter] = header
                            }
                            if (newHeaders.isNotEmpty()) sheetHeaders = newHeaders
                        }
                    } catch (_: Exception) { }
                    if (isAdded) renderMappingStep()
                }
            } else {
                renderMappingStep()
            }
        }
    }
}

/** Parse "A", "a", "1", "AA" etc → 1-based column index */
// parseColInput / colIndexToLetter → ConfigSheetParseUtil
internal fun ConfigSheetFragment.parseColInput(raw: String)  = ConfigSheetParseUtil.parseColInput(raw)
internal fun ConfigSheetFragment.colIndexToLetter(n: Int)    = ConfigSheetParseUtil.colIndexToLetter(n)

internal fun ConfigSheetFragment.updateColPreview() {
    val s = parseColInput(etColStart?.text?.toString() ?: "") ?: run {
        tvColPreview?.text = "⚠ Enter a start column (A or 1)"
        return
    }
    val e = parseColInput(etColEnd?.text?.toString() ?: "") ?: run {
        tvColPreview?.text = "⚠ Enter an end column (J or 10)"
        return
    }
    if (s < 1 || e < s) {
        tvColPreview?.text = "⚠ Invalid range (start ≤ end)"
        return
    }
    val startLetter = colIndexToLetter(s)
    val endLetter   = colIndexToLetter(e)
    val count = e - s + 1
    tvColPreview?.text = "Columns: $startLetter ($s) – $endLetter ($e)  ·  $count total"
}

internal fun ConfigSheetFragment.updateSummary() {
    val sheetName = selectedSheet?.name ?: ""
    val sheetId   = selectedSheet?.id ?: ""
    val tab       = selectedTab
    val email     = googleAccount?.email ?: ""
    val s = parseColInput(etColStart?.text?.toString() ?: "") ?: 1
    val e = parseColInput(etColEnd?.text?.toString() ?: "") ?: 10
    val startLetter = colIndexToLetter(s.coerceAtLeast(1))
    val endLetter   = colIndexToLetter(e.coerceAtLeast(s))
    tvSummary?.text = "✅ Summary\n\nAccount: $email\nSheet: $sheetName\nSheet ID: ${if (sheetId.length > 24) sheetId.take(24) + "…" else sheetId}\nTab: $tab\nColumns: $startLetter–$endLetter (${(e - s + 1).coerceAtLeast(1)} cols)\nBranch: ${branchLabel(activeBranch)}"
}

internal fun ConfigSheetFragment.scheduleLivePreview() {
    previewJob?.cancel()
    val account = googleAccount ?: run {
        tvLivePreview?.text = "Sign in with Google for live preview. The range can still be saved."
        scrollLivePreview?.visibility = View.GONE
        tableLivePreview?.removeAllViews()
        return
    }
    val sheet   = selectedSheet ?: return
    val tab     = selectedTab.takeIf { it.isNotBlank() } ?: return
    val s = parseColInput(etColStart?.text?.toString() ?: "") ?: return
    val e = parseColInput(etColEnd?.text?.toString() ?: "") ?: return
    if (s < 1 || e < s) return

    previewJob = viewLifecycleOwner.lifecycleScope.launch {
        kotlinx.coroutines.delay(600)
        fetchAndShowLivePreview(googleAccount ?: return@launch, sheet.id, tab, s, e)
    }
}

internal suspend fun ConfigSheetFragment.fetchAndShowLivePreview(
    account: GoogleSignInAccount,
    sheetId: String,
    tab: String,
    colStart: Int,
    colEnd: Int
) {
    val acctObj = account.account ?: return
    try {
        pbPreviewLoad?.visibility = View.VISIBLE
        tvLivePreview?.text = "Fetching preview..."
        scrollLivePreview?.visibility = View.GONE
        tableLivePreview?.removeAllViews()
        val ctx = context ?: return
        val token = withContext(Dispatchers.IO) {
            try { GoogleAuthUtil.getToken(ctx, acctObj, ConfigSheetDriveApi.OAUTH_SCOPE) }
            catch (e: UserRecoverableAuthException) { null }
        } ?: run {
            tvLivePreview?.text = "⚠ Token unavailable"
            scrollLivePreview?.visibility = View.GONE
            return
        }

        val startLetter = colIndexToLetter(colStart)
        val endLetter   = colIndexToLetter(colEnd)

        // Row range: use defined values or defaults
        val sRow = etStartRow?.text?.toString()?.trim()?.toIntOrNull() ?: 1
        val eRow = etEndRow?.text?.toString()?.trim()?.toIntOrNull()
        // Max 5 data rows after header, but stop at endRow if defined
        val maxEnd      = sRow + 5
        val previewEndRow = if (eRow != null) minOf(eRow, maxEnd) else maxEnd
        val range = "$tab!${startLetter}${sRow}:${endLetter}${previewEndRow}"
        val previewLabel = when {
            eRow == null              -> "Preview: Row $sRow + next 5 rows"
            eRow <= sRow              -> "Preview: Row $sRow (end row ≤ start row)"
            previewEndRow < maxEnd    -> "Preview: Row $sRow → $eRow (${previewEndRow - sRow} rows)"
            else                     -> "Preview: Row $sRow + next 5 rows (end: $eRow)"
        }
        val encodedRange = java.net.URLEncoder.encode(range, "UTF-8")
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$sheetId/values/$encodedRange"

        val rows = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $token").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val obj = org.json.JSONObject(body)
                val arr = obj.optJSONArray("values") ?: return@withContext emptyList<List<String>>()
                (0 until arr.length()).map { i ->
                    val row = arr.getJSONArray(i)
                    (0 until row.length()).map { j -> row.optString(j, "") }
                }
            }
        }

        if (rows == null) {
            tvLivePreview?.text = "⚠ Sheet fetch failed"
            scrollLivePreview?.visibility = View.GONE
            return
        }
        if (rows.isEmpty()) {
            tvLivePreview?.text = "⚠ No data in this range"
            renderLivePreviewTable(emptyList(), colStart, colEnd)
            return
        }

        tvLivePreview?.text = previewLabel
        // Capture header row for step 5 auto-mapping
        val headerRow = rows.firstOrNull()
        if (headerRow != null) {
            val newHeaders = mutableMapOf<String, String>()
            headerRow.forEachIndexed { idx, text ->
                val letter = colIndexToLetter(colStart + idx)
                if (text.isNotBlank()) newHeaders[letter] = text
            }
            sheetHeaders = newHeaders

            // Default Col End to the last non-blank header cell (e.g. header data only in
            // col 1-3 → default end = 3), instead of whatever wide range was requested.
            // Only when the user hasn't manually set Col End themselves.
            if (!colEndUserModified) {
                val lastNonBlankOffset = headerRow.indexOfLast { it.isNotBlank() }
                if (lastNonBlankOffset >= 0) {
                    val autoEnd = colStart + lastNonBlankOffset
                    val currentEnd = parseColInput(etColEnd?.text?.toString() ?: "") ?: colEnd
                    if (autoEnd in colStart..colEnd && autoEnd != currentEnd) {
                        isAutoAdjustingColEnd = true
                        etColEnd?.setText(autoEnd.toString())
                        isAutoAdjustingColEnd = false
                        updateColPreview()
                        updateSummary()
                        scheduleLivePreview()
                        return
                    }
                }
            }
        }
        // Capture first actual data row (row after header) so Step 5 can preview
        // primary key / field mapping with real values instead of placeholders.
        val firstDataRow = rows.getOrNull(1)
        if (firstDataRow != null) {
            val newSample = mutableMapOf<String, String>()
            firstDataRow.forEachIndexed { idx, text ->
                val letter = colIndexToLetter(colStart + idx)
                newSample[letter] = text
            }
            sampleSheetRow = newSample
        }
        renderLivePreviewTable(rows, colStart, colEnd)

    } catch (e: Exception) {
        tvLivePreview?.text = "⚠ Preview error: ${e.message?.take(60)}"
        scrollLivePreview?.visibility = View.GONE
    } finally {
        pbPreviewLoad?.visibility = View.GONE
    }
}

internal fun ConfigSheetFragment.renderLivePreviewTable(
    rows: List<List<String>>,
    colStart: Int,
    colEnd: Int,
    targetTable: android.widget.TableLayout? = tableLivePreview,
    targetScroll: android.widget.HorizontalScrollView? = scrollLivePreview
) {
    val table = targetTable ?: return
    table.removeAllViews()
    val colCount = (colEnd - colStart + 1).coerceAtLeast(1)

    // Only show actual data rows — no blank padding rows
    val dataRows = rows.map { row -> List(colCount) { c -> row.getOrElse(c) { "" } } }

    val letters = List(colCount) { c -> colIndexToLetter(colStart + c) }
    table.addView(tableRow(letters, "#F3F4F6", "#6B7280", bold = true, compact = true))

    if (dataRows.isEmpty()) {
        // Show one placeholder row when no data yet
        table.addView(tableRow(List(colCount) { "" }, "#FFF7ED", "#111827", bold = true))
    } else {
        dataRows.forEachIndexed { i, row ->
            val bg = when (i) {
                0    -> "#FFF7ED"
                else -> if (i % 2 == 0) "#FFFFFF" else "#F9FAFB"
            }
            val bold = i == 0
            table.addView(tableRow(row, bg, if (bold) "#111827" else "#374151", bold = bold))
        }
    }
    targetScroll?.visibility = View.VISIBLE
}
internal fun ConfigSheetFragment.fetchManageColPreview() {
    val conn = activeConn() ?: return
    val signInAccount = GoogleSignIn.getLastSignedInAccount(requireContext()) ?: run {
        tvColPreviewMgr?.text = "⚠ Reconnect with a Google account"
        return
    }

    val startLetter = colIndexToLetter(conn.colStart)
    val endLetter   = colIndexToLetter(conn.colEnd)
    val sRow        = conn.startRow ?: 1
    val eRow        = conn.endRow?.takeIf { it > 0 }
    val maxEnd      = sRow + 5
    val previewEnd  = if (eRow != null) minOf(eRow, maxEnd) else maxEnd
    val range       = "${conn.tabName}!${startLetter}${sRow}:${endLetter}${previewEnd}"
    val label = when {
        eRow == null           -> "Preview: Row $sRow + next 5 rows"
        eRow <= sRow           -> "Preview: Row $sRow (end row ≤ start row)"
        previewEnd < maxEnd    -> "Preview: Row $sRow → $eRow (${previewEnd - sRow} rows)"
        else                   -> "Preview: Row $sRow + next 5 rows (end: $eRow)"
    }

    tvColPreviewMgr?.text = ""
    pbColPreviewMgr?.visibility = View.VISIBLE
    scrollColPreviewMgr?.visibility = View.GONE

    viewLifecycleOwner.lifecycleScope.launch {
        try {
            val acctObj = signInAccount.account ?: return@launch
            val ctx     = context ?: return@launch
            val token   = withContext(Dispatchers.IO) {
                try { GoogleAuthUtil.getToken(ctx, acctObj, ConfigSheetDriveApi.OAUTH_SCOPE) }
                catch (e: UserRecoverableAuthException) { null }
            } ?: run {
                tvColPreviewMgr?.text = "⚠ Token unavailable"
                return@launch
            }

            val encodedRange = java.net.URLEncoder.encode(range, "UTF-8")
            val url = "https://sheets.googleapis.com/v4/spreadsheets/${conn.sheetId}/values/$encodedRange"
            val rows = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url)
                    .header("Authorization", "Bearer $token").build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string() ?: return@withContext null
                    val arr = org.json.JSONObject(body).optJSONArray("values")
                        ?: return@withContext emptyList<List<String>>()
                    (0 until arr.length()).map { i ->
                        val row = arr.getJSONArray(i)
                        (0 until row.length()).map { j -> row.optString(j, "") }
                    }
                }
            }

            if (!isAdded) return@launch
            pbColPreviewMgr?.visibility = View.GONE
            if (rows == null) {
                tvColPreviewMgr?.text = "⚠ Sheet fetch failed"
                return@launch
            }
            tvColPreviewMgr?.text = label
            renderLivePreviewTable(rows, conn.colStart, conn.colEnd, tableColPreviewMgr, scrollColPreviewMgr)

        } catch (e: Exception) {
            if (isAdded) {
                pbColPreviewMgr?.visibility = View.GONE
                tvColPreviewMgr?.text = "⚠ Error: ${e.message?.take(60)}"
            }
        }
    }
}

/**
 * Sheets → Firebase sync entry for the Sheets tab (Sync Now button +
 * branch-card Sync button).
 *
 * Background-safe: hands the work to [ConfigSheetSyncService] (foreground
 * dataSync service) instead of running it in
 * `viewLifecycleOwner.lifecycleScope` — the old in-fragment coroutine was
 * tied to the fragment VIEW, so switching tabs, rotating, or backgrounding
 * the app long enough to destroy the view cancelled the sync mid-loop (plus
 * the drift AlertDialog could suspend forever with no Activity to show it).
 * The service survives all of that and reports back via notification.
 *
 * Same signature as before so both existing call sites work unchanged; the
 * open tab (if still visible) mirrors live progress in the busy overlay and
 * shows the final summary dialog via the service hooks below.
 */
internal fun ConfigSheetFragment.syncSheetToFirebase(conn: SheetConn) {
    if (conn.columnMapping.isEmpty()) {
        toast("⚠ No column mapping — complete Step 5")
        return
    }
    val pkParts: List<PkPart> = conn.effectivePkParts().ifEmpty {
        conn.columnMapping["consignmentId"]?.col?.let { listOf(PkPart("col", it)) } ?: emptyList()
    }
    if (pkParts.isEmpty()) {
        toast("⚠ No primary key selected — select it in Step 5")
        return
    }
    if (ConfigSheetSyncService.isRunning) {
        toast("🔄 Sync already running — background-এ চলছে, শেষ হলে notification আসবে")
        return
    }
    val ctx = try {
        requireContext()
    } catch (_: Exception) {
        return
    }
    // Mirror hooks BEFORE start (start only enqueues; the service runs after).
    ConfigSheetSyncService.onProgress = { done, total, ins, upd, skip ->
        try {
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                setBusy(
                    true,
                    "Syncing Firebase (background-safe)\n\n" +
                        "✅ Inserted: $ins   🔄 Updated: $upd   ⏭ Skipped: $skip\n" +
                        "📦 Processed: $done / $total"
                )
            }
        } catch (_: Exception) {
        }
    }
    ConfigSheetSyncService.onFinish = { ok, summary ->
        try {
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                setBusy(false)
                try {
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle(if (ok) "✅ Sync Complete" else "⚠ Sync Complete — with errors")
                        .setMessage(summary)
                        .setPositiveButton("OK", null)
                        .show()
                } catch (_: Exception) {
                    toast(if (ok) "✅ Sync complete" else "⚠ Sync finished with errors")
                }
            }
        } catch (_: Exception) {
        }
    }
    if (!ConfigSheetSyncService.start(ctx, conn.branchId, conn.connectionId)) {
        ConfigSheetSyncService.onProgress = null
        ConfigSheetSyncService.onFinish = null
        toast("⚠ Sync start failed — already running")
        return
    }
    setBusy(true, "Sync started…\n\nBackground-এ গেলেও চলবে — শেষ হলে notification + summary আসবে।")
    toast("🔄 Sync started — background-এ গেলেও চলবে")
}

/**
 * Parses a raw sheet cell value into an epoch-millis timestamp.
 * Returns null if the value can't be confidently parsed as a date/time —
 * callers should then skip pushing that field rather than writing garbage.
 * Accepts: epoch seconds (10-digit), epoch millis (13-digit), Google Sheets/Excel
 * date-serial numbers (e.g. 46204 — days since Dec 30 1899, decimal = time of day),
 * ISO-style date/date-time strings (yyyy-MM-dd, yyyy/MM/dd, with optional time),
 * or day-month-name values from Sheets (dd-MMM-yy / dd-MMM-yyyy, e.g. 03-Jul-26).
 * Slash/dash formats like "7/1/2026" are intentionally NOT accepted since
 * day-vs-month order can't be reliably determined — better to skip than guess wrong.
 */
// parseSheetTimestamp / normalizePhone → ConfigSheetParseUtil

/**
 * Rebuilds courier/runs_by_consignmentId for TODAY's runs across the admin's
 * branches. Ongoing sheet syncs only write the index for new/changed run rows,
 * so runs synced before the index existed never got entries — the caller
 * popup's fast path then finds nothing and the node looks absent in console.
 * Reads today's run ids from the branch indexes, then each run node's
 * consignments + status, and flushes in isolated batches (a denied batch
 * fails loudly here instead of silently — if rules are missing, the summary
 * says permission-denied).
 */
internal suspend fun ConfigSheetFragment.rebuildRunConsignmentIndex() {
    try {
        setBusy(true, "Run index rebuild…\n\nFinding branches...")
        val db = com.google.firebase.database.FirebaseDatabase.getInstance()
        var branchIds = RbacManager.current.branchIds.filter { it.isNotBlank() }.distinct()
        if (branchIds.isEmpty()) {
            // Admin with no explicit assignment — Supabase branch list (source of
            // truth since the cutover; the deleted Firebase branches/ node is gone).
            branchIds = runCatching { SupabaseBranchReader.listBranches() }
                .getOrNull().orEmpty().map { it.branchId }.filter { it.isNotBlank() }.distinct()
        }
        if (branchIds.isEmpty()) {
            setBusy(false)
            toast("No branches found")
            return
        }
        // Dhaka day (GMT+6 pinned) — run IDs are Dhaka-date keyed.
        val today = DhakaTime.todayKey()
        // Today's (runType, runId) from each branch index (server-side prefix range).
        setBusy(true, "Run index rebuild…\n\nFinding today\u2019s runs...")
        val runKeys = mutableSetOf<Pair<String, String>>()
        for (branchId in branchIds) {
            val typesSnap = try {
                withContext(Dispatchers.IO) {
                    db.reference.child("courier/runs_by_branchId/$branchId").get().await()
                }
            } catch (_: Exception) { continue }
            typesSnap.children.mapNotNull { it.key }.forEach { runType ->
                try {
                    val rangeSnap = withContext(Dispatchers.IO) {
                        db.reference.child("courier/runs_by_branchId/$branchId/$runType")
                            .orderByKey()
                            .startAt("run_${today}_")
                            .endAt("run_${today}_\uf8ff")
                            .get().await()
                    }
                    rangeSnap.children.mapNotNull { it.key?.trim()?.takeIf { k -> k.isNotBlank() } }
                        .forEach { runId -> runKeys.add(runType to runId) }
                } catch (_: Exception) { /* this type unreadable — skip */ }
            }
        }
        if (runKeys.isEmpty()) {
            setBusy(false)
            toast("No runs found today")
            return
        }
        // Per run: status + consignment ids → index paths.
        var indexed = 0
        var batch = mutableMapOf<String, Any>()
        var failures = 0
        var firstError = ""
        suspend fun flush() {
            if (batch.isEmpty()) return
            val n = batch.size
            try {
                withContext(Dispatchers.IO) {
                    db.reference.updateChildren(batch).await()
                }
                indexed += n
            } catch (e: Exception) {
                failures++
                if (firstError.isBlank()) firstError = e.message?.take(100) ?: e.javaClass.simpleName
            }
            batch = mutableMapOf()
        }
        var done = 0
        for ((runType, runId) in runKeys) {
            done++
            setBusy(true, "Run index rebuild…\n\n$done / ${runKeys.size} run")
            try {
                val runSnap = withContext(Dispatchers.IO) {
                    db.reference.child("courier/run_routes/$runType/$runId").get().await()
                }
                if (!runSnap.exists()) continue
                val status = runSnap.child("status").getValue(String::class.java)?.trim().orEmpty()
                if (status.isBlank()) continue
                runSnap.child("consignments").children.mapNotNull {
                    it.key?.trim()?.takeIf { k -> k.isNotBlank() }
                }.forEach { cid ->
                    batch["courier/runs_by_consignmentId/$cid/$runType/$runId"] = status
                    if (batch.size >= 400) flush()
                }
            } catch (_: Exception) { /* unreadable run — skip */ }
        }
        flush()
        setBusy(false)
        if (!isAdded) return
        val msg = if (failures == 0) {
            "✓ Run index rebuild done\n\n$indexed entries written from ${runKeys.size} runs."
        } else {
            "⚠ $indexed entries written, $failures batches failed.\n\nFirst error: $firstError\n\nUsually missing Firebase Rules write permission."
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Run index rebuild")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    } catch (e: Exception) {
        setBusy(false)
        toast("⚠ Rebuild error: ${e.message?.take(60)}")
    }
}
