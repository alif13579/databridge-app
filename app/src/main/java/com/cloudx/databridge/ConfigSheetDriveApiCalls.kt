package com.cloudx.databridge

import android.view.View
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ConfigSheetFragment's Google Sheets/Drive API calls — Steps 2 ("Sheet") and 3 ("Tab")
 * of the Connect wizard. Fetches the signed-in account's spreadsheet list, then the tab
 * (sheet-within-spreadsheet) list once one is picked.
 *
 * Extracted from ConfigSheetFragment.kt as part of breaking that ~4500-line file into
 * modules. Written as extension functions on ConfigSheetFragment for the same reason as
 * the other Config-Sheet module splits — availableSheets/availableTabs/selectedSheet
 * etc. are read from other wizard steps too.
 *
 * loadTabsForSheet() was updated during this extraction to call
 * GoogleSignInHelper.fetchAccessToken() (like loadSheetsForAccount() already did) instead
 * of an inline GoogleAuthUtil.getToken() call — this was one of the ~7 inline
 * token-fetch call sites noted as deferred when GoogleSignInHelper.kt was first
 * introduced; addressing it now that it lands in this module's natural context.
 */
// ── Drive API: list user's spreadsheets ──────────────────────────
internal fun ConfigSheetFragment.loadSheetsForAccount() {
    val account = googleAccount ?: return
    viewLifecycleOwner.lifecycleScope.launch {
        try {
            pbSheetLoad?.visibility = View.VISIBLE
            val token = GoogleSignInHelper.fetchAccessToken(
                context = requireContext(),
                fragment = this@ConfigSheetFragment,
                account = account,
                scope = ConfigSheetDriveApi.OAUTH_SCOPE,
                recoverableLauncher = recoverableLauncher,
                onError = { msg -> showErr(msg) }
            ) ?: return@launch
            val sheets = withContext(Dispatchers.IO) { fetchDriveSpreadsheets(token) }
            availableSheets = sheets
            if (connectStep == 2 || stepView2?.visibility == View.VISIBLE) updateSheetPickerLabel()
        } catch (e: Exception) {
            showErr("Sheet load failed: ${e.message ?: "unknown"}")
        } finally {
            pbSheetLoad?.visibility = View.GONE
        }
    }
}

// fetchDriveSpreadsheets → ConfigSheetDriveApi
internal fun ConfigSheetFragment.fetchDriveSpreadsheets(accessToken: String) =
    ConfigSheetDriveApi.fetchDriveSpreadsheets(accessToken, httpClient)

internal fun ConfigSheetFragment.updateSheetPickerLabel() {
    tvSelectedSheet?.text = selectedSheet?.name ?: "— Sheet বেছে নিন —"
    tvSelectedSheet?.setTextColor(
        android.graphics.Color.parseColor(if (selectedSheet != null) "#111827" else "#6B7280")
    )
}

internal fun ConfigSheetFragment.openSheetPickerDialog() {
    val ctx = context ?: return
    if (availableSheets.isEmpty()) {
        toast("Sheet লোড হচ্ছে, একটু অপেক্ষা করুন")
        return
    }
    SheetPickerDialog.show(ctx, availableSheets, selectedSheet) { sheet ->
        if (selectedSheet?.id != sheet.id) {
            selectedSheet = sheet
            selectedTab   = ""
            availableTabs = emptyList()
            updateSheetPickerLabel()
            loadTabsForSheet()
        } else {
            updateSheetPickerLabel()
        }
    }
}

internal fun ConfigSheetFragment.loadTabsForSheet() {
    val account = googleAccount ?: return
    val sheet   = selectedSheet ?: return
    viewLifecycleOwner.lifecycleScope.launch {
        try {
            pbTabLoad?.visibility = View.VISIBLE
            val token = GoogleSignInHelper.fetchAccessToken(
                context = requireContext(),
                fragment = this@ConfigSheetFragment,
                account = account,
                scope = ConfigSheetDriveApi.OAUTH_SCOPE,
                recoverableLauncher = recoverableLauncher,
                onError = { msg -> showErr(msg) }
            ) ?: return@launch
            val tabs = withContext(Dispatchers.IO) { fetchSheetTabs(token, sheet.id) }
            availableTabs = tabs
            if (connectStep == 3 || stepView3?.visibility == View.VISIBLE) updateTabSpinner()
        } catch (e: Exception) {
            showErr("Tab load failed: ${e.message ?: "unknown"}")
        } finally {
            pbTabLoad?.visibility = View.GONE
        }
    }
}

// fetchSheetTabs → ConfigSheetDriveApi
internal fun ConfigSheetFragment.fetchSheetTabs(accessToken: String, sheetId: String) =
    ConfigSheetDriveApi.fetchSheetTabs(accessToken, sheetId, httpClient)

internal fun ConfigSheetFragment.updateTabSpinner() {
    val ctx = context ?: return
    val opts = listOf("— Tab বেছে নিন —") + availableTabs
    spinnerTab?.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, opts)
    val sel = availableTabs.indexOf(selectedTab)
    if (sel >= 0) spinnerTab?.setSelection(sel + 1) else spinnerTab?.setSelection(0)
}

