// claims — Petty Cash claim writes. Action: claim_upsert.
//
// Authoritative write of Petty Cash claims into public.claims — the sole
// persistence layer for claims since the Supabase cutover (previously a
// best-effort mirror alongside the app's Firebase write in
// ClaimsRepository.kt; that Firebase write is now removed). See this
// table's own migration comment
// (202608260001_create_petty_cash_claims_tables.sql): "table structure
// only, ahead of the actual data/write-flow migration off Firebase." This
// action is that write flow, now that the cutover has happened.
// *Name fields (branchName, employeeName, staffByName, ...) are intentionally
// not accepted here — that same migration comment explains those are joins
// against users/branches at read time on the Supabase side, not stored columns.

import { admin } from '../_shared/supabase.ts'
import { errLog, guardRequest, reply, unhandled } from '../_shared/http.ts'
import { firebaseIdentity, firebaseProfile } from '../_shared/firebase-auth.ts'
import { requireUsersRow } from '../_shared/users.ts'
import { sendClaimPush, type ClaimPushEvent } from '../_shared/claim-push.ts'

// Legal status moves. Anything not listed here is rejected server-side — the
// app UI also enforces this, but UI checks alone never stopped a modified APK
// from skipping pending straight to settled (audit finding #1).
const LEGAL_TRANSITIONS: Record<string, string[]> = {
  pending: ['verified', 'rejected', 'cancelled'],
  verified: ['approved', 'rejected', 'cancelled'],
  approved: ['settle_in_process', 'verified', 'cancelled'],
  settle_in_process: ['settled', 'approved', 'cancelled'],
  rejected: ['pending', 'cancelled'],
  settled: [],
  cancelled: [],
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

    if (action === 'claim_upsert') {
      const c = body.claim
      const str = (v: unknown) => typeof v === 'string' ? v : ''
      // First non-blank wins: new claim-column keys, then legacy pre-rename
      // keys, so old APK builds keep writing without data loss.
      const pick = (...vs: unknown[]) => {
        for (const v of vs) { const s = str(v).trim(); if (s) return s }
        return ''
      }
      const pickNum = (...vs: unknown[]) => {
        for (const v of vs) { if (typeof v === 'number' && Number.isFinite(v)) return v }
        return 0
      }
      const pickIso = (...vs: unknown[]) => {
        for (const v of vs) { if (typeof v === 'string' && v.trim()) return v }
        return null
      }
      // Actor system_id columns are FKs → users(system_id). A blank actor
      // (e.g. staff on a brand-new pending claim) must go as NULL — ''
      // matches no users row and every insert fails with 23503.
      const fk = (v: unknown) => { const s = str(v).trim(); return s ? s : null }
      const num = (v: unknown) => typeof v === 'number' && Number.isFinite(v) ? v : 0
      const iso = (v: unknown) => typeof v === 'string' && v.trim() ? v : null
      if (!c || !str(c.id).trim() || !str(c.branch_id).trim() || !str(c.requester_system_id).trim()) {
        errLog('claim_upsert', 'missing_required_fields', { id: c?.id, branch_id: c?.branch_id, has_system_id: !!c?.requester_system_id })
        return reply({ error: 'claim id, branch_id and requester_system_id are required' }, 400)
      }
      const normStatus = (s: string) => s === 'ready_to_settle' ? 'settle_in_process' : s

      // ── Caller + branch context (audit #1: never trust client identity) ──
      // Role comes from the server-side Firebase profile read (same pattern as
      // directory's branch_upsert gate) — a caller cannot self-grant it.
      // Claim-stage rights are per-branch assignments on the branches row
      // (mirrors PettyCashViewModel.resolveRoles app-side).
      const profile = await firebaseProfile(identity)
      const callerUid = identity.uid
      const callerSystemId = (profile.systemId || '').trim()
      const callerRole = (profile.roleId || '').trim()
      if (!callerSystemId) {
        errLog('claim_upsert', 'no_system_id', { claim_id: c.id })
        return reply({ error: 'Signed-in user has no system_id' }, 403)
      }
      // users rows are admin-onboarded only (employee edit) — claim writes
      // NEVER create one. Actor/requester FKs require the row; fail fast with
      // a contact-admin message instead of a cryptic FK error.
      if (!await requireUsersRow(callerSystemId)) {
        errLog('claim_upsert', 'caller_users_row_missing', { system_id: callerSystemId })
        return reply({ error: 'Your employee profile is missing — ask admin to add you in employee edit' }, 403)
      }
      const isAdmin = callerRole === 'admin' || callerRole === 'manager'
      const { data: branch } = await admin.from('branches')
        .select('branch_id,staff_uid,staff_uids,staff_role,staff_roles,petty_cash_poc_uid,petty_cash_poc_uids,petty_cash_poc_roles,accountant_uid,accountant_uids,accountant_role,accountant_roles,petty_cash_limit')
        .eq('branch_id', str(c.branch_id).trim()).maybeSingle()
      // Mirror PettyCashViewModel.resolveRoles app-side: a uid match on ANY
      // uid slot (legacy singular + plural) OR a role match on ANY role slot.
      // The old singular-only gate rejected legit staff — e.g. another
      // incharge when staff_uid names one person — with "Only branch staff…"
      // even though the app showed them the button.
      const asList = (v: unknown): string[] => {
        if (Array.isArray(v)) return v.map(x => String(x ?? '').trim()).filter(s => s !== '')
        return typeof v === 'string' && v.trim() !== '' ? [v.trim()] : []
      }
      const matchAny = (uids: string[], roles: string[]) => {
        if (uids.includes(callerUid)) return true
        if (callerRole !== '' && roles.includes(callerRole)) return true
        return false
      }
      const canStaff = isAdmin || matchAny(
        [...asList(branch?.staff_uids), ...asList(branch?.staff_uid)],
        [...asList(branch?.staff_roles), ...asList(branch?.staff_role)])
      const canPoc = isAdmin || matchAny(
        [...asList(branch?.petty_cash_poc_uids), ...asList(branch?.petty_cash_poc_uid)],
        asList(branch?.petty_cash_poc_roles))
      const canAccounts = isAdmin || matchAny(
        [...asList(branch?.accountant_uids), ...asList(branch?.accountant_uid)],
        [...asList(branch?.accountant_roles), ...asList(branch?.accountant_role)])
      const branchLimit = typeof branch?.petty_cash_limit === 'number' && Number.isFinite(branch.petty_cash_limit)
        ? branch.petty_cash_limit : 0

      const { data: existing } = await admin.from('claims').select('*').eq('id', str(c.id).trim()).maybeSingle()

      // ── Shared money guards (audit #4: negative/unbounded) ──
      const requested = num(c.requested_amount), approved = num(c.approved_amount), settled = num(c.settled_amount)
      if (requested < 0 || approved < 0 || settled < 0) {
        errLog('claim_upsert', 'negative_amount', { claim_id: c.id })
        return reply({ error: 'Amounts cannot be negative' }, 422)
      }
      // Expense-date compare by millis, not string: the app may send
      // "+00:00" where Postgres returns "Z" for the same instant — a string
      // compare would 409 every legitimate approval.
      const tsMillis = (v: unknown) => {
        if (typeof v !== 'string' || !v.trim()) return null
        const t = Date.parse(v)
        return Number.isFinite(t) ? t : null
      }
      const checkLimit = (amount: number, what: string) => {
        if (branchLimit > 0 && amount > branchLimit) {
          errLog('claim_upsert', 'over_branch_limit', { claim_id: c.id, what, amount, limit: branchLimit })
          return reply({ error: `${what} exceeds this branch's petty cash limit (${branchLimit})` }, 422)
        }
        return null
      }

      // ── Fixed-conveyance override (audit #6) ──
      // Pickup with a store-configured fixed payout: the stores row is
      // authoritative, not the client-sent amount. Overridden (not rejected)
      // so old builds keep working while forgery stops working.
      let requestedFinal = requested
      if (str(c.category).trim() === 'Pickup' && str(c.store_id).trim()) {
        const { data: store } = await admin.from('stores')
          .select('conveyance_amount').eq('id', str(c.store_id).trim()).maybeSingle()
        const fixed = store && typeof store.conveyance_amount === 'number' && Number.isFinite(store.conveyance_amount)
          ? store.conveyance_amount : null
        if (fixed !== null && fixed >= 0 && fixed !== requested) {
          console.info(`claim_upsert conveyance_override: claim=${c.id} client=${requested} fixed=${fixed}`)
          requestedFinal = fixed
        }
      }

      const attachmentsVal = (() => {
        const raw = Array.isArray(c.attachments) ? c.attachments.slice(0, 2) : []
        return raw.map((a: unknown) => {
          const o = (a ?? {}) as Record<string, unknown>
          const key = str(o.key)
          if (!key) throw new Error('attachment key is required')
          return { key, name: str(o.name), size: num(o.size) }
        })
      })()

      if (!existing) {
        // ── CREATE ──
        const newStatus = normStatus(str(c.status).trim()) || 'pending'
        if (newStatus !== 'pending') {
          errLog('claim_upsert', 'create_non_pending', { claim_id: c.id, status: newStatus })
          return reply({ error: 'New claims must start as pending' }, 403)
        }
        if (str(c.requester_system_id).trim() !== callerSystemId) {
          errLog('claim_upsert', 'create_for_other', { claim_id: c.id })
          return reply({ error: 'You can only place requests for yourself' }, 403)
        }
        if (approved !== 0 || settled !== 0) {
          return reply({ error: 'A new request cannot carry approved/settled amounts' }, 422)
        }
        const stageActors = [c.verified_by_uid, c.verified_by_system_id, c.approved_by_uid, c.approved_by_system_id,
          c.settle_in_process_by_uid, c.settle_in_process_by_system_id, c.settled_by_uid, c.settled_by_system_id,
          c.rejected_by_uid, c.rejected_by_system_id, c.reject_reason,
          c.staff_by_uid, c.staff_by_system_id, c.poc_approved_by_uid, c.poc_approved_by_system_id, c.poc_comment,
          c.ready_to_settle_by_uid, c.ready_to_settle_by_system_id, c.worker_uid]
        if (stageActors.some((v) => str(v as unknown).trim() !== '')) {
          errLog('claim_upsert', 'create_with_actors', { claim_id: c.id })
          return reply({ error: 'A new request cannot carry approval-stage data' }, 403)
        }
        const over = checkLimit(requestedFinal, 'Requested amount')
        if (over) return over
        // Idempotency key (audit #5): retried submit with the same key returns
        // the existing row instead of a duplicate.
        const submitKey = str(c.client_submit_id).trim()
        if (submitKey) {
          const { data: dup } = await admin.from('claims').select('id').eq('client_submit_id', submitKey).maybeSingle()
          if (dup) {
            console.info(`claim_upsert idempotent_hit: key=${submitKey} claim=${dup.id}`)
            return reply({ ok: true, duplicate: true, id: dup.id })
          }
        } else {
          // Old builds send no key — time-window fallback: same requester +
          // branch + amount + purpose + category, still pending, < 120s old.
          // (The 8-identical-pendings case from the audit: seconds apart.)
          const since = new Date(Date.now() - 120_000).toISOString()
          const { data: recent } = await admin.from('claims').select('id')
            .eq('requester_system_id', callerSystemId).eq('branch_id', str(c.branch_id).trim())
            .eq('status', 'pending').eq('requested_amount', requestedFinal)
            .eq('purpose', str(c.purpose)).eq('category', str(c.category))
            .gte('created_at', since).limit(1)
          if (recent && recent.length > 0) {
            errLog('claim_upsert', 'duplicate_window_hit', { claim_id: c.id, existing: recent[0].id })
            return reply({ error: 'Possible duplicate — an identical request was just placed' }, 409)
          }
        }
        const { error } = await admin.from('claims').upsert({
          id: str(c.id), claim_code: str(c.claim_code),
          branch_id: str(c.branch_id), requester_system_id: str(c.requester_system_id),
          client_submit_id: submitKey || null,
          category: str(c.category), purpose: pick(c.purpose, c.remarks),
          consignment_id: str(c.consignment_id), store_id: str(c.store_id), store_name: str(c.store_name),
          pickup_count: num(c.pickup_count),
          vehicle: str(c.vehicle), from_area: str(c.from_area), to_area: str(c.to_area),
          attempted_qty: num(c.attempted_qty), succeeded_qty: pickNum(c.succeeded_qty, c.successed_qty),
          cid_or_merchant: str(c.cid_or_merchant),
          requested_amount: requestedFinal, approved_amount: 0, settled_amount: 0,
          payment_method: str(c.payment_method), transaction_id: str(c.transaction_id),
          status: 'pending',
          attachments: attachmentsVal,
          requester_uid: pick(c.requester_uid, c.worker_uid) || callerUid,
          requester_role: pick(c.requester_role, c.worker_role),
          requested_at: iso(c.requested_at), approved_at: null, settled_at: null,
          created_at: iso(c.created_at), updated_at: iso(c.updated_at),
          verified_by_uid: '', verified_by_system_id: null, verified_at: null, verified_comment: '',
          approved_by_uid: '', approved_by_system_id: null, approved_comment: '',
          settle_in_process_by_uid: '', settle_in_process_by_system_id: null, settle_in_process_at: null,
          settled_by_uid: '', settled_by_system_id: null,
          rejected_by_uid: '', rejected_by_system_id: null, rejected_at: null, reject_reason: '',
        }, { onConflict: 'id' })
        if (error) {
          errLog('claim_upsert', 'db_upsert_failed', { claim_id: c?.id, pg_code: error.code, pg_message: error.message })
          throw error
        }
        return reply({ ok: true, id: str(c.id) })
      }

      // ── UPDATE on existing row: authorize the DELTA, not just the row ──
      const oldStatus: string = typeof existing.status === 'string' ? existing.status : 'pending'
      const newStatus = normStatus(str(c.status).trim()) || oldStatus
      // Terminal rows are frozen (audit #9: settled reports must not shift).
      if (oldStatus === 'settled' || oldStatus === 'cancelled') {
        errLog('claim_upsert', 'terminal_frozen', { claim_id: c.id, status: oldStatus })
        return reply({ error: `This request is ${oldStatus} and can no longer be changed` }, 409)
      }
      if (str(c.requester_system_id).trim() !== String(existing.requester_system_id || '').trim()) {
        return reply({ error: 'Requester cannot be changed' }, 403)
      }
      const isOwner = String(existing.requester_uid || '') === callerUid ||
        String(existing.requester_system_id || '').trim() === callerSystemId

      // Claim-status push (audit #7): tell the requester when someone ELSE
      // moves their request. Best-effort — sendClaimPush never throws, and a
      // push failure must never fail the claim write.
      const fireClaimPush = (event: ClaimPushEvent) => {
        const target = String(existing.requester_system_id || '').trim()
        if (!target || target === callerSystemId) return Promise.resolve(null)
        const amount = Number(existing.approved_amount ?? existing.requested_amount ?? 0) || 0
        return sendClaimPush({
          claimId: str(c.id).trim(), claimCode: String(existing.claim_code ?? ''),
          branchId: String(existing.branch_id ?? ''), requesterSystemId: target,
          event, amount,
        })
      }

      if (newStatus === oldStatus) {
        // Same-status edit.
        if (oldStatus === 'pending') {
          if (!isOwner && !canStaff) {
            errLog('claim_upsert', 'pending_edit_forbidden', { claim_id: c.id })
            return reply({ error: 'Only the requester or branch staff can edit a pending request' }, 403)
          }
          const over = checkLimit(requestedFinal, 'Requested amount')
          if (over) return over
        } else {
          // Past pending, core money/date/identity fields are locked (audit
          // #9) — only stage comments may change, by their stage actors.
          const locked: Array<[string, unknown, unknown]> = [
            ['requested_amount', requestedFinal, Number(existing.requested_amount ?? 0)],
            ['approved_amount', approved, Number(existing.approved_amount ?? 0)],
            ['settled_amount', settled, Number(existing.settled_amount ?? 0)],
            ['category', str(c.category), String(existing.category ?? '')],
            ['purpose', pick(c.purpose, c.remarks), String(existing.purpose ?? '')],
            ['requested_at', tsMillis(iso(c.requested_at)) ?? tsMillis(existing.requested_at), tsMillis(existing.requested_at)],
            ['consignment_id', str(c.consignment_id), String(existing.consignment_id ?? '')],
            ['store_id', str(c.store_id), String(existing.store_id ?? '')],
          ]
          for (const [name, want, have] of locked) {
            if (JSON.stringify(want) !== JSON.stringify(have)) {
              errLog('claim_upsert', 'locked_field_edit', { claim_id: c.id, field: name })
              return reply({ error: `This request is ${oldStatus} — ${name} can no longer be changed` }, 409)
            }
          }
          const commentOk =
            (str(c.verified_comment) === String(existing.verified_comment ?? '') || canStaff) &&
            (str(c.approved_comment) === String(existing.approved_comment ?? '') || canPoc) &&
            (str(c.reject_reason) === String(existing.reject_reason ?? '') || canStaff || canPoc)
          if (!commentOk) {
            return reply({ error: 'Only the responsible approver can edit stage comments' }, 403)
          }
        }
      } else {
        // Status transition — must be legal AND stage-authorized. Actor
        // identity fields are server-stamped to the caller (audit #1: no
        // acting under someone else's name).
        const allowed = LEGAL_TRANSITIONS[oldStatus] ?? []
        if (!allowed.includes(newStatus)) {
          errLog('claim_upsert', 'illegal_transition', { claim_id: c.id, from: oldStatus, to: newStatus })
          return reply({ error: `Cannot move a request from ${oldStatus} to ${newStatus}` }, 403)
        }
        // Money/date core is frozen on every transition (set at pending time;
        // each stage writes only its OWN amount below).
        if (requestedFinal !== Number(existing.requested_amount ?? 0)) {
          return reply({ error: 'Requested amount cannot be changed after submission' }, 409)
        }
        // Only enforced when the client actually sent a date (old builds
        // always do — full-row upsert); a missing value means "no change".
        const wantReqAt = tsMillis(iso(c.requested_at))
        if (wantReqAt !== null && wantReqAt !== tsMillis(existing.requested_at)) {
          return reply({ error: 'Expense date cannot be changed after submission' }, 409)
        }
        const now = new Date().toISOString()
        // Per-transition stage auth + actor stamping. Extra context object
        // carries the actor values the final upsert must use.
        let actor: Record<string, unknown> = {}
        if (oldStatus === 'pending' && newStatus === 'verified') {
          if (!canStaff) return reply({ error: 'Only branch staff can verify requests' }, 403)
          actor = { verified_by_uid: callerUid, verified_by_system_id: callerSystemId, verified_at: now }
        } else if (oldStatus === 'verified' && newStatus === 'approved') {
          if (!canPoc) return reply({ error: 'Only the cash POC can approve requests' }, 403)
          if (requested > 0 && approved > requested) {
            return reply({ error: 'Approved amount cannot exceed the requested amount' }, 422)
          }
          const over = checkLimit(approved, 'Approved amount')
          if (over) return over
          actor = { approved_by_uid: callerUid, approved_by_system_id: callerSystemId, approved_at: now }
        } else if (oldStatus === 'approved' && newStatus === 'settle_in_process') {
          if (!canAccounts) return reply({ error: 'Only accounts can queue requests for settlement' }, 403)
          actor = { settle_in_process_by_uid: callerUid, settle_in_process_by_system_id: callerSystemId, settle_in_process_at: now }
        } else if (oldStatus === 'settle_in_process' && newStatus === 'settled') {
          // Atomic path (audit #3): EVERY settle — any client version — goes
          // through the settle_claim RPC (row locks, balance + idempotency),
          // never a plain upsert.
          if (!canAccounts) return reply({ error: 'Only accounts can settle requests' }, 403)
          const finalAmount = settled > 0 ? settled
            : approved > 0 ? approved
            : Number(existing.approved_amount ?? 0) > 0 ? Number(existing.approved_amount)
            : requestedFinal
          const { data: res, error: rpcError } = await admin.rpc('settle_claim', {
            p_claim_id: str(c.id).trim(), p_amount: finalAmount,
            p_payment_method: str(c.payment_method), p_transaction_id: str(c.transaction_id),
            p_actor_uid: callerUid, p_actor_system_id: callerSystemId,
          })
          if (rpcError) throw rpcError
          const out = (Array.isArray(res) ? res[0] : res) as { ok?: boolean; duplicate?: boolean; error?: string; new_balance?: number; warning?: string } | null
          if (!out?.ok) {
            errLog('claim_settle', 'rpc_rejected', { claim_id: c.id, error: out?.error })
            return reply({ error: out?.error || 'Settle failed' }, 409)
          }
          console.info(`claim_settle ok: claim=${c.id} amount=${finalAmount} duplicate=${!!out?.duplicate} warning=${out?.warning ?? 'none'}`)
          await fireClaimPush('settled')
          return reply({ ok: true, id: str(c.id), duplicate: !!out?.duplicate, new_balance: out?.new_balance, warning: out?.warning ?? null })
        } else if (newStatus === 'rejected' && (oldStatus === 'pending' || oldStatus === 'verified')) {
          if (oldStatus === 'pending' && !canStaff) return reply({ error: 'Only branch staff can reject at this stage' }, 403)
          if (oldStatus === 'verified' && !canPoc) return reply({ error: 'Only the cash POC can reject at this stage' }, 403)
          if (!str(c.reject_reason).trim()) return reply({ error: 'A reject reason is required' }, 422)
          actor = { rejected_by_uid: callerUid, rejected_by_system_id: callerSystemId, rejected_at: now }
        } else if (oldStatus === 'rejected' && newStatus === 'pending') {
          // Resubmit by the owner (audit #8) — back to the queue, reject slate wiped.
          if (!isOwner && !isAdmin) return reply({ error: 'Only the requester can resubmit a rejected request' }, 403)
          actor = { rejected_by_uid: '', rejected_by_system_id: null, rejected_at: null, reject_reason: '' }
        } else if (oldStatus === 'approved' && newStatus === 'verified') {
          // Send-back by POC (audit #8) — approval slate wiped for a redo.
          if (!canPoc) return reply({ error: 'Only the cash POC can send a request back' }, 403)
          actor = { approved_by_uid: '', approved_by_system_id: null, approved_at: null, approved_comment: '' }
        } else if (oldStatus === 'settle_in_process' && newStatus === 'approved') {
          // Send-back by accounts (audit #8).
          if (!canAccounts) return reply({ error: 'Only accounts can send a request back' }, 403)
          actor = { settle_in_process_by_uid: '', settle_in_process_by_system_id: null, settle_in_process_at: null }
        } else if (newStatus === 'cancelled') {
          if (!isOwner && !canStaff && !canPoc && !canAccounts) {
            return reply({ error: 'You cannot cancel this request' }, 403)
          }
        } else {
          return reply({ error: `Cannot move a request from ${oldStatus} to ${newStatus}` }, 403)
        }
        const { error } = await admin.from('claims').upsert({
          id: str(c.id), claim_code: str(c.claim_code) || String(existing.claim_code ?? ''),
          branch_id: String(existing.branch_id ?? str(c.branch_id)),
          requester_system_id: String(existing.requester_system_id ?? ''),
          client_submit_id: (existing as Record<string, unknown>).client_submit_id ?? null,
          category: String(existing.category ?? str(c.category)),
          purpose: String(existing.purpose ?? pick(c.purpose, c.remarks)),
          consignment_id: String(existing.consignment_id ?? ''), store_id: String(existing.store_id ?? ''),
          store_name: String(existing.store_name ?? str(c.store_name)),
          pickup_count: Number(existing.pickup_count ?? 0),
          vehicle: String(existing.vehicle ?? ''), from_area: String(existing.from_area ?? ''),
          to_area: String(existing.to_area ?? ''),
          attempted_qty: Number(existing.attempted_qty ?? 0),
          succeeded_qty: Number(existing.succeeded_qty ?? 0),
          cid_or_merchant: String(existing.cid_or_merchant ?? ''),
          requested_amount: Number(existing.requested_amount ?? 0),
          approved_amount: newStatus === 'approved' ? approved : Number(existing.approved_amount ?? 0),
          settled_amount: Number(existing.settled_amount ?? 0),
          payment_method: str(c.payment_method) || String(existing.payment_method ?? ''),
          transaction_id: str(c.transaction_id) || String(existing.transaction_id ?? ''),
          status: newStatus,
          attachments: (existing as Record<string, unknown>).attachments ?? [],
          requester_uid: String(existing.requester_uid ?? ''),
          requester_role: String(existing.requester_role ?? ''),
          requested_at: existing.requested_at, approved_at: existing.approved_at, settled_at: existing.settled_at,
          created_at: existing.created_at, updated_at: now,
          verified_by_uid: String(existing.verified_by_uid ?? ''),
          verified_by_system_id: existing.verified_by_system_id ?? null,
          verified_at: existing.verified_at, verified_comment: pick(c.verified_comment, c.staff_comment) || String(existing.verified_comment ?? ''),
          approved_by_uid: String(existing.approved_by_uid ?? ''),
          approved_by_system_id: existing.approved_by_system_id ?? null,
          approved_comment: pick(c.approved_comment, c.poc_comment) || String(existing.approved_comment ?? ''),
          settle_in_process_by_uid: String(existing.settle_in_process_by_uid ?? ''),
          settle_in_process_by_system_id: existing.settle_in_process_by_system_id ?? null,
          settle_in_process_at: existing.settle_in_process_at,
          settled_by_uid: String(existing.settled_by_uid ?? ''),
          settled_by_system_id: existing.settled_by_system_id ?? null,
          rejected_by_uid: String(existing.rejected_by_uid ?? ''),
          rejected_by_system_id: existing.rejected_by_system_id ?? null,
          rejected_at: existing.rejected_at, reject_reason: str(c.reject_reason) || String(existing.reject_reason ?? ''),
          ...actor,
        }, { onConflict: 'id' })
        if (error) {
          errLog('claim_upsert', 'db_upsert_failed', { claim_id: c?.id, pg_code: error.code, pg_message: error.message })
          throw error
        }
        return reply({ ok: true, id: str(c.id) })
      }

      // ── Same-status edit write-back ──
      // Pending: owner/staff edits (validated above). Past pending: only stage
      // comments may have changed (validated above) — everything else is
      // written back from the EXISTING row, so a forged payload can't smuggle
      // actor identities, amounts or dates through an innocent-looking edit.
      const now2 = new Date().toISOString()
      const { error } = await admin.from('claims').upsert({
        id: str(c.id), claim_code: str(c.claim_code) || String(existing.claim_code ?? ''),
        branch_id: String(existing.branch_id ?? str(c.branch_id)),
        requester_system_id: String(existing.requester_system_id ?? ''),
        client_submit_id: (existing as Record<string, unknown>).client_submit_id ?? null,
        // branch_name and employee_name are not stored — joined at read time via FKs.
        // type was dropped (202609040004) — it always mirrored category.
        category: oldStatus === 'pending' ? str(c.category) : String(existing.category ?? ''),
        purpose: oldStatus === 'pending' ? pick(c.purpose, c.remarks) : String(existing.purpose ?? ''),
        consignment_id: oldStatus === 'pending' ? str(c.consignment_id) : String(existing.consignment_id ?? ''),
        store_id: oldStatus === 'pending' ? str(c.store_id) : String(existing.store_id ?? ''),
        store_name: str(c.store_name) || String(existing.store_name ?? ''),
        pickup_count: oldStatus === 'pending' ? num(c.pickup_count) : Number(existing.pickup_count ?? 0),
        // Conveyance fields (Pickup / Bulk Delivery) — real columns on
        // public.claims (all NOT NULL with ''/0 defaults). Editable only while
        // pending; frozen afterwards like the other core fields.
        vehicle: oldStatus === 'pending' ? str(c.vehicle) : String(existing.vehicle ?? ''),
        from_area: oldStatus === 'pending' ? str(c.from_area) : String(existing.from_area ?? ''),
        to_area: oldStatus === 'pending' ? str(c.to_area) : String(existing.to_area ?? ''),
        attempted_qty: oldStatus === 'pending' ? num(c.attempted_qty) : Number(existing.attempted_qty ?? 0),
        succeeded_qty: oldStatus === 'pending'
          ? pickNum(c.succeeded_qty, c.successed_qty) : Number(existing.succeeded_qty ?? 0),
        cid_or_merchant: oldStatus === 'pending' ? str(c.cid_or_merchant) : String(existing.cid_or_merchant ?? ''),
        // NOTE: there is no placed_date column on public.claims (the expense
        // date lives in requested_at; ClaimRow derives yyyy-MM-dd from it).
        // A previous version of this upsert sent placed_date and every call
        // failed with "Could not find the 'placed_date' column" — do not
        // re-add it without also adding the column.
        requested_amount: oldStatus === 'pending' ? requestedFinal : Number(existing.requested_amount ?? 0),
        approved_amount: Number(existing.approved_amount ?? 0),
        settled_amount: Number(existing.settled_amount ?? 0),
        payment_method: String(existing.payment_method ?? ''),
        transaction_id: String(existing.transaction_id ?? ''),
        status: oldStatus,
        // attachments jsonb — editable only while pending (validated above).
        attachments: oldStatus === 'pending' ? attachmentsVal : ((existing as Record<string, unknown>).attachments ?? []),
        requester_uid: String(existing.requester_uid ?? ''),
        requester_role: String(existing.requester_role ?? ''),
        requested_at: oldStatus === 'pending' ? iso(c.requested_at) : existing.requested_at,
        approved_at: existing.approved_at, settled_at: existing.settled_at,
        created_at: existing.created_at, updated_at: now2,
        verified_by_uid: String(existing.verified_by_uid ?? ''),
        verified_by_system_id: existing.verified_by_system_id ?? null,
        verified_at: existing.verified_at, verified_comment: pick(c.verified_comment, c.staff_comment) || String(existing.verified_comment ?? ''),
        approved_by_uid: String(existing.approved_by_uid ?? ''),
        approved_by_system_id: existing.approved_by_system_id ?? null,
        approved_comment: pick(c.approved_comment, c.poc_comment) || String(existing.approved_comment ?? ''),
        settle_in_process_by_uid: String(existing.settle_in_process_by_uid ?? ''),
        settle_in_process_by_system_id: existing.settle_in_process_by_system_id ?? null,
        settle_in_process_at: existing.settle_in_process_at,
        settled_by_uid: String(existing.settled_by_uid ?? ''),
        settled_by_system_id: existing.settled_by_system_id ?? null,
        rejected_by_uid: String(existing.rejected_by_uid ?? ''),
        rejected_by_system_id: existing.rejected_by_system_id ?? null,
        rejected_at: existing.rejected_at, reject_reason: str(c.reject_reason) || String(existing.reject_reason ?? ''),
      }, { onConflict: 'id' })
        if (error) {
          errLog('claim_upsert', 'db_upsert_failed', { claim_id: c?.id, pg_code: error.code, pg_message: error.message })
          throw error
        }
        const pushEvent: ClaimPushEvent | null =
          newStatus === 'verified' ? (oldStatus === 'approved' ? 'sent_back' : 'verified')
          : newStatus === 'approved' ? (oldStatus === 'settle_in_process' ? 'sent_back' : 'approved')
          : newStatus === 'settle_in_process' ? 'settle_in_process'
          : newStatus === 'rejected' ? 'rejected'
          : newStatus === 'cancelled' ? 'cancelled'
          : null
        if (pushEvent) await fireClaimPush(pushEvent)
        return reply({ ok: true, id: str(c.id) })
      }

    if (action === 'claim_delete') {
      // Hard delete of a PENDING request by its own requester: the row goes
      // away entirely (no cancelled tombstone). Gated on worker_uid (the
      // Firebase uid stored at submit) + pending status — anything else is
      // someone else's or already in the approval chain. R2 objects are purged
      // app-side (AttachmentUploader.deleteObject per key); the row delete is
      // the atomic point of no return.
      const claimId = typeof body.claim_id === 'string' ? body.claim_id.trim() : ''
      if (!claimId) return reply({ error: 'claim_id is required' }, 400)
      const { data: row, error: readError } = await admin.from('claims')
        .select('id,requester_uid,status').eq('id', claimId).maybeSingle()
      if (readError) throw readError
      if (!row) return reply({ error: 'Claim not found' }, 404)
      if (row.requester_uid !== identity.uid) {
        errLog('claim_delete', 'forbidden', { claim_id: claimId })
        return reply({ error: 'You can only delete your own requests' }, 403)
      }
      if (row.status !== 'pending') {
        return reply({ error: 'Only pending requests can be deleted' }, 409)
      }
      const { error } = await admin.from('claims').delete().eq('id', claimId)
      if (error) {
        errLog('claim_delete', 'db_delete_failed', { claim_id: claimId, pg_code: error.code, pg_message: error.message })
        throw error
      }
      console.info(`claim_delete ok: claim=${claimId}`)
      return reply({ ok: true })
    }

    return reply({ error: 'Unknown action' }, 400)
  } catch (error) {
    return unhandled(action, identity, error)
  }
})
