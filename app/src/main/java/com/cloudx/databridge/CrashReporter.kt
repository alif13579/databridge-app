package com.cloudx.databridge

/**
 * Copyable crash log for hard crashes (e.g. fresh-install crash after
 * Clear data).
 *
 * A Toast/dialog can't be shown from a dying process, so this works in two
 * steps:
 *  1. [install] (called first in DataBridgeApplication.onCreate) chains an
 *     UncaughtExceptionHandler that writes the stack trace + device/app info
 *     to internal storage, then delegates to the previous handler (which
 *     kills the process as usual). File I/O is fully guarded — the handler
 *     itself must never throw.
 *  2. [showPending] (called from MainActivity.onCreate on the next launch)
 *     shows the saved log in a scrollable dialog with Copy again / Clear /
 *     Close. The log is ALSO auto-copied to the clipboard the moment the
 *     dialog shows — the manual Copy button proved unreliable on some devices
 *     (tap → toast, but nothing lands in the clipboard), so the dialog no
 *     longer depends on that tap working. Close keeps the file so it shows
 *     again next launch; Clear deletes it.
 *
 * Copy the text straight into chat — it pinpoints the crash line.
 */
object CrashReporter {
    private const val FILE_NAME = "crash_pending.txt"
    private const val MAX_CHARS = 20_000

    fun install(app: android.app.Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrash(app, thread, throwable)
            } catch (_: Exception) {}
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrash(app: android.app.Application, thread: Thread, throwable: Throwable) {
        val version = try {
            val pkg = app.packageManager.getPackageInfo(app.packageName, 0)
            "${pkg.versionName} (${pkg.versionCode})"
        } catch (_: Exception) { "unknown" }
        val sw = java.io.StringWriter()
        var t: Throwable? = throwable
        while (t != null) {
            sw.append(t.javaClass.name).append(": ").append(t.message ?: "").append('\n')
            for (el in t.stackTrace) sw.append("    at ").append(el.toString()).append('\n')
            t = t.cause
            if (t != null) sw.append("Caused by: ")
        }
        val body = buildString {
            append("time=").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append('\n')
            append("app=").append(version).append('\n')
            append("android=").append(android.os.Build.VERSION.RELEASE).append(" (sdk ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            append("device=").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n')
            append("thread=").append(thread.name).append('\n')
            append(sw.toString())
        }.take(MAX_CHARS)
        app.openFileOutput(FILE_NAME, android.content.Context.MODE_PRIVATE).use {
            it.write(body.toByteArray())
        }
    }

    /** Returns true when a pending crash log was shown. Safe to call every launch. */
    fun showPending(activity: android.app.Activity): Boolean {
        val text = try {
            activity.openFileInput(FILE_NAME).bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null } ?: return false

        val tv = android.widget.TextView(activity).apply {
            this.text = text
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(activity).apply { addView(tv) }
        android.app.AlertDialog.Builder(activity)
            .setTitle("App crash হয়েছিল — log (auto-copied ✓)")
            .setView(scroll)
            .setPositiveButton("Copy again") { _, _ ->
                if (copyToClipboard(activity, text)) {
                    android.widget.Toast.makeText(activity, "Copied — chat-এ paste করে দিন", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(activity, "⚠ Copy failed — text select করে manually copy করুন", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Clear") { _, _ ->
                try { activity.deleteFile(FILE_NAME) } catch (_: Exception) {}
            }
            .setNegativeButton("Close", null)
            .show()
        // Auto-copy on show: the manual button alone proved unreliable (tap →
        // success toast, but nothing in the clipboard on some devices), so paste
        // must work even if the user never taps anything.
        if (copyToClipboard(activity, text)) {
            android.widget.Toast.makeText(activity, "Crash log auto-copied — chat-এ paste করে দিন", android.widget.Toast.LENGTH_LONG).show()
        } else {
            android.widget.Toast.makeText(activity, "⚠ Auto-copy failed — Copy again চাপুন বা select করে copy করুন", android.widget.Toast.LENGTH_LONG).show()
        }
        return true
    }

    /** Best-effort clipboard write. Returns false (instead of throwing) so every
     *  caller can fall back to manual selection — clipboard writes can be
     *  rejected on some OEM builds even from a foreground activity. */
    private fun copyToClipboard(activity: android.app.Activity, text: String): Boolean {
        return try {
            val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DataBridgeCrash", text))
            // Verify it actually landed — the reported bug was a silent no-op.
            clipboard.primaryClip?.getItemAt(0)?.text?.toString() == text
        } catch (_: Exception) {
            false
        }
    }
}
