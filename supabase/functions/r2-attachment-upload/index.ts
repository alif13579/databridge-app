import { createRemoteJWKSet, jwtVerify } from 'npm:jose@5'

/**
 * Presigned-URL issuer for Petty Cash request attachments (Cloudflare R2).
 *
 * Why this exists as its own function, separate from validations:
 * this only ever hands out short-lived R2 URLs — it never touches
 * Postgres/validations data, so it doesn't need the Supabase service role
 * key or the validations table at all. Keeping it isolated means a
 * bug here can't touch remark data, and vice versa.
 *
 * Two actions, both requiring a valid Firebase ID token:
 *   - upload (default, body has no "action" or action: "upload"): Android
 *     sends { file_name, content_type, size_bytes } → this rejects anything
 *     that isn't an image or a PDF or is over 5 MB, then returns a
 *     presigned PUT URL for a fresh object key under the caller's uid.
 *   - download (action: "download"): Android sends { object_key } for an
 *     attachment it already knows about (i.e. it read a PettyCashRequest
 *     that has this key — Firebase's own read rules are what actually gate
 *     who can see which request, this function does no extra per-request
 *     role check on top of "is this a valid Firebase user") → this returns
 *     a presigned GET URL for that exact key.
 *
 * The bucket is private — there is no public base URL. Every read goes
 * through a presigned GET, the same way every write goes through a
 * presigned PUT. The R2 secret access key lives ONLY in this function's
 * environment secrets (set via `supabase secrets set`) — it is never sent
 * to, or bundled in, the Android app. Each presigned URL is scoped to one
 * specific object key and expires in minutes.
 *
 * R2 is S3-compatible, so this hand-rolls AWS Signature Version 4 for
 * presigned URLs rather than pulling in the full AWS SDK for two calls.
 */

const corsHeaders = {
  'Access-Control-Allow-Headers': 'authorization, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Content-Type': 'application/json',
}

const firebaseProjectId = Deno.env.get('FIREBASE_PROJECT_ID')
if (!firebaseProjectId) throw new Error('FIREBASE_PROJECT_ID is required')

const R2_ACCOUNT_ID = Deno.env.get('R2_ACCOUNT_ID')
const R2_ACCESS_KEY_ID = Deno.env.get('R2_ACCESS_KEY_ID')
const R2_SECRET_ACCESS_KEY = Deno.env.get('R2_SECRET_ACCESS_KEY')
const R2_BUCKET_NAME = Deno.env.get('R2_BUCKET_NAME')

for (const [name, value] of Object.entries({
  R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET_NAME,
})) {
  if (!value) throw new Error(`${name} is required`)
}

const firebaseJwks = createRemoteJWKSet(
  new URL('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com'),
)

// Requester-facing limits — kept in one place so the Android side and this
// function can be checked against each other instead of drifting apart.
const MAX_FILE_BYTES = 5 * 1024 * 1024 // 5 MB
const ALLOWED_CONTENT_TYPES = new Set([
  // "all formats" of image, not just jpg/png — matches what BitmapFactory /
  // Android's image picker can plausibly hand back.
  'image/jpeg', 'image/png', 'image/webp', 'image/gif', 'image/heic', 'image/heif', 'image/bmp',
  'application/pdf',
])
// Every attachment object key lives under this prefix — used both to build
// new keys on upload and to scope-check a key handed back for download (see
// handleDownload's own comment on what that check does and doesn't cover).
const ATTACHMENT_KEY_PREFIX = 'petty_cash_attachments/'

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

/** Extension purely for a friendlier stored object key — never trusted for content-type. */
function extensionFor(contentType: string): string {
  switch (contentType) {
    case 'image/jpeg': return 'jpg'
    case 'image/png': return 'png'
    case 'image/webp': return 'webp'
    case 'image/gif': return 'gif'
    case 'image/heic': return 'heic'
    case 'image/heif': return 'heif'
    case 'image/bmp': return 'bmp'
    case 'application/pdf': return 'pdf'
    default: return 'bin'
  }
}

/** HMAC-SHA256 via Web Crypto (Deno-native — avoids node:crypto's npm-compat cold start). */
async function hmac(key: Uint8Array | string, data: string): Promise<Uint8Array> {
  const keyBytes = typeof key === 'string' ? new TextEncoder().encode(key) : key
  const cryptoKey = await crypto.subtle.importKey(
    'raw', keyBytes, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  )
  const signature = await crypto.subtle.sign('HMAC', cryptoKey, new TextEncoder().encode(data))
  return new Uint8Array(signature)
}

function hex(bytes: Uint8Array): string {
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, '0')).join('')
}

/**
 * Builds a presigned SigV4 URL for a single request to R2 — PUT for an
 * upload, GET for a download. R2 supports AWS SigV4 query-parameter
 * presigning identically to S3 (region is always "auto" for R2). Expiry is
 * deliberately short in both cases — these URLs are meant to be used
 * within seconds/minutes of being issued, not stored or replayed.
 */
async function presignR2Url(method: 'PUT' | 'GET', objectKey: string, expirySeconds: number): Promise<string> {
  const region = 'auto'
  const service = 's3'
  const host = `${R2_ACCOUNT_ID}.r2.cloudflarestorage.com`
  const now = new Date()
  const amzDate = now.toISOString().replace(/[:-]|\.\d{3}/g, '') // e.g. 20260824T120000Z
  const dateStamp = amzDate.slice(0, 8)

  const credentialScope = `${dateStamp}/${region}/${service}/aws4_request`
  const signedHeaders = 'host'
  const canonicalQueryParams: [string, string][] = [
    ['X-Amz-Algorithm', 'AWS4-HMAC-SHA256'],
    ['X-Amz-Credential', `${R2_ACCESS_KEY_ID}/${credentialScope}`],
    ['X-Amz-Date', amzDate],
    ['X-Amz-Expires', String(expirySeconds)],
    ['X-Amz-SignedHeaders', signedHeaders],
  ]
  const canonicalQueryString = canonicalQueryParams
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join('&')

  const canonicalUri = `/${R2_BUCKET_NAME}/${objectKey.split('/').map(encodeURIComponent).join('/')}`
  const canonicalHeaders = `host:${host}\n`
  const payloadHash = 'UNSIGNED-PAYLOAD' // presigned PUT/GET: body isn't known/hashed ahead of time

  const canonicalRequest = [
    method, canonicalUri, canonicalQueryString, canonicalHeaders, signedHeaders, payloadHash,
  ].join('\n')

  const canonicalRequestHash = hex(new Uint8Array(
    await crypto.subtle.digest('SHA-256', new TextEncoder().encode(canonicalRequest)),
  ))
  const stringToSign = [
    'AWS4-HMAC-SHA256', amzDate, credentialScope, canonicalRequestHash,
  ].join('\n')

  const kDate = await hmac(`AWS4${R2_SECRET_ACCESS_KEY}`, dateStamp)
  const kRegion = await hmac(kDate, region)
  const kService = await hmac(kRegion, service)
  const kSigning = await hmac(kService, 'aws4_request')
  const signature = hex(await hmac(kSigning, stringToSign))

  return `https://${host}${canonicalUri}?${canonicalQueryString}&X-Amz-Signature=${signature}`
}

Deno.serve(async (request) => {
  if (request.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders })
  if (request.method !== 'POST') return reply({ error: 'Method not allowed' }, 405)
  try {
    const identity = await firebaseIdentity(request)
    const body = await request.json()
    const action = typeof body.action === 'string' ? body.action : 'upload'

    if (action === 'download') return await handleDownload(identity, body)
    if (action === 'upload') return await handleUpload(identity, body)
    return reply({ error: `Unknown action: ${action}` }, 400)
  } catch (error) {
    console.error(error)
    return reply({ error: 'Unauthorized or failed request' }, 401)
  }
})

async function handleUpload(identity: { uid: string }, body: Record<string, unknown>): Promise<Response> {
  const contentType = typeof body.content_type === 'string' ? body.content_type.toLowerCase().trim() : ''
  const sizeBytes = Number(body.size_bytes)
  const originalFileName = typeof body.file_name === 'string' ? body.file_name.trim() : ''

  if (!ALLOWED_CONTENT_TYPES.has(contentType)) {
    return reply({ error: 'Only images or PDF files are allowed' }, 400)
  }
  if (!Number.isFinite(sizeBytes) || sizeBytes <= 0) {
    return reply({ error: 'size_bytes is required' }, 400)
  }
  if (sizeBytes > MAX_FILE_BYTES) {
    return reply({ error: `File exceeds the ${MAX_FILE_BYTES / (1024 * 1024)}MB limit` }, 400)
  }

  // Object key: per-user folder + random component, so one requester can
  // never guess or overwrite another's attachment key even though the
  // bucket itself isn't publicly listable.
  const randomComponent = hex(crypto.getRandomValues(new Uint8Array(8)))
  const objectKey = `${ATTACHMENT_KEY_PREFIX}${identity.uid}/${Date.now()}_${randomComponent}.${extensionFor(contentType)}`

  const uploadUrl = await presignR2Url('PUT', objectKey, 300) // 5-minute window

  console.info(`r2-attachment-upload ok: uid=${identity.uid}, key=${objectKey}, type=${contentType}, size=${sizeBytes}`)
  return reply({
    ok: true,
    upload_url: uploadUrl,
    object_key: objectKey,
    original_file_name: originalFileName,
    content_type: contentType,
  })
}

async function handleDownload(identity: { uid: string }, body: Record<string, unknown>): Promise<Response> {
  const objectKey = typeof body.object_key === 'string' ? body.object_key : ''

  // Scope guard, not a per-request role check: this only confirms the key
  // is actually one of *this feature's* attachment keys (right prefix, no
  // path-traversal component) — it does not check whether `identity.uid`
  // is allowed to see the specific Petty Cash request this key belongs to.
  // That authorization already happened at the point the app read this key
  // out of Firebase in the first place: Firebase's own read rules are what
  // decide which requests (and thus which attachment keys) a given user
  // can see. If a key clears this guard, the caller already had legitimate
  // read access to the request it's attached to.
  if (!objectKey.startsWith(ATTACHMENT_KEY_PREFIX) || objectKey.includes('..')) {
    return reply({ error: 'Invalid object_key' }, 400)
  }

  const downloadUrl = await presignR2Url('GET', objectKey, 300) // 5-minute window — enough for the app to open/hand off the URL

  console.info(`r2-attachment-download ok: uid=${identity.uid}, key=${objectKey}`)
  return reply({ ok: true, download_url: downloadUrl, object_key: objectKey })
}
