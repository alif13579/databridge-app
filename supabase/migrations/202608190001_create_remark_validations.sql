-- Append-only audit trail for delivery and call-centre remarks.
-- The mobile app never receives a database role with access to this table.
-- All access is mediated by the remark-validations Edge Function.
create table if not exists public.remark_validations (
  id bigint generated always as identity primary key,
  consignment_id text not null check (length(trim(consignment_id)) > 0),
  branch_id text not null check (length(trim(branch_id)) > 0),
  delivery_agent_id text not null check (length(trim(delivery_agent_id)) > 0),
  verifier_id text not null check (length(trim(verifier_id)) > 0),
  status text not null default '',
  remarks text not null default '',
  created_at timestamptz not null default now()
);

create index if not exists remark_validations_consignment_created_at_idx
  on public.remark_validations (consignment_id, created_at desc);
create index if not exists remark_validations_agent_created_at_idx
  on public.remark_validations (delivery_agent_id, created_at desc);
create index if not exists remark_validations_created_at_idx
  on public.remark_validations (created_at desc);

revoke all on table public.remark_validations from anon, authenticated;
alter table public.remark_validations enable row level security;

-- Deliberately no anon/authenticated policies: the Edge Function uses the
-- service-role key after it validates a Firebase ID token.
