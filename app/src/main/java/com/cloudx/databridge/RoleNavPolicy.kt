package com.cloudx.databridge

/**
 * Navigation policy by role.
 *
 * - worker → SpaceFragment (WorkerParcelView)
 * - agent, stuff, supervisor → CallCenterFragment
 * - admin → AccessManager + full menu
 * - guest/logged-out → Connect + History
 */
object RoleNavPolicy {
    fun showWorkerSpace(roleId: String): Boolean = roleId == "worker" || roleId == "admin"

    fun showCallCenter(roleId: String): Boolean =
        roleId == "admin" || roleId == "agent" || roleId == "stuff" || roleId == "supervisor"

    fun showConnectHistory(roleId: String): Boolean =
        roleId != "worker" && !showCallCenter(roleId)

    /** Returns the fragment class that should be shown for Space/primary nav */
    fun getSpaceOrPrimaryFragment(roleId: String): Class<out androidx.fragment.app.Fragment> {
        return when {
            showWorkerSpace(roleId) -> WorkerSpaceFragment::class.java
            showCallCenter(roleId) -> CallCenterFragment::class.java
            else -> ConnectFragment::class.java
        }
    }
}
