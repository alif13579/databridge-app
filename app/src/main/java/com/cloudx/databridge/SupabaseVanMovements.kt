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
 * Supabase reads + authoritative writes for the Van Check-In log
 * (public.van_movements) — hub van arrival/departure timestamps.
 *
 * Reads are direct PostgREST (free); writes go through the
 * remark-validations Edge Function's van_checkin / van_checkout actions
 * (service-role admin client — same posture as SupabaseClaimsWriter.save:
 * throws on any failure, callers surface it via runCatching).
 */
object SupabaseVanMovements {
    private const val TAG = "SupabaseVanMovements"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun String.encodeParam(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private fun isoToMillis(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
    }

    /** Operations day follows Bangladesh time regardless of the phone's zone. */
    fun bangladeshTodayStartIso(): String = java.time.LocalDate
        .now(ZoneId.of("Asia/Dhaka"))
        .atStartOfDay(ZoneId.of("Asia/Dhaka"))
        .toInstant().toString()

    /**
     * Today's movements for [branchId], plus any still-inside rows from
     * earlier days (a van checked in yesterday and never checked out must
     * still show as inside). Newest check-in first. Throws on failure —
     * the fragment surfaces it as an error state, same as PettyCashViewModel.
     */
    suspend fun fetchMovements(branchId: String): List<VanMovement> = withContext(Dispatchers.IO) {
        require(branchId.isNotBlank()) { "A branch is required" }
        val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/van_movements" +
            "?select=*&branch_id=eq.${branchId.encodeParam()}" +
            "&or=(check_in_at.gte.${bangladeshTodayStartIso().encodeParam()},check_out_at.is.null)" +
            "&order=check_in_at.desc"
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
                Log.e(TAG, "fetchMovements HTTP ${it.code}: ${text.take(1_000)}")
                error("Couldn't load van movements: HTTP ${it.code}")
            }
            val arr = JSONArray(text)
            List(arr.length()) { i ->
                val row = arr.getJSONObject(i)
                VanMovement(
                    id = row.optString("id"),
                    branchId = row.optString("branch_id"),
                    vehicleNumber = row.optString("vehicle_number"),
                    vehicleType = row.optString("vehicle_type"),
                    driverName = row.optString("driver_name"),
                    checkInAt = isoToMillis(row.optString("check_in_at").ifBlank { row.optString("created_at") }),
                    checkOutAt = isoToMillis(row.optString("check_out_at")),
                    note = row.optString("note")
                )
            }
        }
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
            .put("action", "van_checkin")
            .put("branch_id", branchId)
            .put("vehicle_number", vehicleNumber)
            .put("vehicle_type", vehicleType)
            .put("driver_name", driverName)
            .put("note", note)
            .put("check_in_at", java.time.Instant.ofEpochMilli(checkInAtMillis).toString())
        val text = postAction(body, "van_checkin")
        JSONObject(text).optString("movement_id")
    }

    /** Stamps departure on an open row. [checkOutAtMillis] defaults to now —
     *  pass an earlier instant for a late-tapped correction (never before the
     *  row's own check-in, never the future; the server enforces both).
     *  Idempotent server-side (double-tap reports ok-already, never errors).
     *  Throws on real failure. */
    suspend fun checkOut(movementId: String, checkOutAtMillis: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("action", "van_checkout")
            .put("movement_id", movementId)
            .put("check_out_at", java.time.Instant.ofEpochMilli(checkOutAtMillis).toString())
        postAction(body, "van_checkout")
    }

    private suspend fun postAction(body: JSONObject, logTag: String): String = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) error("Supabase is not configured")
        val token = SupabaseClientManager.getAccessToken() ?: error("Not signed in")
        val request = Request.Builder()
            .url("${SupabaseConfig.PROJECT_URL}/functions/v1/remark-validations")
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
