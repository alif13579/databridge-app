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
                "আপনার system ID পাওয়া যায়নি — admin-এর সাথে যোগাযোগ করুন"
            "a branch is required" in m ->
                "Branch select করা হয়নি — branch বেছে আবার চেষ্টা করুন"
            "not signed in" in m || "no signed-in user" in m || "no firebase user" in m ->
                "Login করা নেই — আবার login করে চেষ্টা করুন"
            "expired" in m || ("token" in m && "401" in m) ->
                "Login session শেষ হয়ে গেছে — আবার login করে চেষ্টা করুন"
            "unknownhost" in m || "socket" in m || "timeout" in m || "network error" in m || "unable to resolve" in m ->
                "Internet সংযোগে সমস্যা — connection check করে আবার চেষ্টা করুন"
            "foreign key" in m || "23503" in m ->
                "তথ্য sync-এ সমস্যা হয়েছে — আবার চেষ্টা করুন, না হলে admin-কে জানান"
            "23502" in m || "null value in column" in m ->
                "তথ্য sync-এ সমস্যা হয়েছে (খালি date/field) — app update করে আবার চেষ্টা করুন"
            "http 400" in m || "missing" in m || "is required" in m ->
                "কিছু তথ্য বাকি আছে — form check করে আবার চেষ্টা করুন"
            "http 401" in m || "unauthorized" in m ->
                "Save করা যায়নি (অনুমতি সমস্যা) — আবার login করে চেষ্টা করুন"
            else -> "Request save হয়নি — আবার চেষ্টা করুন"
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
                android.widget.Toast.makeText(ctx, "Copied — chat-এ paste করে দিন", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
