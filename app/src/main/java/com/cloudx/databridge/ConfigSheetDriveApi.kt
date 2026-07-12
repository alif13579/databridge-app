package com.cloudx.databridge

import okhttp3.OkHttpClient
import okhttp3.Request
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
}
