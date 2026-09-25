// claims-bulk-import — Accounts-only bulk settled-claim import. Action: bulk_import.
//
// The app's Bulk Import screen sends validated sheet rows here; each row is
// inserted DIRECTLY as settled with its approve/settled amounts (the normal
// claim_upsert path forbids new claims carrying approved/settled money, and
// walking hundreds of backfill rows through verify→approve→settle one by one
// is impractical). Every date on the row (requested/submit/code) is the
// sheet's expense date — the upload day never matters.
//
// Safety, same posture as claims/claim_upsert:
// - Firebase identity + server-side role: caller must hold Accounts
//   (accountant slot/role) or admin/manager on the row's branch.
// - Agent must exist in users and belong to the same branch.
// - Category must be an active claim_categories row; conveyance group
//   requires From + To.
// - requested == approved >= 0, 0 <= settled <= approved, and requested is
//   within the branch petty_cash_limit (when set).
// - Per-row client_submit_id: a retried batch skips already-inserted rows
//   (counted as duplicates, not failures).
// - Wallet balance needs no touch: the auto-reconcile trigger recomputes
//   deposits − settled on every claims change.

import { admin } from '../_shared/supabase.ts'
import { errLog, guardRequest, reply, unhandled } from '../_shared/http.ts'
import { firebaseIdentity, firebaseProfile } from '../_shared/firebase-auth.ts'
import { requireUsersRow } from '../_shared/users.ts'
import { sendBulkSettledPush } from '../_shared/claim-push.ts'

Deno.serve(async (request) => {
  const guard = guardRequest(request)
  if (guard) return guard
  let action: string | undefined
  let identity: { uid: string; token: string } | undefined
  try {
    identity = await firebaseIdentity(request)
    const body = await request.json()
    action = body.action
    if (action !== 'bulk_import') return reply({ error: 'Unknown action' }, 400)

    const str = (v: unknown) => typeof v === 'string' ? v : ''
    const num = (v: unknown) => typeof v === 'number' && Number.isFinite(v) ? v : NaN
    // Whole-number quantity: undefined = absent (category default applies),
    // NaN = present but invalid (whole number >= 0 required).
    const qint = (v: unknown): number | undefined => {
      if (v === null || v === undefined || v === '') return undefined
      const n = typeof v === 'number' ? v : Number(String(v).trim())
      if (!Number.isFinite(n) || Math.trunc(n) !== n || n < 0) return NaN
      return n
    }
    const branchId = str(body.branch_id).trim()
    const batch = str(body.batch).trim() || `bulk-${Date.now()}`
    const rows: unknown[] = Array.isArray(body.rows) ? body.rows : []
    if (!branchId) return reply({ error: 'branch_id is required' }, 400)
    if (rows.length === 0) return reply({ error: 'No rows' }, 400)
    if (rows.length > 2000) return reply({ error: 'Max 2000 rows per batch' }, 400)

    const profile = await firebaseProfile(identity)
    const callerUid = identity.uid
    const callerSystemId = (profile.systemId || '').trim()
    const callerRole = (profile.roleId || '').trim()
    if (!callerSystemId) return reply({ error: 'Signed-in user has no system_id' }, 403)
    if (!await requireUsersRow(callerSystemId)) {
      return reply({ error: 'Your employee profile is missing — ask admin to add you in employee edit' }, 403)
    }
    const isAdmin = callerRole === 'admin' || callerRole === 'manager'
    const { data: branch } = await admin.from('branches')
      .select('branch_id,accountant_uid,accountant_uids,accountant_role,accountant_roles,petty_cash_limit')
      .eq('branch_id', branchId).maybeSingle()
    if (!branch) return reply({ error: 'Branch not found' }, 400)
    const asList = (v: unknown): string[] => {
      if (Array.isArray(v)) return v.map((x) => String(x ?? '').trim()).filter((s) => s !== '')
      return typeof v === 'string' && v.trim() !== '' ? [v.trim()] : []
    }
    const canAccounts = isAdmin ||
      asList((branch as Record<string, unknown>).accountant_uids).includes(callerUid) ||
      asList((branch as Record<string, unknown>).accountant_uid).includes(callerUid) ||
      (callerRole !== '' && asList((branch as Record<string, unknown>).accountant_roles).includes(callerRole)) ||
      asList((branch as Record<string, unknown>).accountant_role).includes(callerRole)
    if (!canAccounts) {
      errLog('bulk_import', 'forbidden_not_accounts', { uid: callerUid })
      return reply({ error: 'Only Accounts can bulk-import claims' }, 403)
    }
    const branchLimit = typeof (branch as Record<string, unknown>).petty_cash_limit === 'number' &&
      Number.isFinite((branch as Record<string, unknown>).petty_cash_limit as number)
      ? (branch as Record<string, unknown>).petty_cash_limit as number : 0

    const { data: cats } = await admin.from('claim_categories')
      .select('name,category_group').eq('is_active', true)
    const catGroup = new Map<string, string>()
    for (const c of (cats ?? []) as Array<Record<string, unknown>>) {
      catGroup.set(String(c.name ?? '').trim().toLowerCase(), String(c.category_group ?? 'operation').trim().toLowerCase())
    }

    let inserted = 0
    let duplicates = 0
    const failed: Array<{ index: number; code: string; error: string }> = []
    const pushedByAgent = new Map<string, Array<{ id: string; code: string; amount: number }>>()
    for (let i = 0; i < rows.length; i++) {
      const r = (rows[i] ?? {}) as Record<string, unknown>
      const g = (k: string) => str(r[k]).trim()
      const fail = (error: string) => failed.push({ index: i, code: g('claim_code'), error })
      try {
        const id = g('id'), code = g('claim_code'), sysId = g('requester_system_id')
        const category = g('category')
        const expenseIso = g('requested_at')
        const requested = num(r['requested_amount']), approved = num(r['approved_amount']), settled = num(r['settled_amount'])
        if (!id || !code || !sysId || !category || !expenseIso) { fail('Missing id/code/agent/category/date'); continue }
        if (!Number.isFinite(Date.parse(expenseIso))) { fail(`Bad expense date '${expenseIso}'`); continue }
        if (![requested, approved, settled].every((v) => Number.isFinite(v) && (v as number) >= 0)) {
          fail('Amounts must be numbers >= 0'); continue
        }
        if (requested !== approved) { fail(`Requested (${requested}) must equal approved (${approved})`); continue }
        if ((settled as number) > (approved as number)) { fail(`Settled (${settled}) exceeds approved (${approved})`); continue }
        if (branchLimit > 0 && (requested as number) > branchLimit) { fail(`Requested exceeds branch limit (${branchLimit})`); continue }
        const group = catGroup.get(category.toLowerCase())
        if (!group) { fail(`Unknown/inactive category '${category}'`); continue }
        if (group === 'conveyance' && (!g('from_area') || !g('to_area'))) { fail(`From/To required for ${category}`); continue }
        // Quantities mirror the request form: Pickup carries the pickup
        // count, other conveyance is exactly 1 attempt / 1 success per
        // claim, non-conveyance carries none. Sheet may override via
        // attempted_qty / succeeded_qty / pickup_count.
        const isPickupCat = category.toLowerCase() === 'pickup'
        const attRaw = qint(r['attempted_qty'])
        const sucRaw = qint(r['succeeded_qty'])
        const pickRaw = qint(r['pickup_count'])
        if (attRaw !== undefined && Number.isNaN(attRaw)) { fail('attempted_qty must be a whole number >= 0'); continue }
        if (sucRaw !== undefined && Number.isNaN(sucRaw)) { fail('succeeded_qty must be a whole number >= 0'); continue }
        if (pickRaw !== undefined && Number.isNaN(pickRaw)) { fail('pickup_count must be a whole number >= 0'); continue }
        const isConv = group === 'conveyance'
        const attempted = (attRaw === undefined ? (isConv ? 1 : 0) : attRaw) as number
        const succeeded = (sucRaw === undefined ? (isConv ? attempted : 0) : sucRaw) as number
        if (succeeded > attempted) { fail(`succeeded (${succeeded}) exceeds attempted (${attempted})`); continue }
        const pickupCount = (pickRaw === undefined ? (isPickupCat ? attempted : 0) : pickRaw) as number

        const { data: agent } = await admin.from('users')
          .select('system_id,firebase_id,role,branch_ids').eq('system_id', sysId).maybeSingle()
        const aBranches: string[] = Array.isArray((agent as Record<string, unknown> | null)?.branch_ids)
          ? ((agent as Record<string, unknown>).branch_ids as unknown[]).map((x) => String(x ?? '').trim()) : []
        if (!agent || !aBranches.includes(branchId)) { fail(`Agent ${sysId} not in this branch`); continue }

        const submitKey = g('client_submit_id') || `${batch}-${i}`
        const { data: dupKey } = await admin.from('claims').select('id').eq('client_submit_id', submitKey).maybeSingle()
        if (dupKey) { duplicates++; continue }
        const { data: dupId } = await admin.from('claims').select('id').eq('id', id).maybeSingle()
        if (dupId) { duplicates++; continue }

        const a = agent as Record<string, unknown>
        const { error } = await admin.from('claims').insert({
          id, claim_code: code, branch_id: branchId, requester_system_id: sysId,
          client_submit_id: submitKey,
          category, purpose: g('purpose'), consignment_id: g('consignment_id'),
          store_id: g('store_id'), store_name: g('store_name'), pickup_count: pickupCount,
          vehicle: g('vehicle'), from_area: g('from_area'), to_area: g('to_area'),
          attempted_qty: attempted, succeeded_qty: succeeded, cid_or_merchant: g('cid_or_merchant'),
          requested_amount: requested, approved_amount: approved, settled_amount: settled,
          payment_method: 'Cash', transaction_id: `${batch}-${i}`,
          status: 'settled', attachments: [],
          requester_uid: String(a.firebase_id ?? ''), requester_role: String(a.role ?? ''),
          requested_at: expenseIso, approved_at: expenseIso, settled_at: expenseIso,
          created_at: expenseIso, updated_at: expenseIso,
          verified_by_uid: callerUid, verified_by_system_id: callerSystemId, verified_at: expenseIso, verified_comment: 'Bulk import',
          approved_by_uid: callerUid, approved_by_system_id: callerSystemId, approved_comment: 'Bulk import',
          settle_in_process_by_uid: callerUid, settle_in_process_by_system_id: callerSystemId, settle_in_process_at: expenseIso,
          settled_by_uid: callerUid, settled_by_system_id: callerSystemId,
          rejected_by_uid: null, rejected_by_system_id: null, rejected_at: null, reject_reason: '',
        })
        if (error) { fail(error.message.slice(0, 200)); continue }
        inserted++
        // Per-agent push ledger: the requester (agent) learns their claims
        // settled — the uploader is Accounts, so without this nobody tells them.
        const pushed = pushedByAgent.get(sysId) ?? []
        pushed.push({ id, code, amount: settled as number })
        pushedByAgent.set(sysId, pushed)
      } catch (e) {
        fail(e instanceof Error ? e.message.slice(0, 200) : 'Insert failed')
      }
    }
    // One summary push per agent (never to the uploader themselves, and never
    // failing the batch — sendBulkSettledPush is best-effort).
    for (const [sysId, claims] of pushedByAgent) {
      if (!sysId || sysId === callerSystemId) continue
      await sendBulkSettledPush({ branchId, requesterSystemId: sysId, claims })
    }
    return reply({ ok: true, inserted, duplicates, failed })
  } catch (e) {
    return unhandled(action, identity, e)
  }
})
