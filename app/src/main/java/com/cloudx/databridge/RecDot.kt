package com.cloudx.databridge

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View

/**
 * Pulsing red recording dot for parcel cards — visible while a call recording
 * runs for that parcel (see CallRecordingStore). Bind + recycle-safe: [hide]
 * cancels the loop (call from onBind's else-branch and onViewRecycled, same
 * pattern as CallingDots).
 */
object RecDot {
    fun show(v: View) {
        v.visibility = View.VISIBLE
        if (v.getTag() is ObjectAnimator) return
        val anim = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.2f).apply {
            duration = 650
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        v.setTag(anim)
        anim.start()
    }

    fun hide(v: View) {
        (v.getTag() as? ObjectAnimator)?.cancel()
        v.setTag(null)
        v.alpha = 1f
        v.visibility = View.GONE
    }
}
