-- 202609130001: add hold_class to validation_remarks.
--
-- Hold verification needs two classes of hold: HARD (confirmed no delivery
-- today — e.g. no money, not at address) vs SOFT (uncertain — e.g. not
-- answering the phone, may still deliver). Day-end "guaranteed hold %" counts
-- HARD parcels over total hold parcels.
--
-- Shape follows the table's existing conventions (plain text, '' = unset —
-- same as category/instruction_type; no CHECK so a future third class needs
-- no migration). Stored values are stable keys HARD/SOFT (see
-- ConfigState.HOLD_CLASS_*); '' means unclassified (free-text legacy rows,
-- non-hold remarks). Readers fall back to '' when the column is absent, so
-- old APKs / undeployed Edge Functions keep working.
--
-- Reference safety:
--   * No FK points to/from validation_remarks; admin_upsert_remark allowlists
--     each column, so unknown keys are ignored, not errors.
--   * admin_list_remarks selects '*', so the new column flows automatically.
--   * PostgREST select lists are explicit per caller — fetchRemarkOptions must
--     add hold_class to surface it to pickers (done alongside in app code).

ALTER TABLE public.validation_remarks
  ADD COLUMN IF NOT EXISTS hold_class text NOT NULL DEFAULT '';
