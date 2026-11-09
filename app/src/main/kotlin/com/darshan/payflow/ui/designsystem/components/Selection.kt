package com.darshan.payflow.ui.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.darshan.payflow.R
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.modifiers.pressScale
import com.darshan.payflow.ui.designsystem.modifiers.rememberPressFeedback
import com.darshan.payflow.ui.designsystem.modifiers.trackPress
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme

/** A filter or quick-amount chip. Selection crossfades in the draw phase; the touch target stays 48dp. */
@Composable
fun PayFlowChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = PayFlowTheme.colors
    val motion = LocalMotion.current
    val haptics = LocalHaptics.current
    val press = rememberPressFeedback()
    val selection = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected) { selection.animateTo(if (selected) 1f else 0f, motion.spec(MotionToken.Micro)) }
    Box(
        modifier
            .defaultMinSize(minHeight = Sizes.touchTarget)
            .trackPress(press, enabled)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.tick()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .pressScale(press, pressedScale = 0.95f)
                .defaultMinSize(minHeight = Sizes.chipHeight + Spacing.xxs)
                .clip(Radii.pillShape)
                .drawBehind { drawRect(colors.accentTint, alpha = selection.value) }
                .border(Hairline.width, if (selected) colors.accentTint else if (enabled) colors.outline else colors.hairline, Radii.pillShape)
                .padding(horizontal = Spacing.md, vertical = Spacing.xxs),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = PayFlowTheme.type.label,
                color = when {
                    selected -> colors.onAccentTint
                    enabled -> colors.text
                    else -> colors.textSecondary
                },
                maxLines = 1,
            )
        }
    }
}

/** A toggle with a springing thumb. Thumb and colour are read in the draw phase. */
@Composable
fun PayFlowSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = PayFlowTheme.colors
    val motion = LocalMotion.current
    val haptics = LocalHaptics.current
    val position = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked) { position.animateTo(if (checked) 1f else 0f, motion.spec(MotionToken.Bounce)) }
    val onLabel = stringResource(R.string.ds_on)
    val offLabel = stringResource(R.string.ds_off)
    Row(
        modifier
            .defaultMinSize(minHeight = Sizes.touchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.tick()
                onCheckedChange(it)
            }
            .semantics { stateDescription = if (checked) onLabel else offLabel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(label, style = PayFlowTheme.type.body, color = if (enabled) colors.text else colors.textSecondary, modifier = Modifier.weight(1f))
        Spacer(
            Modifier
                .size(Sizes.switchWidth, Sizes.switchHeight)
                .drawBehind {
                    val p = position.value.coerceIn(0f, 1f)
                    val track = when {
                        !enabled -> colors.primaryDisabled
                        else -> lerp(colors.raised, colors.primary, p)
                    }
                    val height = size.height
                    drawRoundRect(track, cornerRadius = CornerRadius(height / 2))
                    if (p < 1f) {
                        drawRoundRect(
                            if (enabled) colors.outline else colors.hairline,
                            cornerRadius = CornerRadius(height / 2),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = Hairline.width.toPx()),
                            alpha = 1f - p,
                        )
                    }
                    val inset = 4.dp.toPx()
                    val radius = height / 2 - inset
                    val travel = size.width - height
                    drawCircle(
                        if (enabled) lerp(colors.outline, colors.onPrimary, p) else colors.textSecondary.copy(alpha = 0.5f),
                        radius = radius,
                        center = Offset(height / 2 + travel * position.value, height / 2),
                    )
                },
        )
    }
}
