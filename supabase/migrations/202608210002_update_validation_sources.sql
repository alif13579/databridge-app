alter table public.validations
  drop constraint if exists remark_validations_from_check;

alter table public.validations
  drop constraint if exists validations_source_check;

alter table public.validations
  add constraint validations_source_check
  check (source in ('CC', 'WORKER'));
