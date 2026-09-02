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
  canAccessConfig: boolean; name: string; employeeId: string
}

function permissionEnabled(node: unknown, permission: string): boolean {
  if (Array.isArray(node)) return node.includes(permission)
  return !!node && typeof node === 'object' && (node as Record<string, unknown>)[permission] === true
}

function readBranchIds(companyInfo: Record<string, unknown> | null | undefined): string[] {
  const raw = companyInfo?.branch_ids
  const values = Array.isArray(raw)
    ? raw
    : raw && typeof raw === 'object' ? Object.values(raw) : []
  const ids = values.filter((id): id is string => typeof id === 'string' && id.trim())
    .map((id) => id.trim())
  if (ids.length) return [...new Set(ids)]
  // Legacy profiles may still have only a singular branch_id/branch field.
  return [companyInfo?.branch_id, companyInfo?.branch]
    .filter((id): id is string => typeof id === 'string' && id.trim())
    .map((id) => id.trim())
    .filter((id, index, all) => all.indexOf(id) === index)
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
  const branchIds = readBranchIds(companyInfo)
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
    branchIds,
    canAccessCallCenter: permissionEnabled(overrideActive ? overridePermissions : rolePermissions, 'nav_call_center'),
    // Mirrors RbacManager.hasPermission("nav_config") — same override-vs-role
    // permissions node already fetched above, no extra Firebase read needed.
    canAccessConfig: permissionEnabled(overrideActive ? overridePermissions : rolePermissions, 'nav_config'),
    name: typeof profile?.name === 'string' ? profile.name.trim() : '',
    employeeId: typeof companyInfo?.employee_id === 'string' ? companyInfo.employee_id.trim() : '',
  }
}

/** Resolve a known system id only when a write needs to create its master row.
 *  Returns the assigned user's Firebase uid alongside their profile — callers must
 *  pass it through to upsertUser(), or that user's users.firebase_id row gets
 *  overwritten with NULL (see upsertUser's firebaseId param), silently breaking
 *  RLS's my_system_id()/my_branch_ids() for them (branch_id = any(my_branch_ids())
 *  and assigned_to_system_id = my_system_id() both resolve to NULL — reads return
 *  a genuinely empty [], not an error) until they get a fresh sync_profile call. */
async function firebaseProfileForSystemId(systemId: string, identity: { uid: string; token: string }): Promise<(FirebaseProfile & { uid: string }) | null> {
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
  const branchIds = readBranchIds(info)
  return {
    systemId: resolvedId,
    roleId: typeof info?.role_id === 'string' ? info.role_id.trim() : '',
    branchIds,
    canAccessCallCenter: false,
    name: typeof profile?.name === 'string' ? profile.name.trim() : '',
    employeeId: typeof info?.employee_id === 'string' ? info.employee_id.trim() : '',
    uid: index.uid,
  }
}

/** Upserts the English->Bangla pair into the validation_remarks helper table so
 *  Android's direct PostgREST reads can look up Bangla by English text. Only
 *  called when the app actually sent a Bangla label (a predefined remark
 *  option) — a free-typed note has no Bangla counterpart, so it's simply
 *  skipped here and stays English-only wherever it's read.
 *
 *  source ('CC' or 'WORKER', same vocabulary as validations.source) scopes the
 *  conflict target to (source, remarks_en) — see migration 202608250002 for why
 *  remarks_en alone stopped being unique once Worker and CC options share this
 *  table. This upsert only ever touches remarks_en/remarks_bn/updated_at on an
 *  existing row; it never creates a new option (target_status, priority, etc.
 *  would be missing) — a row must already exist here, written by the admin
 *  options screen, for this to update anything. */
async function upsertRemarkLabel(source: string, remarksEn: string, remarksBn: string) {
  const en = remarksEn.trim()
  const bn = remarksBn.trim()
  if (!en || !bn) return
  const { error } = await admin.from('validation_remarks').upsert({
    source, remarks_en: en, remarks_bn: bn, updated_at: new Date().toISOString(),
  }, { onConflict: 'source,remarks_en', ignoreDuplicates: false })
  // A failed catalog upsert must never block the actual remark write.
  if (error) console.error('upsertRemarkLabel failed', error)
}

/**
 * ── DO NOT make `firebaseId` optional again ─────────────────────────────────
 * Every call MUST pass the target user's real Firebase uid. This upsert runs
 * `onConflict: 'system_id'`, so it updates that person's EXISTING row — and
 * `firebase_id: firebaseId || null` means any call that omits it OVERWRITES
 * an already-correct firebase_id with NULL.
 *
 * That happened for real on 2026-08-23: firebaseProfileForSystemId() resolved
 * the assigned worker's uid internally but didn't return it, and the write
 * action's `upsertUser(assignedProfile)` call (no 2nd arg) nulled that
 * worker's firebase_id on every CC-assigned remark. Both my_branch_ids() and
 * my_system_id() look users up BY firebase_id, so once it was NULL every RLS
 * read for that worker — branch-scoped and assigned_to_system_id alike —
 * silently resolved to an empty result. HTTP 200, "genuine empty []", no
 * error anywhere. Looked exactly like a missing/misapplied RLS migration and
 * cost a multi-hour debugging session (see commit 6b3a39c) before the actual
 * cause — corrupted data, not a bad policy — was found.
 *
 * firebaseId is a required param specifically so a future call site CANNOT
 * compile without deciding what to pass. If a caller genuinely has no uid
 * for this user yet, that is itself a bug to fix at the caller, not a reason
 * to silently write NULL here.
 */
async function upsertUser(profile: FirebaseProfile, firebaseId: string) {
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

/** Mints a short-lived Google OAuth2 access token for the given scope from the Firebase
 *  service account (the same key already used for FCM — a project's default Firebase
 *  Admin SDK service account has these permissions by default, no separate credential
 *  needed). */
async function googleAccessToken(scope: string): Promise<string> {
  const serviceAccountJson = Deno.env.get('FCM_SERVICE_ACCOUNT_JSON')
  if (!serviceAccountJson) throw new Error('FCM_SERVICE_ACCOUNT_JSON is not configured')
  const serviceAccount = JSON.parse(serviceAccountJson) as ServiceAccount
  const jwt = new JWT({ email: serviceAccount.client_email, key: serviceAccount.private_key, scopes: [scope] })
  const { access_token: accessToken } = await jwt.authorize()
  if (!accessToken) throw new Error(`Unable to authorize Google request for scope ${scope}`)
  return accessToken
}

/** Supabase's Third-Party Auth (Firebase) reads the `role` claim from the JWT to decide
 *  which Postgres role a direct REST/Realtime request runs as. Firebase ID tokens never
 *  carry a `role` claim by default, so without this every such request silently runs as
 *  `anon` — RLS policies scoped `to authenticated` never even evaluate, regardless of how
 *  correct branch_ids/firebase_id mapping is. See:
 *  https://supabase.com/docs/guides/auth/third-party/firebase-auth#assign-the-role-custom-claim
 *  Idempotent: skips the write if the claim is already set. Errors are logged, not thrown —
 *  this must never block a write or a profile sync from completing. */
async function ensureAuthenticatedRoleClaim(uid: string): Promise<void> {
  try {
    const accessToken = await googleAccessToken('https://www.googleapis.com/auth/identitytoolkit')
    const lookupRes = await fetch('https://identitytoolkit.googleapis.com/v1/accounts:lookup', {
      method: 'POST',
      headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ localId: [uid] }),
    })
    if (!lookupRes.ok) throw new Error(`accounts:lookup HTTP ${lookupRes.status}: ${await lookupRes.text()}`)
    const lookupJson = await lookupRes.json()
    const account = lookupJson?.users?.[0]
    const existingClaims = account?.customAttributes ? JSON.parse(account.customAttributes) : {}
    if (existingClaims.role === 'authenticated') return // already set, nothing to do

    const updateRes = await fetch('https://identitytoolkit.googleapis.com/v1/accounts:update', {
      method: 'POST',
      headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        localId: uid,
        customAttributes: JSON.stringify({ ...existingClaims, role: 'authenticated' }),
      }),
    })
    if (!updateRes.ok) throw new Error(`accounts:update HTTP ${updateRes.status}: ${await updateRes.text()}`)
    console.info(`ensureAuthenticatedRoleClaim: set role=authenticated for uid=${uid}`)
  } catch (error) {
    console.error('ensureAuthenticatedRoleClaim failed', error)
  }
}

async function firebaseRead(identity: { uid: string; token: string }, path: string): Promise<unknown> {
  const response = await fetch(`${firebaseDatabaseUrl}/${path}.json?auth=${encodeURIComponent(identity.token)}`)
  return response.ok ? response.json() : null
}

/** Looks up the Bangla label for a saved remark from the validation_remarks
 *  helper table (populated by upsertRemarkLabel() on every write that included
 *  a Bangla label), scoped to the same source ('CC'/'WORKER') the remark was
 *  saved under — remarks_en alone isn't unique once Worker and CC options
 *  share this table (see migration 202608250002), so a lookup must match on
 *  both to avoid picking up the wrong scope's Bangla for the same English
 *  text. Falls back to the raw English text when there's no match — a
 *  free-typed note, or a remark saved before this table existed. */
async function resolveRemarkBn(source: string, remarksText: string): Promise<string> {
  const en = remarksText.trim()
  if (!en) return remarksText
  const { data, error } = await admin.from('validation_remarks').select('remarks_bn')
    .eq('source', source).eq('remarks_en', en).maybeSingle()
  if (error) { console.error('resolveRemarkBn lookup failed', error); return remarksText }
  return data?.remarks_bn || remarksText
}

/** Batch version for the report action: one lookup query for every distinct
 *  (source, English remark) pair in the page, instead of one per row. Adds
 *  `remarks_bn` to each row (falls back to the English text when there's no
 *  match, same as resolveRemarkBn). Android's own direct-PostgREST reads do
 *  the equivalent lookup themselves — fetch distinct remarks from
 *  validations, then a second query to validation_remarks — see
 *  SupabaseClientManager. */
async function withBanglaLabels<T extends { remarks: string; source: string }>(rows: T[]): Promise<(T & { remarks_bn: string })[]> {
  const distinctKeys = [...new Set(rows.map((r) => `${r.source}\u0000${r.remarks.trim()}`).filter((k) => !k.endsWith('\u0000')))]
  if (!distinctKeys.length) return rows.map((r) => ({ ...r, remarks_bn: r.remarks }))
  const bySource = new Map<string, string[]>()
  for (const key of distinctKeys) {
    const [source, en] = key.split('\u0000')
    bySource.set(source, [...(bySource.get(source) ?? []), en])
  }
  const lookups = await Promise.all([...bySource.entries()].map(async ([source, ens]) => {
    const { data, error } = await admin.from('validation_remarks').select('remarks_en,remarks_bn')
      .eq('source', source).in('remarks_en', ens)
    if (error) { console.error('withBanglaLabels lookup failed', error); return [] }
    return (data ?? []).map((r) => ({ source, ...r }))
  }))
  const map = new Map(lookups.flat().map((r) => [`${r.source}\u0000${r.remarks_en}`, r.remarks_bn]))
  return rows.map((r) => ({ ...r, remarks_bn: map.get(`${r.source}\u0000${r.remarks.trim()}`) || r.remarks }))
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

async function notificationDetails(row: { consignment: string; author_system_id: string; remarks_status: string; remarks: string; source: string }, identity: { uid: string; token: string }) {
  const parcel = await firebaseRead(identity, `courier/consignments/${encodeURIComponent(row.consignment)}`) as Record<string, unknown> | null
  const index = await firebaseRead(identity, `users_by_systemId/${encodeURIComponent(row.author_system_id)}`) as { uid?: unknown } | null
  const authorProfile = typeof index?.uid === 'string'
    ? await firebaseRead(identity, `users/${encodeURIComponent(index.uid)}/profile`) as Record<string, unknown> | null
    : null
  const author = typeof authorProfile?.name === 'string' && authorProfile.name.trim() ? authorProfile.name.trim() : 'Agent'
  const customer = typeof parcel?.recipientName === 'string' && parcel.recipientName.trim() ? parcel.recipientName.trim() : row.consignment
  const attempt = Number(parcel?.attempt) || 0
  const base = (await resolveRemarkBn(row.source, row.remarks)) || 'নতুন রিমার্ক এসেছে'
  return {
    title: `${author} — ${customer}`,
    body: `${base}\n📅 ${ageLabel(parcel?.createdAt, parcel?.updatedAt)}  •  🔁 ${attempt} attempt${attempt === 1 ? '' : 's'}`,
  }
}

async function sendRemarkPush(row: { consignment: string; branch_id: string; assigned_to_system_id: string; author_system_id: string; remarks_status: string; remarks: string; source: string }, identity: { uid: string; token: string }) {
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

    const accessToken = await googleAccessToken('https://www.googleapis.com/auth/firebase.messaging')

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

    // The app must be able to read validations before this user has ever saved a
    // remark. In particular, a worker can receive a CC remark as their first
    // interaction. Upserting through the trusted function establishes the
    // Firebase UID + branch_ids mapping used by validations RLS.
    if (action === 'sync_profile') {
      const profile = await firebaseProfile(identity)
      await upsertUser(profile, identity.uid)
      // Without this claim, Supabase's Third-Party Auth runs every direct REST/Realtime
      // request from this user as the `anon` Postgres role — RLS policies scoped
      // `to authenticated` never evaluate, regardless of correct branch_ids mapping.
      await ensureAuthenticatedRoleClaim(identity.uid)
      console.info(`sync_profile ok: system_id=${profile.systemId}, branches=${profile.branchIds.length}`)
      return reply({ ok: true, system_id: profile.systemId, branch_count: profile.branchIds.length })
    }

    if (action === 'register_push_token') {
      if (typeof body.token !== 'string' || body.token.trim().length < 20) {
        return reply({ error: 'Invalid push token' }, 400)
      }
      const profile = await firebaseProfile(identity)
      // Push registration is normally the earliest authenticated app action.
      // Keep the RLS identity mapping current here too, rather than waiting for
      // the user's first remark save.
      await upsertUser(profile, identity.uid)
      const { error } = await admin.from('fcm_device_tokens').upsert({
        token: body.token.trim(), firebase_uid: identity.uid, system_id: profile.systemId,
        role_id: profile.roleId, branch_ids: profile.branchIds,
        can_access_call_center: profile.canAccessCallCenter, updated_at: new Date().toISOString(),
      }, { onConflict: 'token' })
      if (error) throw error
      return reply({ ok: true })
    }

    if (action === 'admin_list_remarks') {
      const profile = await firebaseProfile(identity)
      if (!profile.canAccessConfig) return reply({ error: 'Not authorized for remark config' }, 403)
      if (body.source !== 'CC' && body.source !== 'WORKER') {
        return reply({ error: 'Invalid remark source' }, 400)
      }
      const { data, error } = await admin.from('validation_remarks').select('*')
        .eq('source', body.source).order('priority', { ascending: false })
      if (error) throw error
      return reply({ ok: true, remarks: data ?? [] })
    }

    // Creates a new remark option (no id in the body) or updates an existing one
    // (id present) — both go through the admin's canAccessConfig check first.
    // This is a distinct table from validations' write action above: this row
    // IS the option itself (what shows in the picker), not a saved remark.
    if (action === 'admin_upsert_remark') {
      const profile = await firebaseProfile(identity)
      if (!profile.canAccessConfig) return reply({ error: 'Not authorized for remark config' }, 403)
      const row = body.remark
      if (!row || typeof row !== 'object') return reply({ error: 'Missing remark' }, 400)
      if (row.source !== 'CC' && row.source !== 'WORKER') {
        return reply({ error: 'Invalid remark source' }, 400)
      }

      const remarkId = typeof row.id === 'string' ? row.id.trim() : ''

      if (remarkId) {
        // Partial update: a caller like ConfigRemarksFragment's per-card status
        // spinner (handleTargetChange) intentionally sends only the one field
        // it means to change (e.g. just target_status) — every other field is
        // simply absent from the request, not meant to be cleared. Fetch the
        // current row first so any field the caller didn't send keeps its
        // existing value, instead of being reset to '' / 0 / true by the same
        // default-fallback logic that's appropriate for a brand new row below.
        const { data: existing, error: fetchError } = await admin.from('validation_remarks')
          .select('*').eq('id', remarkId).maybeSingle()
        if (fetchError) throw fetchError
        if (!existing) return reply({ error: 'Remark not found' }, 404)

        // Only recompute the bn/en cross-fallback when the caller actually sent
        // a language field — a target-status-only update must never touch
        // remarks_en/remarks_bn at all, and must never trip the "one of the two
        // is required" check below (that check is about a genuine write of
        // blank text, not about a request that doesn't mention text at all).
        const sentEn = typeof row.remarks_en === 'string'
        const sentBn = typeof row.remarks_bn === 'string'
        if (sentEn || sentBn) {
          const remarksEn = (sentEn ? row.remarks_en : '').trim()
          const remarksBn = (sentBn ? row.remarks_bn : '').trim()
          if (!remarksEn && !remarksBn) {
            return reply({ error: 'remarks_en or remarks_bn is required' }, 400)
          }
          existing.remarks_en = remarksEn || remarksBn
          existing.remarks_bn = remarksBn || remarksEn
        }
        if (typeof row.category === 'string') existing.category = row.category
        if (typeof row.target_status === 'string') existing.target_status = row.target_status
        if (typeof row.template_id === 'string') existing.template_id = row.template_id
        if (Number.isFinite(row.priority)) existing.priority = row.priority
        if (typeof row.instruction_type === 'string') existing.instruction_type = row.instruction_type
        if (typeof row.instruction_text === 'string') existing.instruction_text = row.instruction_text
        if (typeof row.is_active === 'boolean') existing.is_active = row.is_active
        existing.updated_at = new Date().toISOString()
        // id rides along on `existing` from the select('*') above, but the
        // filter (.eq('id', remarkId)) is what targets the row — destructure
        // it out so the update payload only ever contains columns that are
        // actually meant to be set.
        const { id: _unusedId, ...updatePayload } = existing
        const { data, error } = await admin.from('validation_remarks').update(updatePayload)
          .eq('id', remarkId).select('*').maybeSingle()
        if (error) throw error
        if (!data) return reply({ error: 'Remark not found' }, 404)
        return reply({ ok: true, remark: data })
      }

      // Create: there's no existing row to merge against, so every field
      // genuinely needs a value now — this is the only place '' / 0 / true
      // defaults are still correct to apply for an absent field.
      if (typeof row.remarks_en !== 'string' && typeof row.remarks_bn !== 'string') {
        return reply({ error: 'remarks_en or remarks_bn is required' }, 400)
      }
      const remarksEn = (typeof row.remarks_en === 'string' ? row.remarks_en : '').trim()
      const remarksBn = (typeof row.remarks_bn === 'string' ? row.remarks_bn : '').trim()
      const payload = {
        // Falls back to the other language when one side is blank — mirrors
        // ConfigRemarksFragment.addRemark()'s `bn.ifEmpty { en }` / `en.ifEmpty { bn }`.
        source: row.source,
        remarks_en: remarksEn || remarksBn,
        remarks_bn: remarksBn || remarksEn,
        category: typeof row.category === 'string' ? row.category : '',
        target_status: typeof row.target_status === 'string' ? row.target_status : '',
        template_id: typeof row.template_id === 'string' ? row.template_id : '',
        priority: Number.isFinite(row.priority) ? row.priority : 0,
        instruction_type: typeof row.instruction_type === 'string' ? row.instruction_type : '',
        instruction_text: typeof row.instruction_text === 'string' ? row.instruction_text : '',
        is_active: typeof row.is_active === 'boolean' ? row.is_active : true,
        updated_at: new Date().toISOString(),
      }
      const { data, error } = await admin.from('validation_remarks')
        .insert({ id: crypto.randomUUID(), ...payload }).select('*').maybeSingle()
      if (error) throw error
      return reply({ ok: true, remark: data })
    }

    if (action === 'admin_delete_remark') {
      const profile = await firebaseProfile(identity)
      if (!profile.canAccessConfig) return reply({ error: 'Not authorized for remark config' }, 403)
      if (typeof body.id !== 'string' || !body.id.trim()) return reply({ error: 'id is required' }, 400)
      const { error } = await admin.from('validation_remarks').delete().eq('id', body.id.trim())
      if (error) throw error
      return reply({ ok: true })
    }

    // Used when an admin deletes a status (ConfigStatusesFragment): every remark
    // option whose target_status is the deleted status must either move to a
    // replacement status or be removed, per source (Worker and CC choose their
    // migration target independently — see confirmDelete()'s two spinners).
    // body.source is required (Worker and CC targets are migrated as two separate
    // calls, not one combined request) so this only ever touches one scope's rows.
    if (action === 'admin_migrate_status_remarks') {
      const profile = await firebaseProfile(identity)
      if (!profile.canAccessConfig) return reply({ error: 'Not authorized for remark config' }, 403)
      if (body.source !== 'CC' && body.source !== 'WORKER') {
        return reply({ error: 'Invalid remark source' }, 400)
      }
      if (typeof body.from_status !== 'string' || !body.from_status.trim()) {
        return reply({ error: 'from_status is required' }, 400)
      }
      const fromStatus = body.from_status.trim()
      if (typeof body.to_status === 'string' && body.to_status.trim()) {
        const { error } = await admin.from('validation_remarks')
          .update({ target_status: body.to_status.trim(), updated_at: new Date().toISOString() })
          .eq('source', body.source).eq('target_status', fromStatus)
        if (error) throw error
      } else {
        const { error } = await admin.from('validation_remarks').delete()
          .eq('source', body.source).eq('target_status', fromStatus)
        if (error) throw error
      }
      return reply({ ok: true })
    }

    // Best-effort mirror of Petty Cash claims into public.claims, written alongside
    // (never instead of) the app's existing Firebase write in ClaimsRepository.kt —
    // Firebase stays the source of truth for now. See this table's own migration
    // comment (202608260001_create_petty_cash_claims_tables.sql): "table structure
    // only, ahead of the actual data/write-flow migration off Firebase." This is
    // that mirror, added ahead of the actual cutover so the table stays populated
    // and can be spot-checked against Firebase before Firebase is ever removed.
    // *Name fields (branchName, employeeName, staffByName, ...) are intentionally
    // not accepted here — that same migration comment explains those are joins
    // against users/branches at read time on the Supabase side, not stored columns.
    if (action === 'claim_upsert') {
      const c = body.claim
      const str = (v: unknown) => typeof v === 'string' ? v : ''
      const num = (v: unknown) => typeof v === 'number' && Number.isFinite(v) ? v : 0
      const iso = (v: unknown) => typeof v === 'string' && v.trim() ? v : null
      if (!c || !str(c.id).trim() || !str(c.branch_id).trim() || !str(c.agent_system_id).trim()) {
        return reply({ error: 'claim id, branch_id and agent_system_id are required' }, 400)
      }
      const { error } = await admin.from('claims').upsert({
        id: str(c.id), claim_code: str(c.claim_code),
        branch_id: str(c.branch_id), agent_system_id: str(c.agent_system_id),
        type: str(c.type), category: str(c.category), purpose: str(c.purpose),
        consignment_id: str(c.consignment_id), store_id: str(c.store_id),
        pickup_count: num(c.pickup_count),
        // NOT NULL date column — falls back to today when the caller (currently
        // SupabaseClaimsWriter.kt) sends no placed_date, so this upsert can never
        // violate the NOT NULL constraint even if a caller omits the field.
        placed_date: (typeof c.placed_date === 'string' && c.placed_date.trim()) ? c.placed_date.trim() : new Date().toISOString().slice(0, 10),
        requested_amount: num(c.requested_amount), approved_amount: num(c.approved_amount), settled_amount: num(c.settled_amount),
        payment_method: str(c.payment_method), transaction_id: str(c.transaction_id),
        status: str(c.status), priority: str(c.priority),
        attachment_url: str(c.attachment_url), attachment_name: str(c.attachment_name),
        worker_uid: str(c.worker_uid), worker_role: str(c.worker_role),
        requested_at: iso(c.requested_at), approved_at: iso(c.approved_at), settled_at: iso(c.settled_at),
        created_at: iso(c.created_at), updated_at: iso(c.updated_at),
        staff_by_uid: str(c.staff_by_uid), staff_at: iso(c.staff_at), staff_comment: str(c.staff_comment),
        poc_approved_by_uid: str(c.poc_approved_by_uid), poc_comment: str(c.poc_comment),
        settle_in_process_by_uid: str(c.settle_in_process_by_uid), settle_in_process_at: iso(c.settle_in_process_at),
        settled_by_uid: str(c.settled_by_uid),
        rejected_by_uid: str(c.rejected_by_uid), rejected_at: iso(c.rejected_at), reject_reason: str(c.reject_reason),
      }, { onConflict: 'id' })
      if (error) throw error
      return reply({ ok: true })
    }

    // Best-effort mirror of Petty Cash deposits + wallet balance, same posture
    // as claim_upsert right above — alongside (never instead of) the existing
    // Firebase writes in PettyCashViewModel.kt's depositFund()/settleRequest(),
    // Firebase remains the source of truth. Columns verified 2026-08-30 against
    // a live information_schema.columns dump — see SupabasePettyCashWriter.kt's
    // toSupabaseJson() doc comment for the two things that dump caught
    // (entered_by_name isn't a real column; id is `uuid`, not text, so the
    // Kotlin side converts Firebase's push-id string via
    // UUID.nameUUIDFromBytes() before sending it here).
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
      await ensureAuthenticatedRoleClaim(identity.uid) // defensive: covers a user who writes before ever syncing
      if (row.assigned_to_system_id === authorProfile.systemId) {
        // Already upserted above; avoids a duplicate Firebase profile request.
      } else {
        const assignedProfile = await firebaseProfileForSystemId(row.assigned_to_system_id, identity)
        if (!assignedProfile) return reply({ error: 'Assigned user was not found' }, 400)
        await upsertUser(assignedProfile, assignedProfile.uid)
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
      // Best-effort catalog update — runs after the audit row is safely saved,
      // and never blocks or fails the write response.
      if (typeof row.remarks_bn === 'string') {
        await upsertRemarkLabel(savedRow.source, savedRow.remarks, row.remarks_bn)
      }
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
      return reply(await withBanglaLabels(data ?? []))
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
