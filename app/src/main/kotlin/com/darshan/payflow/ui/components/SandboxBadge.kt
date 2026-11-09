package com.darshan.payflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.darshan.payflow.R
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme

/** The active payment mode, provided by the navigation root so every top bar can show the sandbox badge. */
val LocalPaymentMode = compositionLocalOf { PaymentMode.UPI_APPS }

/** Small amber pill shown in top bars whenever payments are settled by the in-app fake PSP instead of a real UPI app. */
@Composable
fun SandboxBadge(modifier: Modifier = Modifier) {
    val tone = PayFlowTheme.colors.pending
    val description = stringResource(R.string.sandbox_badge_description)
    Row(
        modifier = modifier
            .testTag(PayFlowTestTags.SANDBOX_BADGE)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .background(tone.container, Radii.pillShape)
            .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Icon(Icons.Outlined.Science, contentDescription = null, tint = tone.content, modifier = Modifier.size(Sizes.iconSmall))
        Text(stringResource(R.string.sandbox_badge), style = PayFlowTheme.type.labelStrong, color = tone.content)
    }
}

/** Badge that appears only when the ambient [LocalPaymentMode] is sandbox. */
@Composable
fun SandboxBadgeIfActive(modifier: Modifier = Modifier) {
    if (LocalPaymentMode.current == PaymentMode.SANDBOX) SandboxBadge(modifier)
}
