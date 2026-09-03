-- Van check-in/out log (hub van arrival + departure timestamps).
-- One row per visit: check_out_at NULL means the van is still inside.
--
-- Writes go through the remark-validations Edge Function
-- (van_checkin / van_checkout actions, service-role admin client), so no
-- write policy here on purpose. Reads are branch-scoped, anon-inclusive
-- (Firebase JWTs carry no `role` claim — same pattern as the petty cash
-- tables). The partial unique index is the double check-in guard: one open
-- row per vehicle per branch, enforced by the database, not just the app.
-- Idempotent: IF NOT EXISTS / DROP IF EXISTS.

create table if not exists public.van_movements (
  id uuid primary key default gen_random_uuid(),
  branch_id text not null references public.branches(branch_id),
  vehicle_number text not null,
  vehicle_type text not null default '',
  driver_name text not null default '',
  check_in_at timestamptz not null default now(),
  check_out_at timestamptz,
  check_in_by_uid text not null default '',
  check_in_by_system_id text not null default '',
  check_out_by_uid text not null default '',
  check_out_by_system_id text not null default '',
  note text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.van_movements enable row level security;

grant select on public.van_movements to anon, authenticated;

drop policy if exists "van_movements_branch_read" on public.van_movements;
create policy "van_movements_branch_read"
  on public.van_movements
  for select to anon, authenticated
  using (branch_id = any (my_branch_ids()));

drop index if exists public.van_open_per_vehicle;
create unique index van_open_per_vehicle
  on public.van_movements (branch_id, vehicle_number)
  where check_out_at is null;
