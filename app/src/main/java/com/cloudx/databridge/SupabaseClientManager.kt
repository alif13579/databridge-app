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
    private val httpClient = OkHttpClient.Builder()
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
                val accessToken = json.optString("access_token")
                val refreshToken = json.optString("refresh_token")
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

    /**
     * Upserts the current user into public.users so RLS branch checks work.
     * Safe to call every login — UPSERT is idempotent.
     */
    suspend fun syncUser(
        firebaseId: String,
        systemId: String,
        name: String,
        employeeId: String,
        branchId: String,
    ) {
        val token = getAccessToken() ?: return
        val body = JSONObject().apply {
            put("firebase_id", firebaseId)
            put("system_id", systemId)
            put("name", name)
            put("employee_id", employeeId.ifBlank { JSONObject.NULL })
            put("branch_id", branchId)
            // branch_ids (text[]) was added in migration 202608220001 for
            // multi-branch RLS. syncUser only has a single branchId parameter,
            // so keep branch_ids in sync with branch_id. Without this, new
            // users get branch_ids='{}' (column default), my_branch_ids()
            // returns an empty array, and every RLS-gated SELECT (fetchHistory,
            // Realtime) silently returns nothing — this is why CC→Worker remark
            // updates were not appearing after the migration.
            put("branch_ids", org.json.JSONArray().put(branchId))
        }
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${SupabaseConfig.PROJECT_URL}/rest/v1/users")
                    .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                    .post(body.toString().toRequestBody(json))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "syncUser OK — firebase_id=$firebaseId system_id=$systemId " +
                            "branch_id=$branchId branch_ids=[$branchId]")
                    } else {
                        val body = response.body?.string().orEmpty().take(300)
                        Log.e(TAG, "syncUser HTTP ${response.code} — $body")
                        FirebaseErrorLogger.log(TAG, "sync_user_http_error",
                            "HTTP ${response.code}: $body", mapOf("systemId" to systemId))
                    }
                }
            } catch (e: Exception) {
                FirebaseErrorLogger.log("SupabaseClientManager", "sync_user",
                    e.message ?: "Sync failed", mapOf("systemId" to systemId))
            }
        }
    }

    // ── Direct REST reads (unlimited — replaces Edge Function read actions) ───

    /**
     * Fetches rows from public.validations using the free PostgREST API.
     * No Edge Function invocation is consumed.
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
