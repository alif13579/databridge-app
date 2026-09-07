-- 202609070001: drop public.users.branch_id (singular).
--
-- branch_ids (array) is the live multi-branch source — RLS's my_branch_ids(),
-- users_branch_read, requireUsersRow() and every app/Edge reader check against
-- it. The singular branch_id was write-only (upsertUser kept branchIds[0] in
-- it, nothing ever read it), so it only duplicated data and confused tooling.
--
-- ORDER MATTERS: deploy user-sync WITHOUT the branch_id payload FIRST, then
-- apply this. An old user-sync sending branch_id after the drop would hard-fail
-- every employee save. (validations.branch_id is a different table's column —
-- untouched by this.)
--
-- Apply (Supabase Dashboard → SQL editor):
--   ALTER TABLE public.users DROP COLUMN IF EXISTS branch_id;

ALTER TABLE public.users DROP COLUMN IF EXISTS branch_id;
