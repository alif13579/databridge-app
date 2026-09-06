-- 202609050005: claim-lifecycle guards (audit findings #3 idempotent settle,
-- #5 duplicate-submit idempotency key).
--
-- 1. claims.client_submit_id (nullable text): UUID generated per claim form by
--    new app builds. The claims Edge returns the existing row instead of
--    inserting a duplicate when the key repeats (double-tap / retry safe).
--    Old builds omit it (NULL) — the Edge's time-window fallback covers those.
-- 2. settle_claim() RPC: atomic settle (claim row + wallet balance under row
--    locks). claim_upsert routes EVERY settle_in_process→settled transition
--    through it, so even old APKs settle atomically. Retry with the same
--    transaction_id after a settled write returns ok(duplicate) instead of
--    deducting twice.
-- 3. wallet_deposit() RPC: atomic deposit (deposit row + wallet bump).
--    Idempotent on deposits.id — a retried deposit inserts nothing and bumps
--    nothing. New app builds call it via the petty-cash Edge; deposit_upsert
--    keeps working for old builds (now assignee-gated + server-computed
--    balance) by delegating to this same function.
ALTER TABLE public.claims ADD COLUMN IF NOT EXISTS client_submit_id text NULL;
CREATE INDEX IF NOT EXISTS claims_client_submit_id_idx ON public.claims (client_submit_id);

CREATE OR REPLACE FUNCTION public.settle_claim(
  p_claim_id text,
  p_amount numeric,
  p_payment_method text,
  p_transaction_id text,
  p_actor_uid text,
  p_actor_system_id text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  v_claim public.claims%ROWTYPE;
  v_balance numeric;
  v_approved numeric;
BEGIN
  IF p_claim_id IS NULL OR p_claim_id = '' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'claim_id is required');
  END IF;
  IF p_amount IS NULL OR p_amount < 0 THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Settled amount cannot be negative');
  END IF;
  SELECT * INTO v_claim FROM public.claims WHERE id = p_claim_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Claim not found');
  END IF;
  -- Retry-safe: same transaction settled already → ok without touching money.
  IF v_claim.status = 'settled' AND v_claim.transaction_id = COALESCE(p_transaction_id, '') THEN
    RETURN jsonb_build_object('ok', true, 'duplicate', true, 'claim_id', v_claim.id);
  END IF;
  IF v_claim.status <> 'settle_in_process' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Only settle-in-process claims can be settled (current: ' || COALESCE(v_claim.status, '?') || ')');
  END IF;
  v_approved := COALESCE(v_claim.approved_amount, 0);
  IF v_approved > 0 AND p_amount > v_approved THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Settled amount cannot exceed the approved amount');
  END IF;
  SELECT balance INTO v_balance FROM public.petty_cash_wallet_balance
    WHERE branch_id = v_claim.branch_id FOR UPDATE;
  IF NOT FOUND THEN
    v_balance := 0;
    INSERT INTO public.petty_cash_wallet_balance (branch_id, balance, updated_at)
      VALUES (v_claim.branch_id, 0, now());
  END IF;
  IF v_balance < p_amount THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Insufficient wallet balance');
  END IF;
  UPDATE public.petty_cash_wallet_balance
    SET balance = balance - p_amount, updated_at = now()
    WHERE branch_id = v_claim.branch_id;
  UPDATE public.claims SET
    status = 'settled',
    settled_amount = p_amount,
    payment_method = COALESCE(NULLIF(p_payment_method, ''), payment_method),
    transaction_id = COALESCE(NULLIF(p_transaction_id, ''), transaction_id),
    settled_by_uid = COALESCE(NULLIF(p_actor_uid, ''), settled_by_uid),
    settled_by_system_id = COALESCE(NULLIF(p_actor_system_id, ''), settled_by_system_id),
    settled_at = COALESCE(settled_at, now()),
    updated_at = now()
    WHERE id = p_claim_id;
  RETURN jsonb_build_object('ok', true, 'duplicate', false, 'claim_id', p_claim_id, 'new_balance', v_balance - p_amount);
END;
$$;

CREATE OR REPLACE FUNCTION public.wallet_deposit(
  p_id uuid,
  p_branch_id text,
  p_amount numeric,
  p_source text,
  p_reference text,
  p_remarks text,
  p_uid text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  v_inserted boolean := false;
  v_balance numeric;
BEGIN
  IF p_id IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'deposit id is required');
  END IF;
  IF p_branch_id IS NULL OR p_branch_id = '' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'branch_id is required');
  END IF;
  IF p_amount IS NULL OR p_amount <= 0 THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Deposit amount must be greater than 0');
  END IF;
  -- Idempotency: a retried deposit (same id) inserts nothing and bumps nothing.
  INSERT INTO public.petty_cash_deposits
    (id, branch_id, amount, source, reference, remarks, balance_after, entered_by_uid, created_at)
    VALUES (p_id, p_branch_id, p_amount, COALESCE(p_source, ''), COALESCE(p_reference, ''),
      COALESCE(p_remarks, ''), 0, COALESCE(p_uid, ''), now())
    ON CONFLICT (id) DO NOTHING;
  GET DIAGNOSTICS v_inserted = ROW_COUNT;
  IF v_inserted THEN
    INSERT INTO public.petty_cash_wallet_balance (branch_id, balance, updated_at)
      VALUES (p_branch_id, p_amount, now())
      ON CONFLICT (branch_id) DO UPDATE
        SET balance = public.petty_cash_wallet_balance.balance + EXCLUDED.balance,
            updated_at = now();
  END IF;
  SELECT balance INTO v_balance FROM public.petty_cash_wallet_balance WHERE branch_id = p_branch_id;
  UPDATE public.petty_cash_deposits SET balance_after = v_balance
    WHERE id = p_id AND v_inserted;
  RETURN jsonb_build_object('ok', true, 'duplicate', NOT v_inserted, 'new_balance', v_balance);
END;
$$;
