package com.darshan.payflow.ui.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme

/** A deliberate empty state: drawing, one line of title, one of explanation, and at most one action. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    illustration: IllustrationKind = IllustrationKind.EmptyLedger,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StateColumn(modifier, illustration, title, message, liveRegion = false) {
        if (actionLabel != null && onAction != null) GhostButton(actionLabel, onAction)
    }
}

/**
 * A deliberate error state with a retry. TalkBack announces it when it appears. The retry is the primary action unless the
 * screen already has one ([primaryRetry] = false), as Home does with Scan & Pay: one primary action per screen.
 */
@Composable
fun ErrorState(
    title: String,
    retryLabel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    illustration: IllustrationKind = IllustrationKind.ErrorReceipt,
    primaryRetry: Boolean = true,
) {
    StateColumn(modifier, illustration, title, message, liveRegion = true) {
        if (primaryRetry) PrimaryButton(retryLabel, onRetry) else GhostButton(retryLabel, onRetry)
    }
}

@Composable
private fun StateColumn(
    modifier: Modifier,
    illustration: IllustrationKind,
    title: String,
    message: String?,
    liveRegion: Boolean,
    action: @Composable () -> Unit,
) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    Column(
        modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Illustration(illustration)
        Text(
            title,
            style = type.title,
            color = colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = Spacing.md)
                .then(if (liveRegion) Modifier.semantics { this.liveRegion = LiveRegionMode.Polite } else Modifier),
        )
        if (message != null) Text(message, style = type.body, color = colors.textSecondary, textAlign = TextAlign.Center)
        Column(Modifier.padding(top = Spacing.md)) { action() }
    }
}
