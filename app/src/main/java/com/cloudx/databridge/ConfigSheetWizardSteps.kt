package com.cloudx.databridge

import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TableLayout
import android.widget.TextView
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
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
        tvColPreview?.text = "⚠ শুরু column দিন (A বা 1)"
        return
    }
    val e = parseColInput(etColEnd?.text?.toString() ?: "") ?: run {
        tvColPreview?.text = "⚠ শেষ column দিন (J বা 10)"
        return
    }
    if (s < 1 || e < s) {
        tvColPreview?.text = "⚠ Invalid range (start ≤ end)"
        return
    }
    val startLetter = colIndexToLetter(s)
    val endLetter   = colIndexToLetter(e)
    val count = e - s + 1
    tvColPreview?.text = "Columns: $startLetter ($s) – $endLetter ($e)  ·  মোট $count টি"
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
    tvSummary?.text = "✅ Summary\n\nAccount: $email\nSheet: $sheetName\nSheet ID: ${if (sheetId.length > 24) sheetId.take(24) + "…" else sheetId}\nTab: $tab\nColumns: $startLetter–$endLetter (${(e - s + 1).coerceAtLeast(1)}টি)\nBranch: ${branchLabel(activeBranch)}"
}

internal fun ConfigSheetFragment.scheduleLivePreview() {
    previewJob?.cancel()
    val account = googleAccount ?: run {
        tvLivePreview?.text = "Live preview দেখতে Google account sign in দরকার। Range save করা যাবে।"
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
            tvLivePreview?.text = "⚠ Token পাওয়া যায়নি"
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
            tvLivePreview?.text = "⚠ এই range এ কোনো data নেই"
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
        tvColPreviewMgr?.text = "⚠ Google account দিয়ে reconnect করুন"
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
                tvColPreviewMgr?.text = "⚠ Token পাওয়া যায়নি"
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

internal suspend fun ConfigSheetFragment.syncSheetToFirebase(conn: SheetConn) {
    val ctx = context ?: return
    val account = googleAccount ?: run { toast("Google account নেই"); return }
    val acctObj = account.account ?: return

    if (conn.columnMapping.isEmpty()) {
        toast("⚠ Column mapping নেই — Step 5 complete করুন")
        return
    }
    val pkParts: List<PkPart> = conn.effectivePkParts().ifEmpty {
        conn.columnMapping["consignmentId"]?.col?.let { listOf(PkPart("col", it)) } ?: emptyList()
    }
    if (pkParts.isEmpty()) {
        toast("⚠ Primary key select করা নেই — Step 5 এ select করুন")
        return
    }

    setBusy(true, "Sheet fetch করছে...")

    try {
        // ── 1. Get token ─────────────────────────────────────────
        val token = withContext(Dispatchers.IO) {
            try { GoogleAuthUtil.getToken(ctx, acctObj, ConfigSheetDriveApi.OAUTH_SCOPE) }
            catch (e: UserRecoverableAuthException) { null }
        } ?: run { setBusy(false); toast("⚠ Token পাওয়া যায়নি"); return }

        // ── 2. Fetch all sheet rows ───────────────────────────────
        val startLetter = colIndexToLetter(conn.colStart)
        val endLetter   = colIndexToLetter(conn.colEnd)
        val sRow = conn.startRow?.takeIf { it > 0 } ?: 1
        val eRow = conn.endRow?.takeIf   { it > 0 }
        val rangeStr = if (eRow != null)
            "${conn.tabName}!${startLetter}${sRow}:${endLetter}${eRow}"
        else
            "${conn.tabName}!${startLetter}${sRow}:${endLetter}"
        val encodedRange = java.net.URLEncoder.encode(rangeStr, "UTF-8")
        val url = "https://sheets.googleapis.com/v4/spreadsheets/${conn.sheetId}/values/$encodedRange"

        val allRows = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $token").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val arr  = org.json.JSONObject(body).optJSONArray("values")
                    ?: return@withContext emptyList<List<String>>()
                (0 until arr.length()).map { i ->
                    val row = arr.getJSONArray(i)
                    (0 until row.length()).map { j -> row.optString(j, "") }
                }
            }
        }

        if (allRows == null) { setBusy(false); toast("⚠ Sheet fetch failed"); return }

        // First row = header, rest = data
        val headerRow = allRows.firstOrNull() ?: emptyList()
        val dataRows  = if (allRows.size > 1) allRows.drop(1) else allRows

        // ── 3. Column drift detection ─────────────────────────────
        val currentHeaders = headerRow.mapIndexed { idx, header ->
            colIndexToLetter(conn.colStart + idx) to header.trim()
        }.toMap()

        /** Levenshtein similarity 0..1 between two strings */
        fun similarity(a: String, b: String): Float {
            if (a == b) return 1f
            if (a.isEmpty() || b.isEmpty()) return 0f
            val la = a.lowercase(); val lb = b.lowercase()
            val dp = Array(la.length + 1) { IntArray(lb.length + 1) }
            for (i in 0..la.length) dp[i][0] = i
            for (j in 0..lb.length) dp[0][j] = j
            for (i in 1..la.length) for (j in 1..lb.length) {
                dp[i][j] = if (la[i-1] == lb[j-1]) dp[i-1][j-1]
                else minOf(dp[i-1][j], dp[i][j-1], dp[i-1][j-1]) + 1
            }
            return 1f - dp[la.length][lb.length].toFloat() / maxOf(la.length, lb.length)
        }

        val FUZZY_WARN  = 0.80f  // ≥ 80% similar → yellow warning, sync proceeds
        val FUZZY_MATCH = 0.95f  // ≥ 95% similar → treat as "moved" (auto-correct col)

        val driftFields    = mutableListOf<Triple<String, String, String>>()
        val resolvedMapping = mutableMapOf<String, String>()

        conn.columnMapping.forEach { (field, cm) ->
            if (cm.col.isBlank()) return@forEach
            val currentHeader = currentHeaders[cm.col]
            when {
                // ✅ Exact match at same column
                currentHeader != null && currentHeader.equals(cm.header, ignoreCase = true) ->
                    resolvedMapping[field] = cm.col

                // ✅ Header moved to a different column (exact match elsewhere)
                cm.header.isNotBlank() -> {
                    val exactNewCol = currentHeaders.entries
                        .firstOrNull { it.value.equals(cm.header, ignoreCase = true) }?.key
                    if (exactNewCol != null) {
                        resolvedMapping[field] = exactNewCol
                        driftFields.add(Triple(field, cm.header, "moved: ${cm.col} → $exactNewCol"))
                    } else {
                        // ⚠ No exact match — look for fuzzy match
                        val best = currentHeaders.entries
                            .map { it to similarity(cm.header, it.value) }
                            .maxByOrNull { it.second }
                        val bestSim = best?.second ?: 0f
                        val bestCol = best?.first?.key ?: ""
                        val bestHdr = best?.first?.value ?: ""
                        when {
                            bestSim >= FUZZY_MATCH -> {
                                // Very close match — auto-correct silently
                                resolvedMapping[field] = bestCol
                                driftFields.add(Triple(field, cm.header,
                                    "moved~: ${cm.col} → $bestCol (\"$bestHdr\", ${(bestSim*100).toInt()}%)"))
                            }
                            bestSim >= FUZZY_WARN -> {
                                // Fuzzy match — warn but still sync with original col
                                resolvedMapping[field] = cm.col
                                driftFields.add(Triple(field, cm.header,
                                    "fuzzy: \"${cm.header}\" ≈ \"$bestHdr\" ($bestCol, ${(bestSim*100).toInt()}%) — মূল column ${cm.col} ব্যবহার করা হচ্ছে"))
                            }
                            else ->
                                driftFields.add(Triple(field, cm.header, "missing"))
                                .also { resolvedMapping[field] = cm.col }
                        }
                    }
                }
                else -> resolvedMapping[field] = cm.col
            }
        }

        // Object field drift check (key col + value col separately)
        conn.objectColumnMapping.forEach { (field, ocm) ->
            listOf(ocm.keyCol to ocm.keyHeader, ocm.valueCol to ocm.valueHeader).forEach { (col, savedHdr) ->
                if (col.isBlank() || savedHdr.isBlank() || col.startsWith("fixed:")) return@forEach
                val currentHdr = currentHeaders[col]
                if (currentHdr == null || !currentHdr.equals(savedHdr, ignoreCase = true)) {
                    val sim = if (currentHdr != null) similarity(savedHdr, currentHdr) else 0f
                    val label = if (col == ocm.keyCol) "$field.key" else "$field.value"
                    when {
                        sim >= FUZZY_WARN ->
                            driftFields.add(Triple(label, savedHdr,
                                "fuzzy: \"$savedHdr\" ≈ \"${currentHdr ?: ""}\" ($col, ${(sim*100).toInt()}%)"))
                        else ->
                            driftFields.add(Triple(label, savedHdr, "missing"))
                    }
                }
            }
        }

        // Primary key part drift check
        conn.effectivePkParts().forEach { part ->
            if (part.type != "col" && part.type != "date") return@forEach
            if (part.header.isBlank()) return@forEach
            val currentHdr = currentHeaders[part.value]
            if (currentHdr == null || !currentHdr.equals(part.header, ignoreCase = true)) {
                val sim = if (currentHdr != null) similarity(part.header, currentHdr) else 0f
                driftFields.add(Triple("primaryKey[${part.value}]", part.header,
                    if (sim >= FUZZY_WARN) "fuzzy: \"${part.header}\" ≈ \"${currentHdr ?: ""}\" (${(sim*100).toInt()}%)"
                    else "missing"))
            }
        }

        if (driftFields.isNotEmpty()) {
            setBusy(false)
            val moved   = driftFields.filter { it.third.startsWith("moved:") }
            val movedF  = driftFields.filter { it.third.startsWith("moved~:") }
            val fuzzy   = driftFields.filter { it.third.startsWith("fuzzy:") }
            val missing = driftFields.filter { it.third == "missing" }
            val message = buildString {
                if (moved.isNotEmpty()) {
                    append("📍 Column সরে গেছে (auto-corrected):\n")
                    moved.forEach { (f, h, i) -> append("  • \"$h\" ($f): ${i.removePrefix("moved: ")}\n") }
                    append("\n")
                }
                if (movedF.isNotEmpty()) {
                    append("📍 Column খুব কাছাকাছি (auto-corrected):\n")
                    movedF.forEach { (f, h, i) -> append("  • \"$h\" ($f): ${i.removePrefix("moved~: ")}\n") }
                    append("\n")
                }
                if (fuzzy.isNotEmpty()) {
                    append("⚠ Header পরিবর্তন সন্দেহ (fuzzy match — সতর্কতার সাথে confirm করুন):\n")
                    fuzzy.forEach { (f, _, i) -> append("  • $f: ${i.removePrefix("fuzzy: ")}\n") }
                    append("\n")
                }
                if (missing.isNotEmpty()) {
                    append("❌ Header পাওয়া যায়নি (column পরীক্ষা করুন):\n")
                    missing.forEach { (f, h, _) -> append("  • \"$h\" ($f)\n") }
                }
            }
            val hasMissing = missing.isNotEmpty()
            if (!isAdded) return
            val proceed = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                android.app.AlertDialog.Builder(ctx)
                    .setTitle(if (hasMissing) "❌ Column Drift সনাক্ত!" else "⚠ Column পরিবর্তন")
                    .setMessage(message.trim())
                    .setPositiveButton(if (hasMissing) "Reposition করুন" else "এভাবেই Sync করুন") { _, _ ->
                        if (hasMissing) { openRangeEditor(); cont.resume(false) {} }
                        else cont.resume(true) {}
                    }
                    .setNegativeButton("বাতিল") { _, _ -> cont.resume(false) {} }
                    .setCancelable(false)
                    .show()
            }
            if (!proceed) return
            setBusy(true, "Sync করছে...")
        }

        if (dataRows.isEmpty()) { setBusy(false); toast("⚠ Sheet এ কোনো data নেই"); return }

        // ── 3. Build colLetter → index map ────────────────────────
        fun letterToIndex(letter: String): Int {
            val idx = parseColInput(letter) ?: return -1
            return idx - conn.colStart  // 0-based within fetched range
        }

        // Builds the composite primary key for one row by concatenating its parts in order.
        // If ANY part fails to resolve (a "date" part that won't parse, OR a "col" part whose
        // cell is blank — e.g. agentSystemId missing) the whole key comes back blank so the
        // existing conId.isBlank() check skips the row — a malformed/incomplete key must never
        // be produced (e.g. "run_040726_" with the agent segment silently missing).
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
                        val millis = if (raw.isNotBlank()) parseSheetTimestamp(raw) else null
                        if (millis == null) {
                            partFailed = true
                            ""
                        } else {
                            java.text.SimpleDateFormat("ddMMyy", java.util.Locale.ENGLISH)
                                .format(java.util.Date(millis))
                        }
                    }
                    else -> ""
                }
            }
            return if (partFailed) "" else key
        }

        // ── 4. Process rows ───────────────────────────────────────
        setBusy(true, "Firebase sync করছে...")

        var inserted = 0; var updated = 0; var skipped = 0
        val dateIssues = mutableListOf<String>()
        // Captures the ACTUAL exception from a failed updateChildren() call, keyed by
        // conId — previously swallowed silently (catch (_: Exception) {}), which is why
        // sync could report "Inserted/Updated" counts while Firebase showed no change:
        // inserted++/updated++ happen based on what we INTENDED to write, not on
        // confirmation the write succeeded. Now surfaced in the summary dialog so a
        // permission-denied or validation-rule rejection is visible instead of silent.
        val writeFailures = mutableListOf<String>()
        // systemIds seen in this sync that had NO branch_ids on their users/{uid} profile —
        // means runs_by_branchId was silently never written for their runs. Deduped (Set)
        // since the same agent can appear across many rows.
        val branchlessAgentSystemIds = mutableSetOf<String>()
        val basePath = conn.targetNode.trimEnd('/')

        // ── runs_by_branchId support: resolve each agent's branch_ids ON DEMAND, cached
        // per systemId — NOT a bulk scan of the whole users/ tree. For each distinct
        // agentSystemId encountered in the sheet: users_by_systemId/{sysId}/uid (O(1)
        // reverse-index lookup) -> users/{uid}/profile/company_info/branch_ids (O(1)
        // direct read). Cached so an agent with many rows/consignments in this sync is
        // only fetched once, not once per row.
        val agentBranchCache = mutableMapOf<String, List<String>>()
        val branchResolveFailures = mutableListOf<String>()     // the lookup itself threw

        suspend fun resolveAgentBranchIds(systemId: String): List<String> {
            agentBranchCache[systemId]?.let { return it }
            val branchIds = try {
                val uid = withContext(Dispatchers.IO) {
                    db.reference.child("users_by_systemId/$systemId/uid").get().await()
                        .getValue(String::class.java)?.trim()
                }
                if (uid.isNullOrBlank()) {
                    branchlessAgentSystemIds.add(systemId)
                    emptyList()
                } else {
                    val ids = withContext(Dispatchers.IO) {
                        db.reference.child("users/$uid/profile/company_info/branch_ids")
                            .get().await().children.mapNotNull { it.getValue(String::class.java) }
                    }
                    if (ids.isEmpty()) branchlessAgentSystemIds.add(systemId)
                    ids
                }
            } catch (e: Exception) {
                branchResolveFailures.add("$systemId → ${e.message?.take(80) ?: e.javaClass.simpleName}")
                emptyList()
            }
            agentBranchCache[systemId] = branchIds
            return branchIds
        }

        for (row in dataRows) {
            val conId = buildPrimaryKey(row)
            if (conId.isBlank()) { skipped++; continue }

            // Build field map from column mapping (drift-corrected: resolvedMapping)
            val fieldMap = mutableMapOf<String, Any>()
            resolvedMapping.forEach { (field, colLetter) ->
                if (field == "createdAt" || field == "updatedAt") return@forEach // handled below with validation
                val idx = letterToIndex(colLetter)
                if (idx < 0) return@forEach
                val value = row.getOrElse(idx) { "" }.trim()
                if (value.isNotBlank()) fieldMap[field] = value
            }

            // ── createdAt / updatedAt — parsed from sheet with safety checks:
            //    1) must be a parseable date/timestamp (else ignored — not pushed)
            //    2) must not be in the future
            //    3) createdAt must not be after updatedAt
            val nowMillis = System.currentTimeMillis()
            val createdRaw = resolvedMapping["createdAt"]?.let { colLetter ->
                val idx = letterToIndex(colLetter)
                if (idx >= 0) row.getOrElse(idx) { "" }.trim() else ""
            } ?: ""
            val updatedRaw = resolvedMapping["updatedAt"]?.let { colLetter ->
                val idx = letterToIndex(colLetter)
                if (idx >= 0) row.getOrElse(idx) { "" }.trim() else ""
            } ?: ""

            var createdAtMillis: Long? = if (createdRaw.isNotBlank()) parseSheetTimestamp(createdRaw) else null
            var updatedAtMillis: Long? = if (updatedRaw.isNotBlank()) parseSheetTimestamp(updatedRaw) else null

            if (createdRaw.isNotBlank() && createdAtMillis == null) {
                dateIssues.add("$conId → createdAt: বোঝা যায়নি (\"$createdRaw\")")
            }
            if (updatedRaw.isNotBlank() && updatedAtMillis == null) {
                dateIssues.add("$conId → updatedAt: বোঝা যায়নি (\"$updatedRaw\")")
            }
            if (createdAtMillis != null && createdAtMillis!! > nowMillis) {
                dateIssues.add("$conId → createdAt: ভবিষ্যতের তারিখ, বাদ দেওয়া হয়েছে")
                createdAtMillis = null
            }
            if (updatedAtMillis != null && updatedAtMillis!! > nowMillis) {
                dateIssues.add("$conId → updatedAt: ভবিষ্যতের তারিখ, বাদ দেওয়া হয়েছে")
                updatedAtMillis = null
            }
            if (createdAtMillis != null && updatedAtMillis != null && createdAtMillis!! > updatedAtMillis!!) {
                dateIssues.add("$conId → createdAt, updatedAt-এর পরে হওয়ায় বাদ দেওয়া হয়েছে")
                createdAtMillis = null
            }
            createdAtMillis?.let { fieldMap["createdAt"] = it }
            updatedAtMillis?.let { fieldMap["updatedAt"] = it }

            // Normalize phone
            val phoneField = resolvedMapping["recipientPhone"]?.let { colLetter ->
                val idx = letterToIndex(colLetter)
                if (idx >= 0) row.getOrElse(idx) { "" }.trim() else ""
            }
            val normalizedPhone = normalizePhone(phoneField ?: "")
            if (normalizedPhone.isNotBlank()) fieldMap["recipientPhone"] = normalizedPhone

            // userSystemId — used for runs_by_agentSystemId reverse-index
            // Looks for agentSystemId or agent_system_id field in mapping (preferred over employee_id)
            val userSystemId = fieldMap["agentSystemId"]?.toString()?.trim().orEmpty()
                .ifBlank { fieldMap["agent_system_id"]?.toString()?.trim().orEmpty() }

            // ── Object-type fields: build key-value pair from two specs ────
            // spec: "col:A" (dynamic, read from that column) or "fixed:text" (constant)
            // writes to: {basePath}/{conId}/{field}/{keyValue} = value
            fun resolveSpec(spec: String): String {
                return when {
                    spec.startsWith("fixed:") -> spec.removePrefix("fixed:")
                    spec.startsWith("col:") -> {
                        val letter = spec.removePrefix("col:")
                        val idx = letterToIndex(letter)
                        if (idx < 0) "" else row.getOrElse(idx) { "" }.trim()
                    }
                    else -> "" // legacy: bare column letter (backward compat)
                }
            }
            val objectFieldWrites = mutableMapOf<String, Any>()
            conn.objectColumnMapping.forEach { (field, ocm) ->
                val keyVal   = resolveSpec(ocm.keySpec())
                val valueVal = resolveSpec(ocm.valueSpec())
                if (keyVal.isNotBlank() && valueVal.isNotBlank()) {
                    objectFieldWrites["$field/$keyVal"] = valueVal
                }
            }

            // ── Check Firebase exist ──────────────────────────────
            var existReadFailed = false
            val existSnap = withContext(Dispatchers.IO) {
                try { db.reference.child("$basePath/$conId").get().await() }
                catch (e: Exception) {
                    existReadFailed = true
                    writeFailures.add("$conId → read check failed: ${e.message?.take(80) ?: e.javaClass.simpleName}")
                    null
                }
            }
            if (existReadFailed) { skipped++; continue }

            val multiUpdate = mutableMapOf<String, Any>()

            if (existSnap == null || !existSnap.exists()) {
                // INSERT
                fieldMap.forEach { (k, v) -> multiUpdate["$basePath/$conId/$k"] = v }
                objectFieldWrites.forEach { (k, v) -> multiUpdate["$basePath/$conId/$k"] = v }
                // consignments_by_phone — legacy secondary index, only relevant for the
                // default courier/consignments flow. Guarded so other sheet types
                // (e.g. courier/run_routes/...) never write into this unrelated index.
                if (basePath == "courier/consignments" && normalizedPhone.isNotBlank()) {
                    val status = fieldMap["status"]?.toString() ?: ""
                    multiUpdate["courier/consignments_by_phone/$normalizedPhone/$conId"] = status
                }
                // runs_by_agentSystemId — same reverse-index pattern, generalized for ANY run type
                // under courier/run_routes/{runType}/ (delivery_run, pickup_run, return_run, etc.)
                // so future run types work automatically without code changes.
                val runTypeMatch = Regex("^courier/run_routes/([^/]+)$").find(basePath)
                if (runTypeMatch != null && userSystemId.isNotBlank()) {
                    val runType = runTypeMatch.groupValues[1]
                    val status = fieldMap["status"]?.toString() ?: ""
                    multiUpdate["courier/runs_by_agentSystemId/$userSystemId/$runType/$conId"] = status

                    // runs_by_branchId — branch is resolved ONCE at run-creation time (the agent's
                    // branch_ids *right now*), then locked in via resolvedBranchIds on the run
                    // node itself. If the agent switches branch tomorrow, tomorrow's runId is a
                    // different key entirely (date-scoped), so it re-resolves naturally — today's
                    // already-created run intentionally stays put.
                    val agentBranchIds = resolveAgentBranchIds(userSystemId)
                    if (agentBranchIds.isNotEmpty()) {
                        multiUpdate["$basePath/$conId/resolvedBranchIds"] = agentBranchIds
                        agentBranchIds.forEach { branchId ->
                            multiUpdate["courier/runs_by_branchId/$branchId/$runType/$conId"] = status
                        }
                    }
                }
                inserted++
            } else {
                // COMPARE & UPDATE changed fields only
                val changedFields = mutableMapOf<String, Any>()
                fieldMap.forEach { (k, v) ->
                    val firebaseVal = existSnap.child(k).value
                    val same = when {
                        v is Long && firebaseVal is Number -> firebaseVal.toLong() == v
                        else -> (firebaseVal?.toString() ?: "") == v.toString()
                    }
                    if (!same) changedFields[k] = v
                }
                // Object fields: compare each key-value pair individually
                objectFieldWrites.forEach { (path, v) ->
                    val firebaseVal = existSnap.child(path).value
                    if ((firebaseVal?.toString() ?: "") != v.toString()) changedFields[path] = v
                }
                if (changedFields.isNotEmpty()) {
                    changedFields.forEach { (k, v) -> multiUpdate["$basePath/$conId/$k"] = v }
                    // Update consignments_by_phone if status changed (guarded, see note above)
                    if (basePath == "courier/consignments" && "status" in changedFields && normalizedPhone.isNotBlank()) {
                        multiUpdate["courier/consignments_by_phone/$normalizedPhone/$conId"] =
                            changedFields["status"].toString()
                    }
                    // Update runs_by_agentSystemId if status changed (same guarded pattern, generalized run type)
                    val runTypeMatchUpd = Regex("^courier/run_routes/([^/]+)$").find(basePath)
                    if (runTypeMatchUpd != null && "status" in changedFields && userSystemId.isNotBlank()) {
                        val runType = runTypeMatchUpd.groupValues[1]
                        multiUpdate["courier/runs_by_agentSystemId/$userSystemId/$runType/$conId"] =
                            changedFields["status"].toString()

                        // runs_by_branchId — branch was already locked in at INSERT time
                        // (resolvedBranchIds on the run node). We only propagate the new
                        // status here; we deliberately do NOT re-resolve the agent's branch,
                        // since today's run must stay attached to the branch it was created in
                        // even if the agent's branch assignment changes later today.
                        val lockedBranchIds = existSnap?.child("resolvedBranchIds")
                            ?.children?.mapNotNull { it.getValue(String::class.java) }.orEmpty()
                        lockedBranchIds.forEach { branchId ->
                            multiUpdate["courier/runs_by_branchId/$branchId/$runType/$conId"] =
                                changedFields["status"].toString()
                        }
                    }
                    updated++
                } else {
                    skipped++
                }
            }

            // Multi-path write
            if (multiUpdate.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    try {
                        db.reference.updateChildren(multiUpdate).await()
                    } catch (e: Exception) {
                        writeFailures.add("$conId → ${e.message?.take(80) ?: e.javaClass.simpleName}")
                    }
                }
            }

            // Live progress — updates the busy overlay after every row, like a running counter
            val processedSoFar = inserted + updated + skipped
            setBusy(
                true,
                "Firebase sync করছে...\n\n" +
                "✅ Inserted: $inserted   🔄 Updated: $updated   ⏭ Skipped: $skipped\n" +
                "📦 Processed: $processedSoFar / ${dataRows.size}"
            )
        }

        // ── 5. Summary dialog ─────────────────────────────────────
        setBusy(false)
        if (!isAdded) return
        val issuesText = if (dateIssues.isNotEmpty()) {
            val shown = dateIssues.take(10).joinToString("\n") { "• $it" }
            val more  = if (dateIssues.size > 10) "\n…আরও ${dateIssues.size - 10}টি" else ""
            "\n\n⚠ Date সংক্রান্ত সমস্যা (${dateIssues.size}টি):\n$shown$more"
        } else ""
        // writeFailures means the row was COUNTED as inserted/updated above but the actual
        // Firebase write was rejected (permission-denied, validation rule, etc.) — shown
        // separately and loudly so this doesn't look like a silent success.
        val failuresText = if (writeFailures.isNotEmpty()) {
            val shown = writeFailures.take(10).joinToString("\n") { "• $it" }
            val more  = if (writeFailures.size > 10) "\n…আরও ${writeFailures.size - 10}টি" else ""
            "\n\n❌ Firebase-এ write ব্যর্থ হয়েছে (${writeFailures.size}টি) — এই row গুলো Inserted/Updated " +
            "count-এ ধরা হয়েছে কিন্তু আসলে save হয়নি:\n$shown$more\n\n" +
            "সাধারণত Firebase Security Rules-এ এই path-এ write permission নেই।"
        } else ""
        val branchlessText = when {
            branchResolveFailures.isNotEmpty() -> {
                val shown = branchResolveFailures.take(10).joinToString("\n") { "• $it" }
                val more  = if (branchResolveFailures.size > 10) "\n…আরও ${branchResolveFailures.size - 10}টি" else ""
                "\n\n⚠ runs_by_branchId resolve করতে ব্যর্থ (${branchResolveFailures.size}টি agent) — " +
                "users_by_systemId বা users/ read সমস্যা:\n$shown$more"
            }
            branchlessAgentSystemIds.isNotEmpty() -> {
                val shown = branchlessAgentSystemIds.take(10).joinToString(", ")
                val more  = if (branchlessAgentSystemIds.size > 10) " …আরও ${branchlessAgentSystemIds.size - 10}টি" else ""
                "\n\n⚠ এই agent-দের uid পাওয়া যায়নি বা branch_ids assign করা নেই বলে runs_by_branchId তৈরি হয়নি " +
                "(${branchlessAgentSystemIds.size}টি systemId): $shown$more\n" +
                "Employee edit থেকে এদের branch assign করুন।"
            }
            else -> ""
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle(if (writeFailures.isEmpty()) "✅ Sync Complete" else "⚠ Sync Complete — with errors")
            .setMessage(
                "Inserted : $inserted\n" +
                "Updated  : $updated\n" +
                "Skipped  : $skipped\n" +
                "Total    : ${dataRows.size}" +
                issuesText + failuresText + branchlessText
            )
            .setPositiveButton("OK", null)
            .show()

    } catch (e: Exception) {
        setBusy(false)
        toast("⚠ Sync error: ${e.message?.take(60)}")
    }
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
