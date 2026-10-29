package com.darshan.payflow.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import java.time.Instant

@Entity(
    tableName = "payments",
    indices = [
        Index(value = ["txn_ref"], unique = true),
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["created_at"]),
        Index(value = ["status"]),
    ],
)
data class PaymentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "txn_ref") val txnRef: String,
    @ColumnInfo(name = "payee_vpa") val payeeVpa: String,
    @ColumnInfo(name = "payee_name") val payeeName: String,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    val note: String,
    val status: String,
    val method: String,
    val direction: String,
    @ColumnInfo(name = "psp_txn_id") val pspTxnId: String?,
    @ColumnInfo(name = "upi_txn_id") val upiTxnId: String?,
    @ColumnInfo(name = "refund_id") val refundId: String?,
    @ColumnInfo(name = "failure_reason") val failureReason: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String?,
    @ColumnInfo(name = "mandate_id") val mandateId: String? = null,
)

/** Row shape of [PaymentDao.observeSummary]. */
data class SummaryRow(
    val total: Int,
    val success: Int,
    val pending: Int,
    val failed: Int,
    val refunded: Int,
    @ColumnInfo(name = "sent_paise") val sentPaise: Long,
    @ColumnInfo(name = "received_paise") val receivedPaise: Long,
) {
    fun toDomain() = HistorySummary(total, success, pending, failed, refunded, sentPaise, receivedPaise)
}

fun Payment.toEntity() = PaymentEntity(
    id = id,
    txnRef = txnRef,
    payeeVpa = payeeVpa,
    payeeName = payeeName,
    amountPaise = amountPaise,
    note = note,
    status = status.name,
    method = method.name,
    direction = direction.name,
    pspTxnId = pspTxnId,
    upiTxnId = upiTxnId,
    refundId = refundId,
    failureReason = failureReason,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    idempotencyKey = idempotencyKey,
    mandateId = mandateId,
)

fun PaymentEntity.toDomain() = Payment(
    id = id,
    txnRef = txnRef,
    payeeVpa = payeeVpa,
    payeeName = payeeName,
    amountPaise = amountPaise,
    note = note,
    status = PaymentStatus.valueOf(status),
    method = PaymentMethod.valueOf(method),
    direction = PaymentDirection.valueOf(direction),
    pspTxnId = pspTxnId,
    upiTxnId = upiTxnId,
    refundId = refundId,
    failureReason = failureReason,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    idempotencyKey = idempotencyKey,
    mandateId = mandateId,
)
