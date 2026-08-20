import { createClient } from 'npm:@supabase/supabase-js@2'
import { createRemoteJWKSet, jwtVerify } from 'npm:jose@5'
import { JWT } from 'npm:google-auth-library@9'

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

type FirebaseProfile = {
  systemId: string; roleId: string; branchIds: string[]; canAccessCallCenter: boolean
  name: string; employeeId: string
}

function permissionEnabled(node: unknown, permission: string): boolean {
  if (Array.isArray(node)) return node.includes(permission)
  return !!node && typeof node === 'object' && (node as Record<string, unknown>)[permission] === true
}

/** Reads only the caller's profile, using their verified Firebase ID token. */
async function firebaseProfile(identity: { uid: string; token: string }): Promise<FirebaseProfile> {
  const response = await fetch(
    `${firebaseDatabaseUrl}/users/${encodeURIComponent(identity.uid)}/profile.json?auth=${encodeURIComponent(identity.token)}`,
  )
  if (!response.ok) throw new Error('Unable to resolve the signed-in user profile')
  const profile = await response.json()
  const companyInfo = profile?.company_info
  const systemId = typeof companyInfo?.system_id === 'string' ? companyInfo.system_id.trim() : ''
  if (!systemId) throw new Error('Signed-in user has no system_id')
  let roleId = typeof companyInfo?.role_id === 'string' ? companyInfo.role_id.trim()
    : typeof companyInfo?.role === 'string' ? companyInfo.role.trim() : ''
  if (!roleId) {
    const roleResponse = await fetch(
      `${firebaseDatabaseUrl}/users/${encodeURIComponent(identity.uid)}/role.json?auth=${encodeURIComponent(identity.token)}`,
    )
    if (roleResponse.ok) {
      const legacyRole = await roleResponse.json()
      if (typeof legacyRole === 'string') roleId = legacyRole.trim()
    }
  }
  const rawBranchIds = companyInfo?.branch_ids
  const branchIds = Array.isArray(rawBranchIds) ? rawBranchIds
    : rawBranchIds && typeof rawBranchIds === 'object' ? Object.values(rawBranchIds) : []
  // This mirrors RbacManager.hasPermission("nav_call_center"): a per-user
  // override, when present, takes precedence over the role permission.
  const overridePermissions = companyInfo?.access_overrides?.permissions
  const overrideActive = overridePermissions !== null && overridePermissions !== undefined
  let rolePermissions: unknown = null
  if (!overrideActive && roleId) {
    const roleResponse = await fetch(
      `${firebaseDatabaseUrl}/roles/${encodeURIComponent(roleId)}/permissions.json?auth=${encodeURIComponent(identity.token)}`,
    )
    if (roleResponse.ok) rolePermissions = await roleResponse.json()
  }
  return {
    systemId, roleId,
    branchIds: branchIds.filter((id): id is string => typeof id === 'string' && id.trim()).map((id) => id.trim()),
    canAccessCallCenter: permissionEnabled(overrideActive ? overridePermissions : rolePermissions, 'nav_call_center'),
    name: typeof profile?.name === 'string' ? profile.name.trim() : '',
    employeeId: typeof companyInfo?.employee_id === 'string' ? companyInfo.employee_id.trim() : '',
  }
}

type ServiceAccount = { client_email: string; private_key: string; project_id?: string }

async function firebaseRead(identity: { uid: string; token: string }, path: string): Promise<unknown> {
  const response = await fetch(`${firebaseDatabaseUrl}/${path}.json?auth=${encodeURIComponent(identity.token)}`)
  return response.ok ? response.json() : null
}

async function profileForSystemId(identity: { uid: string; token: string }, systemId: string): Promise<{ name: string; employeeId: string }> {
  const index = await firebaseRead(identity, `users_by_systemId/${encodeURIComponent(systemId)}`) as { uid?: unknown } | null
  if (typeof index?.uid !== 'string' || !index.uid) return { name: '', employeeId: '' }
  const profile = await firebaseRead(identity, `users/${encodeURIComponent(index.uid)}/profile`) as Record<string, unknown> | null
  const companyInfo = profile?.company_info as Record<string, unknown> | undefined
  return {
    name: typeof profile?.name === 'string' ? profile.name.trim() : '',
    employeeId: typeof companyInfo?.employee_id === 'string' ? companyInfo.employee_id.trim() : '',
  }
}

function auditActorName(name: string, employeeId: string, fallbackSystemId: string): string {
  const displayName = name || fallbackSystemId
  return employeeId ? `${displayName} (${employeeId})` : displayName
}

function asMillis(value: unknown): number {
  const number = typeof value === 'number' ? value : typeof value === 'string' ? Number(value) : 0
  return Number.isFinite(number) ? (number > 0 && number < 100_000_000_000 ? number * 1000 : number) : 0
}

function ageLabel(createdAt: unknown, updatedAt: unknown): string {
  const created = asMillis(createdAt)
  if (!created) return '—'
  const diff = Math.max(0, (asMillis(updatedAt) || Date.now()) - created)
  const days = Math.floor(diff / 86_400_000)
  const hours = Math.floor(diff / 3_600_000)
  const minutes = Math.floor(diff / 60_000)
  if (days) return `${days} ${days === 1 ? 'Day' : 'Days'}`
  if (hours) return `${hours} ${hours === 1 ? 'Hour' : 'Hours'}`
  if (minutes) return `${minutes} ${minutes === 1 ? 'Minute' : 'Minutes'}`
  return 'Just now'
}

async function notificationDetails(row: { consignment_id: string; agent_system_id: string; verifier_system_id: string; status: string; remarks: string }, identity: { uid: string; token: string }) {
  const parcel = await firebaseRead(identity, `courier/consignments/${encodeURIComponent(row.consignment_id)}`) as Record<string, unknown> | null
  const authorSystemId = row.agent_system_id === row.verifier_system_id ? row.agent_system_id : row.verifier_system_id
  const index = await firebaseRead(identity, `users_by_systemId/${encodeURIComponent(authorSystemId)}`) as { uid?: unknown } | null
  const authorProfile = typeof index?.uid === 'string'
    ? await firebaseRead(identity, `users/${encodeURIComponent(index.uid)}/profile`) as Record<string, unknown> | null
    : null
  const author = typeof authorProfile?.name === 'string' && authorProfile.name.trim() ? authorProfile.name.trim() : 'Agent'
  const customer = typeof parcel?.recipientName === 'string' && parcel.recipientName.trim() ? parcel.recipientName.trim() : row.consignment_id
  const attempt = Number(parcel?.attempt) || 0
  const base = [row.status, row.remarks].filter(Boolean).join(' — ') || 'নতুন রিমার্ক এসেছে'
  return {
    title: `${author} — ${customer}`,
    body: `${base}\n📅 ${ageLabel(parcel?.createdAt, parcel?.updatedAt)}  •  🔁 ${attempt} attempt${attempt === 1 ? '' : 's'}`,
  }
}

async function sendRemarkPush(row: { consignment_id: string; branch_id: string; agent_system_id: string; verifier_system_id: string; status: string; remarks: string }, identity: { uid: string; token: string }) {
  // A failed or not-yet-configured push must never prevent the audit record from saving.
  const serviceAccountJson = Deno.env.get('FCM_SERVICE_ACCOUNT_JSON')
  if (!serviceAccountJson) {
    console.warn('FCM_SERVICE_ACCOUNT_JSON is not configured; push skipped')
    return
  }
  try {
    const serviceAccount = JSON.parse(serviceAccountJson) as ServiceAccount
    if (!serviceAccount.client_email || !serviceAccount.private_key) throw new Error('Invalid FCM service account')

    // CC -> worker: send only to that worker. Worker -> CC: notify only users
    // whose Firebase RBAC permission grants access to the Call Center fragment.
    const fromWorker = row.agent_system_id === row.verifier_system_id
    let tokenQuery = admin.from('fcm_device_tokens').select('token')
    if (fromWorker) {
      tokenQuery = tokenQuery.eq('can_access_call_center', true).overlaps('branch_ids', [row.branch_id])
    } else {
      tokenQuery = tokenQuery.eq('system_id', row.agent_system_id)
    }
    const { data: devices, error: deviceError } = await tokenQuery
    if (deviceError) throw deviceError
    if (!devices?.length) return

    const jwt = new JWT({
      email: serviceAccount.client_email,
      key: serviceAccount.private_key,
      scopes: ['https://www.googleapis.com/auth/firebase.messaging'],
    })
    const { access_token: accessToken } = await jwt.authorize()
    if (!accessToken) throw new Error('Unable to authorize FCM request')

    const recipientScope = fromWorker ? 'cc' : 'worker'
    const { title, body } = await notificationDetails(row, identity)
    const projectId = serviceAccount.project_id || firebaseProjectId
    await Promise.all(devices.map(async ({ token }) => {
      const response = await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId!)}/messages:send`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: {
          token,
          data: {
            type: 'remark', title, body,
            consignment_id: row.consignment_id,
            scope: recipientScope,
          },
          android: { priority: 'high' },
        } }),
      })
      if (!response.ok) console.error(`FCM send failed (${response.status}): ${await response.text()}`)
    }))
  } catch (error) {
    console.error('FCM push failed', error)
  }
}

Deno.serve(async (request) => {
  if (request.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders })
  if (request.method !== 'POST') return reply({ error: 'Method not allowed' }, 405)
  try {
    const identity = await firebaseIdentity(request)
    const body = await request.json()
    const action = body.action

    if (action === 'register_push_token') {
      if (typeof body.token !== 'string' || body.token.trim().length < 20) {
        return reply({ error: 'Invalid push token' }, 400)
      }
      const profile = await firebaseProfile(identity)
      const { error } = await admin.from('fcm_device_tokens').upsert({
        token: body.token.trim(), firebase_uid: identity.uid, system_id: profile.systemId,
        role_id: profile.roleId, branch_ids: profile.branchIds,
        can_access_call_center: profile.canAccessCallCenter, updated_at: new Date().toISOString(),
      }, { onConflict: 'token' })
      if (error) throw error
      return reply({ ok: true })
    }

    if (action === 'write') {
      const row = body.row
      if (!row || !['consignment_id', 'branch_id', 'agent_system_id', 'verifier_system_id'].every((key) => typeof row[key] === 'string' && row[key].trim())) {
        return reply({ error: 'Missing required row fields' }, 400)
      }
      const verifierProfile = await firebaseProfile(identity)
      if (row.verifier_system_id !== verifierProfile.systemId) {
        return reply({ error: 'verifier_system_id does not belong to the signed-in user' }, 403)
      }
      const parcelPromise = firebaseRead(
        identity, `courier/consignments/${encodeURIComponent(row.consignment_id)}`
      ) as Promise<Record<string, unknown> | null>
      // Worker writes have the same agent and verifier; reuse the verified profile instead
      // of doing another Firebase profile lookup for the same person.
      const agentProfile = row.agent_system_id === verifierProfile.systemId
        ? verifierProfile
        : await profileForSystemId(identity, row.agent_system_id)
      const parcel = await parcelPromise
      const savedRow = {
        consignment_id: row.consignment_id, branch_id: row.branch_id,
        agent_system_id: row.agent_system_id, verifier_system_id: row.verifier_system_id,
        status: typeof row.status === 'string' ? row.status : '',
        remarks: typeof row.remarks === 'string' ? row.remarks : '',
        note: typeof row.note === 'string' ? row.note : '',
        customer_phone: typeof parcel?.recipientPhone === 'string' ? parcel.recipientPhone.trim() : '',
        agent_name: auditActorName(agentProfile.name, agentProfile.employeeId, row.agent_system_id),
        verifier_name: auditActorName(verifierProfile.name, verifierProfile.employeeId, row.verifier_system_id),
      }
      const { error } = await admin.from('remark_validations').insert(savedRow)
      if (error) throw error
      await sendRemarkPush(savedRow, identity)
      return reply({ ok: true })
    }

    let query = admin.from('remark_validations')
      .select('consignment_id,branch_id,agent_system_id,verifier_system_id,status,remarks,note,customer_phone,agent_name,verifier_name,created_at')
    if (action === 'history') query = query.eq('consignment_id', body.consignment_id)
    else if (action === 'today') query = query.eq('agent_system_id', body.agent_system_id).gte('created_at', body.start_iso)
    else if (action === 'agent_range') query = query.eq('agent_system_id', body.agent_system_id).gte('created_at', body.start_iso).lte('created_at', body.end_iso)
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
