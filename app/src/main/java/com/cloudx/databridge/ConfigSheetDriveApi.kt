package com.cloudx.databridge

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Pure HTTP helpers for Google Drive v3 and Sheets v4 APIs.
 *
 * All functions are blocking (intended for `withContext(Dispatchers.IO)`).
 * No Android or Firebase dependencies — easy to unit-test.
 */
object ConfigSheetDriveApi {

    const val SCOPE_DRIVE  = "https://www.googleapis.com/auth/drive.readonly"
    const val SCOPE_SHEETS = "https://www.googleapis.com/auth/spreadsheets.readonly"
    const val OAUTH_SCOPE  = "oauth2:$SCOPE_DRIVE $SCOPE_SHEETS"

    // Write-capable scopes — needed by the Scanner Sheet Connector (writes scanned values
    // into cells), distinct from the readonly scopes above used by ConfigSheetFragment's
    // sync-only connector. drive.file (not full drive.readonly) is intentional: it only grants
    // access to files the app itself created or that the user explicitly opened/picked via the
    // Drive file picker — a narrower, more reviewable scope than full Drive read access.
    const val SCOPE_DRIVE_FILE    = "https://www.googleapis.com/auth/drive.file"
    const val SCOPE_SHEETS_WRITE  = "https://www.googleapis.com/auth/spreadsheets"
    const val OAUTH_SCOPE_WRITE   = "oauth2:$SCOPE_DRIVE_FILE $SCOPE_SHEETS_WRITE"

    /**
     * Fetches the user's spreadsheets from Google Drive, ordered by most recently modified.
     * Throws [IOException] on HTTP error.
     */
    fun fetchDriveSpreadsheets(accessToken: String, httpClient: OkHttpClient): List<DriveFile> {
        val url = "https://www.googleapis.com/drive/v3/files" +
                "?q="       + java.net.URLEncoder.encode("mimeType='application/vnd.google-apps.spreadsheet' and trashed=false", "UTF-8") +
                "&fields="  + java.net.URLEncoder.encode("files(id,name)", "UTF-8") +
                "&pageSize=200" +
                "&orderBy=" + java.net.URLEncoder.encode("modifiedTime desc", "UTF-8")

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Drive API ${resp.code}")
            val body = resp.body?.string() ?: return emptyList()
            val files = JSONObject(body).optJSONArray("files") ?: return emptyList()
            return buildList {
                for (i in 0 until files.length()) {
                    val f    = files.getJSONObject(i)
                    val id   = f.optString("id", "")
                    val name = f.optString("name", "")
                    if (id.isNotEmpty()) add(DriveFile(id, name))
                }
            }
        }
    }

    /**
     * Fetches the tab/sheet names from a Google Spreadsheet.
     * Throws [IOException] on HTTP error.
     */
    fun fetchSheetTabs(accessToken: String, sheetId: String, httpClient: OkHttpClient): List<String> {
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$sheetId" +
                "?fields=" + java.net.URLEncoder.encode("sheets(properties(title))", "UTF-8")

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Sheets API ${resp.code}")
            val body = resp.body?.string() ?: return emptyList()
            val arr  = JSONObject(body).optJSONArray("sheets") ?: return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val title = arr.optJSONObject(i)
                        ?.optJSONObject("properties")
                        ?.optString("title", "")
                        ?: ""
                    if (title.isNotEmpty()) add(title)
                }
            }
        }
    }

    /**
     * Fetches a single column's values from a tab, e.g. to scan Column T (20) for a matching
     * employee_id. Returns values in row order starting at row 1 — index 0 == row 1, so
     * `values[i]` corresponds to sheet row `i + 1`. Blank/missing cells before the last
     * non-blank one come back as empty strings (Sheets omits trailing blank cells from a
     * column range response, but not interior ones).
     * Throws [IOException] on HTTP error.
     */
    fun fetchColumnValues(
        accessToken: String,
        sheetId: String,
        tabName: String,
        columnLetter: String,
        httpClient: OkHttpClient
    ): List<String> {
        val range = java.net.URLEncoder.encode("$tabName!$columnLetter:$columnLetter", "UTF-8")
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$sheetId/values/$range"

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Sheets API ${resp.code}: ${resp.body?.string()}")
            val body = resp.body?.string() ?: return emptyList()
            val rows = JSONObject(body).optJSONArray("values") ?: return emptyList()
            return buildList {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONArray(i)
                    add(row?.optString(0, "") ?: "")
                }
            }
        }
    }

    /**
     * Writes a single value into one cell, e.g. Column K (11) at a specific row. 1-indexed
     * row number, matching how rows are numbered in the Sheets UI itself.
     * Uses valueInputOption=RAW — the value is stored exactly as given, no formula parsing
     * or auto-formatting (e.g. a numeric-looking consignment ID won't get reformatted).
     * Throws [IOException] on HTTP error.
     */
    fun writeCellValue(
        accessToken: String,
        sheetId: String,
        tabName: String,
        columnLetter: String,
        row: Int,
        value: String,
        httpClient: OkHttpClient
    ) {
        val cellRef = "$tabName!$columnLetter$row"
        val encodedRange = java.net.URLEncoder.encode(cellRef, "UTF-8")
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$sheetId/values/$encodedRange" +
                "?valueInputOption=RAW"

        val payload = JSONObject().apply {
            put("range", cellRef)
            put("majorDimension", "ROWS")
            put("values", org.json.JSONArray().put(org.json.JSONArray().put(value)))
        }

        val body = payload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .put(body)
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Sheets write API ${resp.code}: ${resp.body?.string()}")
        }
    }

    /**
     * Appends a new row at the end of a tab, writing `columnLetter` -> value at whatever row
     * Sheets decides is "the end" (respects existing data, won't overwrite). Used when every
     * existing row for an agent already has data in the target column — see the scanner
     * connector's "no blank slot" fallback.
     * Returns the 1-indexed row number that was actually written to, parsed from the API's
     * returned updatedRange (e.g. "Day 16!K47" -> 47) — the caller needs this to also write
     * the matching employee_id into the match column at the SAME row.
     * Throws [IOException] on HTTP error, or [IllegalStateException] if the row number
     * couldn't be parsed from the response.
     */
    fun appendRowValue(
        accessToken: String,
        sheetId: String,
        tabName: String,
        columnLetter: String,
        value: String,
        httpClient: OkHttpClient
    ): Int {
        val range = java.net.URLEncoder.encode("$tabName!$columnLetter:$columnLetter", "UTF-8")
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$sheetId/values/$range:append" +
                "?valueInputOption=RAW&insertDataOption=INSERT_ROWS"

        val payload = JSONObject().apply {
            put("majorDimension", "ROWS")
            put("values", org.json.JSONArray().put(org.json.JSONArray().put(value)))
        }

        val body = payload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Sheets append API ${resp.code}: ${resp.body?.string()}")
            val respBody = resp.body?.string() ?: throw IllegalStateException("Empty append response")
            val updatedRange = JSONObject(respBody).optJSONObject("updates")?.optString("updatedRange", "")
                ?: throw IllegalStateException("No updatedRange in append response")
            // e.g. "Day 16!K47" or "Day 16!K47:K47" -> extract the row number after the column letters
            val rowMatch = Regex("![A-Z]+(\\d+)").find(updatedRange)
                ?: throw IllegalStateException("Could not parse row number from '$updatedRange'")
            return rowMatch.groupValues[1].toInt()
        }
    }
}
