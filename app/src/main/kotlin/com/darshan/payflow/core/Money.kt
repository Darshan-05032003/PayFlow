package com.darshan.payflow.core

/** Money is always `Long` paise. These helpers format and parse rupee text exactly, never via Double. */
object Money {
    private const val RUPEE = "₹"
    private val amountRegex = Regex("""^(\d{0,13})(?:\.(\d{1,2}))?$""")

    /** `12_345` → `₹123.45`, with Indian digit grouping (`₹12,34,567.89`). */
    fun formatInr(paise: Long): String {
        val negative = paise < 0
        val abs = if (negative) -paise else paise
        val rupees = abs / 100
        val cents = (abs % 100).toInt()
        val digits = rupees.toString()
        val grouped = buildString {
            val n = digits.length
            if (n <= 3) {
                append(digits)
            } else {
                val head = digits.substring(0, n - 3)
                val tail = digits.substring(n - 3)
                val headGroups = head.reversed().chunked(2).joinToString(",").reversed()
                append(headGroups).append(',').append(tail)
            }
        }
        val cents2 = cents.toString().padStart(2, '0')
        return (if (negative) "-" else "") + RUPEE + grouped + "." + cents2
    }

    /** Parses `"₹ 1,00,000.50"` → `100_000_50`. Returns null for anything that is not a plain positive rupee amount. */
    fun parseInrToPaise(text: String): Long? {
        val cleaned = text.trim().removePrefix(RUPEE).trim().replace(",", "")
        if (cleaned.isEmpty()) return null
        if (!cleaned.all { it in '0'..'9' || it == '.' }) return null
        val match = amountRegex.matchEntire(cleaned) ?: return null
        val rupeesText = match.groupValues[1]
        val centsText = match.groupValues[2]
        if (rupeesText.isEmpty() && centsText.isEmpty()) return null
        val rupees = if (rupeesText.isEmpty()) 0L else rupeesText.toLongOrNull() ?: return null
        val cents = when (centsText.length) {
            0 -> 0L
            1 -> centsText.toLong() * 10
            else -> centsText.toLong()
        }
        return rupees * 100 + cents
    }
}
