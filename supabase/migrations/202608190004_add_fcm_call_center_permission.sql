-- Safe for projects that already applied the initial FCM token migration.
alter table public.fcm_device_tokens
  add column if not exists can_access_call_center boolean not null default false;

create index if not exists fcm_device_tokens_call_center_idx
  on public.fcm_device_tokens (can_access_call_center) where can_access_call_center;
