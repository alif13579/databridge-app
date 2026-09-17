package com.cloudx.databridge

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Typing-style "Calling…" dots for the 📞 calling badge on parcel cards.
 *
 * Cycles base → base. → base.. → base... every [PERIOD_MS]. One shared main
 * Handler for every badge (no per-card threads). Runnables are tracked in a
 * WeakHashMap so a forgotten [stop] can never leak an Activity, and each loop
 * re-checks attachment — a detached/recycled view stops itself on the next
 * tick even if the adapter forgot to call [stop]. [start] always stops any
 * previous loop on that view first, so rebinds never stack loops.
 */
object CallingDots {

    private const val PERIOD_MS = 500L
    private const val MAX_DOTS = 3

    private val handler = Handler(Looper.getMainLooper())
    private val active = WeakHashMap<TextView, Runnable>()

    @Synchronized
    fun start(view: TextView, base: String) {
        stopLocked(view)
        var step = 0
        val loop = object : Runnable {
            override fun run() {
                synchronized(this@CallingDots) {
                    if (active[view] !== this) return
                }
                if (!view.isAttachedToWindow) {
                    stop(view)
                    return
                }
                step = (step + 1) % (MAX_DOTS + 1)
                try {
                    view.text = base + ".".repeat(step)
                } catch (_: Exception) {
                    stop(view)
                    return
                }
                handler.postDelayed(this, PERIOD_MS)
            }
        }
        active[view] = loop
        try {
            view.text = base
        } catch (_: Exception) {
            stopLocked(view)
            return
        }
        handler.postDelayed(loop, PERIOD_MS)
    }

    fun stop(view: TextView) {
        synchronized(this) { stopLocked(view) }
    }

    private fun stopLocked(view: TextView) {
        active.remove(view)?.let { handler.removeCallbacks(it) }
    }
}
