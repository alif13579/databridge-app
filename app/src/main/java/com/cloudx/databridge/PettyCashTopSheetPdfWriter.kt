package com.cloudx.databridge

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
 *          break, no huge gaps), each agent headed by its SL block.
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
    private const val pageWidth = 595   // A4 width in points (210mm @ 72pt/in)
    private const val pageHeight = 842  // A4 height in points (297mm @ 72pt/in)
    private const val margin = 36f
    private val contentWidth get() = pageWidth - margin * 2

    private val darkColor = Color.parseColor("#0F172A")
    private val mutedColor = Color.parseColor("#64748B")
    private val borderColor = Color.parseColor("#CBD5E1")
    private val headerFillColor = Color.parseColor("#0F172A")
    private val lightFillColor = Color.parseColor("#F1F5F9")

    private val moneyFormat = NumberFormat.getNumberInstance(Locale.US)
    private val dateDisplayFormat = SimpleDateFormat("dd-MM-yy", Locale.US)
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
    private class PageCtx(val pdf: PdfDocument) {
        private var pageNum = 0
        private lateinit var page: PdfDocument.Page
        lateinit var canvas: Canvas
            private set

        init {
            nextPage()
        }

        fun nextPage() {
            if (pageNum > 0) pdf.finishPage(page)
            pageNum += 1
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
            canvas = page.canvas
        }

        fun finish() {
            pdf.finishPage(page)
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
    ) {
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
        val pdf = PdfDocument()
        val ctx = PageCtx(pdf)

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

        // ── Conveyance Vouchers — continuous flow like the sample PDF: agents
        // follow one another with no forced page break (no huge gaps); a
        // table that outgrows the page continues with its header repeated.
        // Agents ordered by their first appearance in the settled list,
        // matching the Agent Acknowledgement page's SL order. ──
        val conveyanceClaims = settled.filter { isConveyanceClaim(it) }
        val agentOrder = LinkedHashSet<String>()
        settled.forEach { agentOrder.add(it.agentSystemId) }
        ctx.nextPage()
        var voucherSl = 1
        var voucherCanvas = ctx.canvas
        var voucherY = margin
        agentOrder.forEach { agentSystemId ->
            val agentClaims = conveyanceClaims.filter { it.agentSystemId == agentSystemId }
                .sortedBy { it.placedDate }
            if (agentClaims.isEmpty()) return@forEach
            val drawn = drawConveyanceVoucherAgent(ctx, voucherCanvas, voucherY, agentClaims, voucherSl)
            voucherCanvas = drawn.first
            voucherY = drawn.second
            voucherSl += 1
        }

        // ── Unsettled Bills — every in-range claim still awaiting money
        // (pending/approved/…; rejected/cancelled are dead, never listed),
        // one row each, stamped UNSETTLED. Skipped when there are none. ──
        val unsettled = claims.filter {
            val s = it.status.trim().lowercase()
            s != "settled" && s != "rejected" && s != "cancelled" && s != "cancel"
        }.sortedBy { it.placedDate }
        if (unsettled.isNotEmpty()) {
            ctx.nextPage()
            drawUnsettledPage(ctx, unsettled)
        }

        ctx.finish()
        outFile.outputStream().use { pdf.writeTo(it) }
        pdf.close()
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

    // Sample voucher columns (x-positions measured off the reference PDF):
    // Date | From | To | Description | Vehicle | Amount | Attempt quantity |
    // Delivered | CID / Merchant — borderless, small bold headers.
    private val voucherHeaders = listOf(
        "Date" to 0.10f, "From" to 0.11f, "To" to 0.08f, "Description" to 0.11f,
        "Vehicle" to 0.10f, "Amount" to 0.10f, "Attempt quantity" to 0.12f,
        "Delivered" to 0.11f, "CID / Merchant" to 0.17f,
    )
    private val voucherRowH = 11f
    private val voucherHeadH = 13f

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

    private fun partitionVoucherItems(agentClaims: List<SupabaseClaimsReader.ClaimRow>): List<VoucherItem> {
        // Collect every LOT-ID group across the whole agent (date-ordered
        // inside), then interleave with singles by earliest date — so a LOT
        // batch always renders as ONE merged block even when other claims
        // sit between its rows.
        val byLot = linkedMapOf<String, MutableList<SupabaseClaimsReader.ClaimRow>>()
        agentClaims.forEach { c ->
            val lotId = lotIdOf(c)
            if (lotId.isNotEmpty()) byLot.getOrPut(lotId) { mutableListOf() }.add(c)
        }
        data class Slot(val date: String, val item: VoucherItem)
        val slots = mutableListOf<Slot>()
        agentClaims.forEach { c ->
            val lotId = lotIdOf(c)
            if (lotId.isEmpty()) {
                slots.add(Slot(c.placedDate, VoucherItem.Single(c)))
            } else {
                val group = byLot.remove(lotId) ?: return@forEach
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
        runCatching { dateDisplayFormat.format(dateIsoFormat.parse(claim.placedDate) ?: java.util.Date()) }
            .getOrDefault(claim.placedDate)

    /** Borderless sample-style column headers (small bold sans). */
    private fun drawVoucherHeaderRow(canvas: Canvas, y: Float): Float {
        var x = margin
        val weights = voucherHeaders.map { it.second }
        val widths = weights.map { it * contentWidth }
        voucherHeaders.forEachIndexed { i, (label, _) ->
            val w = widths[i]
            val right = i in 5..7
            val paint = textPaint(darkColor, 6f, bold = true, sans = true).apply {
                if (right) textAlign = Paint.Align.RIGHT
            }
            fitTextSize(paint, 6f, label, w - 4f)
            if (right) canvas.drawText(label, x + w - 2f, y + voucherHeadH - 3f, paint)
            else canvas.drawText(label, x + 2f, y + voucherHeadH - 3f, paint)
            x += w
        }
        return y + voucherHeadH
    }

    private fun drawVoucherRow(
        canvas: Canvas, y: Float,
        claim: SupabaseClaimsReader.ClaimRow,
    ): Float {
        var x = margin
        val widths = voucherHeaders.map { it.second * contentWidth }
        val left = textPaint(darkColor, 6.6f, sans = true)
        val right = textPaint(darkColor, 6.6f, sans = true).apply { textAlign = Paint.Align.RIGHT }
        val values = listOf(
            voucherDateLabel(claim), areaLabel(claim.fromArea), areaLabel(claim.toArea), claim.category, claim.vehicle,
            moneyFormat.format(claim.settledAmount), claim.attemptQuantity.toString(),
            claim.deliveredQuantity.toString(), claim.cidOrMerchant,
        )
        values.forEachIndexed { i, text ->
            val w = widths[i]
            if (i in 5..7) {
                fitTextSize(right, 6.6f, text, w - 4f)
                canvas.drawText(text, x + w - 2f, y + voucherRowH - 2.5f, right)
            } else {
                fitTextSize(left, 6.6f, text, w - 4f)
                canvas.drawText(text, x + 2f, y + voucherRowH - 2.5f, left)
            }
            x += w
        }
        return y + voucherRowH
    }

    /** Merged LOT block, sample-style: NO lines at all — the shared values
     *  (Date/From/To/Description/Vehicle/Amount-SUM/Attempted-SUM/Delivered-
     *  SUM) appear ONCE, vertically centered, while every consignment keeps
     *  its own CID row. Shared values come from the first row (a LOT batch
     *  is one trip: same date/route/vehicle by construction). */
    private fun drawLotBlock(
        canvas: Canvas, y: Float,
        group: List<SupabaseClaimsReader.ClaimRow>,
    ): Float {
        val blockH = voucherRowH * group.size
        val widths = voucherHeaders.map { it.second * contentWidth }
        val first = group.first()

        // Merged cells (cols 0..7), vertically centered.
        var x = margin
        val xs = mutableListOf(margin)
        widths.forEach { w -> x += w; xs.add(x) }
        val midY = y + blockH / 2f + 2.5f
        val leftPaint = textPaint(darkColor, 6.6f, sans = true)
        val rightPaint = textPaint(darkColor, 6.6f, sans = true).apply { textAlign = Paint.Align.RIGHT }
        val merged = listOf(
            voucherDateLabel(first) to false,
            areaLabel(first.fromArea) to false,
            areaLabel(first.toArea) to false,
            first.category to false,
            first.vehicle to false,
            moneyFormat.format(group.sumOf { it.settledAmount }) to true,
            group.sumOf { it.attemptQuantity }.toString() to true,
            group.sumOf { it.deliveredQuantity }.toString() to true,
        )
        merged.forEachIndexed { i, (text, right) ->
            val w = widths[i]
            if (right) {
                fitTextSize(rightPaint, 6.6f, text, w - 4f)
                canvas.drawText(text, xs[i] + w - 2f, midY, rightPaint)
            } else {
                fitTextSize(leftPaint, 6.6f, text, w - 4f)
                canvas.drawText(text, xs[i] + 2f, midY, leftPaint)
            }
        }
        // Per-row CID cells.
        val cidPaint = textPaint(darkColor, 6.6f, sans = true)
        group.forEachIndexed { i, claim ->
            fitTextSize(cidPaint, 6.6f, claim.cidOrMerchant, widths[8] - 4f)
            canvas.drawText(claim.cidOrMerchant, xs[8] + 2f, y + voucherRowH * i + voucherRowH - 2.5f, cidPaint)
        }
        return y + blockH
    }

    private fun drawConveyanceVoucherAgent(
        ctx: PageCtx,
        startCanvas: Canvas,
        startY: Float,
        agentClaims: List<SupabaseClaimsReader.ClaimRow>,
        sl: Int,
    ): Pair<Canvas, Float> {
        var canvas = startCanvas
        var y = startY
        val titlePaint = textPaint(darkColor, 11f, bold = true, sans = true)
        val labelPaint = textPaint(darkColor, 7.5f, bold = true, sans = true)
        val valuePaint = textPaint(darkColor, 7.5f, sans = true)
        val smallBold = textPaint(darkColor, 7f, bold = true, sans = true)
        val first = agentClaims.first()
        val agentName = first.agentName

        // Keep at least the agent header + column header + first row together.
        if (y + 14f + 12f + 26f + voucherHeadH + voucherRowH > pageHeight - margin) {
            ctx.nextPage(); canvas = ctx.canvas; y = margin
        }
        // Sample-style agent header: title, SL, then two label/value rows
        // (labels bold, values normal), no boxes.
        canvas.drawText("Conveyance Voucher", margin, y + 10f, titlePaint)
        y += 15f
        canvas.drawText("SL : $sl", margin, y + 8f, smallBold)
        y += 12f
        y = drawVoucherAgentLine(canvas, y, "Agent ID", displayAgentId(first), "Agent Name", agentName, labelPaint, valuePaint)
        y = drawVoucherAgentLine(
            canvas, y, "Designation", first.agentDesignation.ifBlank { "Delivery Agent" },
            "Department", "Fulfillment", labelPaint, valuePaint,
        )
        y += 5f

        y = drawVoucherHeaderRow(canvas, y)
        // Rows: LOT claims sharing a LOT ID (store_id) render as one merged
        // block (shared values once, one row per consignment); the rest
        // render as normal single rows — all in expense-date order.
        val items = partitionVoucherItems(agentClaims)
        items.forEach { item ->
            when (item) {
                is VoucherItem.Single -> {
                    if (y + voucherRowH > pageHeight - margin - 46f) {
                        ctx.nextPage(); canvas = ctx.canvas; y = margin
                        canvas.drawText("$agentName (contd.)", margin, y + 8f, valuePaint)
                        y += 12f
                        y = drawVoucherHeaderRow(canvas, y)
                    }
                    y = drawVoucherRow(canvas, y, item.claim)
                }
                is VoucherItem.LotGroup -> {
                    val blockH = voucherRowH * item.claims.size
                    val freshPageH = pageHeight - margin - 46f - margin
                    if (y + blockH > pageHeight - margin - 46f && blockH <= freshPageH) {
                        ctx.nextPage(); canvas = ctx.canvas; y = margin
                        canvas.drawText("$agentName (contd.)", margin, y + 8f, valuePaint)
                        y += 12f
                        y = drawVoucherHeaderRow(canvas, y)
                    }
                    if (blockH <= freshPageH) {
                        y = drawLotBlock(canvas, y, item.claims)
                    } else {
                        // Oversized group (taller than a page): fall back to
                        // plain rows so no data is ever lost.
                        item.claims.forEach { claim ->
                            if (y + voucherRowH > pageHeight - margin - 46f) {
                                ctx.nextPage(); canvas = ctx.canvas; y = margin
                                canvas.drawText("$agentName (contd.)", margin, y + 8f, valuePaint)
                                y += 12f
                                y = drawVoucherHeaderRow(canvas, y)
                            }
                            y = drawVoucherRow(canvas, y, claim)
                        }
                    }
                }
            }
        }
        val gTotal = agentClaims.sumOf { it.settledAmount }
        val totalDelivered = agentClaims.sumOf { it.deliveredQuantity }
        // Keep G/Total + In-word together on one page, with breathing room
        // above, between, and below.
        if (y + 10f + 13f + 12f + 16f + 14f > pageHeight - margin) {
            ctx.nextPage(); canvas = ctx.canvas; y = margin
        }
        y += 10f
        canvas.drawText("G/Total = ${moneyFormat.format(gTotal)}   Total Delivered = $totalDelivered", margin, y + 9f, textPaint(darkColor, 8f, bold = true, sans = true))
        y += 12f
        if (y + 16f + 14f > pageHeight - margin) {
            ctx.nextPage(); canvas = ctx.canvas; y = margin
        }
        y += 16f
        canvas.drawText("In word: ${amountInWords(gTotal)}", margin, y, textPaint(darkColor, 8f, sans = true))
        y += 14f
        return canvas to y
    }

    /** One sample-style agent header line: two bold labels + normal values. */
    private fun drawVoucherAgentLine(
        canvas: Canvas, y: Float, label1: String, value1: String, label2: String, value2: String,
        labelPaint: Paint, valuePaint: Paint,
    ): Float {
        val rowH = 11f
        val half = contentWidth / 2f
        canvas.drawText(label1, margin, y + 8f, labelPaint)
        val w1 = labelPaint.measureText("$label1 ")
        fitTextSize(valuePaint, 7.5f, value1, half - w1 - 8f)
        canvas.drawText(value1, margin + w1 + 2f, y + 8f, valuePaint)
        canvas.drawText(label2, margin + half, y + 8f, labelPaint)
        val w2 = labelPaint.measureText("$label2 ")
        fitTextSize(valuePaint, 7.5f, value2, half - w2 - 8f)
        canvas.drawText(value2, margin + half + w2 + 2f, y + 8f, valuePaint)
        return y + rowH
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

    // Corporate report look (matches the reference Pathao PDF): serif family
    // on the summary pages — the default sans looked "robotic" next to it.
    // Voucher tables follow the sample voucher exactly: plain sans
    // (Arial-like), borderless rows, small bold headers (sans = true).
    private fun textPaint(colorInt: Int, size: Float, bold: Boolean = false, italic: Boolean = false, sans: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            textSize = size
            typeface = when {
                sans && bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                sans -> Typeface.DEFAULT
                bold -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
                italic -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                else -> Typeface.SERIF
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
            val headerPaint = textPaint(Color.WHITE, 7.5f, bold = true)
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
            if (i in alignRight) {
                fitTextSize(rightPaint, 7.5f, text, width - 6f)
                canvas.drawText(text, x + width - 3f, y + rowHeight - 4f, rightPaint)
            } else {
                fitTextSize(leftPaint, 7.5f, text, width - 6f)
                canvas.drawText(text, x + 3f, y + rowHeight - 4f, leftPaint)
            }
            x += width
        }
        canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, strokeBorder)
        return y + rowHeight
    }

    private fun drawSummaryGroupHeader(canvas: Canvas, y: Float, groupLabel: String, amountLabel: String, strokeBorder: Paint, fillColor: Int): Float {
        val rowHeight = 14f
        canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, fillPaint(fillColor))
        val headerPaint = textPaint(Color.WHITE, 7.5f, bold = true)
        val rightPaint = textPaint(Color.WHITE, 7.5f, bold = true).apply { textAlign = Paint.Align.RIGHT }
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
