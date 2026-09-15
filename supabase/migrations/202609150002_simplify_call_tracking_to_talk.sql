-- Simplify call tracking to talk evidence only: today's total talk seconds plus a
-- JSON log of per-talk timestamps (see CallAttemptStore.kt). Drops the count/max/cut
-- columns (feature unreleased — no data to migrate).
alter table public.validations
  drop column if exists call_count,
  drop column if exists call_max_talk_sec,
  drop column if exists call_cut,
  add column if not exists call_log jsonb;
-- call_talk_sec (from 202609150001) stays as-is.
