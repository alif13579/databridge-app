-- LOT Delivery category (Bulk-like conveyance, but one request covers MULTIPLE
-- consignments scanned/typed + added). Group conveyance so the Top Sheet
-- folds it into Operation total + Conveyance Voucher pages like Bulk Delivery.
--
-- Storage needs no schema change: claims.consignment_id / cid_or_merchant hold
-- the comma-joined IDs (display-only in petty cash context), attempted_qty /
-- succeeded_qty = consignment count. The app form (LOT mode) builds the list.
--
-- Apply with: supabase db push  (or insert via Dashboard → claim_categories)
-- Idempotent: ON CONFLICT DO NOTHING.

insert into public.claim_categories (name, category_group, is_active, sort_order) values
  ('LOT Delivery', 'conveyance', true, 25)
on conflict (name) do nothing;
