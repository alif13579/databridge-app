alter table public.remark_validations
  rename column status to remarks_status;

alter table public.remark_validations
  add column if not exists consignment_status text not null default '';
