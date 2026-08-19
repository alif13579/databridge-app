import { createClient } from 'npm:@supabase/supabase-js@2'
import { createRemoteJWKSet, jwtVerify } from 'npm:jose@5'

const corsHeaders = {
  'Access-Control-Allow-Headers': 'authorization, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Content-Type': 'application/json',
}

const firebaseProjectId = Deno.env.get('FIREBASE_PROJECT_ID')
if (!firebaseProjectId) throw new Error('FIREBASE_PROJECT_ID is required')
const firebaseDatabaseUrl = Deno.env.get('FIREBASE_DATABASE_URL')?.replace(/\/$/, '')
if (!firebaseDatabaseUrl) throw new Error('FIREBASE_DATABASE_URL is required')

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

async function firebaseIdentity(request: Request): Promise<{ uid: string; token: string }> {
  const token = request.headers.get('authorization')?.replace(/^Bearer\s+/i, '')
  if (!token) throw new Error('Missing Firebase ID token')
  const { payload } = await jwtVerify(token, firebaseJwks, {
    algorithms: ['RS256'], audience: firebaseProjectId,
    issuer: `https://securetoken.google.com/${firebaseProjectId}`,
  })
  if (typeof payload.sub !== 'string' || !payload.sub) throw new Error('Invalid Firebase subject')
  return { uid: payload.sub, token }
}

async function firebaseSystemId(identity: { uid: string; token: string }): Promise<string> {
  const response = await fetch(
    `${firebaseDatabaseUrl}/users/${encodeURIComponent(identity.uid)}/profile/company_info/system_id.json?auth=${encodeURIComponent(identity.token)}`,
  )
  if (!response.ok) throw new Error('Unable to resolve the signed-in user profile')
  const systemId = await response.json()
  if (typeof systemId !== 'string' || !systemId.trim()) throw new Error('Signed-in user has no system_id')
  return systemId
}

Deno.serve(async (request) => {
  if (request.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders })
  if (request.method !== 'POST') return reply({ error: 'Method not allowed' }, 405)
  try {
    const identity = await firebaseIdentity(request)
    const body = await request.json()
    const action = body.action

    if (action === 'write') {
      const row = body.row
      if (!row || !['consignment_id', 'branch_id', 'delivery_agent_id', 'verifier_id'].every((key) => typeof row[key] === 'string' && row[key].trim())) {
        return reply({ error: 'Missing required row fields' }, 400)
      }
      if (row.verifier_id !== await firebaseSystemId(identity)) {
        return reply({ error: 'verifier_id does not belong to the signed-in user' }, 403)
      }
      const { error } = await admin.from('remark_validations').insert({
        consignment_id: row.consignment_id, branch_id: row.branch_id,
        delivery_agent_id: row.delivery_agent_id, verifier_id: row.verifier_id,
        status: typeof row.status === 'string' ? row.status : '',
        remarks: typeof row.remarks === 'string' ? row.remarks : '',
      })
      if (error) throw error
      return reply({ ok: true })
    }

    let query = admin.from('remark_validations')
      .select('consignment_id,branch_id,delivery_agent_id,verifier_id,status,remarks,created_at')
    if (action === 'history') query = query.eq('consignment_id', body.consignment_id)
    else if (action === 'today') query = query.eq('delivery_agent_id', body.delivery_agent_id).gte('created_at', body.start_iso)
    else if (action === 'agent_range') query = query.eq('delivery_agent_id', body.delivery_agent_id).gte('created_at', body.start_iso).lte('created_at', body.end_iso)
    else if (action === 'new_since') query = query.in('consignment_id', body.consignment_ids).gt('created_at', body.since_iso)
    else return reply({ error: 'Unknown action' }, 400)
    const { data, error } = await query.order('created_at', { ascending: false })
    if (error) throw error
    console.log(JSON.stringify({ action, firebaseUid: identity.uid, count: data?.length ?? 0 }))
    return reply(data ?? [])
  } catch (error) {
    console.error(error)
    return reply({ error: 'Unauthorized or failed request' }, 401)
  }
})
