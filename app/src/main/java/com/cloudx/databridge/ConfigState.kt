package com.cloudx.databridge

/**
 * 📦 ConfigState — shared in-memory state for all Config sub-fragments.
 *
 * Mirrors the JSX App-level useState variables:
 *   statuses, statusMeta, remarks, workerLang, ccLang
 *
 * Firebase paths (statuses/language/sheets — unrelated to remark options):
 *   config/language/workerLang                ← LanguageFragment r/w
 *   config/language/ccLang                     ← LanguageFragment r/w
 *   config/statusMeta/{key}/...                ← StatusesFragment r/w
 *   config/sheets/{branchId}/current/          ← SheetFragment r/w
 *
 * Remark options moved to Supabase (public.validation_remarks) — see
 * ConfigRemarksFragment's header comment. ConfigState.remarks is unused/legacy,
 * same as before the move (StatusesFragment reads/writes remark counts via its
 * own scoped lookups, not this map).
 */
object ConfigState {

    // ── Data models ───────────────────────────────────────────────────────────

    data class StatusMeta(
        val bn:       String = "",
        val en:       String = "",
        val color:    String = "#6B7280",
        val bg:       String = "#F3F4F6",
        val priority: Int    = 0,  // authority — see StatusMetaCache.Entry.priority's doc
        val sortOrder: Int   = 0,  // display/worklist order — see StatusMetaCache.Entry.sortOrder's doc
        val builtIn:  Boolean = false,
    )

    data class Remark(
        val id:            String = "",
        val text_bn:       String = "",
        val text_en:       String = "",
        val target_status: String = "",
        val template_id:   String = "", // optional — linked WhatsApp template, blank = no auto-message
        val priority:      Int    = 0,  // higher = shown first in the remark picker
        // Instruction: tells the DELIVERY AGENT what to actually do, separate from
        // target_status (which just changes the consignment's status). E.g. a remark
        // like "Customer isn't answering calls" might carry instruction_type = "on_hold"
        // with instruction_text = "Try again after 3pm, don't return yet" -- the agent
        // sees that text alongside the CC remark once it's applied, not just a bare
        // status change. instruction_type is a fixed dropdown (see INSTRUCTION_TYPES
        // below); instruction_text is free text the admin writes per-remark, not derived
        // from the type. Both blank = no instruction attached (the common case).
        val instruction_type: String = "",
        val instruction_text: String = "",
        // is_active: false hides this option from the picker without deleting it —
        // an alternative to a hard delete when past saved remarks may still reference
        // this text (see validation_remarks migration 202608250002).
        val is_active: Boolean = true,
    )

    /** Fixed instruction-type options for the Remarks config's Instruction dropdown —
     *  hardcoded per Alif's decision (not admin-configurable), though the value set
     *  could grow later (e.g. an attempt/aging-driven dynamic instruction system was
     *  discussed and explicitly deferred, not built here). Empty string means "no
     *  instruction" and isn't itself a dropdown entry — it's the default/unset state. */
    const val INSTRUCTION_TYPE_ON_HOLD = "on_hold"
    const val INSTRUCTION_TYPE_RETURN = "return"
    val INSTRUCTION_TYPES = listOf(INSTRUCTION_TYPE_ON_HOLD, INSTRUCTION_TYPE_RETURN)

    fun instructionTypeLabel(type: String): String = when (type) {
        INSTRUCTION_TYPE_ON_HOLD -> "On Hold"
        INSTRUCTION_TYPE_RETURN -> "Return"
        else -> type
    }

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
