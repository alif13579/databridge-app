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

/** Resolve a known system id only when a write needs to create its master row. */
async function firebaseProfileForSystemId(systemId: string, identity: { uid: string; token: string }): Promise<FirebaseProfile | null> {
  const index = await firebaseRead(identity, `users_by_systemId/${encodeURIComponent(systemId)}`) as { uid?: unknown } | null
  if (typeof index?.uid !== 'string' || !index.uid) return null
  // The assigned user need not be the caller, so read only the public profile
  // fields required to maintain the master `users` row.
  const response = await fetch(`${firebaseDatabaseUrl}/users/${encodeURIComponent(index.uid)}/profile.json?auth=${encodeURIComponent(identity.token)}`)
  if (!response.ok) return null
  const profile = await response.json()
  const info = profile?.company_info
  const resolvedId = typeof info?.system_id === 'string' ? info.system_id.trim() : ''
  if (resolvedId !== systemId) return null
  const branches = info?.branch_ids
  const branchIds = Array.isArray(branches) ? branches : branches && typeof branches === 'object' ? Object.values(branches) : []
  return {
    systemId: resolvedId,
    roleId: typeof info?.role_id === 'string' ? info.role_id.trim() : '',
    branchIds: branchIds.filter((id): id is string => typeof id === 'string' && id.trim()).map((id) => id.trim()),
    canAccessCallCenter: false,
    name: typeof profile?.name === 'string' ? profile.name.trim() : '',
    employeeId: typeof info?.employee_id === 'string' ? info.employee_id.trim() : '',
  }
}

async function upsertUser(profile: FirebaseProfile, firebaseId?: string) {
  const { error } = await admin.from('users').upsert({
    system_id: profile.systemId,
    employee_id: profile.employeeId || null,
    name: profile.name || profile.systemId,
    // branch_id (singular) kept in sync for any other code still reading it;
    // branch_ids (array) is what RLS's my_branch_ids() actually checks against —
    // a single value silently dropped every branch after an agent's first one.
    branch_id: profile.branchIds[0] || null,
    branch_ids: profile.branchIds,
    role: profile.roleId || null,
    firebase_id: firebaseId || null,
    updated_at: new Date().toISOString(),
  }, { onConflict: 'system_id' })
  if (error) throw error
}

type ServiceAccount = { client_email: string; private_key: string; project_id?: string }

async function firebaseRead(identity: { uid: string; token: string }, path: string): Promise<unknown> {
  const response = await fetch(`${firebaseDatabaseUrl}/${path}.json?auth=${encodeURIComponent(identity.token)}`)
  return response.ok ? response.json() : null
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

async function notificationDetails(row: { consignment: string; author_system_id: string; remarks_status: string; remarks: string }, identity: { uid: string; token: string }) {
  const parcel = await firebaseRead(identity, `courier/consignments/${encodeURIComponent(row.consignment)}`) as Record<string, unknown> | null
  const index = await firebaseRead(identity, `users_by_systemId/${encodeURIComponent(row.author_system_id)}`) as { uid?: unknown } | null
  const authorProfile = typeof index?.uid === 'string'
    ? await firebaseRead(identity, `users/${encodeURIComponent(index.uid)}/profile`) as Record<string, unknown> | null
    : null
  const author = typeof authorProfile?.name === 'string' && authorProfile.name.trim() ? authorProfile.name.trim() : 'Agent'
  const customer = typeof parcel?.recipientName === 'string' && parcel.recipientName.trim() ? parcel.recipientName.trim() : row.consignment
  const attempt = Number(parcel?.attempt) || 0
  const base = row.remarks || 'নতুন রিমার্ক এসেছে'
  return {
    title: `${author} — ${customer}`,
    body: `${base}\n📅 ${ageLabel(parcel?.createdAt, parcel?.updatedAt)}  •  🔁 ${attempt} attempt${attempt === 1 ? '' : 's'}`,
  }
}

async function sendRemarkPush(row: { consignment: string; branch_id: string; assigned_to_system_id: string; author_system_id: string; remarks_status: string; remarks: string }, identity: { uid: string; token: string }) {
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
    const fromWorker = row.assigned_to_system_id === row.author_system_id
    let tokenQuery = admin.from('fcm_device_tokens').select('token')
    if (fromWorker) {
      tokenQuery = tokenQuery.eq('can_access_call_center', true).overlaps('branch_ids', [row.branch_id])
    } else {
      tokenQuery = tokenQuery.eq('system_id', row.assigned_to_system_id)
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
          // Data-only (no `notification` block): Android always calls
          // onMessageReceived() regardless of foreground/background state.
          // With a `notification` block present, FCM auto-handles the message
          // when the app is backgrounded and onMessageReceived() is NOT called,
          // so AppNotificationManager.add() never fires and the in-app drawer
          // never receives the event.
          // AppNotificationManager.add() calls showSystemNotification() itself,
          // so the system tray notification still appears in all cases.
          data: {
            type: 'remark', title, body,
            consignment_id: row.consignment,
            scope: recipientScope,
            notif_parcel_id: row.consignment,
            notif_scope: recipientScope,
          },
          android: {
            priority: 'high',
          },
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
      if (!row || !['consignment', 'branch_id', 'assigned_to_system_id', 'source'].every((key) => typeof row[key] === 'string' && row[key].trim())) {
        return reply({ error: 'Missing required row fields' }, 400)
      }
      if (row.source !== 'CC' && row.source !== 'WORKER') {
        return reply({ error: 'Invalid remark source' }, 400)
      }
      // Author fields come exclusively from the verified Firebase identity; Android
      // never supplies them, so a caller cannot impersonate another employee.
      const authorProfile = await firebaseProfile(identity)
      await upsertUser(authorProfile, identity.uid)
      if (row.assigned_to_system_id === authorProfile.systemId) {
        // Already upserted above; avoids a duplicate Firebase profile request.
      } else {
        const assignedProfile = await firebaseProfileForSystemId(row.assigned_to_system_id, identity)
        if (!assignedProfile) return reply({ error: 'Assigned user was not found' }, 400)
        await upsertUser(assignedProfile)
      }
      const parcelPromise = firebaseRead(
        identity, `courier/consignments/${encodeURIComponent(row.consignment)}`
      ) as Promise<Record<string, unknown> | null>
      // Worker writes have the same assigned agent and author; reuse the verified profile instead
      // of doing another Firebase profile lookup for the same person.
      const parcel = await parcelPromise
      const savedRow = {
        consignment: row.consignment, branch_id: row.branch_id,
        assigned_to_system_id: row.assigned_to_system_id,
        source: row.source,
        author_system_id: authorProfile.systemId,
        remarks_status: typeof row.remarks_status === 'string' ? row.remarks_status : '',
        consignment_status: typeof parcel?.status === 'string' ? parcel.status.trim() : '',
        remarks: typeof row.remarks === 'string' ? row.remarks : '',
        note: typeof row.note === 'string' ? row.note : '',
        customer_phone: typeof parcel?.recipientPhone === 'string' ? parcel.recipientPhone.trim() : '',
      }
      const { error } = await admin.from('validations').insert(savedRow)
      if (error) throw error
      await sendRemarkPush(savedRow, identity)
      return reply({ ok: true })
    }

    let query = admin.from('validations')
      .select('id,consignment,branch_id,assigned_to_system_id,author_system_id,source,remarks_status,consignment_status,remarks,note,customer_phone,created_at,author:users!validations_author_system_id_fkey(name,employee_id,role),assigned:users!validations_assigned_to_system_id_fkey(name,employee_id,role)')
    // history, today, agent_range, new_since — removed: Android now calls the
    // PostgREST REST API directly (unlimited free tier, no invocation consumed).
    if (action === 'report') {
      if (typeof body.branch_id !== 'string' || typeof body.start_iso !== 'string' || typeof body.end_iso !== 'string') {
        return reply({ error: 'branch_id, start_iso and end_iso are required' }, 400)
      }
      const page = Math.max(0, Number(body.page) || 0)
      const pageSize = Math.min(100, Math.max(1, Number(body.page_size) || 50))
      query = query.eq('branch_id', body.branch_id).gte('created_at', body.start_iso).lt('created_at', body.end_iso)
      for (const field of ['consignment', 'assigned_to_system_id', 'author_system_id', 'remarks_status', 'consignment_status', 'source'] as const) {
        if (typeof body[field] === 'string' && body[field].trim()) query = query.eq(field, body[field].trim())
      }
      const { data, error } = await query.order('created_at', { ascending: false }).range(page * pageSize, (page + 1) * pageSize - 1)
      if (error) throw error
      return reply(data ?? [])
    }
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
