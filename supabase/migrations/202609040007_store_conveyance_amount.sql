-- 202609040007: stores.conveyance_amount (nullable numeric).
--
-- Fixed pickup conveyance payout per store. NULL = not set (request form
-- keeps old behavior: amount hidden + 0). Set (>0) = request form prefills
-- it as the requested amount, non-editable. Applied live via Management
-- API (db query).

ALTER TABLE public.stores ADD COLUMN IF NOT EXISTS conveyance_amount numeric NULL;
