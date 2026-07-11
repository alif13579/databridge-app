package com.cloudx.databridge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Shared swipe-to-action behavior for parcel cards in WorkerSpaceFragment and CallCenterFragment.
 *
 *  - Swipe RIGHT (drag card to the right, revealing left side) -> triggers Call
 *  - Swipe LEFT  (drag card to the left, revealing right side) -> triggers Remarks
 *
 * The card always snaps back to its resting position after the action fires — this is a
 * shortcut trigger, not a dismiss/delete gesture. Header rows (or any non-swipeable view type)
 * are excluded via [isSwipeable].
 *
 * Usage:
 *   val touchHelper = ItemTouchHelper(
 *       SwipeActionCallback(
 *           context = requireContext(),
 *           isSwipeable = { position -> adapter.getItemViewType(position) == VIEW_TYPE_CARD },
 *           onSwipeRight = { position -> ... call the parcel at position ... },
 *           onSwipeLeft  = { position -> ... open remarks for the parcel at position ... }
 *       )
 *   )
 *   touchHelper.attachToRecyclerView(rvParcelList)
 */
class SwipeActionCallback(
    private val context: android.content.Context,
    private val isSwipeable: (Int) -> Boolean = { true },
    private val onSwipeRight: (Int) -> Unit,
    private val onSwipeLeft: (Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val callColor = Color.parseColor("#10b981")     // green
    private val remarksColor = Color.parseColor("#00a8c0")  // cyan
    private val iconPaint = Paint().apply {
        color = Color.WHITE
        textSize = context.resources.displayMetrics.density * 20f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION || !isSwipeable(position)) return 0
        return super.getSwipeDirs(recyclerView, viewHolder)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        // Always notify the adapter to rebind — this snaps the card back to resting position
        // instead of removing it (this is a shortcut action, not a swipe-to-dismiss).
        viewHolder.bindingAdapter?.notifyItemChanged(position)
        when (direction) {
            ItemTouchHelper.RIGHT -> onSwipeRight(position)
            ItemTouchHelper.LEFT -> onSwipeLeft(position)
        }
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val itemView = viewHolder.itemView
        val density = context.resources.displayMetrics.density
        val pad = 24f * density

        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            val bgPaint = Paint().apply { isAntiAlias = true }
            if (dX > 0) {
                // Swiping right -> reveal green "Call" panel on the left
                bgPaint.color = callColor
                val rect = RectF(
                    itemView.left.toFloat(), itemView.top.toFloat(),
                    itemView.left + dX, itemView.bottom.toFloat()
                )
                c.drawRoundRect(rect, 14f * density, 14f * density, bgPaint)
                if (dX > pad) {
                    c.drawText("📞", itemView.left + pad, itemView.top + itemView.height / 2f + (iconPaint.textSize / 3), iconPaint)
                }
            } else if (dX < 0) {
                // Swiping left -> reveal cyan "Remarks" panel on the right
                bgPaint.color = remarksColor
                val rect = RectF(
                    itemView.right + dX, itemView.top.toFloat(),
                    itemView.right.toFloat(), itemView.bottom.toFloat()
                )
                c.drawRoundRect(rect, 14f * density, 14f * density, bgPaint)
                if (abs(dX) > pad) {
                    c.drawText("✏️", itemView.right - pad, itemView.top + itemView.height / 2f + (iconPaint.textSize / 3), iconPaint)
                }
            }
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.35f
}
