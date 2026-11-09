package com.darshan.payflow.ui.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlin.math.PI
import kotlin.math.sin

/** Pure curve of the error nudge: three decaying swings, in units of the shake distance. */
object ShakeCurve {
    private const val SWINGS = 3
    fun offset(t: Float): Float {
        val p = t.coerceIn(0f, 1f)
        val decay = (1f - p) * (1f - p)
        return sin(p * 2f * PI.toFloat() * SWINGS) * decay
    }
}

/**
 * Single-field input: label above, value in a layered container, helper or error below.
 *
 * - Focus: the border warms to the accent and a soft glow grows outside it, read in the draw phase.
 * - Error: a new [error] plays one reject haptic and a shake. Reduced motion skips the shake; colour, icon and text remain.
 * - Disabled and read-only are visibly different from enabled.
 * Tag the editable node with [fieldTag] to reach it from tests.
 */
@Composable
fun PayFlowTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helper: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    fieldTag: String? = null,
) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val motion = LocalMotion.current
    val haptics = LocalHaptics.current
    val focused by interactionSource.collectIsFocusedAsState()
    val focus = remember { Animatable(0f) }
    val shake = remember { Animatable(1f) }
    val previousError = remember { arrayOf(error) }
    val shakePx = with(LocalDensity.current) { Spacing.xs.toPx() }

    LaunchedEffect(focused) { focus.animateTo(if (focused) 1f else 0f, motion.spec(MotionToken.Small)) }
    LaunchedEffect(error) {
        val changed = error != previousError[0]
        previousError[0] = error
        if (error != null && changed) {
            haptics.reject()
            if (!motion.reduced) {
                shake.snapTo(0f)
                shake.animateTo(1f, motion.spec(MotionToken.Shake))
            }
        }
    }

    val isError = error != null
    val idleBorder = when {
        isError -> colors.failed.strong
        !enabled -> colors.hairline
        readOnly -> colors.hairline
        else -> colors.outline
    }
    val activeBorder = if (isError) colors.failed.strong else colors.accent
    val glowColor = if (isError) colors.failed.strong else colors.focusGlow
    val container = if (enabled && !readOnly) colors.surface else colors.raised
    val textColor = if (enabled) colors.text else colors.textSecondary

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label, style = type.label, color = if (isError) colors.failed.strong else colors.textSecondary)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.inputHeight)
                .graphicsLayer { translationX = ShakeCurve.offset(shake.value) * shakePx }
                .drawBehind {
                    val radius = Radii.small.toPx()
                    val p = focus.value
                    if (p > 0f) {
                        val outer = Spacing.xxs.toPx() * p
                        drawRoundRect(
                            glowColor,
                            topLeft = Offset(-outer, -outer),
                            size = Size(size.width + outer * 2, size.height + outer * 2),
                            cornerRadius = CornerRadius(radius + outer),
                            alpha = 0.16f * p,
                        )
                    }
                    drawRoundRect(container, cornerRadius = CornerRadius(radius))
                    val stroke = Hairline.width.toPx()
                    drawRoundRect(
                        lerp(idleBorder, activeBorder, p),
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(radius),
                        style = Stroke(width = stroke),
                    )
                }
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            leading?.invoke()
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (fieldTag != null) Modifier.testTag(fieldTag) else Modifier)
                        .semantics {
                            contentDescription = label
                            if (error != null) error(error)
                        },
                    enabled = enabled,
                    readOnly = readOnly,
                    singleLine = singleLine,
                    textStyle = type.body.copy(color = textColor),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                )
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, style = type.body, color = colors.textSecondary, maxLines = 1)
                }
            }
            trailing?.invoke()
        }
        AnimatedVisibility(visible = isError, enter = motion.enter(), exit = motion.exit()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.failed.strong, modifier = Modifier.size(Sizes.iconSmall))
                Text(error.orEmpty(), style = type.label, color = colors.failed.strong)
            }
        }
        if (!isError && helper != null) Text(helper, style = type.label, color = colors.textSecondary)
    }
}
