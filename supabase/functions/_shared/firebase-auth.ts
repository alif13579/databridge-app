// Shared Firebase identity + profile reads. Every function trusts the same
// thing: the caller's Firebase ID token (verified against Firebase's JWKS),
// then reads ONLY from Firebase server-side — Android never supplies identity
// content, so a caller cannot impersonate another employee.

import { createRemoteJWKSet, jwtVerify } from 'npm:jose@5'
import { JWT } from 'npm:google-auth-library@9'

export const firebaseProjectId = Deno.env.get('FIREBASE_PROJECT_ID')
if (!firebaseProjectId) throw new Error('FIREBASE_PROJECT_ID is required')
export const firebaseDatabaseUrl = Deno.env.get('FIREBASE_DATABASE_URL')?.replace(/\/$/, '')
if (!firebaseDatabaseUrl) throw new Error('FIREBASE_DATABASE_URL is required')

const firebaseJwks = createRemoteJWKSet(
  new URL('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com'),
)

export async function firebaseIdentity(request: Request): Promise<{ uid: string; token: string }> {
  const token = request.headers.get('authorization')?.replace(/^Bearer\s+/i, '')
  if (!token) throw new Error('Missing Firebase ID token')
  let payload: { sub?: unknown }
  try {
    const result = await jwtVerify(token, firebaseJwks, {
      algorithms: ['RS256'], audience: firebaseProjectId,
      issuer: `https://securetoken.google.com/${firebaseProjectId}`,
    })
    payload = result.payload
  } catch (e) {
    throw new Error(`Firebase token verification failed: ${e instanceof Error ? e.message : String(e)}`)
  }
  if (typeof payload.sub !== 'string' || !payload.sub) throw new Error('Invalid Firebase subject')
  return { uid: payload.sub, token }
}

export type FirebaseProfile = {
  systemId: string; roleId: string; branchIds: string[]; canAccessCallCenter: boolean
  canAccessConfig: boolean; name: string; employeeId: string
  // POC report contact fields (Top Sheet header reads users.phone/designation
  // via fetchPocForBranch). Firebase sources: profile/phone (top-level) +
  // profile/company_info/designation — parsed in all three readers below so
  // upsertUser keeps them in sync like name/role.
  phone: string; designation: string
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
export async function firebaseProfile(identity: { uid: string; token: string }): Promise<FirebaseProfile> {
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
    phone: typeof profile?.phone === 'string' ? profile.phone.trim() : '',
    designation: typeof companyInfo?.designation === 'string' ? companyInfo.designation.trim() : '',
  }
}

/** Resolve a known system id only when a write needs to create its master row.
 *  Returns the assigned user's Firebase uid alongside their profile — callers must
 *  pass it through to upsertUser(), or that user's users.firebase_id row gets
 *  overwritten with NULL (see upsertUser's firebaseId param), silently breaking
 *  RLS's my_system_id()/my_branch_ids() for them (branch_id = any(my_branch_ids())
 *  and assigned_to_system_id = my_system_id() both resolve to NULL — reads return
 *  a genuinely empty [], not an error) until they get a fresh sync_profile call. */
export async function firebaseProfileForSystemId(systemId: string, identity: { uid: string; token: string }): Promise<(FirebaseProfile & { uid: string }) | null> {
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
    phone: typeof profile?.phone === 'string' ? profile.phone.trim() : '',
    designation: typeof info?.designation === 'string' ? info.designation.trim() : '',
    uid: index.uid,
  }
}

/** Reads an arbitrary user's Firebase profile (by uid) for the claims-drain
 *  backfill below. Same shape as firebaseProfile(), but the uid comes from
 *  the request body (a claim actor), not the verified token — callers must
 *  only ever pass uids collected from real claim rows, never free input that
 *  decides WHAT gets written (the profile content itself always comes from
 *  this server-side read, preserving upsertUser's no-client-identity rule).
 *  Returns null when the uid has no profile or no system_id (e.g. a deleted
 *  account) — the caller reports it instead of writing a bad row. */
export async function firebaseProfileForUid(uid: string, identity: { uid: string; token: string }): Promise<(FirebaseProfile & { uid: string }) | null> {
  const response = await fetch(`${firebaseDatabaseUrl}/users/${encodeURIComponent(uid)}/profile.json?auth=${encodeURIComponent(identity.token)}`)
  if (!response.ok) return null
  const profile = await response.json()
  const companyInfo = profile?.company_info
  const systemId = typeof companyInfo?.system_id === 'string' ? companyInfo.system_id.trim() : ''
  if (!systemId) return null
  let roleId = typeof companyInfo?.role_id === 'string' ? companyInfo.role_id.trim()
    : typeof companyInfo?.role === 'string' ? companyInfo.role.trim() : ''
  if (!roleId) {
    const roleResponse = await fetch(
      `${firebaseDatabaseUrl}/users/${encodeURIComponent(uid)}/role.json?auth=${encodeURIComponent(identity.token)}`,
    )
    if (roleResponse.ok) {
      const legacyRole = await roleResponse.json()
      if (typeof legacyRole === 'string') roleId = legacyRole.trim()
    }
  }
  return {
    systemId,
    roleId,
    branchIds: readBranchIds(companyInfo),
    canAccessCallCenter: false,
    canAccessConfig: false,
    name: typeof profile?.name === 'string' ? profile.name.trim() : '',
    employeeId: typeof companyInfo?.employee_id === 'string' ? companyInfo.employee_id.trim() : '',
    phone: typeof profile?.phone === 'string' ? profile.phone.trim() : '',
    designation: typeof companyInfo?.designation === 'string' ? companyInfo.designation.trim() : '',
    uid,
  }
}

export type ServiceAccount = { client_email: string; private_key: string; project_id?: string }

/** Mints a short-lived Google OAuth2 access token for the given scope from the Firebase
 *  service account (the same key already used for FCM — a project's default Firebase
 *  Admin SDK service account has these permissions by default, no separate credential
 *  needed). */
export async function googleAccessToken(scope: string): Promise<string> {
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
export async function ensureAuthenticatedRoleClaim(uid: string): Promise<void> {
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

export async function firebaseRead(identity: { uid: string; token: string }, path: string): Promise<unknown> {
  const response = await fetch(`${firebaseDatabaseUrl}/${path}.json?auth=${encodeURIComponent(identity.token)}`)
  return response.ok ? response.json() : null
}
