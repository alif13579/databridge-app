package com.cloudx.databridge

import android.content.Context
import android.graphics.Typeface

/**
 * Bundled Roboto (Apache 2.0) for every app-generated PDF, so reports render
 * the same on all devices instead of whatever the system default
 * serif/sans happens to be.
 *
 * Call [init] once with any Context before drawing (both PDF writers do this
 * themselves when they receive one). Until then [of] returns null and callers
 * fall back to the system sans (which is Roboto on Android anyway).
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
            // Roboto first; Liberation Sans kept as fallback for older installs
            // that may still reference it.
            regularFace = load("Roboto-Regular.ttf") ?: load("LiberationSans-Regular.ttf")
            boldFace = load("Roboto-Bold.ttf") ?: load("LiberationSans-Bold.ttf")
            italicFace = load("Roboto-Italic.ttf") ?: load("LiberationSans-Italic.ttf")
            boldItalicFace = load("Roboto-BoldItalic.ttf") ?: load("LiberationSans-BoldItalic.ttf")
            // Last resort: system Roboto (sans-serif IS Roboto on Android).
            if (regularFace == null) regularFace = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            if (boldFace == null) boldFace = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            if (italicFace == null) italicFace = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
            if (boldItalicFace == null) boldItalicFace = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
            ready = true
        }
    }

    /** Roboto for the wanted style, or null when not loaded yet. */
    fun of(bold: Boolean = false, italic: Boolean = false): Typeface? = when {
        bold && italic -> boldItalicFace ?: boldFace ?: italicFace ?: regularFace
        bold -> boldFace ?: regularFace
        italic -> italicFace ?: regularFace
        else -> regularFace
    }
}
