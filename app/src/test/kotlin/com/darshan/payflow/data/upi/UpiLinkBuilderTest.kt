package com.darshan.payflow.data.upi

import com.darshan.payflow.domain.model.UpiPayRequest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpiLinkBuilderTest {
    private val request = UpiPayRequest(
        payeeVpa = "alice@okbank",
        payeeName = "Alice Stores",
        amountPaise = 12_350,
        note = "Lunch & drinks",
        txnRef = "PF20261029ABC123",
    )

    @Test
    fun `builds a spec compliant upi pay link`() {
        val link = UpiLinkBuilder.build(request)
        assertThat(link).isEqualTo(
            "upi://pay?pa=alice%40okbank&pn=Alice%20Stores&am=123.50&cu=INR&tn=Lunch%20%26%20drinks&tr=PF20261029ABC123",
        )
    }

    @Test
    fun `amount is always two decimals of rupees`() {
        assertThat(UpiLinkBuilder.build(request.copy(amountPaise = 1))).contains("&am=0.01&")
        assertThat(UpiLinkBuilder.build(request.copy(amountPaise = 100))).contains("&am=1.00&")
        assertThat(UpiLinkBuilder.build(request.copy(amountPaise = 1_00_000_00))).contains("&am=100000.00&")
    }

    @Test
    fun `note is optional and unicode is percent encoded as utf8`() {
        val link = UpiLinkBuilder.build(request.copy(note = "", payeeName = "राम"))
        assertThat(link).doesNotContain("tn=")
        assertThat(link).contains("pn=%E0%A4%B0%E0%A4%BE%E0%A4%AE")
    }

    @Test
    fun `rejects invalid input instead of producing a broken link`() {
        assertThat(runCatching { UpiPayRequest("alice", "A", 100, "", "T1") }.isFailure).isTrue()
        assertThat(runCatching { UpiPayRequest("alice@okbank", "A", 0, "", "T1") }.isFailure).isTrue()
        assertThat(runCatching { UpiPayRequest("alice@okbank", "A", -5, "", "T1") }.isFailure).isTrue()
        assertThat(runCatching { UpiPayRequest("alice@okbank", "A", 100, "", "") }.isFailure).isTrue()
        assertThat(runCatching { UpiPayRequest("alice@okbank", "A", 100, "", "ref with spaces") }.isFailure).isTrue()
        assertThat(runCatching { UpiPayRequest("alice@okbank", "A", 100, "", "x".repeat(36)) }.isFailure).isTrue()
        assertThat(runCatching { UpiPayRequest("alice@okbank", "", 100, "", "T1") }.isFailure).isTrue()
    }

    @Test
    fun `payee name and note are trimmed and capped`() {
        val long = UpiPayRequest("alice@okbank", " " + "n".repeat(200) + " ", 100, "t".repeat(200), "T1")
        assertThat(long.payeeName).hasLength(UpiPayRequest.MAX_NAME_LENGTH)
        assertThat(long.note).hasLength(UpiPayRequest.MAX_NOTE_LENGTH)
    }

    @Test
    fun `txn ref generator produces unique url safe refs within 35 chars`() {
        val refs = (1..10_000).map { TxnRefGenerator.next() }.toSet()
        assertThat(refs).hasSize(10_000)
        refs.forEach { assertThat(it).matches("^[A-Za-z0-9]{10,35}$") }
    }
}
