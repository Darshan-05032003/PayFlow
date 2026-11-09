package com.darshan.payflow.ui.collect

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.CollectRequest
import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.domain.usecase.CollectActionResult
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.SandboxBadge
import com.darshan.payflow.ui.components.SecureWindow
import com.darshan.payflow.ui.components.relativeLabel
import com.darshan.payflow.ui.designsystem.components.AmountDecimals
import com.darshan.payflow.ui.designsystem.components.AmountText
import com.darshan.payflow.ui.designsystem.components.BrandLoader
import com.darshan.payflow.ui.designsystem.components.DisplayText
import com.darshan.payflow.ui.designsystem.components.EmptyState
import com.darshan.payflow.ui.designsystem.components.ErrorState
import com.darshan.payflow.ui.designsystem.components.GhostButton
import com.darshan.payflow.ui.designsystem.components.GhostTone
import com.darshan.payflow.ui.designsystem.components.HairlineDivider
import com.darshan.payflow.ui.designsystem.components.IllustrationKind
import com.darshan.payflow.ui.designsystem.components.PayFlowAvatar
import com.darshan.payflow.ui.designsystem.components.PayFlowBottomSheet
import com.darshan.payflow.ui.designsystem.components.PayFlowIconButton
import com.darshan.payflow.ui.designsystem.components.PayFlowListRow
import com.darshan.payflow.ui.designsystem.components.PayFlowSurface
import com.darshan.payflow.ui.designsystem.components.PayFlowToastHost
import com.darshan.payflow.ui.designsystem.components.PayFlowTopBar
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.components.SectionHeader
import com.darshan.payflow.ui.designsystem.components.SkeletonList
import com.darshan.payflow.ui.designsystem.components.StatusKind
import com.darshan.payflow.ui.designsystem.components.StatusPill
import com.darshan.payflow.ui.designsystem.components.SurfaceLevel
import com.darshan.payflow.ui.designsystem.components.ToastHostState
import com.darshan.payflow.ui.designsystem.components.rememberToastHostState
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.effects.ToastTone
import com.darshan.payflow.ui.designsystem.effects.perform
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.pay.SwipeToPay
import com.darshan.payflow.ui.theme.PayFlowTheme
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.delay

data class CollectCallbacks(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onApprove: (String) -> Unit = {},
    val onDecline: (String) -> Unit = {},
    val onOpenPayment: (String) -> Unit = {},
)

@Composable
fun CollectRoute(onBack: () -> Unit, onOpenPayment: (String) -> Unit, viewModel: CollectViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toasts = rememberToastHostState()
    val haptics = LocalHaptics.current
    val resources = LocalResources.current
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                CollectEvent.RefreshFailed -> toasts.show(resources.getString(R.string.collect_msg_refresh_failed), ToastTone.Failed)
                is CollectEvent.Answered -> {
                    val feedback = CollectFeedback.of(event.result)
                    feedback.haptic?.let(haptics::perform)
                    toasts.show(answerMessage(event.result, feedback, resources), feedback.tone())
                }
            }
        }
    }
    CollectScreen(
        state = state,
        callbacks = CollectCallbacks(onBack = onBack, onRefresh = viewModel::refresh, onApprove = viewModel::approve, onDecline = viewModel::decline, onOpenPayment = onOpenPayment),
        clock = viewModel.clock,
        memory = viewModel.headsUp,
        toasts = toasts,
    )
}

private fun CollectFeedback.tone(): ToastTone = when (this) {
    CollectFeedback.Paid -> ToastTone.Success
    CollectFeedback.Waiting -> ToastTone.Pending
    CollectFeedback.Declined -> ToastTone.Info
    CollectFeedback.Problem -> ToastTone.Failed
}

internal fun answerMessage(result: CollectActionResult, feedback: CollectFeedback, resources: android.content.res.Resources): String = resources.getString(
    when (result) {
        is CollectActionResult.Approved -> when (feedback) {
            CollectFeedback.Paid -> R.string.collect_msg_approved
            CollectFeedback.Problem -> R.string.collect_msg_payment_failed
            else -> R.string.collect_msg_waiting
        }
        is CollectActionResult.Declined -> R.string.collect_msg_declined
        is CollectActionResult.Expired -> R.string.collect_msg_expired
        is CollectActionResult.AlreadyAnswered -> R.string.collect_msg_already
        is CollectActionResult.Unavailable, CollectActionResult.NotFound -> R.string.collect_msg_unavailable
    },
)

@Composable
fun CollectStatus.label(): String = stringResource(
    when (this) {
        CollectStatus.PENDING -> R.string.collect_status_pending
        CollectStatus.APPROVED -> R.string.collect_status_approved
        CollectStatus.DECLINED -> R.string.collect_status_declined
        CollectStatus.EXPIRED -> R.string.collect_status_expired
    },
)

private fun CollectStatus.kind(): StatusKind = when (this) {
    CollectStatus.PENDING -> StatusKind.Pending
    CollectStatus.APPROVED -> StatusKind.Success
    CollectStatus.DECLINED -> StatusKind.Failed
    CollectStatus.EXPIRED -> StatusKind.Neutral
}

/** Ticks once a second while anything is counting down, on the same clock the answers are checked against. */
@Composable
private fun rememberNow(clock: Clock, ticking: Boolean): State<Instant> = produceState(clock.instant(), clock, ticking) {
    value = clock.instant()
    while (ticking) {
        delay(TICK_MS - value.toEpochMilli() % TICK_MS)
        value = clock.instant()
    }
}

private const val TICK_MS = 1_000L // token-audit: allow (clock tick for the countdown)

/**
 * Payment requests. Open ones arrive like heads-up cards, each with a ring counting down to its expiry, and are paid by
 * swiping, through the same slider and confirmation as Pay. Declining is one tap. Nothing here ever answers for the user, and
 * an expired request can't be paid: the slider locks the moment the ring runs out, and the use case checks again.
 */
@Composable
fun CollectScreen(
    state: CollectUiState,
    callbacks: CollectCallbacks,
    clock: Clock = Clock.systemUTC(),
    memory: HeadsUpMemory = remember { HeadsUpMemory() },
    toasts: ToastHostState = rememberToastHostState(),
) {
    SecureWindow()
    val colors = PayFlowTheme.colors
    val content = state as? CollectUiState.Content
    val now = rememberNow(clock, ticking = content?.requests?.any { it.request.status == CollectStatus.PENDING } == true)
    val split = content?.let { CollectSections.split(it.requests, now.value) }
    var confirmId by rememberSaveable { mutableStateOf<String?>(null) }
    val confirmItem = split?.open?.firstOrNull { it.request.id == confirmId }
    // The sheet stays while its answer is in flight, and goes the moment the request can no longer be paid.
    val sheetVisible = confirmItem != null && (confirmItem.busy || CollectApproval.canApprove(confirmItem, now.value))
    LaunchedEffect(sheetVisible) { if (!sheetVisible) confirmId = null }

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize().then(if (sheetVisible) Modifier.clearAndSetSemantics {} else Modifier)) {
            PayFlowTopBar(
                title = stringResource(R.string.collect_title),
                onBack = callbacks.onBack,
                actions = {
                    SandboxBadge(Modifier.padding(end = Spacing.xxs))
                    if (content?.refreshing == true) {
                        Box(Modifier.size(Sizes.touchTarget), contentAlignment = Alignment.Center) { BrandLoader(size = Sizes.loaderSmall) }
                    } else {
                        PayFlowIconButton(Icons.Outlined.Refresh, stringResource(R.string.collect_refresh), callbacks.onRefresh, tint = colors.text)
                    }
                },
            )
            when (state) {
                CollectUiState.Loading -> SkeletonList(Modifier.fillMaxWidth().testTag(PayFlowTestTags.LOADING), count = 3)
                CollectUiState.Empty -> EmptyState(
                    title = stringResource(R.string.collect_empty),
                    message = stringResource(R.string.collect_empty_hint),
                    illustration = IllustrationKind.NoRequests,
                    actionLabel = stringResource(R.string.collect_refresh),
                    onAction = callbacks.onRefresh,
                    modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.EMPTY),
                )
                is CollectUiState.Error -> ErrorState(
                    title = stringResource(R.string.error_generic),
                    retryLabel = stringResource(R.string.retry),
                    onRetry = callbacks.onRefresh,
                    modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.ERROR),
                )
                is CollectUiState.Content -> CollectList(split!!, now, memory, callbacks, onReview = { confirmId = it })
            }
        }
        PayFlowToastHost(toasts, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        ConfirmSheet(sheetVisible, confirmItem, now, callbacks, onDismiss = { confirmId = null })
    }
}

@Composable
private fun CollectList(split: CollectSplit, now: State<Instant>, memory: HeadsUpMemory, callbacks: CollectCallbacks, onReview: (String) -> Unit) {
    val colors = PayFlowTheme.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.xl)) {
        item(key = "intro", contentType = "intro") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.pending.strong, modifier = Modifier.size(Sizes.iconSmall))
                Text(stringResource(R.string.collect_intro), style = PayFlowTheme.type.label, color = colors.textSecondary)
            }
        }
        if (split.open.isNotEmpty()) {
            item(key = "openHeader", contentType = "header") {
                SectionHeader(stringResource(R.string.collect_open_header), Modifier.padding(horizontal = Spacing.screen))
            }
            itemsIndexed(split.open, key = { _, it -> "open:${it.request.id}" }, contentType = { _, _ -> "open" }) { index, item ->
                HeadsUp(item.request.id, index, memory) {
                    RequestCard(item, now, callbacks, onReview)
                }
            }
        }
        if (split.earlier.isNotEmpty()) {
            item(key = "earlierHeader", contentType = "header") {
                SectionHeader(stringResource(R.string.collect_earlier), Modifier.padding(horizontal = Spacing.screen).padding(top = Spacing.md))
            }
            itemsIndexed(split.earlier, key = { _, it -> "done:${it.request.id}" }, contentType = { _, _ -> "done" }) { index, item ->
                Column {
                    AnsweredRow(item.request, callbacks)
                    if (index < split.earlier.lastIndex) HairlineDivider(Modifier.padding(start = Spacing.screen + Sizes.avatar + Spacing.sm, end = Spacing.screen))
                }
            }
        }
    }
}

/**
 * A request arriving like a heads-up notification: it drops in from above on the Bouncy spring and settles. Once per
 * request per visit. Under reduced motion it only fades.
 */
@Composable
private fun HeadsUp(id: String, index: Int, memory: HeadsUpMemory, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    val play = remember(id) { memory.firstSight(id) }
    val arrive = remember(id) { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(id) {
        if (play) {
            val start = withFrameMillis { it }
            val wait = motion.staggerDelayMs(index).toLong()
            while (withFrameMillis { it } - start < wait) Unit
            arrive.animateTo(1f, motion.spec(MotionToken.Bounce))
        }
    }
    val reduced = motion.reduced
    Box(
        Modifier.graphicsLayer {
            val p = arrive.value
            alpha = p.coerceIn(0f, 1f)
            if (!reduced) {
                translationY = -(1f - p) * size.height * HEADS_UP_DROP
                val s = HEADS_UP_SCALE + (1f - HEADS_UP_SCALE) * p
                scaleX = s
                scaleY = s
            }
        },
    ) { content() }
}

private const val HEADS_UP_DROP = 0.6f
private const val HEADS_UP_SCALE = 0.94f

@Composable
private fun RequestCard(item: CollectItem, now: State<Instant>, callbacks: CollectCallbacks, onReview: (String) -> Unit) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val motion = LocalMotion.current
    val request = item.request
    val name = remember(request.payeeName) { DisplayText.safe(request.payeeName) }
    val vpa = remember(request.payeeVpa) { DisplayText.safe(request.payeeVpa) }
    val canApprove = CollectApproval.canApprove(item, now.value)
    val canDecline = CollectApproval.canDecline(item, now.value)
    val expired = request.isOverdue(now.value)
    val amount = Money.formatInr(request.amountPaise)

    // An answer that ended without paying sends the handle home, as on Pay.
    var refusals by remember { mutableIntStateOf(0) }
    val wasBusy = remember { booleanArrayOf(item.busy) }
    LaunchedEffect(item.busy, request.status) {
        if (CollectApproval.refused(wasBusy[0], item.busy, request.status)) refusals++
        wasBusy[0] = item.busy
    }

    PayFlowSurface(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.xs).testTag(PayFlowTestTags.collectCard(request.id)),
        level = SurfaceLevel.Raised,
        shape = Radii.largeShape,
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PayFlowAvatar(name)
                Column(Modifier.weight(1f)) {
                    Text(name, style = type.bodyStrong, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(vpa, style = type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CountdownRing(request, now)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(stringResource(R.string.collect_asks), style = type.label, color = colors.textSecondary)
                AmountText(request.amountPaise, style = type.display, color = colors.text, decimals = AmountDecimals.Always)
                if (request.note.isNotBlank()) {
                    Text("“${DisplayText.safe(request.note)}”", style = type.body, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            val label = when {
                expired -> stringResource(R.string.collect_swipe_expired)
                item.busy -> stringResource(R.string.collect_swipe_paying)
                else -> stringResource(R.string.collect_swipe, amount)
            }
            if (motion.reduced) {
                // Under reduced motion there is no slider to drag: paying is the review sheet's button, one step later.
                PrimaryButton(
                    stringResource(R.string.collect_review),
                    { onReview(request.id) },
                    enabled = canApprove,
                    loading = item.busy,
                    modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.collectPlain(request.id)),
                )
            } else {
                SwipeToPay(
                    label = label,
                    description = if (canApprove) stringResource(R.string.collect_swipe_description, amount, name, vpa) else label,
                    enabled = canApprove,
                    processing = item.busy,
                    refusals = refusals,
                    onCommit = { callbacks.onApprove(request.id) },
                    onPlainAction = { onReview(request.id) },
                    testTag = PayFlowTestTags.collectApprove(request.id),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val declineDescription = stringResource(R.string.collect_decline_description, name)
                GhostButton(
                    stringResource(R.string.collect_decline),
                    { callbacks.onDecline(request.id) },
                    enabled = canDecline,
                    tone = GhostTone.Danger,
                    modifier = Modifier.weight(1f).testTag(PayFlowTestTags.collectDecline(request.id)).semantics { contentDescription = declineDescription },
                )
                if (!motion.reduced) {
                    GhostButton(
                        stringResource(R.string.collect_use_button),
                        { onReview(request.id) },
                        enabled = canApprove,
                        outlined = false,
                        tone = GhostTone.Neutral,
                        modifier = Modifier.weight(1f).testTag(PayFlowTestTags.collectPlain(request.id)),
                    )
                }
            }
        }
    }
}

/**
 * What's left of the request's life. The sweep is read in the draw phase and eases between ticks; the label changes once a
 * second. Accent while there's time, the pending tone in the last two minutes, and an empty ring once it has expired.
 */
@Composable
private fun CountdownRing(request: CollectRequest, now: State<Instant>) {
    val colors = PayFlowTheme.colors
    val motion = LocalMotion.current
    val countdown = CollectCountdown.of(request, now.value)
    val sweep = remember(request.id) { Animatable(countdown.fraction) }
    LaunchedEffect(countdown.fraction) { sweep.animateTo(countdown.fraction, motion.spec(MotionToken.Small)) }
    val tone = when (countdown.urgency) {
        Urgency.Calm -> colors.accent
        Urgency.Soon -> colors.pending.strong
        Urgency.Expired -> colors.failed.strong
    }
    val track = colors.hairline
    val text = CollectCountdown.label(countdown.remaining)
    val description = if (countdown.urgency == Urgency.Expired) stringResource(R.string.collect_status_expired) else stringResource(R.string.collect_countdown, text)
    val currentTone by rememberUpdatedState(tone)
    Box(
        Modifier
            .size(Sizes.countdownRing)
            .drawBehind {
                val stroke = Sizes.ringStroke.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(currentTone, -90f, 360f * sweep.value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            .testTag(PayFlowTestTags.collectRing(request.id))
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PayFlowTheme.type.label, color = if (countdown.urgency == Urgency.Calm) colors.text else tone, maxLines = 1)
    }
}

@Composable
private fun AnsweredRow(request: CollectRequest, callbacks: CollectCallbacks) {
    val name = remember(request.payeeName) { DisplayText.safe(request.payeeName) }
    val status = request.status.label()
    val paymentId = request.paymentId
    PayFlowListRow(
        title = name,
        subtitle = "${request.updatedAt.relativeLabel()} · ${request.payeeVpa}",
        leading = { PayFlowAvatar(name) },
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                AmountText(request.amountPaise, color = PayFlowTheme.colors.textSecondary)
                StatusPill(request.status.kind(), status, animate = false)
            }
        },
        onClick = paymentId?.let { id -> { callbacks.onOpenPayment(id) } },
    )
}

/**
 * The plain way to pay a request: TalkBack's activate on the slider, "Pay with a button", and the reduced-motion button all
 * land here. It names who gets the money and says plainly that approving pays rather than receives.
 */
@Composable
private fun ConfirmSheet(visible: Boolean, item: CollectItem?, now: State<Instant>, callbacks: CollectCallbacks, onDismiss: () -> Unit) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    // Kept through the exit animation.
    val last = remember { mutableStateOf<CollectItem?>(null) }
    if (item != null) last.value = item
    val shown = last.value ?: return
    val request = shown.request
    val busy = item?.busy == true
    PayFlowBottomSheet(visible, onDismiss, Modifier.testTag(PayFlowTestTags.COLLECT_CONFIRM_SHEET), dismissible = !busy) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).padding(bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(stringResource(R.string.collect_confirm_title), style = type.label, color = colors.textSecondary, modifier = Modifier.semantics { heading() })
            AmountText(request.amountPaise, style = type.display, color = colors.text, decimals = AmountDecimals.Always)
            Text(stringResource(R.string.collect_confirm_to, DisplayText.safe(request.payeeName)), style = type.bodyStrong, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(DisplayText.safe(request.payeeVpa), style = type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.collect_confirm_warning), style = type.label, color = colors.pending.strong)
            Spacer(Modifier.height(Spacing.xxs))
            PrimaryButton(
                stringResource(R.string.collect_confirm_pay, Money.formatInr(request.amountPaise)),
                { callbacks.onApprove(request.id) },
                loading = busy,
                enabled = visible && item != null && CollectApproval.canApprove(item, now.value),
                modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.COLLECT_CONFIRM_PAY),
            )
            GhostButton(stringResource(R.string.collect_confirm_cancel), onDismiss, outlined = false, tone = GhostTone.Neutral, enabled = !busy, modifier = Modifier.fillMaxWidth())
        }
    }
}
