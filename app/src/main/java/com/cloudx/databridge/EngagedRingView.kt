package com.cloudx.databridge

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View

/**
 * Rotating gradient ring overlay — draws a spinning conic-gradient stroke around a
 * rounded-rect matching the parcel card's own corner radius, to visually flag "someone is
 * actively working this parcel right now" (card expanded within the last N minutes — see
 * EngagedStateManager for the Firebase-backed presence logic this is purely the visual half of).
 *
 * Usage: add as the LAST child of the card's root FrameLayout (so it draws on top, matching
 * how the age badge overlay is already layered in item_parcel_card.xml / item_parcel_agent_card.xml),
 * sized to match_parent, then toggle visibility + start()/stop() based on engaged state.
 */
class EngagedRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Matches bg_parcel_card.xml's corners android:radius="14dp". Kept in sync manually
     *  since drawables don't expose their radius programmatically. */
    private val cornerRadiusPx = 14f * resources.displayMetrics.density
    private val strokeWidthPx = 2.5f * resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
    }
    private val ringRect = RectF()
    private var sweepAngle = 0f

    private val colors = intArrayOf(
        android.graphics.Color.parseColor("#00D4FF"), // cyan
        android.graphics.Color.parseColor("#7C3AED"), // purple
        android.graphics.Color.parseColor("#00D4FF"), // back to cyan — seamless loop
    )

    private var rotationAnimator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = strokeWidthPx / 2f
        ringRect.set(inset, inset, w - inset, h - inset)
        rebuildShader()
    }

    private fun rebuildShader() {
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        // SweepGradient rotates via matrix (see draw()), not by rebuilding — cheaper per-frame.
        val shader = SweepGradient(cx, cy, colors, null)
        paint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (paint.shader == null) return

        val matrix = android.graphics.Matrix()
        matrix.setRotate(sweepAngle, width / 2f, height / 2f)
        paint.shader?.setLocalMatrix(matrix)

        canvas.drawRoundRect(ringRect, cornerRadiusPx, cornerRadiusPx, paint)
    }

    /** Starts the spin loop. Safe to call repeatedly (e.g. on every bind) — no-ops if already running. */
    fun start() {
        if (rotationAnimator?.isRunning == true) return
        visibility = VISIBLE
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                sweepAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        rotationAnimator?.cancel()
        rotationAnimator = null
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // RecyclerView recycles this view — always cancel the animator so a stale spin doesn't
        // keep running (and leaking) on a detached view, or bleed into whatever this ViewHolder
        // gets rebound to next.
        rotationAnimator?.cancel()
        rotationAnimator = null
    }
}
