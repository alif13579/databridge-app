package com.cloudx.databridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * Supabase reads + authoritative writes for the generic Check-In log
 * (public.check_ins) — hub van arrival/departure timestamps today, more
 * subject kinds later. Identity is system_id-only (users lookups key on
 * system_id, so no uid is stored anywhere here).
 *
 * Reads are direct PostgREST (free); writes go through the
 * check-ins Edge Function's checkin / checkout actions
 * (service-role admin client — same posture as SupabaseClaimsWriter.save:
 * throws on any failure, callers surface it via runCatching).
 */
object SupabaseVanMovements {
    private const val TAG = "SupabaseVanMovements"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun String.encodeParam(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private fun isoToMillis(value: String): Long =
        SupabaseRemarkValidationWriter.parseDbTimestampMillis(value)

    /** Operations day follows Bangladesh time regardless of the phone's zone. */
    fun bangladeshTodayStartIso(): String = java.time.LocalDate
        .now(ZoneId.of("Asia/Dhaka"))
        .atStartOfDay(ZoneId.of("Asia/Dhaka"))
        .toInstant().toString()

    /**
     * Still-inside rows for [branchId] — check_out_at NULL, any day (a van
     * checked in yesterday and never checked out must still show as inside).
     * Newest check-in first. Throws on failure.
     */
    suspend fun fetchOpen(branchId: String): List<VanMovement> = withContext(Dispatchers.IO) {
        require(branchId.isNotBlank()) { "A branch is required" }
        val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/check_ins" +
            "?select=*&branch_id=eq.${branchId.encodeParam()}" +
            "&check_out_at=is.null" +
            "&order=check_in_at.desc"
        fetchList(url, token)
    }

    /**
     * Completed visits for [branchId], newest check-in first, paged.
     * [limit]/[offset] drive the See-more pagination; pass limit+1 from the
     * caller is unnecessary — hasMore is derived by the fragment requesting
     * one extra row (see CheckInFragment.HISTORY_PAGE_SIZE usage). Throws.
     */
    suspend fun fetchHistory(branchId: String, limit: Int, offset: Int): List<VanMovement> =
        withContext(Dispatchers.IO) {
            require(branchId.isNotBlank()) { "A branch is required" }
            val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
            val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/check_ins" +
                "?select=*&branch_id=eq.${branchId.encodeParam()}" +
                "&check_out_at=not.is.null" +
                "&order=check_in_at.desc" +
                "&limit=$limit&offset=$offset"
            fetchList(url, token)
        }

    private fun fetchList(url: String, token: String): List<VanMovement> {
        val response = SupabaseClientManager.httpClient.newCall(
            Request.Builder().url(url)
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get().build()
        ).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                Log.e(TAG, "fetchList HTTP ${it.code}: ${text.take(1_000)}")
                error("Couldn't load van movements: HTTP ${it.code}")
            }
            val arr = JSONArray(text)
            return List(arr.length()) { i ->
                val row = arr.getJSONObject(i)
                VanMovement(
                    id = row.optString("id"),
                    branchId = row.optString("branch_id"),
                    vehicleNumber = row.optString("subject_label"),
                    vehicleType = row.optString("vehicle_type"),
                    driverName = row.optString("driver_name"),
                    checkInAt = isoToMillis(row.optString("check_in_at").ifBlank { row.optString("created_at") }),
                    checkOutAt = isoToMillis(row.optString("check_out_at")),
                    note = row.optString("note")
                )
            }
        }
    }

    /**
     * Today's movements for [branchId], plus any still-inside rows from
     * earlier days (a van checked in yesterday and never checked out must
     * still show as inside). Newest check-in first. Throws on failure —
     * the fragment surfaces it as an error state, same as PettyCashViewModel.
     *
     * Kept for callers that need one combined list; the fragment itself reads
     * [fetchOpen] + [fetchHistory] separately so a checked-out row can never
     * hide in (or linger from) a combined date filter.
     */
    suspend fun fetchMovements(branchId: String): List<VanMovement> = withContext(Dispatchers.IO) {
        require(branchId.isNotBlank()) { "A branch is required" }
        val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/check_ins" +
            "?select=*&branch_id=eq.${branchId.encodeParam()}" +
            "&or=(check_in_at.gte.${bangladeshTodayStartIso().encodeParam()},check_out_at.is.null)" +
            "&order=check_in_at.desc"
        fetchList(url, token)
    }

    /** Opens a movement row (van arrival). [checkInAtMillis] defaults to now —
     *  pass an earlier instant for a late-tapped backdate (never the future;
     *  the server rejects that too). Throws on failure — including the
     *  server's 409 when this van is already inside. Returns the movement id. */
    suspend fun checkIn(
        branchId: String,
        vehicleNumber: String,
        vehicleType: String,
        driverName: String = "",
        note: String = "",
        checkInAtMillis: Long = System.currentTimeMillis(),
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("action", "checkin")
            .put("branch_id", branchId)
            .put("subject_type", "van")
            .put("subject_label", vehicleNumber)
            .put("vehicle_type", vehicleType)
            .put("driver_name", driverName)
            .put("note", note)
            .put("check_in_at", java.time.Instant.ofEpochMilli(checkInAtMillis).toString())
        val text = postAction(body, "checkin")
        JSONObject(text).optString("movement_id")
    }

    /** Stamps departure on an open row. [checkOutAtMillis] defaults to now —
     *  pass an earlier instant for a late-tapped correction (never before the
     *  row's own check-in, never the future; the server enforces both).
     *  Idempotent server-side (double-tap reports ok-already, never errors).
     *  Throws on real failure. */
    suspend fun checkOut(movementId: String, checkOutAtMillis: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("action", "checkout")
            .put("movement_id", movementId)
            .put("check_out_at", java.time.Instant.ofEpochMilli(checkOutAtMillis).toString())
        postAction(body, "checkout")
    }

    private suspend fun postAction(body: JSONObject, logTag: String): String = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) error("Supabase is not configured")
        val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
        val request = Request.Builder()
            .url("${SupabaseConfig.PROJECT_URL}/functions/v1/check-ins")
            .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        val text: String
        SupabaseClientManager.httpClient.newCall(request).execute().use { response ->
            text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.e(TAG, "$logTag HTTP ${response.code}: ${text.take(500)}")
                val reason = runCatching { JSONObject(text).optString("error") }.getOrDefault("").ifBlank { "HTTP ${response.code}" }
                error(reason)
            }
        }
        text
    }
}
