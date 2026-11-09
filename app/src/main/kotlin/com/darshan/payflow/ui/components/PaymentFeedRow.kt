package com.darshan.payflow.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.components.AmountDecimals
import com.darshan.payflow.ui.designsystem.components.AmountSign
import com.darshan.payflow.ui.designsystem.components.AmountText
import com.darshan.payflow.ui.designsystem.components.DisplayText
import com.darshan.payflow.ui.designsystem.components.PayFlowAvatar
import com.darshan.payflow.ui.designsystem.components.PayFlowListRow
import com.darshan.payflow.ui.designsystem.components.StatusKind
import com.darshan.payflow.ui.designsystem.components.StatusPill
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.navigation.containerTransform
import com.darshan.payflow.ui.navigation.paymentContainerKey
import com.darshan.payflow.ui.theme.PayFlowTheme

/** A settled, successful payment is the normal case and needs no label: only exceptions get a pill. */
fun PaymentStatus.feedPill(): StatusKind? = when (this) {
    PaymentStatus.SUCCESS -> null
    PaymentStatus.CREATED, PaymentStatus.PENDING -> StatusKind.Pending
    PaymentStatus.FAILED -> StatusKind.Failed
    PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED -> StatusKind.Refund
}

/**
 * The payment row everywhere a list of payments is shown: Home's feed, History, a mandate's debits. Avatar, payee, a time
 * line and the UPI ID, the signed amount (masked when [amountsHidden]) and a pill for anything but a plain success. TalkBack
 * hears one sentence with the status, pill or not. The row is one end of the container transform into the payment's detail.
 *
 * [timeLabel] is whatever reads best where the row sits: "2 h ago" on Home, "10:38 PM" under a day header.
 */
@Composable
fun PaymentFeedRow(
    payment: Payment,
    amountsHidden: Boolean,
    timeLabel: String,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PayFlowTheme.colors
    val outgoing = payment.direction == PaymentDirection.OUTGOING
    val name = remember(payment.payeeName) { DisplayText.safe(payment.payeeName) }
    val statusLabel = payment.status.label()
    val direction = stringResource(if (outgoing) R.string.payment_to else R.string.payment_from)
    val spokenAmount = if (amountsHidden) stringResource(R.string.ds_amount_hidden) else Money.formatInr(payment.amountPaise)
    val description = stringResource(R.string.payment_row_description, direction, name, spokenAmount, statusLabel)
    val amountColor = when {
        payment.status == PaymentStatus.FAILED -> colors.textSecondary
        !outgoing -> colors.success.strong
        else -> colors.text
    }
    PayFlowListRow(
        title = name,
        subtitle = "$timeLabel · ${payment.payeeVpa}",
        leading = { PayFlowAvatar(name) },
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                AmountText(
                    paise = if (outgoing) -payment.amountPaise else payment.amountPaise,
                    color = amountColor,
                    decimals = AmountDecimals.WhenNonZero,
                    sign = AmountSign.Always,
                    hidden = amountsHidden,
                )
                val pill: StatusKind? = payment.status.feedPill()
                if (pill != null) StatusPill(pill, statusLabel, animate = false)
            }
        },
        onClick = { onClick(payment.id) },
        modifier = modifier
            .containerTransform(paymentContainerKey(payment.id))
            .testTag(PayFlowTestTags.paymentRow(payment.id))
            .semantics(mergeDescendants = true) { contentDescription = description },
    )
}
