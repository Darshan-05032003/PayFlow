package com.darshan.payflow.data.upi

import com.darshan.payflow.domain.model.UpiPayRequest
import java.security.SecureRandom

/** Builds `upi://pay?pa=&pn=&am=&cu=INR&tn=&tr=` deep links with RFC 3986 percent-encoding (UTF-8). */
object UpiLinkBuilder {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

    fun build(request: UpiPayRequest): String = buildString {
        append("upi://pay?pa=").append(encode(request.payeeVpa))
        append("&pn=").append(encode(request.payeeName))
        append("&am=").append(formatAmount(request.amountPaise))
        append("&cu=INR")
        if (request.note.isNotEmpty()) append("&tn=").append(encode(request.note))
        append("&tr=").append(encode(request.txnRef))
    }

    /** `12_350` → `"123.50"`; UPI apps expect plain rupees with two decimals, no grouping, no symbol. */
    fun formatAmount(paise: Long): String = "${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"

    fun encode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            if (c < 0x80 && UNRESERVED.indexOf(c.toChar()) >= 0) append(c.toChar()) else append('%').append("%02X".format(c))
        }
    }
}

/** Unique, URL-safe, ≤ 35 char merchant references: `PF` + 12 base-36 time chars + 10 random chars. */
object TxnRefGenerator {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    fun next(nowMillis: Long = System.currentTimeMillis()): String {
        val time = nowMillis.toString(36).uppercase().padStart(9, '0')
        val rand = buildString { repeat(12) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
        return "PF$time$rand"
    }
}
