-- ─────────────────────────────────────────────────────────────────────────────
-- Fix: RLS read policy compared validations.branch_id against a single
-- public.users.branch_id value, but a CC agent's Firebase profile can carry
-- MULTIPLE branch_ids (see BranchCreateFragment/BranchEditFragment — managers,
-- accountants, and other roles are routinely granted more than one branch).
-- upsertUser() in the Edge Function only ever stored branchIds[0], so any
-- agent with more than one branch silently lost read access (RLS, not an
-- error) to validations rows for their other branch(es) — this is why the
-- CallCenterFragment long-press journey popup could come back empty for a
-- parcel belonging to one of an agent's non-first branches.
--
-- branch_id (singular, text) is kept for now rather than dropped immediately —
-- other code may still read it — but it is no longer what RLS checks against.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── users: add the array column, backfill from the existing single value ────
alter table public.users add column if not exists branch_ids text[] not null default '{}';

update public.users
set branch_ids = array[branch_id]
where branch_id is not null
  and (branch_ids is null or branch_ids = '{}');

-- ── index: GIN for "does this row's branch_id appear in that array" lookups ─
drop index if exists public.users_branch_id_idx;
create index if not exists users_branch_ids_gin_idx on public.users using gin (branch_ids);

-- ── helper: current user's branch_ids (replaces the single-value my_branch_id) ─
create or replace function public.my_branch_ids()
returns text[] language sql stable security definer as $$
  select coalesce(branch_ids, '{}') from public.users where firebase_id = auth.uid()::text limit 1;
$$;

-- ── validations: read — branch_id must be ANY of the caller's branch_ids ────
drop policy if exists "validations_read_own_branch" on public.validations;
create policy "validations_read_own_branch"
on public.validations for select
using (branch_id = any (public.my_branch_ids()));

-- ── validations: insert — same array-membership check ───────────────────────
drop policy if exists "validations_insert_own_branch" on public.validations;
create policy "validations_insert_own_branch"
on public.validations for insert
with check (
  branch_id = any (public.my_branch_ids())
  and author_system_id = public.my_system_id()
);

-- my_branch_id() (singular) is intentionally left in place — dropping it here
-- could break any other policy/function still referencing it. It simply stops
-- being used by the validations policies above.
