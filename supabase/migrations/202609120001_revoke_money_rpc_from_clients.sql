-- Close direct-RPC bypass on money-moving functions.
--
-- settle_claim and wallet_deposit are SECURITY DEFINER with no internal actor
-- check by design — authorization lives in the Edge Functions (claims /
-- petty-cash), which call them with the service_role key. But Postgres
-- grants EXECUTE to anon/authenticated by default, so any signed-in user
-- could invoke them directly (modified APK / curl) and bypass the
-- staff/POC/accounts gates entirely. Revoke client-side execution; the
-- Edge Functions (service_role) are unaffected.
REVOKE ALL ON FUNCTION public.settle_claim(text, numeric, text, text, text, text) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.wallet_deposit(uuid, text, numeric, text, text, text, text) FROM PUBLIC, anon, authenticated;
