// validations — Call Center / Worker REMARK flows only.
// Actions: write, sync_run_status, report, admin_list_remarks,
// admin_upsert_remark, admin_delete_remark, admin_migrate_status_remarks.
//
// NOTE: this is the canonical slug (renamed from remark-validations).
// supabase/functions/remark-validations/index.ts is a byte-identical compat
// copy kept deployed so old APKs/extension builds keep working — edit BOTH
// files together until the old slug is retired.
//
// Every other domain moved to its own function (see supabase/functions/):
// user-sync (profile/push-token/backfill_user), directory (branches/stores),
// claims (claim_upsert), petty-cash (deposits/wallet), check-ins. Shared
// helpers live in ../_shared/ so a fix there rolls out to all functions.

import { admin } from '../_shared/supabase.ts'
import { errLog, guardRequest, reply, unhandled } from '../_shared/http.ts'
import {
  ensureAuthenticatedRoleClaim,
  firebaseIdentity,
  firebaseProfile,
  firebaseRead,
} from '../_shared/firebase-auth.ts'
import { requireUsersRow } from '../_shared/users.ts'
import {
  sendRemarkPush,
  upsertRemarkLabel,
  withBanglaLabels,
} from '../_shared/remarks.ts'

/** Short non-human id (10 letters, never digits) — matches the DB default
 *  for validation_remarks.id (202609090004). */
function shortId(): string {
  const abc = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'
  const bytes = crypto.getRandomValues(new Uint8Array(10))
  let out = ''
  for (let i = 0; i < 10; i++) out += abc[bytes[i] % 52]
  return out
}

Deno.serve(async (request) => {
  const guard = guardRequest(request)
  if (guard) return guard
  let action: string | undefined
  let identity: { uid: string; token: string } | undefined
  try {
    identity = await firebaseIdentity(request)
    const body = await request.json()
    action = body.action

    if (action === 'write') {
      const row = body.row
      if (!row || !['consignment', 'branch_id', 'assigned_to_system_id', 'source'].every((key) => typeof row[key] === 'string' && row[key].trim())) {
        errLog('write', 'missing_required_fields', { row: JSON.stringify(row) })
        return reply({ error: 'Missing required row fields' }, 400)
      }
      if (row.source !== 'CC' && row.source !== 'WORKER') {
        errLog('write', 'invalid_source', { source: row.source })
        return reply({ error: 'Invalid remark source' }, 400)
      }
      // Author fields come exclusively from the verified Firebase identity; Android
      // never supplies them, so a caller cannot impersonate another employee.
      // users rows are admin-onboarded only (employee edit) — a login/remark
      // NEVER creates one. Fail fast with a contact-admin message when the
      // row is missing instead of a cryptic FK error (validations FKs both
      // author and assigned to users).
      const authorProfile = await firebaseProfile(identity)
      if (!await requireUsersRow(authorProfile.systemId)) {
        errLog('write', 'author_users_row_missing', { system_id: authorProfile.systemId })
        return reply({ error: 'Your employee profile is missing — ask admin to add you in employee edit' }, 403)
      }
      // Keep this device fleet's push routing fresh: branch transfers and
      // role changes otherwise leave fcm_device_tokens.branch_ids /
      // can_access_call_center stale until the next login (register_push_token
      // only runs on auth-state change). Best-effort — a failed refresh must
      // never block the remark save.
      try {
        const { error: tokenRefreshError } = await admin.from('fcm_device_tokens').update({
          branch_ids: authorProfile.branchIds,
          role_id: authorProfile.roleId,
          can_access_call_center: authorProfile.canAccessCallCenter,
          updated_at: new Date().toISOString(),
        }).eq('firebase_uid', identity.uid)
        if (tokenRefreshError) console.error('fcm token refresh failed', tokenRefreshError)
      } catch (e) {
        console.error('fcm token refresh failed', e)
      }
      await ensureAuthenticatedRoleClaim(identity.uid) // defensive: covers a user who writes before ever syncing
      if (row.assigned_to_system_id === authorProfile.systemId) {
        // Author row already verified above; avoids a duplicate lookup.
      } else {
        // FK to users: the assigned agent must be admin-onboarded too. Fail
        // fast (contact admin) instead of auto-creating the row or hitting a
        // cryptic FK error. A missing/stale index entry for a REAL employee
        // means employee edit hasn't onboarded them yet.
        if (!await requireUsersRow(row.assigned_to_system_id)) {
          errLog('write', 'assigned_users_row_missing', { assigned: row.assigned_to_system_id })
          return reply({ error: 'Assigned agent is not onboarded — ask admin to add them in employee edit' }, 403)
        }
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
      if (error) {
        errLog('write', 'db_insert_failed', { consignment: savedRow.consignment, pg_code: error.code, pg_message: error.message })
        throw error
      }
      // Best-effort catalog update — runs after the audit row is safely saved,
      // and never blocks or fails the write response.
      if (typeof row.remarks_bn === 'string') {
        await upsertRemarkLabel(savedRow.source, savedRow.remarks, row.remarks_bn)
      }
      const push = await sendRemarkPush(savedRow, identity)
      return reply({ ok: true, push })
    }

    if (action === 'sync_run_status') {
      // Run-route → validations snapshot sync (Hermes extension): a run page's
      // live consignment statuses overwrite `consignment_status` on EVERY row
      // of that consignment created on the run-open day (day_start..day_end,
      // Dhaka bounds sent by the caller) — not just the latest row. History
      // keeps the remark trail, but every same-day row carries the final run
      // status. Rows that already match are skipped; consignments with no row
      // that day are reported as missing (never auto-created).
      const items = body.items
      if (!Array.isArray(items) || items.length === 0) {
        return reply({ error: 'items[] (consignment + status) required' }, 400)
      }
      if (items.length > 500) {
        return reply({ error: 'max 500 items per call' }, 400)
      }
      const dayStart = typeof body.day_start === 'string' ? body.day_start : ''
      const dayEnd = typeof body.day_end === 'string' ? body.day_end : ''
      if (!dayStart || !dayEnd) {
        return reply({ error: 'day_start and day_end (ISO) required' }, 400)
      }
      const authorProfile = await firebaseProfile(identity)
      if (!await requireUsersRow(authorProfile.systemId)) {
        errLog('sync_run_status', 'author_users_row_missing', { system_id: authorProfile.systemId })
        return reply({ error: 'Your employee profile is missing — ask admin to add you in employee edit' }, 403)
      }
      const want = new Map<string, string>()
      for (const it of items) {
        const c = typeof it?.consignment === 'string' ? it.consignment.trim() : ''
        const s = typeof it?.status === 'string' ? it.status.trim() : ''
        if (c && s) want.set(c, s)
      }
      if (want.size === 0) {
        return reply({ error: 'no usable consignment + status pairs' }, 400)
      }
      const ids = [...want.keys()]
      const rowsByConsignment = new Map<string, { id: string; consignment_status: string | null }[]>()
      for (let i = 0; i < ids.length; i += 200) {
        const chunk = ids.slice(i, i + 200)
        const { data, error } = await admin.from('validations')
          .select('id,consignment,consignment_status')
          .in('consignment', chunk)
          .gte('created_at', dayStart)
          .lt('created_at', dayEnd)
        if (error) {
          errLog('sync_run_status', 'select_failed', { pg_code: error.code, pg_message: error.message })
          throw error
        }
        for (const row of data ?? []) {
          const list = rowsByConsignment.get(row.consignment) ?? []
          list.push({ id: row.id, consignment_status: row.consignment_status })
          rowsByConsignment.set(row.consignment, list)
        }
      }
      let updated = 0, unchanged = 0, missing = 0
      for (const [consignment, status] of want) {
        const rows = rowsByConsignment.get(consignment) ?? []
        if (rows.length === 0) { missing++; continue }
        for (const row of rows) {
          if ((row.consignment_status ?? '') === status) { unchanged++; continue }
          const { error } = await admin.from('validations')
            .update({ consignment_status: status })
            .eq('id', row.id)
          if (error) {
            errLog('sync_run_status', 'update_failed', { consignment, pg_code: error.code, pg_message: error.message })
            throw error
          }
          updated++
        }
      }
      return reply({ ok: true, updated, unchanged, missing })
    }

    if (action === 'report') {
      // history, today, agent_range, new_since were removed earlier: Android
      // calls the PostgREST REST API directly (unlimited free tier).
      if (typeof body.branch_id !== 'string' || typeof body.start_iso !== 'string' || typeof body.end_iso !== 'string') {
        return reply({ error: 'branch_id, start_iso and end_iso are required' }, 400)
      }
      const page = Math.max(0, Number(body.page) || 0)
      const pageSize = Math.min(100, Math.max(1, Number(body.page_size) || 50))
      let query = admin.from('validations')
        .select('id,consignment,branch_id,assigned_to_system_id,author_system_id,source,remarks_status,consignment_status,remarks,note,customer_phone,created_at,author:users!validations_author_system_id_fkey(name,employee_id,role),assigned:users!validations_assigned_to_system_id_fkey(name,employee_id,role)')
        .eq('branch_id', body.branch_id).gte('created_at', body.start_iso).lt('created_at', body.end_iso)
      for (const field of ['consignment', 'assigned_to_system_id', 'author_system_id', 'remarks_status', 'consignment_status', 'source'] as const) {
        if (typeof body[field] === 'string' && body[field].trim()) query = query.eq(field, body[field].trim())
      }
      const { data, error } = await query.order('created_at', { ascending: false }).range(page * pageSize, (page + 1) * pageSize - 1)
      if (error) throw error
      return reply(await withBanglaLabels(data ?? []))
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
        .insert({ id: shortId(), ...payload }).select('*').maybeSingle()
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

    return reply({ error: 'Unknown action' }, 400)
  } catch (error) {
    return unhandled(action, identity, error)
  }
})
