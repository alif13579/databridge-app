-- 202609050001: branch-scoped read on public.users.
--
-- Claim/validation reads embed users rows for actor names (requester, staff,
-- poc, ... via PostgREST embeds in SupabaseClaimsReader). The embedded rows
-- are filtered by the users table's OWN policies, which were own-row-only —
-- so every other person's name came back null and cards/details/steps showed
-- blank requester/actor names. Firebase parity is any-authenticated-user can
-- read profiles, but branch overlap is the tighter rule that covers every
-- claim flow (actors are always same-branch): a user sees users rows whose
-- branch_ids overlap their own. Own-row policies stay untouched.
-- Applied live via Management API (db query).

grant select on public.users to anon, authenticated;

drop policy if exists "users_branch_read" on public.users;
create policy "users_branch_read"
  on public.users
  for select to anon, authenticated
  using (branch_ids && my_branch_ids());
