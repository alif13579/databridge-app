import { createRemoteJWKSet, jwtVerify } from 'npm:jose@5'

/**
 * Presigned-upload issuer for Petty Cash request attachments (Cloudflare R2).
 *
 * Why this exists as its own function, separate from remark-validations:
 * this only ever hands out a short-lived upload URL — it never touches
 * Postgres/validations data, so it doesn't need the Supabase service role
 * key or the remark-validations table at all. Keeping it isolated means a
 * bug here can't touch remark data, and vice versa.
 *
 * Flow: Android sends { file_name, content_type, size_bytes } with its
 * Firebase ID token → this function verifies the token (same JWKS check as
 * remark-validations), rejects anything that isn't an image or a PDF or is
 * over 5 MB, then returns a presigned R2 PUT URL the app can upload directly
 * to. The R2 secret access key lives ONLY in this function's environment
 * secrets (set via `supabase secrets set`) — it is never sent to, or
 * bundled in, the Android app. A client can at most get one presigned URL
 * for one specific object key, valid for a few minutes.
 *
 * R2 is S3-compatible, so this hand-rolls AWS Signature Version 4 for a
 * presigned URL rather than pulling in the full AWS SDK for one call.
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
// Optional: a public R2.dev or custom domain the app can GET the file back
// from after upload. If unset, the function still returns the object key
// so the caller can construct/store a URL once one is configured.
const R2_PUBLIC_BASE_URL = Deno.env.get('R2_PUBLIC_BASE_URL')?.replace(/\/$/, '') ?? ''

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
 * Builds a presigned SigV4 URL for a single PUT to R2. R2 supports AWS
 * SigV4 query-parameter presigning identically to S3 (region is always
 * "auto" for R2). Expiry is deliberately short — this URL is meant to be
 * used within seconds by the requesting device, not stored or replayed.
 */
async function presignR2PutUrl(objectKey: string, contentType: string, expirySeconds: number): Promise<string> {
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
  const payloadHash = 'UNSIGNED-PAYLOAD' // presigned PUT: body isn't known/hashed ahead of time

  const canonicalRequest = [
    'PUT', canonicalUri, canonicalQueryString, canonicalHeaders, signedHeaders, payloadHash,
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
    const objectKey = `petty_cash_attachments/${identity.uid}/${Date.now()}_${randomComponent}.${extensionFor(contentType)}`

    const uploadUrl = await presignR2PutUrl(objectKey, contentType, 300) // 5-minute window
    const publicUrl = R2_PUBLIC_BASE_URL ? `${R2_PUBLIC_BASE_URL}/${objectKey}` : ''

    console.info(`r2-attachment-upload ok: uid=${identity.uid}, key=${objectKey}, type=${contentType}, size=${sizeBytes}`)
    return reply({
      ok: true,
      upload_url: uploadUrl,
      object_key: objectKey,
      public_url: publicUrl,
      original_file_name: originalFileName,
      content_type: contentType,
    })
  } catch (error) {
    console.error(error)
    return reply({ error: 'Unauthorized or failed request' }, 401)
  }
})
