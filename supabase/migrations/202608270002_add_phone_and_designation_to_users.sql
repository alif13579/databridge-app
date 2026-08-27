-- public.users currently has no phone/designation columns (see 202608200006's
-- create table) — Firebase already stores these at users/{uid}/profile/phone
-- (see CallCenterFragment.kt's systemId->phone resolution and
-- EmployeeEditFragment.kt's edit form) and users/{uid}/profile/company_info/
-- designation (see UserRepository.kt), they just never made it into the
-- Supabase mirror. Needed for the "Top Sheet For Petty Cash Expense" PDF's
-- POC "Contact" and "Designation" fields, per the same discussion that added
-- branches.region and branches.petty_cash_limit.
alter table public.users add column if not exists phone text not null default '';
alter table public.users add column if not exists designation text not null default '';
