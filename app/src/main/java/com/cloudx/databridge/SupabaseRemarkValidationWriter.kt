package com.cloudx.databridge

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** Accesses the remark audit log only through the Firebase-authenticated Edge Function. */
object SupabaseRemarkValidationWriter {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val jsonMediaType = "application/json".toMediaType()

    fun write(assignedAgentSystemId: String, branchId: String, consignmentId: String,
              status: String, remarksText: String, noteText: String = "", source: String,
              screen: String) {
        if (assignedAgentSystemId.isBlank() || branchId.isBlank() || consignmentId.isBlank()) {
            val missing = buildList {
                if (assignedAgentSystemId.isBlank()) add("assignedAgentSystemId")
                if (branchId.isBlank()) add("branchId")
                if (consignmentId.isBlank()) add("consignmentId")
            }.joinToString(",")
            log(screen, "supabase_validation_skip_missing_ids", "Missing required IDs: $missing", consignmentId); return
        }
        invoke(JSONObject().put("action", "write").put("row", JSONObject()
            .put("consignment", consignmentId).put("branch_id", branchId)
            .put("assigned_to_system_id", assignedAgentSystemId)
            .put("source", source)
            .put("remarks_status", status).put("remarks", remarksText).put("note", noteText)), screen,
            "supabase_validation_write", consignmentId) { }
    }

    /** Associates the current signed-in user and this app installation's FCM token server-side. */
    fun registerPushToken(token: String) {
        if (token.isBlank()) return
        invoke(
            JSONObject().put("action", "register_push_token").put("token", token),
            screen = "PushNotifications",
            action = "push_token_register",
            reference = ""
        ) { }
    }

    /**
     * Creates/refreshes the caller's trusted Supabase user row before any validation read.
     * A worker may receive a Call Center remark before writing their own; without this,
     * validations RLS has no Firebase UID/branch mapping and correctly returns no rows.
     */
    fun syncCurrentUserProfile(onComplete: (String?) -> Unit = {}) {
        invoke(
            JSONObject().put("action", "sync_profile"),
            screen = "SupabaseProfileSync",
            action = "supabase_profile_sync",
            reference = ""
        ) { response ->
            Log.i("SupabaseProfileSync", "sync_profile response=${response?.take(300) ?: "FAILED"}")
            onComplete(response)
        }
    }

    /** Parses Supabase/PostgREST timestamp output without turning a parse failure into 1970. */
    fun parseCreatedAtMillis(value: String?): Long {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return 0L
        raw.toLongOrNull()?.let { number ->
            return if (kotlin.math.abs(number) < 100_000_000_000L) number * 1000L else number
        }
        return runCatching { Instant.parse(raw).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli() }
            .getOrDefault(0L)
    }

    // ── Read functions — direct PostgREST REST API (unlimited, zero invocations) ──

    fun fetchHistory(consignmentId: String, screen: String, onResult: (List<JSONObject>) -> Unit) {
        if (consignmentId.isBlank()) return onResult(emptyList())
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            onResult(SupabaseClientManager.fetchValidations(screen, "fetch_history", listOf(
                "consignment" to "eq.$consignmentId",
                "order" to "created_at.desc"
            )))
        }
    }

    fun fetchTodayForDeliveryAgent(assignedAgentSystemId: String, screen: String, onResult: (List<JSONObject>) -> Unit) {
        if (assignedAgentSystemId.isBlank()) return onResult(emptyList())
        val start = LocalDate.now(ZoneId.of("Asia/Dhaka")).atStartOfDay(ZoneId.of("Asia/Dhaka")).toInstant().toString()
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            onResult(SupabaseClientManager.fetchValidations(screen, "fetch_today", listOf(
                "assigned_to_system_id" to "eq.$assignedAgentSystemId",
                "created_at" to "gte.$start",
                "order" to "created_at.desc"
            )))
        }
    }

    fun fetchForDeliveryAgentInRange(assignedAgentSystemId: String, rangeStartMs: Long, rangeEndMs: Long,
                                     screen: String, onResult: (List<JSONObject>) -> Unit) {
        if (assignedAgentSystemId.isBlank()) return onResult(emptyList())
        val start = Instant.ofEpochMilli(rangeStartMs).toString()
        val end   = Instant.ofEpochMilli(rangeEndMs).toString()
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            onResult(SupabaseClientManager.fetchValidations(screen, "fetch_range", listOf(
                "assigned_to_system_id" to "eq.$assignedAgentSystemId",
                "created_at" to "gte.$start",
                "created_at" to "lte.$end",
                "order" to "created_at.desc"
            )))
        }
    }

    fun fetchNewRemarksSince(consignmentIds: List<String>, sinceEpochMs: Long, screen: String,
                             onResult: (List<JSONObject>) -> Unit) {
        if (consignmentIds.isEmpty()) return onResult(emptyList())
        val since = Instant.ofEpochMilli(sinceEpochMs).toString()
        val allRows = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        // PostgREST supports `in.(A,B,C)` for up to ~1000 items; chunk at 200 for safety.
        val chunks = consignmentIds.distinct().chunked(200)
        val remaining = java.util.concurrent.atomic.AtomicInteger(chunks.size)
        chunks.forEach { chunk ->
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val rows = SupabaseClientManager.fetchValidations(screen, "fetch_new_since", listOf(
                    "consignment" to "in.(${chunk.joinToString(",")})",
                    "created_at" to "gte.$since",
                    "order" to "created_at.desc"
                ))
                allRows.addAll(rows)
                if (remaining.decrementAndGet() == 0) onResult(allRows.toList())
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
