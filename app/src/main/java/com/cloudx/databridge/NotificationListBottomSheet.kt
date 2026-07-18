package com.cloudx.databridge

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BottomSheet that shows the full in-app notification list.
 * Opening it marks all notifications as read.
 */
class NotificationListBottomSheet : BottomSheetDialogFragment() {

    /** Called when the user taps a notification that has a linked parcel. */
    var onParcelClick: ((parcelId: String, scope: String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        // Root scroll container
        val root = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 12.dp(), 16.dp(), 32.dp())
        }
        root.addView(container)

        // ── Header row ──────────────────────────────────────────────────
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 12.dp() }
        }

        val tvTitle = TextView(ctx).apply {
            text = "🔔 Notifications"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.theme_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvClearAll = TextView(ctx).apply {
            text = "Clear all"
            textSize = 12f
            setTextColor(ctx.getColor(R.color.theme_text_secondary))
            setOnClickListener {
                AppNotificationManager.clearAll()
                container.removeAllViews()
                container.addView(headerRow)
                container.addView(emptyView(ctx, dp))
            }
        }

        headerRow.addView(tvTitle)
        headerRow.addView(tvClearAll)
        container.addView(headerRow)

        // ── Notification items ───────────────────────────────────────────
        val notifs = AppNotificationManager.notifications
        if (notifs.isEmpty()) {
            container.addView(emptyView(ctx, dp))
        } else {
            val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
            notifs.forEach { item ->
                val card = buildCard(ctx, dp, item, sdf)
                container.addView(card)
            }
        }

        return root
    }

    override fun onResume() {
        super.onResume()
        // Mark all as read when the sheet opens
        AppNotificationManager.markAllRead()
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun emptyView(ctx: android.content.Context, dp: Float): View {
        return TextView(ctx).apply {
            text = "📭  No notifications yet"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setTextColor(ctx.getColor(R.color.theme_text_secondary))
            setPadding(0, (24 * dp).toInt(), 0, (24 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun buildCard(
        ctx: android.content.Context,
        dp: Float,
        item: AppNotificationManager.NotifItem,
        sdf: SimpleDateFormat
    ): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            setBackgroundResource(R.drawable.bg_stat_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (8 * dp).toInt() }
            // Tappable only when the notification links to a specific parcel
            if (item.parcelId.isNotBlank()) {
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    // Dismiss first so the BottomSheet's fragment transaction completes
                    // before MainActivity tries to load ParcelDetailFragment.
                    // Invoking onParcelClick while the sheet is still attached causes a
                    // concurrent fragment-manager transaction crash (state loss / IAE).
                    val pid   = item.parcelId
                    val scope = item.scope
                    dismissAllowingStateLoss()
                    // Post the navigation onto the main looper so it runs after the
                    // dismiss transaction commits. Using Handler(mainLooper) instead of
                    // view?.post because the view may already be null by the time the
                    // BottomSheet detaches, which would silently drop the navigation.
                    Handler(Looper.getMainLooper()).post { onParcelClick?.invoke(pid, scope) }
                }
            }
        }

        // Icon + title row
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val icon = when (item.type) {
            "remark" -> "💬"
            "alert"  -> "⚠️"
            else     -> "🔔"
        }

        val tvIcon = TextView(ctx).apply {
            text = icon
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (6 * dp).toInt() }
        }

        val tvTitleText = TextView(ctx).apply {
            text = if (item.parcelId.isNotBlank()) "${item.title}  →" else item.title
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.theme_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTime = TextView(ctx).apply {
            text = sdf.format(Date(item.timestamp))
            textSize = 10f
            setTextColor(ctx.getColor(R.color.theme_text_secondary))
        }

        titleRow.addView(tvIcon)
        titleRow.addView(tvTitleText)
        titleRow.addView(tvTime)
        card.addView(titleRow)

        // Message
        if (item.message.isNotBlank()) {
            val tvMsg = TextView(ctx).apply {
                text = item.message
                textSize = 12f
                setTextColor(ctx.getColor(R.color.theme_text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (3 * dp).toInt() }
            }
            card.addView(tvMsg)
        }

        return card
    }
}
