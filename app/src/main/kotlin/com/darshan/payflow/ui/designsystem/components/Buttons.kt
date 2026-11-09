package com.darshan.payflow.ui.designsystem.components

import android.os.SystemClock
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.darshan.payflow.R
import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.effects.perform
import com.darshan.payflow.ui.designsystem.modifiers.PressFeedback
import com.darshan.payflow.ui.designsystem.modifiers.metallicSheen
import com.darshan.payflow.ui.designsystem.modifiers.pressScale
import com.darshan.payflow.ui.designsystem.modifiers.rememberPressFeedback
import com.darshan.payflow.ui.designsystem.modifiers.trackPress
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme

/**
 * Drops repeat clicks inside [windowMs]. A second layer against double submits; the ViewModel's idempotency stays the real
 * guard. A clock that jumps backwards resets the window, so it can never lock a button forever.
 */
class ClickDebouncer(private val windowMs: Long = DEFAULT_WINDOW_MS, private val now: () -> Long = SystemClock::uptimeMillis) {
    private var last: Long? = null

    fun tryAcquire(): Boolean {
        val time = now()
        val previous = last
        val open = previous == null || time < previous || time - previous >= windowMs
        if (open) last = time
        return open
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 500L
    }
}

private const val SHEEN_REST_POSITION = 0.2f
private const val SHEEN_PRESS_TRAVEL = 0.5f

/** Common behaviour: press feedback from the raw pointer, debounced click, one haptic per accepted click, button role. */
private fun Modifier.buttonBehaviour(
    enabled: Boolean,
    press: PressFeedback,
    interaction: MutableInteractionSource,
    role: Role,
    onClickLabel: String?,
    onClick: () -> Unit,
): Modifier = trackPress(press, enabled).clickable(
    enabled = enabled,
    onClickLabel = onClickLabel,
    role = role,
    interactionSource = interaction,
    indication = null,
    onClick = onClick,
)

@Composable
private fun rememberGuardedClick(debounceMs: Long, haptic: HapticEvent?, onClick: () -> Unit): () -> Unit {
    val haptics = LocalHaptics.current
    val debouncer = remember(debounceMs) { ClickDebouncer(debounceMs) }
    return {
        if (debounceMs <= 0 || debouncer.tryAcquire()) {
            haptic?.let(haptics::perform)
            onClick()
        }
    }
}

@Composable
private fun ButtonLabel(text: String, leadingIcon: ImageVector?, loading: Boolean, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.alpha(if (loading) 0f else 1f),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) Icon(leadingIcon, contentDescription = null, tint = color, modifier = Modifier.size(Sizes.icon * 0.84f))
        Text(
            text,
            style = PayFlowTheme.type.button,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val ButtonPadding = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)

/**
 * The one primary action of a screen: brushed-metal gradient with a resting sheen that travels with the press.
 *
 * States: enabled, pressed, disabled, [loading] (keeps its width, label stays in semantics, not clickable).
 * Debounced by default. Set [debounceMs] to 0 for controls that legitimately repeat.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    debounceMs: Long = ClickDebouncer.DEFAULT_WINDOW_MS,
    press: PressFeedback = rememberPressFeedback(),
) {
    val colors = PayFlowTheme.colors
    val active = enabled && !loading
    val interaction = remember { MutableInteractionSource() }
    val click = rememberGuardedClick(debounceMs, HapticEvent.Light, onClick)
    val loadingLabel = stringResource(R.string.loading)
    val fill = remember(colors, enabled) {
        if (enabled) Brush.verticalGradient(listOf(colors.primaryTop, colors.primaryBottom)) else null
    }
    Box(
        modifier
            .heightIn(min = Sizes.buttonHeight)
            .pressScale(press)
            .clip(Radii.mediumShape)
            .drawWithCache {
                onDrawBehind {
                    if (fill != null) {
                        drawRect(fill)
                        drawRect(colors.primaryPressed, alpha = press.progress)
                    } else {
                        drawRect(colors.primaryDisabled)
                    }
                }
            }
            .then(if (enabled) Modifier.metallicSheen(colors.sheen, intensity = colors.sheenStrength, position = { SHEEN_REST_POSITION + SHEEN_PRESS_TRAVEL * press.progress }) else Modifier)
            .border(Hairline.width, if (enabled) colors.sheen.copy(alpha = 0.14f) else colors.hairline, Radii.mediumShape)
            .buttonBehaviour(active, press, interaction, Role.Button, null, click)
            .then(if (loading) Modifier.semantics { stateDescription = loadingLabel } else Modifier)
            .then(ButtonPadding),
        contentAlignment = Alignment.Center,
    ) {
        val content = if (enabled) colors.onPrimary else colors.onPrimaryDisabled
        ButtonLabel(text, leadingIcon, loading, content)
        if (loading) BrandLoader(size = Sizes.loaderSmall, color = content, trackColor = content.copy(alpha = 0.25f), label = loadingLabel)
    }
}

/**
 * A secondary action with real travel: the champagne cap sits on a darker base and moves down onto it when pressed.
 * Rounded and low so it stays Aurum Noir. Same states and debounce as [PrimaryButton].
 */
@Composable
fun TactileButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    debounceMs: Long = ClickDebouncer.DEFAULT_WINDOW_MS,
    press: PressFeedback = rememberPressFeedback(),
) {
    val colors = PayFlowTheme.colors
    val active = enabled && !loading
    val interaction = remember { MutableInteractionSource() }
    val click = rememberGuardedClick(debounceMs, HapticEvent.Light, onClick)
    val loadingLabel = stringResource(R.string.loading)
    val depthPx = with(LocalDensity.current) { Sizes.tactileDepth.toPx() }
    val cap = if (enabled) colors.tactileCap else colors.primaryDisabled
    val base = if (enabled) colors.tactileBase else colors.hairline
    val content = if (enabled) colors.onTactile else colors.onPrimaryDisabled
    Box(
        modifier
            .heightIn(min = Sizes.buttonHeight + Sizes.tactileDepth)
            .drawBehind {
                val radius = Radii.medium.toPx()
                drawRoundRect(base, topLeft = Offset(0f, depthPx), size = Size(size.width, size.height - depthPx), cornerRadius = CornerRadius(radius))
            }
            .buttonBehaviour(active, press, interaction, Role.Button, null, click)
            .then(if (loading) Modifier.semantics { stateDescription = loadingLabel } else Modifier),
        propagateMinConstraints = true,
    ) {
        Box(
            Modifier
                .matchParentSizeMinusDepth(depthPx)
                .graphicsLayer { translationY = depthPx * press.progress }
                .drawBehind { drawRoundRect(cap, cornerRadius = CornerRadius(Radii.medium.toPx())) }
                .then(ButtonPadding),
            contentAlignment = Alignment.Center,
        ) {
            ButtonLabel(text, leadingIcon, loading, content)
            if (loading) BrandLoader(size = Sizes.loaderSmall, color = content, trackColor = content.copy(alpha = 0.25f), label = loadingLabel)
        }
    }
}

/** Makes the cap fill the button except for the base's visible depth at the bottom. */
private fun Modifier.matchParentSizeMinusDepth(depthPx: Float): Modifier = layout { measurable, constraints ->
    val depth = depthPx.toInt()
    val minHeight = (constraints.minHeight - depth).coerceAtLeast(0)
    val inner = constraints.copy(
        minWidth = constraints.minWidth,
        minHeight = minHeight,
        maxHeight = if (constraints.hasBoundedHeight) (constraints.maxHeight - depth).coerceAtLeast(minHeight) else constraints.maxHeight,
    )
    val placeable = measurable.measure(inner)
    layout(placeable.width, placeable.height + depth) { placeable.place(0, 0) }
}

enum class GhostTone { Accent, Neutral, Danger }

/** Low-emphasis action. Outlined by default; [outlined] false reads as a text button. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    outlined: Boolean = true,
    tone: GhostTone = GhostTone.Accent,
    debounceMs: Long = ClickDebouncer.DEFAULT_WINDOW_MS,
    press: PressFeedback = rememberPressFeedback(),
) {
    val colors = PayFlowTheme.colors
    val active = enabled && !loading
    val interaction = remember { MutableInteractionSource() }
    val click = rememberGuardedClick(debounceMs, HapticEvent.Light, onClick)
    val loadingLabel = stringResource(R.string.loading)
    val content = when {
        !enabled -> colors.textSecondary
        tone == GhostTone.Danger -> colors.failed.strong
        tone == GhostTone.Neutral -> colors.text
        else -> colors.accent
    }
    val border = if (enabled) colors.outline else colors.hairline
    Box(
        modifier
            .heightIn(min = Sizes.buttonHeightCompact)
            .pressScale(press)
            .clip(Radii.mediumShape)
            .drawBehind { drawRect(colors.raised, alpha = press.progress) }
            .then(if (outlined) Modifier.border(Hairline.width, border, Radii.mediumShape) else Modifier)
            .buttonBehaviour(active, press, interaction, Role.Button, null, click)
            .then(if (loading) Modifier.semantics { stateDescription = loadingLabel } else Modifier)
            .then(ButtonPadding),
        contentAlignment = Alignment.Center,
    ) {
        ButtonLabel(text, leadingIcon, loading, content)
        if (loading) BrandLoader(size = Sizes.loaderSmall, color = content, trackColor = content.copy(alpha = 0.25f), label = loadingLabel)
    }
}

/** A 48dp icon action. [tonal] adds a raised backdrop. [contentDescription] is required: an icon alone says nothing to TalkBack. */
@Composable
fun PayFlowIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
    tint: Color = LocalContentColor.current,
    debounceMs: Long = 0L,
    press: PressFeedback = rememberPressFeedback(),
) {
    val colors = PayFlowTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val click = rememberGuardedClick(debounceMs, HapticEvent.Light, onClick)
    IconButtonFrame(modifier, enabled, tonal, press, interaction, click) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) tint else colors.textSecondary) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(Sizes.icon))
        }
    }
}

@Composable
private fun IconButtonFrame(
    modifier: Modifier,
    enabled: Boolean,
    tonal: Boolean,
    press: PressFeedback,
    interaction: MutableInteractionSource,
    click: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = PayFlowTheme.colors
    Box(
        modifier
            .size(Sizes.touchTarget)
            .pressScale(press, pressedScale = 0.92f)
            .clip(Radii.pillShape)
            .drawBehind {
                if (tonal) drawRect(colors.raised)
                drawRect(colors.accentTint, alpha = press.progress)
            }
            .buttonBehaviour(enabled, press, interaction, Role.Button, null, click),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
