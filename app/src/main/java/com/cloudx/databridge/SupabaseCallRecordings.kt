package com.cloudx.databridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * PostgREST read/write for public.call_recordings (manual call recordings on
 * parcel journeys). Same auth pattern as SupabaseClientManager.fetchValidations:
 * Firebase ID token as bearer, anon-role RLS branch policies gate rows.
 * R2 bytes are handled separately by [CallRecordingUploader]; this object only
 * stores the metadata row that points at the uploaded r2_key.
 */
object SupabaseCallRecordings {

    private const val TAG = "CallRecordings"
    private val jsonMedia = "application/json".toMediaType()

    data class Recording(
        val id: String,
        val consignment: String,
        val authorSystemId: String,
        val source: String,
        val r2Key: String,
        val durationSec: Int,
        val note: String,
        val createdAt: Long,
        val authorName: String = "",
        val authorPhotoUrl: String = "",
    )

    /** All recordings for one consignment, oldest first. Never throws — empty on failure. */
    suspend fun fetchForConsignment(consignmentId: String, screen: String): List<JSONObject> =
        get("consignment=eq.${enc(consignmentId)}&order=created_at.asc", screen)

    /**
     * Recordings in a Dhaka-day range (inclusive), oldest first, capped at
     * [limit] (Cleanup panel pages by day if it ever hits the cap).
     */
    suspend fun fetchRange(fromMs: Long, toMs: Long, limit: Int, screen: String): List<JSONObject> {
        val fromIso = java.time.Instant.ofEpochMilli(fromMs).toString()
        val toIso = java.time.Instant.ofEpochMilli(toMs).toString()
        return get(
            "created_at=gte.$fromIso&created_at=lte.$toIso&order=created_at.asc&limit=$limit",
            screen
        )
    }

    /** consignment → recording count for a batch of ids (ViewOrders card badges). */
    suspend fun fetchCounts(ids: List<String>, screen: String): Map<String, Int> {
        if (ids.isEmpty() || !SupabaseConfig.isConfigured) return emptyMap()
        val inList = ids.distinct().joinToString(",") { enc(it) }
        // Only the consignment column travels — tiny rows, safe for 50-card batches.
        val rows = get("consignment=in.($inList)&select=consignment", screen)
        return rows.groupingBy { it.optString("consignment") }.eachCount()
    }

    data class Stats(val count: Int, val bytes: Long, val oldestMs: Long, val newestMs: Long)

    /** Branch-wide totals for the Cleanup header (id + bytes + date only). */
    suspend fun fetchStats(screen: String): Stats {
        val rows = get("select=file_size_bytes,created_at&order=created_at.asc&limit=10000", screen)
        var bytes = 0L
        var oldest = 0L
        var newest = 0L
        for (r in rows) {
            bytes += r.optLong("file_size_bytes")
            val ms = SupabaseRemarkValidationWriter.parseCreatedAtMillis(r.optString("created_at"))
            if (ms > 0) {
                if (oldest == 0L || ms < oldest) oldest = ms
                if (ms > newest) newest = ms
            }
        }
        return Stats(rows.size, bytes, oldest, newest)
    }

    /**
     * Deletes one metadata row (RLS: branch-scoped, see 202609160002). The
     * caller deletes the R2 object first via AttachmentUploader.deleteObject
     * (best-effort); a row-delete failure must keep the row so the file never
     * becomes an invisible orphan.
     */
    suspend fun deleteRow(id: String, screen: String): Boolean = withContext(Dispatchers.IO) {
        if (id.isBlank() || !SupabaseConfig.isConfigured) return@withContext false
        val token = try { SupabaseClientManager.getAccessToken() } catch (_: Exception) { null }
            ?: return@withContext false
        try {
            val resp = SupabaseClientManager.httpClient.newCall(
                Request.Builder()
                    .url("${SupabaseConfig.PROJECT_URL}/rest/v1/call_recordings?id=eq.${enc(id)}")
                    .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .delete().build()
            ).execute()
            resp.use {
                if (!it.isSuccessful) {
                    Log.e(TAG, "delete HTTP ${it.code} ($screen)")
                    FirebaseErrorLogger.log(screen, "callrec_delete_http", "HTTP ${it.code}")
                    return@withContext false
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "delete failed ($screen)", e)
            FirebaseErrorLogger.log(screen, "callrec_delete_error", e.message ?: "failed")
            false
        }
    }

    private suspend fun get(query: String, screen: String): List<JSONObject> =
        withContext(Dispatchers.IO) {
            if (!SupabaseConfig.isConfigured) return@withContext emptyList()
            val token = try { SupabaseClientManager.getAccessToken() } catch (_: Exception) { null }
            if (token == null) return@withContext emptyList()
            val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/call_recordings?$query"
            try {
                val resp = SupabaseClientManager.httpClient.newCall(
                    Request.Builder().url(url)
                        .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Accept", "application/json")
                        .get().build()
                ).execute()
                resp.use {
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        Log.e(TAG, "fetch HTTP ${it.code} ($screen): ${text.take(300)}")
                        return@withContext emptyList()
                    }
                    val arr = JSONArray(text)
                    List(arr.length()) { i -> arr.getJSONObject(i) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetch failed ($screen)", e)
                FirebaseErrorLogger.log(screen, "callrec_fetch_error", e.message ?: "failed")
                emptyList()
            }
        }

    /**
     * Inserts one metadata row after a successful R2 upload. Returns the new
     * row id, or null on failure (caller keeps the local file so the agent
     * can retry — never silently drop a recording the agent chose to keep).
     */
    suspend fun insert(
        screen: String,
        consignment: String,
        authorSystemId: String,
        source: String,
        branchId: String,
        r2Key: String,
        durationSec: Int,
        fileSizeBytes: Long,
        note: String = "",
    ): String? = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext null
        val token = try { SupabaseClientManager.getAccessToken() } catch (_: Exception) { null }
            ?: return@withContext null
        val payload = JSONObject()
            .put("consignment", consignment)
            .put("author_system_id", authorSystemId)
            .put("source", if (source.equals("CC", ignoreCase = true)) "CC" else "WORKER")
            .put("branch_id", branchId)
            .put("r2_key", r2Key)
            .put("duration_sec", durationSec)
            .put("file_size_bytes", fileSizeBytes)
            .put("note", note)
            .toString()
        try {
            val resp = SupabaseClientManager.httpClient.newCall(
                Request.Builder()
                    .url("${SupabaseConfig.PROJECT_URL}/rest/v1/call_recordings")
                    .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=representation")
                    .post(payload.toRequestBody(jsonMedia)).build()
            ).execute()
            resp.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    Log.e(TAG, "insert HTTP ${it.code} ($screen): ${text.take(500)}")
                    FirebaseErrorLogger.log(screen, "callrec_insert_http",
                        "HTTP ${it.code}: ${text.take(300)}")
                    return@withContext null
                }
                runCatching { JSONArray(text).optJSONObject(0)?.optString("id") }
                    .getOrNull()?.takeIf { id -> id.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "insert failed ($screen)", e)
            FirebaseErrorLogger.log(screen, "callrec_insert_error", e.message ?: "failed")
            null
        }
    }

    /** Converts raw rows to timeline-ready [Recording]s (author labels resolved by caller). */
    fun toRecordings(
        rows: List<JSONObject>,
        nameOf: (systemId: String) -> String = { "" },
        photoOf: (systemId: String) -> String = { "" },
    ): List<Recording> = rows.mapNotNull { r ->
        val key = r.optString("r2_key").trim()
        if (key.isBlank()) return@mapNotNull null
        val sysId = r.optString("author_system_id").trim()
        Recording(
            id = r.optString("id"),
            consignment = r.optString("consignment"),
            authorSystemId = sysId,
            source = r.optString("source").trim().ifBlank { "WORKER" },
            r2Key = key,
            durationSec = r.optInt("duration_sec"),
            note = r.optString("note").trim(),
            createdAt = SupabaseRemarkValidationWriter.parseCreatedAtMillis(r.optString("created_at")),
            authorName = nameOf(sysId).ifBlank { sysId },
            authorPhotoUrl = photoOf(sysId),
        )
    }

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
}
