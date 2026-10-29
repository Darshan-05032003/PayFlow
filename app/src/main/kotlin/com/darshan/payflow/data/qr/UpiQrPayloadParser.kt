package com.darshan.payflow.data.qr

import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.UpiPayRequest
import com.darshan.payflow.domain.model.VpaValidation
import com.darshan.payflow.domain.model.VpaValidator
import java.net.URLDecoder

enum class QrError { NOT_UPI, UNSUPPORTED, TOO_LONG, MISSING_VPA, INVALID_VPA, INVALID_AMOUNT, INVALID_CURRENCY }

/** A validated UPI QR. [raw] is kept for diagnostics but never shown or logged ([toString] omits it). */
class UpiQrPayload(
    val payeeVpa: String,
    val payeeName: String,
    val amountPaise: Long?,
    val note: String,
    val txnRef: String?,
    val merchantCode: String?,
    val raw: String,
) {
    override fun toString(): String = "UpiQrPayload(payeeVpa=$payeeVpa, payeeName=$payeeName, amountPaise=$amountPaise, txnRef=$txnRef, merchantCode=$merchantCode)"
    override fun equals(other: Any?): Boolean = other is UpiQrPayload && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()
}

sealed interface QrParseResult {
    data class Valid(val payload: UpiQrPayload) : QrParseResult
    data class Invalid(val error: QrError) : QrParseResult
}

/**
 * Parses `upi://pay?pa=&pn=&am=&cu=&tn=&tr=&mc=` QR contents defensively. Only `upi://pay` is supported (BharatQR TLV
 * and `upi://mandate` are rejected). Unknown parameters such as `url=` are ignored and never followed.
 */
object UpiQrPayloadParser {
    const val MAX_RAW_LENGTH = 4_096
    const val MAX_TEXT_LENGTH = 99
    private val amountRegex = Regex("""^\d{1,6}(\.\d{1,2})?$""")

    fun parse(raw: String): QrParseResult {
        if (raw.length > MAX_RAW_LENGTH) return QrParseResult.Invalid(QrError.TOO_LONG)
        val text = raw.trim()
        val schemeEnd = text.indexOf("://")
        if (schemeEnd <= 0 || !text.substring(0, schemeEnd).equals("upi", ignoreCase = true)) return QrParseResult.Invalid(QrError.NOT_UPI)
        val rest = text.substring(schemeEnd + 3)
        val queryStart = rest.indexOf('?')
        val host = (if (queryStart >= 0) rest.substring(0, queryStart) else rest).trimEnd('/')
        if (!host.equals("pay", ignoreCase = true)) return QrParseResult.Invalid(QrError.UNSUPPORTED)
        val params = parseQuery(if (queryStart >= 0) rest.substring(queryStart + 1) else "")

        val pa = params["pa"] ?: return QrParseResult.Invalid(QrError.MISSING_VPA)
        val vpa = when (val v = VpaValidator.validate(pa)) {
            is VpaValidation.Valid -> v.normalized
            is VpaValidation.Invalid -> return QrParseResult.Invalid(QrError.INVALID_VPA)
        }
        val currency = params["cu"]
        if (currency != null && !currency.equals("INR", ignoreCase = true)) return QrParseResult.Invalid(QrError.INVALID_CURRENCY)
        val amount = params["am"]?.let { am ->
            if (!amountRegex.matches(am)) return QrParseResult.Invalid(QrError.INVALID_AMOUNT)
            val paise = Money.parseInrToPaise(am) ?: return QrParseResult.Invalid(QrError.INVALID_AMOUNT)
            if (paise !in 1..Payment.MAX_AMOUNT_PAISE) return QrParseResult.Invalid(QrError.INVALID_AMOUNT)
            paise
        }
        val name = params["pn"]?.let(::printable)?.take(MAX_TEXT_LENGTH)?.takeIf { it.isNotBlank() } ?: vpa
        val note = params["tn"]?.let(::printable)?.take(MAX_TEXT_LENGTH) ?: ""
        val txnRef = params["tr"]?.takeIf { UpiPayRequest.TXN_REF_REGEX.matches(it) }
        val mc = params["mc"]?.takeIf { it.length in 1..8 && it.all(Char::isDigit) }
        return QrParseResult.Valid(UpiQrPayload(vpa, name, amount, note, txnRef, mc, raw))
    }

    /** First occurrence wins; `+` is treated as a space (common in generators); bad percent-escapes fall back to the raw text. */
    private fun parseQuery(query: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = pair.substring(0, eq).lowercase()
            if (map.containsKey(key)) continue
            map[key] = decode(pair.substring(eq + 1))
        }
        return map
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrElse { value.replace('+', ' ') }

    private fun printable(value: String): String =
        value.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.trim()
}
