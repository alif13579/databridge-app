-- 202609130001: add hold_class to validation_remarks.
--
-- Hold verification needs two classes of hold: "strict" (locked, confirmed no
-- delivery today — e.g. no money, not at address) vs "non-strict" (delivery
-- still possible — e.g. not answering the phone, needs follow-up). Day-end
-- "guaranteed hold %" counts strict parcels over total hold parcels.
--
-- Shape follows the table's existing conventions (plain text, '' = unset —
-- same as category/instruction_type; no CHECK so a future third class needs
-- no migration). Stored values are lowercase stable keys strict/non-strict (see
-- ConfigState.HOLD_CLASS_*); '' means unclassified. Hold class lives ONLY in
-- this catalog — agent remark saves (validations rows) never carry it.
-- Readers fall back to '' when the column is absent, so old APKs /
-- undeployed Edge Functions keep working.
--
-- Reference safety:
--   * No FK points to/from validation_remarks; admin_upsert_remark allowlists
--     each column, so unknown keys are ignored, not errors.
--   * admin_list_remarks selects '*', so the new column flows automatically.
--   * PostgREST select lists are explicit per caller — fetchRemarkOptions must
--     add hold_class to surface it to pickers (done alongside in app code).

ALTER TABLE public.validation_remarks
  ADD COLUMN IF NOT EXISTS hold_class text NOT NULL DEFAULT '';
