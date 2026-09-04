-- Fix Petty Cash Supabase reads: claims/branches/stores were unreachable
-- from the app, which serves every request as `anon` (Firebase Third-party
-- Auth JWTs carry no `role` claim — see 202609030001's header).
--
-- What was broken (verified live 2026-09-04 via information_schema/pg_policies):
--   1. public.claims has ZERO policies → every direct PostgREST read
--      (ClaimsRepository.get/search, Top Sheet report) returned [] (HTTP 200,
--      genuinely-empty look), so request lists/reports were always empty.
--      Writes were unaffected (Edge Function uses the service-role admin
--      client and bypasses RLS).
--   2. public.branches policy branches_read_all is `to authenticated` ONLY →
--      anon reads returned [] → SupabasePettyCashReader.fetchBranch threw
--      "Branch not found", blocking the whole Petty Cash flow (including
--      claim submit, which also FK-checks branch_id) even with rows present.
--   3. public.stores has ZERO policies → the Pickup store picker always
--      showed "No stores available".
--   4. The deployed claim_upsert sent a `placed_date` column that does not
--      exist on public.claims → EVERY claim write failed with
--      "Could not find the 'placed_date' column" (fixed in the Edge Function
--      alongside this migration; no DDL needed for it).
--
-- Writes need nothing here (Edge admin client bypasses RLS). All policies
-- are read-only and narrowly scoped; RLS policies are OR-ed so these only
-- ADD the documented read paths. Idempotent: DROP IF EXISTS first.

-- 1. Grants (harmless if already granted).
grant select on public.claims to anon, authenticated;
grant select on public.branches to anon, authenticated;
grant select on public.stores to anon, authenticated;

-- 2. Claims: branch-scoped read, plus the requester's own rows (covers
--    searchMyClaims, which filters by requester_system_id without a branch).
--    Same my_branch_ids()/my_system_id() helpers + anon-inclusive pattern
--    as the validations/deposits policies.
drop policy if exists "claims_branch_read" on public.claims;
create policy "claims_branch_read"
  on public.claims
  for select to anon, authenticated
  using (
    branch_id = any (my_branch_ids())
    or requester_system_id = my_system_id()
  );

-- 3. Branches: public directory read (same using(true) shape the old
--    branches_read_all had — just extended to anon, which is the role the
--    app actually runs as).
drop policy if exists "branches_read_all" on public.branches;
create policy "branches_read_all"
  on public.branches
  for select to anon, authenticated
  using (true);

-- 4. Stores: public directory read (courier-wide picker, same pattern as
--    claim_categories_public_read — the app never writes stores).
drop policy if exists "stores_public_read" on public.stores;
create policy "stores_public_read"
  on public.stores
  for select to anon, authenticated
  using (true);
