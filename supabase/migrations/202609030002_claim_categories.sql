-- Claim categories catalog (admin-managed): the request form's category
-- picker reads this (SupabaseClaimsReader.fetchClaimCategories) so an admin
-- can add categories without an app release. `category_group` drives the
-- Top Sheet report: conveyance rows fold into the Operation total and get
-- per-agent Conveyance Voucher pages; operation / office / utilities get
-- their own summary sections.
--
-- Reads are a public catalog (same using(true) pattern as
-- validation_remarks). Writes stay admin-only via Dashboard / service role
-- (no write policy here on purpose) — the app never inserts categories.
-- Idempotent: IF NOT EXISTS / DROP IF EXISTS / ON CONFLICT DO NOTHING.

create table if not exists public.claim_categories (
  name text primary key,
  category_group text not null default 'operation'
    check (category_group in ('conveyance', 'operation', 'office', 'utilities')),
  is_active boolean not null default true,
  sort_order integer not null default 0,
  created_at timestamptz not null default now()
);

alter table public.claim_categories enable row level security;

grant select on public.claim_categories to anon, authenticated;

drop policy if exists "claim_categories_public_read" on public.claim_categories;
create policy "claim_categories_public_read"
  on public.claim_categories
  for select to anon, authenticated
  using (true);

insert into public.claim_categories (name, category_group, is_active, sort_order) values
  ('Bulk Delivery', 'conveyance', true, 10),
  ('Pickup', 'conveyance', true, 20)
on conflict (name) do nothing;
