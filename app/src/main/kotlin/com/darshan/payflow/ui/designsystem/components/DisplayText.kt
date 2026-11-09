package com.darshan.payflow.ui.designsystem.components

/** Rules for showing text that came from outside the app, such as a payee name from a QR code or a link. */
object DisplayText {
    /**
     * Removes invisible direction and width controls. A name with a right-to-left override (U+202E) can render
     * reversed and read like a different payee on a confirm screen. Visible text in any language is untouched.
     */
    fun safe(text: String): String {
        if (text.none(::isControl)) return text
        return text.filterNot(::isControl)
    }

    private fun isControl(c: Char): Boolean = when (c.code) {
        in 0x202A..0x202E, in 0x2066..0x2069, in 0x200B..0x200F, 0x061C, 0xFEFF -> true
        else -> false
    }
}
