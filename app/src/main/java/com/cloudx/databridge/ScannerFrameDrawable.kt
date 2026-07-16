package com.cloudx.databridge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class ScannerFrameDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val len = (b.width().coerceAtMost(b.height()) * 0.22f)
        val left = b.left + 4f
        val top = b.top + 4f
        val right = b.right - 4f
        val bottom = b.bottom - 4f

        canvas.drawLine(left, top, left + len, top, paint)
        canvas.drawLine(left, top, left, top + len, paint)

        canvas.drawLine(right, top, right - len, top, paint)
        canvas.drawLine(right, top, right, top + len, paint)

        canvas.drawLine(left, bottom, left + len, bottom, paint)
        canvas.drawLine(left, bottom, left, bottom - len, paint)

        canvas.drawLine(right, bottom, right - len, bottom, paint)
        canvas.drawLine(right, bottom, right, bottom - len, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
