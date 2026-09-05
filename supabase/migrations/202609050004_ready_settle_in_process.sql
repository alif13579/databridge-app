-- 202609050004: ready_to_settle_* columns back to settle_in_process_*,
-- and the status value 'ready_to_settle' back to 'settle_in_process'
-- (preferred term). Data-preserving. The 15 live rows on the old value move
-- with it. change_user_system_id() follows. Claims Edge normalizes old
-- 'ready_to_settle' payloads (6.9.5 builds) on write.
-- Applied live via Management API (db query).

ALTER TABLE public.claims RENAME COLUMN ready_to_settle_by_uid TO settle_in_process_by_uid;
ALTER TABLE public.claims RENAME COLUMN ready_to_settle_by_system_id TO settle_in_process_by_system_id;
ALTER TABLE public.claims RENAME COLUMN ready_to_settle_at TO settle_in_process_at;
ALTER TABLE public.claims RENAME CONSTRAINT claims_ready_to_settle_by_system_id_fkey TO claims_settle_in_process_by_system_id_fkey;
UPDATE public.claims SET status = 'settle_in_process', updated_at = now() WHERE status = 'ready_to_settle';

CREATE OR REPLACE FUNCTION public.change_user_system_id(p_old text, p_new text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  c_validations_author int := 0;
  c_validations_assigned int := 0;
  c_claims_requester int := 0;
  c_claims_verified int := 0;
  c_claims_approved int := 0;
  c_claims_sip int := 0;
  c_claims_settled int := 0;
  c_claims_rejected int := 0;
  c_tokens int := 0;
BEGIN
  IF p_old IS NULL OR btrim(p_old) = '' THEN
    RAISE EXCEPTION 'old system_id is required';
  END IF;
  IF p_new IS NULL OR btrim(p_new) = '' THEN
    RAISE EXCEPTION 'new system_id is required';
  END IF;
  IF p_old = p_new THEN
    RAISE EXCEPTION 'old and new system_id are the same';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM users WHERE system_id = p_old) THEN
    RAISE EXCEPTION 'system_id % not found in users', p_old;
  END IF;
  IF EXISTS (SELECT 1 FROM users WHERE system_id = p_new) THEN
    RAISE EXCEPTION 'system_id % already used by another user', p_new;
  END IF;

  UPDATE validations SET author_system_id = p_new WHERE author_system_id = p_old;
  GET DIAGNOSTICS c_validations_author = ROW_COUNT;
  UPDATE validations SET assigned_to_system_id = p_new WHERE assigned_to_system_id = p_old;
  GET DIAGNOSTICS c_validations_assigned = ROW_COUNT;

  UPDATE claims SET requester_system_id = p_new WHERE requester_system_id = p_old;
  GET DIAGNOSTICS c_claims_requester = ROW_COUNT;
  UPDATE claims SET verified_by_system_id = p_new WHERE verified_by_system_id = p_old;
  GET DIAGNOSTICS c_claims_verified = ROW_COUNT;
  UPDATE claims SET approved_by_system_id = p_new WHERE approved_by_system_id = p_old;
  GET DIAGNOSTICS c_claims_approved = ROW_COUNT;
  UPDATE claims SET settle_in_process_by_system_id = p_new WHERE settle_in_process_by_system_id = p_old;
  GET DIAGNOSTICS c_claims_sip = ROW_COUNT;
  UPDATE claims SET settled_by_system_id = p_new WHERE settled_by_system_id = p_old;
  GET DIAGNOSTICS c_claims_settled = ROW_COUNT;
  UPDATE claims SET rejected_by_system_id = p_new WHERE rejected_by_system_id = p_old;
  GET DIAGNOSTICS c_claims_rejected = ROW_COUNT;

  UPDATE fcm_device_tokens SET system_id = p_new, updated_at = NOW() WHERE system_id = p_old;
  GET DIAGNOSTICS c_tokens = ROW_COUNT;

  UPDATE users SET system_id = p_new, updated_at = NOW() WHERE system_id = p_old;

  RETURN jsonb_build_object(
    'old', p_old, 'new', p_new,
    'validations_author', c_validations_author,
    'validations_assigned', c_validations_assigned,
    'claims_requester', c_claims_requester,
    'claims_verified', c_claims_verified,
    'claims_approved', c_claims_approved,
    'claims_settle_in_process', c_claims_sip,
    'claims_settled', c_claims_settled,
    'claims_rejected', c_claims_rejected,
    'device_tokens', c_tokens
  );
END;
$$;
