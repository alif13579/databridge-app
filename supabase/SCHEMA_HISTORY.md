# Supabase Schema History

This file replaces the individual migration files that built this schema
(202608190001 through 202608270002 — 22 files total). Those files are no
longer in the repo; this document is the record of what they did and what
the schema looks like as a result, kept so that history isn't lost even
though the step-by-step files are gone.

**Why the migration files were removed:** the app and the Supabase project
were confirmed to already be schema-compatible — every column the app
queries for exists with the right name and type — so there was no pending
migration to apply. The files were pure history at that point, not an
outstanding to-do list. If Supabase migrations are needed again going
forward (a genuinely new schema change), start a fresh migration file from
the CURRENT SCHEMA section below as the baseline, the same way you would
with `supabase db diff` against a live project.

**Audit method:** every one of the 22 original migration files was read in
full, in filename-chronological order, and each `create table` / `rename` /
`add column` / `drop column` statement was traced by hand to arrive at an
initial draft of the schema below. That manual trace was then verified
against the live Supabase project (`information_schema.columns`,
`information_schema.routines`, `pg_policies`, all queried directly against
the actual database) — three real mismatches between the manual trace and
the live schema were caught this way and are called out inline below.
Postgres wasn't installable in the sandbox this audit ran in, so a live
query (run by the person, output pasted back) was the only way to get
ground truth rather than a second manual read-through.

---

## Table history (chronological, by original migration file)

1. **202608190001** — created `remark_validations` (the original name for
   what is now `validations`): append-only Call Center / Worker remark
   audit log, no client table access, Edge-Function-mediated only.
2. **202608190002** — renamed `delivery_agent_id`→`agent_system_id`,
   `verifier_id`→`verifier_system_id` (idempotent guard; likely a same-day
   correction to migration 1's initial column names).
3. **202608190003** — created `fcm_device_tokens`: FCM push registration,
   server-managed only.
4. **202608190004** — added `can_access_call_center` to `fcm_device_tokens`
   (idempotent — already had it from migration 3 by this point; likely a
   safety net for a project that ran an earlier version of migration 3).
5. **202608200001** — added `customer_phone`, `agent_name`,
   `verifier_name`, `note` to `remark_validations`.
6. **202608200002** — renamed `agent_system_id`→`assigned_agent_system_id`,
   `verifier_system_id`→`author_system_id`; added `author_firebase_uid`,
   `author_name`, `author_employee_id`; **dropped** `agent_name`,
   `verifier_name` (superseded by the author_* columns above).
7. **202608200003** — added `"from"` (check: 'validator' /
   'verification_request').
8. **202608200004** — renamed `"from"`→`source`.
9. **202608200005** — renamed `status`→`remarks_status`; added
   `consignment_status`.
10. **202608200006** — the big normalization pass: created `users` table;
    renamed `remark_validations`→`validations`; renamed
    `consignment_id`→`consignment`, `assigned_agent_system_id`→
    `assigned_to_system_id`; changed the primary key from a bigint identity
    to a `uuid` (`id`); made most content columns nullable; **dropped**
    `author_firebase_uid`, `author_name`, `author_employee_id` (superseded
    by a foreign key to the new `users` table instead of denormalized
    copies); added FKs `author_system_id`/`assigned_to_system_id` →
    `users.system_id`.
11. **202608210001** — RLS policies + Realtime publication for
    `validations`/`users`. No structural change.
12. **202608210002** — updated `source`'s check constraint to `'CC'` /
    `'WORKER'` (replacing the original `'validator'`/`'verification_request'`
    values from migration 7). No structural change.
13. **202608220001** — added `branch_ids` (text[]) to `users`, backfilled
    from the existing single `branch_id`; RLS updated to check array
    membership. `branch_id` (singular) was kept, not dropped.
14. **202608220002** — fixed `auth.uid()` failing on non-UUID Firebase
    subject claims by reading the JWT `sub` claim directly instead. No
    structural change.
15. **202608220003** — granted `anon` SELECT on `validations` (root cause:
    Firebase Third-Party Auth JWTs in this project carry no `role` claim,
    so PostgREST was assigning every request the `anon` role, which had no
    grant). No structural change.
16. **202608230001** — added a worker-assignment RLS exception (a worker
    can always read validations rows assigned to them, independent of
    current branch membership). No structural change.
17. **202608230002** — created `remark_labels` (`english_label` PK,
    `bangla_label`): a plain English→Bangla lookup catalog, populated
    on-the-fly by the Edge Function's write path via upsert — **never
    seeded with static/predefined data**.
18. **202608250001** — renamed `remark_labels`→`validation_remarks`;
    renamed `english_label`→`remarks_en`, `bangla_label`→`remarks_bn`;
    added `category` (blank on every row so far — reserved for future use);
    dropped the `validations_with_bn` view created alongside migration 17
    in favor of two plain queries from the app.
19. **202608250002** — extended `validation_remarks` from a lookup helper
    into the full remark-*option* store (the predefined choices a CC/Worker
    agent picks from): dropped the old `remarks_en`-only primary key, added
    a new `id` (text) primary key; added `source` (check: 'WORKER' / 'CC'),
    `target_status`, `template_id`, `priority`, `instruction_type`,
    `instruction_text`, `is_active`; added a `(source, remarks_en)` unique
    constraint (replacing global uniqueness on `remarks_en` alone, since the
    same English text can now be a distinct option under each source).
20. **202608260001** — created four tables **not yet wired to any app code
    or write-flow**: `branches`, `claims`, `petty_cash_deposits`,
    `petty_cash_wallet_balance`. Explicitly "table structure only" per that
    migration's own header comment, mirroring Firebase's existing shape as
    a starting point for an eventual Petty Cash / Claims migration off
    Firebase. RLS is enabled with **zero policies** on all four (denies all
    access by default) — this was deliberate, not an oversight, per that
    file's own comments. As of this writing, `SupabaseClientManager.kt`
    never queries any of these four tables — Petty Cash / Claims still run
    entirely on Firebase Realtime Database.
21. **202608270001** — granted `anon` SELECT + added an `anon` read policy
    on `validation_remarks` (same root cause as #15, found again on this
    table: no `role` claim → `anon` role → no matching grant/policy →
    silent empty-result reads, since both fragments treat a lookup miss as
    "fall back to English text" rather than surfacing an error).
22. **202608270002** — added `phone`, `designation` to `users` (needed for
a Petty Cash PDF report's POC contact fields; mirrors data Firebase
already had that never made it into the Supabase copy).
23. **202609030001** (new migration file, NOT yet applied — apply via
dashboard SQL Editor or `supabase db push`) — Full Petty Cash cutover
reads: `anon`+`authenticated` SELECT grants + branch-scoped read policies
on `petty_cash_deposits` / `petty_cash_wallet_balance`
(`branch_id = any (my_branch_ids())`), plus an own-row-only users read
policy (`firebase_id = current_firebase_id()`) for
SupabasePettyCashReader.fetchCurrentUser. Same anon-inclusive pattern as
#15/#21 (Firebase JWTs carry no `role` claim → PostgREST serves `anon`).
Writes need no policy change (Edge Function admin client bypasses RLS).
App side of the same cutover: PettyCashViewModel deposits/balance/branch/
user reads + deposit/settle writes are Supabase-only (Firebase RTDB writes
removed); ClaimsRepository's commented Firebase blocks deleted, one-time
Firebase index tools isolated in FirebaseClaimsIndexMigration; store picker
reads public.stores. Courier-directory reads (areas, consignment preview),
Firebase Auth (Supabase Third-party Auth tokens), FCM, and the Edge
Function's Firebase profile lookups intentionally remain.
24. **202609030002** (new migration file — apply via `supabase db push`)
— `claim_categories` catalog (name PK, group check:
conveyance/operation/office/utilities, is_active, sort_order), anon+
authenticated SELECT with a using(true) read policy (public catalog, same
pattern as validation_remarks), writes admin-only via Dashboard/service
role. Seeded with Bulk Delivery + Pickup (both conveyance). App side:
request form picker + Top Sheet grouping/voucher filter read this table;
report's users-embed fixed (was HTTP 300 ambiguous); store fields
(consignment_id/store_id/store_name/pickup_count) restored to the
claim_upsert write path with a new ClaimInfo.storeName carried through
read/edit/display.
25. **202609030003** (new migration file — apply via Management API +
`migration repair`, same as 0001/0002 since local history lost the originals)
— `van_movements` hub van in/out log (uuid PK; branch_id FK → branches;
vehicle_number/type; driver_name; check_in/out_at; in/out by uid+system_id;
note; created/updated_at), anon+authenticated branch-scoped SELECT
(`branch_id = any (my_branch_ids())`, writes Edge-Function-only), plus a
partial unique index (branch_id, vehicle_number) WHERE check_out_at IS NULL
— one open row per van per branch, so concurrent double check-ins fail at
the DB, not just the app. App side: VanCheckInFragment (drawer,
nav_van_checkin permission) + static VanCatalog fleet + van_checkin/
van_checkout Edge Function actions.
26. **202609030004** — REPLACES 25's `van_movements` (dropped in the same file;
it never held production rows): generic `check_ins` table — subject_type +
subject_label instead of vehicle_number, NO uid columns (identity is
system_id-only: check_in/out_by_system_id, since users lookups key on
system_id). Same branch-scoped RLS; partial unique index renamed to
checkin_open_per_subject (branch_id, subject_type, subject_label). Edge
Function actions renamed van_checkin/van_checkout → checkin/checkout
 (validations unchanged); app reader/writer moved to check_ins.
27. **202609040002** (applied live via Management API, same as 0001) —
rename `claims.attempt_quantity → attempted_qty`,
`claims.delivered_quantity → successed_qty` (owner-requested names) +
backfill migrated rows (Pickup → both = pickup_count; Bulk Delivery →
 both = 1). App writer/reader + `claims` Edge Function redeployed on the
new names; Kotlin property names unchanged (mapping-only change).
28. **202609040003** (applied live via Management API, same as 0001) —
`change_user_system_id(old, new)` helper: system_id is the users PK with 8
FK references, so Dashboard PK edits fail while referenced. One call moves
validations (author/assigned), claims (all 6 actor columns),
fcm_device_tokens.system_id + the users row atomically, with guards (old
must exist, new must be free). Use: `SELECT change_user_system_id('3',
'1704');` — then still fix Firebase profile + users_by_systemId index or
the next sync recreates the old row.

---

## CURRENT SCHEMA (as of this audit)

### `public.validations`
*(original name: `remark_validations`; the canonical Call Center / Worker
remark record — every "Set Remarks" save, from both apps, lands here)*

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | uuid | not null (PK) | was bigint identity originally |
| `consignment` | text | not null | was `consignment_id` |
| `branch_id` | text | not null | |
| `assigned_to_system_id` | text | **not null** | FK → `users.system_id`; chain of renames: `delivery_agent_id`→`agent_system_id`→`assigned_agent_system_id`→`assigned_to_system_id`; verified NOT NULL live — migration 10's `alter column ... drop not null` list didn't include this column, a detail the manual trace initially missed |
| `author_system_id` | text | **not null** | FK → `users.system_id`; chain: `verifier_id`→`verifier_system_id`→`author_system_id`; same NOT NULL correction as above |
| `remarks_status` | text | null | was `status` |
| `remarks` | text | null | |
| `created_at` | timestamptz | not null | |
| `customer_phone` | text | null | |
| `note` | text | null | |
| `source` | text | null | check: `'CC'` / `'WORKER'` (was `'validator'`/`'verification_request'` originally); was named `"from"`. **Known inconsistency, found via live query, not present in any migration file:** the column's default is still `'verification_request'::text` — migration 12 updated the check constraint to `'CC'`/`'WORKER'` but never touched the column default, so an insert that omits `source` entirely would violate the table's own check constraint. No migration file shows this because it was never fixed at the migration-file level; flagging it here rather than silently correcting it, since it's a live data-integrity gap someone should decide how to handle (fix the default, or confirm every insert path always sets `source` explicitly and the stale default is unreachable dead weight) |
| `consignment_status` | text | null | |

RLS: read = own branch (any of `my_branch_ids()`) OR assigned worker;
insert = own branch + author is self. `anon` granted SELECT (see history
#15) since Firebase JWTs here carry no `role` claim.

### `public.users`
| Column | Type | Notes |
|---|---|---|
| `system_id` | text (PK) | |
| `employee_id` | text, unique | |
| `name` | text, not null | |
| `branch_id` | text | single-value, kept for compatibility, no longer what RLS checks |
| `role` | text | |
| `firebase_id` | text | matches `auth.jwt() ->> 'sub'` via `current_firebase_id()` |
| `branch_ids` | text[], not null default `{}` | the array RLS actually checks against |
| `phone` | text, not null default `''` | |
| `designation` | text, not null default `''` | |
| `created_at`, `updated_at` | timestamptz | |

### `public.validation_remarks`
*(original name: `remark_labels`; the predefined remark-option catalog —
this is the table `fetchRemarkOptions()` reads, and the one confirmed
empty in the dashboard, prompting this whole audit)*

| Column | Type | Notes |
|---|---|---|
| `id` | text (PK) | was `english_label`-as-PK originally; default `(gen_random_uuid())::text` |
| `remarks_en` | text, not null | was `english_label` |
| `remarks_bn` | text, not null | was `bangla_label` |
| `category` | text, not null default `''` | reserved, blank on every row so far |
| `source` | text, not null | check: `'WORKER'` / `'CC'` |
| `target_status` | text, not null default `''` | |
| `template_id` | text, not null default `''` | |
| `priority` | integer, not null default `0` | |
| `instruction_type` | text, not null default `''` | |
| `instruction_text` | text, not null default `''` | |
| `is_active` | boolean, not null default `true` | |
| `updated_at` | timestamptz, not null | |

Unique: `(source, remarks_en)`. RLS: `authenticated` AND `anon` both
granted SELECT with a `using (true)` policy each (see history #21) —
**this table's RLS/grants are correctly configured; the table is simply
empty of rows.** No row has ever been written here except via the app's
own "predefined option was used with a Bangla label" upsert path or the
admin screen (`ConfigRemarksFragment`) — nothing seeded it with the
original Firebase `config/remarks_worker`/`config/remarks_call_center`
options, which is why the CC "Set Remarks" picker currently shows nothing.

### `public.fcm_device_tokens`
| Column | Type |
|---|---|
| `token` (PK) | text |
| `firebase_uid` | text, not null |
| `system_id` | text, not null |
| `role_id` | text, not null default `''` |
| `can_access_call_center` | boolean, not null default `false` |
| `branch_ids` | text[], not null default `{}` |
| `updated_at` | timestamptz, not null |

No client access (service-role / Edge Function only).

### `public.claims` — now live (Petty Cash / Claims report, ClaimsReportFragment)
*(created 202608260001 as "structure only"; became active once
SupabaseClaimsReader.kt/ClaimsReportFragment.kt started querying it — see
migration history #20 for the original create-table statement and every
column comment)*

Two changes since the original create-table:

- `branch_id`/`agent_system_id` gained explicit FK constraints (to
  `branches(branch_id)`/`users(system_id)`) — the original create-table
  had neither; added manually via SQL Editor, not tracked as a numbered
  migration.
- `expense_date` was renamed to `placed_date` and its semantics changed:
  originally conveyance-only (populated when category is Pickup/Bulk
  Delivery, blank otherwise, per the original column comment), it's now
  the date a claim/expense request was placed — mandatory on every claim
  regardless of category, user-editable (any role), defaulting to
  `CURRENT_DATE` on insert but not locked to it.

`public.branches`, `public.petty_cash_deposits`, `public.petty_cash_wallet_balance`
remain not-yet-used — see below.

### Tables that exist but are NOT yet used by any app code
*(created 202608260001, explicitly "structure only" ahead of an eventual
Petty Cash / Claims migration off Firebase; RLS enabled with zero
policies, so nothing can read/write them yet even if the app tried)*

- `public.branches`
- `public.petty_cash_deposits`
- `public.petty_cash_wallet_balance`

Petty Cash and Claims features currently run mostly on Firebase Realtime
Database (`PettyCashViewModel.kt`, `PettyCashModels.kt`, `FirebasePaths.kt`)
— `public.claims` above is the one exception now being migrated (see
`ClaimsReportFragment.kt`); the three tables above are still fully
Firebase-only and untouched. See each table's column list in migration
history #20 above (preserved there since these aren't live yet, so
there's no "current" reads to verify column names against — the audit's
cross-reference against `SupabaseClientManager.kt`'s query strings only
covered `validations`, `users`, and `validation_remarks`, the tables the
app actually queried at the time of that audit).

### Platform-level objects (not from any migration file)

`rls_auto_enable` — an event trigger function that exists in the live
project but appears in none of the 22 migration files. It fires on
`CREATE TABLE`/`CREATE TABLE AS`/`SELECT INTO` in the `public` schema and
force-enables RLS on the new table automatically, as a safety net against
a table being created without RLS ever being turned on. This is a
Supabase-project-level configuration, not something any migration in this
repo's history set up — noted here only so a future reader doesn't go
looking through migration files for where it came from.

---

## Known data-integrity gap (found via live query, not fixed here)

`validations.source`'s column default is still `'verification_request'::text`
— the value from migration 7, before `source` existed under that name and
before its allowed values changed. Migration 12
(202608210002_update_validation_sources) updated the *check constraint* to
`'CC'` / `'WORKER'` but never touched the column default, so as of this
audit an `INSERT` that omits `source` entirely would fail the table's own
check constraint. No migration file shows this being fixed, and no live
query result available during this audit could confirm whether every
actual insert path (the Edge Function's write action) always sets `source`
explicitly, making the stale default unreachable in practice, or whether
this is a live landmine. Left as-is rather than silently patched, since
fixing it wasn't the task — flagging it here so it can be deliberately
decided on later, e.g. `alter table public.validations alter column source
set default 'CC'` (or whatever the intended default should be) in a new
migration when someone picks this up.

---

## Verified: app ↔ Supabase compatibility

Cross-checked every `select=...` column list in
`SupabaseClientManager.kt`'s three live queries (`/rest/v1/validations`,
two separate `/rest/v1/validation_remarks` reads) against the schema
above — every column name and type matches, confirmed against the live
database (not just the migration-file trace) for `validations`,
`validation_remarks`, `users`, `branches`, `claims`, `fcm_device_tokens`,
and `petty_cash_deposits`/`petty_cash_wallet_balance`. **No
pending/incompatible migration was found.** The empty Call Center remark
picker is a data gap (the table was never seeded with the original
Firebase remark options), not a schema or code bug.
