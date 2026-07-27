package com.cloudx.databridge

import android.content.Context

/**
 * Per-device, per-consignment dial-attempt counter — purely local (SharedPreferences),
 * never synced to Firebase. Each device/install tracks its own tally of how many times a CC
 * agent has dialed a given parcel's number from this app, for a quick "you've already tried
 * this N times" glance on the parcel card. This is intentionally NOT a team-wide/shared
 * count — a colleague calling the same parcel from a different device won't affect it.
 */
object DialCountStore {
    private const val PREFS_NAME = "dial_counts"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Increments and returns the new count for [consignmentId]. */
    fun increment(ctx: Context, consignmentId: String): Int {
        if (consignmentId.isBlank()) return 0
        val p = prefs(ctx)
        val next = p.getInt(consignmentId, 0) + 1
        p.edit().putInt(consignmentId, next).apply()
        return next
    }

    /** Current count for [consignmentId], 0 if never dialed from this device. */
    fun get(ctx: Context, consignmentId: String): Int =
        if (consignmentId.isBlank()) 0 else prefs(ctx).getInt(consignmentId, 0)
}
