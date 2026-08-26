-- Petty Cash / Claims — table structure only, ahead of the actual data/write-flow
-- migration off Firebase. Mirrors Firebase's current shape exactly (see
-- ClaimsModels.kt's ClaimInfo, PettyCashModels.kt's PettyCashDeposit, Branch.kt,
-- and FirebasePaths.kt's claim*/pettyCash*/branch* paths) so the eventual
-- migration is close to a field-for-field copy rather than a redesign-while-migrating.
--
-- Four tables, matching Firebase's four distinct roots:
--   claims/{claimId}/info                       -> claims (below)
--   petty_cash/{branchId}/wallet/deposits/{id}   -> petty_cash_deposits (below)
--   petty_cash/{branchId}/wallet/balance         -> petty_cash_wallet_balance (below)
--   branches/{branchId}                          -> branches (below)
--
-- Deliberately NOT migrated: claims/indexes/claims_by_branchId and
-- claims_by_systemId (ClaimsRepository's manual Firebase index trees). A SQL
-- WHERE clause with an index on branch_id/agent_system_id replaces both —
-- see the indexes below. No Edge Function, RLS write policy, or data copy is
-- part of this migration; those come with the actual write-flow cutover.
--
-- Every *_name paired with a *_uid/*_id/*_system_id column in Firebase (branch_name,
-- employee_name, staff_by_name, poc_approved_by_name, settle_in_process_by_name,
-- settled_by_name, rejected_by_name, entered_by_name, manager_name, etc.) is
-- deliberately NOT a column here — public.users already has name keyed by
-- system_id (and branches.name below covers branch_id), so every one of these
-- is a join/lookup at read time instead of a stored, driftable copy. Firebase
-- stored them denormalized because Realtime Database has no server-side JOIN;
-- Postgres does, so there's no reason to carry that duplication forward.

-- ── branches ─────────────────────────────────────────────────────────────
-- Mirrors Branch.kt exactly. manager_uid/accountant_uid/petty_cash_poc_uid/
-- staff_uid are Firebase uids (not system_id) — kept as bare text reference
-- columns, same as Firebase, rather than a foreign key to users, since users
-- is keyed by system_id there and a uid->system_id join would need
-- firebase_id, an unindexed lookup at that table currently.
create table if not exists public.branches (
  branch_id            text primary key,
  branch_code          text not null default '',
  name                 text not null default '',
  branch_type          text not null default '',
  address              text not null default '',
  latitude             double precision not null default 0,
  longitude            double precision not null default 0,
  email                text not null default '',
  phone                text not null default '',
  manager_uid          text not null default '',
  accountant_uid       text not null default '',
  accountant_role      text not null default '',
  petty_cash_poc_uid   text not null default '',
  staff_uid            text not null default '',
  staff_role           text not null default '',
  parent_branch_id     text not null default '',
  status               text not null default 'active',
  image_url            text not null default '',
  created_by           text not null default '',
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now()
  -- updated_log (Branch.kt's Map<String, UpdateLogEntry> audit trail) has no
  -- column here — an unbounded, ever-growing map is a poor fit for a single
  -- row's column; if/when this table gets a real write-flow, that history
  -- becomes its own table (branch_update_log or similar) keyed on branch_id,
  -- not a repeat of Firebase's shape.
);

alter table public.branches enable row level security;
-- No policies yet — see claims' note below; same reasoning (no write-flow
-- wired to this table yet).

-- ── claims ───────────────────────────────────────────────────────────────
-- The canonical request/approval record — ClaimInfo lives once in Firebase
-- ("It intentionally lives only once", per that file's doc comment) and
-- should stay that way here: this table IS the record, not an index or a
-- cache of it. PettyCashRequest (PettyCashModels.kt) is a display-only
-- adapter over this shape (see ClaimInfo.asPettyCashRequest()) and has no
-- table of its own — there is nothing in it that isn't already a field here.
create table if not exists public.claims (
  id                          text primary key,        -- Firebase's claim_{timestamp} kept as-is for now, so
                                                          -- imported rows keep their original id unchanged.
  claim_code                  text not null,
  branch_id                   text not null,
  employee_id                 text not null default '',
  -- Canonical unique filter/index key (digits-only system_id) — see ClaimInfo's
  -- doc comment on why this exists separately from employee_id (which can
  -- contain spaces, making it unsafe as a Firebase key; that constraint
  -- doesn't apply here, but the same column keeps the eventual copy 1:1).
  -- Also the join key into users for employee_name — see the note above.
  agent_system_id             text not null default '',
  type                        text not null default '',
  category                    text not null default '',
  purpose                     text not null default '',
  consignment_id              text not null default '',
  store_id                    text not null default '',
  store_name                  text not null default '',
  pickup_count                integer not null default 0,
  requested_amount            numeric not null default 0,
  approved_amount             numeric not null default 0,
  settled_amount              numeric not null default 0,
  payment_method              text not null default '',
  transaction_id              text not null default '',
  status                      text not null default 'pending',
  priority                    text not null default 'normal',
  -- R2 object key, not a URL (the bucket is private) — matches
  -- PettyCashRequest.attachmentUrl's naming and doc comment exactly, kept
  -- here under the same misleading-but-established name for a 1:1 field
  -- mapping at migration time.
  attachment_url              text not null default '',
  attachment_name             text not null default '',
  worker_uid                  text not null default '',
  worker_role                 text not null default '',
  requested_at                timestamptz,
  approved_at                 timestamptz,
  settled_at                  timestamptz,
  created_at                  timestamptz not null default now(),
  updated_at                  timestamptz not null default now(),
  staff_by_uid                text not null default '',
  staff_at                    timestamptz,
  staff_comment                text not null default '',
  poc_approved_by_uid          text not null default '',
  poc_comment                  text not null default '',
  settle_in_process_by_uid     text not null default '',
  settle_in_process_at         timestamptz,
  settled_by_uid                text not null default '',
  rejected_by_uid               text not null default '',
  rejected_at                   timestamptz,
  reject_reason                  text not null default ''
);

create unique index if not exists claims_claim_code_key on public.claims (claim_code);
-- Replaces Firebase's claims_by_branchId manual index tree — a plain WHERE
-- branch_id = ? [AND created_at BETWEEN ? AND ?] covers ClaimsRepository.search()'s
-- range-scan pattern without a separate index structure to keep in sync.
create index if not exists claims_branch_id_created_at_idx on public.claims (branch_id, created_at);
-- Replaces claims_by_systemId — same reasoning, for searchMyClaims()'s per-agent scan.
create index if not exists claims_agent_system_id_created_at_idx on public.claims (agent_system_id, created_at);
create index if not exists claims_status_idx on public.claims (status);

alter table public.claims enable row level security;
-- No policies yet — RLS is enabled with zero policies, which denies all access
-- by default. Deliberate for a table with no write-flow wired to it yet: safer
-- to enable and add real policies at the write-flow migration than to leave
-- this open in the meantime. See validations'/validation_remarks' migrations
-- for the read/write policy pattern this will likely follow.

-- ── petty_cash_deposits ──────────────────────────────────────────────────
-- One row per fund deposit into a branch's petty cash wallet. Mirrors
-- PettyCashDeposit exactly; Firebase's push()-generated id becomes a plain
-- generated primary key here.
create table if not exists public.petty_cash_deposits (
  id               uuid primary key default gen_random_uuid(),
  branch_id        text not null,
  amount           numeric not null,
  source           text not null default '',   -- Cash, Bank, Adjustment
  reference        text not null default '',
  remarks          text not null default '',
  balance_after    numeric not null default 0,
  entered_by_uid   text not null default '',
  created_at       timestamptz not null default now()
);

create index if not exists petty_cash_deposits_branch_id_created_at_idx
  on public.petty_cash_deposits (branch_id, created_at);

alter table public.petty_cash_deposits enable row level security;
-- No policies yet — see claims' note above; same reasoning.

-- ── petty_cash_wallet_balance ────────────────────────────────────────────
-- One row per branch: the current running balance. Firebase stores this as a
-- single Double at petty_cash/{branchId}/wallet/balance, updated atomically via
-- runTransaction() (PettyCashViewModel.depositFund()) — a plain `UPDATE ... SET
-- balance = balance + $1` here is equally atomic under Postgres's normal
-- transaction guarantees, no special handling needed at the eventual write-flow
-- migration.
create table if not exists public.petty_cash_wallet_balance (
  branch_id   text primary key,
  balance     numeric not null default 0,
  updated_at  timestamptz not null default now()
);

alter table public.petty_cash_wallet_balance enable row level security;
-- No policies yet — see claims' note above; same reasoning.
