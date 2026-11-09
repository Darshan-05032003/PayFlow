package com.darshan.payflow.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.darshan.payflow.R
import com.darshan.payflow.domain.model.PaymentStatus

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
