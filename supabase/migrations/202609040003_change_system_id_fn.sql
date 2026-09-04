-- 202609040003: reusable system_id changer for public.users.
--
-- system_id is the users PK with 8 FK references (validations x2, claims
-- x6), so Dashboard PK edits fail while any row references the old id.
-- This function moves everything atomically: all referencing columns +
-- fcm_device_tokens.system_id (no FK but push targeting reads it) +
-- the users row itself. Applied live via Management API (db query).
--
-- Use (Supabase Dashboard → SQL editor):
--   SELECT change_user_system_id('3', '1704');
-- Returns per-table moved counts. Raises on: old missing, new already
-- used, blank/same ids. Safe to re-run (second run raises "not found").
--
-- ALSO update Firebase afterwards or the next login/remark sync recreates
-- the old row: users/<uid>/profile/company_info/system_id plus the
-- users_by_systemId index (delete old key, add new key → {uid}).

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
  c_claims_staff int := 0;
  c_claims_poc int := 0;
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
  UPDATE claims SET staff_by_system_id = p_new WHERE staff_by_system_id = p_old;
  GET DIAGNOSTICS c_claims_staff = ROW_COUNT;
  UPDATE claims SET poc_approved_by_system_id = p_new WHERE poc_approved_by_system_id = p_old;
  GET DIAGNOSTICS c_claims_poc = ROW_COUNT;
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
    'claims_staff', c_claims_staff,
    'claims_poc', c_claims_poc,
    'claims_settle_in_process', c_claims_sip,
    'claims_settled', c_claims_settled,
    'claims_rejected', c_claims_rejected,
    'device_tokens', c_tokens
  );
END;
$$;
