-- Preserve existing rows while making the system-id semantics explicit.
alter table public.remark_validations
  rename column delivery_agent_id to agent_system_id;

alter table public.remark_validations
  rename column verifier_id to verifier_system_id;
