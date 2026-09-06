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
import { errLog, guardRequest, reply, unhandled } from '../_shared/http.ts'
import { firebaseIdentity, firebaseProfile } from '../_shared/firebase-auth.ts'

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

Deno.serve(async (request) => {
  const guard = guardRequest(request)
  if (guard) return guard
  let action: string | undefined
  let identity: { uid: string; token: string } | undefined
  try {
    identity = await firebaseIdentity(request)
    const body = await request.json()
    action = body.action

    // ── Branch-scoped money gate (audit #2) ──
    // Deposits and balance writes move real money, so they require the
    // branch's accounts assignment (mirrors PettyCashViewModel.resolveRoles:
    // accountant uid, or everyone with the accountant role) or a global
    // admin/manager. Role comes from the server-side Firebase profile — a
    // caller cannot self-grant it, and the actor is stamped from the session.
    const gateAccounts = async (branchId: string) => {
      const profile = await firebaseProfile(identity!)
      const role = (profile.roleId || '').trim()
      if (role === 'admin' || role === 'manager') return { ok: true as const, profile }
      const { data: branch } = await admin.from('branches')
        .select('accountant_uid,accountant_role').eq('branch_id', branchId).maybeSingle()
      const au = typeof branch?.accountant_uid === 'string' ? branch.accountant_uid.trim() : ''
      const ar = typeof branch?.accountant_role === 'string' ? branch.accountant_role.trim() : ''
      const allowed = (au !== '' && au === identity!.uid) || (ar !== '' && ar === role)
      if (!allowed) {
        errLog(action ?? 'petty_cash', 'forbidden', { branch_id: branchId, role })
        return { ok: false as const, response: reply({ error: 'Only accounts can manage this wallet' }, 403) }
      }
      return { ok: true as const, profile }
    }

    if (action === 'petty_cash_deposit_upsert') {
      const d = body.deposit
      const str = (v: unknown) => typeof v === 'string' ? v : ''
      const num = (v: unknown) => typeof v === 'number' && Number.isFinite(v) ? v : 0
      const iso = (v: unknown) => typeof v === 'string' && v.trim() ? v : null
      if (!d || !str(d.id).trim() || !str(d.branch_id).trim()) {
        return reply({ error: 'deposit id and branch_id are required' }, 400)
      }
      const gate = await gateAccounts(str(d.branch_id).trim())
      if (!gate.ok) return gate.response
      if (num(d.amount) <= 0) {
        return reply({ error: 'Deposit amount must be greater than 0' }, 422)
      }
      // Atomic path (audit #3): the wallet_deposit RPC inserts the deposit row
      // and bumps the balance under lock, idempotent on the deposit id — a
      // retried deposit bumps nothing. The client's balance_after is ignored
      // (never trust a client-computed balance).
      if (UUID_RE.test(str(d.id).trim())) {
        const { data: res, error: rpcError } = await admin.rpc('wallet_deposit', {
          p_id: str(d.id).trim(), p_branch_id: str(d.branch_id).trim(), p_amount: num(d.amount),
          p_source: str(d.source), p_reference: str(d.reference), p_remarks: str(d.remarks),
          p_uid: identity.uid,
        })
        if (rpcError) throw rpcError
        const out = (Array.isArray(res) ? res[0] : res) as { ok?: boolean; duplicate?: boolean; error?: string; new_balance?: number } | null
        if (!out?.ok) return reply({ error: out?.error || 'Deposit failed' }, 422)
        console.info(`wallet_deposit ok: branch=${str(d.branch_id).trim()} amount=${num(d.amount)} duplicate=${!!out?.duplicate}`)
        return reply({ ok: true, duplicate: !!out?.duplicate, new_balance: out?.new_balance })
      }
      // Non-UUID legacy id — gated plain upsert (no balance trust: balance is
      // recomputed server-side from the current row + this amount).
      const { data: wallet } = await admin.from('petty_cash_wallet_balance')
        .select('balance').eq('branch_id', str(d.branch_id).trim()).maybeSingle()
      const newBalance = Number(wallet?.balance ?? 0) + num(d.amount)
      const { error: depError } = await admin.from('petty_cash_deposits').upsert({
        id: str(d.id), branch_id: str(d.branch_id),
        amount: num(d.amount), source: str(d.source), reference: str(d.reference), remarks: str(d.remarks),
        balance_after: newBalance,
        entered_by_uid: identity.uid,
        created_at: iso(d.created_at),
      }, { onConflict: 'id' })
      if (depError) throw depError
      await admin.from('petty_cash_wallet_balance').upsert({
        branch_id: str(d.branch_id).trim(), balance: newBalance, updated_at: new Date().toISOString(),
      }, { onConflict: 'branch_id' })
      return reply({ ok: true, new_balance: newBalance })
    }

    if (action === 'wallet_deposit') {
      // Direct atomic deposit (same RPC as above) for callers holding a UUID.
      const branchId = typeof body.branch_id === 'string' ? body.branch_id.trim() : ''
      const depositId = typeof body.deposit_id === 'string' ? body.deposit_id.trim() : ''
      const amount = typeof body.amount === 'number' && Number.isFinite(body.amount) ? body.amount : 0
      if (!branchId || !depositId || !UUID_RE.test(depositId)) {
        return reply({ error: 'branch_id and a UUID deposit_id are required' }, 400)
      }
      const gate = await gateAccounts(branchId)
      if (!gate.ok) return gate.response
      if (amount <= 0) return reply({ error: 'Deposit amount must be greater than 0' }, 422)
      const str = (v: unknown) => typeof v === 'string' ? v : ''
      const { data: res, error: rpcError } = await admin.rpc('wallet_deposit', {
        p_id: depositId, p_branch_id: branchId, p_amount: amount,
        p_source: str(body.source), p_reference: str(body.reference), p_remarks: str(body.remarks),
        p_uid: identity.uid,
      })
      if (rpcError) throw rpcError
      const out = (Array.isArray(res) ? res[0] : res) as { ok?: boolean; duplicate?: boolean; error?: string; new_balance?: number } | null
      if (!out?.ok) return reply({ error: out?.error || 'Deposit failed' }, 422)
      return reply({ ok: true, duplicate: !!out?.duplicate, new_balance: out?.new_balance })
    }

    // One row per branch — a plain running-balance snapshot, not an
    // append-only log the way petty_cash_deposits above is. No legitimate app
    // flow writes the balance directly anymore (settle goes through the
    // settle_claim RPC via claim_upsert, deposits through wallet_deposit) —
    // so direct writes are admin/manager-only. This closes the arbitrary-
    // balance hole (audit #2): a forged balance can no longer come through here.
    if (action === 'petty_cash_wallet_balance_upsert') {
      const branchId = typeof body.branch_id === 'string' ? body.branch_id.trim() : ''
      const balance = typeof body.balance === 'number' && Number.isFinite(body.balance) ? body.balance : null
      if (!branchId || balance === null) {
        return reply({ error: 'branch_id and a numeric balance are required' }, 400)
      }
      const profile = await firebaseProfile(identity)
      const role = (profile.roleId || '').trim()
      if (role !== 'admin' && role !== 'manager') {
        errLog('petty_cash_wallet_balance_upsert', 'forbidden', { branch_id: branchId, role })
        return reply({ error: 'Direct balance writes are admin-only — use deposit/settle' }, 403)
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
