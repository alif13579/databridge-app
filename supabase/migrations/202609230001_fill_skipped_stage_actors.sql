-- 202609230001: auto-attribute skipped workflow stages to the acting user.
--
-- Rule (product decision, Sep 2026): when a claim moves forward with earlier
-- stages skipped (e.g. direct settle without verify/approve, bulk-style
-- single-sitting actions), the blank actor columns of the skipped stages are
-- filled with the actor of the furthest stage present on the row. Timestamps
-- are intentionally NOT touched — a blank verified_at still means "no
-- separate verification happened", only the name gap is closed.
--
-- Only BLANK actors are ever filled; an already-stamped stage keeps its own
-- actor (staff A verifies, admin B approves → verified stays A). Rejection
-- is terminal, not a skip-forward, so rejected_by never propagates.
-- *_by_system_id columns are FKs → users(system_id); propagated values come
-- from the row's own other stage columns (already valid FKs or NULL), so the
-- trigger can never introduce an FK violation by itself.

CREATE OR REPLACE FUNCTION public.claims_fill_skipped_stage_actors()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_uid text;
  v_sys text;
BEGIN
  -- Furthest stage with an actor wins: settled > settle_in_process > approved > verified.
  IF NEW.settled_by_uid IS NOT NULL AND NEW.settled_by_uid <> '' THEN
    v_uid := NEW.settled_by_uid;
    v_sys := NEW.settled_by_system_id;
  ELSIF NEW.settle_in_process_by_uid IS NOT NULL AND NEW.settle_in_process_by_uid <> '' THEN
    v_uid := NEW.settle_in_process_by_uid;
    v_sys := NEW.settle_in_process_by_system_id;
  ELSIF NEW.approved_by_uid IS NOT NULL AND NEW.approved_by_uid <> '' THEN
    v_uid := NEW.approved_by_uid;
    v_sys := NEW.approved_by_system_id;
  ELSIF NEW.verified_by_uid IS NOT NULL AND NEW.verified_by_uid <> '' THEN
    v_uid := NEW.verified_by_uid;
    v_sys := NEW.verified_by_system_id;
  ELSE
    RETURN NEW;
  END IF;

  IF NEW.verified_by_uid IS NULL OR NEW.verified_by_uid = '' THEN
    NEW.verified_by_uid := v_uid;
    NEW.verified_by_system_id := v_sys;
  END IF;
  IF NEW.approved_by_uid IS NULL OR NEW.approved_by_uid = '' THEN
    NEW.approved_by_uid := v_uid;
    NEW.approved_by_system_id := v_sys;
  END IF;
  IF NEW.settle_in_process_by_uid IS NULL OR NEW.settle_in_process_by_uid = '' THEN
    NEW.settle_in_process_by_uid := v_uid;
    NEW.settle_in_process_by_system_id := v_sys;
  END IF;
  IF NEW.settled_by_uid IS NULL OR NEW.settled_by_uid = '' THEN
    NEW.settled_by_uid := v_uid;
    NEW.settled_by_system_id := v_sys;
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS claims_fill_skipped_stage_actors ON public.claims;
CREATE TRIGGER claims_fill_skipped_stage_actors
  BEFORE INSERT OR UPDATE ON public.claims
  FOR EACH ROW EXECUTE FUNCTION public.claims_fill_skipped_stage_actors();
