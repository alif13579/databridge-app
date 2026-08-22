-- Firebase UIDs are opaque strings, not UUIDs. auth.uid() attempts to parse
-- the JWT subject as uuid and raises 22P02 for Firebase IDs such as
-- Dd21YcVJfVRIFxrx9tIOsMRQlSs2. Read the subject claim directly instead.

create or replace function public.current_firebase_id()
returns text
language sql
stable
security definer
set search_path = public
as $$
  select auth.jwt() ->> 'sub';
$$;

create or replace function public.my_branch_id()
returns text
language sql
stable
security definer
set search_path = public
as $$
  select branch_id
  from public.users
  where firebase_id = public.current_firebase_id()
  limit 1;
$$;

create or replace function public.my_system_id()
returns text
language sql
stable
security definer
set search_path = public
as $$
  select system_id
  from public.users
  where firebase_id = public.current_firebase_id()
  limit 1;
$$;

create or replace function public.my_branch_ids()
returns text[]
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(branch_ids, '{}')
  from public.users
  where firebase_id = public.current_firebase_id()
  limit 1;
$$;

drop policy if exists "users_read_own" on public.users;
create policy "users_read_own"
on public.users for select
using (firebase_id = public.current_firebase_id());

drop policy if exists "users_upsert_own" on public.users;
create policy "users_upsert_own"
on public.users for all
using (firebase_id = public.current_firebase_id())
with check (firebase_id = public.current_firebase_id());
