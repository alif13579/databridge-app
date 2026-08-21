package com.cloudx.databridge

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
 *  • Exchange a Firebase ID token for a Supabase session on login.
 *  • Sync the signed-in user's profile row to public.users so RLS works.
 *  • Expose [getAccessToken] for direct PostgREST / Realtime calls.
 *  • Provide [fetchRest] — a drop-in for the former Edge Function read calls
 *    that uses the unlimited REST API instead of consuming invocations.
 */
object SupabaseClientManager {

    private val httpClient = OkHttpClient()
    private val json = "application/json".toMediaType()

    private var _client: SupabaseClient? = null
    val client: SupabaseClient
        get() = _client ?: error("SupabaseClientManager not initialised — call init() in Application.onCreate()")

    val isInitialised get() = _client != null

    /** Call once from [DataBridgeApplication.onCreate]. */
    fun init() {
        if (!SupabaseConfig.isConfigured) return
        _client = createSupabaseClient(
            supabaseUrl = SupabaseConfig.PROJECT_URL,
            supabaseKey = SupabaseConfig.PUBLISHABLE_KEY,
        ) {
            install(Auth)
            install(Realtime)
            install(Postgrest)
        }
    }

    // ── Auth exchange ─────────────────────────────────────────────────────────

    /**
     * Exchanges the current Firebase ID token for a Supabase session.
     * Call this after a successful Firebase sign-in and after RBAC data is
     * loaded (so that [syncUser] can be called immediately after).
     */
    suspend fun exchangeFirebaseToken(): Boolean {
        val client = _client ?: return false
        return try {
            val token = FirebaseAuth.getInstance().currentUser
                ?.getIdToken(false)?.await()?.token ?: return false
            importFirebaseIdTokenSession(client, token)
        } catch (e: Exception) {
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
                    FirebaseErrorLogger.log(
                        "SupabaseClientManager",
                        "exchange_firebase_token_bad_response",
                        "Missing access_token or refresh_token"
                    )
                    return@withContext false
                }
                client.auth.importAuthToken(accessToken, refreshToken, retrieveUser = true, autoRefresh = true)
                true
            }
        }

    /** Returns a valid Supabase access token, refreshing if needed. */
    suspend fun getAccessToken(): String? {
        val client = _client ?: return null
        // supabase-kt automatically refreshes the session when expired
        return try {
            client.auth.currentSessionOrNull()?.accessToken
                ?: run {
                    // Session missing — re-exchange
                    if (exchangeFirebaseToken()) client.auth.currentSessionOrNull()?.accessToken
                    else null
                }
        } catch (_: Exception) { null }
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
                httpClient.newCall(request).execute().use { /* fire-and-forget */ }
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
                    FirebaseErrorLogger.log(screen, "${action}_http_error",
                        "HTTP ${it.code}: ${text.take(300)}")
                    return@withContext emptyList()
                }
                val arr = JSONArray(text)
                List(arr.length()) { i -> arr.getJSONObject(i) }
            }
        } catch (e: Exception) {
            FirebaseErrorLogger.log(screen, "${action}_error", e.message ?: "Request failed")
            emptyList()
        }
    }

    private fun String.encodeParam() = java.net.URLEncoder.encode(this, "UTF-8")
}
