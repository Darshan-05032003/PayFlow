package com.darshan.payflow.data.local

import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.testutil.testPayment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaymentEntityMapperTest {
    @Test
    fun `payment round trips through the entity`() {
        val payment = testPayment(status = PaymentStatus.SUCCESS, idempotencyKey = "idem-1")
            .copy(pspTxnId = "psp", upiTxnId = "upi", refundId = "rf", failureReason = null)
        assertThat(payment.toEntity().toDomain()).isEqualTo(payment)
    }

    @Test
    fun `entity stores epoch millis and enum names`() {
        val entity = testPayment().toEntity()
        assertThat(entity.createdAt).isEqualTo(testPayment().createdAt.toEpochMilli())
        assertThat(entity.status).isEqualTo("CREATED")
        assertThat(entity.method).isEqualTo("UPI_INTENT")
        assertThat(entity.direction).isEqualTo("OUTGOING")
    }

    @Test
    fun `status source and handler package round trip and default for old rows`() {
        val payment = testPayment(status = PaymentStatus.SUCCESS).copy(statusSource = StatusSource.UPI_APP, handlerPackage = "com.example.gpay")
        val entity = payment.toEntity()
        assertThat(entity.statusSource).isEqualTo("UPI_APP")
        assertThat(entity.handlerPackage).isEqualTo("com.example.gpay")
        assertThat(entity.toDomain()).isEqualTo(payment)
        // A row written by an older schema (or garbage) reads as PSP-confirmed, never crashes the list.
        assertThat(entity.copy(statusSource = "???").toDomain().statusSource).isEqualTo(StatusSource.PSP)
    }
}
