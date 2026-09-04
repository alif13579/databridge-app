package com.cloudx.databridge

/**
 * Copyable error dialog for Supabase/backend failures.
 *
 * Toasts are transient and can't be copied, so when a save fails the exact
 * server reason (e.g. the Edge Function's {"error","reason"} body, threaded
 * into the exception message by writers like SupabaseClaimsWriter) would be
 * lost before the user can share it. This dialog shows the full text in a
 * scrollable monospace view with a Copy button instead — tap Copy and paste
 * it straight into chat for diagnosis.
 *
 * One-line use from any Fragment/Activity:
 *   SupabaseErrorDialog.show(requireContext(), "Claim save failed", errMsg)
 */
object SupabaseErrorDialog {
    fun show(ctx: android.content.Context, title: String, message: String) {
        val text = message.ifBlank { "Unknown error (empty message)" }
        val tv = android.widget.TextView(ctx).apply {
            this.text = text
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(tv) }

        android.app.AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DataBridgeError", "$title\n$text"))
                android.widget.Toast.makeText(ctx, "Copied — chat-এ paste করে দিন", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
