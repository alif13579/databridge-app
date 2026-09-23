package com.cloudx.databridge

import android.content.Context
import android.graphics.Typeface

/**
 * Bundled Liberation Sans (SIL OFL, metrically identical to Arial) for every
 * app-generated PDF, so reports render the same on all devices instead of
 * whatever the system default serif/sans happens to be.
 *
 * Call [init] once with any Context before drawing (both PDF writers do this
 * themselves when they receive one). Until then [of] returns null and callers
 * fall back to the system sans.
 */
object PdfFonts {
    @Volatile private var regularFace: Typeface? = null
    @Volatile private var boldFace: Typeface? = null
    @Volatile private var italicFace: Typeface? = null
    @Volatile private var boldItalicFace: Typeface? = null
    @Volatile private var ready: Boolean = false

    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val assets = context.applicationContext.assets
            fun load(name: String): Typeface? =
                runCatching { Typeface.createFromAsset(assets, "fonts/$name") }.getOrNull()
            regularFace = load("LiberationSans-Regular.ttf")
            boldFace = load("LiberationSans-Bold.ttf")
            italicFace = load("LiberationSans-Italic.ttf")
            boldItalicFace = load("LiberationSans-BoldItalic.ttf")
            ready = true
        }
    }

    /** Liberation Sans for the wanted style, or null when not loaded yet. */
    fun of(bold: Boolean = false, italic: Boolean = false): Typeface? = when {
        bold && italic -> boldItalicFace ?: boldFace ?: italicFace ?: regularFace
        bold -> boldFace ?: regularFace
        italic -> italicFace ?: regularFace
        else -> regularFace
    }
}
