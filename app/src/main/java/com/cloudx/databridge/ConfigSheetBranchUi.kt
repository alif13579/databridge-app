package com.cloudx.databridge

import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ConfigSheetFragment's Branch Select screen — the entry screen (BRANCH_SELECT state)
 * shown before entering the Connect wizard for a specific branch. Renders the
 * branch-picker spinner, the Connected/Unconnected tab split, per-branch connection
 * cards, and the action card ("+ New Sheet" / "Manage" buttons) for whichever branch is
 * currently selected.
 *
 * Extracted from ConfigSheetFragment.kt as part of breaking that ~4500-line file into
 * modules. Written as extension functions on ConfigSheetFragment (not a separate class
 * holding its own state) for the same reason as the other Config-Sheet module splits:
 * this screen's state (activeBranch, connections, screen) is read/written from other
 * parts of the fragment too (e.g. render() checks `screen` to decide which panel to
 * show). Keeping that state on the fragment and moving only these functions here keeps
 * behavior identical while organizing the code.
 */
internal fun ConfigSheetFragment.updateBranchSpinner() {
    val ctx = context ?: return

    if (branches.isEmpty()) {
        tvBranchEmpty?.visibility      = View.VISIBLE
        layoutBranchTabs?.visibility   = View.GONE
        sectionConnected?.visibility   = View.GONE
        sectionUnconnected?.visibility = View.GONE
        return
    }

    tvBranchEmpty?.visibility = View.GONE

    val connectedBranches   = branches.filter { connections[it]?.isNotEmpty() == true }
    val unconnectedBranches = branches.filter { connections[it].isNullOrEmpty() }
    val hasBoth = connectedBranches.isNotEmpty() && unconnectedBranches.isNotEmpty()

    // ── Tab row ───────────────────────────────────────────────────
    if (hasBoth) {
        layoutBranchTabs?.visibility = View.VISIBLE
        if (activeBranchTab != "unconnected") activeBranchTab = "connected"
        updateBranchTabStyles()
        tabBranchConnected?.setOnClickListener {
            activeBranchTab = "connected"
            updateBranchTabStyles()
            renderBranchSections(ctx, connectedBranches, unconnectedBranches)
        }
        tabBranchUnconnected?.setOnClickListener {
            activeBranchTab = "unconnected"
            updateBranchTabStyles()
            renderBranchSections(ctx, connectedBranches, unconnectedBranches)
        }
        tabBranchConnected?.text   = "Connected (${connectedBranches.size})"
        tabBranchUnconnected?.text = "Unconnected (${unconnectedBranches.size})"
    } else {
        // No unconnected branches → hide tab row entirely
        layoutBranchTabs?.visibility = View.GONE
        activeBranchTab = if (connectedBranches.isEmpty()) "unconnected" else "connected"
    }

    renderBranchSections(ctx, connectedBranches, unconnectedBranches)
}

internal fun ConfigSheetFragment.updateBranchTabStyles() {
    val activeColor   = android.graphics.Color.parseColor("#E8380D")
    val inactiveColor = context!!.getColor(R.color.theme_text_secondary)
    tabBranchConnected?.setTextColor(
        if (activeBranchTab == "connected") activeColor else inactiveColor
    )
    tabBranchUnconnected?.setTextColor(
        if (activeBranchTab == "unconnected") activeColor else inactiveColor
    )
}

internal fun ConfigSheetFragment.renderBranchSections(
    ctx: android.content.Context,
    connectedBranches: List<String>,
    unconnectedBranches: List<String>
) {
    val hasBoth = connectedBranches.isNotEmpty() && unconnectedBranches.isNotEmpty()

    // Show connected section
    val showConnected = connectedBranches.isNotEmpty() &&
        (!hasBoth || activeBranchTab == "connected")
    sectionConnected?.visibility = if (showConnected) View.VISIBLE else View.GONE

    if (showConnected) {
        containerConnectedBranches?.removeAllViews()

        // Single branch → auto expand on first load only
        // Multiple branches → all collapsed by default
        if (expandedBranch == null && connectedBranches.size == 1) {
            expandedBranch = connectedBranches.first()
        } else if (connectedBranches.size > 1 && expandedBranch != null &&
            !connectedBranches.contains(expandedBranch)) {
            expandedBranch = null
        }

        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        connectedBranches.forEach { branchId ->
            val connList   = connections[branchId] ?: emptyList()
            val isExpanded = expandedBranch == branchId

            // ── Outer branch card ──────────────────────────────────
            val branchCard = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                background  = resources.getDrawable(R.drawable.bg_card_rounded, null)
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 12.dp()
                layoutParams = lp
            }

            // ── Header row ─────────────────────────────────────────
            val headerRow = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity     = android.view.Gravity.CENTER_VERTICAL
                setPadding(16.dp(), 14.dp(), 12.dp(), 14.dp())
                isClickable = true
                isFocusable = true
            }

            val tvArrow = TextView(ctx).apply {
                text     = if (isExpanded) "▼" else "▶"
                textSize = 11f
                setTextColor(context!!.getColor(R.color.theme_text_secondary))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 10.dp() }
            }

            val headerCenter = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvBranchName = TextView(ctx).apply {
                text     = branchLabel(branchId)
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(context!!.getColor(R.color.theme_text_primary))
            }
            // Collapsed summary
            val tvSummary = TextView(ctx).apply {
                text      = "${connList.size} sheet${if (connList.size != 1) "s" else ""} connected"
                textSize  = 11f
                setTextColor(context!!.getColor(R.color.theme_text_secondary))
                visibility = if (isExpanded) android.view.View.GONE else android.view.View.VISIBLE
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 2.dp() }
            }
            headerCenter.addView(tvBranchName)
            headerCenter.addView(tvSummary)

            val btnNewSheet = TextView(ctx).apply {
                text      = "+ New Sheet"
                textSize  = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor("#3B82F6"))
                background = resources.getDrawable(R.drawable.bg_card_rounded, null)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    context!!.getColor(R.color.theme_bg_accent)
                )
                setPadding(16.dp(), 8.dp(), 16.dp(), 8.dp())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    activeBranch       = branchId
                    activeConnectionId = ""
                    selectedNickname   = ""
                    clearConnectForm()
                    screen      = ConfigScreen.CONNECTING
                    connectStep = 1
                    render()
                }
            }

            headerRow.addView(tvArrow)
            headerRow.addView(headerCenter)
            headerRow.addView(btnNewSheet)

            // ── Sheet sub-cards (visible when expanded) ────────────
            val sheetsContainer = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                visibility  = if (isExpanded) android.view.View.VISIBLE else android.view.View.GONE
                setPadding(12.dp(), 0, 12.dp(), 12.dp())
            }

            connList.forEach { conn ->
                val sheetCard = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    background  = resources.getDrawable(R.drawable.bg_input_rounded, null)
                    setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 8.dp() }
                }

                // Nickname — top identifier (bold)
                val tvNickname = TextView(ctx).apply {
                    text     = conn.nickname.ifBlank { conn.sheetName }
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(context!!.getColor(R.color.theme_text_primary))
                }

                // Sheet Name
                val tvSheetInfo = TextView(ctx).apply {
                    text = "Sheet Name: ${conn.sheetName}"
                    textSize = 11f
                    setTextColor(context!!.getColor(R.color.theme_text_secondary))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 4.dp() }
                }

                // Tab Name
                val tvTabInfo = TextView(ctx).apply {
                    text = "Tab Name: ${conn.tabName}"
                    textSize = 11f
                    setTextColor(context!!.getColor(R.color.theme_text_secondary))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 2.dp() }
                }

                // Range
                val startLetter = colIndexToLetter(conn.colStart)
                val endLetter   = colIndexToLetter(conn.colEnd)
                val sRow        = conn.startRow?.takeIf { it > 1 }
                val eRow        = conn.endRow?.takeIf   { it > 0 }
                val rangeText   = when {
                    sRow != null && eRow != null -> "$startLetter$sRow:$endLetter$eRow"
                    sRow != null                 -> "$startLetter$sRow:$endLetter"
                    else                         -> "$startLetter:$endLetter"
                }
                val tvRange = TextView(ctx).apply {
                    text     = "Current Range: $rangeText"
                    textSize = 11f
                    setTextColor(context!!.getColor(R.color.theme_text_secondary))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 2.dp() }
                }

                // ── Buttons row (Manage + Sync) ───────────────────
                val buttonsRow = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity     = android.view.Gravity.END
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 10.dp() }
                }

                val btnSync = android.widget.Button(ctx).apply {
                    text      = "🔄 Sync"
                    textSize  = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.parseColor("#2563EB"))
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        context!!.getColor(R.color.theme_bg_accent)
                    )
                    setPadding(20.dp(), 6.dp(), 20.dp(), 6.dp())
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 8.dp() }
                    setOnClickListener {
                        viewLifecycleOwner.lifecycleScope.launch {
                            syncSheetToFirebase(conn)
                        }
                    }
                }

                val btnManage = android.widget.Button(ctx).apply {
                    text      = "Manage"
                    textSize  = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.WHITE)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#16A34A")
                    )
                    setPadding(24.dp(), 6.dp(), 24.dp(), 6.dp())
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setOnClickListener {
                        activeBranch       = branchId
                        activeConnectionId = conn.connectionId
                        screen = ConfigScreen.MANAGING
                        render()
                    }
                }

                buttonsRow.addView(btnSync)
                buttonsRow.addView(btnManage)

                sheetCard.addView(tvNickname)
                sheetCard.addView(tvSheetInfo)
                sheetCard.addView(tvTabInfo)
                sheetCard.addView(tvRange)
                sheetCard.addView(buttonsRow)
                sheetsContainer.addView(sheetCard)
            }

            // ── Toggle click ───────────────────────────────────────
            headerRow.setOnClickListener {
                expandedBranch = if (isExpanded) null else branchId
                updateBranchSpinner()
            }

            branchCard.addView(headerRow)
            branchCard.addView(sheetsContainer)
            containerConnectedBranches?.addView(branchCard)
        }
    }

    // Show unconnected section
    val showUnconnected = unconnectedBranches.isNotEmpty() &&
        (!hasBoth || activeBranchTab == "unconnected")
    sectionUnconnected?.visibility = if (showUnconnected) View.VISIBLE else View.GONE

    // Always hide spinner/action card when not showing unconnected section
    if (!showUnconnected) {
        spinnerBranch?.visibility  = View.GONE
        tvSingleBranch?.visibility = View.GONE
        btnBranchAction?.visibility = View.GONE
        cardConnInfo?.visibility    = View.GONE
        cardBranchInfo?.visibility  = View.GONE
        return
    }

    if (showUnconnected) {
        if (unconnectedBranches.size == 1) {
            activeBranch = unconnectedBranches.first()
            spinnerBranch?.visibility  = View.GONE
            tvSingleBranch?.visibility = View.GONE
        } else {
            spinnerBranch?.visibility  = View.VISIBLE
            tvSingleBranch?.visibility = View.GONE
            val opts = listOf("শাখা বেছে নিন...") + unconnectedBranches.map { branchLabel(it) }
            spinnerBranch?.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, opts)
            val sel = unconnectedBranches.indexOf(activeBranch)
            if (sel >= 0) spinnerBranch?.setSelection(sel + 1)
        }
        updateBranchActionCard()
    }
}

internal fun ConfigSheetFragment.updateBranchActionCard() {
    val conn = connections[activeBranch]?.firstOrNull()
    if (activeBranch.isEmpty()) {
        btnBranchAction?.visibility = View.GONE
        cardConnInfo?.visibility    = View.GONE
        cardBranchInfo?.visibility  = View.GONE
        return
    }
    updateSelectedBranchInfo()
    btnBranchAction?.visibility = View.VISIBLE
    if (conn != null) {
        cardConnInfo?.visibility = View.VISIBLE
        tvConnInfoSheet?.text    = "📄 ${conn.sheetName}"
        tvConnInfoTab?.text      = "📑 Tab: ${conn.tabName}"
        tvConnInfoCols?.text     = "📊 Columns: ${conn.columns.firstOrNull() ?: "A"}–${conn.columns.lastOrNull() ?: "J"} (${conn.columns.size}টি)"
        btnBranchAction?.text    = "Manage"
        btnBranchAction?.setBackgroundColor(android.graphics.Color.parseColor("#16A34A"))
    } else {
        cardConnInfo?.visibility = View.GONE
        btnBranchAction?.text    = "Connect করুন"
        btnBranchAction?.setBackgroundColor(android.graphics.Color.parseColor("#E8380D"))
    }
}

internal fun ConfigSheetFragment.branchLabel(branchId: String): String {
    val info = branchInfos[branchId]
    val name = info?.name?.takeIf { it.isNotBlank() } ?: branchId
    val code = info?.code.orEmpty()
    return if (code.isBlank()) name else "$name ($code)"
}

internal fun ConfigSheetFragment.updateSelectedBranchInfo() {
    val info = branchInfos[activeBranch] ?: BranchInfo(id = activeBranch, name = activeBranch)
    cardBranchInfo?.visibility = View.VISIBLE
    tvBranchInfoName?.text = branchLabel(activeBranch)
    tvBranchInfoCode?.text = "Code: ${info.code.ifBlank { "N/A" }}"
    tvBranchInfoAddress?.text = "Address: ${info.address.ifBlank { "N/A" }}"
    tvBranchInfoType?.text = "Type: ${info.type.ifBlank { "N/A" }}"
    tvBranchInfoStatus?.text = "Status: ${info.status.ifBlank { "N/A" }}"
}


