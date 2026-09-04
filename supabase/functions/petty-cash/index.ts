// petty-cash — wallet + deposit writes. Actions: petty_cash_deposit_upsert,
// petty_cash_wallet_balance_upsert.
//
// Authoritative writes for Petty Cash deposits + wallet balance, same
// posture as the claims function — the sole persistence layer since the
// Full Petty Cash cutover (previously a best-effort mirror alongside the
// Firebase writes in PettyCashViewModel.kt's depositFund()/settleRequest();
// those Firebase writes are now removed). Columns verified 2026-08-30 against
// a live information_schema.columns dump — see SupabasePettyCashWriter.kt's
// toSupabaseJson() doc comment for the two things that dump caught
// (entered_by_name isn't a real column; id is `uuid`, not text, so the
// Kotlin side converts Firebase's push-id string via
// UUID.nameUUIDFromBytes() before sending it here).

import { admin } from '../_shared/supabase.ts'
import { guardRequest, reply, unhandled } from '../_shared/http.ts'
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

    if (action === 'petty_cash_deposit_upsert') {
      const d = body.deposit
      const str = (v: unknown) => typeof v === 'string' ? v : ''
      const num = (v: unknown) => typeof v === 'number' && Number.isFinite(v) ? v : 0
      const iso = (v: unknown) => typeof v === 'string' && v.trim() ? v : null
      if (!d || !str(d.id).trim() || !str(d.branch_id).trim()) {
        return reply({ error: 'deposit id and branch_id are required' }, 400)
      }
      const { error } = await admin.from('petty_cash_deposits').upsert({
        id: str(d.id), branch_id: str(d.branch_id),
        amount: num(d.amount), source: str(d.source), reference: str(d.reference), remarks: str(d.remarks),
        balance_after: num(d.balance_after),
        entered_by_uid: str(d.entered_by_uid),
        created_at: iso(d.created_at),
      }, { onConflict: 'id' })
      if (error) throw error
      return reply({ ok: true })
    }

    // One row per branch — a plain running-balance snapshot, not an
    // append-only log the way petty_cash_deposits above is. Called
    // independently from both depositFund() (+amount) and settleRequest()
    // (-settledAmount) since either can change the balance without the
    // other creating a deposit row.
    if (action === 'petty_cash_wallet_balance_upsert') {
      const branchId = typeof body.branch_id === 'string' ? body.branch_id.trim() : ''
      const balance = typeof body.balance === 'number' && Number.isFinite(body.balance) ? body.balance : null
      if (!branchId || balance === null) {
        return reply({ error: 'branch_id and a numeric balance are required' }, 400)
      }
      const { error } = await admin.from('petty_cash_wallet_balance').upsert({
        branch_id: branchId, balance, updated_at: new Date().toISOString(),
      }, { onConflict: 'branch_id' })
      if (error) throw error
      return reply({ ok: true })
    }

    return reply({ error: 'Unknown action' }, 400)
  } catch (error) {
    return unhandled(action, identity, error)
  }
})
