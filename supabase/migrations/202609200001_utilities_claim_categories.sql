-- Utilities Expense categories (Expense Summary section C): the request
-- form's category picker reads claim_categories (see
-- SupabaseClaimsReader.fetchClaimCategories), so these appear in the
-- dropdown with no app release once applied. `category_group = 'utilities'`
-- drives the Top Sheet report: each gets its own summary row under
-- C. Utilities Expense and folds into the utilities total.
--
-- Fields on the form are the standard non-conveyance set (no code change):
-- Expense Date, Amount, Purpose (bill account no / month), receipt
-- attachment (max 2). No consignment/store/vehicle/from/to.
--
-- Apply with: supabase db push  (or insert via Dashboard → claim_categories)
-- Idempotent: ON CONFLICT DO NOTHING.

insert into public.claim_categories (name, category_group, is_active, sort_order) values
  ('Internet Bill', 'utilities', true, 30),
  ('Regarding Mobile Bill For QC Team Member', 'utilities', true, 40),
  ('Gas Bill', 'utilities', true, 50),
  ('Local Security Guard Bill', 'utilities', true, 60),
  ('Garbage Bill', 'utilities', true, 70),
  ('Water/Wasa Bill', 'utilities', true, 80),
  ('Cleaner Bill', 'utilities', true, 90),
  ('Transgender Bill', 'utilities', true, 100)
on conflict (name) do nothing;
