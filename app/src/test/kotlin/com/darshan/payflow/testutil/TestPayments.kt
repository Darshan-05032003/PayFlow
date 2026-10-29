package com.darshan.payflow.testutil

import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

val FIXED_INSTANT: Instant = Instant.parse("2026-10-29T10:15:30Z")
val fixedClock: Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)

fun testPayment(
    id: String = "pay-1",
    txnRef: String = "PF$id",
    payeeVpa: String = "alice@okbank",
    payeeName: String = "Alice",
    amountPaise: Long = 12_345,
    status: PaymentStatus = PaymentStatus.CREATED,
    method: PaymentMethod = PaymentMethod.UPI_INTENT,
    direction: PaymentDirection = PaymentDirection.OUTGOING,
    createdAt: Instant = FIXED_INSTANT,
    note: String = "Lunch",
    idempotencyKey: String? = null,
): Payment = Payment(
    id = id,
    txnRef = txnRef,
    payeeVpa = payeeVpa,
    payeeName = payeeName,
    amountPaise = amountPaise,
    note = note,
    status = status,
    method = method,
    direction = direction,
    pspTxnId = null,
    upiTxnId = null,
    refundId = null,
    failureReason = null,
    createdAt = createdAt,
    updatedAt = createdAt,
    idempotencyKey = idempotencyKey,
)
