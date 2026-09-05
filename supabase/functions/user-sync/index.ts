// user-sync — identity + push-token registry. Actions: sync_profile,
// register_push_token, unregister_push_token, backfill_user, user_upsert.
//
// user_upsert is the Supabase-FIRST employee create/edit path (admin UI):
// the users row is written authoritatively, then Firebase gets a best-effort
// backup mirror (profile + system-id index) so a future Firebase removal
// breaks nothing. Repair paths (sync_profile/backfill_user/remark writes)
// still copy Firebase → Supabase; the mirrors keep both sides identical so
// those copies converge instead of clobbering.
//
// This establishes the Firebase UID → public.users mapping that every
// branch-scoped RLS policy reads through. Called at login / token rotation
// (and on demand before RLS-gated reads), never in a hot loop.

import { admin } from '../_shared/supabase.ts'
import { errLog, guardRequest, reply, unhandled } from '../_shared/http.ts'
import {
  ensureAuthenticatedRoleClaim,
  firebaseIdentity,
  firebaseProfile,
  firebaseProfileForUid,
} from '../_shared/firebase-auth.ts'
import { firebaseDelete, firebaseUpdatePaths } from '../_shared/firebase-mirror.ts'
import { upsertUser } from '../_shared/users.ts'
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

    // The app must be able to read validations before this user has ever saved a
    // remark. In particular, a worker can receive a CC remark as their first
    // interaction. Upserting through the trusted function establishes the
    // Firebase UID + branch_ids mapping used by validations RLS.
    if (action === 'sync_profile') {
      const profile = await firebaseProfile(identity)
      await upsertUser(profile, identity.uid)
      // Without this claim, Supabase's Third-Party Auth runs every direct REST/Realtime
      // request from this user as the `anon` Postgres role — RLS policies scoped
      // `to authenticated` never evaluate, regardless of correct branch_ids mapping.
      await ensureAuthenticatedRoleClaim(identity.uid)
      console.info(`sync_profile ok: system_id=${profile.systemId}, branches=${profile.branchIds.length}`)
      return reply({ ok: true, system_id: profile.systemId, branch_count: profile.branchIds.length })
    }

    if (action === 'register_push_token') {
      if (typeof body.token !== 'string' || body.token.trim().length < 20) {
        return reply({ error: 'Invalid push token' }, 400)
      }
      const profile = await firebaseProfile(identity)
      // Push registration is normally the earliest authenticated app action.
      // Keep the RLS identity mapping current here too, rather than waiting for
      // the user's first remark save.
      await upsertUser(profile, identity.uid)
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
      // One-way user drain for the Firebase→Supabase claims migration (see
      // FirebaseClaimsMigrator.kt): ensures a claim actor's public.users row
      // exists BEFORE their claims are copied, so copied rows' actor names
      // resolve via join and the 100% read-back check passes.
      //
      // Security posture matches upsertUser's DO-NOT rule: the ONLY client
      // input is the target Firebase uid. Name/system_id/branches all come
      // from the server-side Firebase read above — a caller can only repair
      // the target's truthful row (upsert keyed on system_id, firebase_id
      // always the real uid, never NULL), never inject another identity.
      if (typeof body.firebase_uid !== 'string' || !body.firebase_uid.trim()) {
        return reply({ error: 'firebase_uid is required' }, 400)
      }
      const targetUid = body.firebase_uid.trim()
      const target = await firebaseProfileForUid(targetUid, identity)
      if (!target) {
        errLog('backfill_user', 'no_firebase_profile', { firebase_uid: targetUid })
        return reply({ error: 'No Firebase profile with system_id for this user' }, 404)
      }
      await upsertUser(target, targetUid)
      // Defensive, same as the write path: covers a user whose reads would
      // otherwise run as anon before ever syncing themselves.
      await ensureAuthenticatedRoleClaim(targetUid)
      console.info(`backfill_user ok: system_id=${target.systemId}`)
      return reply({ ok: true, system_id: target.systemId })
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
