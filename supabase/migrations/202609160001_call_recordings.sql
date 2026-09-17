-- Call recordings attached to parcel journeys (manual save only).
--
-- Why a separate table instead of reusing validations: a recording is a
-- binary R2 object with its own lifecycle (record → upload → play), not a
-- remark/status event. The journey timeline merges both sources client-side
-- (see JourneyLogUi / SupabaseCallRecordings.kt). R2 bytes live under the
-- call_recordings/ prefix (see r2-attachment-upload); this table holds the
-- metadata that points at them.
--
-- Access pattern mirrors check_ins/van_movements: writes come from the app
-- via PostgREST with a branch-scoped WITH CHECK policy (non-financial,
-- append-only-ish data — unlike validations, no Edge Function mediation),
-- reads are branch-scoped anon-inclusive (Firebase JWTs carry no `role`
-- claim, so PostgREST assigns the anon role — same as every other table).
-- Idempotent: IF NOT EXISTS / DROP IF EXISTS.

create table if not exists public.call_recordings (
  id uuid primary key default gen_random_uuid(),
  consignment text not null,
  author_system_id text not null default '',
  assigned_to_system_id text not null default '',
  source text not null default 'WORKER' check (source in ('CC', 'WORKER')),
  branch_id text not null default '',
  r2_key text not null,
  duration_sec int not null default 0,
  file_size_bytes bigint not null default 0,
  note text not null default '',
  created_at timestamptz not null default now()
);

alter table public.call_recordings enable row level security;

grant select, insert on public.call_recordings to anon, authenticated;

drop policy if exists "call_recordings_branch_read" on public.call_recordings;
create policy "call_recordings_branch_read"
  on public.call_recordings
  for select to anon, authenticated
  using (branch_id = any (my_branch_ids()));

drop policy if exists "call_recordings_branch_insert" on public.call_recordings;
create policy "call_recordings_branch_insert"
  on public.call_recordings
  for insert to anon, authenticated
  with check (branch_id = any (my_branch_ids()));

drop index if exists public.call_recordings_by_consignment;
create index call_recordings_by_consignment
  on public.call_recordings (consignment, created_at desc);

-- Realtime so journey timelines can pick up new recordings live.
-- Publication membership is additive; already-present tables are untouched.
do $$
begin
  if not exists (
    select 1 from pg_publication_tables
    where pubname = 'supabase_realtime' and tablename = 'call_recordings'
  ) then
    alter publication supabase_realtime add table public.call_recordings;
  end if;
end $$;
