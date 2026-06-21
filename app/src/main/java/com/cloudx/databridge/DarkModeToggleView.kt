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
 * 🌙 DarkModeToggleView
 * — Animation plays first (350ms), THEN theme changes (no mid-animation crash)
 * — Animator is cancelled on detach to prevent IllegalStateException
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

        applyState(isDark, animate = false, applyTheme = false)
        setOnClickListener { onToggleClicked() }
    }

    fun syncState() {
        isDark = prefs.getBoolean("dark_mode", true)
        applyState(isDark, animate = false, applyTheme = false)
    }

    private fun onToggleClicked() {
        isDark = !isDark
        prefs.edit().putBoolean("dark_mode", isDark).apply()
        // ✅ Animate first — apply theme AFTER animation ends (prevents crash)
        applyState(isDark, animate = true, applyTheme = true)
    }

    private fun applyState(dark: Boolean, animate: Boolean, applyTheme: Boolean) {
        // Track background
        setBackgroundResource(
            if (dark) R.drawable.bg_toggle_track_dark else R.drawable.bg_toggle_track_light
        )

        // Thumb icon (always white on blue)
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
        val dp = resources.displayMetrics.density
        val marginPx = (5 * dp).toInt()
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
                    // Guard: only update if view is still attached
                    if (isAttachedToWindow) {
                        thumb.translationX = anim.animatedValue as Float
                    }
                }

                if (applyTheme) {
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            // ✅ Theme changes AFTER animation — no crash
                            if (isAttachedToWindow) {
                                AppCompatDelegate.setDefaultNightMode(
                                    if (isDark) AppCompatDelegate.MODE_NIGHT_YES
                                    else        AppCompatDelegate.MODE_NIGHT_NO
                                )
                            }
                        }
                        override fun onAnimationCancel(animation: Animator) {
                            // Cancelled (e.g. view detached) — apply theme immediately
                            AppCompatDelegate.setDefaultNightMode(
                                if (isDark) AppCompatDelegate.MODE_NIGHT_YES
                                else        AppCompatDelegate.MODE_NIGHT_NO
                            )
                        }
                    })
                }
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
        // ✅ Cancel animator on detach — prevents update on destroyed view
        currentAnimator?.cancel()
        currentAnimator = null
    }
}