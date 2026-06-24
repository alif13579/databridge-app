package com.cloudx.databridge

/**
 * 📦 ConfigState — shared in-memory state for all Config sub-fragments.
 *
 * Mirrors the JSX App-level useState variables:
 *   statuses, statusMeta, remarks, workerLang, ccLang
 *
 * Firebase paths:
 *   config/remarks/{statusKey}[]/         ← RemarksFragment r/w
 *   config/language/workerLang            ← LanguageFragment r/w
 *   config/language/ccLang                ← LanguageFragment r/w
 *   config/statusMeta/{key}/...           ← StatusesFragment r/w
 *   config/sheets/{branchId}/current/     ← SheetFragment r/w
 */
object ConfigState {

    // ── Data models ───────────────────────────────────────────────────────────

    data class StatusMeta(
        val bn:       String = "",
        val en:       String = "",
        val color:    String = "#6B7280",
        val bg:       String = "#F3F4F6",
        val priority: Int    = 0,
        val builtIn:  Boolean = false,
    )

    data class Remark(
        val id:            String = "",
        val text_bn:       String = "",
        val text_en:       String = "",
        val target_status: String = "",
    )

    // ── Base constants (mirrors JSX BASE_STATUSES / BASE_STATUS_META) ─────────

    val BASE_STATUSES = listOf("PENDING", "CONFIRMED", "DELIVERED", "RETURN", "HOLD", "CANCELLED")

    val BASE_STATUS_META: Map<String, StatusMeta> = mapOf(
        "PENDING"   to StatusMeta("অপেক্ষমান",         "Pending",   "#F59E0B", "#FEF3C7", 1, true),
        "CONFIRMED" to StatusMeta("নিশ্চিত",            "Confirmed", "#3B82F6", "#DBEAFE", 3, true),
        "DELIVERED" to StatusMeta("ডেলিভারি হয়েছে",  "Delivered", "#10B981", "#D1FAE5", 5, true),
        "RETURN"    to StatusMeta("ফেরত",               "Return",    "#EF4444", "#FEE2E2", 2, true),
        "HOLD"      to StatusMeta("হোল্ড",              "Hold",      "#8B5CF6", "#EDE9FE", 4, true),
        "CANCELLED" to StatusMeta("বাতিল",              "Cancelled", "#6B7280", "#F3F4F6", 0, true),
    )

    private fun defaultRemarks(): MutableMap<String, MutableList<Remark>> = mutableMapOf(
        "DELIVERED" to mutableListOf(
            Remark("d1", "সঠিকভাবে পৌঁছে দেওয়া হয়েছে", "Delivered successfully",  "DELIVERED"),
            Remark("d2", "গ্রাহক সন্তুষ্ট",              "Customer satisfied",       "DELIVERED"),
            Remark("d3", "প্রতিবেশী পেয়েছেন",            "Received by neighbor",     "DELIVERED"),
        ),
        "RETURN" to mutableListOf(
            Remark("r1", "গ্রাহক নেই",        "Customer absent",  "RETURN"),
            Remark("r2", "ফোন ধরেননি",         "No answer",        "RETURN"),
            Remark("r3", "ঠিকানা ভুল",         "Wrong address",    "RETURN"),
            Remark("r4", "পণ্য প্রত্যাখ্যান", "Product rejected", "RETURN"),
        ),
        "HOLD" to mutableListOf(
            Remark("h1", "গ্রাহক পরে নিতে চান", "Customer will take later", "HOLD"),
            Remark("h2", "এলাকায় সমস্যা আছে",  "Area issue",               "HOLD"),
        ),
        "CONFIRMED" to mutableListOf(
            Remark("c1", "গ্রাহক নিশ্চিত করেছেন", "Customer confirmed", "CONFIRMED"),
        ),
        "PENDING"   to mutableListOf(),
        "CANCELLED" to mutableListOf(),
    )

    val STATUS_COLORS: List<Pair<String,String>> = listOf(
        "#F59E0B" to "#FEF3C7",
        "#3B82F6" to "#DBEAFE",
        "#10B981" to "#D1FAE5",
        "#EF4444" to "#FEE2E2",
        "#8B5CF6" to "#EDE9FE",
        "#6B7280" to "#F3F4F6",
        "#EC4899" to "#FCE7F3",
        "#14B8A6" to "#CCFBF1",
        "#F97316" to "#FFEDD5",
        "#6366F1" to "#E0E7FF",
    )

    // Language options (mirrors JSX LANG_OPTIONS)
    data class LangOption(val value: String, val label: String)
    val LANG_OPTIONS = listOf(
        LangOption("bn_bn", "Remark = বাংলা, Status = বাংলা"),
        LangOption("bn_en", "Remark = বাংলা, Status = English"),
        LangOption("en_en", "Remark = English, Status = English"),
        LangOption("en_bn", "Remark = English, Status = বাংলা"),
    )

    // ── Mutable shared state ──────────────────────────────────────────────────
    var statuses:   List<String>                     = BASE_STATUSES.toMutableList()
    var statusMeta: Map<String, StatusMeta>          = BASE_STATUS_META.toMutableMap()
    var remarks:    MutableMap<String, MutableList<Remark>> = defaultRemarks()
    var workerLang: String                           = "bn_bn"
    var ccLang:     String                           = "bn_en"

    /** Reset to defaults (call on logout) */
    fun reset() {
        statuses   = BASE_STATUSES.toMutableList()
        statusMeta = BASE_STATUS_META.toMutableMap()
        remarks    = defaultRemarks()
        workerLang = "bn_bn"
        ccLang     = "bn_en"
    }
}
