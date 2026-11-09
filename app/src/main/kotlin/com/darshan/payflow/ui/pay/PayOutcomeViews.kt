package com.darshan.payflow.ui.pay

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.darshan.payflow.R
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.components.AmountDecimals
import com.darshan.payflow.ui.designsystem.components.AmountOdometer
import com.darshan.payflow.ui.designsystem.components.AmountText
import com.darshan.payflow.ui.designsystem.components.DisplayText
import com.darshan.payflow.ui.designsystem.components.GhostButton
import com.darshan.payflow.ui.designsystem.components.GhostTone
import com.darshan.payflow.ui.designsystem.components.HairlineDivider
import com.darshan.payflow.ui.designsystem.components.Illustration
import com.darshan.payflow.ui.designsystem.components.IllustrationKind
import com.darshan.payflow.ui.designsystem.components.PayFlowAvatar
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.components.StatusKind
import com.darshan.payflow.ui.designsystem.components.StatusPill
import com.darshan.payflow.ui.designsystem.modifiers.metallicSheen
import com.darshan.payflow.ui.designsystem.modifiers.rememberSheenSweep
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionDurations
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme
import androidx.compose.runtime.withFrameMillis

@Composable
private fun CenteredTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = PayFlowTheme.type.title,
        color = PayFlowTheme.colors.text,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .testTag(PayFlowTestTags.PAY_OUTCOME_TITLE)
            .semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
    )
}

@Composable
private fun CenteredHint(text: String) {
    Text(text, style = PayFlowTheme.type.body, color = PayFlowTheme.colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

/** Scrolling content over a docked action area, so the actions are reachable however long the content gets. */
@Composable
private fun OutcomeFrame(
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen, vertical = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) { content() }
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) { actions() }
    }
}

/** Handed off to the UPI app, or come back to after process death. */
@Composable
fun AwaitingAppView(phase: PayPhase.AwaitingApp, mode: PaymentMode?, callbacks: PayCallbacks, modifier: Modifier = Modifier) {
    val real = mode == PaymentMode.UPI_APPS
    val waitingLabel = stringResource(R.string.pay_processing_label)
    OutcomeFrame(
        modifier,
        actions = {
            if (real) {
                GhostButton(stringResource(R.string.pay_view_payment), { callbacks.onOpenPayment(phase.paymentId) }, modifier = Modifier.fillMaxWidth())
            } else {
                GhostButton(stringResource(R.string.pay_check_status), callbacks.onCheckStatus, modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_CHECK_STATUS))
            }
        },
    ) {
        ProcessingRing(label = waitingLabel)
        CenteredTitle(stringResource(R.string.pay_awaiting_app))
        CenteredHint(stringResource(if (real) R.string.pay_awaiting_app_hint_real else R.string.pay_awaiting_app_hint))
    }
}

/** Waiting for an answer. The words get more honest as the wait gets longer, and at the limit the way out opens. */
@Composable
fun ConfirmingView(mode: PaymentMode?, stage: ProcessingStage, callbacks: PayCallbacks, modifier: Modifier = Modifier) {
    val real = mode == PaymentMode.UPI_APPS
    val waitingLabel = stringResource(R.string.pay_processing_label)
    OutcomeFrame(
        modifier,
        actions = {
            if (stage == ProcessingStage.BANK_SLOW) {
                GhostButton(stringResource(R.string.pay_leave), callbacks.onBack, modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_LEAVE))
            }
        },
    ) {
        ProcessingRing(label = waitingLabel)
        when (stage) {
            ProcessingStage.NORMAL -> {
                CenteredTitle(stringResource(if (real) R.string.pay_recording else R.string.pay_confirming))
                if (!real) CenteredHint(stringResource(R.string.pay_confirming_hint))
            }
            ProcessingStage.STILL_CHECKING -> {
                CenteredTitle(stringResource(R.string.pay_still_checking))
                CenteredHint(stringResource(R.string.pay_still_checking_hint))
            }
            ProcessingStage.BANK_SLOW -> {
                CenteredTitle(stringResource(R.string.pay_bank_slow))
                CenteredHint(stringResource(R.string.pay_bank_slow_hint))
            }
        }
    }
}

/** No usable answer: the provider couldn't be reached, or answered for other details. It stays pending and never celebrates. */
@Composable
fun UnresolvedView(phase: PayPhase.Unresolved, polling: Boolean, stage: ProcessingStage, callbacks: PayCallbacks, modifier: Modifier = Modifier) {
    OutcomeFrame(
        modifier,
        actions = {
            PrimaryButton(stringResource(R.string.pay_check_status), callbacks.onCheckStatus, loading = polling, modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_CHECK_STATUS))
            GhostButton(stringResource(R.string.pay_view_payment), { callbacks.onOpenPayment(phase.paymentId) }, modifier = Modifier.fillMaxWidth())
        },
    ) {
        Illustration(if (phase.mismatch) IllustrationKind.ErrorReceipt else IllustrationKind.Offline)
        CenteredTitle(stringResource(if (phase.mismatch) R.string.pay_mismatch else R.string.pay_unresolved))
        CenteredHint(stringResource(if (phase.mismatch) R.string.pay_mismatch_hint else R.string.pay_unresolved_hint))
        if (polling) PollingLine(stage)
    }
}

@Composable
private fun PollingLine(stage: ProcessingStage) {
    Text(
        stringResource(if (stage == ProcessingStage.BANK_SLOW) R.string.pay_polling_slow else R.string.pay_polling),
        style = PayFlowTheme.type.label,
        color = PayFlowTheme.colors.pending.strong,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Who established the status, in words the user can act on. Never says "verified" for an app-reported or user-marked result. */
@Composable
fun statusSourceLabel(payment: Payment, handlerLabel: String?): String {
    val app = handlerLabel ?: payment.knownHandlerPackage ?: stringResource(R.string.pay_source_app_generic)
    val pending = payment.status == PaymentStatus.PENDING || payment.status == PaymentStatus.CREATED
    return when {
        pending && payment.isUpiAppPayment -> stringResource(R.string.pay_source_unconfirmed, app)
        pending -> stringResource(R.string.pay_source_pending_psp)
        payment.statusSource == StatusSource.UPI_APP -> stringResource(R.string.pay_source_reported_by, app)
        payment.statusSource == StatusSource.USER -> stringResource(R.string.pay_source_marked_by_user)
        payment.status == PaymentStatus.REFUNDED || payment.status == PaymentStatus.REFUND_PENDING -> stringResource(R.string.pay_source_refund)
        payment.status == PaymentStatus.FAILED -> stringResource(R.string.pay_source_psp_failed)
        else -> stringResource(R.string.pay_source_psp)
    }
}

/**
 * The result. Glyph, title, amount and payee, then the receipt printing down, then the actions. Whatever the outcome earned is
 * decided in [PayOutcomes]; this only draws it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultView(
    phase: PayPhase.Done,
    mode: PaymentMode?,
    polling: Boolean,
    stage: ProcessingStage,
    callbacks: PayCallbacks,
    onShareRequest: () -> Unit,
    onPayAgainRequest: () -> Unit,
    modifier: Modifier = Modifier,
    playedKey: String = "",
    onPlayed: (String) -> Unit = {},
) {
    val motion = LocalMotion.current
    val outcome = PayOutcomes.of(phase)
    val payment = phase.payment
    val appLabel = phase.handlerLabel ?: payment?.knownHandlerPackage ?: stringResource(R.string.pay_source_app_generic)
    val status = payment?.status ?: phase.status
    val pending = outcome.kind == OutcomeKind.PENDING
    val realPending = pending && payment?.isUpiAppPayment == true
    val failed = outcome.kind == OutcomeKind.FAILED || outcome.kind == OutcomeKind.REPORTED_FAILURE

    // The moment plays once for this outcome. A rotation shows the finished frame, so nothing fires twice.
    // Decided once per outcome: marking it played must not stop the moment that is playing now. The record of what has
    // played is held by the screen, outside the cross-fade, so it survives a rotation.
    val momentKey = "${phase.paymentId}:$status"
    val play = remember(momentKey) { playedKey != momentKey && !motion.reduced }
    LaunchedEffect(momentKey) { onPlayed(momentKey) }

    val title = when (outcome.kind) {
        OutcomeKind.SUCCESS -> stringResource(R.string.pay_done_success)
        OutcomeKind.REPORTED_SUCCESS -> stringResource(R.string.pay_done_reported_success, appLabel)
        OutcomeKind.MARKED_PAID -> stringResource(R.string.pay_done_marked_paid)
        OutcomeKind.PENDING -> stringResource(R.string.pay_done_pending)
        OutcomeKind.FAILED -> stringResource(R.string.pay_done_failed)
        OutcomeKind.REPORTED_FAILURE -> stringResource(R.string.pay_done_reported_failure, appLabel)
        OutcomeKind.MARKED_NOT_PAID -> stringResource(R.string.pay_done_marked_not_paid)
        OutcomeKind.REFUND -> stringResource(if (status == PaymentStatus.REFUND_PENDING) R.string.pay_done_refund_pending else R.string.pay_done_refunded)
    }

    OutcomeFrame(
        modifier,
        actions = {
            if (realPending) {
                PrimaryButton(stringResource(R.string.pay_mark_paid), callbacks.onMarkPaid, modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_MARK_PAID))
                GhostButton(stringResource(R.string.pay_mark_not_paid), callbacks.onMarkNotPaid, tone = GhostTone.Neutral, modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_MARK_NOT_PAID))
            } else if (pending && mode != PaymentMode.UPI_APPS) {
                PrimaryButton(stringResource(R.string.pay_check_status), callbacks.onCheckStatus, loading = polling, modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_CHECK_STATUS))
            } else if (failed) {
                PrimaryButton(stringResource(R.string.pay_try_again), callbacks.onPayAgain, modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_AGAIN))
            } else {
                PrimaryButton(stringResource(R.string.pay_done), callbacks.onDone, modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_RESET))
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                if (payment != null) {
                    GhostButton(stringResource(R.string.pay_share), onShareRequest, outlined = false, leadingIcon = Icons.Outlined.Share, modifier = Modifier.testTag(PayFlowTestTags.PAY_SHARE))
                }
                if (phase.paymentId.isNotEmpty()) {
                    GhostButton(stringResource(R.string.pay_view_payment), { callbacks.onOpenPayment(phase.paymentId) }, outlined = false)
                }
                if (!failed) {
                    GhostButton(
                        stringResource(R.string.pay_again),
                        if (pending) onPayAgainRequest else callbacks.onPayAgain,
                        outlined = false,
                        tone = GhostTone.Neutral,
                        modifier = Modifier.testTag(PayFlowTestTags.PAY_AGAIN),
                    )
                }
                if (failed || pending) {
                    GhostButton(stringResource(R.string.pay_done), callbacks.onDone, outlined = false, tone = GhostTone.Neutral, modifier = Modifier.testTag(PayFlowTestTags.PAY_RESET))
                }
            }
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (outcome.kind) {
                OutcomeKind.SUCCESS -> {
                    if (play && outcome.celebration == Celebration.FULL) CoinBurst(Modifier.size(Sizes.heroAction * 2.2f))
                    SuccessGlyph(play)
                }
                OutcomeKind.REPORTED_SUCCESS -> if (outcome.celebration == Celebration.QUIET) SuccessGlyph(play) else MarkedGlyph(failed = false)
                OutcomeKind.MARKED_PAID -> MarkedGlyph(failed = false)
                OutcomeKind.MARKED_NOT_PAID -> MarkedGlyph(failed = true)
                OutcomeKind.PENDING -> PendingGlyph(live = polling)
                OutcomeKind.FAILED, OutcomeKind.REPORTED_FAILURE -> FailedGlyph(play)
                OutcomeKind.REFUND -> RefundGlyph(play)
            }
        }
        CenteredTitle(title)
        if (payment != null) {
            ResultAmount(payment.amountPaise, outcome.celebration == Celebration.FULL, play)
            PayeeLine(payment)
        }
        phase.reason?.let { reason ->
            Text(reason, style = PayFlowTheme.type.body, color = PayFlowTheme.colors.failed.strong, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (payment == null) {
            when {
                outcome.kind == OutcomeKind.SUCCESS -> CenteredHint(stringResource(R.string.pay_done_success_hint))
                pending -> CenteredHint(stringResource(R.string.pay_done_pending_hint))
            }
        }
        if (realPending) CenteredHint(stringResource(R.string.pay_pending_real_hint, appLabel))
        if (polling) PollingLine(stage)
        if (payment != null) {
            PrintingReceipt(ReceiptContent.from(payment, phase.handlerLabel), payment, phase.handlerLabel, play)
        }
    }
}

/**
 * A confirmed amount is the real number from its first frame and catches the sheen once; it never counts up from zero, so the
 * screen can't read "Payment successful ₹0.00" even for a frame. Anything else states the number and leaves it.
 */
@Composable
private fun ResultAmount(paise: Long, full: Boolean, play: Boolean) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    if (full) {
        // The sheen plays when this flips, i.e. once, right after the amount lands; a replayed outcome stays still.
        var landed by remember { mutableStateOf(!play) }
        LaunchedEffect(Unit) { landed = true }
        val sweep = rememberSheenSweep(landed)
        Box(
            Modifier
                .fillMaxWidth()
                .clipToBounds()
                .metallicSheen(colors.sheen, intensity = colors.sheenStrength, position = { sweep.position })
                .testTag(PayFlowTestTags.PAY_RESULT_AMOUNT),
            contentAlignment = Alignment.Center,
        ) {
            AmountOdometer(paise, style = type.display, color = colors.text, decimals = AmountDecimals.Always)
        }
    } else {
        AmountText(paise, Modifier.testTag(PayFlowTestTags.PAY_RESULT_AMOUNT), style = type.display, color = colors.text, decimals = AmountDecimals.Always)
    }
}

@Composable
private fun PayeeLine(payment: Payment) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val name = DisplayText.safe(payment.payeeName)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        PayFlowAvatar(name, size = Sizes.avatarSmall)
        Column(Modifier.weight(1f, fill = false)) {
            Text(name, style = type.bodyStrong, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(DisplayText.safe(payment.payeeVpa), style = type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A receipt's edge: square at the top, where it leaves the printer, and a row of round perforations along the bottom. */
class PerforatedEdgeShape(private val tooth: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { tooth.toPx() }
        val path = Path()
        path.moveTo(0f, 0f)
        path.lineTo(size.width, 0f)
        path.lineTo(size.width, size.height - r)
        val teeth = (size.width / (r * 4)).toInt().coerceAtLeast(1)
        val step = size.width / teeth
        var x = size.width
        repeat(teeth) {
            path.quadraticTo(x - step / 2, size.height + r, x - step, size.height - r)
            x -= step
        }
        path.close()
        return Outline.Generic(path)
    }
}

/**
 * The receipt prints: it slides out of a slot under the summary, on the Settle spring. It reads status and source from the
 * same [ReceiptContent] the shared text and image use. Printing is only a layer: the content is there from the first frame, for
 * TalkBack and for anyone who doesn't wait.
 */
@Composable
private fun PrintingReceipt(receipt: ReceiptContent, payment: Payment, handlerLabel: String?, play: Boolean) {
    val motion = LocalMotion.current
    val printed = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(play) {
        if (play) {
            // Waits on the frame clock rather than `delay`, so the wait is part of the animation and tests see it as one.
            holdForFrames(MotionDurations.Screen.toLong())
            printed.animateTo(1f, motion.spec(MotionToken.Settle))
        } else {
            printed.snapTo(1f)
        }
    }
    Box(Modifier.fillMaxWidth().padding(top = Spacing.xs).clipToBounds()) {
        ReceiptCard(
            receipt, payment, handlerLabel,
            Modifier.graphicsLayer { translationY = -(1f - printed.value.coerceIn(0f, 1f)) * size.height },
        )
    }
}

/**
 * The receipt itself: status pill, who established it, reference, UPI transaction, the app that handled it, time, note and the
 * sandbox line. Pay prints it; the payment detail shows it as is, so both say exactly the same thing.
 */
@Composable
fun ReceiptCard(
    receipt: ReceiptContent,
    payment: Payment,
    handlerLabel: String?,
    modifier: Modifier = Modifier,
    tag: String = PayFlowTestTags.PAY_RECEIPT,
    paper: Color = PayFlowTheme.colors.surface,
) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val shape = remember { PerforatedEdgeShape(Sizes.receiptPerforation) }
    val stroke = with(LocalDensity.current) { Hairline.width.toPx() }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .drawBehind {
                drawRect(paper)
                drawLine(colors.outline, Offset(0f, 0f), Offset(size.width, 0f), stroke)
            }
            .padding(horizontal = Spacing.md)
            .padding(top = Spacing.md, bottom = Spacing.lg)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.pay_receipt_title), style = type.bodyStrong, color = colors.text, modifier = Modifier.weight(1f))
            StatusPill(receipt.status.kind(), ReceiptContent.statusLabel(receipt.status))
        }
        Text(statusSourceLabel(payment, handlerLabel), style = type.label, color = colors.textSecondary)
        HairlineDivider()
        ReceiptRow(stringResource(R.string.detail_txn_ref), receipt.reference)
        receipt.upiTxnId?.let { ReceiptRow(stringResource(R.string.detail_upi_txn), it) }
        handlerLabel?.let { ReceiptRow(stringResource(R.string.detail_paid_via), it) }
        ReceiptRow(stringResource(R.string.pay_receipt_time), receipt.timeText())
        if (payment.note.isNotBlank()) ReceiptRow(stringResource(R.string.detail_note), DisplayText.safe(payment.note))
        if (!payment.isUpiAppPayment) Text(stringResource(R.string.pay_receipt_sandbox), style = type.label, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.xxs))
    }
}

fun PaymentStatus.kind(): StatusKind = when (this) {
    PaymentStatus.CREATED, PaymentStatus.PENDING -> StatusKind.Pending
    PaymentStatus.SUCCESS -> StatusKind.Success
    PaymentStatus.FAILED -> StatusKind.Failed
    PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED -> StatusKind.Refund
}

@Composable
private fun ReceiptRow(label: String, value: String) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(label, style = type.label, color = colors.textSecondary)
        Text(value, style = type.label, color = colors.text, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** Suspends for [millis] measured on the frame clock. */
private suspend fun holdForFrames(millis: Long) {
    val start = withFrameMillis { it }
    while (withFrameMillis { it } - start < millis) Unit
}
