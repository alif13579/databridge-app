-- Parcel Receiving + Inter Change Commission (conveyance group): the
-- request form's category picker reads claim_categories, so these appear
-- for Incharge/Staff with no app release once applied (the app also falls
-- back to built-in names offline). Conveyance form fields
-- (vehicle/from/to/quantities) come from the 'conveyance' group.
--
-- Apply with: supabase db push  (or insert via Dashboard → claim_categories)
-- Idempotent: ON CONFLICT DO NOTHING.

insert into public.claim_categories (name, category_group, is_active, sort_order) values
  ('Parcel Receiving', 'conveyance', true, 22),
  ('Inter Change Commission', 'conveyance', true, 24)
on conflict (name) do nothing;

-- Retire the plain "Inter Change" name: field trips are "Inter Change
-- Commission" now. No claim row still uses it (all renamed), so hiding it
-- from every picker/filter is safe.
update public.claim_categories set is_active = false where name = 'Inter Change';
