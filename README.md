# DataBridge

**DataBridge** is an Android app for courier/delivery workforce management, built for operations running on Pathao Courier. It connects field agents, call center staff, supervisors, and admins under one platform — with real-time Firebase sync, role-based access control, and Google Sheets integration for delivery data.

---

## Features

### Role-Based Access Control (RBAC)
Every screen and action is gated by a permission system. Roles include `admin`, `supervisor`, `staff`, `worker/agent`, and `guest`. Permissions are managed per-role in Firebase and enforced at runtime via `RbacManager`. The `AccessManagerFragment` lets admins toggle permissions per role without a new app release.

### Worker Space
The primary view for delivery agents. Shows their assigned parcels for the day — customer name, phone, address, COD amount, consignment ID, and status. Data is filtered by the logged-in user's `employee_id` (from `company_info` in Firebase), so each agent sees only their own parcels. Planned: live sync from Google Sheets.

### Call Center
A dialer-focused view for call center agents. Displays parcel records with one-tap calling, remark logging, and status updates. Integrates with the auto-dial helper and tracks call history locally via Room database, with optional Firebase sync.

### Config *(admin / supervisor / staff)*
A 4-tab settings panel for branch-level configuration:

- **Remarks** — Manage predefined remark options per delivery status (Delivered, Return, Hold, etc.) in Bengali and English.
- **Language** — Set the display language for Worker and Call Center fragments independently (remark language + status language).
- **Statuses** — Add, edit, or delete custom delivery statuses beyond the built-in set. Each status has a name (Bengali + English), color, and priority.
- **Sheet** — Connect a Google Sheet per branch as the delivery data source. Supports a 4-step OAuth flow (Google account → Drive sheet picker → tab selector → column range), Manual and Auto-detect column mapping, branch-wise sync intervals, and full audit history in Firebase.

### Scanner
Barcode/QR scanner for parcel validation. Supports single and batch scan modes with camera or manual entry. Scan results are logged with timestamps.

### Branch Management
Admins can create, view, and edit branches. Supervisors and staff see their assigned branch detail. Each branch has its own sheet config and sync settings.

### Employee Management
View and manage employees per branch. Admins see all employees across all branches; supervisors see their branch. Employee profiles include role, `employee_id`, branch assignment, and contact info.

### Salary Manager
Configure salary slabs and piecework rates for agents. Slabs are branch-aware and editable by authorized roles.

### History
Unified activity log showing call records, parcel status changes, and sync events. Filterable by date and type.

### Memory
Earnings tracker for agents. Logs completed deliveries with COD amounts to help agents track daily/weekly earnings.

### Connect
Links the app to a browser extension via Firebase session. Supports permanent (Google-authenticated) and guest sessions. Session state is monitored in real-time; disconnect triggers automatic cleanup.

### Settings
App-level settings including dark mode toggle, Google account link/unlink, and local data management.

---

## Architecture

| Layer | Tech |
|---|---|
| Language | Kotlin |
| UI | XML layouts, Fragments, Navigation Drawer + Bottom Nav |
| Backend | Firebase Realtime Database |
| Auth | Firebase Auth + Google Sign-In |
| Local DB | Room (call records) |
| Background Sync | WorkManager (planned: sheet sync) |
| Networking | OkHttp (Google Sheets / Drive API) |
| Image loading | Coil |

---

## Firebase Structure

```
users/{uid}/
  profile/         ← display name, email, lastActive
  company_info/    ← role, branch_ids, employee_id

roles/{roleId}/
  permissions/     ← map of nav_* keys → boolean

branches/{branch_id}/
  name, ...

config/
  remarks/{statusKey}/[]     ← remark list per status
  language/workerLang        ← worker fragment language
  language/ccLang            ← call center language
  statusMeta/{key}/          ← custom status definitions
  sheets/{branch_id}/
    current/                 ← active sheet config + column mapping
    history/{push_id}/       ← audit log of every change
    data/rows/{consignment}/ ← synced sheet rows

sessions/{extId}/            ← browser extension sessions
container/container_{uid}/   ← permanent data container
```

---

## RBAC Permissions

| Key | Description |
|---|---|
| `nav_dashboard` | Dashboard |
| `nav_my_tasks` | My Tasks |
| `nav_space` | Worker Space |
| `nav_call_center` | Call Center |
| `nav_connect` | Connect to extension |
| `nav_history` | History |
| `nav_scanner` | Scanner |
| `nav_memory` | Memory / earnings |
| `nav_salary_manager` | Salary Manager |
| `nav_access_manager` | Access Manager (role editor) |
| `nav_branches` | Branch view/management |
| `nav_team` | Employee management |
| `nav_config` | Config (remarks, language, statuses, sheets) |
| `nav_reports` | Reports |
| `nav_settings` | Settings |
| `nav_support` | Support |

---

## Google Sheets Integration

Config → Sheet tab allows authorized users to connect a Google Sheet per branch as the delivery roster source.

**Flow:**
1. Choose branch
2. Connect → Google OAuth popup (Drive + Sheets readonly scopes)
3. Select spreadsheet from Drive
4. Select tab/worksheet
5. Define column range and map columns (Manual or Auto-detect from headers)
6. Set sync interval (branch-wide, reflected on all agents' devices)

**Column mapping fields:** Agent ID, Consignment ID, Customer Name, Phone, Address, COD, Status, Note

**Data path:** `config/sheets/{branch_id}/data/rows/{consignment_id}/`

Each agent's Worker Space shows only rows where the Agent ID column matches their `employee_id`.

All config changes are written to `history/` with timestamp, user name, role, and action (`created` / `updated` / `disconnected`).

---

## Version

Current: **2.7**
