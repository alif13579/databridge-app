# Supabase deployment: users and validations

The Android app calls the `validations` Edge Function with a Firebase ID
token. (The old `remark-validations` slug is still deployed as a compat copy
for old builds — same code, see `functions/validations/index.ts`.) The function derives the canonical `author_system_id` from that verified
identity, upserts the master `users` record, and writes the activity to
`validations`. Names and employee IDs are joined from `users`; they are never
duplicated in validation rows. Mobile clients have no direct table permissions.

## One-time dashboard setup

1. Apply every file in `migrations/` in filename order. Existing deployments
   must apply `202608200006_normalize_users_and_validations.sql` before deploying
   the updated function/app. It starts the normalized `users` and
   `validations` architecture without importing legacy records.
2. In **Edge Functions**, create a function named `validations`, replace
   its source with `functions/validations/index.ts`, and deploy it.
3. In **Edge Functions → Secrets**, add these values:

   ```text
   FIREBASE_PROJECT_ID=databridgebd
   FIREBASE_DATABASE_URL=https://databridgebd-default-rtdb.asia-southeast1.firebasedatabase.app
   ```

   For instant background notifications, also create a private key in Firebase
   Console → Project settings → Service accounts and store the complete JSON
   **only** as this Supabase Edge Function secret:

   ```text
   FCM_SERVICE_ACCOUNT_JSON={...complete service-account JSON...}
   ```

   Enable **Firebase Cloud Messaging API (V1)** in the Firebase/Google Cloud
   project. Never add this JSON to Android, Git, or `local.properties`.

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
supabase functions deploy validations
```

The Edge Function supports bounded, server-side `report` queries using a
required branch and half-open date range (`start_iso` inclusive, `end_iso`
exclusive), optional validation filters, and a maximum page size of 100.

Set `FCM_SERVICE_ACCOUNT_JSON` in the Supabase Dashboard rather than placing a
private key in a shell command or repository file.
