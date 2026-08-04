package com.cloudx.databridge

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * Consistent per-channel color coding across Home, Select Wallet, and Manage
 * Wallets. Deliberately generic (initial letter + tint) rather than each MFS
 * provider's actual brand mark.
 */
object CashChannelStyle {
    private val KNOWN = mapOf(
        "rocket" to ("#EDE9FE" to "#6D28D9"),
        "bkash" to ("#FCE7F3" to "#BE185D"),
        "nagad" to ("#FFEDD5" to "#C2410C"),
        "upay" to ("#FEF3C7" to "#B45309"),
    )
    private val FALLBACK = "#F1F5F9" to "#475569"

    fun colors(channelName: String): Pair<String, String> = KNOWN[channelName.lowercase()] ?: FALLBACK

    fun iconDrawable(channelName: String, radiusDp: Int, density: Float): GradientDrawable {
        val (bg, _) = colors(channelName)
        return GradientDrawable().apply {
            setColor(Color.parseColor(bg))
            cornerRadius = radiusDp * density
        }
    }
}
