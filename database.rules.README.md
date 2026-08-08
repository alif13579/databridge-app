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
Write is allowed only for one of these transitions, each checked against
the corresponding role field on `branches/{branchId}`:

| From status | To status | Allowed by |
|---|---|---|
| *(new)* | `pending_team_align` | the request's own `workerUid` (self-submit only), **and** the submitter's role must have `petty_cash_requester: true` under `roles/{role_id}/permissions` (checked by resolving `users/{uid}/profile/company_info/role_id` then looking up that role's permissions) |
| `pending_team_align` | `pending_poc` | `branches/{branchId}/team_aligned_uid` |
| `pending_poc` | `approved` | `branches/{branchId}/petty_cash_poc_uid` |
| `approved` | `settled` | `branches/{branchId}/accountant_uid` |
| `pending_team_align` | `rejected` | `branches/{branchId}/team_aligned_uid` |
| `pending_poc` | `rejected` | `branches/{branchId}/petty_cash_poc_uid` |

Every transition additionally requires `workerUid`, `branchId`, `amount`,
and `category` to stay unchanged from the existing record — this stops a
compromised or buggy client from smuggling in an amount change disguised
as a status transition.

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

## Deploying

```bash
firebase deploy --only database
```

(requires the Firebase CLI authenticated against this project; not run as
part of this change — coordinate with whoever manages the Firebase
project before deploying, since these rules will start enforcing
restrictions that may not have existed before.)
