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
 *   Page 2 — Petty Cash Expense Summary (21-row category/type breakdown)
 *   Page 3+ — Agent Acknowledgement (one page, one row per agent)
 *   Page 4+ — Conveyance Voucher, one page PER AGENT with conveyance claims
 *             (category is Pickup or Bulk Delivery — see the category column's
 *             comment on migration 202608260001)
 *
 * A4 at 72pt/inch — same size CashExportWriter.kt already uses for its exports.
 * Every "page type" above is forced onto its own page (startNewPage() at each
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

    // Legacy conveyance types (pre-catalog data, e.g. the reference PDF's
    // InterChange rows): any settled claim whose category isn't in the admin
    // catalog falls back here — conveyance iff its category or type is in
    // this set, otherwise the operation group, so page-1 totals always stay
    // balanced (operation + office + utilities == grand total).
    private val legacyConveyanceTypes = setOf("Pickup", "Bulk Delivery", "InterChange", "Inter Change")

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
     * Conveyance-group claims are counted via their `type` rows (never also
     * as a category row — type mirrors category, so counting both would
     * double up); every other group via its category rows.
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
        // pre-catalog rows, by a legacy conveyance type saved as `type`
        // (reference PDF's InterChange rows). Description always shows the
        // saved type verbatim, however it got here.
        fun isConveyanceClaim(row: SupabaseClaimsReader.ClaimRow): Boolean =
            groupOf(row.category) == "conveyance" || row.type in legacyConveyanceTypes
        fun categoryTotal(category: String) = settled.filter { it.category == category }.sumOf { it.settledAmount }
        fun typeTotal(type: String) = settled.filter { it.type.equals(type, ignoreCase = true) }.sumOf { it.settledAmount }
        val pdf = PdfDocument()
        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas

        fun startNewPage() {
            pdf.finishPage(page)
            pageNum += 1
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
            canvas = page.canvas
        }

        // ── Page 1: Top Sheet ──────────────────────────────────────────────
        // Conveyance claims count only via their type rows (never also as a
        // category row — type mirrors category, counting both would double
        // up); every other group via its category rows.
        val conveyanceTotal = settled.filter { isConveyanceClaim(it) }.sumOf { it.settledAmount }
        val operationTotal =
            settled.filter { !isConveyanceClaim(it) && groupOf(it.category) == "operation" }.sumOf { it.settledAmount } + conveyanceTotal
        val officeTotal = settled.filter { !isConveyanceClaim(it) && groupOf(it.category) == "office" }.sumOf { it.settledAmount }
        val utilitiesTotal = settled.filter { !isConveyanceClaim(it) && groupOf(it.category) == "utilities" }.sumOf { it.settledAmount }
        drawTopSheetPage(
            canvas, operationTotal, officeTotal, utilitiesTotal,
            branchName, branchRegion, pettyCashLimit,
            pocName, pocEmployeeId, pocDesignation, pocContact, fromDateIso, toDateIso,
        )

        // ── Page 2: Petty Cash Expense Summary ──────────────────────────────
        startNewPage()
        // Dynamic rows straight from the data: distinct categories per group
        // plus one row per saved conveyance type (verbatim — Pickup, Bulk
        // Delivery, InterChange, whatever the table holds).
        val opCategoryRows = settled.map { it.category }.distinct()
            .filter { groupOf(it) == "operation" }.sorted()
            .map { it to categoryTotal(it) }
        val convTypeRows = settled.filter { isConveyanceClaim(it) }.map { it.type }.distinct()
            .map { it to typeTotal(it) }
        val officeRows = settled.map { it.category }.distinct()
            .filter { groupOf(it) == "office" }.sorted()
            .map { it to categoryTotal(it) }
        val utilitiesRows = settled.map { it.category }.distinct()
            .filter { groupOf(it) == "utilities" }.sorted()
            .map { it to categoryTotal(it) }
        drawExpenseSummaryPage(
            canvas, opCategoryRows, convTypeRows, officeRows, utilitiesRows,
            branchName, branchRegion,
            pocName, pocEmployeeId, pocDesignation, pocContact, fromDateIso, toDateIso,
        )

        // ── Page 3: Agent Acknowledgement ────────────────────────────────────
        startNewPage()
        drawAgentAcknowledgementPage(canvas, settled, fromDateIso, pocName, pocEmployeeId, pocDesignation)

        // ── Page 4+: Conveyance Voucher, one page per agent with conveyance
        // claims. Agents ordered by their first appearance in the settled
        // list, matching the Agent Acknowledgement page's SL order — so
        // voucher pages read in the same agent order the preceding page
        // lists them in. ──
        val conveyanceClaims = settled.filter { isConveyanceClaim(it) }
        val agentOrder = LinkedHashSet<String>()
        settled.forEach { agentOrder.add(it.agentSystemId) }
        var voucherSl = 1
        agentOrder.forEach { agentSystemId ->
            val agentClaims = conveyanceClaims.filter { it.agentSystemId == agentSystemId }
                .sortedBy { it.placedDate }
            if (agentClaims.isEmpty()) return@forEach
            startNewPage()
            drawConveyanceVoucherPage(canvas, agentClaims, voucherSl)
            voucherSl += 1
        }

        pdf.finishPage(page)
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

        // Hub name row.
        y = drawTwoColLabelRow(canvas, y, "Hub Name:", branchName, labelPaint, valuePaint, strokeBorder)
        // Month/date row.
        val monthLabel = SimpleDateFormat("MMMM yyyy", Locale.US).format(dateIsoFormat.parse(fromDateIso) ?: java.util.Date())
        y = drawTwoColLabelRow(
            canvas, y, "Month Name: $monthLabel",
            "Date: ${dateDisplayFormat.format(dateIsoFormat.parse(toDateIso) ?: java.util.Date())}",
            labelPaint, valuePaint, strokeBorder, isTwoLabels = true,
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
        y = drawTwoColLabelRow(canvas, y, "Total Cost", moneyFormat.format(totalCost), labelPaint, valuePaint, strokeBorder, boldValue = true)
        y = drawTwoColLabelRow(canvas, y, "Petty cash limit", moneyFormat.format(pettyCashLimit), labelPaint, valuePaint, strokeBorder, boldValue = true)
        y = drawTwoColLabelRow(canvas, y, "Cash in hand", moneyFormat.format(cashInHand), labelPaint, valuePaint, strokeBorder, boldValue = true)
        y = drawTwoColLabelRow(canvas, y, "Over expenditure", moneyFormat.format(overExpenditure), labelPaint, valuePaint, strokeBorder, boldValue = true)
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

    private fun drawExpenseSummaryPage(
        canvas: Canvas,
        opCategoryRows: List<Pair<String, Double>>,
        convTypeRows: List<Pair<String, Double>>,
        officeRows: List<Pair<String, Double>>,
        utilitiesRows: List<Pair<String, Double>>,
        branchName: String, branchRegion: String,
        pocName: String, pocEmployeeId: String, pocDesignation: String, pocContact: String,
        fromDateIso: String, toDateIso: String,
    ) {
        var y = margin
        val titlePaint = textPaint(darkColor, 12f, bold = true)
        val labelPaint = textPaint(darkColor, 8.5f, bold = true)
        val valuePaint = textPaint(darkColor, 8.5f)
        val strokeBorder = strokePaint(borderColor, 0.6f)

        canvas.drawText("Petty Cash Expense Summery", margin, y + 10f, titlePaint)
        y += 16f
        val monthLabel = SimpleDateFormat("MMMM yyyy", Locale.US).format(dateIsoFormat.parse(fromDateIso) ?: java.util.Date())
        y = drawTwoColLabelRow(canvas, y, "Hub Name: $branchName", "", labelPaint, valuePaint, strokeBorder)
        y = drawTwoColLabelRow(
            canvas, y, "Month Name: $monthLabel",
            "Date: ${dateDisplayFormat.format(dateIsoFormat.parse(toDateIso) ?: java.util.Date())}",
            labelPaint, valuePaint, strokeBorder, isTwoLabels = true,
        )
        y = drawTwoColLabelRow(canvas, y, "Responsible Name: $pocName", "", labelPaint, valuePaint, strokeBorder)
        y = drawTwoColLabelRow(canvas, y, "EID: $pocEmployeeId", "Designation: $pocDesignation", labelPaint, valuePaint, strokeBorder, isTwoLabels = true)
        y = drawTwoColLabelRow(canvas, y, "Contact Number: $pocContact", "Region Name: $branchRegion", labelPaint, valuePaint, strokeBorder, isTwoLabels = true)
        y += 10f

        val rowHeightSmall = 12f

        // A. Operation Expense — operation-group category rows first, then one
        // row per saved conveyance type (verbatim from the data).
        y = drawSummaryGroupHeader(canvas, y, "A. Operation Expense", "Amount", strokeBorder, headerFillColor)
        val operationRows = opCategoryRows + convTypeRows
        operationRows.forEachIndexed { i, (label, amount) ->
            y = drawSummaryLineRow(canvas, y, i + 1, label, amount, rowHeightSmall, strokeBorder)
        }
        val operationSectionTotal = operationRows.sumOf { it.second }
        y = drawSummaryTotalRow(canvas, y, "Total Operation Expense", operationSectionTotal, strokeBorder)

        // B. Office Maintenance Cost.
        y = drawSummaryGroupHeader(canvas, y, "B. Office Maintaince Cost", "Amount", strokeBorder, headerFillColor)
        officeRows.forEachIndexed { i, (label, amount) ->
            y = drawSummaryLineRow(canvas, y, i + 1, label, amount, rowHeightSmall, strokeBorder)
        }
        val officeTotal = officeRows.sumOf { it.second }
        y = drawSummaryTotalRow(canvas, y, "Total Office Maintaince Cost", officeTotal, strokeBorder)

        // C. Utilities Expense.
        y = drawSummaryGroupHeader(canvas, y, "C. Utilities Expense", "Amount", strokeBorder, headerFillColor)
        utilitiesRows.forEachIndexed { i, (label, amount) ->
            y = drawSummaryLineRow(canvas, y, i + 1, label, amount, rowHeightSmall, strokeBorder)
        }
        val utilitiesTotal = utilitiesRows.sumOf { it.second }
        y = drawSummaryTotalRow(canvas, y, "Total Utilities Expense", utilitiesTotal, strokeBorder)

        y += 8f
        val grandTotal = operationSectionTotal + officeTotal + utilitiesTotal
        y = drawSummaryTotalRow(canvas, y, "Total Cost (A+B+C)", grandTotal, strokeBorder, emphasize = true)
        y += 20f

        // Sign-off row.
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
        canvas: Canvas,
        settled: List<SupabaseClaimsReader.ClaimRow>,
        fromDateIso: String,
        pocName: String,
        pocEmployeeId: String,
        pocDesignation: String,
    ) {
        var y = margin
        val titlePaint = textPaint(darkColor, 12f, bold = true).apply { textAlign = Paint.Align.CENTER }
        val labelPaint = textPaint(darkColor, 8.5f, bold = true)
        val valuePaint = textPaint(darkColor, 8.5f)
        val strokeBorder = strokePaint(borderColor, 0.6f)
        val monthYearLabel = SimpleDateFormat("MMMM-yy", Locale.US).format(dateIsoFormat.parse(fromDateIso) ?: java.util.Date())

        canvas.drawText("Pathao Limited", margin + contentWidth / 2, y + 12f, titlePaint)
        y += 16f
        canvas.drawText("Agent Acknowledgement ($monthYearLabel)", margin + contentWidth / 2, y + 12f, titlePaint)
        y += 18f

        // Group settled claims per agent — Amount and Total Succeeded summed
        // across every settled claim (not only conveyance ones), per the
        // reference PDF's "Amount"/quantity columns.
        data class AgentSummary(val systemId: String, val name: String, val phone: String, val amount: Double, val delivered: Int)
        val bySystemId = settled.groupBy { it.agentSystemId }
        val summaries = bySystemId.map { (systemId, rows) ->
            AgentSummary(
                systemId = systemId,
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
        summaries.forEachIndexed { i, s ->
            y = drawDataRow(
                canvas, y,
                listOf((i + 1).toString(), s.name, s.systemId, moneyFormat.format(s.amount), s.delivered.toString(), s.phone, ""),
                headers.map { it.second }, strokeBorder, alignRight = setOf(3, 4),
            )
        }
        val totalAmount = summaries.sumOf { it.amount }
        val totalDelivered = summaries.sumOf { it.delivered }
        y = drawDataRow(
            canvas, y,
            listOf("", "", "Total =", moneyFormat.format(totalAmount), totalDelivered.toString(), "", ""),
            headers.map { it.second }, strokeBorder, alignRight = setOf(3, 4), bold = true,
        )
        y += 14f
        canvas.drawText(amountInWords(totalAmount), margin, y, textPaint(darkColor, 8.5f, bold = true))
    }

    // ── Page 4+: Conveyance Voucher (one page per agent) ────────────────────

    private fun drawConveyanceVoucherPage(
        canvas: Canvas,
        agentClaims: List<SupabaseClaimsReader.ClaimRow>,
        sl: Int,
    ) {
        var y = margin
        val titlePaint = textPaint(darkColor, 12f, bold = true)
        val labelPaint = textPaint(darkColor, 8.5f, bold = true)
        val valuePaint = textPaint(darkColor, 8.5f)
        val strokeBorder = strokePaint(borderColor, 0.6f)
        val first = agentClaims.first()

        canvas.drawText("Conveyance Voucher", margin, y + 10f, titlePaint)
        y += 16f
        canvas.drawText("SL : $sl", margin, y + 9f, valuePaint)
        y += 14f
        y = drawTwoColLabelRow(canvas, y, "Agent ID ${first.agentSystemId}", "Agent Name ${first.agentName}", labelPaint, valuePaint, strokeBorder, isTwoLabels = true)
        y = drawTwoColLabelRow(canvas, y, "Designation: ${first.agentDesignation.ifBlank { "Delivery Agent" }}", "Department: Fulfillment", labelPaint, valuePaint, strokeBorder, isTwoLabels = true)
        y += 8f

        val headers = listOf("Date" to 0.11f, "From" to 0.13f, "Destination" to 0.13f, "Description" to 0.14f, "Vehicle" to 0.09f, "Amount" to 0.09f, "Attempted" to 0.10f, "Succeeded" to 0.09f, "CID / Merchant" to 0.12f)
        y = drawTableHeaderRow(canvas, y, headers, strokeBorder, headerFillColor)
        agentClaims.forEach { claim ->
            val dateLabel = runCatching { dateDisplayFormat.format(dateIsoFormat.parse(claim.placedDate) ?: java.util.Date()) }.getOrDefault(claim.placedDate)
            y = drawDataRow(
                canvas, y,
                listOf(
                    dateLabel, claim.fromArea, claim.toArea, claim.type, claim.vehicle,
                    moneyFormat.format(claim.settledAmount), claim.attemptQuantity.toString(),
                    claim.deliveredQuantity.toString(), claim.cidOrMerchant,
                ),
                headers.map { it.second }, strokeBorder, alignRight = setOf(5, 6, 7),
            )
        }
        val gTotal = agentClaims.sumOf { it.settledAmount }
        val totalDelivered = agentClaims.sumOf { it.deliveredQuantity }
        y += 4f
        canvas.drawText("G/Total = ${moneyFormat.format(gTotal)}   Total Succeeded = $totalDelivered", margin, y + 9f, textPaint(darkColor, 8.5f, bold = true))
        y += 16f
        canvas.drawText("In word: ${amountInWords(gTotal)}", margin, y, textPaint(darkColor, 8.5f))
    }

    // ── Shared drawing helpers ───────────────────────────────────────────────

    private fun textPaint(colorInt: Int, size: Float, bold: Boolean = false, italic: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            textSize = size
            typeface = when {
                bold -> Typeface.DEFAULT_BOLD
                italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                else -> Typeface.DEFAULT
            }
        }

    private fun fillPaint(colorInt: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt; style = Paint.Style.FILL }

    private fun strokePaint(colorInt: Int, width: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt; style = Paint.Style.STROKE; strokeWidth = width }

    private fun truncateToWidth(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end -= 1
        return if (end <= 0) "" else text.substring(0, end) + "…"
    }

    /** A single row split into two label:value pairs (or one, if [value2]/[label2] blank). */
    private fun drawTwoColLabelRow(
        canvas: Canvas, y: Float, label: String, value: String,
        labelPaint: Paint, valuePaint: Paint, strokeBorder: Paint,
        isTwoLabels: Boolean = false, boldValue: Boolean = false,
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
            canvas.drawText(value, margin + labelWidth + 4f, y + rowHeight - 5f, if (boldValue) textPaint(darkColor, 8.5f, bold = true) else valuePaint)
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
        val headerPaint = textPaint(Color.WHITE, 7.5f, bold = true)
        cols.forEach { (label, weight) ->
            val width = weight * contentWidth
            canvas.drawText(truncateToWidth(label, headerPaint, width - 6f), x + 3f, y + rowHeight - 5f, headerPaint)
            x += width
        }
        canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, strokeBorder)
        return y + rowHeight
    }

    private fun drawDataRow(
        canvas: Canvas, y: Float, values: List<String>, weights: List<Float>,
        strokeBorder: Paint, alignRight: Set<Int> = emptySet(), bold: Boolean = false,
    ): Float {
        val rowHeight = 14f
        var x = margin
        val leftPaint = textPaint(darkColor, 7.5f, bold = bold)
        val rightPaint = textPaint(darkColor, 7.5f, bold = bold).apply { textAlign = Paint.Align.RIGHT }
        values.forEachIndexed { i, value ->
            val width = weights.getOrElse(i) { 0.1f } * contentWidth
            if (i in alignRight) {
                val shown = truncateToWidth(value, rightPaint, width - 6f)
                canvas.drawText(shown, x + width - 3f, y + rowHeight - 4f, rightPaint)
            } else {
                val shown = truncateToWidth(value.ifBlank { "-" }, leftPaint, width - 6f)
                canvas.drawText(shown, x + 3f, y + rowHeight - 4f, leftPaint)
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
        canvas.drawText(truncateToWidth(label, leftPaint, contentWidth * 0.7f), margin + slWidth, y + rowHeight - 3f, leftPaint)
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
