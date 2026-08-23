-- A delivery worker must always be able to read validation rows addressed to that
-- worker.  Branch membership is still valid for Call Center users, but it is not a
-- reliable worker entitlement: an active run can retain its branch assignment while
-- the worker profile is changed, and that previously made CC -> worker pushes arrive
-- successfully but the follow-up REST/Realtime read return an RLS-filtered [] result.
--
-- Keep branch-scoped visibility for CC and add the narrowest worker exception: only
-- rows whose assigned_to_system_id is the Firebase-authenticated user's system ID.
drop policy if exists "validations_read_own_branch" on public.validations;
create policy "validations_read_own_branch"
on public.validations for select
using (
  branch_id = any (public.my_branch_ids())
  or assigned_to_system_id = public.my_system_id()
);
