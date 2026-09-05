package com.cloudx.databridge

import android.util.Base64
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Singleton Supabase client.
 *
 * Responsibilities:
 *  • Create and hold the SupabaseClient (Auth + Realtime + Postgrest).
 *  • Use Firebase ID tokens directly with Supabase Third-party Auth.
 *  • Sync the signed-in user's profile row to public.users so RLS works.
 *  • Expose [getAccessToken] for direct PostgREST / Realtime calls.
 *  • Provide [fetchRest] — a drop-in for the former Edge Function read calls
 *    that uses the unlimited REST API instead of consuming invocations.
 */
object SupabaseClientManager {

    private const val TAG = "SupabaseClientManager"
    // Bare OkHttpClient() has NO call timeout — on a slow/flaky mobile connection a stuck
    // request here left fetchValidations() (and callers like fetchHistory's
    // deferred.await()) hanging forever, which is why the Action History / Journey Log
    // dialog could sometimes sit in its loading state indefinitely. Bounding both the
    // per-phase timeouts and the overall call time guarantees fetchValidations() always
    // returns (emptyList() on failure) within a fixed window.
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()

    private var _client: SupabaseClient? = null
    val client: SupabaseClient
        get() = _client ?: error("SupabaseClientManager not initialised — call init() in Application.onCreate()")

    val isInitialised get() = _client != null

    /** Call once from [DataBridgeApplication.onCreate]. */
    fun init() {
        Log.i(
            TAG,
            "Supabase config: configured=${SupabaseConfig.isConfigured}, " +
                "url=${SupabaseConfig.PROJECT_URL.ifBlank { "<missing>" }}, " +
                "publishableKeyPresent=${SupabaseConfig.PUBLISHABLE_KEY.isNotBlank()}"
        )
        if (!SupabaseConfig.isConfigured) {
            Log.e(TAG, "Supabase client was not initialized because configuration is invalid")
            return
        }
        _client = createSupabaseClient(
            supabaseUrl = SupabaseConfig.PROJECT_URL,
            supabaseKey = SupabaseConfig.PUBLISHABLE_KEY,
        ) {
            install(Auth)
            install(Realtime)
            install(Postgrest)
        }
    }

    // ── Legacy Auth exchange diagnostics ───────────────────────────────────────

    /**
     * Legacy diagnostic for the former Supabase Auth token-exchange flow.
     *
     * Firebase Third-party Auth uses the Firebase JWT directly, so application
     * reads do not call this method. It is intentionally retained during the
     * migration to make a failed legacy endpoint response observable in Logcat.
     */
    suspend fun exchangeFirebaseToken(): Boolean {
        val client = _client
        if (client == null) {
            Log.e(TAG, "Firebase token exchange skipped: Supabase client is not initialized")
            return false
        }
        return try {
            val token = FirebaseAuth.getInstance().currentUser
                ?.getIdToken(false)?.await()?.token
            if (token.isNullOrBlank()) {
                Log.e(TAG, "Firebase token exchange skipped: no Firebase ID token")
                FirebaseErrorLogger.log(TAG, "exchange_firebase_token_no_id_token", "No Firebase ID token")
                return false
            }
            logFirebaseTokenClaims(token)
            Log.d(TAG, "Starting legacy Firebase ID-token exchange")
            importFirebaseIdTokenSession(client, token)
        } catch (e: Exception) {
            Log.e(TAG, "Firebase token exchange failed", e)
            FirebaseErrorLogger.log("SupabaseClientManager", "exchange_firebase_token",
                e.message ?: "Exchange failed")
            false
        }
    }

    private suspend fun importFirebaseIdTokenSession(client: SupabaseClient, firebaseToken: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("provider", "firebase")
                .put("id_token", firebaseToken)
            val request = Request.Builder()
                .url("${SupabaseConfig.PROJECT_URL}/auth/v1/token?grant_type=id_token")
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(json))
                .build()
            val response = httpClient.newCall(request).execute()
            response.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    Log.e(TAG, "Firebase token exchange HTTP ${it.code}: ${text.take(1_000)}")
                    FirebaseErrorLogger.log(
                        "SupabaseClientManager",
                        "exchange_firebase_token_http_error",
                        "HTTP ${it.code}: ${text.take(300)}"
                    )
                    return@withContext false
                }
                val json = JSONObject(text)
                val accessToken = json.optStr("access_token")
                val refreshToken = json.optStr("refresh_token")
                if (accessToken.isBlank() || refreshToken.isBlank()) {
                    Log.e(TAG, "Firebase token exchange response was missing access_token or refresh_token")
                    FirebaseErrorLogger.log(
                        "SupabaseClientManager",
                        "exchange_firebase_token_bad_response",
                        "Missing access_token or refresh_token"
                    )
                    return@withContext false
                }
                client.auth.importAuthToken(accessToken, refreshToken, retrieveUser = true, autoRefresh = true)
                Log.i(TAG, "Firebase token exchange succeeded")
                true
            }
        }

    /**
     * Returns the current Firebase ID token for Supabase Third-party Auth.
     *
     * Supabase's Firebase integration validates this token directly for REST,
     * Realtime and Storage. It does not require a Supabase Auth session created
     * through /auth/v1/token.
     */
    suspend fun getAccessToken(): String? {
        return try {
            if (_client == null) {
                Log.e(TAG, "Firebase token unavailable: Supabase client is not initialized")
                return null
            }
            val token = FirebaseAuth.getInstance().currentUser
                ?.getIdToken(false)?.await()?.token
            if (token.isNullOrBlank()) {
                Log.e(TAG, "Firebase token unavailable: no signed-in Firebase user or ID token")
                FirebaseErrorLogger.log(TAG, "firebase_access_token_missing", "No Firebase ID token")
                null
            } else {
                logFirebaseTokenClaims(token)
                token
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to obtain Firebase ID token", e)
            FirebaseErrorLogger.log(TAG, "firebase_access_token_error", e.message ?: "ID token request failed")
            null
        }
    }

    /** Sign out of Supabase (call alongside Firebase sign-out). */
    suspend fun signOut() {
        try { _client?.auth?.signOut() } catch (_: Exception) {}
    }

    // ── User sync ─────────────────────────────────────────────────────────────
    // REMOVED (Supabase-first): the old direct-REST syncUser() upsert lived here.
    // It wrote name = Google displayName, employee_id = NULL and a SINGLE branch
    // into public.users on every launch — clobbering the authoritative row
    // whenever it ran as `authenticated`. RLS identity mapping is established
    // by the user-sync Edge Function's sync_profile action instead
    // (SupabaseRemarkValidationWriter.ensureProfileSynced, before RLS-gated
    // reads), and employee create/edit goes through SupabaseUserWriter.

    // ── Direct REST reads (unlimited — replaces Edge Function read actions) ───

    /**
     * Fetches rows from public.validations using the free PostgREST API.
     * No Edge Function invocation is consumed.
     *
     * Rows come back English-only (remarks column) — callers wanting Bangla
     * pull the distinct remarks text out of the result and pass it to
     * [fetchRemarkLabels] as a second, separate query.
     *
     * [params] is a list of query-string key→value pairs appended to the URL.
     * Returns a list of rows as [JSONObject], same shape as the Edge Function
     * used to return so existing callers need no changes.
     */
    suspend fun fetchValidations(
        screen: String,
        action: String,
        params: List<Pair<String, String>>,
    ): List<JSONObject> = withContext(Dispatchers.IO) {
        val token = getAccessToken()
        if (token == null) {
            Log.e(TAG, "Validation read skipped: Firebase bearer token is unavailable ($screen/$action)")
            RemarkPushChainLog.log("RemarkPushChain", "fetchValidations($screen/$action): SKIPPED — " +
                "getAccessToken() returned null (no signed-in Firebase user / no ID token)", isWarning = true)
            FirebaseErrorLogger.log(screen, "${action}_no_token", "No Supabase token")
            return@withContext emptyList()
        }
        val query = params.joinToString("&") { (k, v) ->
            "${k.encodeParam()}=${v.encodeParam()}"
        }
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/validations?$query"
        try {
            val response = httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()
            ).execute()
            response.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    Log.e(TAG, "Validation read HTTP ${it.code} ($screen/$action): ${text.take(1_000)}")
                    RemarkPushChainLog.log("RemarkPushChain", "fetchValidations($screen/$action): " +
                        "HTTP ${it.code} — ${text.take(500)}", isWarning = true)
                    FirebaseErrorLogger.log(screen, "${action}_http_error",
                        "HTTP ${it.code}: ${text.take(300)}")
                    return@withContext emptyList()
                }
                val arr = JSONArray(text)
                if (arr.length() == 0) {
                    Log.w(TAG, "Validation read returned 0 rows ($screen/$action) — " +
                        "possible RLS block: check branch_ids in public.users for this Firebase UID. " +
                        "URL: $url")
                    RemarkPushChainLog.log("RemarkPushChain", "fetchValidations($screen/$action): " +
                        "HTTP 200 but 0 rows (genuine empty array) — query: $query", isWarning = true)
                } else {
                    Log.d(TAG, "Validation read OK ($screen/$action): ${arr.length()} row(s)")
                    RemarkPushChainLog.log("RemarkPushChain", "fetchValidations($screen/$action): " +
                        "HTTP 200, ${arr.length()} row(s)")
                }
                List(arr.length()) { i -> arr.getJSONObject(i) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Validation read failed ($screen/$action)", e)
            RemarkPushChainLog.log("RemarkPushChain", "fetchValidations($screen/$action): " +
                "EXCEPTION — ${e.javaClass.simpleName}: ${e.message}", isWarning = true)
            FirebaseErrorLogger.log(screen, "${action}_error", e.message ?: "Request failed")
            emptyList()
        }
    }

    // In-memory only — cleared on process death/app restart by design (see the
    // conversation that settled on this): remark options are a small predefined
    // set, so this fills up fast and stays fresh without a persistence/invalidation
    // story. Guarded by the class monitor since GlobalScope.launch callers (Realtime
    // push handlers) can hit this from multiple coroutines concurrently.
    //
    // Keyed by "$source\u0000$remarksEn" — remarks_en alone isn't unique once Worker
    // and CC options share validation_remarks (same English text can be a different
    // row per source, see migration 202608250002), so every lookup here is scoped
    // to a specific source, same as the Edge Function's resolveRemarkBn/withBanglaLabels.
    private val remarkLabelCache = mutableMapOf<String, String>()
    private fun remarkCacheKey(source: String, remarksEn: String) = "$source\u0000$remarksEn"

    /**
     * Resolves a single English remark text to Bangla for the given [source]
     * ('CC' or 'WORKER', same vocabulary as validations.source), using
     * [remarkLabelCache] first and only hitting validation_remarks on a cache
     * miss. This is the preferred entry point for one-off lookups (a single
     * Realtime push event) — for a whole page of rows, prefer [fetchRemarkLabels]
     * once for the distinct set instead of calling this per-row.
     *
     * Returns null on a miss (no catalog entry — a free-typed note, or a remark
     * saved before this table existed) and does NOT cache the miss, since a
     * pending write's upsert could resolve it moments later.
     */
    suspend fun resolveRemarkBnCached(screen: String, source: String, remarksEn: String): String? {
        val en = remarksEn.trim()
        if (en.isBlank() || source.isBlank()) return null
        val key = remarkCacheKey(source, en)
        synchronized(remarkLabelCache) { remarkLabelCache[key] }?.let { return it }
        val looked = fetchRemarkLabels(screen, source, listOf(en))[en] ?: return null
        synchronized(remarkLabelCache) { remarkLabelCache[key] = looked }
        return looked
    }

    /**
     * Looks up Bangla labels for a set of English remark texts from
     * public.validation_remarks, scoped to [source] ('CC' or 'WORKER') — the
     * write side upserts en/bn pairs here per-source (see
     * SupabaseRemarkValidationWriter.write's remarksBnText param and the Edge
     * Function's upsertRemarkLabel). No Edge Function invocation — free PostgREST
     * API, same as [fetchValidations].
     *
     * Checks [remarkLabelCache] first and only queries for entries not already
     * cached; every fresh result is cached before returning. Prefer this over
     * repeated [resolveRemarkBnCached] calls when resolving a whole page of rows
     * at once — one query for every actual miss, instead of one per row. All
     * rows passed in a single call must share the same [source]; callers with a
     * mixed-source page (shouldn't happen — CC and Worker screens each only
     * ever fetch their own source) should group by source and call once per group.
     *
     * Returns a map of English text -> Bangla text. A key with no catalog
     * match (a free-typed note, or a remark saved before this table existed)
     * is simply absent from the map — callers should fall back to the
     * English text themselves when a lookup misses, same pattern as the
     * Edge Function's resolveRemarkBn/withBanglaLabels.
     *
     * [remarksEn] should already be the distinct set of texts needed — callers
     * pull this from a [fetchValidations] result's `remarks` field before
     * calling this, no point looking up duplicates twice.
     */
    suspend fun fetchRemarkLabels(
        screen: String,
        source: String,
        remarksEn: Collection<String>,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val distinct = remarksEn.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty() || source.isBlank()) return@withContext emptyMap()
        val cached = mutableMapOf<String, String>()
        val toFetch = mutableListOf<String>()
        synchronized(remarkLabelCache) {
            distinct.forEach { en ->
                remarkLabelCache[remarkCacheKey(source, en)]?.let { cached[en] = it } ?: toFetch.add(en)
            }
        }
        if (toFetch.isEmpty()) return@withContext cached
        val token = getAccessToken()
        if (token == null) {
            Log.e(TAG, "Remark label read skipped: Firebase bearer token is unavailable ($screen)")
            return@withContext cached
        }
        // PostgREST in.(...) filter — comma-separated, each value individually
        // percent-encoded. A literal comma or paren inside a remark text would
        // break this filter syntax, but remark texts are short fixed labels
        // (predefined options), never free-typed content, so this is safe here.
        val inList = toFetch.joinToString(",") { java.net.URLEncoder.encode(it, "UTF-8") }
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/validation_remarks" +
            "?select=remarks_en,remarks_bn&source=eq.${source.encodeParam()}&remarks_en=in.($inList)"
        try {
            val response = httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()
            ).execute()
            response.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    Log.e(TAG, "Remark label read HTTP ${it.code} ($screen): ${text.take(1_000)}")
                    FirebaseErrorLogger.log(screen, "validation_remarks_http_error",
                        "HTTP ${it.code}: ${text.take(300)}")
                    return@withContext cached
                }
                val arr = JSONArray(text)
                val fresh = buildMap {
                    for (i in 0 until arr.length()) {
                        val row = arr.getJSONObject(i)
                        val en = row.optStr("remarks_en")
                        val bn = row.optStr("remarks_bn")
                        if (en.isNotBlank() && bn.isNotBlank()) put(en, bn)
                    }
                }
                if (fresh.isNotEmpty()) {
                    synchronized(remarkLabelCache) {
                        fresh.forEach { (en, bn) -> remarkLabelCache[remarkCacheKey(source, en)] = bn }
                    }
                }
                cached + fresh
            }
        } catch (e: Exception) {
            Log.e(TAG, "Remark label read failed ($screen)", e)
            FirebaseErrorLogger.log(screen, "validation_remarks_error", e.message ?: "Request failed")
            cached
        }
    }

    /** A remark option as loaded from public.validation_remarks for the picker/chip UI
     *  (CallCenterFragment, WorkerSpaceFragment, RemarkPopupOverlay, ParcelDetailFragment).
     *  Mirrors ConfigState.Remark's fields — kept as a separate lightweight type here
     *  since callers only need a subset for rendering, not the admin-config shape. */
    data class RemarkOption(
        val id: String, val textBn: String, val textEn: String, val targetStatus: String,
        val templateId: String, val priority: Int, val instructionType: String, val instructionText: String,
        // Sheet verdict (validation_remarks.category): what RemarkSheetMirror
        // writes into the branch's connected remark sheet on a CC save.
        // Blank = no sheet write for this remark.
        val category: String,
    )

    /** Loads every active remark option for [source] ('CC' or 'WORKER') directly from
     *  public.validation_remarks — a free, unlimited PostgREST read (same
     *  validation_remarks_select_authenticated policy [fetchRemarkLabels] above already
     *  relies on), not an Edge Function call. This is what the option picker/chip UI reads
     *  on every open, so it deliberately avoids the Edge Function's admin_list_remarks
     *  (which is gated behind canAccessConfig — the wrong permission level for an ordinary
     *  CC/Worker user just picking a remark to save, not editing the option catalog).
     *  Sorted by priority descending, matching ConfigRemarksFragment's picker order. */
    suspend fun fetchRemarkOptions(screen: String, source: String): List<RemarkOption> = withContext(Dispatchers.IO) {
        if (source.isBlank()) return@withContext emptyList()
        val token = getAccessToken()
        if (token == null) {
            Log.e(TAG, "Remark option read skipped: Firebase bearer token is unavailable ($screen)")
            return@withContext emptyList()
        }
        val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/validation_remarks" +
            "?select=id,remarks_bn,remarks_en,target_status,template_id,priority,instruction_type,instruction_text,category" +
            "&source=eq.${source.encodeParam()}&is_active=eq.true&order=priority.desc"
        try {
            val response = httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()
            ).execute()
            response.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    Log.e(TAG, "Remark option read HTTP ${it.code} ($screen): ${text.take(1_000)}")
                    FirebaseErrorLogger.log(screen, "remark_options_http_error", "HTTP ${it.code}: ${text.take(300)}")
                    return@withContext emptyList()
                }
                val arr = JSONArray(text)
                List(arr.length()) { i ->
                    val row = arr.getJSONObject(i)
                    RemarkOption(
                        id = row.optStr("id"), textBn = row.optStr("remarks_bn"), textEn = row.optStr("remarks_en"),
                        targetStatus = row.optStr("target_status"), templateId = row.optStr("template_id"),
                        priority = row.optInt("priority", 0), instructionType = row.optStr("instruction_type"),
                        instructionText = row.optStr("instruction_text"),
                        category = row.optStr("category"),
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Remark option read failed ($screen)", e)
            FirebaseErrorLogger.log(screen, "remark_options_error", e.message ?: "Request failed")
            emptyList()
        }
    }

    /** Logs only non-secret JWT claims needed to diagnose Third-party Auth/RLS. */
    private var lastLoggedTokenClaims: String? = null

    private fun logFirebaseTokenClaims(token: String) {
        try {
            val payload = token.split('.').getOrNull(1) ?: return
            val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val claims = JSONObject(decoded)
            val summary = "uid=${claims.optString("sub", "<missing>")}, " +
                "aud=${claims.optString("aud", "<missing>")}, " +
                "iss=${claims.optString("iss", "<missing>")}, " +
                "role=${claims.optString("role", "<missing>")}, " +
                "exp=${claims.optLong("exp", 0L)}"
            Log.d(TAG, "Firebase token claims: $summary")
            if (summary != lastLoggedTokenClaims) {
                lastLoggedTokenClaims = summary
                RemarkPushChainLog.log("RemarkPushChain", "Firebase token claims: $summary")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not decode Firebase token claims for diagnostics: ${e.message}")
        }
    }

    private fun String.encodeParam() = java.net.URLEncoder.encode(this, "UTF-8")
}
