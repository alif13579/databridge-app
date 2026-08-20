-- A remark is immutable, so capture the authenticated author explicitly.
-- The assigned agent remains separate because reports and CC-to-worker
-- notifications need the parcel owner, not merely the author.
do $$
begin
  if exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'remark_validations' and column_name = 'agent_system_id')
     and not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'remark_validations' and column_name = 'assigned_agent_system_id') then
    alter table public.remark_validations rename column agent_system_id to assigned_agent_system_id;
  end if;

  if exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'remark_validations' and column_name = 'verifier_system_id')
     and not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'remark_validations' and column_name = 'author_system_id') then
    alter table public.remark_validations rename column verifier_system_id to author_system_id;
  end if;
end $$;

alter table public.remark_validations
  add column if not exists author_firebase_uid text not null default '',
  add column if not exists author_name text not null default '',
  add column if not exists author_employee_id text not null default '';

alter table public.remark_validations
  drop column if exists agent_name,
  drop column if exists verifier_name;

drop index if exists public.remark_validations_agent_created_at_idx;
create index if not exists remark_validations_assigned_agent_created_at_idx
  on public.remark_validations (assigned_agent_system_id, created_at desc);
create index if not exists remark_validations_author_created_at_idx
  on public.remark_validations (author_system_id, created_at desc);
