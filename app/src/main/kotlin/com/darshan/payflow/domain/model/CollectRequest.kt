package com.darshan.payflow.domain.model

import java.time.Instant

enum class CollectStatus { PENDING, APPROVED, DECLINED, EXPIRED }

/** An incoming "pay me" request from a payee, as relayed by the PSP. Answering APPROVED creates a [Payment] with method COLLECT. */
data class CollectRequest(
    val id: String,
    val payeeVpa: String,
    val payeeName: String,
    val amountPaise: Long,
    val note: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val updatedAt: Instant,
    val status: CollectStatus,
    val paymentId: String?,
) {
    fun isOverdue(now: Instant): Boolean = status == CollectStatus.PENDING && !expiresAt.isAfter(now)

    /** The status to display: a pending request past its expiry is shown as expired even before the next refresh persists it. */
    fun effectiveStatus(now: Instant): CollectStatus = if (isOverdue(now)) CollectStatus.EXPIRED else status
}
