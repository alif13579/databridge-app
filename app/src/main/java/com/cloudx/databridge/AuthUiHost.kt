package com.cloudx.databridge

/** Activity that owns global auth UI (top bar, drawer, settings sync). */
interface AuthUiHost {
    fun refreshAuthUi(forceReload: Boolean = false)
    fun launchGoogleSignIn()
}
