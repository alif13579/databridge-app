# DataBridge

**DataBridge** is an Android app for courier/delivery workforce management, built for operations running on Pathao Courier. It connects field agents, call center staff, supervisors, and admins under one platform — with role-based access control, real-time remark sync, Petty Cash management, and Google Sheets integration for delivery data.

App version: **6.0.0** (versionCode 282). Language: Kotlin, minSdk 23, target/compile 34.

---

## Where data lives (read this first)

The backend is a **hybrid — Supabase first, Firebase for the rest**. Rule of thumb:

| Feature | Source of truth |
|---|---|
| Petty Cash claims (request → settle) | Supabase `public.claims` |
| Wallet balance + deposits | Supabase `public.petty_cash_deposits`, `public.petty_cash_wallet_balance` |
| Van check-in/out log | Supabase `public.check_ins` (subject_type/label; static fleet list in `VanCatalog.kt`) |
| Claim categories catalog | Supabase `public.claim_categories` (admin-managed) |
| Branches, stores, users (synced copy) | Supabase `public.branches`, `public.stores`, `public.users` |
| CC/Worker remarks audit log | Supabase `public.validations` + `public.validation_remarks` |
| Push device registry | Supabase `public.fcm_device_tokens` (server-managed) |
| Sign-in / identity | **Firebase Auth** (+ Google Sign-In). Supabase trusts the Firebase JWT directly (Third-party Auth) |
| Delivery roster, consignments, areas, runs | **Firebase** Realtime Database (`courier/...`) |
| Config (remarks options admin, statuses, sheets, language, roles) | **Firebase** (`config/...`, `roles/...`) |
| Sessions/extensions, presence, call state | **Firebase** (`sessions/...`, `container/...`, `users/.../connections`) |
| Attachments (receipt photos/PDFs) | Cloudflare R2 (private bucket, presigned URLs via Edge Function) |
| Error logs | Firebase `error_logs/...` |

Firebase is still required (Auth + RTDB + FCM). "Supabase-only" in commit messages means the *Petty Cash claim flow*, never the whole app.

Supabase project: `jlmvpozfacpxphftzfvw` (`https://jlmvpozfacpxphftzfvw.supabase.co`).
Firebase project: `databridgebd` (RTDB `https://databridgebd-default-rtdb.asia-southeast1.firebasedatabase.app`).

---

## Key flows

### Petty Cash claim lifecycle
`pending → verified (Staff) → approved (Cash POC) → ready_to_settle → settled (Accounts)`, or `rejected` / `cancelled`. Every status change is a full-row upsert to `public.claims` via the `claim_upsert` Edge Function action — never a partial patch.

- Requester form (`PettyCashRequestCreateFragment`): category comes from the `claim_categories` catalog (admin can add more; seeded with `Bulk Delivery`, `Pickup`). Expense date defaults to today, past dates allowed, future blocked. Pickup carries quantities only (amount = 0 at request; the settled amount is set at approve/settle). Store/consignment/pickup-count are saved on the claim.
- Central logic: `PettyCashViewModel` (Supabase-only reads/writes). Claim persistence: `ClaimsRepository` → `SupabaseClaimsWriter` / `SupabaseClaimsReader`.
- **Amounts in reports are always `settled_amount`** (actual payout), and only `status == settled` rows count.

### Petty Cash report (Top Sheet PDF)
`ClaimsReportFragment`: single branch + employee/category/status filters + date range (filters `requested_at`) → `SupabaseClaimsReader.fetchClaimsForReport` → `PettyCashTopSheetPdfWriter` generates the 4-section PDF (Top Sheet, Expense Summary, Agent Acknowledgement, per-agent Conveyance Vouchers). Category sections come from the DB (distinct values + catalog groups), conveyance types appear verbatim as saved, voucher headers read `Attempted` / `Succeeded` (neutral for pickup + delivery). The branch's real POC header resolves via `branches.petty_cash_poc_uid → public.users`.

### Firebase → Supabase claims drain
Old Firebase claims are drained from **Petty Cash → Reports → Migrate Firebase Claims** (Accounts only): each claim is copied field-wise, read back, and compared on all 54 `ClaimInfo` fields — the Firebase original (claim node + index entries) is deleted **only on a 100% match**. Mismatches/errors stay in Firebase and are listed. Actor `users` rows are backfilled from Firebase first (`backfill_user`), so names resolve. Re-running drains the remainder to zero. Code: `FirebaseClaimsMigrator.kt`.

### Remarks (Call Center ↔ Worker)
- Save path: fragments → `SupabaseRemarkValidationWriter.write()` → Edge Function `write` action (derives author from the verified token, upserts `users`, inserts `validations`, triggers push). CC picker options come from `validation_remarks` (source `CC`/`WORKER`); selecting one fills its admin-written `instruction_text` into the note box (`×` clears it); the box content saves as the note.
- Live paths, in order: Supabase Realtime WebSocket → FCM data-message fallback → 1-minute badge poll. FCM carries only the consignment ID; the body is always re-fetched (RLS-protected).
- Push targeting (`sendRemarkPush`): Worker→CC = CC devices with `can_access_call_center` overlapping the branch; CC→Worker = the assigned worker's `system_id`. Token rows refresh on every remark save and re-register on login/token-rotation (with backoff retry); sign-out unregisters. Code: `DataBridgeMessagingService`, `AppNotificationManager`, `SupabaseRealtimeManager`.

### Attachments
Request form → `AttachmentUploader` (5 MB / image-or-PDF check + JPEG compression) → presigned PUT from `r2-attachment-upload` → R2 object key stored as `claims.attachment_url`. Viewing goes through a fresh presigned GET each time (bucket is private). R2 credentials live only in the function's secrets.

### Auth model (important)
Supabase has no passwords here: every REST/Realtime/Function call carries the **Firebase ID token**. Because these JWTs carry no `role` claim, PostgREST serves requests as `anon` — so RLS policies and grants cover `anon, authenticated`. The Edge Function sets a `role=authenticated` custom claim on first sync (`ensureAuthenticatedRoleClaim`); the app force-refreshes its cached token once per process afterwards.

---

## Backend reference

### Edge Functions (`supabase/functions/`)
- **`remark-validations`** (`verify_jwt = false` — it verifies the Firebase JWT itself): `sync_profile`, `register_push_token`, `unregister_push_token`, `backfill_user`, `write`, `report`, `claim_upsert`, `petty_cash_deposit_upsert`, `petty_cash_wallet_balance_upsert`, `admin_list_remarks`, `admin_upsert_remark`, `admin_delete_remark`, `admin_migrate_status_remarks`.
- **`r2-attachment-upload`** (`verify_jwt = false`): `upload` (presigned PUT), `download` (presigned GET).

### Database
- `supabase/migrations/` holds real migration files (including RLS policies + `claim_categories` + seeds). `supabase/SCHEMA_HISTORY.md` is the human-readable history of how the schema got here — read it before touching tables.
- Direct PostgREST reads are free/unlimited; Edge Function invocations are not — prefer REST for reads (see `SupabaseClientManager.fetchValidations` pattern).

### Secrets (what goes where)
| Secret | Where | Notes |
|---|---|---|
| `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` | `local.properties` (gitignored) / CI `-P` flags | Publishable key is public by design; it ships in the APK |
| `sbp_...` access token | env var `SUPABASE_ACCESS_TOKEN` only, never committed | For `supabase login/link/db push/functions deploy` |
| `sb_secret_...` / service-role key | Supabase server side only | NEVER in the app, Gradle, or Git |
| `FCM_SERVICE_ACCOUNT_JSON`, `R2_*`, `FIREBASE_*` | Edge Function secrets (`supabase secrets set` / Dashboard) | Never in the repo |
| `google-services.json` | `app/` (gitignored — ask an admin for a copy) | Required to build |
| `keystore.properties` | repo root (gitignored) | Release signing; without it builds fall back to debug signing |

---

## Working with this repo

### New-machine setup
1. Clone, then get `google-services.json` into `app/` and create `local.properties` (see `local.properties.example`).
2. Install tooling without sudo: `supabase` CLI → `~/.local/bin`, Node 20 LTS → `~/.node` (this Mac has no Homebrew; official installer needs an admin password).
3. `export SUPABASE_ACCESS_TOKEN=<sbp_...>` → `supabase link --project-ref jlmvpozfacpxphftzfvw` → `supabase db push` → `supabase functions deploy remark-validations r2-attachment-upload`.
4. `./gradlew assembleDebug` (first run warms the Gradle cache; ~2 min after that).
5. Admin adds claim categories via Dashboard → Table Editor → `claim_categories` (no app release needed).

### Conventions that prevent real incidents
- **Amount shown anywhere = `settled_amount`.** Requested/approved are intermediate figures only.
- **Claim writes are full-row upserts.** A partial map must be applied onto the loaded full claim first (`applyUpdates`) or missing columns blank out.
- **`upsertUser` rule:** `firebase_id` is a required param — never write NULL (it silently breaks RLS for that user). Identity content always comes from server-side Firebase reads, never client input.
- **Run `assembleDebug` before pushing.** Kotlin gotchas that bit before: `/*` inside KDoc swallows the file (nested comments); `launch`/`delay` need explicit imports even with qualified `GlobalScope`.
- This repo gets worked on by multiple sessions/branches in parallel. Before branching, run `git branch -a` (two branches were once built for the same feature in parallel). Pull before pushing every time; `git branch --merged` is not proof a branch is done.
- Tokens/keys pasted in chat must be rotated afterwards.

---

## Repo map (where to look)

- `app/src/main/java/com/cloudx/databridge/` — all app code (~150 files)
  - Petty Cash: `PettyCashViewModel`, `ClaimsRepository`, `SupabaseClaimsWriter/Reader`, `SupabasePettyCashWriter/Reader`, `PettyCash*Fragment`, `ClaimsReportFragment`, `PettyCashTopSheetPdfWriter`, `FirebaseClaimsMigrator`, `FirebaseClaimsIndexMigration`
  - Remarks/push: `SupabaseRemarkValidationWriter`, `SupabaseClientManager`, `SupabaseRealtimeManager`, `DataBridgeMessagingService`, `AppNotificationManager`, `CallCenterFragment`, `WorkerSpaceFragment`
  - Platform: `DataBridgeApplication`, `MainActivity`, `AuthManager`, `RbacManager`, `AttachmentUploader`, `FirebasePaths`
- `supabase/functions/` — Edge Function source (deployed separately, see setup)
- `supabase/migrations/` + `supabase/SCHEMA_HISTORY.md` — schema truth
- `database.rules.json` + `database.rules.README.md` — Firebase RTDB rules
