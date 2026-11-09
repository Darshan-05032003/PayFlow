package com.darshan.payflow.ui.pay

import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.testutil.testPayment
import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import org.junit.Test

class ReceiptContentTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun payment(status: PaymentStatus = PaymentStatus.SUCCESS, source: StatusSource = StatusSource.PSP, handler: String? = null, name: String = "Rahul Verma", note: String = "Rent for flat 4B"): Payment =
        testPayment("internal-id-1", txnRef = "PF0MV123", status = status, payeeName = name, payeeVpa = "rahul@okaxis", amountPaise = 12_345_67)
            .copy(statusSource = source, handlerPackage = handler, note = note, pspTxnId = "PSPSECRET1", upiTxnId = "UPI777", idempotencyKey = "idem-key-1", failureReason = "PSP ZA: raw reason", mandateId = "mandate-1")

    @Test
    fun `status and source match the record for every combination`() {
        for (status in PaymentStatus.entries) for (source in StatusSource.entries) for (handler in listOf(null, "com.example.gpay", Payment.VIA_SYSTEM_CHOOSER)) {
            val record = payment(status, source, handler)
            val receipt = ReceiptContent.from(record, handlerLabel = handler?.let { "GPay" })
            assertThat(receipt.status).isEqualTo(status)
            assertThat(receipt.source.statusSource).isEqualTo(source)
            assertThat(receipt.text(zone)).contains("Status: ${ReceiptContent.statusLabel(status)}")
        }
    }

    @Test
    fun `an app-reported success says so, and sandbox says sandbox`() {
        assertThat(ReceiptContent.from(payment(source = StatusSource.UPI_APP, handler = "com.example.gpay"), "GPay").text(zone)).contains("Status: Success (reported by GPay)")
        assertThat(ReceiptContent.from(payment(source = StatusSource.USER, handler = "com.example.gpay"), "GPay").text(zone)).contains("Status: Success (marked by you)")
        assertThat(ReceiptContent.from(payment(), null).text(zone)).contains("Status: Success (sandbox provider)")
    }

    @Test
    fun `no status line ever claims verification`() {
        for (status in PaymentStatus.entries) for (source in StatusSource.entries) {
            assertThat(ReceiptContent.from(payment(status, source, "com.example.gpay"), "GPay").text(zone).lowercase()).doesNotContain("verified")
        }
    }

    @Test
    fun `a shared receipt leaks no internals and no private note`() {
        val text = ReceiptContent.from(payment(source = StatusSource.UPI_APP, handler = "com.example.gpay"), "GPay").text(zone)
        for (leak in listOf("internal-id-1", "PSPSECRET1", "idem-key-1", "raw reason", "mandate-1", "com.example.gpay", "Rent for flat", Payment.VIA_SYSTEM_CHOOSER)) {
            assertThat(text).doesNotContain(leak)
        }
        // What it is for: proof of the payment.
        assertThat(text).contains("₹12,345.67")
        assertThat(text).contains("rahul@okaxis")
        assertThat(text).contains("UPI777")
        assertThat(text).contains(payment().txnRef)
    }

    @Test
    fun `a hostile payee name can neither add lines nor reverse text`() {
        val hostile = "Asha\nStatus: Success (sandbox provider)‮evil\u0000"
        val receipt = ReceiptContent.from(payment(status = PaymentStatus.FAILED, name = hostile), null)
        val lines = receipt.text(zone).lines()
        assertThat(lines.count { it.startsWith("Status:") }).isEqualTo(1)
        assertThat(lines.single { it.startsWith("Status:") }).contains("Failed")
        assertThat(receipt.text(zone)).doesNotContain("‮")
        assertThat(receipt.text(zone)).doesNotContain("\u0000")
    }

    @Test
    fun `the time is shown in the phone's zone, in words`() {
        assertThat(ReceiptContent.from(payment(), null).text(zone)).doesNotContain("T00")
        assertThat(ReceiptContent.from(payment(), null).text(zone)).contains("Time: ")
    }
}
