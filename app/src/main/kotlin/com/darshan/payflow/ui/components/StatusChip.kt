package com.darshan.payflow.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.darshan.payflow.ui.theme.StatusColor

/** Semantic colours for a payment status: green success, amber pending, red failed, blue refunds. */
@Composable
fun PaymentStatus.statusColor(): StatusColor = when (this) {
    PaymentStatus.SUCCESS -> PayFlowTheme.status.success
    PaymentStatus.FAILED -> PayFlowTheme.status.failed
    PaymentStatus.REFUNDED, PaymentStatus.REFUND_PENDING -> PayFlowTheme.status.refunded
    PaymentStatus.PENDING -> PayFlowTheme.status.pending
    PaymentStatus.CREATED -> PayFlowTheme.status.neutral
}

fun PaymentStatus.icon(): ImageVector = when (this) {
    PaymentStatus.SUCCESS -> Icons.Outlined.CheckCircle
    PaymentStatus.FAILED -> Icons.Outlined.ErrorOutline
    PaymentStatus.REFUNDED, PaymentStatus.REFUND_PENDING -> Icons.Outlined.Replay
    PaymentStatus.CREATED, PaymentStatus.PENDING -> Icons.Outlined.Schedule
}

/** A small pill with a tinted background, used wherever a status is shown in a list or header. */
@Composable
fun StatusChip(label: String, colors: StatusColor, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Surface(color = colors.container, contentColor = colors.content, shape = CircleShape, modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = if (icon == null) 10.dp else 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp).padding(end = 0.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                modifier = if (icon != null) Modifier.padding(start = 4.dp) else Modifier,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun StatusChip(status: PaymentStatus, modifier: Modifier = Modifier, withIcon: Boolean = false) {
    StatusChip(status.label(), status.statusColor(), modifier, icon = if (withIcon) status.icon() else null)
}
