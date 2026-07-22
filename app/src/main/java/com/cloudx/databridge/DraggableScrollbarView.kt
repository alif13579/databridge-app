package com.cloudx.databridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min

/**
 * A draggable scrollbar thumb, since android:scrollbars is visual-only on RecyclerView — there
 * is no platform-level touch-to-drag support for it (unlike some OEMs' ListView behavior, which
 * isn't guaranteed either). This view supplies that behavior itself.
 *
 * Usage: add as a sibling of the RecyclerView inside the same FrameLayout, positioned to the
 * right edge (e.g. layout_gravity="end", fixed width, match_parent height), then call
 * attachTo(recyclerView) once the RecyclerView's adapter/layoutManager are set.
 *
 * How it works:
 *  - Listens to RecyclerView's scroll events (RecyclerView.OnScrollListener) to compute what
 *    fraction of the total scrollable range is currently visible, and positions/sizes this
 *    view's thumb rect accordingly (same idea as a normal scrollbar, just drawn by us).
 *  - On ACTION_DOWN over the thumb, or anywhere in the track, and ACTION_MOVE afterward: computes
 *    the touch's vertical fraction within this view's height, and tells the RecyclerView to jump
 *    to the matching item position via LinearLayoutManager.scrollToPositionWithOffset — this is
 *    a direct jump (like a real scrollbar), not a fling/smooth-scroll, since the user is actively
 *    dragging and expects 1:1 tracking with their finger.
 *  - Auto-hides (alpha fade) shortly after the RecyclerView stops scrolling AND the thumb isn't
 *    currently being dragged, matching normal scrollbar fade behavior — but reappears
 *    immediately on any new touch/scroll.
 */
class DraggableScrollbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var recyclerView: RecyclerView? = null

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#E8380D")
    }
    private val thumbRect = RectF()
    private val cornerRadiusPx = 6f * resources.displayMetrics.density
    /** Minimum thumb height so it never shrinks to an unusably tiny/hard-to-grab sliver on a
     *  very long list — same principle as why real scrollbars have a min-thumb-size rule. */
    private val minThumbHeightPx = 40f * resources.displayMetrics.density

    private var isDragging = false
    private var isVisible = false
    private val hideRunnable = Runnable { animateHide() }
    private val hideDelayMs = 1000L

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            updateThumbFromScrollPosition()
            if (!isDragging) showThenScheduleHide()
        }
    }

    /** Call once the RecyclerView's adapter and layoutManager are both set. Safe to call again
     *  if the adapter is swapped out later — removes the old listener first. */
    fun attachTo(rv: RecyclerView) {
        recyclerView?.removeOnScrollListener(scrollListener)
        recyclerView = rv
        rv.addOnScrollListener(scrollListener)
        // Initial position — the list may already be scrolled (e.g. restored state) by the
        // time this view is attached.
        post { updateThumbFromScrollPosition() }
    }

    private fun updateThumbFromScrollPosition() {
        val rv = recyclerView ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val itemCount = lm.itemCount
        if (itemCount <= 0 || height <= 0) {
            visibility = INVISIBLE
            return
        }

        val firstVisible = lm.findFirstVisibleItemPosition()
        if (firstVisible < 0) return

        // Fraction of the list scrolled past, and fraction currently visible — same math a
        // real scrollbar uses (visible-extent / total-extent for thumb size, scrolled/total
        // for thumb position).
        val visibleCount = (lm.findLastVisibleItemPosition() - firstVisible + 1).coerceAtLeast(1)
        val visibleFraction = (visibleCount.toFloat() / itemCount).coerceIn(0f, 1f)

        if (visibleFraction >= 1f) {
            // Everything fits on screen at once — nothing to scroll, no thumb to show.
            visibility = INVISIBLE
            return
        }
        visibility = VISIBLE

        val thumbHeight = max(height * visibleFraction, minThumbHeightPx)
        val scrollableRange = itemCount - visibleCount
        val scrolledFraction = if (scrollableRange > 0) firstVisible.toFloat() / scrollableRange else 0f
        val maxThumbTop = height - thumbHeight
        val thumbTop = (scrolledFraction * maxThumbTop).coerceIn(0f, max(maxThumbTop, 0f))

        thumbRect.set(0f, thumbTop, width.toFloat(), thumbTop + thumbHeight)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(thumbRect, cornerRadiusPx, cornerRadiusPx, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rv = recyclerView ?: return false
        val lm = rv.layoutManager as? LinearLayoutManager ?: return false
        val itemCount = lm.itemCount
        if (itemCount <= 0 || height <= 0) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                showThenScheduleHide(scheduleHide = false)
                parent?.requestDisallowInterceptTouchEvent(true)
                scrollToTouchY(event.y, itemCount)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                scrollToTouchY(event.y, itemCount)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                showThenScheduleHide()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun scrollToTouchY(touchY: Float, itemCount: Int) {
        val rv = recyclerView ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val fraction = (touchY / height.toFloat()).coerceIn(0f, 1f)
        val targetPosition = (fraction * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)
        lm.scrollToPositionWithOffset(targetPosition, 0)
    }

    private fun showThenScheduleHide(scheduleHide: Boolean = true) {
        removeCallbacks(hideRunnable)
        if (!isVisible) {
            isVisible = true
            animate().alpha(1f).setDuration(120L).start()
        } else {
            alpha = 1f
        }
        if (scheduleHide) postDelayed(hideRunnable, hideDelayMs)
    }

    private fun animateHide() {
        if (isDragging) return // never hide mid-drag, regardless of timer firing
        isVisible = false
        animate().alpha(0f).setDuration(250L).start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        recyclerView?.removeOnScrollListener(scrollListener)
        removeCallbacks(hideRunnable)
    }
}
