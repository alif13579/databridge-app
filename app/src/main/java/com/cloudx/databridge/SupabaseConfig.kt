package com.cloudx.databridge

/**
 * Supabase project configuration for the remark_validations table.
 *
 * PLACEHOLDER VALUES — Alif needs to fill these in with the real project
 * URL and anon (public) key from Supabase Dashboard → Project Settings →
 * API, after running supabase_remark_validations_schema.sql there.
 *
 * Deliberately uses the anon key, not the service_role key: the app
 * authenticates via Firebase Auth, not Supabase Auth, so Supabase's own
 * "authenticated" role is never populated for this app's traffic. Per
 * Alif's decision, remark_validations' RLS policy grants full access to
 * the `anon` role with no per-user restriction — protection lives in the
 * app itself (Firebase login gates whether the remark-save screens are
 * reachable at all), not in a Supabase-side identity check. The
 * service_role key must NEVER go in client app code (it bypasses RLS
 * entirely) — only the anon key belongs here.
 */
object SupabaseConfig {
    const val PROJECT_URL = "https://YOUR_PROJECT_REF.supabase.co"
    const val ANON_KEY = "YOUR_ANON_KEY_HERE"

    val isConfigured: Boolean
        get() = PROJECT_URL != "https://YOUR_PROJECT_REF.supabase.co" && ANON_KEY != "YOUR_ANON_KEY_HERE"
}
