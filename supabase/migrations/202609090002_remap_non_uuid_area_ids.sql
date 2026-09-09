-- 202609090002: remap every non-UUID areas.area_id to UUIDs.
--
-- Continues 202609090001 (which covered pure-numeric ids): pickup areas
-- still carry Firebase push-keys (-P-…) and legacy acronyms (BA, GDJS).
-- After this, every area_id in every branch is a UUID — one pattern.
--
-- Reference safety (verified before writing this):
--   * public.stores.area_id references areas by VALUE string compare (no FK;
--     the claim/store forms match store areas against the directory by
--     area_id). Updated below with the same rule, so nothing points stale.
--   * claims From/To keep display-name snapshots — untouched.
--   * Firebase courier/{stores,areas,areas_backup} have NO live readers
--     (pickers read Supabase; extension touches only courier/consignments).
--     Delete those three nodes in the Firebase console afterwards so a
--     manual Reports → Sync directory drain can never resurrect stale ids.
--   * UNIQUE (branch_id, area_id) preserved: mapping is per (branch, old).
--     NOTE: stores has no branch column, so its update matches on value
--     globally — consistent with the app's own matching rule.
--
-- Idempotent: re-running finds no non-UUID rows and changes nothing.

CREATE TEMP TABLE area_id_remap AS
SELECT branch_id, area_id AS old_id, gen_random_uuid()::text AS new_id
FROM public.areas
WHERE area_id !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

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
