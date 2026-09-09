-- 202609090003: shorten every areas.area_id to a 10-char id.
--
-- 202609090001/02 made all ids UUIDs (36 chars — too long to read/scan).
-- This converts every area_id (UUID or otherwise) to a short non-human id
-- (10 chars, A-Za-z0-9, always containing letters) and rewrites
-- public.stores.area_id with the same value-match rule the app uses.
-- New creates allocate the same shape (see directory Edge Function).
--
-- Idempotent: re-running regenerates (only run once, right after 02).
-- Apply via Management API (db query).

CREATE TEMP TABLE area_id_remap AS
SELECT a.branch_id, a.area_id AS old_id, (
  -- NOTE: the ORDER BY references the outer row (length(a.area_id)) on
  -- purpose: it keeps the subquery correlated so Postgres evaluates it once
  -- PER ROW. An uncorrelated version is pulled up into an InitPlan and every
  -- row gets the SAME id (23505).
  SELECT string_agg(
    substr('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789',
           (floor(random() * 62) + 1)::int, 1), '' ORDER BY g + length(a.area_id))
  FROM generate_series(1, 10) g
) AS new_id
FROM public.areas AS a;

UPDATE public.areas AS a
SET area_id = m.new_id,
    updated_at = now()
FROM area_id_remap AS m
WHERE a.branch_id = m.branch_id
  AND a.area_id = m.old_id;

UPDATE public.stores AS s
SET area_id = m.new_id,
    updated_at = now()
FROM (SELECT DISTINCT old_id, new_id FROM area_id_remap) AS m
WHERE s.area_id = m.old_id;

DROP TABLE area_id_remap;
