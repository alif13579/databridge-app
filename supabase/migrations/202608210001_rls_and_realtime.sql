-- ─────────────────────────────────────────────────────────────────────────────
-- RLS policies for validations + users tables, and enable Realtime.
--
-- auth.uid() returns the Firebase UID when the client authenticates via
-- Supabase Auth's Firebase provider (signInWithIdToken).  The users.firebase_id
-- column already stores that same UID, so no schema change is needed.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── Helper: current user's branch_id ─────────────────────────────────────────
create or replace function public.my_branch_id()
returns text language sql stable security definer as $$
  select branch_id from public.users where firebase_id = auth.uid()::text limit 1;
$$;

-- ── Helper: current user's system_id ─────────────────────────────────────────
create or replace function public.my_system_id()
returns text language sql stable security definer as $$
  select system_id from public.users where firebase_id = auth.uid()::text limit 1;
$$;

-- ── validations: read ─────────────────────────────────────────────────────────
drop policy if exists "validations_read_own_branch" on public.validations;
create policy "validations_read_own_branch"
on public.validations for select
using (branch_id = public.my_branch_id());

-- ── validations: insert ───────────────────────────────────────────────────────
drop policy if exists "validations_insert_own_branch" on public.validations;
create policy "validations_insert_own_branch"
on public.validations for insert
with check (
  branch_id = public.my_branch_id()
  and author_system_id = public.my_system_id()
);

-- ── users: read own row ───────────────────────────────────────────────────────
drop policy if exists "users_read_own" on public.users;
create policy "users_read_own"
on public.users for select
using (firebase_id = auth.uid()::text);

-- ── users: upsert own row (login sync) ───────────────────────────────────────
drop policy if exists "users_upsert_own" on public.users;
create policy "users_upsert_own"
on public.users for all
using (firebase_id = auth.uid()::text)
with check (firebase_id = auth.uid()::text);

-- ── grant read to authenticated role ─────────────────────────────────────────
grant select, insert on public.validations to authenticated;
grant select, insert, update on public.users to authenticated;

-- ── enable Realtime on validations ───────────────────────────────────────────
-- Row-level changes are filtered by RLS, so each agent only receives
-- events for their own branch.
do $$
begin
  if not exists (
    select 1
    from pg_publication_tables
    where pubname = 'supabase_realtime'
      and schemaname = 'public'
      and tablename = 'validations'
  ) then
    alter publication supabase_realtime add table public.validations;
  end if;
end $$;
