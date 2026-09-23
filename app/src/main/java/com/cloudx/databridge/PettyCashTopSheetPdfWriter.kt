package com.cloudx.databridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Generates the "Top Sheet For Petty Cash Expense" report as a single multi-page
 * PDF, reproducing the reference PDF's 4 page types from SupabaseClaimsReader.
 * ClaimRow data:
 *   Page 1 — Top Sheet (branch/POC summary + 3-line category-group totals)
 *   Page 2 — Petty Cash Expense Summary (fixed A/B/C rows like the sample
 *            PDF — only the A-section amounts come from the data)
 *   Page 3 — Agent Acknowledgement (one row per operation-expense agent,
 *            tall empty signature cells for signing on paper)
 *   Then — Conveyance Vouchers in one continuous flow (no per-agent page
 *          break, no huge gaps): each agent is one bordered block exactly
 *          like the sample PDF (title → SL → Agent ID → Designation →
 *          9 column headers → one row per claim → 2 blank rows → G/Total →
 *          In-word), same-LOT rows merged rowspan-style with one row per
 *          consignment.
 *   Last — Unsettled Bills (only when the range has unsettled claims):
 *          one row per pending claim, stamped UNSETTLED.
 *
 * Long tables flow onto continuation pages with the table header repeated —
 * nothing is ever drawn past the page edge. Text never truncates with "…":
 * oversized cell text shrinks to fit instead.
 *
 * A4 at 72pt/inch — same size CashExportWriter.kt already uses for its exports.
 * Every "page type" above is forced onto its own page (nextPage() at each
 * boundary) rather than flowing continuously, per the "Top Sheet alada single
 * page, Expense Summary single page, ... individual single A4 page" request —
 * this differs from CashExportWriter's single continuously-flowing table.
 */
object PettyCashTopSheetPdfWriter {
    /** Voucher page grouping: one bordered block per agent (classic), or one
     *  bordered block per conveyance category holding every agent's rows. */
    enum class VoucherGrouping { AGENT_WISE, CATEGORY_WISE }

    private const val pageWidth = 595   // A4 width in points (210mm @ 72pt/in)
    private const val pageHeight = 842  // A4 height in points (297mm @ 72pt/in)
    private const val margin = 36f
    private val contentWidth get() = pageWidth - margin * 2

    private val darkColor = Color.parseColor("#0F172A")
    private val mutedColor = Color.parseColor("#64748B")
    private val borderColor = Color.parseColor("#CBD5E1")
    // Mid grey instead of near-black: readable headers at a fraction of the
    // toner/ink (solid black fills are the most expensive thing on the page).
    // Voucher column headers stay unfilled white, exactly like the sample PDF.
    private val headerFillColor = Color.parseColor("#A3AEBB")
    private val lightFillColor = Color.parseColor("#F1F5F9")

    private val moneyFormat = NumberFormat.getNumberInstance(Locale.US)
    private val dateDisplayFormat = SimpleDateFormat("dd-MM-yy", Locale.US)
    private val voucherDateFormat = SimpleDateFormat("dd-MMM-yyyy", Locale.US)
    private val dateIsoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * Business month: the 26th–25th cycle (26 Aug–25 Sep == September).
     * The label month is always the range END date's month — a full-cycle
     * report 26 Aug–25 Sep renders "September 2026", and the Aug bill
     * (1–25 Aug) renders "August 2026". Pass [pattern] "MMMM yyyy" for the
     * Top Sheet / Summary pages, "MMMM-yy" for the Acknowledgement header.
     */
    private fun businessMonthLabel(toDateIso: String, pattern: String): String {
        val date = runCatching { dateIsoFormat.parse(toDateIso) }.getOrNull() ?: java.util.Date()
        return SimpleDateFormat(pattern, Locale.US).format(date)
    }

    /** Display ID everywhere in the report: employee ID (FDA 1958), never the
     *  internal system id — matches the reference PDF's Agent ID column. */
    private fun displayAgentId(row: SupabaseClaimsReader.ClaimRow): String =
        row.agentEmployeeId.ifBlank { row.agentSystemId }

    /**
     * Per-generate() page cursor. Tables (acknowledgement, vouchers, long
     * category breakdowns) can outgrow one A4 page — instead of drawing past
     * the edge (content silently lost), draw loops call [nextPage] mid-table
     * and repeat the table header there. One instance per generate() call so
     * overlapping generations can't share page state.
     */
    private class PageCtx(val pdf: PdfDocument, val totalPages: Int) {
        private var pageNum = 0
        private lateinit var page: PdfDocument.Page
        lateinit var canvas: Canvas
            private set

        /** Set while conveyance voucher pages are drawn — only those pages
         *  get the "Page X of Y" footer in the right-bottom corner. */
        var footerEnabled = false

        val pages: Int get() = pageNum

        init {
            nextPage()
        }

        fun nextPage() {
            if (pageNum > 0) {
                drawFooterIfNeeded()
                pdf.finishPage(page)
            }
            pageNum += 1
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
            canvas = page.canvas
        }

        fun finish() {
            drawFooterIfNeeded()
            pdf.finishPage(page)
        }

        private fun drawFooterIfNeeded() {
            if (!footerEnabled) return
            val paint = textPaint(mutedColor, 7.5f).apply { textAlign = Paint.Align.RIGHT }
            val text = if (totalPages > 0) "Page $pageNum of $totalPages" else "Page $pageNum"
            canvas.drawText(text, margin + contentWidth, pageHeight - 14f, paint)
        }
    }

// Legacy conveyance categories (pre-catalog data, e.g. the reference
// PDF's InterChange rows): any settled claim whose category isn't in the
// admin catalog falls back here — conveyance iff its category is in this
// set, otherwise the operation group, so page-1 totals always stay
// balanced (operation + office + utilities == grand total).
private val legacyConveyanceTypes = setOf(
    "Pickup", "Bulk Delivery", "LOT Delivery", "InterChange", "Inter Change",
    "Parcel Receiving", "Parcel Sending", "Inter Change Commission",
)

    /** Old rows store the "OFFICE" sentinel or a raw area id; new rows store
     *  the human-readable label. Normalize for display. */
    private fun areaLabel(value: String): String =
        if (value.trim().equals("OFFICE", ignoreCase = true)) "Office" else value

    /** Everything renderReport needs — threaded through both passes so the
     *  counting pass and the real pass draw byte-identical pages. */
    private data class ReportInput(
        val claims: List<SupabaseClaimsReader.ClaimRow>,
        val branchName: String,
        val branchRegion: String,
        val pettyCashLimit: Double,
        val pocName: String,
        val pocEmployeeId: String,
        val pocDesignation: String,
        val pocContact: String,
        val fromDateIso: String,
        val toDateIso: String,
        val categoryGroups: Map<String, String>,
        val voucherGrouping: VoucherGrouping,
    )

    /**
     * Builds the full report PDF for one branch over [fromDateIso, toDateIso]
     * (both "yyyy-MM-dd") from [claims] (already scoped to that branch/range —
     * see SupabaseClaimsReader.fetchClaimsForReport) and writes it to [outFile].
     * Only settled claims (status == "settled") are counted into any total —
     * a pending/rejected claim isn't an actual expense yet, and every Amount
     * shown is the settled amount (the actual payout), never requested.
     *
     * [categoryGroups] maps category name → group (conveyance / operation /
     * office / utilities) from the admin-managed claim_categories catalog.
     * Conveyance-group claims are counted via their category rows; every
     * other group via its own category rows.
     */
    fun generate(
        outFile: File,
        claims: List<SupabaseClaimsReader.ClaimRow>,
        branchName: String,
        branchRegion: String,
        pettyCashLimit: Double,
        pocName: String,
        pocEmployeeId: String,
        pocDesignation: String,
        pocContact: String,
        fromDateIso: String,
        toDateIso: String,
        categoryGroups: Map<String, String> = emptyMap(),
        voucherGrouping: VoucherGrouping = VoucherGrouping.AGENT_WISE,
        appContext: Context? = null,
    ) {
        appContext?.let(PdfFonts::init)
        val input = ReportInput(
            claims, branchName, branchRegion, pettyCashLimit,
            pocName, pocEmployeeId, pocDesignation, pocContact,
            fromDateIso, toDateIso, categoryGroups, voucherGrouping,
        )
        // Two passes so conveyance footers can read "Page X of Y": pass 1
        // counts pages (throwaway document), pass 2 draws the real file.
        // Both passes draw identical pages — only the footer total differs.
        val counting = PdfDocument()
        val totalPages = renderReport(counting, -1, input)
        counting.close()
        val pdf = PdfDocument()
        renderReport(pdf, totalPages, input)
        outFile.outputStream().use { pdf.writeTo(it) }
        pdf.close()
    }

    /** Draws the whole report into [pdf]; returns the page count. */
    private fun renderReport(pdf: PdfDocument, totalPages: Int, input: ReportInput): Int {
        val claims = input.claims
        val branchName = input.branchName
        val branchRegion = input.branchRegion
        val pettyCashLimit = input.pettyCashLimit
        val pocName = input.pocName
        val pocEmployeeId = input.pocEmployeeId
        val pocDesignation = input.pocDesignation
        val pocContact = input.pocContact
        val fromDateIso = input.fromDateIso
        val toDateIso = input.toDateIso
        val categoryGroups = input.categoryGroups
        val voucherGrouping = input.voucherGrouping
        val settled = claims.filter { it.status.equals("settled", ignoreCase = true) }
        fun groupOf(category: String): String =
            categoryGroups[category]
                ?: if (category in legacyConveyanceTypes) "conveyance" else "operation"
        // A conveyance claim is identified by its category's group — or, for
        // pre-catalog rows, by a legacy conveyance category name (reference
        // PDF's InterChange rows). Description always shows the saved
        // category verbatim, however it got here.
        fun isConveyanceClaim(row: SupabaseClaimsReader.ClaimRow): Boolean =
            groupOf(row.category) == "conveyance" || row.category in legacyConveyanceTypes
        // ── Hardcoded A-section buckets (sample-PDF structure) ─────────────
        // Office-marked = office-group catalog rows, an "Office Expense"
        // category, or the Utilities store/cid (the 1160 row is filed under
        // Pickup but belongs in A.9 Others, exactly like the sample).
        fun isOfficeMarked(row: SupabaseClaimsReader.ClaimRow): Boolean =
            groupOf(row.category) == "office" ||
                row.category.equals("Office Expense", ignoreCase = true) ||
                row.cidOrMerchant.trim().equals("Utilities Expenses", ignoreCase = true)
        // Legacy Parcel Receiving trips filed under the Inter Change store:
        // they are the flat-rate 200/trip Hub→Office runs (5×200 in the Aug
        // bill), unlike commission rows whose amount varies with the voucher
        // quantity. A misbucket here only ever moves money between A.2 and
        // A.8 — the A total always stays balanced.
        fun isLegacyParcelReceiving(row: SupabaseClaimsReader.ClaimRow): Boolean =
            row.category in setOf("Inter Change", "Inter Change Commission") &&
                row.settledAmount == 200.0
        // 1..9 = A.1..A.9 row. Everything lands somewhere (leftovers go to
        // A.9 Others) so the page always balances to the settled total.
        fun bucketOf(row: SupabaseClaimsReader.ClaimRow): Int = when {
            row.category.equals("Parcel Sending", ignoreCase = true) -> 1
            row.category.equals("Parcel Receiving", ignoreCase = true) || isLegacyParcelReceiving(row) -> 2
            row.category.equals("Pickup", ignoreCase = true) && !isOfficeMarked(row) -> 3
            row.category in setOf("Delivery", "Parcel Delivery", "Delivery Conveyance") -> 4
            row.category in setOf("Bulk Delivery", "LOT Delivery") -> 5
            row.category in setOf("Inter Change", "Inter Change Commission") -> 8
            else -> 9
        }
        // Verbatim labels from the sample PDF (typos kept: "Receving",
        // "Maintaince", "Gurad").
        val aLabels = listOf(
            "Parcel Sending Cost (Hub To Hub)",
            "Parcel Receving Cost(Van/Point To Hub)",
            "Parcel Pickup Conveyance",
            "Parcel Delivery Conveyance",
            "Bulk Parcel Delivery Conveyance",
            "Pickup Van Parking",
            "Cycle Parking Bill",
            "Inter Change Commission",
            "Others Exp…",
        )
        // Split settled rows by catalog group so each lands in its own
        // summary section: conveyance/operation -> A, office -> B,
        // utilities -> C. Previously everything fell into A (utilities like
        // Internet Bill showed under A.9 Others, B/C hardcoded 0).
        val aRows = settled.filter { groupOf(it.category) in setOf("conveyance", "operation") }
        val bRows = settled.filter { groupOf(it.category) == "office" }
        val cRows = settled.filter { groupOf(it.category) == "utilities" }
        val aAmounts = (1..9).map { i -> aRows.filter { bucketOf(it) == i }.sumOf { it.settledAmount } }
        val bAmounts = (1..bLabels.size).map { i -> bRows.filter { bBucketOf(it) == i }.sumOf { it.settledAmount } }
        val cAmounts = (1..cLabels.size).map { i -> cRows.filter { cBucketOf(it) == i }.sumOf { it.settledAmount } }
        val ctx = PageCtx(pdf, totalPages)

        // ── Page 1: Top Sheet ──────────────────────────────────────────────
        // Same buckets as page 2, so both pages always agree:
        // Total == A + B + C == settled grand total.
        val operationTotal = aAmounts.sum()
        val officeTotal = bAmounts.sum()
        val utilitiesTotal = cAmounts.sum()
        drawTopSheetPage(
            ctx.canvas, operationTotal, officeTotal, utilitiesTotal,
            branchName, branchRegion, pettyCashLimit,
            pocName, pocEmployeeId, pocDesignation, pocContact, fromDateIso, toDateIso,
        )

        // ── Page 2: Petty Cash Expense Summary ──────────────────────────────
        ctx.nextPage()
        drawExpenseSummaryPage(
            ctx, aLabels, aAmounts, bAmounts, cAmounts,
            branchName, branchRegion,
            pocName, pocEmployeeId, pocDesignation, pocContact, fromDateIso, toDateIso,
        )

        // ── Page 3: Agent Acknowledgement (operation-expense agents only) ──
        ctx.nextPage()
        drawAgentAcknowledgementPage(ctx, aRows, toDateIso, pocName, pocEmployeeId, pocDesignation)

        // ── Conveyance Vouchers — continuous flow like the sample PDF: blocks
        // follow one another with no forced page break (no huge gaps); a
        // table that outgrows the page continues with its header repeated.
        // Agent-wise: one block per agent, ordered by first appearance in the
        // settled list (matches the Acknowledgement page's SL order).
        // Category-wise: one block per conveyance category holding every
        // agent's rows, categories in first-appearance order. ──
        val conveyanceClaims = settled.filter { isConveyanceClaim(it) }
        if (conveyanceClaims.isNotEmpty()) {
            ctx.nextPage()
            ctx.footerEnabled = true
            var voucherSl = 1
            var voucherCanvas = ctx.canvas
            var voucherY = margin
            // One measured grid for the whole report (same columns line up
            // across blocks); category-wise measures the Agent column.
            val (vWidths, vXs) = voucherGrid(
                conveyanceClaims, agentColumn = voucherGrouping == VoucherGrouping.CATEGORY_WISE,
            )
            // Breathing room between consecutive voucher blocks (skipped when a
            // block ends flush at the page bottom — the page break itself is the
            // separator there).
            fun gapAfter(y: Float): Float =
                if (y + voucherGap <= pageHeight - margin) y + voucherGap else y
            when (voucherGrouping) {
                VoucherGrouping.AGENT_WISE -> {
                    val agentOrder = LinkedHashSet<String>()
                    settled.forEach { agentOrder.add(it.agentSystemId) }
                    agentOrder.forEach { agentSystemId ->
                        val agentClaims = conveyanceClaims.filter { it.agentSystemId == agentSystemId }
                            .sortedBy { it.placedDate }
                        if (agentClaims.isEmpty()) return@forEach
                        val drawn = drawConveyanceVoucherAgent(ctx, voucherCanvas, voucherY, vWidths, vXs, agentClaims, voucherSl)
                        voucherCanvas = drawn.first
                        voucherY = gapAfter(drawn.second)
                        voucherSl += 1
                    }
                }
                VoucherGrouping.CATEGORY_WISE -> {
                    val categoryOrder = LinkedHashSet<String>()
                    conveyanceClaims.forEach { categoryOrder.add(it.category.ifBlank { "Other" }) }
                    categoryOrder.forEach { category ->
                        val catClaims = conveyanceClaims
                            .filter { it.category.ifBlank { "Other" } == category }
                            .sortedBy { it.placedDate }
                        if (catClaims.isEmpty()) return@forEach
                        val drawn = drawConveyanceVoucherCategory(ctx, voucherCanvas, voucherY, vWidths, vXs, category, catClaims, voucherSl)
                        voucherCanvas = drawn.first
                        voucherY = gapAfter(drawn.second)
                        voucherSl += 1
                    }
                }
            }
        }

        // ── Unsettled Bills — every in-range claim still awaiting money
        // (pending/approved/…; rejected/cancelled are dead, never listed),
        // one row each, stamped UNSETTLED. Skipped when there are none. ──
        ctx.footerEnabled = false
        val unsettled = claims.filter {
            val s = it.status.trim().lowercase()
            s != "settled" && s != "rejected" && s != "cancelled" && s != "cancel"
        }.sortedBy { it.placedDate }
        if (unsettled.isNotEmpty()) {
            ctx.nextPage()
            drawUnsettledPage(ctx, unsettled)
        }

        ctx.finish()
        return ctx.pages
    }

    // ── Page 1: Top Sheet ────────────────────────────────────────────────────

    private fun drawTopSheetPage(
        canvas: Canvas,
        operationTotal: Double, officeTotal: Double, utilitiesTotal: Double,
        branchName: String, branchRegion: String, pettyCashLimit: Double,
        pocName: String, pocEmployeeId: String, pocDesignation: String, pocContact: String,
        fromDateIso: String, toDateIso: String,
    ) {
        var y = margin
        val titlePaint = textPaint(darkColor, 13f, bold = true).apply { textAlign = Paint.Align.CENTER }
        val labelPaint = textPaint(darkColor, 9f, bold = true)
        val valuePaint = textPaint(darkColor, 9f)
        val strokeBorder = strokePaint(borderColor, 0.6f)

        // Header block: company name + report title.
        canvas.drawText("Pathao Limited", margin + contentWidth / 2, y + 14f, titlePaint)
        y += 18f
        canvas.drawRect(margin, y, margin + contentWidth, y + 20f, fillPaint(lightFillColor))
        canvas.drawRect(margin, y, margin + contentWidth, y + 20f, strokeBorder)
        canvas.drawText("Top Sheet For Petty Cash Expense", margin + contentWidth / 2, y + 14f, titlePaint)
        y += 20f

        // Hub / month / date rows — each its own label:value block.
        y = drawTwoColLabelRow(canvas, y, "Hub Name", branchName, labelPaint, valuePaint, strokeBorder)
        // Business month from the range end (26 Aug–25 Sep == September).
        val monthLabel = businessMonthLabel(toDateIso, "MMMM yyyy")
        y = drawTwoColLabelRow(canvas, y, "Month Name", monthLabel, labelPaint, valuePaint, strokeBorder)
        y = drawTwoColLabelRow(
            canvas, y, "Date",
            dateDisplayFormat.format(dateIsoFormat.parse(toDateIso) ?: java.util.Date()),
            labelPaint, valuePaint, strokeBorder,
        )

        // POC details block.
        y = drawSectionHeaderRow(canvas, y, "Petty Cash POC Details:", strokeBorder, labelPaint)
        y = drawTwoColLabelRow(canvas, y, "Employee ID", pocEmployeeId, labelPaint, valuePaint, strokeBorder)
        y = drawTwoColLabelRow(canvas, y, "Name", pocName, labelPaint, valuePaint, strokeBorder)
        y = drawTwoColLabelRow(canvas, y, "Designation", pocDesignation, labelPaint, valuePaint, strokeBorder)
        y = drawTwoColLabelRow(canvas, y, "Contact", pocContact, labelPaint, valuePaint, strokeBorder)
        y = drawSectionHeaderRow(canvas, y, "Region Name: $branchRegion", strokeBorder, labelPaint)
        y += 14f

        // 3-group cost table (totals computed by the caller from the settled list).
        val totalCost = operationTotal + officeTotal + utilitiesTotal

        y = drawTableHeaderRow(canvas, y, listOf("SL" to 0.1f, "Description" to 0.7f, "Amount" to 0.2f), strokeBorder, headerFillColor)
        y = drawDataRow(canvas, y, listOf("1", "Operation Expense", moneyFormat.format(operationTotal)), listOf(0.1f, 0.7f, 0.2f), strokeBorder, alignRight = setOf(2))
        y = drawDataRow(canvas, y, listOf("2", "Office Maintaince Cost", moneyFormat.format(officeTotal)), listOf(0.1f, 0.7f, 0.2f), strokeBorder, alignRight = setOf(2))
        y = drawDataRow(canvas, y, listOf("3", "Utilities expense", moneyFormat.format(utilitiesTotal)), listOf(0.1f, 0.7f, 0.2f), strokeBorder, alignRight = setOf(2))
        y += 14f

        val cashInHand = (pettyCashLimit - totalCost).coerceAtLeast(0.0)
        val overExpenditure = (totalCost - pettyCashLimit).coerceAtLeast(0.0)
        y = drawTwoColLabelRow(canvas, y, "Total Cost", moneyFormat.format(totalCost), labelPaint, valuePaint, strokeBorder, boldValue = true, alignRightValue = true)
        y = drawTwoColLabelRow(canvas, y, "Petty cash limit", moneyFormat.format(pettyCashLimit), labelPaint, valuePaint, strokeBorder, boldValue = true, alignRightValue = true)
        y = drawTwoColLabelRow(canvas, y, "Cash in hand", moneyFormat.format(cashInHand), labelPaint, valuePaint, strokeBorder, boldValue = true, alignRightValue = true)
        y = drawTwoColLabelRow(canvas, y, "Over expenditure", moneyFormat.format(overExpenditure), labelPaint, valuePaint, strokeBorder, boldValue = true, alignRightValue = true)
        y += 40f

        // Acknowledgement boxes.
        val boxHeight = 90f
        val halfWidth = contentWidth / 2f
        canvas.drawRect(margin, y, margin + halfWidth, y + 20f, fillPaint(lightFillColor))
        canvas.drawRect(margin + halfWidth, y, margin + contentWidth, y + 20f, fillPaint(lightFillColor))
        canvas.drawText("Acknowledged by Accounts", margin + 4f, y + 14f, textPaint(darkColor, 8.5f, bold = true))
        canvas.drawText("Acknowledged by Department", margin + halfWidth + 4f, y + 14f, textPaint(darkColor, 8.5f, bold = true))
        canvas.drawRect(margin, y, margin + halfWidth, y + 20f + boxHeight, strokeBorder)
        canvas.drawRect(margin + halfWidth, y, margin + contentWidth, y + 20f + boxHeight, strokeBorder)
    }

    // ── Page 2: Petty Cash Expense Summary ──────────────────────────────────
    // Fixed A/B/C rows exactly like the sample PDF — amounts come from the
    // data (A via bucketOf, B/C via b/cBucketOf on the office/utilities
    // groups). Office-marked Pickup rows stay in A.9 Others, never in B/C.

    private val bLabels = listOf(
        "Print And Photocopy Cost",
        "Office Accessories Purchase",
        "Internet Connection Cost",
        "CCTV Setup And Maintenance Cost",
        "IPS Set-Up And Servicing Cost",
        "Others Exp…",
    )

    private val cLabels = listOf(
        "Internet Bill",
        "Regarding Mobile Bill For QC Team Member",
        "Gas Bill",
        "Local Security Gurad Bill",
        "Garbage Bill",
        "Water/Wasa Bill",
        "Cleaner Bill",
        "Transgender Bill",
        "Others Exp…",
    )

    /** Normalizes category/label for B/C matching — tolerant to the sample
     *  PDF's "Gurad" typo vs the catalog's "Guard", casing and extra spaces. */
    private fun normSummaryLabel(s: String): String =
        s.trim().lowercase().replace("gurad", "guard").replace("\\s+".toRegex(), " ")

    /** 1-based B row for an office-group claim; unknown names fall to Others. */
    private fun bBucketOf(row: SupabaseClaimsReader.ClaimRow): Int {
        val want = normSummaryLabel(row.category)
        bLabels.forEachIndexed { i, label ->
            if (normSummaryLabel(label) == want) return i + 1
        }
        return bLabels.size
    }

    /** 1-based C row for a utilities-group claim; unknown names fall to Others. */
    private fun cBucketOf(row: SupabaseClaimsReader.ClaimRow): Int {
        val want = normSummaryLabel(row.category)
        cLabels.forEachIndexed { i, label ->
            if (normSummaryLabel(label) == want) return i + 1
        }
        return cLabels.size
    }

    private fun drawExpenseSummaryPage(
        ctx: PageCtx,
        aLabels: List<String>,
        aAmounts: List<Double>,
        bAmounts: List<Double>,
        cAmounts: List<Double>,
        branchName: String, branchRegion: String,
        pocName: String, pocEmployeeId: String, pocDesignation: String, pocContact: String,
        fromDateIso: String, toDateIso: String,
    ) {
        var canvas = ctx.canvas
        var y = margin
        val titlePaint = textPaint(darkColor, 12f, bold = true).apply { textAlign = Paint.Align.CENTER }
        val labelPaint = textPaint(darkColor, 8.5f, bold = true)
        val valuePaint = textPaint(darkColor, 8.5f)
        val strokeBorder = strokePaint(borderColor, 0.6f)

        canvas.drawText("Petty Cash Expense Summery", margin + contentWidth / 2, y + 10f, titlePaint)
        y += 16f
        val monthLabel = businessMonthLabel(toDateIso, "MMMM yyyy")
        y = drawTwoColLabelRow(canvas, y, "Hub Name", branchName, labelPaint, valuePaint, strokeBorder)
        y = drawTwoColLabelRow(canvas, y, "Month Name", monthLabel, labelPaint, valuePaint, strokeBorder)
        y = drawTwoColLabelRow(
            canvas, y, "Date",
            dateDisplayFormat.format(dateIsoFormat.parse(toDateIso) ?: java.util.Date()),
            labelPaint, valuePaint, strokeBorder,
        )
        y = drawTwoColLabelRow(canvas, y, "Responsible Name", pocName, labelPaint, valuePaint, strokeBorder)
        y = drawTwoColLabelRow(canvas, y, "EID", pocEmployeeId, labelPaint, valuePaint, strokeBorder)
        y = drawTwoColLabelRow(canvas, y, "Designation", pocDesignation, labelPaint, valuePaint, strokeBorder)
        y = drawTwoColLabelRow(canvas, y, "Contact Number", pocContact, labelPaint, valuePaint, strokeBorder)
        y = drawTwoColLabelRow(canvas, y, "Region Name", branchRegion, labelPaint, valuePaint, strokeBorder)
        y += 10f

        val rowHeightSmall = 12f

        // A. Operation Expense — 9 fixed rows; long lists continue with the
        // group header repeated.
        y = drawSummaryGroupHeader(canvas, y, "A. Operation Expense", "Amount", strokeBorder, headerFillColor)
        aLabels.forEachIndexed { i, label ->
            if (y + rowHeightSmall > pageHeight - margin) {
                ctx.nextPage(); canvas = ctx.canvas; y = margin
                y = drawSummaryGroupHeader(canvas, y, "A. Operation Expense (contd.)", "Amount", strokeBorder, headerFillColor)
            }
            y = drawSummaryLineRow(canvas, y, i + 1, label, aAmounts.getOrElse(i) { 0.0 }, rowHeightSmall, strokeBorder)
        }
        val operationSectionTotal = aAmounts.sum()
        y = drawSummaryTotalRow(canvas, y, "Total Operation Expense", operationSectionTotal, strokeBorder)
        y += 8f

        // B. Office Maintenance Cost — office-group rows by category.
        y = drawSummaryGroupHeader(canvas, y, "B. Office Maintaince Cost", "Amount", strokeBorder, headerFillColor)
        bLabels.forEachIndexed { i, label ->
            if (y + rowHeightSmall > pageHeight - margin) {
                ctx.nextPage(); canvas = ctx.canvas; y = margin
                y = drawSummaryGroupHeader(canvas, y, "B. Office Maintaince Cost (contd.)", "Amount", strokeBorder, headerFillColor)
            }
            y = drawSummaryLineRow(canvas, y, i + 1, label, bAmounts.getOrElse(i) { 0.0 }, rowHeightSmall, strokeBorder)
        }
        val officeSectionTotal = bAmounts.sum()
        y = drawSummaryTotalRow(canvas, y, "Total Office Maintaince Cost", officeSectionTotal, strokeBorder)
        y += 8f

        // C. Utilities Expense — utilities-group rows by category (Internet
        // Bill -> C.1, etc.).
        y = drawSummaryGroupHeader(canvas, y, "C. Utilities Expense", "Amount", strokeBorder, headerFillColor)
        cLabels.forEachIndexed { i, label ->
            if (y + rowHeightSmall > pageHeight - margin) {
                ctx.nextPage(); canvas = ctx.canvas; y = margin
                y = drawSummaryGroupHeader(canvas, y, "C. Utilities Expense (contd.)", "Amount", strokeBorder, headerFillColor)
            }
            y = drawSummaryLineRow(canvas, y, i + 1, label, cAmounts.getOrElse(i) { 0.0 }, rowHeightSmall, strokeBorder)
        }
        val utilitiesSectionTotal = cAmounts.sum()
        y = drawSummaryTotalRow(canvas, y, "Total Utilities Expense", utilitiesSectionTotal, strokeBorder)

        y += 8f
        val grandTotal = operationSectionTotal + officeSectionTotal + utilitiesSectionTotal
        // Keep grand total + sign-off together: break before them if tight.
        if (y + 14f + 20f + 34f > pageHeight - margin) {
            ctx.nextPage(); canvas = ctx.canvas; y = margin
        }
        y = drawSummaryTotalRow(canvas, y, "Total Cost (A+B+C)", grandTotal, strokeBorder, emphasize = true)
        y += 20f

        // Sign-off row — keep it whole on one page.
        if (y + 34f > pageHeight - margin) {
            ctx.nextPage(); canvas = ctx.canvas; y = margin
        }
        val signOffLabels = listOf("Prepared By", "Incharge", "Checked By FFM", "Acknowledged by Admin", "Acknowledged by Lead (Ops)")
        val colWidth = contentWidth / signOffLabels.size
        signOffLabels.forEachIndexed { i, label ->
            val x = margin + i * colWidth
            canvas.drawText(label, x, y + 10f, textPaint(darkColor, 7f, bold = true))
        }
        y += 24f
        canvas.drawText(pocName, margin, y, textPaint(darkColor, 7.5f))
    }

    // ── Page 3: Agent Acknowledgement ────────────────────────────────────────

    private fun drawAgentAcknowledgementPage(
        ctx: PageCtx,
        settled: List<SupabaseClaimsReader.ClaimRow>,
        toDateIso: String,
        pocName: String,
        pocEmployeeId: String,
        pocDesignation: String,
    ) {
        var canvas = ctx.canvas
        var y = margin
        val titlePaint = textPaint(darkColor, 12f, bold = true).apply { textAlign = Paint.Align.CENTER }
        val labelPaint = textPaint(darkColor, 8.5f, bold = true)
        val valuePaint = textPaint(darkColor, 8.5f)
        val strokeBorder = strokePaint(borderColor, 0.6f)
        val monthYearLabel = businessMonthLabel(toDateIso, "MMMM-yy")

        canvas.drawText("Pathao Limited", margin + contentWidth / 2, y + 12f, titlePaint)
        y += 16f
        canvas.drawText("Agent Acknowledgement ($monthYearLabel)", margin + contentWidth / 2, y + 12f, titlePaint)
        y += 18f

        // Operation-expense agents only: utilities/office claims (e.g. an
        // Internet Bill filed by non-agent staff) never appear here — the
        // caller passes the conveyance+operation rows. Grouped by system id
        // (stable key), displayed by employee ID (FDA 1958).
        data class AgentSummary(val systemId: String, val empId: String, val name: String, val phone: String, val amount: Double, val delivered: Int)
        val bySystemId = settled.groupBy { it.agentSystemId }
        val summaries = bySystemId.map { (systemId, rows) ->
            AgentSummary(
                systemId = systemId,
                empId = rows.first().agentEmployeeId.ifBlank { systemId },
                name = rows.first().agentName.ifBlank { systemId },
                phone = rows.first().agentPhone,
                amount = rows.sumOf { it.settledAmount },
                delivered = rows.sumOf { it.deliveredQuantity },
            )
        }.sortedBy { it.name }

        y = drawTwoColLabelRow(canvas, y, "E ID: $pocEmployeeId", "Name: $pocName", labelPaint, valuePaint, strokeBorder, isTwoLabels = true)
        y = drawTwoColLabelRow(canvas, y, "Department: Fulfillment", "Designation: $pocDesignation", labelPaint, valuePaint, strokeBorder, isTwoLabels = true)
        y = drawSectionHeaderRow(canvas, y, "Purpose: Petty Cash", strokeBorder, labelPaint)

        // "Succeeded" is neutral for both pickup and delivery claims (a
        // delivered parcel and a completed pickup are both successes) —
        // "Delivered" alone read as delivery-only.
        val headers = listOf("SL" to 0.06f, "Name" to 0.22f, "ID" to 0.12f, "Amount" to 0.12f, "Total Succeeded" to 0.13f, "Active Contact Number" to 0.17f, "Received by Signature" to 0.18f)
        y = drawTableHeaderRow(canvas, y, headers, strokeBorder, headerFillColor)
        // Agent rows continue on fresh pages with the header repeated; SL
        // numbers keep counting across the break. Rows are tall with an
        // empty signature cell so agents can sign on paper.
        val ackRowHeight = 24f
        var sl = 1
        summaries.forEach { s ->
            if (y + ackRowHeight > pageHeight - margin - 42f) {
                ctx.nextPage(); canvas = ctx.canvas; y = margin
                y = drawTableHeaderRow(canvas, y, headers, strokeBorder, headerFillColor)
            }
            y = drawDataRow(
                canvas, y,
                listOf(sl.toString(), s.name, s.empId, moneyFormat.format(s.amount), s.delivered.toString(), s.phone, ""),
                headers.map { it.second }, strokeBorder, alignRight = setOf(3, 4),
                rowHeight = ackRowHeight, blankCells = setOf(6),
            )
            sl += 1
        }
        // Keep the total row + in-words line together on one page.
        if (y + 14f + 24f > pageHeight - margin) {
            ctx.nextPage(); canvas = ctx.canvas; y = margin
            y = drawTableHeaderRow(canvas, y, headers, strokeBorder, headerFillColor)
        }
        val totalAmount = summaries.sumOf { it.amount }
        val totalDelivered = summaries.sumOf { it.delivered }
        y = drawDataRow(
            canvas, y,
            listOf("", "", "Total =", moneyFormat.format(totalAmount), totalDelivered.toString(), "", ""),
            headers.map { it.second }, strokeBorder, alignRight = setOf(3, 4), bold = true,
        )
        // Breathing room before and after the in-words line.
        y += 12f
        if (y + 14f > pageHeight - margin) {
            ctx.nextPage(); canvas = ctx.canvas; y = margin
        }
        canvas.drawText(amountInWords(totalAmount), margin, y, textPaint(darkColor, 8.5f, bold = true))
        y += 14f
    }

    // ── Conveyance Vouchers (continuous flow) ──────────────────────────────
    // Draws one agent's voucher block at the current page position and
    // returns the canvas + y to continue from (may be a fresh page after a
    // mid-table break). Callers keep flowing — no per-agent page breaks.

    // ── Conveyance Vouchers: one bordered block per agent, exactly like the
    // sample "Conveyance Voucher.pdf" ─────────────────────────────────────
    // Block layout (single 9-column grid, shared outer border):
    //   title row (full span) → SL row (full span) → Agent ID row →
    //   Designation row → 9 column headers → one row per claim →
    //   2 blank spacer rows → G/Total row → In-word row.
    // Sample column widths measured off the reference PDF:
    // Date | From | To | Description | Vehicle | Amount | Attempt quantity |
    // Delivered | CID / Merchant — all headers/data centered, dark hairlines.
    private val voucherHeaders = listOf(
        "Date" to 0.100f, "From" to 0.101f, "To" to 0.100f, "Description" to 0.101f,
        "Vehicle" to 0.101f, "Amount" to 0.100f, "Attempt quantity" to 0.136f,
        "Delivered" to 0.100f, "CID / Merchant" to 0.161f,
    )
    private val voucherGridColor = Color.parseColor("#1F2937")
    private val voucherTitleH = 20f
    private val voucherSlH = 13f
    private val voucherAgentH = 14f
    private val voucherHeadH = 15f
    private val voucherRowH = 12f
    private val voucherGTotalH = 15f
    private val voucherInWordH = 17f
    private val voucherGap = 12f

    /** One renderable voucher line: a lone claim, or a LOT-ID group whose
     *  shared cells merge into one block (one row per consignment). All of
     *  an agent's same-LOT rows group together wherever they sit in
     *  date order — the block renders at its earliest row's position. */
    private sealed interface VoucherItem {
        data class Single(val claim: SupabaseClaimsReader.ClaimRow) : VoucherItem
        data class LotGroup(val claims: List<SupabaseClaimsReader.ClaimRow>) : VoucherItem
    }

    private fun lotIdOf(c: SupabaseClaimsReader.ClaimRow): String =
        if (c.category == "LOT Delivery") c.storeId.trim() else ""

    private fun partitionVoucherItems(
        agentClaims: List<SupabaseClaimsReader.ClaimRow>,
        scopeLotByAgent: Boolean = false,
    ): List<VoucherItem> {
        // Collect every LOT-ID group across the whole block (date-ordered
        // inside), then interleave with singles by earliest date — so a LOT
        // batch always renders as ONE merged block even when other claims
        // sit between its rows. Category-wise blocks scope the LOT key by
        // agent too, so two agents' same store-id trips never merge.
        val byLot = linkedMapOf<String, MutableList<SupabaseClaimsReader.ClaimRow>>()
        agentClaims.forEach { c ->
            val lotId = lotIdOf(c)
            if (lotId.isEmpty()) return@forEach
            val key = if (scopeLotByAgent) "$lotId|${c.agentSystemId}" else lotId
            byLot.getOrPut(key) { mutableListOf() }.add(c)
        }
        data class Slot(val date: String, val item: VoucherItem)
        val slots = mutableListOf<Slot>()
        agentClaims.forEach { c ->
            val lotId = lotIdOf(c)
            if (lotId.isEmpty()) {
                slots.add(Slot(c.placedDate, VoucherItem.Single(c)))
            } else {
                val key = if (scopeLotByAgent) "$lotId|${c.agentSystemId}" else lotId
                val group = byLot.remove(key) ?: return@forEach
                val ordered = group.sortedBy { it.placedDate }
                slots.add(
                    Slot(
                        ordered.minOf { it.placedDate },
                        if (ordered.size == 1) VoucherItem.Single(ordered[0])
                        else VoucherItem.LotGroup(ordered),
                    )
                )
            }
        }
        return slots.sortedBy { it.date }.map { it.item }
    }

    private fun voucherDateLabel(claim: SupabaseClaimsReader.ClaimRow): String =
        runCatching { voucherDateFormat.format(dateIsoFormat.parse(claim.placedDate) ?: java.util.Date()) }
            .getOrDefault(claim.placedDate)

    /** Column pixel boundaries for the 9-column voucher grid — widths are
     *  measured from the actual content (headers + every row of [claims]),
     *  so a short-CID report doesn't waste half the page on CID while
     *  squeezing the rest. Each column gets padding, a minimum (headers must
     *  fit) and a maximum cap (one long merchant can't eat the table);
     *  the result is normalized to exactly fill the content width. */
    private fun voucherGrid(
        claims: List<SupabaseClaimsReader.ClaimRow>,
        agentColumn: Boolean,
    ): Pair<List<Float>, List<Float>> {
        val headerPaint = textPaint(darkColor, 7f, bold = true)
        val dataPaint = textPaint(darkColor, 6.8f)
        val maxW = FloatArray(9)
        voucherHeaders.forEachIndexed { i, (label, _) ->
            val shown = if (i == 3 && agentColumn) "Agent" else label
            maxW[i] = headerPaint.measureText(shown)
        }
        claims.forEach { c ->
            val vals = listOf(
                voucherDateLabel(c), areaLabel(c.fromArea), areaLabel(c.toArea),
                if (agentColumn) c.agentName.ifBlank { displayAgentId(c) } else c.category,
                c.vehicle, moneyFormat.format(c.settledAmount),
                c.attemptQuantity.toString(), c.deliveredQuantity.toString(), c.cidOrMerchant,
            )
            vals.forEachIndexed { i, s ->
                maxW[i] = maxOf(maxW[i], dataPaint.measureText(s.ifBlank { "-" }))
            }
        }
        val pad = 8f
        val minW = floatArrayOf(46f, 40f, 32f, 50f, 36f, 36f, 52f, 42f, 62f)
        val capW = floatArrayOf(78f, 92f, 72f, 118f, 72f, 66f, 86f, 86f, 176f)
        val natural = maxW.mapIndexed { i, m -> (m + pad).coerceIn(minW[i], capW[i]) }
        val total = natural.sum().takeIf { it > 0f } ?: contentWidth
        val widths = natural.map { it / total * contentWidth }
        var x = margin
        val xs = mutableListOf(x)
        widths.forEach { w -> x += w; xs.add(x) }
        return widths to xs
    }

    /** Vertically-centered baseline for [textSize] inside a row of height [h]. */
    private fun centerBaseline(y: Float, h: Float, textSize: Float): Float =
        y + h / 2f + textSize * 0.35f

    private fun drawVoucherCellBox(canvas: Canvas, grid: Paint, x0: Float, y: Float, x1: Float, h: Float) {
        canvas.drawRect(x0, y, x1, y + h, grid)
    }

    /** Title row: full-span "Conveyance Voucher", bold + centered. */
    private fun drawVoucherTitleRow(canvas: Canvas, y: Float, xs: List<Float>, grid: Paint): Float {
        drawVoucherCellBox(canvas, grid, xs[0], y, xs[9], voucherTitleH)
        val paint = textPaint(darkColor, 11f, bold = true).apply { textAlign = Paint.Align.CENTER }
        fitTextSize(paint, 11f, "Conveyance Voucher", contentWidth - 8f)
        canvas.drawText("Conveyance Voucher", xs[0] + contentWidth / 2f, centerBaseline(y, voucherTitleH, 11f), paint)
        return y + voucherTitleH
    }

    /** SL row: full-span "SL : N", bold, left. */
    private fun drawVoucherSlRow(canvas: Canvas, y: Float, xs: List<Float>, grid: Paint, sl: Int): Float {
        drawVoucherCellBox(canvas, grid, xs[0], y, xs[9], voucherSlH)
        canvas.drawText("SL : $sl", xs[0] + 4f, centerBaseline(y, voucherSlH, 8f), textPaint(darkColor, 8f, bold = true))
        return y + voucherSlH
    }

    /** Agent row: [Agent ID] [id + Agent Name] [name spanning the rest]. */
    private fun drawVoucherAgentRow(
        canvas: Canvas, y: Float, xs: List<Float>, grid: Paint,
        claim: SupabaseClaimsReader.ClaimRow,
    ): Float {
        drawVoucherCellBox(canvas, grid, xs[0], y, xs[1], voucherAgentH)
        drawVoucherCellBox(canvas, grid, xs[1], y, xs[3], voucherAgentH)
        drawVoucherCellBox(canvas, grid, xs[3], y, xs[9], voucherAgentH)
        val labelPaint = textPaint(darkColor, 7.5f, bold = true).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText("Agent ID", (xs[0] + xs[1]) / 2f, centerBaseline(y, voucherAgentH, 7.5f), labelPaint)
        // Middle cell is split in two halves: id on the left, "Agent Name"
        // label centered in the right half — a long id can never reach the
        // label, so they can't overlap.
        val midX = (xs[1] + xs[3]) / 2f
        val idPaint = textPaint(darkColor, 7.5f)
        val id = displayAgentId(claim)
        fitTextSize(idPaint, 7.5f, id, (midX - xs[1]) - 6f)
        canvas.drawText(id, xs[1] + 3f, centerBaseline(y, voucherAgentH, 7.5f), idPaint)
        val nameLabelPaint = textPaint(darkColor, 7.5f, bold = true).apply { textAlign = Paint.Align.CENTER }
        fitTextSize(nameLabelPaint, 7.5f, "Agent Name", (xs[3] - midX) - 6f)
        canvas.drawText("Agent Name", (midX + xs[3]) / 2f, centerBaseline(y, voucherAgentH, 7.5f), nameLabelPaint)
        val namePaint = textPaint(darkColor, 7.5f)
        val name = claim.agentName.ifBlank { id }
        fitTextSize(namePaint, 7.5f, name, (xs[9] - xs[3]) - 6f)
        canvas.drawText(name, xs[3] + 3f, centerBaseline(y, voucherAgentH, 7.5f), namePaint)
        return y + voucherAgentH
    }

    /** Designation row: [Designation:] [Delivery Agent ×2] [Department:
     *  Fulfillment ×3] [empty] [empty] [empty]. */
    private fun drawVoucherDesigRow(
        canvas: Canvas, y: Float, xs: List<Float>, grid: Paint,
        claim: SupabaseClaimsReader.ClaimRow,
    ): Float {
        drawVoucherCellBox(canvas, grid, xs[0], y, xs[1], voucherAgentH)
        drawVoucherCellBox(canvas, grid, xs[1], y, xs[3], voucherAgentH)
        drawVoucherCellBox(canvas, grid, xs[3], y, xs[6], voucherAgentH)
        drawVoucherCellBox(canvas, grid, xs[6], y, xs[7], voucherAgentH)
        drawVoucherCellBox(canvas, grid, xs[7], y, xs[8], voucherAgentH)
        drawVoucherCellBox(canvas, grid, xs[8], y, xs[9], voucherAgentH)
        val labelPaint = textPaint(darkColor, 7.5f, bold = true).apply { textAlign = Paint.Align.CENTER }
        val leftLabel = textPaint(darkColor, 7.5f, bold = true)
        canvas.drawText("Designation:", xs[0] + 3f, centerBaseline(y, voucherAgentH, 7.5f), leftLabel)
        val desig = claim.agentDesignation.ifBlank { "Delivery Agent" }
        fitTextSize(labelPaint, 7.5f, desig, (xs[3] - xs[1]) - 6f)
        canvas.drawText(desig, (xs[1] + xs[3]) / 2f, centerBaseline(y, voucherAgentH, 7.5f), labelPaint)
        canvas.drawText("Department: Fulfillment", (xs[3] + xs[6]) / 2f, centerBaseline(y, voucherAgentH, 7.5f), labelPaint)
        return y + voucherAgentH
    }

    /** Column header row: 9 bold centered headers on white (sample-exact).
     *  Category-wise blocks swap the Description header for Agent. */
    private fun drawVoucherColHeader(
        canvas: Canvas, y: Float, widths: List<Float>, xs: List<Float>, grid: Paint,
        agentColumn: Boolean = false,
    ): Float {
        voucherHeaders.forEachIndexed { i, (label, _) ->
            drawVoucherCellBox(canvas, grid, xs[i], y, xs[i + 1], voucherHeadH)
            val shown = if (i == 3 && agentColumn) "Agent" else label
            val paint = textPaint(darkColor, 7f, bold = true).apply { textAlign = Paint.Align.CENTER }
            fitTextSize(paint, 7f, shown, widths[i] - 4f)
            canvas.drawText(shown, (xs[i] + xs[i + 1]) / 2f, centerBaseline(y, voucherHeadH, 7f), paint)
        }
        return y + voucherHeadH
    }

    /** One claim = one 9-cell row, every value centered (sample-exact: even
     *  consecutive duplicates repeat, nothing is hidden). Category-wise
     *  blocks show the agent in column 3 (the category is the block's own). */
    private fun drawVoucherDataRow(
        canvas: Canvas, y: Float, widths: List<Float>, xs: List<Float>, grid: Paint,
        claim: SupabaseClaimsReader.ClaimRow,
        agentColumn: Boolean = false,
    ): Float {
        val values = listOf(
            voucherDateLabel(claim), areaLabel(claim.fromArea), areaLabel(claim.toArea),
            if (agentColumn) claim.agentName.ifBlank { displayAgentId(claim) } else claim.category,
            claim.vehicle,
            moneyFormat.format(claim.settledAmount), claim.attemptQuantity.toString(),
            claim.deliveredQuantity.toString(), claim.cidOrMerchant,
        )
        values.forEachIndexed { i, text ->
            drawVoucherCellBox(canvas, grid, xs[i], y, xs[i + 1], voucherRowH)
            val paint = textPaint(darkColor, 6.8f).apply { textAlign = Paint.Align.CENTER }
            fitTextSize(paint, 6.8f, text.ifBlank { "-" }, widths[i] - 4f)
            canvas.drawText(text.ifBlank { "-" }, (xs[i] + xs[i + 1]) / 2f, centerBaseline(y, voucherRowH, 6.8f), paint)
        }
        return y + voucherRowH
    }

    /** Merged LOT block (rowspan): claims sharing one LOT ID are one trip, so
     *  the shared cells (Date…Delivered, Amount/Attempt/Delivered summed)
     *  merge into a single vertically-centered cell while every consignment
     *  keeps its own CID row. Horizontal rules only split the CID column. */
    private fun drawVoucherLotBlock(
        canvas: Canvas, y: Float, widths: List<Float>, xs: List<Float>, grid: Paint,
        group: List<SupabaseClaimsReader.ClaimRow>,
        agentColumn: Boolean = false,
    ): Float {
        val blockH = voucherRowH * group.size
        val first = group.first()
        // Outer box + full-height vertical dividers.
        canvas.drawRect(xs[0], y, xs[9], y + blockH, grid)
        for (i in 1 until 9) canvas.drawLine(xs[i], y, xs[i], y + blockH, grid)
        // Row splits inside the CID column only (merged cells have no splits).
        for (r in 1 until group.size) {
            val hy = y + voucherRowH * r
            canvas.drawLine(xs[8], hy, xs[9], hy, grid)
        }
        // Merged shared cells (cols 0..7), vertically centered.
        val midBase = centerBaseline(y, blockH, 6.8f)
        val merged = listOf(
            voucherDateLabel(first), areaLabel(first.fromArea), areaLabel(first.toArea),
            if (agentColumn) first.agentName.ifBlank { displayAgentId(first) } else first.category,
            first.vehicle,
            moneyFormat.format(group.sumOf { it.settledAmount }),
            group.sumOf { it.attemptQuantity }.toString(),
            group.sumOf { it.deliveredQuantity }.toString(),
        )
        merged.forEachIndexed { i, text ->
            val paint = textPaint(darkColor, 6.8f).apply { textAlign = Paint.Align.CENTER }
            fitTextSize(paint, 6.8f, text.ifBlank { "-" }, widths[i] - 4f)
            canvas.drawText(text.ifBlank { "-" }, (xs[i] + xs[i + 1]) / 2f, midBase, paint)
        }
        // Per-row CID cells.
        group.forEachIndexed { r, claim ->
            val paint = textPaint(darkColor, 6.8f).apply { textAlign = Paint.Align.CENTER }
            val cid = claim.cidOrMerchant.ifBlank { "-" }
            fitTextSize(paint, 6.8f, cid, widths[8] - 4f)
            canvas.drawText(cid, (xs[8] + xs[9]) / 2f, centerBaseline(y + voucherRowH * r, voucherRowH, 6.8f), paint)
        }
        return y + blockH
    }

    private fun drawVoucherBlankRow(canvas: Canvas, xs: List<Float>, grid: Paint, y: Float): Float {
        for (i in 0 until 9) drawVoucherCellBox(canvas, grid, xs[i], y, xs[i + 1], voucherRowH)
        return y + voucherRowH
    }

    /** G/Total row: [empty ×2] [G/Total = ×3] [total] [Total Delivered =]
     *  [delivered] [empty] — merges measured off the sample. */
    private fun drawVoucherGTotalRow(
        canvas: Canvas, y: Float, xs: List<Float>, grid: Paint,
        gTotal: Double, totalDelivered: Int,
    ): Float {
        drawVoucherCellBox(canvas, grid, xs[0], y, xs[1], voucherGTotalH)
        drawVoucherCellBox(canvas, grid, xs[1], y, xs[2], voucherGTotalH)
        drawVoucherCellBox(canvas, grid, xs[2], y, xs[5], voucherGTotalH)
        drawVoucherCellBox(canvas, grid, xs[5], y, xs[6], voucherGTotalH)
        drawVoucherCellBox(canvas, grid, xs[6], y, xs[7], voucherGTotalH)
        drawVoucherCellBox(canvas, grid, xs[7], y, xs[8], voucherGTotalH)
        drawVoucherCellBox(canvas, grid, xs[8], y, xs[9], voucherGTotalH)
        fun centered(x0: Float, x1: Float, text: String) {
            val paint = textPaint(darkColor, 8f, bold = true).apply { textAlign = Paint.Align.CENTER }
            fitTextSize(paint, 8f, text, (x1 - x0) - 4f)
            canvas.drawText(text, (x0 + x1) / 2f, centerBaseline(y, voucherGTotalH, 8f), paint)
        }
        centered(xs[2], xs[5], "G/Total =")
        centered(xs[5], xs[6], moneyFormat.format(gTotal))
        centered(xs[6], xs[7], "Total Delivered =")
        centered(xs[7], xs[8], totalDelivered.toString())
        return y + voucherGTotalH
    }

    /** In-word row: [In word:] [words spanning the rest, centered]. */
    private fun drawVoucherInWordRow(canvas: Canvas, y: Float, xs: List<Float>, grid: Paint, gTotal: Double): Float {
        drawVoucherCellBox(canvas, grid, xs[0], y, xs[1], voucherInWordH)
        drawVoucherCellBox(canvas, grid, xs[1], y, xs[9], voucherInWordH)
        canvas.drawText("In word:", xs[0] + 3f, centerBaseline(y, voucherInWordH, 7.5f), textPaint(darkColor, 7.5f, bold = true))
        val words = amountInWords(gTotal)
        val paint = textPaint(darkColor, 8f, bold = true).apply { textAlign = Paint.Align.CENTER }
        fitTextSize(paint, 8f, words, (xs[9] - xs[1]) - 6f)
        canvas.drawText(words, (xs[1] + xs[9]) / 2f, centerBaseline(y, voucherInWordH, 8f), paint)
        return y + voucherInWordH
    }

    private fun drawConveyanceVoucherAgent(
        ctx: PageCtx,
        startCanvas: Canvas,
        startY: Float,
        widths: List<Float>,
        xs: List<Float>,
        agentClaims: List<SupabaseClaimsReader.ClaimRow>,
        sl: Int,
    ): Pair<Canvas, Float> {
        var canvas = startCanvas
        var y = startY
        val grid = strokePaint(voucherGridColor, 0.7f)
        val first = agentClaims.first()
        val items = partitionVoucherItems(agentClaims)
        val gTotal = agentClaims.sumOf { it.settledAmount }
        val totalDelivered = agentClaims.sumOf { it.deliveredQuantity }

        fun itemHeight(item: VoucherItem): Float = when (item) {
            is VoucherItem.Single -> voucherRowH
            is VoucherItem.LotGroup -> voucherRowH * item.claims.size
        }

        // Keep at least the block head + column headers + first row together.
        val headH = voucherTitleH + voucherSlH + voucherAgentH * 2 + voucherHeadH
        if (y + headH + voucherRowH > pageHeight - margin) {
            ctx.nextPage(); canvas = ctx.canvas; y = margin
        }
        y = drawVoucherTitleRow(canvas, y, xs, grid)
        y = drawVoucherSlRow(canvas, y, xs, grid, sl)
        y = drawVoucherAgentRow(canvas, y, xs, grid, first)
        y = drawVoucherDesigRow(canvas, y, xs, grid, first)
        y = drawVoucherColHeader(canvas, y, widths, xs, grid)

        // Rows: LOT claims sharing a LOT ID (store_id) render as one merged
        // rowspan block; the rest render as normal single rows — all in
        // expense-date order. A table that outgrows the page continues with
        // its column headers repeated.
        val freshPageH = pageHeight - margin - margin
        items.forEach { item ->
            val h = itemHeight(item)
            val overSized = h > freshPageH - voucherHeadH
            if (!overSized && y + h > pageHeight - margin) {
                ctx.nextPage(); canvas = ctx.canvas; y = margin
                y = drawVoucherColHeader(canvas, y, widths, xs, grid)
            }
            y = when (item) {
                is VoucherItem.Single -> drawVoucherDataRow(canvas, y, widths, xs, grid, item.claim)
                is VoucherItem.LotGroup ->
                    if (!overSized) drawVoucherLotBlock(canvas, y, widths, xs, grid, item.claims)
                    else {
                        // Taller than a page: fall back to plain rows so no
                        // data is ever lost.
                        var yy = y
                        item.claims.forEach { claim ->
                            if (yy + voucherRowH > pageHeight - margin) {
                                ctx.nextPage(); canvas = ctx.canvas; yy = margin
                                yy = drawVoucherColHeader(canvas, yy, widths, xs, grid)
                            }
                            yy = drawVoucherDataRow(canvas, yy, widths, xs, grid, claim)
                        }
                        yy
                    }
            }
        }
        // Sample-style tail: 2 blank spacer rows, then G/Total + In-word —
        // kept together on one page.
        val tailH = voucherRowH * 2 + voucherGTotalH + voucherInWordH
        if (y + tailH > pageHeight - margin) {
            ctx.nextPage(); canvas = ctx.canvas; y = margin
            y = drawVoucherColHeader(canvas, y, widths, xs, grid)
        }
        y = drawVoucherBlankRow(canvas, xs, grid, y)
        y = drawVoucherBlankRow(canvas, xs, grid, y)
        y = drawVoucherGTotalRow(canvas, y, xs, grid, gTotal, totalDelivered)
        y = drawVoucherInWordRow(canvas, y, xs, grid, gTotal)
        return canvas to y
    }

    /** Category row (category-wise blocks): [Category] [name ×2]
     *  [Agents: N · Total: Tk X spanning the rest]. */
    private fun drawVoucherCategoryRow(
        canvas: Canvas, y: Float, xs: List<Float>, grid: Paint,
        category: String, agentCount: Int, gTotal: Double,
    ): Float {
        drawVoucherCellBox(canvas, grid, xs[0], y, xs[1], voucherAgentH)
        drawVoucherCellBox(canvas, grid, xs[1], y, xs[3], voucherAgentH)
        drawVoucherCellBox(canvas, grid, xs[3], y, xs[9], voucherAgentH)
        val labelPaint = textPaint(darkColor, 7.5f, bold = true).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText("Category", (xs[0] + xs[1]) / 2f, centerBaseline(y, voucherAgentH, 7.5f), labelPaint)
        val namePaint = textPaint(darkColor, 7.5f)
        fitTextSize(namePaint, 7.5f, category, (xs[3] - xs[1]) - 6f)
        canvas.drawText(category, xs[1] + 3f, centerBaseline(y, voucherAgentH, 7.5f), namePaint)
        canvas.drawText(
            "Agents: $agentCount · Total: Tk ${moneyFormat.format(gTotal)}",
            (xs[3] + xs[9]) / 2f, centerBaseline(y, voucherAgentH, 7.5f), labelPaint,
        )
        return y + voucherAgentH
    }

    /** Agents row (category-wise blocks): [Agents] [comma-joined agent
     *  names spanning the rest]. */
    private fun drawVoucherAgentsRow(
        canvas: Canvas, y: Float, xs: List<Float>, grid: Paint,
        agentNames: List<String>,
    ): Float {
        drawVoucherCellBox(canvas, grid, xs[0], y, xs[1], voucherAgentH)
        drawVoucherCellBox(canvas, grid, xs[1], y, xs[9], voucherAgentH)
        val labelPaint = textPaint(darkColor, 7.5f, bold = true).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText("Agents", (xs[0] + xs[1]) / 2f, centerBaseline(y, voucherAgentH, 7.5f), labelPaint)
        val namePaint = textPaint(darkColor, 7.5f)
        val shown = agentNames.joinToString(", ")
        fitTextSize(namePaint, 7.5f, shown, (xs[9] - xs[1]) - 6f)
        canvas.drawText(shown, xs[1] + 3f, centerBaseline(y, voucherAgentH, 7.5f), namePaint)
        return y + voucherAgentH
    }

    /** One category's voucher block: every agent's rows together in a single
     *  bordered block (same skeleton as the agent block; column 3 shows the
     *  agent since the category is the block's own). LOT trips stay scoped
     *  per agent so two agents' same store-id never merges. */
    private fun drawConveyanceVoucherCategory(
        ctx: PageCtx,
        startCanvas: Canvas,
        startY: Float,
        widths: List<Float>,
        xs: List<Float>,
        category: String,
        catClaims: List<SupabaseClaimsReader.ClaimRow>,
        sl: Int,
    ): Pair<Canvas, Float> {
        var canvas = startCanvas
        var y = startY
        val grid = strokePaint(voucherGridColor, 0.7f)
        val agentNames = catClaims.map { it.agentName.ifBlank { displayAgentId(it) } }
            .distinct().sorted()
        val gTotal = catClaims.sumOf { it.settledAmount }
        val totalDelivered = catClaims.sumOf { it.deliveredQuantity }
        val items = partitionVoucherItems(catClaims, scopeLotByAgent = true)

        fun itemHeight(item: VoucherItem): Float = when (item) {
            is VoucherItem.Single -> voucherRowH
            is VoucherItem.LotGroup -> voucherRowH * item.claims.size
        }

        val headH = voucherTitleH + voucherSlH + voucherAgentH * 2 + voucherHeadH
        if (y + headH + voucherRowH > pageHeight - margin) {
            ctx.nextPage(); canvas = ctx.canvas; y = margin
        }
        y = drawVoucherTitleRow(canvas, y, xs, grid)
        y = drawVoucherSlRow(canvas, y, xs, grid, sl)
        y = drawVoucherCategoryRow(canvas, y, xs, grid, category, agentNames.size, gTotal)
        y = drawVoucherAgentsRow(canvas, y, xs, grid, agentNames)
        y = drawVoucherColHeader(canvas, y, widths, xs, grid, agentColumn = true)

        val freshPageH = pageHeight - margin - margin
        items.forEach { item ->
            val h = itemHeight(item)
            val overSized = h > freshPageH - voucherHeadH
            if (!overSized && y + h > pageHeight - margin) {
                ctx.nextPage(); canvas = ctx.canvas; y = margin
                y = drawVoucherColHeader(canvas, y, widths, xs, grid, agentColumn = true)
            }
            y = when (item) {
                is VoucherItem.Single -> drawVoucherDataRow(canvas, y, widths, xs, grid, item.claim, agentColumn = true)
                is VoucherItem.LotGroup ->
                    if (!overSized) drawVoucherLotBlock(canvas, y, widths, xs, grid, item.claims, agentColumn = true)
                    else {
                        var yy = y
                        item.claims.forEach { claim ->
                            if (yy + voucherRowH > pageHeight - margin) {
                                ctx.nextPage(); canvas = ctx.canvas; yy = margin
                                yy = drawVoucherColHeader(canvas, yy, widths, xs, grid, agentColumn = true)
                            }
                            yy = drawVoucherDataRow(canvas, yy, widths, xs, grid, claim, agentColumn = true)
                        }
                        yy
                    }
            }
        }
        val tailH = voucherRowH * 2 + voucherGTotalH + voucherInWordH
        if (y + tailH > pageHeight - margin) {
            ctx.nextPage(); canvas = ctx.canvas; y = margin
            y = drawVoucherColHeader(canvas, y, widths, xs, grid, agentColumn = true)
        }
        y = drawVoucherBlankRow(canvas, xs, grid, y)
        y = drawVoucherBlankRow(canvas, xs, grid, y)
        y = drawVoucherGTotalRow(canvas, y, xs, grid, gTotal, totalDelivered)
        y = drawVoucherInWordRow(canvas, y, xs, grid, gTotal)
        return canvas to y
    }

    // ── Unsettled Bills page ─────────────────────────────────────────────────
    // One row per not-yet-settled claim in range (pending/approved/...),
    // stamped UNSETTLED so it can never be mistaken for an expense page.

    private val unsettledHeaders = listOf(
        "Date" to 0.11f, "Agent" to 0.20f, "Category" to 0.20f,
        "CID / Merchant" to 0.20f, "Requested" to 0.14f, "Status" to 0.15f,
    )

    private fun drawUnsettledPage(
        ctx: PageCtx,
        unsettled: List<SupabaseClaimsReader.ClaimRow>,
    ) {
        var canvas = ctx.canvas
        var y = margin
        val titlePaint = textPaint(darkColor, 12f, bold = true).apply { textAlign = Paint.Align.CENTER }
        val stampPaint = textPaint(Color.parseColor("#B91C1C"), 16f, bold = true).apply { textAlign = Paint.Align.CENTER }
        val strokeBorder = strokePaint(borderColor, 0.6f)

        canvas.drawText("Unsettled Bills", margin + contentWidth / 2, y + 12f, titlePaint)
        y += 18f
        // Stamp box.
        val stampH = 26f
        canvas.drawRect(margin, y, margin + contentWidth, y + stampH, strokePaint(Color.parseColor("#B91C1C"), 1f))
        canvas.drawText("UNSETTLED", margin + contentWidth / 2, y + 18f, stampPaint)
        y += stampH + 10f

        y = drawTableHeaderRow(canvas, y, unsettledHeaders, strokeBorder, headerFillColor)
        val weights = unsettledHeaders.map { it.second }
        unsettled.forEach { row ->
            if (y + 14f > pageHeight - margin - 30f) {
                ctx.nextPage(); canvas = ctx.canvas; y = margin
                y = drawTableHeaderRow(canvas, y, unsettledHeaders, strokeBorder, headerFillColor)
            }
            y = drawDataRow(
                canvas, y,
                listOf(
                    voucherDateLabel(row),
                    row.agentName.ifBlank { displayAgentId(row) },
                    row.category,
                    row.cidOrMerchant,
                    moneyFormat.format(row.requestedAmount),
                    row.status,
                ),
                weights, strokeBorder, alignRight = setOf(4),
            )
        }
        val totalRequested = unsettled.sumOf { it.requestedAmount }
        y += 10f
        if (y + 14f > pageHeight - margin) {
            ctx.nextPage(); canvas = ctx.canvas; y = margin
        }
        y = drawSummaryTotalRow(canvas, y, "Total Unsettled (requested)", totalRequested, strokeBorder)
        y += 14f
    }

    // ── Shared drawing helpers ───────────────────────────────────────────────

    // Every word in the report is Liberation Sans (bundled, SIL OFL) — the
    // Arial-metric look of the reference PDFs, identical on every device.
    // Falls back to the system sans only when the bundled fonts haven't been
    // initialised (generate() always inits when it receives a Context).
    private fun textPaint(colorInt: Int, size: Float, bold: Boolean = false, italic: Boolean = false, sans: Boolean = true): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            textSize = size
            typeface = PdfFonts.of(bold, italic) ?: when {
                bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                else -> Typeface.DEFAULT
            }
        }

    private fun fillPaint(colorInt: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt; style = Paint.Style.FILL }

    private fun strokePaint(colorInt: Int, width: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt; style = Paint.Style.STROKE; strokeWidth = width }

    // Never "…" text: shrink the size until it fits the cell (floor 5pt).
    // Long merchant names get smaller instead of cut off.
    private val minTextSize = 5f

    private fun fitTextSize(paint: Paint, baseSize: Float, text: String, maxWidth: Float): Float {
        if (text.isEmpty() || maxWidth <= 0f) return baseSize
        var size = baseSize
        paint.textSize = size
        while (size > minTextSize && paint.measureText(text) > maxWidth) {
            size -= 0.5f
            paint.textSize = size
        }
        return size
    }

    /** A single row split into two label:value pairs (or one, if [value2]/[label2] blank). */
    private fun drawTwoColLabelRow(
        canvas: Canvas, y: Float, label: String, value: String,
        labelPaint: Paint, valuePaint: Paint, strokeBorder: Paint,
        isTwoLabels: Boolean = false, boldValue: Boolean = false, alignRightValue: Boolean = false,
    ): Float {
        val rowHeight = 16f
        canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, strokeBorder)
        if (isTwoLabels) {
            canvas.drawText(label, margin + 4f, y + rowHeight - 5f, labelPaint)
            canvas.drawLine(margin + contentWidth / 2f, y, margin + contentWidth / 2f, y + rowHeight, strokeBorder)
            canvas.drawText(value, margin + contentWidth / 2f + 4f, y + rowHeight - 5f, labelPaint)
        } else {
            val labelWidth = contentWidth * 0.35f
            canvas.drawText(label, margin + 4f, y + rowHeight - 5f, labelPaint)
            canvas.drawLine(margin + labelWidth, y, margin + labelWidth, y + rowHeight, strokeBorder)
            val paint = if (boldValue) textPaint(darkColor, 8.5f, bold = true) else valuePaint
            if (alignRightValue) {
                val prevAlign = paint.textAlign
                paint.textAlign = Paint.Align.RIGHT
                fitTextSize(paint, 8.5f, value, contentWidth - labelWidth - 8f)
                canvas.drawText(value, margin + contentWidth - 4f, y + rowHeight - 5f, paint)
                paint.textAlign = prevAlign
            } else {
                canvas.drawText(value, margin + labelWidth + 4f, y + rowHeight - 5f, paint)
            }
        }
        return y + rowHeight
    }

    private fun drawSectionHeaderRow(canvas: Canvas, y: Float, text: String, strokeBorder: Paint, labelPaint: Paint): Float {
        val rowHeight = 16f
        canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, fillPaint(lightFillColor))
        canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, strokeBorder)
        canvas.drawText(text, margin + contentWidth / 2f, y + rowHeight - 5f, textPaint(darkColor, 8.5f, bold = true).apply { textAlign = Paint.Align.CENTER })
        return y + rowHeight
    }

    private fun drawTableHeaderRow(canvas: Canvas, y: Float, cols: List<Pair<String, Float>>, strokeBorder: Paint, fillColor: Int): Float {
        val rowHeight = 16f
        canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, fillPaint(fillColor))
        var x = margin
        cols.forEach { (label, weight) ->
            val width = weight * contentWidth
            val headerPaint = textPaint(darkColor, 7.5f, bold = true)
            fitTextSize(headerPaint, 7.5f, label, width - 6f)
            canvas.drawText(label, x + 3f, y + rowHeight - 5f, headerPaint)
            x += width
        }
        canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, strokeBorder)
        return y + rowHeight
    }

    private fun drawDataRow(
        canvas: Canvas, y: Float, values: List<String>, weights: List<Float>,
        strokeBorder: Paint, alignRight: Set<Int> = emptySet(), bold: Boolean = false,
        rowHeight: Float = 14f, blankCells: Set<Int> = emptySet(),
    ): Float {
        var x = margin
        val leftPaint = textPaint(darkColor, 7.5f, bold = bold)
        val rightPaint = textPaint(darkColor, 7.5f, bold = bold).apply { textAlign = Paint.Align.RIGHT }
        values.forEachIndexed { i, value ->
            val width = weights.getOrElse(i) { 0.1f } * contentWidth
            // blankCells (e.g. signature) stay empty for signing — never "-".
            val text = if (i in blankCells) "" else value.ifBlank { "-" }
            // Vertically centered (tall acknowledgement rows used to sit on
            // the bottom border).
            val base = centerBaseline(y, rowHeight, 7.5f)
            if (i in alignRight) {
                fitTextSize(rightPaint, 7.5f, text, width - 6f)
                canvas.drawText(text, x + width - 3f, base, rightPaint)
            } else {
                fitTextSize(leftPaint, 7.5f, text, width - 6f)
                canvas.drawText(text, x + 3f, base, leftPaint)
            }
            x += width
        }
        canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, strokeBorder)
        return y + rowHeight
    }

    private fun drawSummaryGroupHeader(canvas: Canvas, y: Float, groupLabel: String, amountLabel: String, strokeBorder: Paint, fillColor: Int): Float {
        val rowHeight = 14f
        canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, fillPaint(fillColor))
        val headerPaint = textPaint(darkColor, 7.5f, bold = true)
        val rightPaint = textPaint(darkColor, 7.5f, bold = true).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText(groupLabel, margin + 3f, y + rowHeight - 4f, headerPaint)
        canvas.drawText(amountLabel, margin + contentWidth - 3f, y + rowHeight - 4f, rightPaint)
        canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, strokeBorder)
        return y + rowHeight
    }

    private fun drawSummaryLineRow(canvas: Canvas, y: Float, sl: Int, label: String, amount: Double, rowHeight: Float, strokeBorder: Paint): Float {
        val leftPaint = textPaint(darkColor, 7.5f)
        val rightPaint = textPaint(darkColor, 7.5f).apply { textAlign = Paint.Align.RIGHT }
        val slWidth = contentWidth * 0.06f
        canvas.drawText(sl.toString(), margin + 3f, y + rowHeight - 3f, leftPaint)
        fitTextSize(leftPaint, 7.5f, label, contentWidth * 0.7f)
        canvas.drawText(label, margin + slWidth, y + rowHeight - 3f, leftPaint)
        canvas.drawText(moneyFormat.format(amount), margin + contentWidth - 3f, y + rowHeight - 3f, rightPaint)
        canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, strokeBorder)
        return y + rowHeight
    }

    private fun drawSummaryTotalRow(canvas: Canvas, y: Float, label: String, amount: Double, strokeBorder: Paint, emphasize: Boolean = false): Float {
        val rowHeight = 14f
        canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, fillPaint(lightFillColor))
        val labelPaint = textPaint(darkColor, if (emphasize) 9f else 7.5f, bold = true)
        val valuePaint = textPaint(darkColor, if (emphasize) 9f else 7.5f, bold = true).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText(label, margin + 3f, y + rowHeight - 4f, labelPaint)
        canvas.drawText(moneyFormat.format(amount), margin + contentWidth - 3f, y + rowHeight - 4f, valuePaint)
        canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, strokeBorder)
        return y + rowHeight
    }

    /** English number-to-words for the "In word: ..." line — Taka only, no paisa
     *  (every amount here is a whole-number petty cash figure). */
    private fun amountInWords(amount: Double): String {
        val n = amount.toLong()
        if (n == 0L) return "ZERO TAKA ONLY"
        val ones = listOf("", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE", "TEN",
            "ELEVEN", "TWELVE", "THIRTEEN", "FOURTEEN", "FIFTEEN", "SIXTEEN", "SEVENTEEN", "EIGHTEEN", "NINETEEN")
        val tens = listOf("", "", "TWENTY", "THIRTY", "FORTY", "FIFTY", "SIXTY", "SEVENTY", "EIGHTY", "NINETY")

        fun twoDigits(v: Int): String = when {
            v < 20 -> ones[v]
            else -> tens[v / 10] + (if (v % 10 != 0) " " + ones[v % 10] else "")
        }
        fun threeDigits(v: Int): String {
            val h = v / 100
            val rest = v % 100
            return buildList {
                if (h > 0) add("${ones[h]} HUNDRED")
                if (rest > 0) add(twoDigits(rest))
            }.joinToString(" ")
        }

        var remaining = n
        val crore = remaining / 10_000_000; remaining %= 10_000_000
        val lakh = remaining / 100_000; remaining %= 100_000
        val thousand = remaining / 1_000; remaining %= 1_000
        val hundred = remaining.toInt()

        val parts = buildList {
            if (crore > 0) add("${threeDigits(crore.toInt())} CRORE")
            if (lakh > 0) add("${threeDigits(lakh.toInt())} LAKH")
            if (thousand > 0) add("${threeDigits(thousand.toInt())} THOUSAND")
            if (hundred > 0) add(threeDigits(hundred))
        }
        return "${parts.joinToString(" ")} TAKA ONLY"
    }
}
