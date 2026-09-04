package com.cloudx.databridge

/**
 * Supabase project configuration for the validations table.
 *
 * Values are generated from ignored local.properties (development) or Gradle
 * properties (CI/release), rather than being hard-coded in source control.
 * See local.properties.example in the repository root.
 *
 * Only the low-privilege publishable key belongs in an Android APK. It is
 * public by design, so RLS/Edge Functions must enforce data access. The
 * service/secret key must NEVER go in client app code: it bypasses RLS and
 * belongs only in the Supabase Edge Function's secrets.
 */
object SupabaseConfig {
    val PROJECT_URL: String get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    val PUBLISHABLE_KEY: String get() = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    val isConfigured: Boolean
        get() = PROJECT_URL.startsWith("https://") && PUBLISHABLE_KEY.isNotBlank()
}
