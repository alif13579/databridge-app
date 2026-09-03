package com.cloudx.databridge

/** Central permission catalog (key, title, description). */
object PermissionCatalog {
    data class Perm(val key: String, val title: String, val description: String)

    val all: List<Perm> = listOf(
        Perm("nav_dashboard",      "Dashboard",       "View dashboard"),
        Perm("nav_my_tasks",       "My Tasks",        "See assigned tasks"),
        Perm("nav_approvals",      "Approvals",       "View and manage approvals"),
        Perm("nav_reports",        "Reports",         "View reports"),
        Perm("nav_settings",       "Settings",        "Access app settings"),
        Perm("nav_support",        "Support",         "View support/help"),
        Perm("nav_space",          "Space",           "Space module (Worker view)"),
        Perm("nav_call_center",    "Call Center",     "Call Center (Agent/Supervisor view)"),
        Perm("nav_virtual_routing","Virtual Routing", "Manually assign a delivery area to a parcel (Call Center)"),
        Perm("nav_connect",        "Connect",         "Connect to extension/device"),
        Perm("nav_history",        "History",         "View history"),
        Perm("nav_scanner",        "Scanner",         "Parcel scanner feature"),
        Perm("nav_memory",         "Memory",          "Earnings memory for workers"),
        Perm("nav_chat",           "Messages",        "Chat with other users"),
        Perm("nav_salary_manager", "Salary Manager",  "Manage salary slabs & rates"),
        Perm("nav_access_manager", "Access Manager",  "Manage roles & permissions"),
        Perm("nav_branches",       "Branches",        "View branches / My branch"),
        Perm("nav_team",           "Employees",       "View and manage employees"),
        Perm("nav_config",         "Config",          "App config: remarks, language, statuses, sheets"),
        Perm("nav_cash_management","Cash Management", "Branch cash collection & MFS reconciliation"),
        Perm("nav_petty_cash",     "Petty Cash",      "Worker convenience bill requests & approval chain"),
        Perm("petty_cash_requester", "Requester", "Can submit new petty cash requests (e.g. Pickup Agent, Delivery Agent)"),
        Perm("nav_leave_management","Leave Management","Leave requests & Incharge/Shift Lead approval chain"),
        Perm("leave_requester", "Requester", "Can submit new leave requests (e.g. Pickup Agent, Delivery Agent)"),
        Perm("nav_van_checkin",  "Van Check-In",  "Hub van arrival/departure log (check in/out with timestamps)"),
    )

    /**
     * Sub-permissions shown nested under their parent in Access Manager,
     * only enabled/visible once the parent permission is checked.
     *
     * Petty Cash: Requester lives under Petty Cash — the other petty cash
     * roles (Staff / Cash POC / Accountant) are deliberately NOT here,
     * since those are per-branch individual (or per-branch role) assignments
     * made from Branch Edit, not a global role-wide permission toggle.
     * "Staff" (staff_uid/staff_role, isStaff, etc.) was formerly named
     * "Team Aligned" -- fully renamed since no production data existed
     * under the old names. Branch Edit lets Staff be set to either a
     * specific person OR a role (any active holder of that role can then
     * act), so this permission catalog intentionally doesn't duplicate
     * that choice as a third, global on/off switch.
     *
     * Leave Management: Requester lives under Leave Management the same
     * way. Its Incharge/Shift Lead queues are NOT permission-catalog
     * entries either, but for the opposite reason from Petty Cash — they
     * ARE meant to be role-wide (every Incharge/Shift Lead at a branch can
     * act), so they're resolved purely by role NAME match in
     * LeaveViewModel.resolveRoles(), no permission toggle needed at all.
     */
    val childrenOf: Map<String, List<Perm>> = mapOf(
        "nav_petty_cash" to all.filter { it.key == "petty_cash_requester" },
        "nav_leave_management" to all.filter { it.key == "leave_requester" }
    )

    private val childKeys: Set<String> = childrenOf.values.flatten().map { it.key }.toSet()

    /** Top-level permissions only — excludes anything nested under childrenOf, for the main list UI. */
    val topLevel: List<Perm> = all.filter { it.key !in childKeys }

    /** Helper to get a mutable permissions map with defaults (false). */
    fun blankPermissions(): MutableMap<String, Boolean> = all.associate { it.key to false }.toMutableMap()

    fun defaultPermissions(): Map<String, Boolean> = all.associate { it.key to false }
}
