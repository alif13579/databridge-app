package com.cloudx.databridge

/**
 * Human-readable (Bangla-first) one-liners for save failures, so an agent
 * understands what happened without reading server JSON. The exact technical
 * message still goes to [SupabaseErrorDialog] with Copy for diagnosis.
 */
object UserErrorText {
    fun forSaveFailure(t: Throwable?): String {
        val m = ((t?.message.orEmpty() + " " + t?.cause?.message.orEmpty()).lowercase())
        return when {
            "system id is missing" in m || "agent system id is required" in m ->
                "Your system ID was not found — contact your admin"
            "a branch is required" in m ->
                "No branch selected — select a branch and retry"
            "not signed in" in m || "no signed-in user" in m || "no firebase user" in m ->
                "Not logged in — log in and retry"
            "expired" in m || ("token" in m && "401" in m) ->
                "Login session expired — log in and retry"
            "unknownhost" in m || "socket" in m || "timeout" in m || "network error" in m || "unable to resolve" in m ->
                "Internet connection problem — check connection and retry"
            "foreign key" in m || "23503" in m ->
                "Data sync failed — retry, or inform the admin"
            "23502" in m || "null value in column" in m ->
                "Data sync failed (empty date/field) — update the app and retry"
            "http 400" in m || "missing" in m || "is required" in m ->
                "Some info is missing — check the form and retry"
            "http 401" in m || "unauthorized" in m ->
                "Save failed (permission issue) — log in and retry"
            else -> "Request save failed — retry"
        }
    }
}
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
                android.widget.Toast.makeText(ctx, "Copied — paste it into the chat", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
