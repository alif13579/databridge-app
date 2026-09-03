-- Full Petty Cash cutover: open direct PostgREST SELECTs for the reads
-- SupabasePettyCashReader does (deposits, wallet balance, own users row).
--
-- Writes need nothing here: they go through the remark-validations Edge
-- Function (petty_cash_deposit_upsert / petty_cash_wallet_balance_upsert),
-- which uses the service-role admin client and bypasses RLS entirely.
--
-- Auth context: Firebase Third-party Auth JWTs in this project carry no
-- `role` claim, so PostgREST serves app requests as the `anon` role — every
-- policy below is therefore `to anon, authenticated`, the same pattern the
-- validations / validation_remarks anon grants already use. Without the
-- anon side, reads silently return [] (HTTP 200, genuinely-empty look).
--
-- All policies are branch- or self-scoped (never using(true)): RLS policies
-- are OR-ed, so these only ADD a narrow read path without widening any
-- existing policy. Idempotent: DROP IF EXISTS first, safe to re-apply.
-- Helpers my_branch_ids() / current_firebase_id() already exist (see
-- SCHEMA_HISTORY entries 13/14 and the users/validations RLS setup).

-- 1. Grants for the two tables the app now reads directly. (branches/users
--    already have the grants their existing app reads rely on —
--    fetchBranches/fetchClaimsForReport — so they are not re-granted here.)
grant select on public.petty_cash_deposits to anon, authenticated;
grant select on public.petty_cash_wallet_balance to anon, authenticated;

-- 2. Branch-scoped deposit reads: a user sees deposits only for branches in
--    their own public.users.branch_ids array.
drop policy if exists "petty_cash_deposits_branch_read" on public.petty_cash_deposits;
create policy "petty_cash_deposits_branch_read"
  on public.petty_cash_deposits
  for select to anon, authenticated
  using (branch_id = any (my_branch_ids()));

-- 3. Branch-scoped wallet-balance reads (one row per branch).
drop policy if exists "petty_cash_wallet_balance_branch_read" on public.petty_cash_wallet_balance;
create policy "petty_cash_wallet_balance_branch_read"
  on public.petty_cash_wallet_balance
  for select to anon, authenticated
  using (branch_id = any (my_branch_ids()));

-- 4. Own-row users read: the app resolves the signed-in user's display name
--    + system_id via public.users keyed by Firebase uid (see
--    SupabasePettyCashReader.fetchCurrentUser). Scoped to the caller's own
--    row only — never a table-wide grant.
drop policy if exists "users_self_read_by_firebase_id" on public.users;
create policy "users_self_read_by_firebase_id"
  on public.users
  for select to anon, authenticated
  using (firebase_id = current_firebase_id());
