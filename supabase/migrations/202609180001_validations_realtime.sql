-- Realtime for the validations feed (extension HV dashboard + CC panel).
--
-- The Android app already subscribes postgres_changes on public.validations
-- (SupabaseRealtimeManager, per-branch channels, Firebase third-party JWT).
-- This only adds the table to the publication so browser clients can do the
-- same; RLS is untouched (read = own branch OR assigned worker, anon SELECT
-- granted in 202608220003 — realtime honors the exact same policies).
--
-- Guarded + additive like 202609160001 (call_recordings): re-running is a
-- no-op, already-present tables are untouched. Payload per event is one row;
-- clients join with a per-branch filter so each device only receives rows it
-- is allowed to see and needs.
do $$
begin
  if not exists (
    select 1 from pg_publication_tables
    where pubname = 'supabase_realtime' and tablename = 'validations'
  ) then
    alter publication supabase_realtime add table public.validations;
  end if;
end $$;
