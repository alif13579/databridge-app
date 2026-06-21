package com.cloudx.databridge

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.ImageViewCompat

/**
 * 🌙 DarkModeToggleView — v3 (crash-safe)
 *
 * Fix: The previous version called setDefaultNightMode() in onAnimationCancel,
 * which triggered a recreation loop (detach → cancel → setDefaultNightMode → detach → ...).
 * Now:
 * — Click is disabled while animation runs (no rapid re-triggers)
 * — setDefaultNightMode() is called ONLY in onAnimationEnd
 * — onAnimationCancel does NOTHING (pref already saved; new activity reads it on create)
 * — onDetachedFromWindow just cancels the animator silently
 */
class DarkModeToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private val prefs = context.getSharedPreferences("databridge_toggles", Context.MODE_PRIVATE)
    private var isDark: Boolean = prefs.getBoolean("dark_mode", true)

    private lateinit var thumb: FrameLayout
    private lateinit var thumbIcon: ImageView
    private lateinit var idleIcon: ImageView

    private var currentAnimator: ValueAnimator? = null

    // track(60) - thumb(24) - marginStart(3) - marginEnd(3) = 30dp
    private val thumbEndX: Float
        get() = resources.displayMetrics.density * 30f

    init {
        LayoutInflater.from(context).inflate(R.layout.view_dark_mode_toggle, this, true)
        thumb     = findViewById(R.id.dmThumb)
        thumbIcon = findViewById(R.id.dmThumbIcon)
        idleIcon  = findViewById(R.id.dmIdleIcon)

        clipChildren  = false
        clipToPadding = false
        isClickable   = true
        isFocusable   = true

        applyState(isDark, animate = false)
        setOnClickListener { onToggleClicked() }
    }

    fun syncState() {
        isDark = prefs.getBoolean("dark_mode", true)
        applyState(isDark, animate = false)
    }

    private fun onToggleClicked() {
        isDark = !isDark
        prefs.edit().putBoolean("dark_mode", isDark).apply()

        // ✅ Disable click while animating — prevents rapid re-trigger cascade
        isClickable = false

        applyState(isDark, animate = true)
    }

    private fun applyState(dark: Boolean, animate: Boolean) {
        // Track background
        setBackgroundResource(
            if (dark) R.drawable.bg_toggle_track_dark else R.drawable.bg_toggle_track_light
        )

        // Thumb icon (white on blue)
        thumbIcon.setImageResource(if (dark) R.drawable.ic_moon_toggle else R.drawable.ic_sun_toggle)
        ImageViewCompat.setImageTintList(thumbIcon, ColorStateList.valueOf(Color.WHITE))

        // Idle icon (faded, opposite side)
        idleIcon.setImageResource(if (dark) R.drawable.ic_sun_toggle else R.drawable.ic_moon_toggle)
        ImageViewCompat.setImageTintList(
            idleIcon,
            ColorStateList.valueOf(
                if (dark) Color.parseColor("#555555") else Color.parseColor("#C8D0E0")
            )
        )

        // Idle icon gravity
        val marginPx = (5 * resources.displayMetrics.density).toInt()
        val idleParams = idleIcon.layoutParams as LayoutParams
        if (dark) {
            idleParams.gravity     = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            idleParams.marginStart = marginPx
            idleParams.marginEnd   = 0
        } else {
            idleParams.gravity     = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            idleParams.marginEnd   = marginPx
            idleParams.marginStart = 0
        }
        idleIcon.layoutParams = idleParams

        // Thumb animation
        val targetX = if (dark) thumbEndX else 0f
        if (animate) {
            currentAnimator?.cancel()
            currentAnimator = ValueAnimator.ofFloat(thumb.translationX, targetX).apply {
                duration     = 350L
                interpolator = DecelerateInterpolator(1.5f)

                addUpdateListener { anim ->
                    if (isAttachedToWindow) {
                        thumb.translationX = anim.animatedValue as Float
                    }
                }

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // ✅ Apply theme ONLY here — after animation fully completes
                        if (isAttachedToWindow) {
                            AppCompatDelegate.setDefaultNightMode(
                                if (isDark) AppCompatDelegate.MODE_NIGHT_YES
                                else        AppCompatDelegate.MODE_NIGHT_NO
                            )
                        }
                        // Note: if not attached, activity is already recreating — no need to call again.
                        // The pref is saved; MainActivity.onCreate() will apply the correct theme.
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        // ✅ Do NOTHING here — this fires when onDetachedFromWindow cancels
                        // the animator during activity recreation. The pref is already saved.
                        // Calling setDefaultNightMode() here caused the recreation loop crash.
                    }
                })
                start()
            }
        } else {
            post {
                if (isAttachedToWindow) thumb.translationX = targetX
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // ✅ Silently cancel — onAnimationCancel will fire but does nothing (safe)
        currentAnimator?.cancel()
        currentAnimator = null
    }
}