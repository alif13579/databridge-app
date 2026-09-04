// Shared service-role Supabase client. Every function uses the admin client
// (bypasses RLS) — access control lives in each action's own checks (Firebase
// identity + role/permission gates), never in RLS for these write paths.

import { createClient } from 'npm:@supabase/supabase-js@2'

const secretKeys = JSON.parse(Deno.env.get('SUPABASE_SECRET_KEYS') ?? '{}')
const serviceRoleKey = secretKeys.default ?? Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')
if (!serviceRoleKey) throw new Error('A Supabase server key is required')

export const admin = createClient(Deno.env.get('SUPABASE_URL')!, serviceRoleKey, {
  auth: { persistSession: false, autoRefreshToken: false },
})
