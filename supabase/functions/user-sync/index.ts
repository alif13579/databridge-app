// user-sync — identity + push-token registry. Actions: sync_profile,
// register_push_token, unregister_push_token, user_upsert.
//
// public.users is source of truth: ONLY user_upsert (admin employee
// create/edit screen) writes it. sync_profile / register_push_token NEVER
// write users rows — they only read (users_row_missing flag) so a login can
// never clobber admin data. A missing row means the admin hasn't onboarded
// this account via employee edit; the app shows "contact admin".

import { admin } from '../_shared/supabase.ts'
import { errLog, guardRequest, reply, unhandled } from '../_shared/http.ts'
import {
  ensureAuthenticatedRoleClaim,
  firebaseIdentity,
  firebaseProfile,
} from '../_shared/firebase-auth.ts'
import { firebaseUpdatePaths } from '../_shared/firebase-mirror.ts'
import { requireUsersRow, upsertUser } from '../_shared/users.ts'
import type { FirebaseProfile } from '../_shared/firebase-auth.ts'

Deno.serve(async (request) => {
  const guard = guardRequest(request)
  if (guard) return guard
  let action: string | undefined
  let identity: { uid: string; token: string } | undefined
  try {
    identity = await firebaseIdentity(request)
    const body = await request.json()
    action = body.action

    // Read-only identity check: establishes nothing, writes nothing. Returns
    // the Firebase profile plus whether the admin-onboarded users row exists
    // (missing → the app tells the user to contact admin instead of showing
    // silent empty screens). The authenticated role claim is still ensured —
    // that is an Auth claim, not users data.
    if (action === 'sync_profile') {
      const profile = await firebaseProfile(identity)
      const row = await requireUsersRow(profile.systemId)
      await ensureAuthenticatedRoleClaim(identity.uid)
      console.info(`sync_profile ok: system_id=${profile.systemId}, users_row=${row ? 'present' : 'MISSING'}`)
      return reply({ ok: true, system_id: profile.systemId, branch_count: profile.branchIds.length, users_row_missing: !row })
    }

    if (action === 'register_push_token') {
      if (typeof body.token !== 'string' || body.token.trim().length < 20) {
        return reply({ error: 'Invalid push token' }, 400)
      }
      const profile = await firebaseProfile(identity)
      const { error } = await admin.from('fcm_device_tokens').upsert({
        token: body.token.trim(), firebase_uid: identity.uid, system_id: profile.systemId,
        role_id: profile.roleId, branch_ids: profile.branchIds,
        can_access_call_center: profile.canAccessCallCenter, updated_at: new Date().toISOString(),
      }, { onConflict: 'token' })
      if (error) throw error
      return reply({ ok: true })
    }

    if (action === 'unregister_push_token') {
      // Called from AuthManager.signOut() BEFORE the local sign-out, so the
      // request still carries a valid Bearer token. Scoped to the caller's
      // own rows (token + firebase_uid): a token string alone can never
      // delete another user's mapping. Idempotent — deleting an already-gone
      // token still returns ok, so logout never blocks on push cleanup.
      if (typeof body.token !== 'string' || body.token.trim().length < 20) {
        return reply({ error: 'Invalid push token' }, 400)
      }
      const { error } = await admin.from('fcm_device_tokens').delete()
        .eq('token', body.token.trim()).eq('firebase_uid', identity.uid)
      if (error) throw error
      return reply({ ok: true })
    }

    if (action === 'backfill_user') {
      // REMOVED: users rows are admin-onboarded via employee edit (user_upsert)
      // only — no repair path may create them. The one-time migration that
      // used this is long complete.
      return reply({ error: 'backfill_user is retired — onboard via employee edit' }, 410)
    }

    if (action === 'user_upsert') {
      // Supabase-FIRST employee create/edit (Employee screen). The users row
      // is authoritative here — every field comes from the admin UI, never
      // from a Firebase read — then Firebase gets a backup mirror.
      const b = body.user ?? {}
      const str = (v: unknown) => typeof v === 'string' ? v : ''
      const targetUid = str(b.firebase_uid).trim()
      const systemId = str(b.system_id).trim()
      if (!targetUid) return reply({ error: 'firebase_uid is required' }, 400)
      if (!systemId) return reply({ error: 'system_id is required' }, 400)
      // Gate: the user themselves, or admin/manager (same gate as directory
      // writes). Role comes from the server-side Firebase profile read.
      const caller = await firebaseProfile(identity)
      const isSelf = identity.uid === targetUid
      if (!isSelf && caller.roleId !== 'admin' && caller.roleId !== 'manager') {
        errLog('user_upsert', 'forbidden', { role: caller.roleId })
        return reply({ error: 'Only admin or manager can save users' }, 403)
      }
      const branchIds = Array.isArray(b.branch_ids)
        ? [...new Set(b.branch_ids.filter((id: unknown): id is string => typeof id === 'string' && !!id.trim()).map((id: string) => id.trim()))]
        : []
      const profile: FirebaseProfile = {
        systemId,
        roleId: str(b.role).trim(),
        branchIds,
        canAccessCallCenter: false,
        canAccessConfig: false,
        name: str(b.name).trim() || systemId,
        employeeId: str(b.employee_id).trim(),
        phone: str(b.phone).trim(),
        designation: str(b.designation).trim(),
      }
      // 1) Authoritative Supabase write (employee_id-collision fallback inside).
      await upsertUser(profile, targetUid)
      // 2) Best-effort Firebase backup mirror — never fails the save.
      let mirrored = false
      try {
        const status = str(b.status).trim() || 'active'
        const mirror: Record<string, unknown> = {
          [`users/${targetUid}/profile/name`]: profile.name,
          [`users/${targetUid}/profile/phone`]: profile.phone,
          [`users/${targetUid}/profile/company_info/system_id`]: systemId,
          [`users/${targetUid}/profile/company_info/employee_id`]: profile.employeeId,
          [`users/${targetUid}/profile/company_info/designation`]: profile.designation,
          [`users/${targetUid}/profile/company_info/role_id`]: profile.roleId,
          [`users/${targetUid}/profile/company_info/status`]: status,
          [`users/${targetUid}/profile/company_info/branch_ids`]: branchIds,
          [`users_by_systemId/${systemId}/uid`]: targetUid,
          [`users_by_systemId/${systemId}/status`]: status,
        }
        await firebaseUpdatePaths(mirror)
        const prevSystemId = str(b.previous_system_id).trim()
        if (prevSystemId && prevSystemId !== systemId) {
          await firebaseDelete(`users_by_systemId/${prevSystemId}`)
        }
        mirrored = true
      } catch (e) {
        errLog('user_upsert', 'mirror_failed', {
          system_id: systemId, err: e instanceof Error ? e.message.slice(0, 200) : String(e).slice(0, 200),
        })
      }
      console.info(`user_upsert ok: system_id=${systemId} mirrored=${mirrored}`)
      return reply({ ok: true, system_id: systemId, mirrored })
    }

    return reply({ error: 'Unknown action' }, 400)
  } catch (error) {
    return unhandled(action, identity, error)
  }
})
