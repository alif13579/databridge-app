package com.cloudx.databridge

import com.google.firebase.auth.FirebaseAuth
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Accesses the remark audit log only through the Firebase-authenticated Edge Function. */
object SupabaseRemarkValidationWriter {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val jsonMediaType = "application/json".toMediaType()

    fun write(deliveryAgentId: String, verifierId: String, branchId: String, consignmentId: String,
              status: String, remarksText: String, screen: String) {
        if (deliveryAgentId.isBlank() || verifierId.isBlank() || branchId.isBlank() || consignmentId.isBlank()) {
            val missing = buildList {
                if (deliveryAgentId.isBlank()) add("deliveryAgentId")
                if (verifierId.isBlank()) add("verifierId")
                if (branchId.isBlank()) add("branchId")
                if (consignmentId.isBlank()) add("consignmentId")
            }.joinToString(",")
            log(screen, "supabase_validation_skip_missing_ids", "Missing required IDs: $missing", consignmentId); return
        }
        invoke(JSONObject().put("action", "write").put("row", JSONObject()
            .put("consignment_id", consignmentId).put("branch_id", branchId)
            .put("delivery_agent_id", deliveryAgentId).put("verifier_id", verifierId)
            .put("status", status).put("remarks", remarksText)), screen, "supabase_validation_write", consignmentId) { }
    }

    fun fetchHistory(consignmentId: String, screen: String, onResult: (List<JSONObject>) -> Unit) {
        if (consignmentId.isBlank()) return onResult(emptyList())
        invoke(JSONObject().put("action", "history").put("consignment_id", consignmentId), screen,
            "supabase_validation_fetch_history", consignmentId) { onResult(rows(it)) }
    }

    fun fetchTodayForDeliveryAgent(deliveryAgentId: String, screen: String, onResult: (List<JSONObject>) -> Unit) {
        if (deliveryAgentId.isBlank()) return onResult(emptyList())
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toString()
        invoke(JSONObject().put("action", "today").put("delivery_agent_id", deliveryAgentId).put("start_iso", start),
            screen, "supabase_validation_fetch_today", deliveryAgentId) { onResult(rows(it)) }
    }

    fun fetchForDeliveryAgentInRange(deliveryAgentId: String, rangeStartMs: Long, rangeEndMs: Long,
                                     screen: String, onResult: (List<JSONObject>) -> Unit) {
        if (deliveryAgentId.isBlank()) return onResult(emptyList())
        invoke(JSONObject().put("action", "agent_range").put("delivery_agent_id", deliveryAgentId)
            .put("start_iso", Instant.ofEpochMilli(rangeStartMs).toString())
            .put("end_iso", Instant.ofEpochMilli(rangeEndMs).toString()), screen,
            "supabase_validation_fetch_range", deliveryAgentId) { onResult(rows(it)) }
    }

    fun fetchNewRemarksSince(consignmentIds: List<String>, sinceEpochMs: Long, screen: String,
                             onResult: (List<JSONObject>) -> Unit) {
        if (consignmentIds.isEmpty()) return onResult(emptyList())
        val allRows = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        val chunks = consignmentIds.distinct().chunked(200)
        val remaining = java.util.concurrent.atomic.AtomicInteger(chunks.size)
        chunks.forEach { chunk ->
            invoke(JSONObject().put("action", "new_since").put("consignment_ids", JSONArray(chunk))
                .put("since_iso", Instant.ofEpochMilli(sinceEpochMs).toString()), screen,
                "supabase_validation_fetch_new_since", "") {
                allRows.addAll(rows(it)); if (remaining.decrementAndGet() == 0) onResult(allRows.toList())
            }
        }
    }

    private fun invoke(payload: JSONObject, screen: String, action: String, reference: String,
                       onResult: (String?) -> Unit) {
        if (!SupabaseConfig.isConfigured) {
            log(screen, "${action}_skip_not_configured", "SUPABASE_URL/SUPABASE_PUBLISHABLE_KEY are not configured", reference)
            onResult(null); return
        }
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) { log(screen, "${action}_skip_not_signed_in", "No Firebase user", reference); onResult(null); return }
        user.getIdToken(false).addOnCompleteListener { tokenTask ->
            val token = tokenTask.result?.token
            if (!tokenTask.isSuccessful || token.isNullOrBlank()) {
                log(screen, "${action}_token_error", tokenTask.exception?.message ?: "No Firebase ID token", reference)
                onResult(null); return@addOnCompleteListener
            }
            val request = Request.Builder().url("${SupabaseConfig.PROJECT_URL}/functions/v1/remark-validations")
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY).addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json").post(payload.toString().toRequestBody(jsonMediaType)).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    log(screen, "${action}_network_error", e.message ?: "Network error", reference); onResult(null)
                }
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        if (it.isSuccessful) onResult(text) else {
                            log(screen, "${action}_http_error", "HTTP ${it.code}: ${text.take(500)}", reference); onResult(null)
                        }
                    }
                }
            })
        }
    }

    private fun rows(json: String?): List<JSONObject> = try {
        val array = JSONArray(json ?: "[]"); List(array.length()) { array.getJSONObject(it) }
    } catch (_: Exception) { emptyList() }

    private fun log(screen: String, action: String, error: String, reference: String) = FirebaseErrorLogger.log(
        screen = screen, action = action, errorMessage = error,
        extra = if (reference.isBlank()) emptyMap() else mapOf("reference" to reference)
    )
}
