-- Same root cause as 202608220003 (grant_validation_read_to_anon), now
-- found on validation_remarks too: Firebase Third-party Auth JWTs arrive
-- without a `role` claim in this project, so PostgREST assigns every
-- authenticated Android request the `anon` database role, never
-- `authenticated`.
--
-- validation_remarks' only policy (202608230002) is scoped `to authenticated`,
-- and no grant was ever given to `anon` — so SupabaseClientManager.
-- fetchRemarkLabels()'s direct PostgREST read (Authorization: Bearer
-- <firebase-jwt>, same token as every other call) has been landing as
-- `anon` all along, matching no policy, and silently returning zero rows.
-- No error surfaces: WorkerSpaceFragment/CallCenterFragment both treat a
-- lookup miss as "fall back to the English text", so this failed silently
-- instead of crashing.
--
-- Fix: grant the same table privilege to anon that validations already
-- has, so the existing `using (true)`-equivalent access actually applies.
-- RLS still requires a matching policy to return rows, so add the anon
-- read policy directly rather than relying on the authenticated-only one.
grant select on public.validation_remarks to anon;

create policy "validation_remarks_select_anon"
  on public.validation_remarks for select
  to anon
  using (true);
