package com.cloudx.databridge

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared Google Sign-In behavior used by BOTH ConfigSheetFragment (Sheets tab) and
 * ConfigConnectorsFragment (Connectors tab). Extracted here because the two fragments'
 * pickGoogleAccount() / handleSignInResult() / fetchAccessToken() were functionally
 * identical (verified line-by-line during the ConfigSheetFragment.kt module split) —
 * only their error-display method (toast vs a custom error view) and OAuth scope
 * string differed.
 *
 * DELIBERATELY STATELESS: every function here takes what it needs as a parameter and
 * returns a result — it never reads or writes `googleAccount` / `googleSignInClient`
 * on either fragment. Those properties stay on each fragment because the rest of each
 * fragment's state machine (wizard-step gating, UI visibility) reads them directly in
 * many places; making this helper own that state would mean threading a reference
 * through every one of those call sites for no benefit. Each fragment calls these
 * functions and assigns the result to its own property itself.
 *
 * Per-feature account isolation (the SharedPreferences email-matching guard) is NOT
 * here — it stays fragment-specific by design, since "isolation" means Sheets and
 * Connectors must NOT quietly share behavior for that part. See ConfigSheetFragment's
 * and ConfigConnectorsFragment's own accountPrefs()/PREFS_KEY_EMAIL for that guard.
 */
object GoogleSignInHelper {

    /**
     * Forces Google's account chooser to appear by signing out of [client] first —
     * without this, GoogleSignInClient silently reuses whatever account is already
     * cached device-wide and skips the chooser UI entirely.
     *
     * [onLaunchFailed] is called with the exception message if launching the sign-in
     * intent itself throws (rare — e.g. Activity already finishing).
     */
    fun pickAccount(
        fragment: Fragment,
        client: GoogleSignInClient,
        signInLauncher: ActivityResultLauncher<android.content.Intent>,
        onLaunchFailed: (String) -> Unit
    ) {
        client.signOut().addOnCompleteListener {
            try {
                if (fragment.isAdded) signInLauncher.launch(client.signInIntent)
            } catch (e: Exception) {
                if (fragment.isAdded) onLaunchFailed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Parses the sign-in result intent into a GoogleSignInAccount. Returns null (and
     * calls [onError]) on failure — the caller decides what "failure" means for its UI
     * (toast, inline error view, etc.) and whether to retry.
     */
    fun parseSignInResult(
        data: android.content.Intent?,
        onError: (String) -> Unit
    ): GoogleSignInAccount? {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            task.getResult(com.google.android.gms.common.api.ApiException::class.java)
        } catch (e: com.google.android.gms.common.api.ApiException) {
            onError("Sign-in failed (code ${e.statusCode})")
            null
        } catch (e: Exception) {
            onError("Sign-in error: ${e.message}")
            null
        }
    }

    /**
     * Fetches a fresh OAuth access token for [account] with [scope]. If Google requires
     * fresh user consent (UserRecoverableAuthException), launches [recoverableLauncher]
     * with the recovery intent instead of failing outright — the caller's screen should
     * retry the original action when that launcher's result comes back successful.
     *
     * Runs the blocking GoogleAuthUtil call on Dispatchers.IO; safe to call directly
     * from a coroutine on any dispatcher.
     */
    suspend fun fetchAccessToken(
        context: Context,
        fragment: Fragment,
        account: GoogleSignInAccount,
        scope: String,
        recoverableLauncher: ActivityResultLauncher<android.content.Intent>,
        onError: (String) -> Unit
    ): String? {
        val acctObj = account.account ?: run {
            onError("Account info নেই")
            return null
        }
        return withContext(Dispatchers.IO) {
            try {
                GoogleAuthUtil.getToken(context, acctObj, scope)
            } catch (e: UserRecoverableAuthException) {
                withContext(Dispatchers.Main) {
                    if (fragment.isAdded) {
                        try {
                            e.intent?.let { recoverableLauncher.launch(it) }
                        } catch (_: Exception) { /* best-effort recovery prompt */ }
                    }
                }
                null
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { if (fragment.isAdded) onError("Token fetch failed: ${e.message}") }
                null
            }
        }
    }

    /**
     * Per-feature account persistence — each caller passes its OWN SharedPreferences
     * file name so Sheets and Connectors never read/write each other's saved email.
     * See ConfigSheetFragment/ConfigConnectorsFragment's account-isolation doc comment
     * for the full reasoning (GoogleSignIn.getLastSignedInAccount() is device-wide).
     */
    fun rememberConnectedEmail(context: Context, prefsFileName: String, email: String?) {
        if (email.isNullOrBlank()) return
        context.getSharedPreferences(prefsFileName, Context.MODE_PRIVATE)
            .edit().putString("connected_email", email).apply()
    }

    /** Returns the cached account ONLY if its email matches this feature's own saved
     *  email and it still holds every scope in [requiredScopes] — otherwise null. */
    fun restoreOwnAccountIfMatching(
        context: Context,
        prefsFileName: String,
        requiredScopes: List<com.google.android.gms.common.api.Scope>
    ): GoogleSignInAccount? {
        val savedEmail = context.getSharedPreferences(prefsFileName, Context.MODE_PRIVATE)
            .getString("connected_email", null) ?: return null
        val cached = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        if (!cached.email.equals(savedEmail, ignoreCase = true)) return null
        if (!GoogleSignIn.hasPermissions(cached, *requiredScopes.toTypedArray())) return null
        return cached
    }
}
