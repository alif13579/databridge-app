// directory — branch + store + area directories. Actions: branch_upsert,
// branch_delete, store_upsert, store_delete, area_upsert, area_delete,
// backfill_branches, backfill_stores, backfill_areas.
//
// The branch directory persists ONLY to Supabase (branch_upsert /
// branch_delete, admin-or-manager gated server-side). backfill_* are one-way
// Firebase→Supabase drains that take no client content — only a trigger.

import { admin } from '../_shared/supabase.ts'
import { errLog, guardRequest, reply, unhandled } from '../_shared/http.ts'
import { firebaseDelete, firebaseUpdatePaths } from '../_shared/firebase-mirror.ts'
import {
  firebaseIdentity,
  firebaseProfile,
  firebaseRead,
} from '../_shared/firebase-auth.ts'

/** Next pure-numeric id above the highest numeric one in [existing].
 *  Legacy acronyms (BA, GDJS) and Firebase push-keys (-P-…) are ignored, so
 *  generated ids never collide with them. */
function nextNumericId(existing: unknown[]): string {
  let max = 0
  for (const v of existing) {
    if (typeof v !== 'string') continue
    const t = v.trim()
    if (/^\d+$/.test(t)) max = Math.max(max, parseInt(t, 10))
  }
  return String(max + 1)
}

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
      // Access slots are multi person + multi role now. New clients send
      // arrays; legacy singulars (old APKs) merge in so nothing is lost.
      const arr = (v: unknown): string[] => Array.isArray(v)
        ? [...new Set(v.filter((x): x is string => typeof x === 'string').map((x) => x.trim()).filter(Boolean))]
        : []
      const one = (v: unknown): string[] => {
        const t = str(v).trim()
        return t ? [t] : []
      }
      const mergeList = (...lists: string[][]) => [...new Set(lists.flat())]
      const managerUids = mergeList(arr(b?.manager_uids), one(b?.manager_uid))
      const managerRoles = mergeList(arr(b?.manager_roles), one(b?.manager_role))
      const accountantUids = mergeList(arr(b?.accountant_uids), one(b?.accountant_uid))
      const accountantRoles = mergeList(arr(b?.accountant_roles), one(b?.accountant_role))
      const pocUids = mergeList(arr(b?.petty_cash_poc_uids), one(b?.petty_cash_poc_uid))
      const pocRoles = mergeList(arr(b?.petty_cash_poc_roles), one(b?.petty_cash_poc_role))
      const staffUids = mergeList(arr(b?.staff_uids), one(b?.staff_uid))
      const staffRoles = mergeList(arr(b?.staff_roles), one(b?.staff_role))
      const first = (l: string[]) => l[0] ?? ''
      const row: Record<string, unknown> = {
        branch_id: branchId,
        branch_code: str(b.branch_code), name: str(b.name).trim(), branch_type: str(b.branch_type),
        region: typeof b.region === 'string' ? b.region : (existing?.region ?? ''),
        address: str(b.address), latitude: num(b.latitude), longitude: num(b.longitude),
        email: str(b.email), phone: str(b.phone),
        // Arrays are authoritative; singulars mirror the first entry so old
        // builds keep reading something sensible.
        manager_uid: first(managerUids), manager_uids: managerUids, manager_roles: managerRoles,
        accountant_uid: first(accountantUids), accountant_role: first(accountantRoles),
        accountant_uids: accountantUids, accountant_roles: accountantRoles,
        petty_cash_poc_uid: first(pocUids),
        petty_cash_poc_uids: pocUids, petty_cash_poc_roles: pocRoles,
        petty_cash_limit: typeof b.petty_cash_limit === 'number' && Number.isFinite(b.petty_cash_limit)
          ? b.petty_cash_limit : (existing?.petty_cash_limit ?? 0),
        staff_uid: first(staffUids), staff_role: first(staffRoles),
        staff_uids: staffUids, staff_roles: staffRoles,
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
      // Membership fan-out: an assignment on this form must actually WORK.
      // Every assigned person (by Firebase uid) and every holder of an assigned
      // role gets this branch in users.branch_ids (RLS) + the Firebase mirror
      // (RTDB reads). Additive only — unassigning here revokes the functional
      // flag immediately (resolveRoles reads this row live); lingering
      // visibility is trimmed via employee edit, the membership screen.
      // Best-effort: a fan-out failure never fails the branch save itself.
      try {
        const grantRoles = [...new Set([...managerRoles, ...accountantRoles, ...pocRoles, ...staffRoles])]
        const grantUidSet = new Set([...managerUids, ...accountantUids, ...pocUids, ...staffUids])
        const targets = new Map<string, { firebaseId: string; branches: string[] }>()
        if (grantUidSet.size > 0) {
          const { data } = await admin.from('users')
            .select('system_id,firebase_id,branch_ids').in('firebase_id', [...grantUidSet])
          for (const u of data ?? []) {
            const sys = u.system_id as string
            if (sys) targets.set(sys, { firebaseId: u.firebase_id as string ?? '', branches: (u.branch_ids as string[]) ?? [] })
          }
        }
        if (grantRoles.length > 0) {
          const { data } = await admin.from('users')
            .select('system_id,firebase_id,branch_ids').in('role', grantRoles)
          for (const u of data ?? []) {
            const sys = u.system_id as string
            if (sys && !targets.has(sys)) {
              targets.set(sys, { firebaseId: u.firebase_id as string ?? '', branches: (u.branch_ids as string[]) ?? [] })
            }
          }
        }
        let granted = 0
        const fbPaths: Record<string, unknown> = {}
        for (const [sys, t] of targets) {
          if (t.branches.includes(branchId)) continue
          const next = [...t.branches, branchId]
          const { error: memError } = await admin.from('users')
            .update({ branch_ids: next, updated_at: now }).eq('system_id', sys)
          if (memError) {
            console.error('branch role fan-out users update failed', sys, memError.message)
            continue
          }
          granted++
          if (t.firebaseId) fbPaths[`users/${t.firebaseId}/profile/company_info/branch_ids`] = next
        }
        if (Object.keys(fbPaths).length > 0) {
          try {
            await firebaseUpdatePaths(fbPaths)
          } catch (e) {
            errLog('branch_upsert', 'fanout_mirror_failed', {
              branch_id: branchId, err: e instanceof Error ? e.message.slice(0, 200) : String(e).slice(0, 200),
            })
          }
        }
        console.info(`branch_upsert ok: branch=${branchId} fanout_granted=${granted}`)
      } catch (e) {
        errLog('branch_upsert', 'fanout_failed', {
          branch_id: branchId, err: e instanceof Error ? e.message.slice(0, 200) : String(e).slice(0, 200),
        })
      }
      // Unassign cleanup for removed_uids (edit flow): strip the branch from
      // holders that match NO current assignment on this branch — neither a
      // uid slot nor (via their users.role) a role slot. Holders still covered
      // another way keep their membership. Best-effort like the fan-out above.
      try {
        const removed = arr(body.removed_uids)
        if (removed.length > 0) {
          const coveredRoles = new Set([...managerRoles, ...accountantRoles, ...pocRoles, ...staffRoles])
          const coveredUids = new Set([...managerUids, ...accountantUids, ...pocUids, ...staffUids])
          const { data: exUsers } = await admin.from('users')
            .select('system_id,firebase_id,branch_ids,role').in('firebase_id', removed)
          let stripped = 0
          const fbStrip: Record<string, unknown> = {}
          for (const u of exUsers ?? []) {
            const sys = u.system_id as string
            if (!sys) continue
            const uFb = (u.firebase_id as string) ?? ''
            const uRole = (u.role as string) ?? ''
            if (coveredUids.has(uFb)) continue
            if (uRole && coveredRoles.has(uRole)) continue
            const cur = ((u.branch_ids as string[]) ?? []).filter((x) => x !== branchId)
            if (cur.length === ((u.branch_ids as string[]) ?? []).length) continue
            const { error: stripError } = await admin.from('users')
              .update({ branch_ids: cur, updated_at: now }).eq('system_id', sys)
            if (stripError) {
              console.error('branch unassign strip failed', sys, stripError.message)
              continue
            }
            stripped++
            if (uFb) fbStrip[`users/${uFb}/profile/company_info/branch_ids`] = cur
          }
          if (Object.keys(fbStrip).length > 0) {
            try {
              await firebaseUpdatePaths(fbStrip)
            } catch (e) {
              errLog('branch_upsert', 'strip_mirror_failed', {
                branch_id: branchId, err: e instanceof Error ? e.message.slice(0, 200) : String(e).slice(0, 200),
              })
            }
          }
          console.info(`branch_upsert unassign: branch=${branchId} stripped=${stripped}`)
        }
      } catch (e) {
        errLog('branch_upsert', 'strip_failed', {
          branch_id: branchId, err: e instanceof Error ? e.message.slice(0, 200) : String(e).slice(0, 200),
        })
      }
      // Best-effort Firebase backup mirror (Supabase is authoritative).
      try {
        await firebaseUpdatePaths({
          [`branches/${branchId}/name`]: row.name,
          [`branches/${branchId}/branch_code`]: row.branch_code,
          [`branches/${branchId}/branch_type`]: row.branch_type,
          [`branches/${branchId}/region`]: row.region,
          [`branches/${branchId}/address`]: row.address,
          [`branches/${branchId}/phone`]: row.phone,
          [`branches/${branchId}/email`]: row.email,
          [`branches/${branchId}/status`]: row.status,
          [`branches/${branchId}/petty_cash_limit`]: row.petty_cash_limit,
        })
      } catch (e) {
        errLog('branch_upsert', 'mirror_failed', {
          branch_id: branchId, err: e instanceof Error ? e.message.slice(0, 200) : String(e).slice(0, 200),
        })
      }
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
      // No users-table write here: branch membership lives ONLY in employee
      // edit. A deleted branch_id may linger in some users.branch_ids until
      // admin cleans it there — harmless (no branch row, no data under it).
      const { error } = await admin.from('branches').delete().eq('branch_id', branchId)
      if (error) throw error
      console.info(`branch_delete ok: branch=${branchId}`)
      try {
        await firebaseDelete(`branches/${branchId}`)
      } catch (e) {
        errLog('branch_delete', 'mirror_failed', {
          branch_id: branchId, err: e instanceof Error ? e.message.slice(0, 200) : String(e).slice(0, 200),
        })
      }
      return reply({ ok: true })
    }

    if (action === 'store_upsert') {
      // Authoritative store write: the store directory (Config → Stores)
      // persists ONLY to Supabase now — same posture as branch_upsert above
      // (admin-or-manager gated, client supplies form fields). conveyance_amount
      // is the fixed pickup payout prefetched by the request form; null/0 =
      // not set (old behavior: amount hidden + 0).
      const profile = await firebaseProfile(identity)
      if (profile.roleId !== 'admin' && profile.roleId !== 'manager') {
        errLog('store_upsert', 'forbidden', { role: profile.roleId })
        return reply({ error: 'Only admin or manager can save stores' }, 403)
      }
      const s = body.store
      const str = (v: unknown) => typeof v === 'string' ? v : ''
      let storeId = s ? str(s.store_id).trim() : ''
      if (!str(s?.name).trim()) return reply({ error: 'Store name is required' }, 400)
      const amountRaw = s?.conveyance_amount
      const amount = typeof amountRaw === 'number' && Number.isFinite(amountRaw) && amountRaw > 0 ? amountRaw : null
      const fields = {
        name: str(s.name).trim(), address: str(s.address),
        area_id: str(s.area_id).trim(), area_name: str(s.area_name).trim(),
        phone: str(s.phone), conveyance_amount: amount,
      }
      if (!storeId) {
        // Auto ID (new app): next integer above the highest numeric store_id.
        // Plain INSERT (not upsert) + retry: two admins racing to the same
        // number must collide with 23505 and retry, never silently overwrite
        // each other's row.
        for (let attempt = 0; attempt < 5 && !storeId; attempt++) {
          const { data: rows, error: readError } = await admin.from('stores').select('store_id')
          if (readError) throw readError
          const candidate = nextNumericId((rows ?? []).map((r) => r.store_id))
          const { error } = await admin.from('stores').insert({ store_id: candidate, ...fields })
          if (!error) {
            storeId = candidate
          } else if (error.code !== '23505') {
            errLog('store_upsert', 'db_insert_failed', { pg_code: error.code, pg_message: error.message })
            throw error
          }
        }
        if (!storeId) return reply({ error: 'Could not allocate a store ID, please retry' }, 409)
      } else {
        const { error } = await admin.from('stores').upsert({ store_id: storeId, ...fields }, { onConflict: 'store_id' })
        if (error) {
          errLog('store_upsert', 'db_upsert_failed', { store_id: storeId, pg_code: error.code, pg_message: error.message })
          throw error
        }
      }
      console.info(`store_upsert ok: store=${storeId}`)
      try {
        await firebaseUpdatePaths({
          [`courier/stores/${storeId}/name`]: str(s.name).trim(),
          [`courier/stores/${storeId}/address`]: str(s.address),
          [`courier/stores/${storeId}/area_id`]: str(s.area_id).trim(),
          [`courier/stores/${storeId}/area_name`]: str(s.area_name).trim(),
          [`courier/stores/${storeId}/phone`]: str(s.phone),
          [`courier/stores/${storeId}/conveyance_amount`]: amount,
        })
      } catch (e) {
        errLog('store_upsert', 'mirror_failed', {
          store_id: storeId, err: e instanceof Error ? e.message.slice(0, 200) : String(e).slice(0, 200),
        })
      }
      return reply({ ok: true, store_id: storeId })
    }

    if (action === 'store_delete') {
      // Same admin/manager gate. No FK references stores, so a plain delete
      // is safe — existing claims keep their copied store_name snapshot.
      const profile = await firebaseProfile(identity)
      if (profile.roleId !== 'admin' && profile.roleId !== 'manager') {
        errLog('store_delete', 'forbidden', { role: profile.roleId })
        return reply({ error: 'Only admin or manager can delete stores' }, 403)
      }
      const storeId = typeof body.store_id === 'string' ? body.store_id.trim() : ''
      if (!storeId) return reply({ error: 'store_id is required' }, 400)
      const { error } = await admin.from('stores').delete().eq('store_id', storeId)
      if (error) throw error
      console.info(`store_delete ok: store=${storeId}`)
      try {
        await firebaseDelete(`courier/stores/${storeId}`)
      } catch (e) {
        errLog('store_delete', 'mirror_failed', {
          store_id: storeId, err: e instanceof Error ? e.message.slice(0, 200) : String(e).slice(0, 200),
        })
      }
      return reply({ ok: true })
    }

    if (action === 'area_upsert') {
      // Authoritative area write: the branch-wise area directory (Config →
      // Areas) persists ONLY to Supabase — same posture as store_upsert
      // (admin-or-manager gated, client supplies form fields). Same
      // (branch_id, area_id) twice = update. Claims/stores keep display
      // snapshots (names), so renames never rewrite history.
      const profile = await firebaseProfile(identity)
      if (profile.roleId !== 'admin' && profile.roleId !== 'manager') {
        errLog('area_upsert', 'forbidden', { role: profile.roleId })
        return reply({ error: 'Only admin or manager can save areas' }, 403)
      }
      const a = body.area
      const str = (v: unknown) => typeof v === 'string' ? v : ''
      const branchId = a ? str(a.branch_id).trim() : ''
      let areaId = a ? str(a.area_id).trim() : ''
      if (!branchId) return reply({ error: 'branch_id is required' }, 400)
      if (!str(a?.name).trim()) return reply({ error: 'Area name is required' }, 400)
      const areaType = ['pickup', 'delivery', 'both'].includes(str(a?.area_type).trim())
        ? str(a.area_type).trim() : 'both'
      const { data: branch } = await admin.from('branches').select('branch_id').eq('branch_id', branchId).maybeSingle()
      if (!branch) return reply({ error: 'Unknown branch_id' }, 400)
      const now = new Date().toISOString()
      if (!areaId) {
        // Auto ID (new app): next integer above this branch's highest numeric
        // area_id. Plain INSERT + retry so concurrent creates collide with
        // 23505 and retry instead of overwriting each other.
        for (let attempt = 0; attempt < 5 && !areaId; attempt++) {
          const { data: rows, error: readError } = await admin.from('areas')
            .select('area_id').eq('branch_id', branchId)
          if (readError) throw readError
          const candidate = nextNumericId((rows ?? []).map((r) => r.area_id))
          const { error } = await admin.from('areas').insert({
            branch_id: branchId, area_id: candidate,
            name: str(a.name).trim(), area_type: areaType, zone: str(a.zone).trim(),
            updated_at: now,
          })
          if (!error) {
            areaId = candidate
          } else if (error.code !== '23505') {
            errLog('area_upsert', 'db_insert_failed', { branch_id: branchId, pg_code: error.code, pg_message: error.message })
            throw error
          }
        }
        if (!areaId) return reply({ error: 'Could not allocate an area ID, please retry' }, 409)
        console.info(`area_upsert ok (auto id): branch=${branchId} area=${areaId}`)
      } else {
        const { data: existing } = await admin.from('areas').select('created_at').eq('branch_id', branchId).eq('area_id', areaId).maybeSingle()
        const { error } = await admin.from('areas').upsert({
          branch_id: branchId, area_id: areaId,
          name: str(a.name).trim(), area_type: areaType, zone: str(a.zone).trim(),
          updated_at: now, ...(existing?.created_at ? { created_at: existing.created_at } : {}),
        }, { onConflict: 'branch_id,area_id' })
        if (error) {
          errLog('area_upsert', 'db_upsert_failed', { branch_id: branchId, area_id: areaId, pg_code: error.code, pg_message: error.message })
          throw error
        }
        console.info(`area_upsert ok: branch=${branchId} area=${areaId}`)
      }
      try {
        await firebaseUpdatePaths({
          [`courier/areas_backup/${branchId}/${areaId}/name`]: str(a.name).trim(),
          [`courier/areas_backup/${branchId}/${areaId}/area_type`]: areaType,
          [`courier/areas_backup/${branchId}/${areaId}/zone`]: str(a.zone).trim(),
        })
      } catch (e) {
        errLog('area_upsert', 'mirror_failed', {
          branch_id: branchId, area_id: areaId, err: e instanceof Error ? e.message.slice(0, 200) : String(e).slice(0, 200),
        })
      }
      return reply({ ok: true, branch_id: branchId, area_id: areaId })
    }

    if (action === 'area_delete') {
      // Same admin/manager gate. No FK references areas (claims/stores keep
      // name snapshots), so a plain delete is safe.
      const profile = await firebaseProfile(identity)
      if (profile.roleId !== 'admin' && profile.roleId !== 'manager') {
        errLog('area_delete', 'forbidden', { role: profile.roleId })
        return reply({ error: 'Only admin or manager can delete areas' }, 403)
      }
      const branchId = typeof body.branch_id === 'string' ? body.branch_id.trim() : ''
      const areaId = typeof body.area_id === 'string' ? body.area_id.trim() : ''
      if (!branchId || !areaId) return reply({ error: 'branch_id and area_id are required' }, 400)
      const { error } = await admin.from('areas').delete().eq('branch_id', branchId).eq('area_id', areaId)
      if (error) throw error
      console.info(`area_delete ok: branch=${branchId} area=${areaId}`)
      try {
        await firebaseDelete(`courier/areas_backup/${branchId}/${areaId}`)
      } catch (e) {
        errLog('area_delete', 'mirror_failed', {
          branch_id: branchId, area_id: areaId, err: e instanceof Error ? e.message.slice(0, 200) : String(e).slice(0, 200),
        })
      }
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
      // Preserve admin-set conveyance_amount across re-runs: Firebase has
      // no such field, so a blind upsert would wipe it back to null.
      const { data: existing } = await admin.from('stores').select('store_id,conveyance_amount')
      const kept = new Map((existing ?? []).map((r) => [r.store_id as string, r.conveyance_amount as number | null]))
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
            conveyance_amount: kept.get(storeId) ?? null,
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

    if (action === 'backfill_areas') {
      // One-way drain for the area picker directories: Firebase
      // `courier/areas/{delivery_area,pickup_area}` → Supabase public.areas.
      // Firebase areas are courier-wide (no branch), Supabase areas are
      // branch-wise — so every Firebase area is copied into EVERY branch
      // (preserves the old behavior where all branches saw the same areas;
      // admins then curate per branch). An area_id present in both Firebase
      // dirs lands once per branch as type 'both'. Idempotent upsert on
      // (branch_id, area_id) — safe to re-run any time; existing rows keep
      // their admin-set zone (Firebase has no such field).
      const { data: branches } = await admin.from('branches').select('branch_id')
      const branchIds = (branches ?? []).map((b) => b.branch_id as string).filter(Boolean)
      if (branchIds.length === 0) return reply({ ok: true, synced: 0 })
      const { data: existing } = await admin.from('areas').select('branch_id,area_id,area_type,zone')
      const kept = new Map((existing ?? []).map((r) => [
        `${r.branch_id as string}::${r.area_id as string}`,
        { type: r.area_type as string, zone: r.zone as string },
      ]))
      const str = (v: unknown) => typeof v === 'string' ? v : ''
      // area_id -> {name, types} merged across both Firebase dirs first, so
      // each id is written once per branch (never clobbering its own type).
      const merged = new Map<string, { name: string; types: Set<string> }>()
      for (const [dir, type] of [['courier/areas/delivery_area', 'delivery'], ['courier/areas/pickup_area', 'pickup']] as const) {
        const all = await firebaseRead(identity, dir) as Record<string, Record<string, unknown>> | null
        if (!all || typeof all !== 'object') continue
        for (const [, s] of Object.entries(all)) {
          if (!s || typeof s !== 'object') continue
          const areaId = str(s.areaId).trim() || str(s.area_id).trim()
          const name = str(s.name).trim()
          if (!areaId || !name) continue
          const entry = merged.get(areaId) ?? { name, types: new Set<string>() }
          entry.types.add(type)
          if (!entry.name) entry.name = name
          merged.set(areaId, entry)
        }
      }
      let synced = 0
      const failed: string[] = []
      const now = new Date().toISOString()
      for (const branchId of branchIds) {
        for (const [areaId, entry] of merged) {
          try {
            const prev = kept.get(`${branchId}::${areaId}`)
            const type = prev?.type ?? (entry.types.size > 1 ? 'both' : [...entry.types][0])
            const { error } = await admin.from('areas').upsert({
              branch_id: branchId, area_id: areaId, name: entry.name,
              area_type: type, zone: prev?.zone ?? '',
              updated_at: now,
            }, { onConflict: 'branch_id,area_id' })
            if (error) throw error
            synced++
          } catch (e) {
            const msg = e instanceof Error ? e.message : String(e)
            errLog('backfill_areas', 'row_upsert_failed', { branch_id: branchId, area_id: areaId, err: msg.slice(0, 200) })
            if (failed.length < 20) failed.push(`${branchId}/${areaId}: ${msg.slice(0, 120)}`)
          }
        }
      }
      console.info(`backfill_areas ok: synced=${synced} failed=${failed.length}`)
      return reply({ ok: true, synced, failed })
    }

    return reply({ error: 'Unknown action' }, 400)
  } catch (error) {
    return unhandled(action, identity, error)
  }
})
