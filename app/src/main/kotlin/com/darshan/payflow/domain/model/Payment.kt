package com.darshan.payflow.domain.model

import java.time.Instant

/**
 * One payment attempt. Amounts are `Long` paise. [txnRef] is the merchant/app reference we send to the PSP
 * (`tr` in the UPI link); [pspTxnId] and [upiTxnId] are set only after the PSP confirmed the payment.
 */
data class Payment(
    val id: String,
    val txnRef: String,
    val payeeVpa: String,
    val payeeName: String,
    val amountPaise: Long,
    val note: String,
    val status: PaymentStatus,
    val method: PaymentMethod,
    val direction: PaymentDirection,
    val pspTxnId: String?,
    val upiTxnId: String?,
    val refundId: String?,
    val failureReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val idempotencyKey: String?,
) {
    init {
        require(amountPaise in 1..MAX_AMOUNT_PAISE) { "amount out of range: $amountPaise" }
        require(id.isNotBlank()) { "id is blank" }
        require(txnRef.isNotBlank()) { "txnRef is blank" }
    }

    companion object {
        /** ₹1,00,000 per UPI transaction (NPCI limit for most categories), in paise. */
        const val MAX_AMOUNT_PAISE: Long = 1_00_000_00L
    }
}

/** Aggregate numbers for the home card. Money sums only count captured payments. */
data class HistorySummary(
    val total: Int,
    val success: Int,
    val pending: Int,
    val failed: Int,
    val refunded: Int,
    val sentPaise: Long,
    val receivedPaise: Long,
) {
    companion object {
        val EMPTY = HistorySummary(0, 0, 0, 0, 0, 0, 0)

        fun of(payments: Collection<Payment>): HistorySummary {
            val captured = setOf(PaymentStatus.SUCCESS, PaymentStatus.REFUND_PENDING)
            return HistorySummary(
                total = payments.size,
                success = payments.count { it.status in captured },
                pending = payments.count { it.status == PaymentStatus.CREATED || it.status == PaymentStatus.PENDING },
                failed = payments.count { it.status == PaymentStatus.FAILED },
                refunded = payments.count { it.status == PaymentStatus.REFUNDED },
                sentPaise = payments.filter { it.status in captured && it.direction == PaymentDirection.OUTGOING }.sumOf { it.amountPaise },
                receivedPaise = payments.filter { it.status in captured && it.direction == PaymentDirection.INCOMING }.sumOf { it.amountPaise },
            )
        }
    }
}
