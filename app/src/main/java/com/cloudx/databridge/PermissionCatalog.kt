package com.cloudx.databridge

/** Central permission catalog (key, title, description). */
object PermissionCatalog {
    data class Perm(val key: String, val title: String, val description: String)

    val all: List<Perm> = listOf(
        Perm("nav_dashboard",      "Dashboard",       "View dashboard"),
        Perm("nav_my_tasks",       "My Tasks",        "See assigned tasks"),
        Perm("nav_reports",        "Reports",         "View reports"),
        Perm("nav_settings",       "Settings",        "Access app settings"),
        Perm("nav_support",        "Support",         "View support/help"),
        Perm("nav_space",          "Space",           "Space module (Worker view)"),
        Perm("nav_call_center",    "Call Center",     "Call Center (Agent/Supervisor view)"),
        Perm("nav_connect",        "Connect",         "Connect to extension/device"),
        Perm("nav_history",        "History",         "View history"),
        Perm("nav_scanner",        "Scanner",         "Parcel scanner feature"),
        Perm("nav_memory",         "Memory",          "Earnings memory for workers"),
        Perm("nav_salary_manager", "Salary Manager",  "Manage salary slabs & rates"),
        Perm("nav_access_manager", "Access Manager",  "Manage roles & permissions"),
        Perm("nav_branches",       "Branches",        "View branches / My branch"),
        Perm("nav_team",           "Employees",       "View and manage employees"),
        Perm("nav_config",         "Config",          "App config: remarks, language, statuses, sheets"),
    )

    /** Helper to get a mutable permissions map with defaults (false). */
    fun blankPermissions(): MutableMap<String, Boolean> = all.associate { it.key to false }.toMutableMap()

    fun defaultPermissions(): Map<String, Boolean> = all.associate { it.key to false }
}
