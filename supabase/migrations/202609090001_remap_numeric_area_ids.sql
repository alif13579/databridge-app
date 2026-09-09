-- 202609090001: remap pure-numeric areas.area_id to UUIDs.
--
-- New area creates now allocate crypto UUIDs (see directory Edge Function
-- area_upsert), like the rest of the directory (push-keys, acronyms). This
-- one-time remap converts the old sequential ids ('1'..'6') so every area_id
-- is non-human.
--
-- Reference safety (verified 2026-09-09 before writing this):
--   * public.stores.area_id references areas by VALUE (no FK constraint);
--     the claim/store forms match store areas against the directory by
--     area_id string compare. No store row uses a numeric area_id today, but
--     the stores update below covers any that ever does — same match rule as
--     the app, so nothing can point at a stale value afterwards.
--   * claims From/To keep display-name snapshots (not area_id) — untouched.
--   * courier/areas_backup/{branch}/{areaId} Firebase mirror keys are
--     write-only (no app reader); stale numeric keys left behind are
--     harmless orphans. Next area_upsert for that area writes the new key.
--   * UNIQUE (branch_id, area_id) is preserved: mapping is per (branch, old).
--
-- Idempotent: re-running finds no ^[0-9]+$ rows and changes nothing.
-- Apply via Management API (db query), same as 202609050002.

CREATE TEMP TABLE area_id_remap AS
SELECT branch_id, area_id AS old_id, gen_random_uuid()::text AS new_id
FROM public.areas
WHERE area_id ~ '^[0-9]+$';

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
