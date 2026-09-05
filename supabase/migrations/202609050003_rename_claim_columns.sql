-- 202609050003: rename claims columns for better naming (data-preserving).
--
-- successed_qty  -> succeeded_qty  (typo)
-- staff_*        -> verified_*     (the Verify stage is done by Staff role)
-- poc_approved_* -> approved_*      (approved_at already had the clean name)
-- settle_in_process_* -> ready_to_settle_* (matches the ready_to_settle status)
-- worker_uid/role -> requester_uid/role (matches requester_system_id)
-- remarks        -> purpose        (what the column actually holds; avoids
--                                  confusion with validations.remarks)
-- FK constraint names follow their columns. RLS policies reference only
-- branch_id/requester_system_id (untouched). Old app versions keep working:
-- the claims Edge Function accepts both old and new payload keys.
-- Applied live via Management API (db query).

ALTER TABLE public.claims RENAME COLUMN successed_qty TO succeeded_qty;
ALTER TABLE public.claims RENAME COLUMN staff_by_uid TO verified_by_uid;
ALTER TABLE public.claims RENAME COLUMN staff_by_system_id TO verified_by_system_id;
ALTER TABLE public.claims RENAME COLUMN staff_at TO verified_at;
ALTER TABLE public.claims RENAME COLUMN staff_comment TO verified_comment;
ALTER TABLE public.claims RENAME COLUMN poc_approved_by_uid TO approved_by_uid;
ALTER TABLE public.claims RENAME COLUMN poc_approved_by_system_id TO approved_by_system_id;
ALTER TABLE public.claims RENAME COLUMN poc_comment TO approved_comment;
ALTER TABLE public.claims RENAME COLUMN settle_in_process_by_uid TO ready_to_settle_by_uid;
ALTER TABLE public.claims RENAME COLUMN settle_in_process_by_system_id TO ready_to_settle_by_system_id;
ALTER TABLE public.claims RENAME COLUMN settle_in_process_at TO ready_to_settle_at;
ALTER TABLE public.claims RENAME COLUMN worker_uid TO requester_uid;
ALTER TABLE public.claims RENAME COLUMN worker_role TO requester_role;
ALTER TABLE public.claims RENAME COLUMN remarks TO purpose;

ALTER TABLE public.claims RENAME CONSTRAINT claims_staff_by_system_id_fkey TO claims_verified_by_system_id_fkey;
ALTER TABLE public.claims RENAME CONSTRAINT claims_poc_approved_by_system_id_fkey TO claims_approved_by_system_id_fkey;
ALTER TABLE public.claims RENAME CONSTRAINT claims_settle_in_process_by_system_id_fkey TO claims_ready_to_settle_by_system_id_fkey;

-- change_user_system_id must follow its columns (same behavior, new names).
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
  c_claims_ready int := 0;
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
  UPDATE claims SET ready_to_settle_by_system_id = p_new WHERE ready_to_settle_by_system_id = p_old;
  GET DIAGNOSTICS c_claims_ready = ROW_COUNT;
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
    'claims_ready_to_settle', c_claims_ready,
    'claims_settled', c_claims_settled,
    'claims_rejected', c_claims_rejected,
    'device_tokens', c_tokens
  );
END;
$$;
