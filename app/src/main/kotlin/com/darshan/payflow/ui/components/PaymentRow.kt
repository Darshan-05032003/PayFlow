package com.darshan.payflow.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.ui.PayFlowTestTags
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun PaymentStatus.label(): String = stringResource(
    when (this) {
        PaymentStatus.CREATED -> R.string.status_created
        PaymentStatus.PENDING -> R.string.status_pending
        PaymentStatus.SUCCESS -> R.string.status_success
        PaymentStatus.FAILED -> R.string.status_failed
        PaymentStatus.REFUND_PENDING -> R.string.status_refund_pending
        PaymentStatus.REFUNDED -> R.string.status_refunded
    },
)

/** Foreground colour for a status, usable on plain surfaces (headlines, detail text). */
@Composable
fun PaymentStatus.color(): Color = statusColor().content

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

fun Payment.formattedDate(zone: ZoneId = ZoneId.systemDefault()): String = dateFormatter.format(createdAt.atZone(zone))

/**
 * One payment in a list: payee avatar + name, relative time and UPI ID, amount and a status chip.
 * The whole row is one accessibility node ("Payment to Alice, ₹123.45, Success").
 */
@Composable
fun PaymentRow(payment: Payment, onClick: (String) -> Unit, modifier: Modifier = Modifier) {
    val amount = Money.formatInr(payment.amountPaise)
    val status = payment.status.label()
    val outgoing = payment.direction == PaymentDirection.OUTGOING
    val direction = stringResource(if (outgoing) R.string.payment_to else R.string.payment_from)
    val description = stringResource(R.string.payment_row_description, direction, payment.payeeName, amount, status)
    val time = payment.createdAt.relativeLabel()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(payment.id) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(PayFlowTestTags.paymentRow(payment.id))
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InitialAvatar(payment.payeeName)
        Column(modifier = Modifier.weight(1f)) {
            Text(payment.payeeName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$time · ${payment.payeeVpa}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (outgoing) "− $amount" else "+ $amount",
                style = MaterialTheme.typography.titleMedium,
                color = if (!outgoing) PayFlowThemeSuccess() else MaterialTheme.colorScheme.onSurface,
            )
            StatusChip(payment.status)
        }
    }
}

@Composable
private fun PayFlowThemeSuccess(): Color = com.darshan.payflow.ui.theme.PayFlowTheme.status.success.content
