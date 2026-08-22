-- Firebase Third-party Auth JWTs currently arrive without a `role` claim in
-- this project, so PostgREST assigns the anon database role. The validations
-- SELECT policy still limits rows to the caller's branch_ids; grant only the
-- table privilege needed for that RLS policy to evaluate.
grant select on public.validations to anon;
