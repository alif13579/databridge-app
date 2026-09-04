-- 202609040002: rename conveyance quantity columns + backfill migrated rows.
--
-- attempt_quantity -> attempted_qty, delivered_quantity -> successed_qty
-- (owner-requested names). Applied live via Management API (db query),
-- same as 202609040001 (db push refuses: early migration history missing).
--
-- Backfill (same statement, ran right after the rename):
--   Pickup        -> attempted_qty = pickup_count, successed_qty = pickup_count
--   Bulk Delivery -> attempted_qty = 1, successed_qty = 1 (one consignment each)
-- Verified: 0 mismatched rows, all Pickup rows mirror pickup_count.

ALTER TABLE public.claims RENAME COLUMN attempt_quantity TO attempted_qty;
ALTER TABLE public.claims RENAME COLUMN delivered_quantity TO successed_qty;

UPDATE public.claims
SET attempted_qty = pickup_count, successed_qty = pickup_count, updated_at = NOW()
WHERE category = 'Pickup';

UPDATE public.claims
SET attempted_qty = 1, successed_qty = 1, updated_at = NOW()
WHERE category = 'Bulk Delivery';
