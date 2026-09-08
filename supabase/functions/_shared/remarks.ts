// Remark-specific helpers: Bangla label catalog lookups + remark push.
// Only imported by the validations function (plus its remark-validations
// compat copy — same code).

import { admin } from './supabase.ts'
import {
  firebaseProjectId,
  firebaseRead,
  googleAccessToken,
  type ServiceAccount,
} from './firebase-auth.ts'

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
export async function upsertRemarkLabel(source: string, remarksEn: string, remarksBn: string) {
  const en = remarksEn.trim()
  const bn = remarksBn.trim()
  if (!en || !bn) return
  const { error } = await admin.from('validation_remarks').upsert({
    source, remarks_en: en, remarks_bn: bn, updated_at: new Date().toISOString(),
  }, { onConflict: 'source,remarks_en', ignoreDuplicates: false })
  // A failed catalog upsert must never block the actual remark write.
  if (error) console.error('upsertRemarkLabel failed', error)
}

/** Looks up the Bangla label for a saved remark from the validation_remarks
 *  helper table (populated by upsertRemarkLabel() on every write that included
 *  a Bangla label), scoped to the same source ('CC'/'WORKER') the remark was
 *  saved under — remarks_en alone isn't unique once Worker and CC options
 *  share this table (see migration 202608250002), so a lookup must match on
 *  both to avoid picking up the wrong scope's Bangla for the same English
 *  text. Falls back to the raw English text when there's no match — a
 *  free-typed note, or a remark saved before this table existed. */
export async function resolveRemarkBn(source: string, remarksText: string): Promise<string> {
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
export async function withBanglaLabels<T extends { remarks: string; source: string }>(rows: T[]): Promise<(T & { remarks_bn: string })[]> {
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

/** Sends one data-only FCM to each token with the given scope. Returns accepted count.
 *  UNREGISTERED tokens are deleted outright; all other failures are logged only. */
async function sendToTokens(tokens: string[], scope: string, accessToken: string, projectId: string | undefined, title: string, body: string, consignment: string): Promise<number> {
  if (!tokens.length) return 0
  const outcomes = await Promise.all(tokens.map(async (token) => {
    const response = await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId!)}/messages:send`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: {
        token,
        // Data-only (no `notification` block): Android always calls
        // onMessageReceived() regardless of foreground/background state.
        data: {
          type: 'remark', title, body,
          consignment_id: consignment,
          scope,
          notif_parcel_id: consignment,
          notif_scope: scope,
        },
        android: { priority: 'high' },
      } }),
    })
    if (response.ok) return true
    const text = await response.text()
    let isUnregistered = false
    try {
      const parsed = JSON.parse(text)
      const details = parsed?.error?.details
      isUnregistered = Array.isArray(details) && details.some((d: unknown) =>
        (d as { errorCode?: unknown })?.errorCode === 'UNREGISTERED')
    } catch { /* non-JSON body — logged, non-deleting failure */ }
    if (isUnregistered) {
      const { error: deleteError } = await admin.from('fcm_device_tokens').delete().eq('token', token)
      if (deleteError) console.error(`Failed to delete unregistered token: ${deleteError.message}`)
      else console.info(`Deleted unregistered FCM token (${token.slice(0, 12)}...)`)
    } else {
      console.error(`FCM send failed (${response.status}): ${text}`)
    }
    return false
  }))
  return outcomes.filter(Boolean).length
}

export async function sendRemarkPush(row: { consignment: string; branch_id: string; assigned_to_system_id: string; author_system_id: string; remarks_status: string; remarks: string; source: string }, identity: { uid: string; token: string }) {
  // A failed or not-yet-configured push must never prevent the audit record from saving.
  // Return a deliberately non-sensitive diagnostic so the Android sender can show exactly
  // why a saved remark did not produce a notification. The function's server log retains
  // the same outcome for cases where the sender device is no longer available.
  const serviceAccountJson = Deno.env.get('FCM_SERVICE_ACCOUNT_JSON')
  if (!serviceAccountJson) {
    console.error(`remark_push skipped: reason=fcm_service_account_missing consignment=${row.consignment}`)
    return { recipient_scope: row.source === 'WORKER' ? 'cc' : 'worker', matched_devices: 0, accepted: 0, reason: 'fcm_service_account_missing' }
  }
  try {
    const serviceAccount = JSON.parse(serviceAccountJson) as ServiceAccount
    if (!serviceAccount.client_email || !serviceAccount.private_key) throw new Error('Invalid FCM service account')

    // ┌──────────────────── COMPATIBILITY INVARIANT ────────────────────┐
    // │ CC -> Worker is known working production behavior. Keep this     │
    // │ exact system_id-only recipient path isolated from Worker -> CC.  │
    // │ Do NOT add branch/permission filters here: a worker is targeted  │
    // │ by the consignment's assigned_to_system_id only.                 │
    // └─────────────────────────────────────────────────────────────────┘
    // Worker -> CC deliberately uses the validation row's canonical run branch,
    // because only CC devices with access to that branch must receive the event.
    // `source` is the explicit, validated direction of the remark. It is safer than
    // inferring direction from IDs: a CC agent can be assigned a parcel too.
    const fromWorker = row.source === 'WORKER'
    if (fromWorker) {
      // Unchanged path: all CC devices with access to this branch (scope=cc).
      const { data: devices, error: deviceError } = await admin.from('fcm_device_tokens').select('token')
        .eq('can_access_call_center', true).overlaps('branch_ids', [row.branch_id])
      if (deviceError) throw deviceError
      const matchedDevices = devices?.length ?? 0
      if (!matchedDevices) {
        console.warn(`remark_push skipped: reason=no_matching_device_token consignment=${row.consignment} recipient=cc branch=${row.branch_id} can_access_call_center=true`)
        return { recipient_scope: 'cc', matched_devices: 0, accepted: 0, reason: 'no_matching_device_token' }
      }
      const accessToken = await googleAccessToken('https://www.googleapis.com/auth/firebase.messaging')
      const { title, body } = await notificationDetails(row, identity)
      const projectId = serviceAccount.project_id || firebaseProjectId
      const accepted = await sendToTokens((devices ?? []).map((d) => (d as { token: string }).token), 'cc', accessToken, projectId, title, body, row.consignment)
      const reason = accepted === matchedDevices ? 'accepted_by_fcm' : 'fcm_rejected_some_devices'
      console.info(`remark_push result: consignment=${row.consignment} scope=cc matched=${matchedDevices} accepted=${accepted} reason=${reason}`)
      return { recipient_scope: 'cc', matched_devices: matchedDevices, accepted, reason }
    }
    // CC source: TWO pushes (worker path unchanged + new CC branch fan-out).
    // Before: only the assigned worker got scope=worker, other CC agents got
    // nothing (silent card/status). Now: worker keeps scope=worker, same-branch
    // CC devices (excluding the author's own devices) get scope=cc.
    // ┌──────────────────── COMPATIBILITY INVARIANT ────────────────────┐
    // │ CC -> Worker block below is known working production behavior.   │
    // │ Keep its exact system_id-only recipient query unchanged.         │
    // └─────────────────────────────────────────────────────────────────┘
    const { data: workerDevices, error: workerError } = await admin.from('fcm_device_tokens').select('token')
      .eq('system_id', row.assigned_to_system_id)
    if (workerError) throw workerError
    // Same-branch CC devices, excluding the author's own devices (no self-push).
    const { data: ccDevices, error: ccError } = await admin.from('fcm_device_tokens').select('token')
      .eq('can_access_call_center', true).overlaps('branch_ids', [row.branch_id])
      .neq('firebase_uid', identity.uid)
    if (ccError) throw ccError
    const workerTokens = (workerDevices ?? []).map((d) => (d as { token: string }).token).filter(Boolean)
    const ccTokens = (ccDevices ?? []).map((d) => (d as { token: string }).token).filter(Boolean)
      .filter((t) => !workerTokens.includes(t)) // dedupe: a worker with CC access gets worker scope only
    const matchedDevices = workerTokens.length + ccTokens.length
    if (!matchedDevices) {
      console.warn(`remark_push skipped: reason=no_matching_device_token consignment=${row.consignment} recipient=worker system_id=${row.assigned_to_system_id} + cc branch=${row.branch_id}`)
      return { recipient_scope: 'worker+cc', matched_devices: 0, accepted: 0, reason: 'no_matching_device_token' }
    }

    const accessToken = await googleAccessToken('https://www.googleapis.com/auth/firebase.messaging')

    const { title, body } = await notificationDetails(row, identity)
    const projectId = serviceAccount.project_id || firebaseProjectId
    const workerAccepted = await sendToTokens(workerTokens, 'worker', accessToken, projectId, title, body, row.consignment)
    const ccAccepted = await sendToTokens(ccTokens, 'cc', accessToken, projectId, title, body, row.consignment)
    const accepted = workerAccepted + ccAccepted
    const reason = accepted === matchedDevices ? 'accepted_by_fcm' : 'fcm_rejected_some_devices'
    console.info(`remark_push result: consignment=${row.consignment} scope=worker+cc matched=${matchedDevices} (worker=${workerTokens.length} cc=${ccTokens.length}) accepted=${accepted} reason=${reason}`)
    return { recipient_scope: 'worker+cc', matched_devices: matchedDevices, accepted, reason,
      worker_matched: workerTokens.length, worker_accepted: workerAccepted,
      cc_matched: ccTokens.length, cc_accepted: ccAccepted }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    console.error(`remark_push failed: consignment=${row.consignment} reason=${message}`)
    return { recipient_scope: row.source === 'WORKER' ? 'cc' : 'worker', matched_devices: 0, accepted: 0, reason: `push_exception: ${message.slice(0, 160)}` }
  }
}
