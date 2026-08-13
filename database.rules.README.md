# Firebase Realtime Database Rules

`database.rules.json` (repo root) is the full production rules file,
covering every top-level node in the database (`users`, `roles`,
`branches`, `chats`, `memory`, `cash_management`, `courier`, `petty_cash`,
and others) — kept in the repo so rule changes go through the same
review/history as app code, instead of being edited ad-hoc in the Firebase
Console with no record of what changed or why.

Kept as strict single-line-string JSON so it validates with any plain
JSON parser and deploys cleanly via `firebase deploy --only database`.

**This README only documents the `petty_cash` node in detail** (the
feature this repo's recent history has been building). The other nodes
follow simple, consistent patterns — mostly `auth != null` reads with
role_id checks (`admin`/`manager`/`supervisor`/etc, read from
`users/{uid}/profile/company_info/role_id`) gating writes — readable
directly from the rules file itself. If you change the rules, update
both the file and this doc.

## Known limitation: `branch_ids` membership can't be checked in rules

`users/{uid}/profile/company_info/branch_ids` is stored as a **List**
(`setValue(List<String>)` from Kotlin), which Firebase RTDB stores as an
index-keyed array (`{"0": "branchA", "1": "branchB"}`), not a map keyed by
branch ID (`{"branchA": true}`). RTDB rules can't search array *values* —
only navigate by known child key. So `hasChild($branchId)` does not work
here, and no rule in this file can verify "is $branchId in this user's
branch_ids". Because of that, **read access below is intentionally loose**
(`auth != null` — any signed-in user can read `branches/*` and
`petty_cash/*/requests`, wallet balance, and deposits for any branch).

This is a deliberate tradeoff, not an oversight: the sensitive operation
in this feature is *writing* (approving/settling/moving money), which
every write rule below does gate correctly by role. Read-side branch
isolation would require migrating `branch_ids` to a map — a separate,
larger refactor touching every file that reads/writes it
(`RbacManager`, `BranchEditFragment`, `BranchCreateFragment`,
`BranchListFragment`, `DashboardViewModel`, `EmployeeFragment`,
`EmployeeEditFragment`) — deliberately not bundled into this change.

## Fixed bug: `enteredByUid` validate rules must distinguish create from edit

`cash_management/{branchId}/collections`, `.../handovers`, and
`.../hub_payments` each `.validate` that `enteredByUid` is present and
numeric-amount-positive. It used to also require
`newData.child('enteredByUid').val() === auth.uid` unconditionally --
correct on *create* (stops spoofing someone else as the submitter), but
wrong on *edit*: `CashManagementViewModel.updateCollection` /
`updateLedgerEntry` intentionally use `updateChildren()` on just the
changed fields, leaving the original `enteredByUid` alone so the audit
trail still shows who originally entered it. That meant anyone editing
an entry they didn't personally submit -- an admin correcting a
branch's deposit, for instance -- got rejected by the rule, not the app.

Fixed by branching on `data.exists()`: on create, `enteredByUid` must
equal `auth.uid`; on edit, it just has to match whatever it already
was (`data.child('enteredByUid').val()`), which also means an editor
can't reassign attribution to themselves while changing other fields.
Verified against the literal rule strings in this file (Node.js
mini-evaluator standing in for the Firebase emulator, which needs
`storage.googleapis.com` to fetch and wasn't reachable) -- both the
create-spoof and attribution-tamper cases still correctly fail.

## Rule-by-rule

**`users/{uid}`** — a user can only read/write their own profile node.
`branch_ids` and `role_id` under `company_info` are readable by any
signed-in user (needed so e.g. Branch pickers can resolve other people's
names/roles when assigning Team Aligned / Cash POC / Accountant).

**`roles/{roleId}`** — readable by any signed-in user (role names need to
resolve for display), not writable via rules (managed through the admin
flow / Console directly; no self-service role editing yet).

**`branches/{branchId}`** — readable by any signed-in user (see limitation
above), not writable via client rules — branch data changes go through
existing app flows w/ their own logic, kept locked down at the rules level.

**`petty_cash/{branchId}/requests/{requestId}`** — the core state machine.
Write is allowed for one of these transitions/actions, each checked
against the corresponding role field on `branches/{branchId}`:

| From status | To status | Allowed by |
|---|---|---|
| *(new)* | `pending` | the request's own `workerUid` (self-submit only), **and** the submitter's role must have `petty_cash_requester: true` under `roles/{role_id}/permissions` (checked by resolving `users/{uid}/profile/company_info/role_id` then looking up that role's permissions) |
| `pending` | *(deleted)* | the request's own `workerUid`, only while still `pending` |
| `pending` | `pending` (edit) | the request's own `workerUid`, only while still `pending` — `workerUid`/`branchId` must stay the same; category/amount/purpose can change |
| `pending` | `acknowledged` | `branches/{branchId}/team_aligned_uid` |
| `acknowledged` | `approved` | `branches/{branchId}/petty_cash_poc_uid` |
| `approved` | `settle_in_process` | `branches/{branchId}/accountant_uid` |
| `settle_in_process` | `settled` | `branches/{branchId}/accountant_uid` |
| `pending` | `rejected` | `branches/{branchId}/team_aligned_uid` |
| `acknowledged` | `rejected` | `branches/{branchId}/petty_cash_poc_uid` |

Every approval-chain transition (everything except the Requester's own
edit) additionally requires `workerUid`, `branchId`, `amount`, and
`category` to stay unchanged from the existing record — this stops a
compromised or buggy client from smuggling in an amount change disguised
as a status transition. The Requester's own edit is the one path allowed
to change `amount`/`category`/`purpose`, but only while status is still
`pending` and only by the original submitter.

No rule permits `settled -> anything` or `rejected -> anything` — those
are terminal states.

**`petty_cash/{branchId}/wallet/balance`** — only `accountant_uid` can
write (deposits and settlements both go through
`PettyCashViewModel`'s Firebase transactions, which run as that user).

**`petty_cash/{branchId}/wallet/deposits/{depositId}`** — only
`accountant_uid` can create (never edit/delete — `!data.exists()` blocks
overwriting an existing deposit record), and the deposit's own
`enteredByUid` must match the writer.

## Who can be a "Requester"?

Unlike Team Aligned / Petty Cash POC / Accountant (branch-specific
assignments on `branches/{branchId}`), submitting a new request is gated
by a **company-wide role permission**: `petty_cash_requester` under
`PermissionCatalog`. An admin grants this to whichever job-title roles
should be able to file requests (e.g. Pickup Agent, Delivery Agent) via
the existing Access Manager screen — no separate UI was built for this,
since Access Manager already lets an admin toggle any permission key
per-role, and `petty_cash_requester` is just one more entry in that same
catalog. Multiple roles can hold it simultaneously.

App-side, `PettyCashRequestCreateFragment` checks
`RbacManager.hasPermission("petty_cash_requester")` before rendering the
form. The rules file re-checks the same thing server-side on write (see
the `roles/{role_id}/permissions/petty_cash_requester` lookup in the
create-transition rule above) so a compromised or modified client can't
bypass the app-side check.

## `courier/merchants` — the Pickup category's merchant directory

Petty Cash's Request Create screen shows a Merchant Name picker when the
category is Pickup. That list is read from `courier/merchants/{merchantId}`
(each entry just `{ name: "..." }`) rather than a Petty-Cash-specific
node, since merchants are a courier-wide concept — the same list can be
reused if merchant selection shows up elsewhere in the courier flow later.
Read is open to any signed-in user (needed by the picker); write is
restricted to admin/manager, since this is a shared reference list, not
something every Requester should be able to add entries to.

## Deploying

```bash
firebase deploy --only database
```

(requires the Firebase CLI authenticated against this project; not run as
part of this change — coordinate with whoever manages the Firebase
project before deploying, since these rules will start enforcing
restrictions that may not have existed before.)
