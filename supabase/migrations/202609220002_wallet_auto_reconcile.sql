-- Auto-reconcile petty_cash_wallet_balance (deposits MINUS settled).
--
-- Why: the wallet is delta-maintained by the settle_claim / wallet_deposit
-- RPCs, so any direct claims/deposits edit (bulk corrections, backfills,
-- deletes) silently drifted it (once found at +2548 instead of -1300).
-- These triggers recompute the branch balance from source tables after
-- every relevant change, so ALL write paths (RPC, Edge, direct SQL, app)
-- converge on the same number with no double-count possible (recompute,
-- never delta):
--   balance = SUM(deposits) - SUM(settled_amount WHERE status='settled')
--
-- settle_claim RPC stays correct: its delta bump lands first, then the
-- claims UPDATE fires this trigger and recomputes to the identical value
-- (its returned new_balance is unchanged).
--
-- SECURITY DEFINER (owner) so the wallet write never trips RLS, whichever
-- role edits the claim. Fixed body, no parameters from callers.

CREATE OR REPLACE FUNCTION public.reconcile_wallet_balance(p_branch_id text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF p_branch_id IS NULL OR p_branch_id = '' THEN
    RETURN;
  END IF;
  INSERT INTO public.petty_cash_wallet_balance AS w (branch_id, balance, updated_at)
  SELECT p_branch_id,
         (SELECT COALESCE(SUM(amount), 0) FROM public.petty_cash_deposits WHERE branch_id = p_branch_id)
         - (SELECT COALESCE(SUM(settled_amount), 0) FROM public.claims
            WHERE branch_id = p_branch_id AND status = 'settled'),
         now()
  ON CONFLICT (branch_id) DO UPDATE
    SET balance = EXCLUDED.balance, updated_at = now();
END;
$$;

CREATE OR REPLACE FUNCTION public.trg_claims_wallet_reconcile()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status = 'settled' THEN
      PERFORM public.reconcile_wallet_balance(OLD.branch_id);
    END IF;
    RETURN OLD;
  ELSIF TG_OP = 'INSERT' THEN
    IF NEW.status = 'settled' THEN
      PERFORM public.reconcile_wallet_balance(NEW.branch_id);
    END IF;
    RETURN NEW;
  ELSE -- UPDATE: only settled-amount/status/branch moves can change the sum
    IF OLD.branch_id IS DISTINCT FROM NEW.branch_id
      OR OLD.status IS DISTINCT FROM NEW.status
      OR OLD.settled_amount IS DISTINCT FROM NEW.settled_amount THEN
      PERFORM public.reconcile_wallet_balance(OLD.branch_id);
      IF NEW.branch_id IS DISTINCT FROM OLD.branch_id THEN
        PERFORM public.reconcile_wallet_balance(NEW.branch_id);
      END IF;
    END IF;
    RETURN NEW;
  END IF;
END;
$$;

DROP TRIGGER IF EXISTS trg_claims_wallet_reconcile ON public.claims;
CREATE TRIGGER trg_claims_wallet_reconcile
  AFTER INSERT OR UPDATE OR DELETE ON public.claims
  FOR EACH ROW EXECUTE FUNCTION public.trg_claims_wallet_reconcile();

CREATE OR REPLACE FUNCTION public.trg_deposits_wallet_reconcile()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    PERFORM public.reconcile_wallet_balance(OLD.branch_id);
    RETURN OLD;
  ELSIF TG_OP = 'INSERT' THEN
    PERFORM public.reconcile_wallet_balance(NEW.branch_id);
    RETURN NEW;
  ELSE
    IF OLD.branch_id IS DISTINCT FROM NEW.branch_id
      OR OLD.amount IS DISTINCT FROM NEW.amount THEN
      PERFORM public.reconcile_wallet_balance(OLD.branch_id);
      IF NEW.branch_id IS DISTINCT FROM OLD.branch_id THEN
        PERFORM public.reconcile_wallet_balance(NEW.branch_id);
      END IF;
    END IF;
    RETURN NEW;
  END IF;
END;
$$;

DROP TRIGGER IF EXISTS trg_deposits_wallet_reconcile ON public.petty_cash_deposits;
CREATE TRIGGER trg_deposits_wallet_reconcile
  AFTER INSERT OR UPDATE OR DELETE ON public.petty_cash_deposits
  FOR EACH ROW EXECUTE FUNCTION public.trg_deposits_wallet_reconcile();
