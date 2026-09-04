-- 202609040004: drop claims.type (always mirrored category — verified
-- type = category on every row before dropping). Category (admin catalog)
-- is the single source now. Applied live via Management API (db query).

ALTER TABLE public.claims DROP COLUMN IF EXISTS type;
