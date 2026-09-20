-- Inter Change category (hub-to-hub trip conveyance, e.g. Sonargaon<>Madanpur).
-- Group conveyance so the Top Sheet folds it into Operation total + Conveyance
-- Voucher pages like Bulk Delivery. Requester-facing (Pickup / Bulk Delivery /
-- LOT Delivery / Inter Change); staff see every other category.
--
-- Storage needs no schema change: claims.vehicle / from_area / to_area carry
-- the trip, cid_or_merchant holds the "From<>To" route label (display-only),
-- attempted_qty / succeeded_qty = 1 per request. The app form hides the
-- consignment row for this category (a trip has no consignment ID).
--
-- Apply with: supabase db push  (or insert via Dashboard → claim_categories)
-- Idempotent: ON CONFLICT DO NOTHING.

insert into public.claim_categories (name, category_group, is_active, sort_order) values
  ('Inter Change', 'conveyance', true, 27)
on conflict (name) do nothing;
