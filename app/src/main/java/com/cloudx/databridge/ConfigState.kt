package com.cloudx.databridge

/**
 * 📦 ConfigState — shared in-memory state for all Config sub-fragments.
 *
 * Mirrors the JSX App-level useState variables:
 *   statuses, statusMeta, remarks, workerLang, ccLang
 *
 * Firebase paths:
 *   config/remarks_worker/{statusKey}[]/      ← RemarksFragment r/w (worker scope)
 *   config/remarks_call_center/{statusKey}[]/ ← RemarksFragment r/w (call-center scope)
 *   config/language/workerLang                ← LanguageFragment r/w
 *   config/language/ccLang                     ← LanguageFragment r/w
 *   config/statusMeta/{key}/...                ← StatusesFragment r/w
 *   config/sheets/{branchId}/current/          ← SheetFragment r/w
 *
 * Note: ConfigState.remarks is unused/legacy — StatusesFragment reads/writes the two
 * scoped nodes directly (own remarksWorker/remarksCallCenter maps) since remark counts
 * and delete-migration must be checked per scope independently.
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
        val template_id:   String = "", // optional — linked WhatsApp template, blank = no auto-message
        val priority:      Int    = 0,  // higher = shown first in the remark picker
    )

    data class WhatsAppTemplate(
        val id:   String = "",
        val name: String = "", // admin-facing label, e.g. "Delivery confirmation"
        val body: String = "", // message text with placeholders: {name} {phone} {address} {cod} {consignmentId} {hub}
    )

    // Statuses and remarks are admin-created and loaded from Firebase only.
    val BASE_STATUSES = emptyList<String>()
    val BASE_STATUS_META: Map<String, StatusMeta> = emptyMap()

    private fun defaultRemarks(): MutableMap<String, MutableList<Remark>> = mutableMapOf()

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
    var whatsappTemplates: MutableMap<String, WhatsAppTemplate> = mutableMapOf()
    var workerLang: String                           = "bn_bn"
    var ccLang:     String                           = "bn_en"

    /** Reset to defaults (call on logout) */
    fun reset() {
        statuses   = BASE_STATUSES.toMutableList()
        statusMeta = BASE_STATUS_META.toMutableMap()
        remarks    = defaultRemarks()
        whatsappTemplates = mutableMapOf()
        workerLang = "bn_bn"
        ccLang     = "bn_en"
    }
}
