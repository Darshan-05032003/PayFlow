package com.darshan.payflow.ui.mandates

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.ExecutionCheck
import com.darshan.payflow.domain.model.Mandate
import com.darshan.payflow.domain.model.MandateAction
import com.darshan.payflow.domain.model.MandateFrequency
import com.darshan.payflow.domain.model.MandateStatus
import com.darshan.payflow.domain.model.MandateValidation
import com.darshan.payflow.domain.usecase.ExecuteMandateResult
import com.darshan.payflow.domain.usecase.UpdateMandateResult
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.PaymentFeedRow
import com.darshan.payflow.ui.components.SandboxBadge
import com.darshan.payflow.ui.components.SecureWindow
import com.darshan.payflow.ui.components.relativeLabel
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
import com.darshan.payflow.ui.designsystem.components.PayFlowChoiceChip
import com.darshan.payflow.ui.designsystem.components.PayFlowSurface
import com.darshan.payflow.ui.designsystem.components.PayFlowTextField
import com.darshan.payflow.ui.designsystem.components.PayFlowToastHost
import com.darshan.payflow.ui.designsystem.components.PayFlowTopBar
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.components.SectionHeader
import com.darshan.payflow.ui.designsystem.components.SkeletonList
import com.darshan.payflow.ui.designsystem.components.StatusKind
import com.darshan.payflow.ui.designsystem.components.StatusPill
import com.darshan.payflow.ui.designsystem.components.SurfaceLevel
import com.darshan.payflow.ui.designsystem.components.TactileButton
import com.darshan.payflow.ui.designsystem.components.ToastHostState
import com.darshan.payflow.ui.designsystem.components.rememberToastHostState
import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.effects.ToastTone
import com.darshan.payflow.ui.designsystem.effects.perform
import com.darshan.payflow.ui.designsystem.modifiers.pressScale
import com.darshan.payflow.ui.designsystem.modifiers.rememberPressFeedback
import com.darshan.payflow.ui.designsystem.modifiers.trackPress
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.pay.message
import com.darshan.payflow.ui.theme.PayFlowTheme
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ shared labels

@Composable
fun MandateFrequency.label(): String = stringResource(
    when (this) {
        MandateFrequency.DAILY -> R.string.mandate_freq_daily
        MandateFrequency.WEEKLY -> R.string.mandate_freq_weekly
        MandateFrequency.MONTHLY -> R.string.mandate_freq_monthly
        MandateFrequency.QUARTERLY -> R.string.mandate_freq_quarterly
        MandateFrequency.YEARLY -> R.string.mandate_freq_yearly
        MandateFrequency.AS_PRESENTED -> R.string.mandate_freq_as_presented
    },
)

@Composable
fun MandateStatus.label(): String = stringResource(
    when (this) {
        MandateStatus.ACTIVE -> R.string.mandate_status_active
        MandateStatus.PAUSED -> R.string.mandate_status_paused
        MandateStatus.REVOKED -> R.string.mandate_status_revoked
        MandateStatus.EXPIRED -> R.string.mandate_status_expired
    },
)

@Composable
fun MandateAction.label(): String = stringResource(
    when (this) {
        MandateAction.PAUSE -> R.string.mandate_action_pause
        MandateAction.RESUME -> R.string.mandate_action_resume
        MandateAction.REVOKE -> R.string.mandate_action_revoke
        MandateAction.EXPIRE -> R.string.mandate_status_expired
    },
)

@Composable
fun MandateValidation.message(): String = stringResource(
    when (this) {
        MandateValidation.Ok -> R.string.mandate_rule_ok
        MandateValidation.InvalidAmount -> R.string.mandate_rule_amount
        MandateValidation.StartInPast -> R.string.mandate_rule_start
        MandateValidation.EndBeforeStart -> R.string.mandate_rule_end
        MandateValidation.TooLong -> R.string.mandate_rule_too_long
        MandateValidation.InvalidPayee -> R.string.mandate_rule_payee
    },
)

@Composable
fun ExecutionCheck.message(): String = stringResource(
    when (this) {
        ExecutionCheck.Allowed -> R.string.mandate_exec_ok
        ExecutionCheck.InvalidAmount -> R.string.error_invalid_amount
        ExecutionCheck.AmountExceedsMax -> R.string.mandate_exec_exceeds
        ExecutionCheck.NotActive -> R.string.mandate_exec_not_active
        ExecutionCheck.OutsideValidity -> R.string.mandate_exec_outside
        ExecutionCheck.AlreadyExecutedThisPeriod -> R.string.mandate_exec_period
    },
)

private fun MandateStatus.kind(): StatusKind = when (this) {
    MandateStatus.ACTIVE -> StatusKind.Success
    MandateStatus.PAUSED -> StatusKind.Pending
    MandateStatus.REVOKED -> StatusKind.Failed
    MandateStatus.EXPIRED -> StatusKind.Neutral
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/** Toast words and haptic for a pause, resume or revoke result, shared by the stack and the detail. */
private fun actionFeedback(result: UpdateMandateResult, resources: android.content.res.Resources): Pair<String, ToastTone> = when (result) {
    is UpdateMandateResult.Updated -> resources.getString(R.string.mandate_msg_updated) to ToastTone.Success
    is UpdateMandateResult.IllegalTransition -> resources.getString(R.string.mandate_msg_illegal) to ToastTone.Failed
    is UpdateMandateResult.Unavailable, UpdateMandateResult.NotFound -> resources.getString(R.string.collect_msg_unavailable) to ToastTone.Failed
}

// ------------------------------------------------------------------ list

data class MandatesCallbacks(
    val onBack: () -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onRetry: () -> Unit = {},
    /** Pause or resume from a card. */
    val onAction: (String, MandateAction) -> Unit = { _, _ -> },
)

@Composable
fun MandatesRoute(onBack: () -> Unit, onOpen: (String) -> Unit, onCreate: () -> Unit, viewModel: MandatesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toasts = rememberToastHostState()
    val haptics = LocalHaptics.current
    val resources = LocalResources.current
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is MandateDetailEvent.ActionResult) {
                val (text, tone) = actionFeedback(event.result, resources)
                haptics.perform(if (event.result is UpdateMandateResult.Updated) HapticEvent.Light else HapticEvent.Reject)
                toasts.show(text, tone)
            }
        }
    }
    MandatesScreen(state, MandatesCallbacks(onBack, onOpen, onCreate, viewModel::retry, viewModel::act), zone = viewModel.zone, toasts = toasts)
}

/**
 * AutoPay: the mandates as a stack of cards, swiped through one at a time, each with a pause or resume control whose icon
 * morphs between the two. Under the stack, the front card's expected debits on a timeline. One primary action: a new mandate.
 */
@Composable
fun MandatesScreen(
    state: MandatesUiState,
    callbacks: MandatesCallbacks,
    zone: ZoneId = ZoneId.systemDefault(),
    toasts: ToastHostState = rememberToastHostState(),
) {
    val colors = PayFlowTheme.colors
    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            PayFlowTopBar(
                stringResource(R.string.mandates_title),
                onBack = callbacks.onBack,
                actions = { SandboxBadge(Modifier.padding(end = Spacing.sm)) },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state) {
                    MandatesUiState.Loading -> SkeletonList(Modifier.fillMaxWidth().testTag(PayFlowTestTags.LOADING), count = 3)
                    MandatesUiState.Empty -> EmptyState(
                        title = stringResource(R.string.mandates_empty),
                        message = stringResource(R.string.mandates_empty_hint),
                        illustration = IllustrationKind.NoMandates,
                        modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.EMPTY),
                    )
                    is MandatesUiState.Error -> ErrorState(
                        title = stringResource(R.string.error_generic),
                        retryLabel = stringResource(R.string.retry),
                        onRetry = callbacks.onRetry,
                        modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.ERROR),
                    )
                    is MandatesUiState.Content -> MandatesContent(state, callbacks, zone)
                }
            }
            if (state !is MandatesUiState.Error) {
                PrimaryButton(
                    stringResource(R.string.mandate_create),
                    callbacks.onCreate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Spacing.screen, vertical = Spacing.sm)
                        .testTag(PayFlowTestTags.MANDATE_CREATE),
                )
            }
        }
        PayFlowToastHost(toasts, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Sizes.buttonHeight + Spacing.md))
    }
}

@Composable
private fun MandatesContent(state: MandatesUiState.Content, callbacks: MandatesCallbacks, zone: ZoneId) {
    val today = state.today ?: LocalDate.now(zone)
    var front by rememberSaveable { mutableIntStateOf(0) }
    val mandates = state.mandates
    val current = mandates.getOrNull(front.coerceIn(0, (mandates.size - 1).coerceAtLeast(0)))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.mandates_intro),
            style = PayFlowTheme.type.label,
            color = PayFlowTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = Spacing.screen).padding(bottom = Spacing.md),
        )
        MandateStack(mandates, state.busy, today, zone, front, onFront = { front = it }, callbacks)
        StackDots(mandates.size, front)
        if (current != null) {
            val motion = LocalMotion.current
            AnimatedContent(current, transitionSpec = { motion.fadeThrough() }, label = "timeline", contentKey = { it.id }) { mandate ->
                UpcomingTimeline(mandate, today, zone, Modifier.padding(top = Spacing.lg))
            }
        }
        Spacer(Modifier.height(Spacing.xl))
    }
}

/**
 * The stack. The front card follows the finger and tilts a little; past a third of its width, or on a fling, it flies off
 * and the next card rises from behind. Rules in [CardStackHolder]; the offset is read in the layer phase only. TalkBack gets
 * "next" and "previous" actions, and the cards behind are hidden from it.
 */
@Composable
private fun MandateStack(
    mandates: List<Mandate>,
    busy: Set<String>,
    today: LocalDate,
    zone: ZoneId,
    front: Int,
    onFront: (Int) -> Unit,
    callbacks: MandatesCallbacks,
) {
    val motion = LocalMotion.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val direction = if (rtl) -1f else 1f
    val holder = remember { CardStackHolder(mandates.size, 1f) }
    var offset by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val rise = remember { Animatable(1f) }
    val peekPx = with(LocalDensity.current) { Sizes.mandateStackPeek.toPx() }
    LaunchedEffect(mandates.size, front) {
        holder.resize(mandates.size)
        holder.goTo(front)
        if (holder.index != front) onFront(holder.index)
    }

    /** [before] is the card that was in front when the gesture or action began. */
    fun moveTo(target: Int, velocity: Float, before: Int) {
        scope.launch {
            if (target != before) {
                val out = if (target > before) -widthPx * FLY_OUT else widthPx * FLY_OUT
                animate(offset, out, velocity, motion.spec(MotionToken.Snap)) { v, _ -> holder.snapOffset(v); offset = v }
                holder.goTo(target)
                onFront(target)
                rise.snapTo(0f)
                offset = 0f
                haptics.tick()
                rise.animateTo(1f, motion.spec(MotionToken.Bounce))
            } else {
                animate(offset, 0f, velocity, motion.spec(MotionToken.Release)) { v, _ -> holder.snapOffset(v); offset = v }
            }
        }
    }

    val next = stringResource(R.string.mandate_stack_next)
    val previous = stringResource(R.string.mandate_stack_previous)
    val drag = rememberDraggableState { delta ->
        holder.dragBy(delta * direction)
        offset = holder.offset
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen)
            .padding(bottom = Sizes.mandateStackPeek * 2)
            // Every card takes the tallest card's height, so a taller card behind never shows its text in the peek
            // (found at 1.3x text). Cards are plain rows and columns, so intrinsic measurement is cheap here.
            .height(IntrinsicSize.Max)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f); holder.setWidth(widthPx) }
            .testTag(PayFlowTestTags.MANDATE_STACK)
            .semantics {
                customActions = buildList {
                    if (front < mandates.lastIndex) add(CustomAccessibilityAction(next) { moveTo(front + 1, 0f, front); true })
                    if (front > 0) add(CustomAccessibilityAction(previous) { moveTo(front - 1, 0f, front); true })
                }
            },
    ) {
        for (depth in STACK_DEPTH downTo 0) {
            val i = front + depth
            if (i > mandates.lastIndex) continue
            val mandate = mandates[i]
            key(mandate.id) {
                val isFront = depth == 0
                MandateCard(
                    mandate = mandate,
                    busy = mandate.id in busy,
                    today = today,
                    zone = zone,
                    onOpen = { callbacks.onOpen(mandate.id) },
                    onQuick = { action -> callbacks.onAction(mandate.id, action) },
                    quickTag = PayFlowTestTags.mandateQuick(mandate.id),
                    modifier = Modifier
                        .fillMaxHeight()
                        .zIndex((STACK_DEPTH - depth).toFloat())
                        .graphicsLayer {
                            val leaving = (abs(offset) / widthPx).coerceIn(0f, 1f)
                            // How far back this card sits: the front card rises from 1 to 0, the ones behind move up as it leaves.
                            val d = if (isFront) 1f - rise.value else (depth - leaving).coerceAtLeast(0f)
                            val scale = StackGeometry.scale(d)
                            scaleX = scale
                            scaleY = scale
                            translationY = StackGeometry.translationY(d, size.height, peekPx)
                            alpha = if (isFront) 1f else lerp(1f, STACK_BACK_ALPHA, d / STACK_DEPTH)
                            if (isFront) {
                                translationX = offset * direction
                                rotationZ = offset / widthPx * STACK_TILT * direction
                            }
                        }
                        .then(
                            if (isFront) {
                                Modifier.draggable(
                                    drag,
                                    Orientation.Horizontal,
                                    onDragStopped = { v ->
                                        val before = holder.index
                                        moveTo(holder.release(v * direction), v * direction, before)
                                    },
                                )
                            } else {
                                Modifier.clearAndSetSemantics {}
                            },
                        ),
                )
            }
        }
    }
}

private const val STACK_DEPTH = 2
private const val STACK_BACK_ALPHA = 0.55f
private const val STACK_TILT = 6f
private const val FLY_OUT = 1.2f

@Composable
private fun StackDots(count: Int, front: Int) {
    if (count < 2) return
    val colors = PayFlowTheme.colors
    val position = stringResource(R.string.mandate_stack_position, front + 1, count)
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.xs).clearAndSetSemantics { contentDescription = position },
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
    ) {
        repeat(count.coerceAtMost(MAX_DOTS)) { i ->
            val active = i == front.coerceAtMost(MAX_DOTS - 1)
            Spacer(Modifier.size(Sizes.timelineDot).drawBehind { drawCircle(if (active) colors.accent else colors.outline, radius = size.minDimension / (if (active) 2f else 3f)) })
        }
    }
}

private const val MAX_DOTS = 8

/**
 * One mandate as a graphite card: who can debit, up to how much and how often, the next expected debit, and the pause or
 * resume control. The card opens the mandate; the control acts on it through the data layer and shows what it reports.
 */
@Composable
private fun MandateCard(
    mandate: Mandate,
    busy: Boolean,
    today: LocalDate,
    zone: ZoneId,
    onOpen: (() -> Unit)?,
    onQuick: (MandateAction) -> Unit,
    quickTag: String,
    modifier: Modifier = Modifier,
) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val name = remember(mandate.payeeName) { DisplayText.safe(mandate.payeeName) }
    val summary = stringResource(R.string.mandate_summary, Money.formatInr(mandate.maxAmountPaise), mandate.frequency.label())
    val next = when (val n = MandateSchedule.nextDebit(mandate, today, zone)) {
        is NextDebit.On -> stringResource(R.string.mandate_next_on, if (n.date == today) stringResource(R.string.history_day_today).lowercase() else n.date.format(DAY))
        NextDebit.Paused -> stringResource(R.string.mandate_next_paused)
        NextDebit.WhenPresented -> stringResource(R.string.mandate_next_presented)
        NextDebit.Ended -> stringResource(R.string.mandate_next_ended)
    }
    val status = mandate.status.label()
    PayFlowSurface(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.mandateCard)
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .testTag(PayFlowTestTags.mandateCard(mandate.id)),
        level = SurfaceLevel.Hero,
        shape = Radii.largeShape,
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.semantics(mergeDescendants = true) {},
            ) {
                PayFlowAvatar(name)
                Column(Modifier.weight(1f)) {
                    Text(name, style = type.bodyStrong, color = colors.onHero, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(DisplayText.safe(mandate.payeeVpa), style = type.label, color = colors.onHero.copy(alpha = ON_HERO_SECONDARY), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusPill(mandate.status.kind(), status, animate = false)
            }
            Text(summary, style = type.amount, color = colors.onHero, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(stringResource(R.string.mandate_next_label), style = type.label, color = colors.onHero.copy(alpha = ON_HERO_SECONDARY))
                    Text(next, style = type.bodyStrong, color = colors.onHero)
                }
                MandateQuickAction.of(mandate.status)?.let { action -> PauseResumeButton(mandate, action, busy, name, onQuick, quickTag) }
            }
        }
    }
}

private const val ON_HERO_SECONDARY = 0.72f

/**
 * Pause and resume in one control. The icon shows what a tap will do, two bars or a triangle, and morphs between them when
 * the stored status changes, never before. While the request runs it holds and shows the loader.
 */
@Composable
private fun PauseResumeButton(mandate: Mandate, action: MandateAction, busy: Boolean, name: String, onQuick: (MandateAction) -> Unit, tag: String) {
    val colors = PayFlowTheme.colors
    val motion = LocalMotion.current
    val haptics = LocalHaptics.current
    val press = rememberPressFeedback()
    val morph = remember { Animatable(MandateQuickAction.morphTarget(mandate.status)) }
    LaunchedEffect(mandate.status) { morph.animateTo(MandateQuickAction.morphTarget(mandate.status), motion.spec(MotionToken.Bounce)) }
    val description = stringResource(if (action == MandateAction.PAUSE) R.string.mandate_pause_for else R.string.mandate_resume_for, name)
    val ink = colors.onHero
    Box(
        Modifier
            .size(Sizes.touchTarget)
            .pressScale(press)
            .trackPress(press, enabled = !busy)
            .clickable(enabled = !busy, role = Role.Button) {
                haptics.light()
                onQuick(action)
            }
            .testTag(tag)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Spacer(Modifier.size(Sizes.touchTarget).drawBehind { drawCircle(ink, alpha = 0.14f) })
        if (busy) {
            BrandLoader(size = Sizes.loaderSmall, color = ink, trackColor = ink.copy(alpha = 0.2f))
        } else {
            Spacer(
                Modifier.size(Sizes.iconSmall).drawWithCache {
                    val left = Path()
                    val right = Path()
                    onDrawBehind {
                        PauseResumeGlyph.build(morph.value.coerceIn(0f, 1f), size.width, size.height, left, right)
                        drawPath(left, ink)
                        drawPath(right, ink)
                    }
                },
            )
        }
    }
}

/**
 * Two bars that become a triangle. Each bar's four corners slide to one half of the triangle, so the morph is one shape
 * changing rather than two cross-fading. Paths are reused between frames.
 */
object PauseResumeGlyph {
    fun build(p: Float, w: Float, h: Float, left: Path, right: Path) {
        val bar = w * 0.34f
        val mid = w / 2f
        // Pause: two bars. Play: a triangle pointing right, split at its middle.
        quad(left, p, Offset(0f, 0f), Offset(bar, 0f), Offset(bar, h), Offset(0f, h), Offset(0f, 0f), Offset(mid, h / 4f), Offset(mid, h * 3f / 4f), Offset(0f, h))
        quad(right, p, Offset(w - bar, 0f), Offset(w, 0f), Offset(w, h), Offset(w - bar, h), Offset(mid, h / 4f), Offset(w, h / 2f), Offset(w, h / 2f), Offset(mid, h * 3f / 4f))
    }

    private fun quad(path: Path, p: Float, a0: Offset, b0: Offset, c0: Offset, d0: Offset, a1: Offset, b1: Offset, c1: Offset, d1: Offset) {
        path.reset()
        path.moveTo(lerp(a0.x, a1.x, p), lerp(a0.y, a1.y, p))
        path.lineTo(lerp(b0.x, b1.x, p), lerp(b0.y, b1.y, p))
        path.lineTo(lerp(c0.x, c1.x, p), lerp(c0.y, c1.y, p))
        path.lineTo(lerp(d0.x, d1.x, p), lerp(d0.y, d1.y, p))
        path.close()
    }
}

/**
 * The front card's expected debits. Says "expected", because a mandate allows a debit and the merchant decides when; says
 * plainly when nothing will come (paused, ended, billed when presented); and says the sandbox has no merchant.
 */
@Composable
private fun UpcomingTimeline(mandate: Mandate, today: LocalDate, zone: ZoneId, modifier: Modifier = Modifier) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val dates = remember(mandate, today, zone) { MandateSchedule.upcoming(mandate, today, zone) }
    val limit = stringResource(R.string.mandate_up_to, Money.formatInr(mandate.maxAmountPaise))
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.screen).testTag(PayFlowTestTags.MANDATE_TIMELINE)) {
        SectionHeader(stringResource(R.string.mandate_timeline_title))
        val reason = when (MandateSchedule.nextDebit(mandate, today, zone)) {
            NextDebit.Paused -> stringResource(R.string.mandate_timeline_paused)
            NextDebit.WhenPresented -> stringResource(R.string.mandate_timeline_presented, limit)
            NextDebit.Ended -> stringResource(R.string.mandate_timeline_ended)
            is NextDebit.On -> null
        }
        if (reason != null) {
            Text(reason, style = type.body, color = colors.textSecondary, modifier = Modifier.padding(vertical = Spacing.xs))
        } else {
            dates.forEachIndexed { i, date -> TimelineRow(date, today, limit, first = i == 0, last = i == dates.lastIndex) }
        }
        Row(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Icon(Icons.Outlined.Science, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(Sizes.iconSmall))
            Text(stringResource(R.string.mandate_timeline_caption), style = type.label, color = colors.textSecondary)
        }
    }
}

@Composable
private fun TimelineRow(date: LocalDate, today: LocalDate, limit: String, first: Boolean, last: Boolean) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val label = if (date == today) stringResource(R.string.history_day_today) else date.format(DAY)
    Row(Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        val line = colors.outline
        val dot = if (first) colors.accent else colors.outline
        Spacer(
            Modifier
                .width(Sizes.icon)
                .fillMaxHeight()
                .heightIn(min = Sizes.touchTarget)
                .drawBehind {
                    val x = size.width / 2
                    val stroke = Sizes.ringStroke.toPx() / 2
                    if (!first) drawLine(line, Offset(x, 0f), Offset(x, size.height / 2), stroke)
                    if (!last) drawLine(line, Offset(x, size.height / 2), Offset(x, size.height), stroke)
                    drawCircle(dot, radius = Sizes.timelineDot.toPx() / 2, center = Offset(x, size.height / 2))
                },
        )
        Text(label, style = if (first) type.bodyStrong else type.body, color = colors.text, modifier = Modifier.weight(1f).padding(start = Spacing.sm))
        Text(limit, style = type.label, color = colors.textSecondary)
    }
}

// ------------------------------------------------------------------ create

data class CreateMandateCallbacks(
    val onBack: () -> Unit = {},
    val onVpaChange: (String) -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onAmountChange: (String) -> Unit = {},
    val onFrequencyChange: (MandateFrequency) -> Unit = {},
    val onValidityMonthsChange: (Int) -> Unit = {},
    val onNoteChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
)

@Composable
fun CreateMandateRoute(onBack: () -> Unit, onCreated: (String) -> Unit, viewModel: CreateMandateViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toasts = rememberToastHostState()
    val unavailable = stringResource(R.string.collect_msg_unavailable)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is CreateMandateEvent.Created -> onCreated(event.mandateId)
                CreateMandateEvent.Unavailable -> toasts.show(unavailable, ToastTone.Failed)
            }
        }
    }
    CreateMandateScreen(
        state,
        CreateMandateCallbacks(onBack, viewModel::onVpaChange, viewModel::onNameChange, viewModel::onAmountChange, viewModel::onFrequencyChange, viewModel::onValidityMonthsChange, viewModel::onNoteChange, viewModel::submit),
        toasts,
    )
}

/** A short form: who, up to how much, how often, for how long. The provider checks the payee; the rules check the rest. */
@Composable
fun CreateMandateScreen(state: CreateMandateUiState, callbacks: CreateMandateCallbacks, toasts: ToastHostState = rememberToastHostState()) {
    SecureWindow()
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val enabled = !state.submitting
    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            PayFlowTopBar(stringResource(R.string.mandate_create), onBack = callbacks.onBack, actions = { SandboxBadge(Modifier.padding(end = Spacing.sm)) })
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                FormSection(stringResource(R.string.mandate_section_merchant))
                PayFlowTextField(
                    state.vpa, callbacks.onVpaChange, stringResource(R.string.pay_vpa),
                    placeholder = stringResource(R.string.pay_vpa_placeholder), error = state.errors.vpa?.message(), enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), fieldTag = PayFlowTestTags.MANDATE_VPA,
                )
                PayFlowTextField(state.name, callbacks.onNameChange, stringResource(R.string.pay_payee_name), error = state.errors.name?.message(), enabled = enabled, fieldTag = PayFlowTestTags.MANDATE_NAME)
                FormSection(stringResource(R.string.mandate_section_rules))
                PayFlowTextField(
                    state.amountText, callbacks.onAmountChange, stringResource(R.string.mandate_max_amount),
                    helper = stringResource(R.string.mandate_max_amount_hint), error = state.errors.amount?.message(), enabled = enabled,
                    leading = { Text("₹", style = type.body, color = colors.textSecondary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), fieldTag = PayFlowTestTags.MANDATE_AMOUNT,
                )
                ChipGroup(stringResource(R.string.mandate_frequency), MandateFrequency.entries, state.frequency, { it.label() }, { PayFlowTestTags.mandateFrequency(it) }, callbacks.onFrequencyChange, enabled)
                ChipGroup(stringResource(R.string.mandate_validity), VALIDITY_MONTHS, state.validityMonths, { pluralStringResource(R.plurals.mandate_months, it, it) }, { PayFlowTestTags.mandateValidity(it) }, callbacks.onValidityMonthsChange, enabled)
                PayFlowTextField(state.note, callbacks.onNoteChange, stringResource(R.string.pay_note), enabled = enabled)
                state.ruleError?.let { Text(it.message(), style = type.body, color = colors.failed.strong) }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Icon(Icons.Outlined.Science, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(Sizes.iconSmall))
                    Text(stringResource(R.string.mandate_create_sandbox), style = type.label, color = colors.textSecondary)
                }
                Spacer(Modifier.height(Spacing.xs))
            }
            PrimaryButton(
                stringResource(R.string.mandate_create_submit),
                callbacks.onSubmit,
                loading = state.submitting,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.screen, vertical = Spacing.sm).testTag(PayFlowTestTags.MANDATE_SUBMIT),
            )
        }
        PayFlowToastHost(toasts, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Sizes.buttonHeight + Spacing.md))
    }
}

private val VALIDITY_MONTHS = listOf(1, 3, 6, 12, 24)

@Composable
private fun FormSection(title: String) {
    Text(title, style = PayFlowTheme.type.labelStrong, color = PayFlowTheme.colors.textSecondary, modifier = Modifier.padding(top = Spacing.xs).semantics { heading() })
}

@Composable
private fun <T> ChipGroup(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, tag: (T) -> String, onSelect: (T) -> Unit, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(title, style = PayFlowTheme.type.label, color = PayFlowTheme.colors.textSecondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), contentPadding = PaddingValues(end = Spacing.screen)) {
            items(options, key = { it.toString() }) { option ->
                PayFlowChoiceChip(label(option), option == selected, { onSelect(option) }, Modifier.testTag(tag(option)), enabled = enabled)
            }
        }
    }
}

// ------------------------------------------------------------------ detail

data class MandateDetailCallbacks(
    val onBack: () -> Unit = {},
    val onAction: (MandateAction) -> Unit = {},
    val onDebitAmountChange: (String) -> Unit = {},
    val onSimulateDebit: (String) -> Unit = {},
    val onOpenPayment: (String) -> Unit = {},
)

@Composable
fun MandateDetailRoute(onBack: () -> Unit, onOpenPayment: (String) -> Unit, viewModel: MandateDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hidden by viewModel.amountsHidden.collectAsStateWithLifecycle()
    val toasts = rememberToastHostState()
    val haptics = LocalHaptics.current
    val resources = LocalResources.current
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is MandateDetailEvent.ActionResult -> {
                    val (text, tone) = actionFeedback(event.result, resources)
                    haptics.perform(if (event.result is UpdateMandateResult.Updated) HapticEvent.Light else HapticEvent.Reject)
                    toasts.show(text, tone)
                }
                is MandateDetailEvent.ExecutionResult -> when (event.result) {
                    is ExecuteMandateResult.Executed -> toasts.show(resources.getString(R.string.mandate_msg_executed), ToastTone.Success)
                    is ExecuteMandateResult.Rejected -> {
                        haptics.reject()
                        toasts.show(resources.getString(R.string.mandate_msg_rejected), ToastTone.Failed)
                    }
                    is ExecuteMandateResult.Unavailable, ExecuteMandateResult.NotFound -> toasts.show(resources.getString(R.string.collect_msg_unavailable), ToastTone.Failed)
                }
            }
        }
    }
    MandateDetailScreen(
        state,
        MandateDetailCallbacks(onBack, viewModel::act, viewModel::onDebitAmountChange, viewModel::simulateDebit, onOpenPayment),
        toasts,
        amountsHidden = hidden,
        zone = viewModel.zone,
    )
}

/**
 * One mandate: its card with the pause or resume control, the expected debits, the terms, the sandbox's simulated merchant
 * debit, and the debits taken. Revoking can't be undone, so it asks first.
 */
@Composable
fun MandateDetailScreen(
    state: MandateDetailUiState,
    callbacks: MandateDetailCallbacks,
    toasts: ToastHostState = rememberToastHostState(),
    amountsHidden: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    SecureWindow()
    val colors = PayFlowTheme.colors
    var revokeOpen by rememberSaveable { mutableStateOf(false) }
    val content = state as? MandateDetailUiState.Content
    val revokeVisible = revokeOpen && content != null && MandateAction.REVOKE in content.availableActions
    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize().then(if (revokeVisible) Modifier.clearAndSetSemantics {} else Modifier)) {
            PayFlowTopBar(stringResource(R.string.mandate_detail_title), onBack = callbacks.onBack, actions = { SandboxBadge(Modifier.padding(end = Spacing.sm)) })
            when (state) {
                MandateDetailUiState.Loading -> SkeletonList(Modifier.fillMaxWidth().testTag(PayFlowTestTags.LOADING), count = 3)
                MandateDetailUiState.NotFound -> EmptyState(
                    title = stringResource(R.string.mandate_not_found),
                    illustration = IllustrationKind.NoResults,
                    modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.EMPTY),
                )
                is MandateDetailUiState.Error -> ErrorState(
                    title = stringResource(R.string.error_generic),
                    retryLabel = stringResource(R.string.back),
                    onRetry = callbacks.onBack,
                    modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.ERROR),
                )
                is MandateDetailUiState.Content -> DetailContent(state, callbacks, amountsHidden, zone, onRevoke = { revokeOpen = true })
            }
        }
        PayFlowToastHost(toasts, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        RevokeSheet(revokeVisible, content?.mandate, content?.busy == true, onDismiss = { revokeOpen = false }) {
            revokeOpen = false
            callbacks.onAction(MandateAction.REVOKE)
        }
    }
}

@Composable
private fun DetailContent(state: MandateDetailUiState.Content, callbacks: MandateDetailCallbacks, amountsHidden: Boolean, zone: ZoneId, onRevoke: () -> Unit) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val m = state.mandate
    val today = state.today ?: remember(zone) { LocalDate.now(zone) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        MandateCard(
            mandate = m,
            busy = state.busy,
            today = today,
            zone = zone,
            onOpen = null,
            onQuick = callbacks.onAction,
            quickTag = MandateQuickAction.of(m.status)?.let(PayFlowTestTags::mandateAction) ?: "",
            modifier = Modifier.padding(horizontal = Spacing.screen),
        )
        UpcomingTimeline(m, today, zone, Modifier.padding(top = Spacing.lg))
        Column(Modifier.padding(horizontal = Spacing.screen).padding(top = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            SectionHeader(stringResource(R.string.mandate_section_terms))
            TermRow(stringResource(R.string.mandate_umn), m.umn)
            TermRow(stringResource(R.string.mandate_max_amount), Money.formatInr(m.maxAmountPaise))
            TermRow(stringResource(R.string.mandate_frequency), m.frequency.label())
            TermRow(stringResource(R.string.mandate_validity), stringResource(R.string.mandate_validity_range, m.startDate.format(DATE), m.endDate.format(DATE)))
            TermRow(stringResource(R.string.mandate_executions), m.executionCount.toString())
            if (m.note.isNotBlank()) TermRow(stringResource(R.string.detail_note), DisplayText.safe(m.note))
        }
        if (m.status == MandateStatus.ACTIVE) SimulateDebit(state, callbacks)
        SectionHeader(stringResource(R.string.mandate_history), Modifier.padding(horizontal = Spacing.screen).padding(top = Spacing.lg))
        if (state.executions.isEmpty()) {
            Text(stringResource(R.string.mandate_history_empty_hint), style = type.body, color = colors.textSecondary, modifier = Modifier.padding(horizontal = Spacing.screen))
        } else {
            state.executions.forEachIndexed { i, payment ->
                PaymentFeedRow(payment, amountsHidden, payment.createdAt.relativeLabel(), callbacks.onOpenPayment)
                if (i < state.executions.lastIndex) HairlineDivider(Modifier.padding(start = Spacing.screen + Sizes.avatar + Spacing.sm, end = Spacing.screen))
            }
        }
        if (MandateAction.REVOKE in state.availableActions) {
            GhostButton(
                stringResource(R.string.mandate_action_revoke),
                onRevoke,
                enabled = !state.busy,
                tone = GhostTone.Danger,
                leadingIcon = Icons.Outlined.Block,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).padding(top = Spacing.xl).testTag(PayFlowTestTags.mandateAction(MandateAction.REVOKE)),
            )
        }
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun TermRow(label: String, value: String) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(label, style = type.label, color = colors.textSecondary)
        Text(value, style = type.label, color = colors.text, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** Sandbox only: plays the merchant presenting a debit, so the rules can be seen deciding. Labelled as such. */
@Composable
private fun SimulateDebit(state: MandateDetailUiState.Content, callbacks: MandateDetailCallbacks) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    PayFlowSurface(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).padding(top = Spacing.lg), level = SurfaceLevel.Raised) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Science, contentDescription = null, tint = colors.accent, modifier = Modifier.size(Sizes.iconSmall))
                Text(stringResource(R.string.mandate_simulate_title), style = type.bodyStrong, color = colors.text)
            }
            Text(stringResource(R.string.mandate_simulate_hint), style = type.label, color = colors.textSecondary)
            PayFlowTextField(
                state.debitAmountText, callbacks.onDebitAmountChange, stringResource(R.string.pay_amount),
                enabled = !state.busy,
                leading = { Text("₹", style = type.body, color = colors.textSecondary) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                fieldTag = PayFlowTestTags.MANDATE_DEBIT_AMOUNT,
            )
            TactileButton(
                stringResource(R.string.mandate_simulate_button),
                { callbacks.onSimulateDebit(state.debitAmountText) },
                loading = state.busy,
                modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.MANDATE_DEBIT),
            )
        }
    }
}

@Composable
private fun RevokeSheet(visible: Boolean, mandate: Mandate?, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val last = remember { mutableStateOf<Mandate?>(null) }
    if (mandate != null) last.value = mandate
    val shown = last.value ?: return
    PayFlowBottomSheet(visible, onDismiss, Modifier.testTag(PayFlowTestTags.MANDATE_REVOKE_SHEET)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).padding(bottom = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.mandate_revoke_title), style = type.title, color = colors.text, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.mandate_revoke_body, DisplayText.safe(shown.payeeName)), style = type.body, color = colors.textSecondary)
            GhostButton(
                stringResource(R.string.mandate_revoke_confirm),
                onConfirm,
                enabled = !busy,
                tone = GhostTone.Danger,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs).testTag(PayFlowTestTags.MANDATE_REVOKE_CONFIRM),
            )
            PrimaryButton(stringResource(R.string.mandate_revoke_keep), onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}
