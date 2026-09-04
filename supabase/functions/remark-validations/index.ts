// remark-validations — Call Center / Worker REMARK flows only.
// Actions: write, report, admin_list_remarks, admin_upsert_remark,
// admin_delete_remark, admin_migrate_status_remarks.
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
  firebaseProfileForSystemId,
  firebaseRead,
} from '../_shared/firebase-auth.ts'
import { upsertUser } from '../_shared/users.ts'
import {
  sendRemarkPush,
  upsertRemarkLabel,
  withBanglaLabels,
} from '../_shared/remarks.ts'

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
      const authorProfile = await firebaseProfile(identity)
      await upsertUser(authorProfile, identity.uid)
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
        // Already upserted above; avoids a duplicate Firebase profile request.
      } else {
        const assignedProfile = await firebaseProfileForSystemId(row.assigned_to_system_id, identity)
        // Best-effort: upsert the assigned user if we can resolve their profile.
        // A missing/stale index entry must NOT block the remark from saving — the
        // remark row is valid and the CC agent saving it has already been verified.
        if (assignedProfile) await upsertUser(assignedProfile, assignedProfile.uid)
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

    return reply({ error: 'Unknown action' }, 400)
  } catch (error) {
    return unhandled(action, identity, error)
  }
})
