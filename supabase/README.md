# Supabase deployment: remark validations

The Android app calls the `remark-validations` Edge Function with a Firebase ID
token. The function verifies the token, confirms that a write's `verifier_id`
matches that signed-in user's Firebase profile, then accesses Postgres with its
server-only Supabase key. Mobile clients have no direct table permissions.

## One-time dashboard setup

1. In **SQL Editor**, run the contents of
   `migrations/202608190001_create_remark_validations.sql`.
2. In **Edge Functions**, create a function named `remark-validations`, replace
   its source with `functions/remark-validations/index.ts`, and deploy it.
3. In **Edge Functions → Secrets**, add these values:

   ```text
   FIREBASE_PROJECT_ID=databridgebd
   FIREBASE_DATABASE_URL=https://databridgebd-default-rtdb.asia-southeast1.firebasedatabase.app
   ```

4. Set **Verify JWT** to off for this function. This is required because the
   request carries a Firebase JWT, not a Supabase Auth JWT; the function
   verifies Firebase's signature, issuer, audience, expiry, and subject itself.

Supabase-provided server keys stay in the Edge Function environment. Do not add
or paste a `SUPABASE_SERVICE_ROLE_KEY` or a `sb_secret_` key anywhere in this
repository or into the Android app.

## CLI alternative

After `supabase login` and `supabase link --project-ref <project-ref>`:

```sh
supabase secrets set FIREBASE_PROJECT_ID=databridgebd FIREBASE_DATABASE_URL=https://databridgebd-default-rtdb.asia-southeast1.firebasedatabase.app
supabase db push
supabase functions deploy remark-validations
```
