package com.darshan.payflow.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.darshan.payflow.R
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.ui.PayFlowTestTags

/** The active payment mode, provided by the navigation root so every top bar can show the sandbox badge. */
val LocalPaymentMode = compositionLocalOf { PaymentMode.UPI_APPS }

/** Small amber pill shown in top bars whenever payments are settled by the in-app fake PSP instead of a real UPI app. */
@Composable
fun SandboxBadge(modifier: Modifier = Modifier) {
    val colors = PayFlowThemeStatus()
    val description = stringResource(R.string.sandbox_badge_description)
    Surface(color = colors.container, contentColor = colors.content, shape = CircleShape, modifier = modifier.testTag(PayFlowTestTags.SANDBOX_BADGE).semantics(mergeDescendants = true) { contentDescription = description }) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Science, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.sandbox_badge), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** Badge that appears only when the ambient [LocalPaymentMode] is sandbox. */
@Composable
fun SandboxBadgeIfActive(modifier: Modifier = Modifier) {
    if (LocalPaymentMode.current == PaymentMode.SANDBOX) SandboxBadge(modifier)
}

@Composable
private fun PayFlowThemeStatus() = com.darshan.payflow.ui.theme.PayFlowTheme.status.pending
