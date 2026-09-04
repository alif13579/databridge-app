// directory — branch + store directories. Actions: branch_upsert,
// branch_delete, backfill_branches, backfill_stores.
//
// The branch directory persists ONLY to Supabase (branch_upsert /
// branch_delete, admin-or-manager gated server-side). backfill_* are one-way
// Firebase→Supabase drains that take no client content — only a trigger.

import { admin } from '../_shared/supabase.ts'
import { errLog, guardRequest, reply, unhandled } from '../_shared/http.ts'
import {
  firebaseIdentity,
  firebaseProfile,
  firebaseProfileForUid,
  firebaseRead,
} from '../_shared/firebase-auth.ts'
import { upsertUser } from '../_shared/users.ts'

Deno.serve(async (request) => {
  const guard = guardRequest(request)
  if (guard) return guard
  let action: string | undefined
  let identity: { uid: string; token: string } | undefined
  try {
    identity = await firebaseIdentity(request)
    const body = await request.json()
    action = body.action

    if (action === 'branch_upsert') {
      // Authoritative branch write: the branch directory (BranchCreate/
      // BranchEditFragment) persists ONLY to Supabase now — the old Firebase
      // `branches/{id}` write is removed app-side. Client supplies the form
      // fields; *_name/updated_log/employees have no Supabase column and are
      // ignored (names resolve via the users join / Firebase profiles).
      //
      // Gating mirrors EmployeeFragment.canManageBranches(): admin or manager
      // role only. Role comes from the server-side Firebase profile read, so
      // a caller cannot self-grant it.
      const profile = await firebaseProfile(identity)
      if (profile.roleId !== 'admin' && profile.roleId !== 'manager') {
        errLog('branch_upsert', 'forbidden', { role: profile.roleId })
        return reply({ error: 'Only admin or manager can save branches' }, 403)
      }
      const b = body.branch
      const str = (v: unknown) => typeof v === 'string' ? v : ''
      const num = (v: unknown) => typeof v === 'number' && Number.isFinite(v) ? v : 0
      const branchId = b ? str(b.branch_id).trim() : ''
      if (!branchId) return reply({ error: 'branch_id is required' }, 400)
      if (!str(b?.name).trim()) return reply({ error: 'Branch name is required' }, 400)
      if (!str(b?.branch_code).trim()) return reply({ error: 'Branch code is required' }, 400)
      const { data: existing } = await admin.from('branches').select('*').eq('branch_id', branchId).maybeSingle()
      const now = new Date().toISOString()
      const row: Record<string, unknown> = {
        branch_id: branchId,
        branch_code: str(b.branch_code), name: str(b.name).trim(), branch_type: str(b.branch_type),
        region: typeof b.region === 'string' ? b.region : (existing?.region ?? ''),
        address: str(b.address), latitude: num(b.latitude), longitude: num(b.longitude),
        email: str(b.email), phone: str(b.phone),
        manager_uid: str(b.manager_uid),
        accountant_uid: str(b.accountant_uid), accountant_role: str(b.accountant_role),
        petty_cash_poc_uid: str(b.petty_cash_poc_uid),
        petty_cash_limit: typeof b.petty_cash_limit === 'number' && Number.isFinite(b.petty_cash_limit)
          ? b.petty_cash_limit : (existing?.petty_cash_limit ?? 0),
        staff_uid: str(b.staff_uid), staff_role: str(b.staff_role),
        parent_branch_id: str(b.parent_branch_id),
        status: str(b.status) || 'active',
        image_url: str(b.image_url),
        created_by: (existing?.created_by as string) || identity.uid,
        updated_at: now,
      }
      if (existing?.created_at) row.created_at = existing.created_at
      const { error } = await admin.from('branches').upsert(row, { onConflict: 'branch_id' })
      if (error) {
        errLog('branch_upsert', 'db_upsert_failed', { branch_id: branchId, pg_code: error.code, pg_message: error.message })
        throw error
      }
      // Keep RLS membership working immediately: the app also maintains the
      // Firebase profile branch_ids (which sync_profile picks up on next
      // login), but a newly assigned manager opening Petty Cash right now
      // would otherwise fail the branch-scoped reads until then. Best-effort
      // — a failure here never fails the branch save itself.
      const assignedUids = [str(b.manager_uid), str(b.accountant_uid), str(b.petty_cash_poc_uid), str(b.staff_uid)]
        .map((u) => u.trim()).filter(Boolean)
      const removedUids = Array.isArray(b.removed_uids)
        ? b.removed_uids.filter((u: unknown): u is string => typeof u === 'string' && !!u.trim()).map((u: string) => u.trim())
        : []
      const membershipErrors: string[] = []
      for (const uid of assignedUids) {
        try {
          const p = await firebaseProfileForUid(uid, identity)
          if (!p) continue
          const merged = [...new Set([...p.branchIds, branchId])]
          if (merged.length !== p.branchIds.length) await upsertUser({ ...p, branchIds: merged }, uid)
        } catch (e) {
          membershipErrors.push(uid)
        }
      }
      for (const uid of removedUids) {
        if (assignedUids.includes(uid)) continue
        try {
          const p = await firebaseProfileForUid(uid, identity)
          if (p) {
            const filtered = p.branchIds.filter((id) => id !== branchId)
            if (filtered.length !== p.branchIds.length) await upsertUser({ ...p, branchIds: filtered }, uid)
          } else {
            // Orphaned uid (no Firebase profile): strip the branch from
            // whatever users row still carries this firebase_id.
            const { data: rows } = await admin.from('users').select('system_id,branch_ids').eq('firebase_id', uid)
            for (const r of rows ?? []) {
              const ids = Array.isArray(r.branch_ids) ? r.branch_ids.filter((id: unknown) => id !== branchId) : []
              await admin.from('users').update({ branch_ids: ids }).eq('system_id', r.system_id)
            }
          }
        } catch (e) {
          membershipErrors.push(uid)
        }
      }
      console.info(`branch_upsert ok: branch=${branchId} membership_errors=${membershipErrors.length}`)
      return reply({ ok: true, branch_id: branchId })
    }

    if (action === 'branch_delete') {
      // Deletes a Supabase branch row. Same admin/manager gate as
      // branch_upsert above. Refuses when claims still reference the branch
      // (claims_branch_id_fkey) instead of cascading — history stays intact.
      const profile = await firebaseProfile(identity)
      if (profile.roleId !== 'admin' && profile.roleId !== 'manager') {
        errLog('branch_delete', 'forbidden', { role: profile.roleId })
        return reply({ error: 'Only admin or manager can delete branches' }, 403)
      }
      const branchId = typeof body.branch_id === 'string' ? body.branch_id.trim() : ''
      if (!branchId) return reply({ error: 'branch_id is required' }, 400)
      const { data: refs, error: refError } = await admin.from('claims').select('id').eq('branch_id', branchId).limit(1)
      if (refError) throw refError
      if (refs && refs.length > 0) {
        return reply({ error: 'This branch has claims and cannot be deleted. Mark it inactive instead.' }, 409)
      }
      // Strip RLS membership first so no one keeps access via a deleted branch.
      const { data: members } = await admin.from('users').select('system_id,branch_ids').contains('branch_ids', [branchId])
      for (const m of members ?? []) {
        const ids = Array.isArray(m.branch_ids) ? m.branch_ids.filter((id: unknown) => id !== branchId) : []
        await admin.from('users').update({ branch_ids: ids }).eq('system_id', m.system_id)
      }
      const { error } = await admin.from('branches').delete().eq('branch_id', branchId)
      if (error) throw error
      console.info(`branch_delete ok: branch=${branchId}`)
      return reply({ ok: true })
    }

    if (action === 'backfill_branches') {
      // One-way directory drain: Firebase `branches/{id}` → Supabase
      // public.branches. Run from Reports → Sync directory.
      //
      // Security posture matches backfill_user: the client sends NO branch
      // content, only the trigger. Every field below comes from the
      // server-side Firebase read with the caller's own token (branches are
      // readable by any authenticated user per database.rules.json), so a
      // caller can only copy the truthful directory, never inject rows.
      // Idempotent upsert on branch_id — safe to re-run any time.
      const all = await firebaseRead(identity, 'branches') as Record<string, Record<string, unknown>> | null
      if (!all || typeof all !== 'object') return reply({ ok: true, synced: 0 })
      const str = (v: unknown) => typeof v === 'string' ? v : ''
      const num = (v: unknown) => typeof v === 'number' && Number.isFinite(v) ? v : 0
      const millisToIso = (v: unknown) => {
        const n = typeof v === 'number' ? v : typeof v === 'string' ? Number(v) : 0
        if (!Number.isFinite(n) || n <= 0) return undefined
        return new Date(n > 0 && n < 100_000_000_000 ? n * 1000 : n).toISOString()
      }
      let synced = 0
      const failed: string[] = []
      for (const [key, b] of Object.entries(all)) {
        if (!b || typeof b !== 'object') continue
        const branchId = str(b.branch_id).trim() || key.trim()
        if (!branchId) continue
        try {
          const row: Record<string, unknown> = {
            branch_id: branchId,
            branch_code: str(b.branch_code), name: str(b.name), branch_type: str(b.branch_type),
            region: str((b as Record<string, unknown>).region),
            address: str(b.address), latitude: num(b.latitude), longitude: num(b.longitude),
            email: str(b.email), phone: str(b.phone),
            manager_uid: str(b.manager_uid),
            accountant_uid: str(b.accountant_uid), accountant_role: str(b.accountant_role),
            petty_cash_poc_uid: str(b.petty_cash_poc_uid),
            petty_cash_limit: num((b as Record<string, unknown>).petty_cash_limit),
            staff_uid: str(b.staff_uid), staff_role: str(b.staff_role),
            parent_branch_id: str(b.parent_branch_id),
            status: str(b.status) || 'active',
            image_url: str(b.image_url), created_by: str(b.created_by),
          }
          // Firebase *_name / employees / updated_log have no Supabase
          // column — deliberately not copied (names resolve via users join).
          const createdIso = millisToIso(b.created_at)
          const updatedIso = millisToIso(b.updated_at)
          if (createdIso) row.created_at = createdIso
          if (updatedIso) row.updated_at = updatedIso
          const { error } = await admin.from('branches').upsert(row, { onConflict: 'branch_id' })
          if (error) throw error
          synced++
        } catch (e) {
          const msg = e instanceof Error ? e.message : String(e)
          errLog('backfill_branches', 'row_upsert_failed', { branch_id: branchId, err: msg.slice(0, 200) })
          if (failed.length < 20) failed.push(`${branchId}: ${msg.slice(0, 120)}`)
        }
      }
      console.info(`backfill_branches ok: synced=${synced} failed=${failed.length}`)
      return reply({ ok: true, synced, failed })
    }

    if (action === 'backfill_stores') {
      // Same one-way drain for the store picker directory: Firebase
      // `courier/stores/{id}` → Supabase public.stores. The request form's
      // store picker reads ONLY Supabase (SupabaseClaimsReader.fetchStores),
      // so an empty public.stores shows "No stores available" on every
      // Pickup claim. Same no-client-content posture and idempotency as
      // backfill_branches above.
      const all = await firebaseRead(identity, 'courier/stores') as Record<string, Record<string, unknown>> | null
      if (!all || typeof all !== 'object') return reply({ ok: true, synced: 0 })
      const str = (v: unknown) => typeof v === 'string' ? v : ''
      let synced = 0
      const failed: string[] = []
      for (const [key, s] of Object.entries(all)) {
        if (!s || typeof s !== 'object') continue
        const storeId = str(s.storeId).trim() || str(s.store_id).trim() || key.trim()
        if (!storeId) continue
        try {
          const { error } = await admin.from('stores').upsert({
            store_id: storeId,
            name: str(s.name), address: str(s.address),
            area_id: str(s.areaId).trim() || str(s.area_id).trim(),
            area_name: str(s.areaName).trim() || str(s.area_name).trim(),
            phone: str(s.phone),
          }, { onConflict: 'store_id' })
          if (error) throw error
          synced++
        } catch (e) {
          const msg = e instanceof Error ? e.message : String(e)
          errLog('backfill_stores', 'row_upsert_failed', { store_id: storeId, err: msg.slice(0, 200) })
          if (failed.length < 20) failed.push(`${storeId}: ${msg.slice(0, 120)}`)
        }
      }
      console.info(`backfill_stores ok: synced=${synced} failed=${failed.length}`)
      return reply({ ok: true, synced, failed })
    }

    return reply({ error: 'Unknown action' }, 400)
  } catch (error) {
    return unhandled(action, identity, error)
  }
})
