// Shared master-users upsert. Every function that needs a public.users row
// (profile sync, remark writes, branch membership, backfills) goes through
// here so the firebase_id-must-never-be-NULL invariant lives in one place.

import { admin } from './supabase.ts'
import { errLog } from './http.ts'
import type { FirebaseProfile } from './firebase-auth.ts'

/**
 * ── DO NOT make `firebaseId` optional again ─────────────────────────────────
 * Every call MUST pass the target user's real Firebase uid. This upsert runs
 * `onConflict: 'system_id'`, so it updates that person's EXISTING row — and
 * `firebase_id: firebaseId || null` means any call that omits it OVERWRITES
 * an already-correct firebase_id with NULL.
 *
 * That happened for real on 2026-08-23: firebaseProfileForSystemId() resolved
 * the assigned worker's uid internally but didn't return it, and the write
 * action's `upsertUser(assignedProfile)` call (no 2nd arg) nulled that
 * worker's firebase_id on every CC-assigned remark. Both my_branch_ids() and
 * my_system_id() look users up BY firebase_id, so once it was NULL every RLS
 * read for that worker — branch-scoped and assigned_to_system_id alike —
 * silently resolved to an empty result. HTTP 200, "genuine empty []", no
 * error anywhere. Looked exactly like a missing/misapplied RLS migration and
 * cost a multi-hour debugging session (see commit 6b3a39c) before the actual
 * cause — corrupted data, not a bad policy — was found.
 *
 * firebaseId is a required param specifically so a future call site CANNOT
 * compile without deciding what to pass. If a caller genuinely has no uid
 * for this user yet, that is itself a bug to fix at the caller, not a reason
 * to silently write NULL here.
 *
 * (This still applies to the employee_id-conflict fallback below — it reuses
 * the same payload, firebaseId included, so the rule above covers both
 * upsert attempts, not just the first.)
 */
export async function upsertUser(profile: FirebaseProfile, firebaseId: string) {
  const payload = {
    system_id: profile.systemId,
    employee_id: profile.employeeId || null,
    name: profile.name || profile.systemId,
    // branch_id (singular) kept in sync for any other code still reading it;
    // branch_ids (array) is what RLS's my_branch_ids() actually checks against —
    // a single value silently dropped every branch after an agent's first one.
    branch_id: profile.branchIds[0] || null,
    branch_ids: profile.branchIds,
    role: profile.roleId || null,
    firebase_id: firebaseId || null,
    updated_at: new Date().toISOString(),
  }
  const { error } = await admin.from('users').upsert(payload, { onConflict: 'system_id' })
  if (!error) return

  // users.employee_id has its own unique constraint (users_employee_id_key),
  // separate from the system_id conflict target above — Postgres upsert only
  // auto-resolves the ONE conflict target it's given, so a genuine employee_id
  // collision under a different system_id still surfaces as a hard error here,
  // not something onConflict: 'system_id' silently handles.
  //
  // Seen for real on 2026-09-03: an employee (employee_id "M 1703") had two
  // Firebase accounts (two different system_ids) at some point; the stale one
  // got deleted from Firebase, but its users row — keyed by the now-orphaned
  // system_id — was never cleaned up, so every write for the surviving
  // account's system_id hit "duplicate key value violates unique constraint
  // users_employee_id_key" and failed outright, blocking that agent's remark
  // saves entirely until the stale row was found and deleted by hand.
  //
  // employee_id is the durable, real-world identity here (system_id is a
  // Firebase-account artifact that can legitimately change — re-onboarding,
  // a corrupted account getting recreated, etc.), so on this specific
  // conflict, retry keyed on employee_id instead: this updates the existing
  // row in place (system_id and everything else moves to the new values)
  // rather than requiring another manual cleanup each time it recurs.
  const isEmployeeIdConflict = error.code === '23505'
    && (error.message?.includes('employee_id') || error.details?.includes('employee_id'))
  if (!isEmployeeIdConflict || !profile.employeeId) throw error

  errLog('upsertUser', 'employee_id_conflict_fallback', {
    systemId: profile.systemId, employeeId: profile.employeeId, firebaseId,
    originalError: { code: error.code, message: error.message, details: error.details },
  })
  const { error: fallbackError } = await admin.from('users').upsert(payload, { onConflict: 'employee_id' })
  if (fallbackError) throw fallbackError
}
