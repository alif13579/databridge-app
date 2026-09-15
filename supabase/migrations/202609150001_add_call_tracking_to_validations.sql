-- Call-track evidence on remarks: today's dial summary for the parcel's number,
-- recorded by the agent's device at remark-save time (see CallAttemptStore.kt).
-- Lets supervisors see per remark whether the agent actually talked (talk seconds)
-- or fake-dialed (instant cuts). Nullable = unknown (no call-log permission or
-- older app build); 0 = known zero (e.g. remark saved with 0 dials).
-- Table-level SELECT grants + row RLS already cover new columns (no policy change).
alter table public.validations
  add column if not exists call_count int,
  add column if not exists call_talk_sec int,
  add column if not exists call_max_talk_sec int,
  add column if not exists call_cut int;
