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
import kotlinx.coroutines.tasks.await
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

    /** [remarksBnText] is the Bangla label paired with [remarksText] (e.g. CcRemarkOption.label
     *  / WorkerRemarkOption.label) when the remark came from a predefined option — leave blank
     *  for a free-typed note, which has no Bangla counterpart. When present, the Edge Function
     *  upserts the pair into validation_remarks so any card showing this English text (via
     *  withRemarkLabels() below, a Realtime push cache lookup, or the Edge Function's own
     *  report/push paths) can resolve Bangla. */
    fun write(assignedAgentSystemId: String, branchId: String, consignmentId: String,
              status: String, remarksText: String, noteText: String = "", source: String,
              screen: String, remarksBnText: String = "") {
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
            .put("remarks_status", status).put("remarks", remarksText).put("note", noteText)
            .apply { if (remarksBnText.isNotBlank()) put("remarks_bn", remarksBnText) }), screen,
            "supabase_validation_write", consignmentId) { response ->
                // The validation write is intentionally independent from push delivery, so
                // a saved remark can still have push={reason:...}. Keep that outcome in the
                // on-device diagnostic trace instead of silently discarding it.
                val push = runCatching { JSONObject(response ?: "").optJSONObject("push") }.getOrNull()
                val reason = push?.optString("reason").orEmpty().ifBlank { "missing_push_result" }
                val scope = push?.optString("recipient_scope").orEmpty()
                val matched = push?.optInt("matched_devices", 0) ?: 0
                val accepted = push?.optInt("accepted", 0) ?: 0
                val message = "write push result: consignment=$consignmentId source=$source " +
                    "scope=$scope matched=$matched accepted=$accepted reason=$reason"
                Log.i("RemarkPushChain", message)
                RemarkPushChainLog.log("RemarkPushChain", message,
                    isWarning = reason != "accepted_by_fcm")
            }
    }

    // ── Admin remark-option config (ConfigRemarksFragment) ──────────────────────
    // Distinct from write() above: these manage validation_remarks rows as the
    // OPTIONS themselves (what shows in the CC/Worker remark picker), gated
    // server-side by the Edge Function's canAccessConfig check (mirrors
    // RbacManager.hasPermission("nav_config")) — not the ordinary remark-save flow.
    // Suspend-based (unlike invoke()'s callback style above) since
    // ConfigRemarksFragment is already fully coroutine-driven.

    /** Result of an admin_* Edge Function call: the parsed JSONObject on success,
     *  or an error message on failure — ConfigRemarksFragment shows [error] directly
     *  in a Toast, so it's kept human-readable rather than a raw exception. */
    sealed class AdminResult {
        data class Ok(val body: JSONObject) : AdminResult()
        data class Err(val message: String) : AdminResult()
    }

    private suspend fun invokeAdmin(payload: JSONObject): AdminResult = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            if (cont.isActive) cont.resumeWith(Result.success(AdminResult.Err("Not signed in")))
            return@suspendCancellableCoroutine
        }
        user.getIdToken(false).addOnCompleteListener { tokenTask ->
            val token = tokenTask.result?.token
            if (!tokenTask.isSuccessful || token.isNullOrBlank()) {
                if (cont.isActive) cont.resumeWith(Result.success(
                    AdminResult.Err(tokenTask.exception?.message ?: "No Firebase ID token")))
                return@addOnCompleteListener
            }
            val request = Request.Builder().url("${SupabaseConfig.PROJECT_URL}/functions/v1/remark-validations")
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY).addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json").post(payload.toString().toRequestBody(jsonMediaType)).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWith(Result.success(AdminResult.Err(e.message ?: "Network error")))
                }
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        val result = try {
                            if (it.isSuccessful) AdminResult.Ok(JSONObject(text))
                            else AdminResult.Err(JSONObject(text).optString("error").ifBlank { "HTTP ${it.code}" })
                        } catch (_: Exception) {
                            if (it.isSuccessful) AdminResult.Err("Unexpected response") else AdminResult.Err("HTTP ${it.code}")
                        }
                        if (cont.isActive) cont.resumeWith(Result.success(result))
                    }
                }
            })
        }
    }

    /** Lists every remark option for [source] ('CC' or 'WORKER'), sorted by priority
     *  descending (same order the Edge Function query applies, matching
     *  ConfigRemarksFragment's existing `sortedByDescending { it.priority }` display). */
    suspend fun adminListRemarks(source: String): AdminResult =
        invokeAdmin(JSONObject().put("action", "admin_list_remarks").put("source", source))

    /** Creates a remark option ([id] blank) or updates one ([id] set). [remark] should
     *  contain whichever of remarks_en/remarks_bn/category/target_status/template_id/
     *  priority/instruction_type/instruction_text/is_active are being set — omitted
     *  fields default per the Edge Function's admin_upsert_remark handling. */
    suspend fun adminUpsertRemark(source: String, id: String, remark: JSONObject): AdminResult =
        invokeAdmin(JSONObject().put("action", "admin_upsert_remark").put("remark",
            remark.put("source", source).apply { if (id.isNotBlank()) put("id", id) }))

    suspend fun adminDeleteRemark(id: String): AdminResult =
        invokeAdmin(JSONObject().put("action", "admin_delete_remark").put("id", id))

    /** Migrates or deletes every remark option under [source] whose target_status is
     *  [fromStatus] — used when an admin deletes a status (ConfigStatusesFragment).
     *  Pass [toStatus] blank to delete those remarks instead of migrating them. */
    suspend fun adminMigrateStatusRemarks(source: String, fromStatus: String, toStatus: String): AdminResult =
        invokeAdmin(JSONObject().put("action", "admin_migrate_status_remarks")
            .put("source", source).put("from_status", fromStatus)
            .apply { if (toStatus.isNotBlank()) put("to_status", toStatus) })

    /** Associates the current signed-in user and this app installation's FCM token server-side. */
    fun registerPushToken(token: String, onDone: ((Boolean) -> Unit)? = null) {
        if (token.isBlank()) {
            onDone?.invoke(false)
            return
        }
        invoke(
            JSONObject().put("action", "register_push_token").put("token", token),
            screen = "PushNotifications",
            action = "push_token_register",
            reference = ""
        ) { response -> onDone?.invoke(response != null) }
    }

    /**
     * Same registration with bounded retries (immediate, +30s, +5min) for the
     * login/rotation paths — a single failed attempt (offline at login, blip)
     * used to leave the device push-blind until the next auth event. Login
     * and token-rotation callers should prefer this over [registerPushToken].
     */
    fun registerPushTokenWithRetry(token: String, maxAttempts: Int = 3) {
        if (token.isBlank() || maxAttempts < 1) return
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var attempt = 0
            var ok = false
            while (!ok && attempt < maxAttempts) {
                if (attempt > 0) kotlinx.coroutines.delay(if (attempt == 1) 30_000L else 300_000L)
                attempt++
                ok = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    registerPushToken(token) { done ->
                        if (cont.isActive) cont.resume(done, null)
                    }
                }
            }
            if (!ok) {
                log("PushNotifications", "push_token_register_gave_up",
                    "Registration still failing after $attempt attempt(s)", "")
            }
        }
    }

    /** Removes this installation's token mapping at sign-out (see
     *  AuthManager.signOut — called while still signed in). Fire-and-forget:
     *  the server action is idempotent, so logout never blocks on it. */
    fun unregisterPushToken(token: String) {
        if (token.isBlank()) return
        invoke(
            JSONObject().put("action", "unregister_push_token").put("token", token),
            screen = "PushNotifications",
            action = "push_token_unregister",
            reference = ""
        ) { }
    }

    @Volatile private var hasForcedTokenRefreshForRoleClaim = false

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
            RemarkPushChainLog.log("RemarkPushChain", "sync_profile response=${response?.take(300) ?: "FAILED"}", isWarning = response == null)
            // The Edge Function just set the role:authenticated custom claim server-side (or
            // confirmed it's already set) — but per Firebase/Supabase docs, a claim set this way
            // does NOT apply to a token already cached client-side; the SDK must be told to fetch
            // a fresh one. Force this once per process so every later (non-forced) getAccessToken()
            // call picks up the SDK's now-refreshed cache. sync_profile can fire several times in a
            // row at app start, so this is guarded to avoid repeated forced network round-trips.
            if (response != null && !hasForcedTokenRefreshForRoleClaim) {
                hasForcedTokenRefreshForRoleClaim = true
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await()
                        RemarkPushChainLog.log("RemarkPushChain", "Forced Firebase ID token refresh after sync_profile (picks up role claim)")
                    } catch (e: Exception) {
                        RemarkPushChainLog.log("RemarkPushChain", "Forced token refresh failed: ${e.message}", isWarning = true)
                        hasForcedTokenRefreshForRoleClaim = false // allow retry on a later sync_profile call
                    } finally {
                        // A Firebase custom claim is only visible after the forced token refresh.
                        // Do not start an RLS-gated REST/Realtime read with the stale token.
                        onComplete(response)
                    }
                }
            } else {
                onComplete(response)
            }
        }
    }

    @Volatile private var profileSyncConfirmed = false
    private val profileSyncLock = Any()
    private var profileSyncInFlight = false
    private val profileSyncWaiters = mutableListOf<() -> Unit>()

    /**
     * Runs [onReady] once this session's trusted-profile sync (branch_ids etc., which
     * validations RLS checks against) has been attempted. A prior confirmed success this
     * session makes this a same-thread no-op — no extra round trip on every read. Otherwise
     * it triggers a fresh sync attempt first and proceeds to [onReady] regardless of outcome
     * (matching syncCurrentUserProfile's existing best-effort behavior at the fragment-load
     * call site) — a failed/timed-out attempt is not cached as success, so the next call from
     * anywhere retries rather than leaving reads permanently RLS-blind for the rest of the
     * session.
     *
     * This exists because the fragment-load sync alone was not enough: a worker's first-ever
     * interaction can be receiving a CC remark via push seconds after opening a cold app, and
     * refreshWorkerParcelFromPush()/refreshCcParcelFromPush() ran their read directly, with no
     * guarantee the load-time sync had already completed (or that it hadn't silently timed
     * out). Every RLS-gated read path should go through this, not just the one at load.
     */
    fun ensureProfileSynced(onReady: () -> Unit) {
        var alreadySynced = false
        val startSync = synchronized(profileSyncLock) {
            when {
                profileSyncConfirmed -> {
                    alreadySynced = true
                    false
                }
                profileSyncInFlight -> {
                    profileSyncWaiters += onReady
                    false
                }
                else -> {
                    profileSyncWaiters += onReady
                    profileSyncInFlight = true
                    true
                }
            }
        }
        if (alreadySynced) {
            onReady()
            return
        }
        if (!startSync) return
        syncCurrentUserProfile { response ->
            val waiters = synchronized(profileSyncLock) {
                profileSyncConfirmed = response != null
                profileSyncInFlight = false
                profileSyncWaiters.toList().also { profileSyncWaiters.clear() }
            }
            waiters.forEach { it() }
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

    /** Looks up Bangla for every row's `remarks` field, grouped by `source` (a row's
     *  Bangla lookup must match the source it was saved under — remarks_en alone isn't
     *  unique once Worker and CC options share validation_remarks, see migration
     *  202608250002) — one PostgREST call to validation_remarks per distinct source
     *  present, not per row. Injects the result into each row as `remarks_bn` before
     *  the caller's onResult fires — resolveRemarkBn(JSONObject) in the fragments reads
     *  that field directly. A row whose remark has no catalog match (free-typed note,
     *  or saved before the catalog existed) simply doesn't get the field added; the
     *  fragments' resolveRemarkBn() already falls back to English for that case. */
    private suspend fun withRemarkLabels(rows: List<JSONObject>, screen: String): List<JSONObject> {
        if (rows.isEmpty()) return rows
        rows.groupBy { it.optString("source").trim() }.forEach { (source, sourceRows) ->
            if (source.isBlank()) return@forEach
            val distinctEn = sourceRows.map { it.optString("remarks").trim() }.filter { it.isNotBlank() }.distinct()
            if (distinctEn.isEmpty()) return@forEach
            val labels = SupabaseClientManager.fetchRemarkLabels(screen, source, distinctEn)
            if (labels.isEmpty()) return@forEach
            sourceRows.forEach { row ->
                labels[row.optString("remarks").trim()]?.let { bn -> row.put("remarks_bn", bn) }
            }
        }
        return rows
    }

    fun fetchHistory(consignmentId: String, screen: String, onResult: (List<JSONObject>) -> Unit) {
        if (consignmentId.isBlank()) return onResult(emptyList())
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val rows = SupabaseClientManager.fetchValidations(screen, "fetch_history", listOf(
                    "consignment" to "eq.$consignmentId",
                    "order" to "created_at.desc"
                ))
                onResult(withRemarkLabels(rows, screen))
            } catch (e: Exception) {
                RemarkPushChainLog.log("RemarkPushChain", "fetchHistory($screen): uncaught " +
                    "${e.javaClass.simpleName}: ${e.message} — onResult() called with emptyList()", isWarning = true)
                onResult(emptyList())
            }
        }
    }

    fun fetchTodayForDeliveryAgent(assignedAgentSystemId: String, screen: String, onResult: (List<JSONObject>) -> Unit) {
        if (assignedAgentSystemId.isBlank()) return onResult(emptyList())
        val start = LocalDate.now(ZoneId.of("Asia/Dhaka")).atStartOfDay(ZoneId.of("Asia/Dhaka")).toInstant().toString()
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val rows = SupabaseClientManager.fetchValidations(screen, "fetch_today", listOf(
                "assigned_to_system_id" to "eq.$assignedAgentSystemId",
                "created_at" to "gte.$start",
                "order" to "created_at.desc"
            ))
            onResult(withRemarkLabels(rows, screen))
        }
    }

    fun fetchForDeliveryAgentInRange(assignedAgentSystemId: String, rangeStartMs: Long, rangeEndMs: Long,
                                     screen: String, onResult: (List<JSONObject>) -> Unit) {
        if (assignedAgentSystemId.isBlank()) return onResult(emptyList())
        val start = Instant.ofEpochMilli(rangeStartMs).toString()
        val end   = Instant.ofEpochMilli(rangeEndMs).toString()
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val rows = SupabaseClientManager.fetchValidations(screen, "fetch_range", listOf(
                "assigned_to_system_id" to "eq.$assignedAgentSystemId",
                "created_at" to "gte.$start",
                "created_at" to "lte.$end",
                "order" to "created_at.desc"
            ))
            onResult(withRemarkLabels(rows, screen))
        }
    }

    /** Parcels assigned to this worker where CC's remark is still unanswered -- the
     *  consignment's LATEST validations row has source='CC' (worker hasn't submitted
     *  their own remark since). Same "latest row decides" rule used throughout this
     *  file, just checked in the opposite direction from the Hold Validation Export's
     *  stillPending (source='WORKER' there means CC hasn't resolved it yet; here
     *  source='CC' means the worker hasn't).
     *
     *  Bounded to the last 14 days -- an outstanding request older than that would be
     *  unusual and this keeps the query (and the client-side grouping below) cheap for
     *  a background alarm check. Returns each pending consignment's own latest row, with
     *  remarks_bn already resolved via withRemarkLabels(). */
    fun fetchPendingDeliveryRequestsForWorker(assignedAgentSystemId: String, screen: String,
                                              onResult: (List<JSONObject>) -> Unit) {
        if (assignedAgentSystemId.isBlank()) return onResult(emptyList())
        val start = Instant.now().minus(java.time.Duration.ofDays(14)).toString()
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val rows = SupabaseClientManager.fetchValidations(screen, "fetch_pending_delivery_requests", listOf(
                "assigned_to_system_id" to "eq.$assignedAgentSystemId",
                "created_at" to "gte.$start",
                "order" to "created_at.desc"
            ))
            val latestByConsignment = LinkedHashMap<String, JSONObject>()
            rows.forEach { row ->
                val cId = row.optString("consignment")
                if (cId.isBlank()) return@forEach
                val existing = latestByConsignment[cId]
                if (existing == null || row.optString("created_at") > existing.optString("created_at")) {
                    latestByConsignment[cId] = row
                }
            }
            val pending = latestByConsignment.values.filter { it.optString("source") == "CC" }
            onResult(withRemarkLabels(pending, screen))
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
                if (remaining.decrementAndGet() == 0) {
                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        onResult(withRemarkLabels(allRows.toList(), screen))
                    }
                }
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
