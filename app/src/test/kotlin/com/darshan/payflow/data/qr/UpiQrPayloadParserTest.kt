package com.darshan.payflow.data.qr

import com.darshan.payflow.domain.model.Payment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpiQrPayloadParserTest {
    private fun valid(raw: String) = (UpiQrPayloadParser.parse(raw) as QrParseResult.Valid).payload
    private fun error(raw: String) = (UpiQrPayloadParser.parse(raw) as QrParseResult.Invalid).error

    @Test
    fun `parses a merchant qr with amount`() {
        val p = valid("upi://pay?pa=shop@okbank&pn=Chai%20Point&am=45.00&cu=INR&tn=Table%204&tr=ORD123&mc=5812")
        assertThat(p.payeeVpa).isEqualTo("shop@okbank")
        assertThat(p.payeeName).isEqualTo("Chai Point")
        assertThat(p.amountPaise).isEqualTo(4_500)
        assertThat(p.note).isEqualTo("Table 4")
        assertThat(p.txnRef).isEqualTo("ORD123")
        assertThat(p.merchantCode).isEqualTo("5812")
    }

    @Test
    fun `static qr without amount leaves amount null and defaults the name to the vpa`() {
        val p = valid("upi://pay?pa=alice@okbank")
        assertThat(p.amountPaise).isNull()
        assertThat(p.payeeName).isEqualTo("alice@okbank")
        assertThat(p.note).isEmpty()
        assertThat(p.txnRef).isNull()
    }

    @Test
    fun `scheme and host are case insensitive and whitespace is tolerated`() {
        assertThat(valid("  UPI://PAY?pa=Alice@OkBank&am=1  ").payeeVpa).isEqualTo("alice@okbank")
        assertThat(valid("upi://pay?pa=alice@okbank&am=1").amountPaise).isEqualTo(100)
        assertThat(valid("upi://pay?pa=alice@okbank&am=0.5").amountPaise).isEqualTo(50)
    }

    @Test
    fun `plus signs are not spaces in rfc3986 but many generators use them`() {
        assertThat(valid("upi://pay?pa=ab@ab&pn=Chai+Point").payeeName).isEqualTo("Chai Point")
    }

    @Test
    fun `rejects non upi content`() {
        assertThat(error("https://example.com/pay?pa=alice@okbank")).isEqualTo(QrError.NOT_UPI)
        assertThat(error("")).isEqualTo(QrError.NOT_UPI)
        assertThat(error("hello world")).isEqualTo(QrError.NOT_UPI)
        assertThat(error("javascript:alert(1)")).isEqualTo(QrError.NOT_UPI)
        assertThat(error("upi://mandate?pa=alice@okbank")).isEqualTo(QrError.UNSUPPORTED)
        assertThat(error("000201010211...5802IN")).isEqualTo(QrError.NOT_UPI) // BharatQR / EMVCo TLV
    }

    @Test
    fun `rejects bad or missing vpa`() {
        assertThat(error("upi://pay?pn=Alice&am=1")).isEqualTo(QrError.MISSING_VPA)
        assertThat(error("upi://pay?pa=alice&am=1")).isEqualTo(QrError.INVALID_VPA)
        assertThat(error("upi://pay?pa=javascript:alert(1)@x")).isEqualTo(QrError.INVALID_VPA)
        assertThat(error("upi://pay?pa=%D0%B0lice@okbank")).isEqualTo(QrError.INVALID_VPA) // Cyrillic а
        assertThat(error("upi://pay?pa=‮alice@okbank")).isEqualTo(QrError.INVALID_VPA)
        assertThat(error("upi://pay?pa=" + "a".repeat(300) + "@okbank")).isEqualTo(QrError.INVALID_VPA)
    }

    @Test
    fun `rejects negative zero huge and malformed amounts`() {
        for (am in listOf("-1", "0", "0.00", "1e5", "100001", "100000.01", "1.234", "abc", "١٠", "9999999999999999999", "NaN", "Infinity", "0x10", " 1")) {
            assertThat(error("upi://pay?pa=alice@okbank&am=$am")).isEqualTo(QrError.INVALID_AMOUNT)
        }
        assertThat(valid("upi://pay?pa=alice@okbank&am=100000").amountPaise).isEqualTo(Payment.MAX_AMOUNT_PAISE)
    }

    @Test
    fun `rejects non inr currency but accepts missing or inr`() {
        assertThat(error("upi://pay?pa=alice@okbank&cu=USD")).isEqualTo(QrError.INVALID_CURRENCY)
        assertThat(valid("upi://pay?pa=alice@okbank&cu=inr").payeeVpa).isEqualTo("alice@okbank")
    }

    @Test
    fun `ignores unknown and dangerous extra params and never follows urls`() {
        val p = valid("upi://pay?pa=alice@okbank&url=http://evil.example/steal&mode=02&purpose=00&orgid=159002&sign=abc&redirect=javascript:x")
        assertThat(p.payeeVpa).isEqualTo("alice@okbank")
        assertThat(p.raw).contains("url=")
        assertThat(p.toString()).doesNotContain("evil.example") // toString must not echo the raw payload
    }

    @Test
    fun `first value wins for duplicate params`() {
        val p = valid("upi://pay?pa=alice@okbank&pa=mallory@evil&am=1&am=99999")
        assertThat(p.payeeVpa).isEqualTo("alice@okbank")
        assertThat(p.amountPaise).isEqualTo(100)
    }

    @Test
    fun `unicode rtl and control characters in free text are kept printable and capped`() {
        val p = valid("upi://pay?pa=alice@okbank&pn=%E0%A4%B0%E0%A4%BE%E0%A4%AE%20%E2%80%AEevil%00&tn=" + "x".repeat(500))
        assertThat(p.payeeName).isEqualTo("राम evil")
        assertThat(p.note).hasLength(UpiQrPayloadParser.MAX_TEXT_LENGTH)
    }

    @Test
    fun `invalid txnRef is dropped rather than failing the whole qr`() {
        assertThat(valid("upi://pay?pa=alice@okbank&tr=has%20space").txnRef).isNull()
        assertThat(valid("upi://pay?pa=alice@okbank&tr=" + "A".repeat(40)).txnRef).isNull()
        assertThat(valid("upi://pay?pa=alice@okbank&tr=OK123").txnRef).isEqualTo("OK123")
    }

    @Test
    fun `huge payloads are rejected quickly and bad percent encoding does not throw`() {
        assertThat(error("upi://pay?pa=alice@okbank&tn=" + "A".repeat(100 * 1024))).isEqualTo(QrError.TOO_LONG)
        assertThat(valid("upi://pay?pa=alice@okbank&pn=%E0%A4%ZZ&tn=%").payeeVpa).isEqualTo("alice@okbank")
        assertThat(error("upi://pay?pa=%ZZ@okbank")).isEqualTo(QrError.INVALID_VPA)
    }

    @Test
    fun `round trips a link produced by the link builder`() {
        val request = com.darshan.payflow.domain.model.UpiPayRequest("alice@okbank", "Alice & Co", 12_345, "Lunch", "PF1")
        val p = valid(com.darshan.payflow.data.upi.UpiLinkBuilder.build(request))
        assertThat(p.payeeVpa).isEqualTo("alice@okbank")
        assertThat(p.payeeName).isEqualTo("Alice & Co")
        assertThat(p.amountPaise).isEqualTo(12_345)
        assertThat(p.note).isEqualTo("Lunch")
        assertThat(p.txnRef).isEqualTo("PF1")
    }
}
