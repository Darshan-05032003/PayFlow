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
    /** Set when the payment is a debit under an AutoPay mandate. */
    val mandateId: String? = null,
    /** Who established [status]: the sandbox PSP, the UPI app's response, or the user. */
    val statusSource: StatusSource = StatusSource.PSP,
    /**
     * Package of the UPI app that handled the payment (UPI-apps mode), [VIA_SYSTEM_CHOOSER] when the system chooser picked it,
     * null for sandbox payments. Non-null means: no PSP knows this payment, never ask one about it.
     */
    val handlerPackage: String? = null,
) {
    /** Sent to a real UPI app (UPI-apps mode). The sandbox PSP must never confirm, poll or fail it. */
    val isUpiAppPayment: Boolean get() = handlerPackage != null

    /** The handler's package when PayFlow knows it (not the system-chooser sentinel). */
    val knownHandlerPackage: String? get() = handlerPackage?.takeIf { it != VIA_SYSTEM_CHOOSER }

    /** A real UPI-app payment whose outcome is still unknown: the user may settle it by hand after checking their UPI app. */
    val canBeMarkedManually: Boolean
        get() = isUpiAppPayment && (status == PaymentStatus.PENDING || status == PaymentStatus.CREATED)

    init {
        require(amountPaise in 1..MAX_AMOUNT_PAISE) { "amount out of range: $amountPaise" }
        require(id.isNotBlank()) { "id is blank" }
        require(txnRef.isNotBlank()) { "txnRef is blank" }
    }

    companion object {
        /** ₹1,00,000 per UPI transaction (NPCI limit for most categories), in paise. */
        const val MAX_AMOUNT_PAISE: Long = 1_00_000_00L

        /** [handlerPackage] for a UPI-apps payment launched through the system chooser (the chosen app is unknown). */
        const val VIA_SYSTEM_CHOOSER = "upi:system-chooser"
    }
}

/** A payee the user has paid before (distinct by VPA), for the "recent payees" row. */
data class RecentPayee(val vpa: String, val name: String, val lastPaidAt: Instant)

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
