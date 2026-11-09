package com.darshan.payflow.ui.pay

import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.ui.designsystem.components.DisplayText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Who established a receipt's status. */
data class ReceiptSource(val statusSource: StatusSource, val appLabel: String?, val upiAppPayment: Boolean)

/**
 * The one place a receipt's contents are decided: the screen, the shared text and the shared image all read this, so none of
 * them can disagree with the stored payment.
 *
 * A shared receipt is proof of payment and nothing else. It leaves out the user's note, the provider's internal transaction
 * ID, the failure text, the installed app's package name, and every internal key.
 */
data class ReceiptContent(
    val amountPaise: Long,
    val payeeName: String,
    val payeeVpa: String,
    val status: PaymentStatus,
    val source: ReceiptSource,
    val reference: String,
    val upiTxnId: String?,
    val createdAt: Instant,
) {
    /** Plain text for the share sheet. Says who reported the status and never claims a check PayFlow can't make. */
    fun text(zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append("PayFlow receipt\n")
        append("To: ").append(payeeName).append(" (").append(payeeVpa).append(")\n")
        append("Amount: ").append(Money.formatInr(amountPaise)).append('\n')
        append("Status: ").append(statusLabel(status))
        statusSuffix()?.let { append(' ').append(it) }
        append('\n')
        append("Reference: ").append(reference).append('\n')
        upiTxnId?.let { append("UPI transaction: ").append(it).append('\n') }
        append("Time: ").append(timeText(zone))
    }

    fun timeText(zone: ZoneId = ZoneId.systemDefault()): String = TIME_FORMAT.format(createdAt.atZone(zone))

    private fun statusSuffix(): String? {
        val pending = status == PaymentStatus.CREATED || status == PaymentStatus.PENDING
        return when {
            pending && source.upiAppPayment -> "(not confirmed)"
            pending -> null
            source.statusSource == StatusSource.UPI_APP -> "(reported by ${source.appLabel ?: GENERIC_APP})"
            source.statusSource == StatusSource.USER -> "(marked by you)"
            else -> "(sandbox provider)"
        }
    }

    companion object {
        private const val GENERIC_APP = "UPI app"
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)

        fun from(payment: Payment, handlerLabel: String?): ReceiptContent = ReceiptContent(
            amountPaise = payment.amountPaise,
            payeeName = oneLine(payment.payeeName),
            payeeVpa = oneLine(payment.payeeVpa),
            status = payment.status,
            source = ReceiptSource(payment.statusSource, handlerLabel?.let(::oneLine), payment.isUpiAppPayment),
            reference = oneLine(payment.txnRef),
            upiTxnId = payment.upiTxnId?.let(::oneLine),
            createdAt = payment.createdAt,
        )

        fun statusLabel(status: PaymentStatus): String = when (status) {
            PaymentStatus.CREATED, PaymentStatus.PENDING -> "Pending"
            PaymentStatus.SUCCESS -> "Success"
            PaymentStatus.FAILED -> "Failed"
            PaymentStatus.REFUND_PENDING -> "Refund pending"
            PaymentStatus.REFUNDED -> "Refunded"
        }

        /** Text from outside the app can't add receipt lines or reverse what's around it. */
        private fun oneLine(text: String): String =
            DisplayText.safe(text).map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
    }
}
