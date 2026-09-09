-- 202609090004: shorten validations.id + validation_remarks.id.
--
-- Both were UUIDs (36 chars). New shape: 10 letters (A-Za-z, never digits —
-- so an id can never look numeric). Same non-human family as the short area
-- ids (202609090003), letters-only here since remarks never need digits.
--
-- Reference safety (verified 2026-09-09):
--   * No FK points TO either PK (only users FKs + checks + uniques exist).
--   * App/extension/Edge use these ids opaquely: sync_run_status selects +
--     updates by id internally; admin remark CRUD passes the id through;
--     ConfigRemarksFragment matches by string equality. Nothing parses them.
--   * Edge admin_upsert_remark already generates matching 10-letter ids
--     (see validations + remark-validations functions).
--
-- NOTE: the per-row subqueries below deliberately reference the outer row
-- (length(v.id)…): it keeps them correlated so Postgres evaluates once PER
-- ROW. An uncorrelated version is pulled into an InitPlan and stamps every
-- row with the SAME id (23505) — learned the hard way on area ids.
-- Apply via Management API (db query).

-- 1) validations.id uuid -> text (PK preserved, values remapped).
ALTER TABLE public.validations
  ALTER COLUMN id TYPE text USING id::text;

UPDATE public.validations AS v SET id = (
  SELECT translate(substr(md5(gen_random_uuid()::text || v.id), 1, 10),
                   '0123456789abcdef', 'GhJkMnPqRsTvXyZA')
  WHERE length(v.id) >= 0
);

ALTER TABLE public.validations
  ALTER COLUMN id SET DEFAULT
    translate(substr(md5(gen_random_uuid()::text), 1, 10),
              '0123456789abcdef', 'GhJkMnPqRsTvXyZA');

-- 2) validation_remarks.id: remap + default (already text).
UPDATE public.validation_remarks AS r SET id = (
  SELECT translate(substr(md5(gen_random_uuid()::text || r.id), 1, 10),
                   '0123456789abcdef', 'GhJkMnPqRsTvXyZA')
  WHERE length(r.id) >= 0
);

ALTER TABLE public.validation_remarks
  ALTER COLUMN id SET DEFAULT
    translate(substr(md5(gen_random_uuid()::text), 1, 10),
              '0123456789abcdef', 'GhJkMnPqRsTvXyZA');
