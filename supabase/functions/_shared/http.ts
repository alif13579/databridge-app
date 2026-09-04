// Shared HTTP plumbing for all DataBridge Edge Functions.
// Every function verifies the Firebase ID token itself (verify_jwt = false
// in config.toml), so they all share this CORS envelope + JSON error shape.

export const corsHeaders = {
  // Allow-Origin is required for browser clients (the Chrome extension's
  // content scripts fetch these functions from arbitrary page origins, e.g.
  // hermes.pathaointernal.com — without it the preflight has no
  // Access-Control-Allow-Origin and the browser blocks the call entirely).
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Content-Type': 'application/json',
}

export function reply(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: corsHeaders })
}

/** Structured error logger. Every call emits a single-line JSON to stdout so
 *  Supabase Dashboard → Functions → Logs shows filterable, copy-pasteable entries.
 *  Share the copied line with Claude for diagnosis. */
export function errLog(action: string, reason: string, ctx: Record<string, unknown> = {}) {
  console.error(JSON.stringify({ ts: new Date().toISOString(), action, reason, ...ctx }))
}

/** Shared request guard: CORS preflight + POST-only. Returns a Response to
 *  send immediately, or null to continue into the handler. */
export function guardRequest(request: Request): Response | null {
  if (request.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders })
  if (request.method !== 'POST') return reply({ error: 'Method not allowed' }, 405)
  return null
}

/** Shared catch-block shape: logs a structured line and returns the actual
 *  reason in the body (not just a generic message) — the Android client logs
 *  this response text verbatim, so this is the fastest path to diagnosis
 *  without needing dashboard access. */
export function unhandled(
  action: string | undefined,
  identity: { uid: string; token: string } | undefined,
  error: unknown,
) {
  const msg = error instanceof Error ? error.message
    : (error && typeof error === 'object')
      ? JSON.stringify(error)
      : String(error)
  errLog(action ?? 'unknown', 'unhandled_exception', {
    uid: identity?.uid,
    err: error instanceof Error ? { msg: error.message, stack: error.stack?.slice(0, 500) } : msg,
  })
  return reply({ error: 'Unauthorized or failed request', reason: msg, action: action ?? 'unknown' }, 401)
}
