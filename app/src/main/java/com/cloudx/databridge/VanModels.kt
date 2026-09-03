package com.cloudx.databridge

/**
 * Static fleet list for the Van Check-In picker — deliberately hardcoded (not
 * admin-managed): the fleet changes rarely, and the check-in popup must work
 * even with zero Supabase rows. Edit [VANS] below when vans join/leave.
 */
object VanCatalog {
    data class Van(val number: String, val type: String)

    // TODO(fleet): replace with the hub's real van numbers + types.
    // `number` is the van's identity (plate/name as the desk knows it) and
    // doubles as the per-branch uniqueness key for "already inside".
    // `type` should stay one of CNG / Paddle Van / Van / Auto (shown as-is).
    val VANS: List<Van> = listOf(
        Van("VAN-01", "Van"),
        Van("VAN-02", "Van"),
        Van("VAN-03", "CNG"),
        Van("VAN-04", "Auto"),
    )
}

/** One van visit at a branch: check_out_at NULL = still inside. */
data class VanMovement(
    val id: String = "",
    val branchId: String = "",
    val vehicleNumber: String = "",
    val vehicleType: String = "",
    val driverName: String = "",
    val checkInAt: Long = 0L,
    val checkOutAt: Long = 0L,
    val checkInByName: String = "",
    val note: String = "",
) {
    val isInside: Boolean get() = checkOutAt == 0L
}
