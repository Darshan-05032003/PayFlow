package com.darshan.payflow.ui.pay

import androidx.compose.animation.core.animate
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.components.BrandLoader
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.effects.perform
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Swipe-to-pay. Rules are in [SwipeToPayHolder]; this is the gesture and the drawing.
 *
 * - The drag must start on the handle and is tracked by one pointer. A second finger does nothing.
 * - A system cancel arrives as an up that's already consumed, which is how it's told apart from a real lift.
 * - The handle's position is read in the layer and draw phases, so a drag recomposes nothing.
 * - TalkBack's activate runs [onPlainAction], which must ask for confirmation. There's no way to commit without either a real
 *   drag or that confirmation.
 *
 * [refusals] goes up when an attempt ends without a payment, which sends a committed handle home.
 *
 * Also approves collect requests, with the same rules and the same confirmation route.
 */
@Composable
fun SwipeToPay(
    label: String,
    description: String,
    enabled: Boolean,
    processing: Boolean,
    refusals: Int,
    onCommit: () -> Unit,
    onPlainAction: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = PayFlowTestTags.PAY_SWIPE,
) {
    val colors = PayFlowTheme.colors
    val motion = LocalMotion.current
    val haptics = LocalHaptics.current
    val holder = remember { SwipeToPayHolder() }
    var fraction by remember { mutableFloatStateOf(0f) }
    var travel by remember { mutableFloatStateOf(0f) }
    var springJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val active by rememberUpdatedState(enabled && !processing)
    val commit by rememberUpdatedState(onCommit)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val handlePx = with(LocalDensity.current) { Sizes.swipeHandle.toPx() }
    val insetPx = with(LocalDensity.current) { ((Sizes.swipeTrack - Sizes.swipeHandle) / 2).toPx() }

    fun play(signals: List<SwipeSignal>) = signals.forEach { signal -> signal.haptic?.let(haptics::perform) }

    fun springHome() {
        springJob?.cancel()
        springJob = scope.launch {
            animate(fraction, 0f, animationSpec = motion.spec(MotionToken.Release)) { value, _ ->
                holder.springTo(value)
                fraction = holder.fraction
            }
            holder.settled()
            fraction = holder.fraction
        }
    }

    // First composition is not a refusal; a later change is.
    val seenRefusals = remember { intArrayOf(refusals) }
    LaunchedEffect(refusals) {
        if (refusals != seenRefusals[0]) {
            seenRefusals[0] = refusals
            if (holder.phase == SwipePhase.Committed) {
                holder.reopen()
                springHome()
            }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(Sizes.swipeTrack)
            .systemGestureExclusion()
            .clip(Radii.pillShape)
            .drawWithCache {
                val radius = CornerRadius(size.height / 2)
                val detent = (size.width - handlePx - insetPx * 2) / SwipeToPayHolder.DEFAULT_DETENTS
                val tick = Spacing.xxs.toPx()
                val tickWidth = Hairline.width.toPx() * 1.5f
                val tickBottom = size.height - Spacing.xs.toPx()
                onDrawBehind {
                    drawRect(if (active) colors.raised else colors.primaryDisabled)
                    val filled = handlePx + insetPx * 2 + fraction * travel
                    if (fraction > 0f) {
                        val left = if (rtl) size.width - filled else 0f
                        drawRoundRect(colors.accent, topLeft = Offset(left, 0f), size = Size(filled, size.height), cornerRadius = radius, alpha = 0.28f)
                    }
                    if (active) {
                        for (i in 1 until SwipeToPayHolder.DEFAULT_DETENTS) {
                            val x = insetPx + handlePx / 2 + detent * i
                            val at = if (rtl) size.width - x else x
                            drawLine(colors.outline, Offset(at, tickBottom - tick), Offset(at, tickBottom), tickWidth)
                        }
                    }
                }
            }
            .border(Hairline.width, colors.hairline, Radii.pillShape)
            .onSizeChanged { travel = (it.width - handlePx - insetPx * 2).coerceAtLeast(1f) }
            .pointerInput(rtl) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val along = if (rtl) size.width - down.position.x else down.position.x
                    if (along > fraction * travel + handlePx + insetPx * 2) return@awaitEachGesture
                    val began = holder.begin(active)
                    play(began)
                    if (holder.phase != SwipePhase.Dragging) return@awaitEachGesture
                    springJob?.cancel()
                    down.consume()
                    var finished = false
                    while (!finished) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        when {
                            change == null -> {
                                play(holder.cancel())
                                springHome()
                                finished = true
                            }
                            !change.pressed -> {
                                // Only a real lift can pay. The attempt also dies if the form stopped being payable mid-drag.
                                val signals = if (change.changedToUp() && active) holder.release() else holder.cancel()
                                play(signals)
                                fraction = holder.fraction
                                if (SwipeSignal.Committed in signals) commit() else springHome()
                                finished = true
                            }
                            else -> {
                                val dx = change.position.x - change.previousPosition.x
                                play(holder.dragBy((if (rtl) -dx else dx) / travel))
                                fraction = holder.fraction
                                change.consume()
                            }
                        }
                    }
                }
            }
            .testTag(testTag)
            .clearAndSetSemantics {
                contentDescription = description
                text = AnnotatedString(label)
                role = Role.Button
                if (active) {
                    onClick {
                        onPlainAction()
                        true
                    }
                } else {
                    disabled()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label,
            style = PayFlowTheme.type.button,
            color = if (active) colors.text else colors.onPrimaryDisabled,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Sizes.swipeHandle + Spacing.xs)
                .graphicsLayer { alpha = (1f - fraction * LABEL_FADE_SPEED).coerceIn(0f, 1f) },
        )
        val top = colors.primaryTop
        val bottom = colors.primaryBottom
        val fill = remember(top, bottom) { Brush.verticalGradient(listOf(top, bottom)) }
        val disabledFill = colors.primaryDisabled
        val on = if (active) colors.onPrimary else colors.onPrimaryDisabled
        Box(
            Modifier
                .padding(horizontal = Spacing.xxs)
                .size(Sizes.swipeHandle)
                .graphicsLayer { translationX = fraction * travel * if (rtl) -1f else 1f }
                .clip(Radii.pillShape)
                .drawBehind { if (active) drawRect(fill) else drawRect(disabledFill) }
                .border(Hairline.width, colors.hairline, Radii.pillShape),
            contentAlignment = Alignment.Center,
        ) {
            if (processing) {
                BrandLoader(size = Sizes.loaderSmall, color = colors.accent, trackColor = colors.outline)
            } else {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = on, modifier = Modifier.size(Sizes.icon))
            }
        }
    }
}

private const val LABEL_FADE_SPEED = 2.2f
