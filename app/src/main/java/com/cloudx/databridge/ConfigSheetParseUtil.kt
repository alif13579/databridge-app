package com.cloudx.databridge

/**
 * Pure utility functions for sheet column parsing, timestamp parsing,
 * and phone normalisation.
 *
 * No Android, Firebase, or OkHttp dependencies — easy to unit-test.
 */
object ConfigSheetParseUtil {

    /**
     * Parses a column input that can be either a number ("3") or
     * a spreadsheet letter ("C", "AA"). Returns null for invalid input.
     */
    fun parseColInput(raw: String): Int? {
        val s = raw.trim().uppercase()
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }
        var result = 0
        for (ch in s) {
            if (ch !in 'A'..'Z') return null
            result = result * 26 + (ch - 'A' + 1)
        }
        return result
    }

    /**
     * Converts a 1-based column index to its spreadsheet letter(s).
     * e.g. 1 → "A", 26 → "Z", 27 → "AA"
     */
    fun colIndexToLetter(n: Int): String {
        var num = n; var result = ""
        while (num > 0) {
            val rem = (num - 1) % 26
            result = ('A' + rem) + result
            num = (num - 1) / 26
        }
        return result
    }

    /**
     * Parses a raw cell value from a Google Sheet into a Unix timestamp (milliseconds).
     * Handles:
     *  - 13-digit epoch millis
     *  - 10-digit epoch seconds
     *  - Google Sheets / Excel date-serial numbers (day 0 = 1899-12-30)
     *  - Common date strings: "yyyy-MM-dd", "dd-MMM-yyyy", etc.
     *
     * Returns null for blank or unparseable input.
     */
    fun parseSheetTimestamp(raw: String): Long? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim()

        // Numeric: epoch millis/seconds, or date-serial number
        trimmed.toDoubleOrNull()?.let { num ->
            val isPlainInteger = trimmed.matches(Regex("\\d+"))
            return when {
                isPlainInteger && trimmed.length == 13 -> num.toLong()              // epoch millis
                isPlainInteger && trimmed.length == 10 -> (num * 1000.0).toLong()   // epoch seconds
                num > 0 && num < 100000 -> {
                    // Google Sheets / Excel date-serial: day 0 = 1899-12-30 (UTC)
                    ((num - 25569.0) * 86400000.0).toLong()
                }
                else -> null
            }
        }

        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd",
            "dd-MMM-yyyy",
            "d-MMM-yyyy",
            "dd-MMM-yy",
            "d-MMM-yy",
        )
        for (pattern in patterns) {
            try {
                val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.ENGLISH)
                sdf.isLenient = false
                val date = sdf.parse(trimmed) ?: continue
                return date.time
            } catch (_: Exception) { /* try next pattern */ }
        }
        return null
    }

    /**
     * Normalises a Bangladeshi phone number to the international format
     * starting with "880" (13 digits total).
     */
    fun normalizePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return when {
            digits.isBlank()                                    -> ""
            digits.startsWith("880") && digits.length == 13    -> digits
            digits.startsWith("0")   && digits.length == 11    -> "88" + digits
            digits.length == 10                                 -> "880" + digits  // missing leading 0
            else                                                -> "88" + digits
        }
    }
}
