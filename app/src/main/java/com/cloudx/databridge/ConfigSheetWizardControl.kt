package com.cloudx.databridge

import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.TableRow
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * ConfigSheetFragment's wizard control layer — the Manage panel's Sync tab (interval
 * picker, auto-sync toggle), the manage-column preview table helpers (tableRow/tableCell,
 * shared with the live-preview table in ConfigSheetWizardSteps.kt), step navigation
 * (advanceStep), the final Connect/Save flow (handleConnect → showReviewDialog →
 * commitConnection), auto-mapping, the dirty-detection signature system (Exit Wizard vs
 * Save), form clear/prefill, the range-only editor entry point, and the Google account
 * picker for this wizard.
 *
 * Extracted from ConfigSheetFragment.kt as part of breaking that ~4500-line file into
 * modules. Written as extension functions on ConfigSheetFragment for the same reason as
 * the other Config-Sheet module splits — this section's state (connectStep, screen,
 * pendingMapping, pendingPkParts, activeConnectionId, etc.) is read/written from other
 * wizard sections too.
 */
internal fun ConfigSheetFragment.renderSyncTab(conn: SheetConn) {
    switchAutoSync?.setOnCheckedChangeListener(null)
    switchAutoSync?.isChecked = conn.autoSync
    switchAutoSync?.setOnCheckedChangeListener { _, isChecked ->
        val c = activeConn() ?: return@setOnCheckedChangeListener
        val updated = c.copy(autoSync = isChecked)
        updateActiveConn(updated)
        updateSyncGearState(isChecked)
        saveSyncSettings(updated)
    }
    updateSyncGearState(conn.autoSync)
    tvSyncIntervalLabel?.text = "প্রতি ${conn.syncIntervalMin} মিনিট"
    tvLastSynced?.text = "Last sync: কখনো না"
}

internal fun ConfigSheetFragment.updateSyncGearState(enabled: Boolean) {
    btnSyncGear?.alpha = if (enabled) 1f else 0.4f
    btnSyncGear?.isEnabled = enabled
    tvSyncIntervalLabel?.setTextColor(
        android.graphics.Color.parseColor(if (enabled) "#E8380D" else "#6B7280")
    )
}

internal fun ConfigSheetFragment.openIntervalPickerDialog(conn: SheetConn) {
    val options = arrayOf("15 মিনিট", "30 মিনিট", "60 মিনিট", "120 মিনিট", "Custom...")
    val values  = intArrayOf(15, 30, 60, 120, -1)
    val current = values.indexOfFirst { it == conn.syncIntervalMin }.let {
        if (it < 0) options.size - 1 else it // custom if not in list
    }
    android.app.AlertDialog.Builder(requireContext())
        .setTitle("Auto Sync Interval")
        .setSingleChoiceItems(options, current) { dialog, which ->
            if (values[which] == -1) {
                // Custom input
                dialog.dismiss()
                showCustomIntervalInput(conn)
            } else {
                val newInterval = values[which]
                val updated = conn.copy(syncIntervalMin = newInterval)
                updateActiveConn(updated)
                tvSyncIntervalLabel?.text = "প্রতি $newInterval মিনিট"
                saveSyncSettings(updated)
                dialog.dismiss()
            }
        }
        .setNegativeButton("বাতিল", null)
        .show()
}

internal fun ConfigSheetFragment.showCustomIntervalInput(conn: SheetConn) {
    val ctx = context ?: return
    val input = android.widget.EditText(ctx).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        hint = "মিনিট লিখুন (1-1440)"
        textSize = 14f
        setPadding(48, 32, 48, 32)
        if (conn.syncIntervalMin !in listOf(15, 30, 60, 120)) {
            setText(conn.syncIntervalMin.toString())
        }
    }
    android.app.AlertDialog.Builder(ctx)
        .setTitle("Custom Interval")
        .setView(input)
        .setPositiveButton("Save") { _, _ ->
            val minutes = input.text.toString().trim().toIntOrNull()
            if (minutes == null || minutes < 1 || minutes > 1440) {
                toast("⚠ 1 থেকে 1440 মিনিটের মধ্যে দিন")
                return@setPositiveButton
            }
            val updated = conn.copy(syncIntervalMin = minutes)
            updateActiveConn(updated)
            tvSyncIntervalLabel?.text = "প্রতি $minutes মিনিট"
            saveSyncSettings(updated)
        }
        .setNegativeButton("বাতিল", null)
        .show()
}

internal fun ConfigSheetFragment.saveSyncSettings(conn: SheetConn) {
    viewLifecycleOwner.lifecycleScope.launch {
        try {
            val path = "config/sheets/${conn.branchId}/current"
            db.reference.child(path).updateChildren(mapOf(
                "autoSync"        to conn.autoSync,
                "syncIntervalMin" to conn.syncIntervalMin,
            )).await()
        } catch (e: Exception) {
            Log.e("ConfigSheet", "saveSyncSettings failed: ${e.message}")
        }
    }
}

/** Renders column letter header row in the Manage → Columns tab table */
internal fun ConfigSheetFragment.renderManageColTable(colStart: Int, colEnd: Int) {
    val table = tableColPreviewMgr ?: return
    table.removeAllViews()
    val colCount = (colEnd - colStart + 1).coerceAtLeast(1)
    val letters  = List(colCount) { c -> colIndexToLetter(colStart + c) }
    val nums     = List(colCount) { c -> "${colStart + c}" }
    table.addView(tableRow(letters, "#F3F4F6", "#6B7280", bold = true, compact = true))
    table.addView(tableRow(nums,    "#FFF7ED", "#E8380D", bold = true, compact = true))
    scrollColPreviewMgr?.visibility = View.VISIBLE
}

internal fun ConfigSheetFragment.tableRow(
    cells: List<String>,
    bgColor: String,
    textColor: String,
    bold: Boolean,
    compact: Boolean = false,
): TableRow {
    val row = TableRow(requireContext())
    cells.forEach { value ->
        row.addView(tableCell(value, bgColor, textColor, bold, compact))
    }
    return row
}

internal fun ConfigSheetFragment.tableCell(
    value: String,
    bgColor: String,
    textColor: String,
    bold: Boolean,
    compact: Boolean,
): TextView {
    val density = resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    return TextView(requireContext()).apply {
        text = value.ifBlank { " " }
        textSize = if (compact) 10f else 11f
        setTextColor(android.graphics.Color.parseColor(textColor))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        minWidth = dp(96)
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(8), dp(if (compact) 5 else 8), dp(8), dp(if (compact) 5 else 8))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor(bgColor))
            setStroke(dp(1), context!!.getColor(R.color.theme_border))
        }
    }
}

internal fun ConfigSheetFragment.advanceStep() {
    tvConnError?.visibility = View.GONE
    when (connectStep) {
        1 -> {
            if (googleAccount == null) { showErr("Google account select করুন"); return }
        }
        2 -> {
            if (selectedSheet == null) { showErr("Sheet select করুন"); return }
        }
        3 -> {
            if (selectedTab.isBlank()) { showErr("Tab select করুন"); return }
            selectedNickname = etNickname?.text?.toString()?.trim() ?: ""
            if (selectedNickname.isBlank()) { showErr("Nickname দিন — এটা required"); return }
        }
        4 -> {
            val s = parseColInput(etColStart?.text?.toString() ?: "") ?: run { showErr("Valid start column দিন (A বা 1)"); return }
            val e = parseColInput(etColEnd?.text?.toString() ?: "") ?: run { showErr("Valid end column দিন (J বা 10)"); return }
            if (s < 1 || e < s) { showErr("start ≤ end হতে হবে"); return }

            // If sheetHeaders not yet populated, fetch header row first then proceed
            if (sheetHeaders.isEmpty() && googleAccount != null && selectedSheet != null && selectedTab.isNotBlank()) {
                val account = googleAccount!!
                val sheet   = selectedSheet!!
                val tab     = selectedTab
                viewLifecycleOwner.lifecycleScope.launch {
                    setBusy(true, "Header fetch করছে...")
                    try {
                        val token = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try { com.google.android.gms.auth.GoogleAuthUtil.getToken(requireContext(), account.account!!, ConfigSheetDriveApi.OAUTH_SCOPE) }
                            catch (ex: Exception) { null }
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
                            sheetHeaders = newHeaders
                        }
                    } catch (_: Exception) {}
                    finally { setBusy(false) }
                    autoDetectMapping()
                    connectStep++
                    renderConnectStep()
                }
                return // coroutine handles the rest
            }

            autoDetectMapping()
        }
    }
    connectStep++
    renderConnectStep()
}

internal fun ConfigSheetFragment.showErr(msg: String) {
    tvConnError?.text = "⚠ $msg"
    tvConnError?.visibility = View.VISIBLE
}

internal fun ConfigSheetFragment.handleConnect() {
    if (!nodeMappingConfirmed) { showErr("আগে উপরে node পিক করে confirm করুন"); return }
    if (pendingPkParts.isEmpty()) { showErr("Primary key এ কমপক্ষে একটা part (prefix/column) যোগ করুন — required"); return }
    if (pendingPkParts.any { (it.type == "col" || it.type == "date") && it.value.isBlank() }) { showErr("Primary key এর Column/Date part-এ কলাম select করুন"); return }
    val sheet   = selectedSheet ?: run { showErr("Sheet নেই"); return }
    if (selectedTab.isBlank())  { showErr("Tab নেই"); return }
    val s = parseColInput(etColStart?.text?.toString() ?: "") ?: run { showErr("Valid start column দিন (A বা 1)"); return }
    val e = parseColInput(etColEnd?.text?.toString() ?: "")   ?: run { showErr("Valid end column দিন (J বা 10)"); return }
    if (s < 1 || e < s) { showErr("start ≤ end হতে হবে"); return }
    if (pendingMapping.isEmpty() && pendingObjectMapping.isEmpty()) { showErr("কমপক্ষে একটা field map করুন"); return }

    // Match by the exact connectionId currently being managed/edited — do NOT fall back
    // to the first connection in the branch, or a fresh "+ New Sheet" flow would
    // incorrectly overwrite an existing entry.
    val existing = connections[activeBranch]?.find { it.connectionId == activeConnectionId }
    val sRow = etStartRow?.text?.toString()?.trim()?.toIntOrNull()
    val eRow = etEndRow?.text?.toString()?.trim()?.toIntOrNull()

    // Reuse existing connectionId whenever we're editing a known connection
    // (covers both the row-range-only edit AND the full Reconnect wizard flow);
    // only generate a brand-new push key when there's genuinely no existing match.
    val connId = existing?.connectionId
        ?: (db.reference.child("config/sheets/$activeBranch/connections").push().key ?: java.util.UUID.randomUUID().toString())

    val conn = SheetConn(
        connectionId= connId,
        branchId    = activeBranch,
        sheetId     = sheet.id,
        sheetName   = sheet.name,
        tabName     = selectedTab,
        colStart    = s,
        colEnd      = e,
        startRow    = sRow,
        endRow      = eRow,
        nickname    = selectedNickname,
        googleEmail = googleAccount?.email ?: existing?.googleEmail ?: "",
        connectedBy = auth.currentUser?.uid ?: existing?.connectedBy ?: "",
        connectedAt = existing?.connectedAt ?: System.currentTimeMillis(),
        columnMapping = pendingMapping.toMap(),
        objectColumnMapping = pendingObjectMapping.toMap(),
        primaryKeyField = "",  // legacy field left blank for connections saved via new builder
        primaryKeyParts = pendingPkParts.toList(),
        targetNode    = "courier/" + (etTargetNode?.text?.toString()?.trim()?.trim('/')?.ifBlank { "consignments" } ?: "consignments"),
    )

    showReviewDialog(conn, isNew = existing == null)
}

/** Final review before committing — shows target node, primary key format, and every
 *  mapped field with real sample data, so a mistaken mapping is caught before it triggers
 *  a potentially large sync against live Firebase data. */
internal fun ConfigSheetFragment.showReviewDialog(conn: SheetConn, isNew: Boolean) {
    val ctx = context ?: return
    val dp = resources.displayMetrics.density
    fun Int.dp() = (this * dp).toInt()

    val scroll = android.widget.ScrollView(ctx)
    val root = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(24.dp(), 16.dp(), 24.dp(), 8.dp())
    }
    scroll.addView(root)

    fun sectionTitle(text: String) = TextView(ctx).apply {
        this.text = text; textSize = 11f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(android.graphics.Color.parseColor("#6B7280"))
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 10.dp(); bottomMargin = 4.dp() }
    }
    fun valueLine(text: String, color: String = "#111827") = TextView(ctx).apply {
        this.text = text; textSize = 13f
        setTextColor(android.graphics.Color.parseColor(color))
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 2.dp() }
    }

    root.addView(sectionTitle("TARGET NODE"))
    root.addView(valueLine(conn.targetNode, "#1D4ED8"))

    root.addView(sectionTitle("PRIMARY KEY (sample থেকে তৈরি)"))
    root.addView(valueLine(tvPkPreview?.text?.toString()?.removePrefix("Preview (1st row): ")?.removePrefix("Preview: ") ?: "—"))

    root.addView(sectionTitle("MAPPED FIELDS (${conn.columnMapping.size + conn.objectColumnMapping.size})"))
    if (conn.columnMapping.isEmpty() && conn.objectColumnMapping.isEmpty()) {
        root.addView(valueLine("⚠ কোনো field map করা হয়নি", "#F59E0B"))
    } else {
        conn.columnMapping.forEach { (field, colMap) ->
            val sample = sampleSheetRow[colMap.col]?.takeIf { it.isNotBlank() } ?: "(খালি)"
            root.addView(valueLine("• $field  →  ${colMap.header.ifBlank { colMap.col }}  =  \"$sample\""))
        }
        conn.objectColumnMapping.forEach { (field, spec) ->
            fun resolve(s: String): String = when {
                s.startsWith("fixed:") -> "\"${s.removePrefix("fixed:")}\" (fixed)"
                s.startsWith("col:") -> {
                    val letter = s.removePrefix("col:")
                    val sample = sampleSheetRow[letter]?.takeIf { it.isNotBlank() } ?: "(খালি)"
                    "${sheetHeaders[letter] ?: letter} = \"$sample\""
                }
                else -> s
            }
            root.addView(valueLine("• $field { key: ${resolve(spec.keySpec())}, value: ${resolve(spec.valueSpec())} }"))
        }
    }

    android.app.AlertDialog.Builder(ctx)
        .setTitle(if (isNew) "নতুন Sheet Connect করার আগে যাচাই করুন" else "Update করার আগে যাচাই করুন")
        .setView(scroll)
        .setPositiveButton(if (isNew) "✅ Connect করুন" else "✅ Update করুন") { _, _ -> commitConnection(conn, isNew) }
        .setNegativeButton("সম্পাদনা চালিয়ে যান", null)
        .show()
}

/** Actually persists the connection after the user has reviewed and confirmed it. */
internal fun ConfigSheetFragment.commitConnection(conn: SheetConn, isNew: Boolean) {
    val connList = connections.getOrPut(activeBranch) { mutableListOf() }
    val idx = connList.indexOfFirst { it.connectionId == conn.connectionId }
    if (idx >= 0) connList[idx] = conn else connList.add(conn)
    activeConnectionId = conn.connectionId
    // Expand this branch so newly added sheet card is visible
    expandedBranch = activeBranch
    screen = if (isRangeEdit) ConfigScreen.MANAGING else ConfigScreen.BRANCH_SELECT
    isRangeEdit = false
    render()

    // Wait for the actual Firebase write before claiming success — previously this fired
    // saveToFirebase() without waiting and showed "✅ connected!" unconditionally, so a
    // failed write (permission denied, network drop) looked identical to a real success
    // and the connection silently never made it to Firebase.
    val owner = viewLifecycleOwnerLiveData.value ?: return
    owner.lifecycleScope.launch {
        val ok = saveToFirebase(conn)
        if (ok) {
            toast(if (isNew) "✅ $activeBranch connected!" else "✅ Range updated")
        } else {
            toast("⚠ Firebase-এ save ব্যর্থ হয়েছে — নেটওয়ার্ক/permission চেক করে আবার চেষ্টা করুন")
        }
    }
}

internal fun ConfigSheetFragment.autoDetectMapping() {
    val editing = isEditingExistingConn()
    // For a NEW connection, recompute cleanly (original behaviour). For an EXISTING connection
    // being reconnected/edited, NEVER wipe the saved mapping — only fill unmapped gaps, so the
    // user's saved column↔header choices are preserved for both preview and sync.
    if (!editing) pendingMapping.clear()
    if (sheetHeaders.isEmpty()) return
    // Match each Firebase key against sheet headers by similarity
    val allKeys = fetchedNodeKeys + customMappingFields.map { it.first }
    allKeys.forEach { firebaseKey ->
        // Editing existing: skip any key that's already mapped (saved or user-set) or is an
        // object field — auto-detect must only touch fields with no mapping yet.
        if (editing && (pendingMapping.containsKey(firebaseKey) ||
                pendingObjectMapping.containsKey(firebaseKey) ||
                firebaseKey in objectTypeFields)) return@forEach
        val keyLower = firebaseKey.lowercase()
        // Split camelCase: "recipientName" → ["recipient", "name"]
        val keyParts = keyLower.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .lowercase().split(" ", "_", "-").filter { it.isNotBlank() }
        val matched = sheetHeaders.entries.firstOrNull { (_, header) ->
            val h = header.lowercase().trim()
            keyParts.any { part -> h.contains(part) || part.contains(h) }
        }
        if (matched != null) pendingMapping[firebaseKey] = ColMapping(col = matched.key, header = matched.value)
    }
}

// ── Dirty detection (Exit Wizard vs Save) ──────────────────────────────────────────────
// Signatures are LETTER-INSENSITIVE: they key on header text, not column letters, so the
// automatic drift-relocation done while rendering step 5 is NOT mistaken for a user edit.
// A genuine remap (field pointed at a different header), add/remove field, range/tab/sheet/
// nickname/target-node/primary-key change all DO change the signature. The comparison mirrors
// exactly what handleConnect() would persist. Safety rule: if anything can't be resolved we
// return "dirty", so we never show "Exit Wizard" while a real change is pending.
internal fun ConfigSheetFragment.sigCol(cm: ColMapping): String =
    if (cm.header.isNotBlank()) "h:${cm.header.trim().lowercase()}" else "c:${cm.col}"

internal fun ConfigSheetFragment.sigObj(o: ObjectColMapping): String {
    fun p(header: String, col: String) =
        if (header.isNotBlank()) "h:${header.trim().lowercase()}" else "c:$col"
    return "${p(o.keyHeader, o.keyCol)}~${p(o.valueHeader, o.valueCol)}"
}

internal fun ConfigSheetFragment.sigPk(part: PkPart): String = when (part.type) {
    "fixed" -> "fixed:${part.value.trim()}"
    else    -> "${part.type}:" +
        if (part.header.isNotBlank()) "h:${part.header.trim().lowercase()}" else "v:${part.value}"
}

internal fun ConfigSheetFragment.buildEditSignature(
    sheetId: String, tabName: String, colStart: Int, colEnd: Int,
    startRow: Int?, endRow: Int?, nickname: String, targetNode: String,
    colMap: Map<String, ColMapping>, objMap: Map<String, ObjectColMapping>,
    pkParts: List<PkPart>
): String {
    val sRow = startRow?.takeIf { it > 1 } ?: 0   // null / 1 → "no custom start"
    val eRow = endRow?.takeIf { it > 0 } ?: 0     // null / 0 → "no custom end"
    val node = targetNode.trim().trimEnd('/')
    val flat = colMap.entries
        .filter { it.value.col.isNotBlank() || it.value.header.isNotBlank() }
        .map { "${it.key}=${sigCol(it.value)}" }.sorted().joinToString("|")
    val obj = objMap.entries
        .map { "${it.key}=${sigObj(it.value)}" }.sorted().joinToString("|")
    val pk = pkParts.joinToString(">") { sigPk(it) }   // order matters for a composite key
    return listOf(
        "sheet=$sheetId", "tab=${tabName.trim()}",
        "cols=$colStart:$colEnd", "rows=$sRow:$eRow",
        "nick=${nickname.trim()}", "node=$node",
        "flat=$flat", "obj=$obj", "pk=$pk"
    ).joinToString("§")
}

/** Signature of the saved connection exactly as it lives in memory/Firebase. */
internal fun ConfigSheetFragment.savedSignatureOf(conn: SheetConn): String = buildEditSignature(
    conn.sheetId, conn.tabName, conn.colStart, conn.colEnd,
    conn.startRow, conn.endRow, conn.nickname, conn.targetNode,
    conn.columnMapping, conn.objectColumnMapping, conn.effectivePkParts()
)

/** Signature of the in-progress wizard state, from the SAME sources handleConnect() reads.
 *  Returns null when the state can't be fully resolved → caller treats that as dirty. */
internal fun ConfigSheetFragment.currentSignatureOrNull(): String? {
    val sheet = selectedSheet ?: return null
    if (selectedTab.isBlank()) return null
    val cs = parseColInput(etColStart?.text?.toString() ?: "") ?: return null
    val ce = parseColInput(etColEnd?.text?.toString() ?: "") ?: return null
    val sRow = etStartRow?.text?.toString()?.trim()?.toIntOrNull()
    val eRow = etEndRow?.text?.toString()?.trim()?.toIntOrNull()
    val node = "courier/" +
        (etTargetNode?.text?.toString()?.trim()?.trim('/')?.ifBlank { "consignments" } ?: "consignments")
    return buildEditSignature(
        sheet.id, selectedTab, cs, ce, sRow, eRow, selectedNickname, node,
        pendingMapping, pendingObjectMapping, pendingPkParts
    )
}

/** True only when reconnecting an EXISTING connection and nothing a Save would write changed. */
internal fun ConfigSheetFragment.isReconnectUnchanged(): Boolean {
    if (isRangeEdit) return false            // range-edit keeps its own Cancel/Save
    val conn = editingConn() ?: return false // new connection → never "unchanged"
    val current = currentSignatureOrNull() ?: return false
    return current == savedSignatureOf(conn)
}

/** Leaves the reconnect wizard WITHOUT saving. Mirrors the ✕ cancel path so no partial state
 *  leaks, and never writes to Firebase — the saved connection stays exactly as it was. */
internal fun ConfigSheetFragment.exitWizardNoChanges() {
    isRangeEdit = false
    screen = ConfigScreen.BRANCH_SELECT
    render()
}

/** Decides the step-5 primary button: Exit Wizard (existing + no change), Save (existing +
 *  changed & ready), or Connect (new & ready). Enable/alpha mirror handleConnect()'s checks so
 *  the button reflects readiness before it's tapped. Called from every place that can change
 *  the wizard state (mapping render, pk edits, step navigation). */
internal fun ConfigSheetFragment.updateConnectButtonState() {
    val hasValidPk = pendingPkParts.isNotEmpty() &&
        pendingPkParts.none { (it.type == "col" || it.type == "date") && it.value.isBlank() }
    val hasAtLeastOneField = pendingMapping.isNotEmpty() || pendingObjectMapping.isNotEmpty()
    val ready = nodeMappingConfirmed && hasValidPk && hasAtLeastOneField

    when {
        isReconnectUnchanged() -> {
            primaryAction = ConfigPrimaryAction.EXIT
            btnConnect?.text = "Exit Wizard"
            btnConnect?.isEnabled = true
            btnConnect?.alpha = 1.0f
        }
        isEditingExistingConn() -> {
            primaryAction = ConfigPrimaryAction.SAVE
            btnConnect?.text = "Save"
            btnConnect?.isEnabled = ready
            btnConnect?.alpha = if (ready) 1.0f else 0.45f
        }
        else -> {
            primaryAction = ConfigPrimaryAction.NEW
            btnConnect?.text = "Connect"
            btnConnect?.isEnabled = ready
            btnConnect?.alpha = if (ready) 1.0f else 0.45f
        }
    }
}

internal fun ConfigSheetFragment.clearConnectForm() {
    availableSheets = emptyList(); selectedSheet = null
    availableTabs   = emptyList(); selectedTab   = ""
    etColStart?.setText("1"); etColEnd?.setText("10")
    colEndUserModified = false // let header-detection auto-set Col End for this fresh connection
    isRowRangeVisible = false
    layoutRowRange?.visibility = View.GONE
    btnDefineRow?.text = "+ Define Row Range"
    etStartRow?.setText(""); etEndRow?.setText("")
    customMappingFields.clear()
    fetchedNodeKeys.clear()
    nodePreviewData = emptyMap()
    pendingMapping.clear()
    pendingObjectMapping.clear()
    objectTypeFields.clear()
    primaryKeyField = ""
    pendingPkParts.clear()
    targetNode = "courier/consignments"
    etTargetNode?.setText("")
    nodePickerPath.clear()
    nodePickerRevealedDepth = 0
    nodeMappingConfirmed = false
    nodeChildrenCache.clear()
    knownNodePaths = null
    tvFetchStatus?.text = ""
    if (googleAccount != null) loadSheetsForAccount()
}

internal fun ConfigSheetFragment.prefillConnectForm() {
    val conn = activeConn() ?: return
    // Reset transient node/field state so stale keys from a PREVIOUS connect/reconnect in the
    // same session can't survive into this one and cause duplicate rows (see allFields dedup).
    fetchedNodeKeys.clear()
    customMappingFields.clear()
    selectedSheet = DriveFile(conn.sheetId, conn.sheetName)
    selectedTab = conn.tabName
    selectedNickname = conn.nickname
    etNickname?.setText(conn.nickname)
    availableTabs = listOf(conn.tabName)
    updateSheetPickerLabel()
    etColStart?.setText(conn.colStart.toString())
    etColEnd?.setText(conn.colEnd.toString())
    colEndUserModified = true // saved value is authoritative — don't auto-override it
    // Show row range fields if previously saved
    val hasSavedRows = (conn.startRow != null && conn.startRow != 1) || (conn.endRow != null && conn.endRow != 0)
    isRowRangeVisible = hasSavedRows
    layoutRowRange?.visibility = if (hasSavedRows) View.VISIBLE else View.GONE
    btnDefineRow?.text = if (hasSavedRows) "− Hide Row Range" else "+ Define Row Range"
    if (hasSavedRows) {
        conn.startRow?.let { etStartRow?.setText(it.toString()) }
        conn.endRow?.takeIf { it > 0 }?.let { etEndRow?.setText(it.toString()) }
    }
    if (googleAccount != null) loadSheetsForAccount()
    selectedNickname = conn.nickname
    etNickname?.setText(conn.nickname)
    targetNode = conn.targetNode
    etTargetNode?.setText(conn.targetNode.removePrefix("courier/"))
    primaryKeyField = conn.primaryKeyField
    pendingPkParts.clear()
    pendingPkParts.addAll(conn.effectivePkParts())
    pendingMapping.clear()
    pendingMapping.putAll(conn.columnMapping)
    pendingObjectMapping.clear()
    pendingObjectMapping.putAll(conn.objectColumnMapping)
    objectTypeFields.clear()
    objectTypeFields.addAll(conn.objectColumnMapping.keys)
    // Existing connection — target node was already confirmed when it was saved
    nodeMappingConfirmed = conn.targetNode.isNotBlank()
    // Re-add object fields as custom fields so they render in the mapping step. Guard against
    // BOTH lists so an object key already present in fetchedNodeKeys is never double-added.
    conn.objectColumnMapping.keys.forEach { key ->
        if (fetchedNodeKeys.none { it == key } && customMappingFields.none { it.first == key })
            customMappingFields.add(key to key)
    }
}

internal fun ConfigSheetFragment.openRangeEditor() {
    val conn = activeConn() ?: return
    activeConnectionId = conn.connectionId

    selectedSheet = DriveFile(conn.sheetId, conn.sheetName)
    selectedTab = conn.tabName
    selectedNickname = conn.nickname
    etNickname?.setText(conn.nickname)
    availableTabs = listOf(conn.tabName)
    etColStart?.setText(conn.colStart.toString())
    etColEnd?.setText(conn.colEnd.toString())
    colEndUserModified = true // saved value is authoritative — don't auto-override it
    // Show row range if previously saved
    val hasSavedRows = (conn.startRow != null && conn.startRow != 1) || (conn.endRow != null && conn.endRow != 0)
    isRowRangeVisible = hasSavedRows
    layoutRowRange?.visibility = if (hasSavedRows) View.VISIBLE else View.GONE
    btnDefineRow?.text = if (hasSavedRows) "− Hide Row Range" else "+ Define Row Range"
    if (hasSavedRows) {
        conn.startRow?.let { etStartRow?.setText(it.toString()) }
        conn.endRow?.takeIf { it > 0 }?.let { etEndRow?.setText(it.toString()) }
    }
    isRangeEdit = true
    screen = ConfigScreen.CONNECTING
    connectStep = 4
    render()
}

// ── Account picker (JSX showPicker equivalent) ────────────────────
internal fun ConfigSheetFragment.pickGoogleAccount() {
    val client = googleSignInClient ?: run {
        toast("Google Sign-In initialize হয়নি")
        return
    }
    // Sign out first → forces the account chooser to show every time. Shared logic
    // lives in GoogleSignInHelper (used identically by ConfigConnectorsFragment).
    GoogleSignInHelper.pickAccount(
        fragment = this,
        client = client,
        signInLauncher = signInLauncher,
        onLaunchFailed = { msg -> toast("Sign-In launch failed: $msg") }
    )
}

internal fun ConfigSheetFragment.handleSignInResult(data: Intent?) {
    val acc = GoogleSignInHelper.parseSignInResult(data) { msg -> showErr(msg) } ?: return
    googleAccount = acc
    // Remember THIS feature's connected email — see PREFS_FILE_NAME doc comment.
    GoogleSignInHelper.rememberConnectedEmail(requireContext(), PREFS_FILE_NAME, acc.email)
    // Reset downstream
    availableSheets = emptyList(); selectedSheet = null
    availableTabs   = emptyList(); selectedTab   = ""
    updateAccountStep()
    loadSheetsForAccount()
}

internal fun ConfigSheetFragment.updateAccountStep() {
    val acc = googleAccount
    if (acc == null) {
        cardSelectedAccount?.visibility = View.GONE
        tvPickAccountLabel?.text = "Sign in with Google"
        // Hide Next until account is selected
        if (connectStep == 1) btnNext?.visibility = View.GONE
    } else {
        cardSelectedAccount?.visibility = View.VISIBLE
        tvSelectedAccountName?.text  = acc.displayName ?: acc.email ?: "Google User"
        tvSelectedAccountEmail?.text = acc.email ?: ""
        tvPickAccountLabel?.text = "Switch account"
        // Show Next when account is ready
        if (connectStep == 1) btnNext?.visibility = View.VISIBLE
    }
}
