package com.cloudx.databridge

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Writes remarks to Supabase's remark_validations table (see
 * supabase_remark_validations_schema.sql for the full schema).
 *
 * Replaces the earlier Firebase-based courier/remarks_by_consignment +
 * courier/remarks_by_userId paths — per Alif's decision, JSON-tree storage
 * made branch+date-range reporting and export too hard (no real
 * filtering/aggregation without scanning and reshaping client-side), so
 * this data now lives in Supabase/Postgres instead, where a report is
 * just a SQL query.
 *
 * Alif is deleting the old Firebase remark data separately with no
 * backfill, so as of this migration those two Firebase paths are no
 * longer written to by the app at all (existing historical data there is
 * simply orphaned, not migrated).
 *
 * Standard append-only table: every remark is its own row (plain INSERT,
 * no upsert/dedup). First status, final status, per-agent counts, and full
 * per-consignment timelines are all just SQL queries against this table on
 * the reporting side — nothing is pre-aggregated at write time, per
 * Alif's explicit call (an earlier first/last-columns-with-upsert design
 * was reconsidered as working against the whole point of moving to SQL).
 *
 * IDs are always system_id (users/{uid}/profile/company_info/system_id),
 * never the Firebase Auth uid — per Alif, system_id is mandatory on every
 * profile. Missing/blank IDs skip the write entirely (logged via
 * FirebaseErrorLogger, kept as the error-logging destination since that
 * part of the data model didn't change) rather than sending a
 * partial/"unknown" row that would violate the table's NOT NULL columns
 * anyway.
 */
object SupabaseRemarkValidationWriter {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    /**
     * Inserts one remark_validations row.
     *
     * @param deliveryAgentId system_id of the agent the parcel is currently
     *   assigned to (CallCenterParcelItem.workerSystemId on the CC side;
     *   the signed-in worker's own system_id on the Worker side).
     * @param verifierId system_id of the person saving this remark.
     * @param branchId the branch the delivery agent is working out of TODAY.
     * @param consignmentId the parcel this remark is for.
     * @param status the status being recorded.
     * @param remarksText the remark text (status label, or note if blank).
     * @param screen caller identity for FirebaseErrorLogger context only.
     */
    fun write(
        deliveryAgentId: String,
        verifierId: String,
        branchId: String,
        consignmentId: String,
        status: String,
        remarksText: String,
        screen: String
    ) {
        if (deliveryAgentId.isBlank() || verifierId.isBlank() || branchId.isBlank() || consignmentId.isBlank()) {
            FirebaseErrorLogger.log(
                screen = screen,
                action = "supabase_validation_skip_missing_ids",
                errorMessage = "deliveryAgentId=$deliveryAgentId verifierId=$verifierId branchId=$branchId consignmentId=$consignmentId",
                extra = mapOf("consignmentId" to consignmentId)
            )
            return
        }
        if (!SupabaseConfig.isConfigured) {
            FirebaseErrorLogger.log(
                screen = screen,
                action = "supabase_validation_skip_not_configured",
                errorMessage = "SupabaseConfig.PROJECT_URL/ANON_KEY are still placeholders",
                extra = mapOf("consignmentId" to consignmentId)
            )
            return
        }

        val row = JSONObject().apply {
            put("consignment_id", consignmentId)
            put("branch_id", branchId)
            put("delivery_agent_id", deliveryAgentId)
            put("verifier_id", verifierId)
            put("status", status)
            put("remarks", remarksText)
        }
        val body = row.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("${SupabaseConfig.PROJECT_URL}/rest/v1/remark_validations")
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${SupabaseConfig.ANON_KEY}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                FirebaseErrorLogger.log(
                    screen = screen, action = "supabase_validation_write_network_error",
                    errorMessage = e.message ?: "unknown",
                    extra = mapOf("consignmentId" to consignmentId)
                )
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        FirebaseErrorLogger.log(
                            screen = screen, action = "supabase_validation_write_http_error",
                            errorMessage = "HTTP ${it.code}: ${it.body?.string().orEmpty().take(500)}",
                            extra = mapOf("consignmentId" to consignmentId)
                        )
                    }
                }
            }
        })
    }

    /**
     * Fetches the full remark timeline for one consignment, newest first —
     * used to render remark history in the Call Center / Worker parcel
     * card UI, which previously read this from Firebase's
     * courier/remarks_by_consignment (no longer written to, see class doc).
     *
     * @param onResult called on a background thread with the list of remark
     *   rows (each a JSONObject with consignment_id/branch_id/
     *   delivery_agent_id/verifier_id/status/remarks/created_at), or an
     *   empty list on any failure (failures are logged, not surfaced to
     *   the caller as an exception — callers should treat "no history"
     *   and "fetch failed" the same way in the UI: show nothing rather
     *   than block on a retry).
     */
    fun fetchHistory(consignmentId: String, screen: String, onResult: (List<JSONObject>) -> Unit) {
        if (consignmentId.isBlank() || !SupabaseConfig.isConfigured) {
            onResult(emptyList())
            return
        }

        val request = Request.Builder()
            .url(
                "${SupabaseConfig.PROJECT_URL}/rest/v1/remark_validations" +
                    "?consignment_id=eq.$consignmentId&order=created_at.desc"
            )
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${SupabaseConfig.ANON_KEY}")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                FirebaseErrorLogger.log(
                    screen = screen, action = "supabase_validation_fetch_history_network_error",
                    errorMessage = e.message ?: "unknown",
                    extra = mapOf("consignmentId" to consignmentId)
                )
                onResult(emptyList())
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        FirebaseErrorLogger.log(
                            screen = screen, action = "supabase_validation_fetch_history_http_error",
                            errorMessage = "HTTP ${it.code}: ${it.body?.string().orEmpty().take(500)}",
                            extra = mapOf("consignmentId" to consignmentId)
                        )
                        onResult(emptyList())
                        return
                    }
                    val text = it.body?.string().orEmpty()
                    try {
                        val arr = org.json.JSONArray(text)
                        val list = (0 until arr.length()).map { i -> arr.getJSONObject(i) }
                        onResult(list)
                    } catch (e: Exception) {
                        FirebaseErrorLogger.log(
                            screen = screen, action = "supabase_validation_fetch_history_parse_error",
                            errorMessage = e.message ?: "unknown",
                            extra = mapOf("consignmentId" to consignmentId)
                        )
                        onResult(emptyList())
                    }
                }
            }
        })
    }
}
