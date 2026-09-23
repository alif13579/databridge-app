// Shared claim-status push. Notifies a claim's REQUESTER when someone else
// moves their request (verified / approved / settle-in-process / settled /
// rejected / sent-back / cancelled) — audit finding #7 (remark-push existed,
// claim-push didn't; requesters only learned by opening the app).
//
// Same posture as sendRemarkPush in remarks.ts: data-only FCM (no
// `notification` block, so the app always gets onMessageReceived), best-
// effort (a push failure never fails the claim write), UNREGISTERED tokens
// pruned. Recipient is the requester's system_id via fcm_device_tokens.

import { admin } from './supabase.ts'
import { firebaseProjectId, googleAccessToken } from './firebase-auth.ts'

type ServiceAccount = {
  project_id?: string
  client_email?: string
  private_key?: string
}

export type ClaimPushEvent =
  | 'verified' | 'approved' | 'settle_in_process' | 'settled'
  | 'rejected' | 'sent_back' | 'cancelled'

const EVENT_TEXT: Record<ClaimPushEvent, { title: string; body: string }> = {
  verified: { title: '✓ Claim verified', body: 'Staff verified your request — now with the cash POC.' },
  approved: { title: '✓ Claim approved', body: 'Cash POC approved your request — accounts will settle it.' },
  settle_in_process: { title: '⏳ Settlement started', body: 'Accounts queued your request for cash handover.' },
  settled: { title: '💰 Claim settled', body: 'Your claim money has been handed over.' },
  rejected: { title: '✕ Claim rejected', body: 'Your request was rejected — open it to see why and resubmit.' },
  sent_back: { title: '↩ Claim sent back', body: 'Your request was sent back a stage — open it to see what is needed.' },
  cancelled: { title: '🚫 Claim cancelled', body: 'Your request was cancelled.' },
}

export async function sendClaimPush(args: {
  claimId: string
  claimCode: string
  branchId: string
  requesterSystemId: string
  event: ClaimPushEvent
  amount?: number
}) {
  const copy = EVENT_TEXT[args.event]
  const amountSuffix = args.amount && args.amount > 0 ? ` (৳${args.amount})` : ''
  return deliverClaimPush({
    requesterSystemId: args.requesterSystemId,
    title: `${copy.title} — ${args.claimCode || args.claimId}`,
    body: `${copy.body}${amountSuffix}`,
    data: {
      claim_id: args.claimId, claim_code: args.claimCode, branch_id: args.branchId,
    },
    logTag: `claim=${args.claimId} event=${args.event}`,
  })
}

/** Bulk-import settled push: one notification per agent per batch (a 40-row
 *  batch must not buzz 40 times). A single-claim batch reads exactly like a
 *  normal settle; multi-claim batches summarize with the total, and tap opens
 *  the batch's latest claim (type='claim' needs a concrete target — the app
 *  needs no change). Best-effort like sendClaimPush. */
export async function sendBulkSettledPush(args: {
  branchId: string
  requesterSystemId: string
  claims: Array<{ id: string; code: string; amount: number }>
}) {
  if (args.claims.length === 0) return { matched_devices: 0, accepted: 0, reason: 'empty_batch' }
  if (args.claims.length === 1) {
    const c = args.claims[0]
    return sendClaimPush({
      claimId: c.id, claimCode: c.code, branchId: args.branchId,
      requesterSystemId: args.requesterSystemId, event: 'settled', amount: c.amount,
    })
  }
  const total = args.claims.reduce((s, c) => s + (Number.isFinite(c.amount) ? c.amount : 0), 0)
  const latest = args.claims[args.claims.length - 1]
  return deliverClaimPush({
    requesterSystemId: args.requesterSystemId,
    title: `💰 ${args.claims.length} claims settled — ${latest.code}`,
    body: `৳${total.toLocaleString('en-US')} across ${args.claims.length} requests — tap to view`,
    data: {
      claim_id: latest.id, claim_code: latest.code, branch_id: args.branchId,
    },
    logTag: `bulk batch=${args.claims.length} agent=${args.requesterSystemId}`,
  })
}

async function deliverClaimPush(args: {
  requesterSystemId: string
  title: string
  body: string
  data: Record<string, string>
  logTag: string
}) {
  const base = { requester: args.requesterSystemId }
  const serviceAccountJson = Deno.env.get('FCM_SERVICE_ACCOUNT_JSON')
  if (!serviceAccountJson) {
    console.error(`claim_push skipped: reason=fcm_service_account_missing`, base)
    return { matched_devices: 0, accepted: 0, reason: 'fcm_service_account_missing' }
  }
  try {
    const serviceAccount = JSON.parse(serviceAccountJson) as ServiceAccount
    if (!serviceAccount.client_email || !serviceAccount.private_key) throw new Error('Invalid FCM service account')
    const { data: devices, error: deviceError } = await admin.from('fcm_device_tokens')
      .select('token').eq('system_id', args.requesterSystemId)
    if (deviceError) throw deviceError
    const matchedDevices = devices?.length ?? 0
    if (!matchedDevices) {
      console.warn(`claim_push skipped: reason=no_matching_device_token requester=${args.requesterSystemId}`, base)
      return { matched_devices: 0, accepted: 0, reason: 'no_matching_device_token' }
    }
    const accessToken = await googleAccessToken('https://www.googleapis.com/auth/firebase.messaging')
    const projectId = serviceAccount.project_id || firebaseProjectId
    const outcomes = await Promise.all(devices.map(async ({ token }) => {
      const response = await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId!)}/messages:send`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: {
          token,
          // Data-only — see remarks.ts for why no `notification` block.
          data: {
            type: 'claim', title: args.title, body: args.body,
            ...args.data,
            scope: 'claim',
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
      } catch { /* non-JSON — logged, non-deleting failure */ }
      if (isUnregistered) {
        const { error: deleteError } = await admin.from('fcm_device_tokens').delete().eq('token', token)
        if (deleteError) console.error(`Failed to delete unregistered token: ${deleteError.message}`)
        else console.info(`Deleted unregistered FCM token (${token.slice(0, 12)}...)`)
      } else {
        console.error(`FCM send failed (${response.status}): ${text}`)
      }
      return false
    }))
    const accepted = outcomes.filter(Boolean).length
    const reason = accepted === matchedDevices ? 'accepted_by_fcm' : 'fcm_rejected_some_devices'
    console.info(`claim_push result: ${args.logTag} matched=${matchedDevices} accepted=${accepted} reason=${reason}`)
    return { matched_devices: matchedDevices, accepted, reason }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    console.error(`claim_push failed: ${args.logTag} reason=${message}`)
    return { matched_devices: 0, accepted: 0, reason: message.slice(0, 120) }
  }
}
