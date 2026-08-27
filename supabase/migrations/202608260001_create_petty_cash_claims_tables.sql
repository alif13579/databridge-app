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
  -- Not in Firebase's Branch.kt (a Petty Cash report requirement, not a
  -- pre-existing branch attribute) — the "Region Name" printed on the "Top
  -- Sheet For Petty Cash Expense" PDF's POC-details block (e.g. "ISD").
  -- Free text, not yet a fixed list — no region directory exists to
  -- validate against (unlike branch_type, which has none either, or
  -- courier/areas' delivery_area/pickup_area, which do).
  region               text not null default '',
  address              text not null default '',
  latitude             double precision not null default 0,
  longitude            double precision not null default 0,
  email                text not null default '',
  phone                text not null default '',
  manager_uid          text not null default '',
  accountant_uid       text not null default '',
  accountant_role      text not null default '',
  petty_cash_poc_uid   text not null default '',
  -- Not in Firebase's Branch.kt — the "Petty cash limit" figure on the "Top
  -- Sheet For Petty Cash Expense" PDF (e.g. 5000), which "Cash in hand" and
  -- "Over expenditure" are calculated against (limit - total_cost; a negative
  -- result is the over-expenditure amount) — see the same PDF discussion that
  -- added region above. Per-branch, since different hubs can carry different
  -- limits, same as region.
  petty_cash_limit     numeric not null default 0,
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
  -- Free text, not yet a hard enum (a check constraint would need the write-flow's
  -- validation logic settled first) — but the intended fixed set, per the "Top
  -- Sheet For Petty Cash Expense" PDF's Expense Summary page, is exactly these
  -- 18 (the PDF's 3 "Others Exp…" catch-all rows are deliberately excluded —
  -- purpose above covers that free-text case instead of a fixed category):
  --   Operation Expense group:
  --     Parcel Sending Cost (Hub To Hub), Parcel Receiving Cost (Van/Point To Hub),
  --     Pickup Van Parking, Cycle Parking Bill, Toll Fee
  --     — NOT in this list: Parcel Pickup Conveyance / Parcel Delivery Conveyance /
  --     Bulk Parcel Delivery Conveyance, which the PDF derives from `type` (Pickup /
  --     Delivery / Bulk Delivery) instead of being their own category value.
  --   Office Maintenance Cost group:
  --     Print And Photocopy Cost, Office Accessories Purchase, Internet Connection
  --     Cost, CCTV Setup And Maintenance Cost, IPS Set-Up And Servicing Cost
  --   Utilities Expense group:
  --     Internet Bill, Regarding Mobile Bill For QC Team Member, Gas Bill,
  --     Local Security Guard Bill, Garbage Bill, Water/Wasa Bill, Cleaner Bill,
  --     Transgender Bill
  category                    text not null default '',
  purpose                     text not null default '',
  consignment_id              text not null default '',
  store_id                    text not null default '',
  store_name                  text not null default '',
  pickup_count                integer not null default 0,
  -- Conveyance fields — populated when category is one of the two conveyance
  -- categories (Pickup / Bulk Delivery, see type above); blank/0 for every
  -- other category. vehicle is a fixed 3-option list (CNG, Paddle Van, Auto).
  --
  -- from_area/to_area store either an areaId (from courier/areas/pickup_area
  -- or courier/areas/delivery_area — ConfigAreasFragment's Area.areaId, which
  -- of the two directories depends on category) or the literal 'OFFICE'
  -- sentinel, which isn't a real area-directory entry. Which side defaults to
  -- 'OFFICE' — and which side is prefilled from the selected store — depends
  -- on category:
  --   Pickup:        to_area defaults 'OFFICE' (a pickup always ends at the
  --                   office). from_area is prefilled from the selected
  --                   store's Store.areaId (every store has one, per Store's
  --                   shape above) but is a normal pickup_area dropdown — the
  --                   requester can freely change it to any pickup_area entry,
  --                   not just the store's own area.
  --   Bulk Delivery:  from_area defaults 'OFFICE' (a bulk delivery always
  --                   starts at the office). to_area is a plain delivery_area
  --                   dropdown with no store to prefill from (Bulk Delivery
  --                   has no store_id/store_name — Consignment ID instead).
  --
  -- attempt_quantity/delivered_quantity/cid_or_merchant/expense_date mirror
  -- the per-line fields on the "Top Sheet For Petty Cash Expense" PDF's
  -- Conveyance Voucher pages (Attempt quantity / Delivered / CID / Merchant /
  -- Date columns) — expense_date is the date printed on that voucher line,
  -- deliberately separate from created_at/requested_at (when the claim
  -- record itself was created), since a claim can be filed for conveyance
  -- that happened on an earlier date.
  vehicle                     text not null default '',
  from_area                   text not null default '',
  to_area                     text not null default '',
  attempt_quantity            integer not null default 0,
  delivered_quantity          integer not null default 0,
  cid_or_merchant             text not null default '',
  expense_date                date,
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
