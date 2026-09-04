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
import { firebaseIdentity } from '../_shared/firebase-auth.ts'

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
      const { error } = await admin.from('claims').upsert({
        id: str(c.id), claim_code: str(c.claim_code),
        branch_id: str(c.branch_id), requester_system_id: str(c.requester_system_id),
        // branch_name and employee_name are not stored — joined at read time via FKs.
        // type was dropped (202609040004) — it always mirrored category.
        category: str(c.category), remarks: str(c.remarks),
        consignment_id: str(c.consignment_id), store_id: str(c.store_id), store_name: str(c.store_name),
        pickup_count: num(c.pickup_count),
        // Conveyance fields (Pickup / Bulk Delivery) — real columns on
        // public.claims (all NOT NULL with ''/0 defaults). The app's writer
        // always sends them; omitting them here used to silently drop the
        // submitted vehicle/areas/quantities on every conveyance claim.
        vehicle: str(c.vehicle), from_area: str(c.from_area), to_area: str(c.to_area),
        attempted_qty: num(c.attempted_qty), successed_qty: num(c.successed_qty),
        cid_or_merchant: str(c.cid_or_merchant),
        // NOTE: there is no placed_date column on public.claims (the expense
        // date lives in requested_at; ClaimRow derives yyyy-MM-dd from it).
        // A previous version of this upsert sent placed_date and every call
        // failed with "Could not find the 'placed_date' column" — do not
        // re-add it without also adding the column.
        requested_amount: num(c.requested_amount), approved_amount: num(c.approved_amount), settled_amount: num(c.settled_amount),
        payment_method: str(c.payment_method), transaction_id: str(c.transaction_id),
        status: str(c.status), priority: str(c.priority),
        attachment_url: str(c.attachment_url), attachment_name: str(c.attachment_name),
        worker_uid: str(c.worker_uid), worker_role: str(c.worker_role),
        requested_at: iso(c.requested_at), approved_at: iso(c.approved_at), settled_at: iso(c.settled_at),
        created_at: iso(c.created_at), updated_at: iso(c.updated_at),
        staff_by_uid: str(c.staff_by_uid), staff_by_system_id: fk(c.staff_by_system_id), staff_at: iso(c.staff_at), staff_comment: str(c.staff_comment),
        poc_approved_by_uid: str(c.poc_approved_by_uid), poc_approved_by_system_id: fk(c.poc_approved_by_system_id), poc_comment: str(c.poc_comment),
        settle_in_process_by_uid: str(c.settle_in_process_by_uid), settle_in_process_by_system_id: fk(c.settle_in_process_by_system_id), settle_in_process_at: iso(c.settle_in_process_at),
        settled_by_uid: str(c.settled_by_uid), settled_by_system_id: fk(c.settled_by_system_id),
        rejected_by_uid: str(c.rejected_by_uid), rejected_by_system_id: fk(c.rejected_by_system_id), rejected_at: iso(c.rejected_at), reject_reason: str(c.reject_reason),
      }, { onConflict: 'id' })
      if (error) {
        errLog('claim_upsert', 'db_upsert_failed', { claim_id: c?.id, pg_code: error.code, pg_message: error.message })
        throw error
      }
      return reply({ ok: true })
    }

    return reply({ error: 'Unknown action' }, 400)
  } catch (error) {
    return unhandled(action, identity, error)
  }
})
