-- Normalize the validation audit log.  This migration deliberately renames the
-- existing table so deployed history remains available without a copy/delete.
create table if not exists public.users (
  system_id text primary key check (length(trim(system_id)) > 0),
  employee_id text unique,
  name text not null check (length(trim(name)) > 0),
  branch_id text,
  role text,
  firebase_id text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists users_branch_id_idx on public.users (branch_id);

alter table public.remark_validations rename to validations;
alter table public.validations rename column consignment_id to consignment;
alter table public.validations rename column assigned_agent_system_id to assigned_to_system_id;

-- `id` is now UUID.  Keep existing ids only as historical data is already
-- present; new rows use UUIDs and the old numeric id is not exposed by the app.
alter table public.validations add column if not exists validation_uuid uuid default gen_random_uuid();
update public.validations set validation_uuid = gen_random_uuid() where validation_uuid is null;
alter table public.validations alter column validation_uuid set not null;
alter table public.validations drop constraint if exists remark_validations_pkey;
alter table public.validations drop constraint if exists validations_pkey;
alter table public.validations add primary key (validation_uuid);
alter table public.validations rename column id to legacy_id;
alter table public.validations rename column validation_uuid to id;
alter table public.validations drop column legacy_id;

alter table public.validations
  alter column customer_phone drop not null,
  alter column remarks drop not null,
  alter column remarks_status drop not null,
  alter column note drop not null,
  alter column source drop not null,
  alter column consignment_status drop not null;

alter table public.validations
  drop column if exists author_firebase_uid,
  drop column if exists author_name,
  drop column if exists author_employee_id;

alter table public.validations
  add constraint validations_author_system_id_fkey
    foreign key (author_system_id) references public.users(system_id),
  add constraint validations_assigned_to_system_id_fkey
    foreign key (assigned_to_system_id) references public.users(system_id);

drop index if exists public.remark_validations_consignment_created_at_idx;
drop index if exists public.remark_validations_assigned_agent_created_at_idx;
drop index if exists public.remark_validations_author_created_at_idx;
drop index if exists public.remark_validations_created_at_idx;
drop index if exists public.remark_validations_customer_phone_created_at_idx;
create index if not exists idx_validations_branch_created on public.validations (branch_id, created_at desc);
create index if not exists idx_validations_consignment on public.validations (consignment);
create index if not exists idx_validations_author on public.validations (author_system_id);
create index if not exists idx_validations_assigned_to on public.validations (assigned_to_system_id);

revoke all on table public.users from anon, authenticated;
revoke all on table public.validations from anon, authenticated;
alter table public.users enable row level security;
alter table public.validations enable row level security;
