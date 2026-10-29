package com.darshan.payflow.domain.model

/** Lifecycle of a payment. `SUCCESS` is not terminal because a refund can still start from it. */
enum class PaymentStatus(val isTerminal: Boolean) {
    CREATED(false),
    PENDING(false),
    SUCCESS(false),
    FAILED(true),
    REFUND_PENDING(false),
    REFUNDED(true),
}

enum class PaymentMethod { UPI_INTENT, QR_SCAN, COLLECT, MANDATE, CHECKOUT }

enum class PaymentDirection { OUTGOING, INCOMING }

/** History chips. `PENDING` groups everything that is still in flight. */
enum class HistoryFilter(private val statuses: Set<PaymentStatus>) {
    ALL(PaymentStatus.entries.toSet()),
    SUCCESS(setOf(PaymentStatus.SUCCESS)),
    PENDING(setOf(PaymentStatus.CREATED, PaymentStatus.PENDING, PaymentStatus.REFUND_PENDING)),
    FAILED(setOf(PaymentStatus.FAILED)),
    REFUNDED(setOf(PaymentStatus.REFUNDED)),
    ;

    fun matches(status: PaymentStatus): Boolean = status in statuses
    fun statusNames(): List<String> = statuses.map { it.name }

    companion object {
        fun fromNameOrAll(name: String?): HistoryFilter = entries.firstOrNull { it.name == name } ?: ALL
    }
}
