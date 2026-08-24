-- Server-side English->Bangla lookup catalog for remark text.
-- Populated by the remark-validations Edge Function on every write (upsert
-- on english_label, using the service-role key).
--
-- Unlike validations, this table IS directly readable by authenticated
-- clients — Android's history/card reads already go straight to PostgREST
-- (zero Edge Function invocations, see SupabaseRemarkValidationWriter's
-- fetch* functions) and now join against this table to resolve Bangla in
-- the same request, instead of the old client-side ccRemarkOptions match.
--
-- validations.remarks stays English-only; this table is a separate,
-- append/update-only catalog keyed by the exact English text.
create table if not exists public.remark_labels (
  english_label text primary key check (length(trim(english_label)) > 0),
  bangla_label text not null check (length(trim(bangla_label)) > 0),
  updated_at timestamptz not null default now()
);

alter table public.remark_labels enable row level security;

-- Read-only for authenticated clients: lets Android join this table
-- directly on a normal PostgREST read (validations RLS already requires the
-- same Third-Party Auth "authenticated" role claim, see
-- ensureAuthenticatedRoleClaim() in the Edge Function).
create policy "remark_labels_select_authenticated"
  on public.remark_labels for select
  to authenticated
  using (true);

-- Writes stay Edge-Function-only: no insert/update/delete policy for
-- anon/authenticated. The Edge Function uses the service-role key, which
-- bypasses RLS entirely.
revoke insert, update, delete on table public.remark_labels from anon, authenticated;

-- validations.remarks and remark_labels.english_label are both free text with
-- no foreign key between them (a free-typed note in `remarks` may not match
-- any catalog entry, so a FK/not-null relationship isn't valid here) —
-- PostgREST can't auto-embed across a text match. This view does the LEFT
-- JOIN once, server-side, so Android's existing direct-PostgREST reads
-- (fetchHistory/fetchTodayForDeliveryAgent/etc.) get remarks_bn back in the
-- same request just by querying validations_with_bn instead of validations;
-- every existing filter/order/range param still works identically since the
-- view exposes all of validations' columns unchanged, plus remarks_bn.
create view public.validations_with_bn
  with (security_invoker = true) as
select
  v.*,
  coalesce(rl.bangla_label, v.remarks) as remarks_bn
from public.validations v
left join public.remark_labels rl on rl.english_label = v.remarks;

-- security_invoker means this view carries no elevated privilege of its
-- own — it runs with the querying user's role, so validations' existing RLS
-- policies still gate which rows are visible exactly as before.
grant select on public.validations_with_bn to authenticated;
