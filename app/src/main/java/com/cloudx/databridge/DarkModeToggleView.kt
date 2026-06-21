package com.cloudx.databridge

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
 * Exact replica of the custom dark/light mode toggle design.
 * — Pill-shaped track (60×30dp)
 * — Animated blue circle thumb with sun/moon icon
 * — Faded idle icon on the opposite side
 * — Smooth DecelerateInterpolator animation (350ms)
 * — Persists state in "databridge_toggles" SharedPreferences
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

    // Thumb slides: trackW(60) - thumbW(24) - marginStart(3) - marginEnd(3) = 30dp
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

    /** Call when the drawer opens to ensure the visual matches saved preference. */
    fun syncState() {
        isDark = prefs.getBoolean("dark_mode", true)
        applyState(isDark, animate = false)
    }

    private fun onToggleClicked() {
        isDark = !isDark
        prefs.edit().putBoolean("dark_mode", isDark).apply()
        applyState(isDark, animate = true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES
            else        AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun applyState(dark: Boolean, animate: Boolean) {
        // ── Track background ──────────────────────────────────────────────────
        setBackgroundResource(
            if (dark) R.drawable.bg_toggle_track_dark else R.drawable.bg_toggle_track_light
        )

        // ── Thumb icon (always white on blue bg) ──────────────────────────────
        thumbIcon.setImageResource(if (dark) R.drawable.ic_moon_toggle else R.drawable.ic_sun_toggle)
        ImageViewCompat.setImageTintList(thumbIcon, ColorStateList.valueOf(Color.WHITE))

        // ── Idle icon (faded, opposite side) ─────────────────────────────────
        idleIcon.setImageResource(if (dark) R.drawable.ic_sun_toggle else R.drawable.ic_moon_toggle)
        val idleColor = if (dark) Color.parseColor("#555555") else Color.parseColor("#C8D0E0")
        ImageViewCompat.setImageTintList(idleIcon, ColorStateList.valueOf(idleColor))

        val dp = resources.displayMetrics.density
        val marginPx = (5 * dp).toInt()
        val idleParams = idleIcon.layoutParams as LayoutParams
        if (dark) {
            // Sun idle icon → LEFT side
            idleParams.gravity   = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            idleParams.marginStart = marginPx
            idleParams.marginEnd   = 0
        } else {
            // Moon idle icon → RIGHT side
            idleParams.gravity   = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            idleParams.marginEnd   = marginPx
            idleParams.marginStart = 0
        }
        idleIcon.layoutParams = idleParams

        // ── Thumb translation ─────────────────────────────────────────────────
        val targetX = if (dark) thumbEndX else 0f
        if (animate) {
            ValueAnimator.ofFloat(thumb.translationX, targetX).apply {
                duration     = 350L
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { thumb.translationX = it.animatedValue as Float }
                start()
            }
        } else {
            post { thumb.translationX = targetX }
        }
    }
}