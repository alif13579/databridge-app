// Firebase backup-mirror writes (Supabase-first architecture).
//
// Directory/user data lives authoritatively in Supabase now; Firebase RTDB
// keeps a best-effort backup copy so a future Firebase removal breaks
// nothing. These helpers write through the Firebase Admin service account
// (same key as FCM), which bypasses RTDB security rules entirely.
//
// Mirror writes must NEVER fail the Supabase write they back up — every
// caller wraps them in try/catch and only logs. A missed mirror heals on
// the next save of the same record (mirrors are full-state overwrites,
// never deltas).

import { firebaseDatabaseUrl, googleAccessToken } from './firebase-auth.ts'

async function firebaseRequest(
  method: 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  body?: unknown,
): Promise<void> {
  const token = await googleAccessToken('https://www.googleapis.com/auth/firebase.database')
  const url = `${firebaseDatabaseUrl}/${path}.json?access_token=${encodeURIComponent(token)}`
  const response = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!response.ok) {
    throw new Error(`Firebase ${method} ${path} HTTP ${response.status}: ${(await response.text()).slice(0, 200)}`)
  }
}

/** Full-state overwrite of one node (null values delete that child). */
export async function firebaseWrite(path: string, value: unknown): Promise<void> {
  await firebaseRequest('PUT', path, value)
}

/** Multi-path update from the DB root: { 'a/b/c': value, ... } — one round
 *  trip for a whole mirror record. A null value deletes that child. */
export async function firebaseUpdatePaths(paths: Record<string, unknown>): Promise<void> {
  const clean: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(paths)) {
    const key = k.replace(/^\/+/, '')
    if (key) clean[key] = v
  }
  if (Object.keys(clean).length === 0) return
  await firebaseRequest('PATCH', '', clean)
}

/** Removes one node. Missing nodes are not an error (idempotent). */
export async function firebaseDelete(path: string): Promise<void> {
  try {
    await firebaseRequest('DELETE', path)
  } catch (e) {
    // RTDB DELETE on a missing path still returns 200, so a throw here is a
    // real transport/auth problem — rethrow for the caller to log, not swallow.
    throw e
  }
}
