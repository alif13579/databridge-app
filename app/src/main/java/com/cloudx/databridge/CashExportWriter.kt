package com.cloudx.databridge

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Excel (.xlsx) and PDF writers shared by CashLedgerListFragment (per-mode export)
 * and CashManagementHomeFragment (combined export) -- CSV already existed; this
 * adds the other two formats users asked for.
 *
 * No new Gradle dependency for either format:
 *  - xlsx is written directly as a ZIP of minimal OOXML/SpreadsheetML parts via
 *    java.util.zip (already on the platform). Apache POI was the obvious library
 *    choice but pulls in a lot of weight and has known Android packaging quirks
 *    (xmlbeans/stax clashes, multidex); since this sandbox can't run a Gradle
 *    build to catch that kind of problem, hand-rolling a minimal writer removes
 *    the risk entirely. The exact structure below was prototyped in Python first
 *    and opened cleanly in both openpyxl and LibreOffice before being ported here.
 *  - PDF uses android.graphics.pdf.PdfDocument, built into the platform since
 *    API 19 (minSdk here is 23), so no dependency needed there either.
 *
 * A table row is List<Any>: a Number renders right-aligned with #,##0-style
 * formatting (use this for the Amount column); anything else is left-aligned text.
 */
object CashExportWriter {

    // ── XLSX ─────────────────────────────────────────────────────────────────

    /**
     * @param colWidths Excel column-width units (roughly characters), same length as [headers].
     */
    fun writeXlsx(
        outFile: File,
        sheetName: String,
        headers: List<String>,
        rows: List<List<Any>>,
        colWidths: List<Int>,
    ) {
        outFile.parentFile?.mkdirs()
        ZipOutputStream(outFile.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", XLSX_CONTENT_TYPES)
            entry("_rels/.rels", XLSX_RELS)
            entry("xl/workbook.xml", xlsxWorkbookXml(sheetName.take(31)))
            entry("xl/_rels/workbook.xml.rels", XLSX_WORKBOOK_RELS)
            entry("xl/styles.xml", XLSX_STYLES)
            entry("xl/worksheets/sheet1.xml", xlsxSheetXml(headers, rows, colWidths))
        }
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    /** 0-based column index -> Excel column letters (A, B, ... Z, AA, AB, ...). */
    private fun colLetters(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + (i % 26)))
            i = i / 26 - 1
            if (i < 0) break
        }
        return sb.toString()
    }

    private fun xlsxNumberString(n: Number): String {
        val d = n.toDouble()
        return if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
    }

    private fun xlsxSheetXml(headers: List<String>, rows: List<List<Any>>, colWidths: List<Int>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        if (colWidths.isNotEmpty()) {
            sb.append("<cols>")
            colWidths.forEachIndexed { i, w ->
                sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>")
            }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        sb.append("<row r=\"1\">")
        headers.forEachIndexed { i, h ->
            sb.append("<c r=\"${colLetters(i)}1\" t=\"inlineStr\" s=\"1\"><is><t>${xmlEscape(h)}</t></is></c>")
        }
        sb.append("</row>")
        rows.forEachIndexed { zeroBasedIdx, row ->
            val r = zeroBasedIdx + 2
            sb.append("<row r=\"$r\">")
            row.forEachIndexed { c, value ->
                val ref = "${colLetters(c)}$r"
                if (value is Number) {
                    sb.append("<c r=\"$ref\" s=\"2\"><v>${xlsxNumberString(value)}</v></c>")
                } else {
                    val text = value.toString()
                    if (text.isNotEmpty()) {
                        sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlEscape(text)}</t></is></c>")
                    }
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun xlsxWorkbookXml(sheetName: String): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets>
<sheet name="${xmlEscape(sheetName)}" sheetId="1" r:id="rId1"/>
</sheets>
</workbook>"""

    private const val XLSX_CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private const val XLSX_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private const val XLSX_WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    // Style 0 = default body text, 1 = bold white-on-teal header, 2 = #,##0 number.
    private const val XLSX_STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0"/></numFmts>
<fonts count="2">
<font><sz val="10"/><name val="Arial"/></font>
<font><b/><sz val="10"/><color rgb="FFFFFFFF"/><name val="Arial"/></font>
</fonts>
<fills count="3">
<fill><patternFill patternType="none"/></fill>
<fill><patternFill patternType="gray125"/></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FF0099B8"/><bgColor indexed="64"/></patternFill></fill>
</fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="3">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""

    // ── PDF ──────────────────────────────────────────────────────────────────

    data class PdfSummaryCard(val label: String, val value: String)

    /**
     * @param colWeights relative widths, same length as [headers] (don't need to sum to anything specific).
     */
    fun writePdf(
        outFile: File,
        title: String,
        subtitle: String,
        summaryCards: List<PdfSummaryCard>,
        headers: List<String>,
        rows: List<List<Any>>,
        colWeights: List<Float>,
    ) {
        outFile.parentFile?.mkdirs()

        val pageWidth = 595
        val pageHeight = 842
        val margin = 32f
        val contentWidth = pageWidth - margin * 2

        val darkColor = Color.parseColor("#0F172A")
        val mutedColor = Color.parseColor("#64748B")
        val tealColor = Color.parseColor("#0099B8")
        val lightFillColor = Color.parseColor("#F8FAFC")
        val borderColor = Color.parseColor("#CBD5E1")

        val titlePaint = textPaint(darkColor, 16f, bold = true)
        val metaPaint = textPaint(mutedColor, 9f, italic = true)
        val cardLabelPaint = textPaint(Color.WHITE, 8.5f, bold = true).apply { textAlign = Paint.Align.CENTER }
        val cardValuePaint = textPaint(darkColor, 13f, bold = true).apply { textAlign = Paint.Align.CENTER }
        val sectionPaint = textPaint(darkColor, 11f, bold = true)
        val headerTextPaint = textPaint(Color.WHITE, 7.5f, bold = true)
        val cellTextPaintLeft = textPaint(darkColor, 7.5f)
        val cellTextPaintRight = textPaint(darkColor, 7.5f).apply { textAlign = Paint.Align.RIGHT }
        val notePaint = textPaint(mutedColor, 7.5f, italic = true)

        val fillTeal = fillPaint(tealColor)
        val fillLight = fillPaint(lightFillColor)
        val fillWhite = fillPaint(Color.WHITE)
        val strokeBorder = strokePaint(borderColor, 0.6f)

        val pdf = PdfDocument()
        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas
        var y = margin

        fun startNewPage() {
            pdf.finishPage(page)
            pageNum += 1
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
            canvas = page.canvas
            y = margin
        }

        canvas.drawText(title, margin, y + 14f, titlePaint)
        y += 20f
        canvas.drawText(subtitle, margin, y, metaPaint)
        y += 20f

        if (summaryCards.isNotEmpty()) {
            val gap = 6f
            val cardWidth = (contentWidth - gap * (summaryCards.size - 1)) / summaryCards.size
            val labelHeight = 20f
            val valueHeight = 28f
            val top = y
            summaryCards.forEachIndexed { i, card ->
                val left = margin + i * (cardWidth + gap)
                canvas.drawRect(left, top, left + cardWidth, top + labelHeight, fillTeal)
                canvas.drawRect(left, top + labelHeight, left + cardWidth, top + labelHeight + valueHeight, fillLight)
                canvas.drawRect(left, top, left + cardWidth, top + labelHeight + valueHeight, strokeBorder)
                canvas.drawText(card.label, left + cardWidth / 2, top + 13f, cardLabelPaint)
                canvas.drawText(card.value, left + cardWidth / 2, top + labelHeight + 19f, cardValuePaint)
            }
            y = top + labelHeight + valueHeight + 16f
        }

        canvas.drawText("Transactions", margin, y + 9f, sectionPaint)
        y += 16f

        val weightSum = colWeights.sum().takeIf { it > 0f } ?: 1f
        val colWidths = colWeights.map { it / weightSum * contentWidth }
        fun colX(index: Int): Float {
            var x = margin
            for (i in 0 until index) x += colWidths[i]
            return x
        }

        val headerHeight = 20f
        val rowHeight = 16f
        val bottomLimit = pageHeight - margin

        fun drawHeaderRow() {
            canvas.drawRect(margin, y, margin + contentWidth, y + headerHeight, fillTeal)
            headers.forEachIndexed { i, h ->
                canvas.drawText(h, colX(i) + 4f, y + headerHeight - 6f, headerTextPaint)
            }
            y += headerHeight
        }

        drawHeaderRow()

        rows.forEachIndexed { rIdx, row ->
            if (y + rowHeight > bottomLimit) {
                startNewPage()
                drawHeaderRow()
            }
            canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, if (rIdx % 2 == 1) fillLight else fillWhite)
            row.forEachIndexed { c, value ->
                val width = colWidths[c]
                if (value is Number) {
                    val shown = truncateToWidth(formatNumberForDisplay(value), cellTextPaintRight, width - 8f)
                    canvas.drawText(shown, colX(c) + width - 4f, y + rowHeight - 5f, cellTextPaintRight)
                } else {
                    val text = value.toString().ifBlank { "-" }
                    val shown = truncateToWidth(text, cellTextPaintLeft, width - 8f)
                    canvas.drawText(shown, colX(c) + 4f, y + rowHeight - 5f, cellTextPaintLeft)
                }
            }
            canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, strokeBorder)
            y += rowHeight
        }
        canvas.drawLine(margin, y, margin + contentWidth, y, strokeBorder)
        y += 14f

        if (y < bottomLimit - 10f) {
            canvas.drawText("Generated by DataBridge", margin, y, notePaint)
        }

        pdf.finishPage(page)
        outFile.outputStream().use { pdf.writeTo(it) }
        pdf.close()
    }

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

    private fun formatNumberForDisplay(n: Number): String {
        val d = n.toDouble()
        val nf = NumberFormat.getNumberInstance(Locale.US)
        return if (d == Math.floor(d) && !d.isInfinite()) nf.format(d.toLong()) else nf.format(d)
    }

    private fun truncateToWidth(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "\u2026") > maxWidth) end--
        return if (end <= 0) "\u2026" else text.substring(0, end) + "\u2026"
    }
}
