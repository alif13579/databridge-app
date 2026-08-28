import { createClient } from 'npm:@supabase/supabase-js@2'
import { createRemoteJWKSet, jwtVerify } from 'npm:jose@5'

// Mirrors Firebase's claims/{claimId}/info into public.claims as an alternative
// store — Firebase stays the single source of truth (ClaimsRepository.create()/
// update() write there first and only call this after that succeeds); this
// function's job is just to keep the Supabase copy field-for-field in sync.
// See supabase/migrations/202608260001_create_petty_cash_claims_tables.sql for
// the table shape and why several Firebase fields (the *_name companions of a
// *_uid/*_id column) are deliberately not columns here.
//
// Same JWT verification approach as remark-validations/index.ts — this only
// confirms the caller is a genuine, currently signed-in Firebase user. Firebase's
// own rules already authorized the write that produced this ClaimInfo (see
// 5f12777/873204d) before ClaimsRepository ever calls this function, so that
// authorization is not repeated here.

const corsHeaders = {
  'Access-Control-Allow-Headers': 'authorization, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Content-Type': 'application/json',
}

const firebaseProjectId = Deno.env.get('FIREBASE_PROJECT_ID')
if (!firebaseProjectId) throw new Error('FIREBASE_PROJECT_ID is required')

const firebaseJwks = createRemoteJWKSet(
  new URL('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com'),
)
const secretKeys = JSON.parse(Deno.env.get('SUPABASE_SECRET_KEYS') ?? '{}')
const serviceRoleKey = secretKeys.default ?? Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')
if (!serviceRoleKey) throw new Error('A Supabase server key is required')

const admin = createClient(Deno.env.get('SUPABASE_URL')!, serviceRoleKey, {
  auth: { persistSession: false, autoRefreshToken: false },
})

function reply(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: corsHeaders })
}

async function firebaseIdentity(request: Request): Promise<{ uid: string }> {
  const token = request.headers.get('authorization')?.replace(/^Bearer\s+/i, '')
  if (!token) throw new Error('Missing Firebase ID token')
  const { payload } = await jwtVerify(token, firebaseJwks, {
    algorithms: ['RS256'], audience: firebaseProjectId,
    issuer: `https://securetoken.google.com/${firebaseProjectId}`,
  })
  if (typeof payload.sub !== 'string' || !payload.sub) throw new Error('Invalid Firebase subject')
  return { uid: payload.sub }
}

/** Epoch millis (Kotlin Long, 0 = "not set") -> ISO string, or null. */
function msToIso(value: unknown): string | null {
  const n = Number(value)
  if (!Number.isFinite(n) || n <= 0) return null
  return new Date(n).toISOString()
}

function str(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function num(value: unknown): number {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

/** Field-for-field mapping from ClaimInfo's JSON shape (camelCase, as Android's
 *  org.json.JSONObject sends it) to public.claims' columns (snake_case).
 *  Conveyance columns (vehicle/from_area/to_area/attempt_quantity/
 *  delivered_quantity/cid_or_merchant/expense_date) are intentionally absent —
 *  ClaimInfo has no matching fields yet (see migration b580d2f's commit message:
 *  schema-only, ahead of that feature actually being built), so they're left at
 *  their SQL defaults here rather than guessed at.
 *  *_name fields (branchName, employeeName, staffByName, pocApprovedByName,
 *  settleInProcessByName, settledByName, rejectedByName) are deliberately
 *  dropped — not columns, per the migration's own design (join through
 *  public.users/branches at read time instead of carrying a driftable copy). */
function toClaimsRow(c: Record<string, unknown>) {
  return {
    id:                        str(c.claimId),
    claim_code:                str(c.claimCode),
    branch_id:                 str(c.branchId),
    employee_id:               str(c.employeeId),
    agent_system_id:           str(c.agentSystemId),
    type:                      str(c.type),
    category:                  str(c.category),
    purpose:                   str(c.purpose),
    consignment_id:            str(c.consignmentId),
    store_id:                  str(c.storeId),
    store_name:                str(c.storeName),
    pickup_count:              num(c.pickupCount),
    requested_amount:          num(c.requestedAmount),
    approved_amount:           num(c.approvedAmount),
    settled_amount:            num(c.settledAmount),
    payment_method:            str(c.paymentMethod),
    transaction_id:            str(c.transactionId),
    status:                    str(c.status) || 'pending',
    priority:                  str(c.priority) || 'normal',
    attachment_url:            str(c.attachmentUrl),
    attachment_name:           str(c.attachmentName),
    worker_uid:                str(c.workerUid),
    worker_role:               str(c.workerRole),
    requested_at:              msToIso(c.requestedAt),
    approved_at:               msToIso(c.approvedAt),
    settled_at:                msToIso(c.settledAt),
    created_at:                msToIso(c.createdAt) ?? new Date().toISOString(),
    updated_at:                msToIso(c.updatedAt) ?? new Date().toISOString(),
    staff_by_uid:              str(c.staffByUid),
    staff_at:                  msToIso(c.staffAt),
    staff_comment:             str(c.staffComment),
    poc_approved_by_uid:       str(c.pocApprovedByUid),
    poc_comment:               str(c.pocComment),
    settle_in_process_by_uid:  str(c.settleInProcessByUid),
    settle_in_process_at:      msToIso(c.settleInProcessAt),
    settled_by_uid:            str(c.settledByUid),
    rejected_by_uid:           str(c.rejectedByUid),
    rejected_at:               msToIso(c.rejectedAt),
    reject_reason:             str(c.rejectReason),
  }
}

Deno.serve(async (request) => {
  if (request.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders })
  if (request.method !== 'POST') return reply({ error: 'Method not allowed' }, 405)
  try {
    await firebaseIdentity(request) // throws if the token is missing/invalid — caller must be a real signed-in user
    const body = await request.json()
    if (body.action !== 'upsert_claim') return reply({ error: 'Unknown action' }, 400)

    const claim = body.claim
    if (!claim || typeof claim !== 'object') return reply({ error: 'claim object is required' }, 400)
    const row = toClaimsRow(claim as Record<string, unknown>)
    if (!row.id || !row.branch_id || !row.agent_system_id) {
      return reply({ error: 'claim.claimId, branchId and agentSystemId are required' }, 400)
    }

    const { error } = await admin.from('claims').upsert(row, { onConflict: 'id' })
    if (error) throw error
    return reply({ ok: true })
  } catch (error) {
    console.error(error)
    return reply({ error: 'Unauthorized or failed request' }, 401)
  }
})
