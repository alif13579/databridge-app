-- Immutable report/audit snapshots, resolved by the authenticated Edge Function.
-- Existing rows remain valid; these fields are populated for every new remark.
alter table public.remark_validations
  add column if not exists customer_phone text not null default '',
  add column if not exists agent_name text not null default '',
  add column if not exists verifier_name text not null default '',
  add column if not exists note text not null default '';

create index if not exists remark_validations_customer_phone_created_at_idx
  on public.remark_validations (customer_phone, created_at desc)
  where customer_phone <> '';
