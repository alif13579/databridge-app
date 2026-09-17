-- Allow branch-scoped deletes on call_recordings (admin Cleanup panel).
-- Delete = R2 object (presigned DELETE, app-side) + this row. No auto-delete
-- anywhere: proof rows only disappear through the manual panel flow.
-- Idempotent: DROP IF EXISTS.

drop policy if exists "call_recordings_branch_delete" on public.call_recordings;
create policy "call_recordings_branch_delete"
  on public.call_recordings
  for delete to anon, authenticated
  using (branch_id = any (my_branch_ids()));

-- The select/insert grants from 202609160001 already cover read/write;
-- delete needs its own grant.
grant delete on public.call_recordings to anon, authenticated;
