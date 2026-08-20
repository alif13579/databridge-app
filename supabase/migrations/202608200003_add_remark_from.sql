-- Identifies the workflow direction of each immutable remark.
-- "validator" = Call Center validation; "verification_request" = worker asks
-- the Call Center to verify the parcel.
alter table public.remark_validations
  add column if not exists "from" text not null default 'verification_request';

alter table public.remark_validations
  drop constraint if exists remark_validations_from_check;

alter table public.remark_validations
  add constraint remark_validations_from_check
  check ("from" in ('validator', 'verification_request'));
