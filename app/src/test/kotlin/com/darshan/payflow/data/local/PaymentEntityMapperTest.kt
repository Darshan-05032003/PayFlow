package com.darshan.payflow.data.local

import com.darshan.payflow.domain.model.PaymentStatus
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
}
