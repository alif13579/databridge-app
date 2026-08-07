# Firebase Realtime Database Rules — Petty Cash

`database.rules.json` (repo root) is the deployable rules file — kept as
strict single-line-string JSON so it validates with any plain JSON parser
and deploys cleanly via `firebase deploy --only database`. This file
explains the logic in readable form; if you change the rules, update both.

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
| *(new)* | `pending_team_align` | the request's own `workerUid` (self-submit only) |
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

## Deploying

```bash
firebase deploy --only database
```

(requires the Firebase CLI authenticated against this project; not run as
part of this change — coordinate with whoever manages the Firebase
project before deploying, since these rules will start enforcing
restrictions that may not have existed before.)
