-- FCM registration tokens are server-managed.  The Android client can only
-- register the token belonging to its verified Firebase identity through the
-- Edge Function; it never gets direct table access.
create table if not exists public.fcm_device_tokens (
  token text primary key check (length(trim(token)) > 0),
  firebase_uid text not null check (length(trim(firebase_uid)) > 0),
  system_id text not null check (length(trim(system_id)) > 0),
  role_id text not null default '',
  can_access_call_center boolean not null default false,
  branch_ids text[] not null default '{}',
  updated_at timestamptz not null default now()
);

create index if not exists fcm_device_tokens_system_id_idx
  on public.fcm_device_tokens (system_id);
create index if not exists fcm_device_tokens_role_id_idx
  on public.fcm_device_tokens (role_id);
create index if not exists fcm_device_tokens_call_center_idx
  on public.fcm_device_tokens (can_access_call_center) where can_access_call_center;
create index if not exists fcm_device_tokens_branch_ids_idx
  on public.fcm_device_tokens using gin (branch_ids);

revoke all on table public.fcm_device_tokens from anon, authenticated;
alter table public.fcm_device_tokens enable row level security;
