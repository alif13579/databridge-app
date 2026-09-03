-- Generic check-in/out log: REPLACES public.van_movements (dropped at the
-- bottom — it never held production rows, so this is a clean rename, not a
-- data migration; the INSERT ... SELECT below carries over any rows anyway).
-- One table for every subject kind: vans today, employee check-in and more
-- later — told apart by subject_type, so no "van" in the table or columns.
--
-- Identity is system_id-only (check_in_by_system_id / check_out_by_system_id):
-- public.users lookups key on system_id, never uid, so uid columns would be
-- dead weight AND a second identity to keep in sync.
--
-- One row per visit: check_out_at NULL means still inside. Writes go through
-- the remark-validations Edge Function (checkin / checkout actions,
-- service-role admin client), so no write policy here on purpose. Reads are
-- branch-scoped, anon-inclusive (Firebase JWTs carry no `role` claim — same
-- pattern as the other tables). The partial unique index is the double
-- check-in guard: one open row per subject per branch, enforced by the
-- database, not just the app.
-- Idempotent: IF NOT EXISTS / DROP IF EXISTS (the data-carry INSERT is
-- naturally idempotent only on first run — van_movements is dropped right
-- after, so a re-run copies zero rows).

create table if not exists public.check_ins (
  id uuid primary key default gen_random_uuid(),
  branch_id text not null references public.branches(branch_id),
  subject_type text not null default 'van',
  subject_label text not null,
  vehicle_type text not null default '',
  driver_name text not null default '',
  check_in_at timestamptz not null default now(),
  check_out_at timestamptz,
  check_in_by_system_id text not null default '',
  check_out_by_system_id text not null default '',
  note text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.check_ins enable row level security;

grant select on public.check_ins to anon, authenticated;

drop policy if exists "check_ins_branch_read" on public.check_ins;
create policy "check_ins_branch_read"
  on public.check_ins
  for select to anon, authenticated
  using (branch_id = any (my_branch_ids()));

drop index if exists public.checkin_open_per_subject;
create unique index checkin_open_per_subject
  on public.check_ins (branch_id, subject_type, subject_label)
  where check_out_at is null;

-- Carry over anything van_movements ever collected (expected: zero rows),
-- then remove it so only the generic table remains.
insert into public.check_ins
  (branch_id, subject_type, subject_label, vehicle_type, driver_name,
   check_in_at, check_out_at, check_in_by_system_id, check_out_by_system_id,
   note, created_at, updated_at)
select branch_id, 'van', vehicle_number, vehicle_type, driver_name,
  check_in_at, check_out_at, check_in_by_system_id, check_out_by_system_id,
  note, created_at, updated_at
from public.van_movements;

drop table if exists public.van_movements;
