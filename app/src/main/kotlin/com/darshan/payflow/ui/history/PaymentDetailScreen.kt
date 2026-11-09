package com.darshan.payflow.ui.history

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.components.AmountDecimals
import com.darshan.payflow.ui.designsystem.components.AmountText
import com.darshan.payflow.ui.designsystem.components.DisplayText
import com.darshan.payflow.ui.designsystem.components.EmptyState
import com.darshan.payflow.ui.designsystem.components.ErrorState
import com.darshan.payflow.ui.designsystem.components.GhostButton
import com.darshan.payflow.ui.designsystem.components.GhostTone
import com.darshan.payflow.ui.designsystem.components.HairlineDivider
import com.darshan.payflow.ui.designsystem.components.IllustrationKind
import com.darshan.payflow.ui.designsystem.components.PayFlowAvatar
import com.darshan.payflow.ui.designsystem.components.PayFlowIconButton
import com.darshan.payflow.ui.designsystem.components.PayFlowSurface
import com.darshan.payflow.ui.designsystem.components.PayFlowToastHost
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.components.SheetPhysics
import com.darshan.payflow.ui.designsystem.components.SheetTarget
import com.darshan.payflow.ui.designsystem.components.SkeletonList
import com.darshan.payflow.ui.designsystem.components.SurfaceLevel
import com.darshan.payflow.ui.designsystem.components.rememberToastHostState
import com.darshan.payflow.ui.designsystem.effects.ToastTone
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.navigation.containerTransform
import com.darshan.payflow.ui.pay.ReceiptCard
import com.darshan.payflow.ui.pay.ReceiptContent
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlinx.coroutines.launch

data class PaymentDetailCallbacks(
    val onBack: () -> Unit = {},
    val onMarkPaid: () -> Unit = {},
    val onMarkNotPaid: () -> Unit = {},
    val onToggleAmounts: () -> Unit = {},
)

@Composable
fun PaymentDetailRoute(onBack: () -> Unit, containerKey: String? = null, viewModel: PaymentDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hidden by viewModel.amountsHidden.collectAsStateWithLifecycle()
    PaymentDetailScreen(
        state,
        PaymentDetailCallbacks(
            onBack = onBack,
            onMarkPaid = { viewModel.markManually(paid = true) },
            onMarkNotPaid = { viewModel.markManually(paid = false) },
            onToggleAmounts = viewModel::toggleAmountsHidden,
        ),
        amountsHidden = hidden,
        containerKey = containerKey,
    )
}

@Composable
fun PaymentMethod.label(): String = stringResource(
    when (this) {
        PaymentMethod.UPI_INTENT -> R.string.method_upi_intent
        PaymentMethod.QR_SCAN -> R.string.method_qr_scan
        PaymentMethod.COLLECT -> R.string.method_collect
        PaymentMethod.MANDATE -> R.string.method_mandate
        PaymentMethod.CHECKOUT -> R.string.method_checkout
        PaymentMethod.DEEP_LINK -> R.string.method_deep_link
    },
)

@Composable
fun PaymentDetailScreen(state: PaymentDetailUiState, onBack: () -> Unit) = PaymentDetailScreen(state, PaymentDetailCallbacks(onBack = onBack))

/**
 * A payment as a sheet over the list it came from. The row it was opened from grows into it ([containerKey]), and dragging
 * the top down puts it back. The sheet is drawn for every state, so the transform always has somewhere to land.
 *
 * Status and source come from the same [ReceiptCard] Pay prints, so the two never word a payment differently. Amounts follow
 * Home's hide setting.
 */
@Composable
fun PaymentDetailScreen(
    state: PaymentDetailUiState,
    callbacks: PaymentDetailCallbacks,
    amountsHidden: Boolean = false,
    containerKey: String? = null,
) {
    val colors = PayFlowTheme.colors
    val motion = LocalMotion.current
    val toasts = rememberToastHostState()
    val scope = rememberCoroutineScope()
    var drag by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta -> drag = SheetPhysics.applyDrag(drag, delta).coerceAtLeast(0f) }
    val sheetColor = if (colors.isDark) colors.raised else colors.surface

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(
            Modifier
                .statusBarsPadding()
                .padding(top = Spacing.xs)
                .fillMaxSize()
                .then(if (containerKey != null) Modifier.containerTransform(containerKey) else Modifier)
                .onSizeChanged { height = it.height.toFloat() }
                .graphicsLayer { translationY = drag }
                .clip(Radii.topLargeShape)
                .background(sheetColor)
                .border(Hairline.width, colors.hairline, Radii.topLargeShape)
                .testTag(PayFlowTestTags.DETAIL_SHEET),
        ) {
            // The grab area: handle and title. Dragging it down far or fast enough closes the sheet.
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        dragState,
                        Orientation.Vertical,
                        onDragStopped = { velocity ->
                            if (SheetPhysics.settle(drag, height, velocity, dismissible = true) == SheetTarget.Dismissed) {
                                callbacks.onBack()
                            } else {
                                scope.launch { animate(drag, 0f, velocity, motion.spec(MotionToken.Bounce)) { v, _ -> drag = v } }
                            }
                        },
                    ),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = Spacing.sm), contentAlignment = Alignment.Center) {
                    val handle = colors.outline
                    Spacer(Modifier.size(Sizes.sheetHandleWidth, Sizes.sheetHandleHeight).drawBehind { drawRoundRect(handle, cornerRadius = CornerRadius(size.height / 2)) })
                }
                Row(Modifier.fillMaxWidth().padding(start = Spacing.screen, end = Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.payment_detail_title),
                        style = PayFlowTheme.type.title,
                        color = colors.text,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    if (state is PaymentDetailUiState.Content) {
                        PayFlowIconButton(
                            if (amountsHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            stringResource(if (amountsHidden) R.string.home_show_amounts else R.string.home_hide_amounts),
                            callbacks.onToggleAmounts,
                            tint = colors.textSecondary,
                            modifier = Modifier.testTag(PayFlowTestTags.DETAIL_HIDE_AMOUNTS),
                        )
                    }
                    PayFlowIconButton(Icons.Outlined.Close, stringResource(R.string.detail_sheet_close), callbacks.onBack, tint = colors.text)
                }
            }
            when (state) {
                PaymentDetailUiState.Loading -> SkeletonList(Modifier.fillMaxWidth().testTag(PayFlowTestTags.LOADING), count = 4)
                PaymentDetailUiState.NotFound -> EmptyState(
                    title = stringResource(R.string.payment_not_found),
                    illustration = IllustrationKind.NoResults,
                    modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.EMPTY),
                )
                is PaymentDetailUiState.Error -> ErrorState(
                    title = stringResource(R.string.error_generic),
                    retryLabel = stringResource(R.string.back),
                    onRetry = callbacks.onBack,
                    modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.ERROR),
                )
                is PaymentDetailUiState.Content -> DetailContent(state, callbacks, amountsHidden) { message -> toasts.show(message, ToastTone.Info) }
            }
        }
        PayFlowToastHost(toasts, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun DetailContent(state: PaymentDetailUiState.Content, callbacks: PaymentDetailCallbacks, amountsHidden: Boolean, onCopied: (String) -> Unit) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val payment = state.payment
    val outgoing = payment.direction == PaymentDirection.OUTGOING
    val name = remember(payment.payeeName) { DisplayText.safe(payment.payeeName) }
    val receipt = remember(payment, state.handlerLabel) { ReceiptContent.from(payment, state.handlerLabel) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screen)
            .padding(bottom = Spacing.xl)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            PayFlowAvatar(name, size = Sizes.avatarLarge)
            Spacer(Modifier.height(Spacing.xxs))
            Text(stringResource(if (outgoing) R.string.payment_to else R.string.payment_from), style = type.label, color = colors.textSecondary)
            Text(name, style = type.title, color = colors.text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(DisplayText.safe(payment.payeeVpa), style = type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AmountText(
                payment.amountPaise,
                Modifier.padding(top = Spacing.xs).testTag(PayFlowTestTags.DETAIL_AMOUNT),
                style = type.display,
                color = colors.text,
                decimals = AmountDecimals.Always,
                hidden = amountsHidden,
            )
            payment.failureReason?.let {
                Text(DisplayText.safe(it), style = type.body, color = colors.failed.strong, textAlign = TextAlign.Center)
            }
        }
        if (payment.canBeMarkedManually) MarkCard(state, callbacks)
        // The sheet is the surface colour in light, so the receipt is printed on the page colour there to stay a slip of paper.
        ReceiptCard(receipt, payment, state.handlerLabel, tag = PayFlowTestTags.DETAIL_RECEIPT, paper = if (colors.isDark) colors.surface else colors.bg)
        MoreDetails(payment, onCopied)
    }
}

@Composable
private fun MarkCard(state: PaymentDetailUiState.Content, callbacks: PaymentDetailCallbacks) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val payment = state.payment
    PayFlowSurface(Modifier.fillMaxWidth(), level = SurfaceLevel.Raised) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.detail_mark_title), style = type.bodyStrong, color = colors.text)
            Text(
                stringResource(R.string.detail_mark_hint, state.handlerLabel ?: payment.knownHandlerPackage ?: stringResource(R.string.pay_source_app_generic)),
                style = type.body,
                color = colors.textSecondary,
            )
            PrimaryButton(stringResource(R.string.detail_mark_paid), callbacks.onMarkPaid, loading = state.marking, modifier = Modifier.fillMaxWidth().padding(top = Spacing.xxs).testTag(PayFlowTestTags.DETAIL_MARK_PAID))
            GhostButton(stringResource(R.string.detail_mark_not_paid), callbacks.onMarkNotPaid, enabled = !state.marking, tone = GhostTone.Neutral, modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.DETAIL_MARK_NOT_PAID))
        }
    }
}

/** What the receipt leaves out: how it was paid, the provider's own ID, a refund's ID. Each can be copied for support. */
@Composable
private fun MoreDetails(payment: Payment, onCopied: (String) -> Unit) {
    val colors = PayFlowTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(stringResource(R.string.detail_section_details), style = PayFlowTheme.type.labelStrong, color = colors.textSecondary, modifier = Modifier.semantics { heading() })
        HairlineDivider()
        InfoRow(stringResource(R.string.detail_method), payment.method.label())
        payment.pspTxnId?.let { CopyRow(stringResource(R.string.detail_psp_txn), it, onCopied) }
        payment.refundId?.let { CopyRow(stringResource(R.string.detail_refund_id), it, onCopied) }
        // The receipt already shows the reference; this only copies it.
        val clipboard = LocalClipboardManager.current
        val referenceLabel = stringResource(R.string.detail_txn_ref)
        val copied = stringResource(R.string.detail_copied, referenceLabel)
        GhostButton(
            stringResource(R.string.detail_copy, referenceLabel.lowercase()),
            {
                clipboard.setText(AnnotatedString(payment.txnRef))
                onCopied(copied)
            },
            outlined = false,
            tone = GhostTone.Neutral,
            leadingIcon = Icons.Outlined.ContentCopy,
            debounceMs = 0L,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(label, style = type.label, color = colors.textSecondary)
        Text(value, style = type.label, color = colors.text, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** An ID with a copy button, for support. */
@Composable
private fun CopyRow(label: String, value: String, onCopied: (String) -> Unit) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(R.string.detail_copied, label)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(label, style = type.label, color = colors.textSecondary)
        Text(
            value,
            style = type.label,
            color = colors.text,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PayFlowIconButton(
            Icons.Outlined.ContentCopy,
            stringResource(R.string.detail_copy, label),
            {
                clipboard.setText(AnnotatedString(value))
                onCopied(copied)
            },
            tint = colors.textSecondary,
        )
    }
}
