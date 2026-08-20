-- Preserve existing rows while making the system-id semantics explicit.
do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'remark_validations'
      and column_name = 'delivery_agent_id'
  ) and not exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'remark_validations'
      and column_name = 'agent_system_id'
  ) then
    alter table public.remark_validations
      rename column delivery_agent_id to agent_system_id;
  end if;

  if exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'remark_validations'
      and column_name = 'verifier_id'
  ) and not exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'remark_validations'
      and column_name = 'verifier_system_id'
  ) then
    alter table public.remark_validations
      rename column verifier_id to verifier_system_id;
  end if;
end $$;
