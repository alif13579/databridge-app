// check-ins — generic check-in log (vans today; more subject kinds later).
// Actions: checkin, checkout. Identity is system_id-only — users lookups key
// on system_id, so no uid column is stored at all.

import { admin } from '../_shared/supabase.ts'
import { guardRequest, reply, unhandled } from '../_shared/http.ts'
import { firebaseIdentity, firebaseProfile } from '../_shared/firebase-auth.ts'

Deno.serve(async (request) => {
  const guard = guardRequest(request)
  if (guard) return guard
  let action: string | undefined
  let identity: { uid: string; token: string } | undefined
  try {
    identity = await firebaseIdentity(request)
    const body = await request.json()
    action = body.action

    if (action === 'checkin') {
      // Opens one row in public.check_ins (check_out_at NULL = inside).
      // Double check-in is rejected here AND by the
      // checkin_open_per_subject partial unique index — the app pre-checks,
      // but concurrent taps from two devices must not create two open rows.
      const branchId = typeof body.branch_id === 'string' ? body.branch_id.trim() : ''
      const subjectType = typeof body.subject_type === 'string' && body.subject_type.trim()
        ? body.subject_type.trim() : 'van'
      const subjectLabel = typeof body.subject_label === 'string' ? body.subject_label.trim()
        : (typeof body.vehicle_number === 'string' ? body.vehicle_number.trim() : '')
      if (!branchId || !subjectLabel) {
        return reply({ error: 'branch_id and subject_label are required' }, 400)
      }
      const str = (v: unknown) => typeof v === 'string' ? v : ''
      const { data: alreadyOpen } = await admin.from('check_ins').select('id')
        .eq('branch_id', branchId).eq('subject_type', subjectType).eq('subject_label', subjectLabel)
        .is('check_out_at', null).limit(1)
      if (alreadyOpen && alreadyOpen.length > 0) {
        return reply({ error: 'Already checked in', movement_id: alreadyOpen[0].id }, 409)
      }
      // Manual backdate allowed (the tap often comes late) — but never the
      // future. 60s grace covers minor device clock skew.
      const checkInRaw = typeof body.check_in_at === 'string' ? body.check_in_at.trim() : ''
      const checkInAt = checkInRaw ? new Date(checkInRaw) : new Date()
      if (Number.isNaN(checkInAt.getTime())) {
        return reply({ error: 'Invalid check_in_at' }, 400)
      }
      if (checkInAt.getTime() > Date.now() + 60_000) {
        return reply({ error: 'Check-in time cannot be in the future' }, 400)
      }
      const recorder = await firebaseProfile(identity)
      const now = new Date().toISOString()
      const { data, error } = await admin.from('check_ins').insert({
        branch_id: branchId, subject_type: subjectType, subject_label: subjectLabel,
        vehicle_type: str(body.vehicle_type), driver_name: str(body.driver_name),
        note: str(body.note),
        check_in_at: checkInAt.toISOString(),
        check_in_by_system_id: recorder.systemId,
        created_at: now, updated_at: now,
      }).select('id').maybeSingle()
      if (error) throw error
      console.info(`checkin ok: branch=${branchId} subject=${subjectType}/${subjectLabel}`)
      return reply({ ok: true, movement_id: data?.id })
    }

    if (action === 'checkout') {
      // Stamps the open row. Scoped to still-open rows, so a double-tap (or
      // two devices) can't stamp twice — the second call reports already:true
      // instead of failing.
      if (typeof body.movement_id !== 'string' || !body.movement_id.trim()) {
        return reply({ error: 'movement_id is required' }, 400)
      }
      const recorder = await firebaseProfile(identity)
      const now = new Date().toISOString()
      const targetId = body.movement_id.trim()
      const { data: target, error: fetchError } = await admin.from('check_ins')
        .select('id,check_in_at,check_out_at').eq('id', targetId).maybeSingle()
      if (fetchError) throw fetchError
      if (!target) return reply({ error: 'Movement not found' }, 404)
      if (target.check_out_at) return reply({ ok: true, already: true })
      // Manual correction allowed — but never before its own check-in and
      // never the future (same 60s skew grace as check-in).
      const checkOutRaw = typeof body.check_out_at === 'string' ? body.check_out_at.trim() : ''
      const checkOutAt = checkOutRaw ? new Date(checkOutRaw) : new Date()
      if (Number.isNaN(checkOutAt.getTime())) {
        return reply({ error: 'Invalid check_out_at' }, 400)
      }
      if (checkOutAt.getTime() < new Date(target.check_in_at).getTime()) {
        return reply({ error: 'Check-out time cannot be before check-in time' }, 400)
      }
      if (checkOutAt.getTime() > Date.now() + 60_000) {
        return reply({ error: 'Check-out time cannot be in the future' }, 400)
      }
      const { data, error } = await admin.from('check_ins').update({
        check_out_at: checkOutAt.toISOString(),
        check_out_by_system_id: recorder.systemId,
        updated_at: now,
      }).eq('id', targetId).is('check_out_at', null)
        .select('id')
      if (error) throw error
      if (!data || data.length === 0) {
        // Lost a race with another device's checkout between our read and
        // write — that checkout already stamped it, same as already:true.
        return reply({ ok: true, already: true })
      }
      console.info(`checkout ok: movement=${body.movement_id}`)
      return reply({ ok: true })
    }

    return reply({ error: 'Unknown action' }, 400)
  } catch (error) {
    return unhandled(action, identity, error)
  }
})
