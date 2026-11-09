package com.darshan.payflow.ui.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme

/**
 * A pill-shaped search field for a header: search glyph, the text, and a close button that the owner decides the meaning of
 * (clear, collapse, or both). The same focus glow as [PayFlowTextField], with no label above, because it sits where a title was.
 */
@Composable
fun PayFlowSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    closeLabel: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    fieldTag: String? = null,
    closeTag: String? = null,
    onSearch: () -> Unit = {},
) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val motion = LocalMotion.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = remember { Animatable(0f) }
    LaunchedEffect(focused) { focus.animateTo(if (focused) 1f else 0f, motion.spec(MotionToken.Small)) }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.touchTarget)
            .drawBehind {
                val radius = CornerRadius(size.height / 2)
                val p = focus.value
                if (p > 0f) {
                    val outer = Spacing.xxs.toPx() * p
                    drawRoundRect(
                        colors.focusGlow,
                        topLeft = Offset(-outer, -outer),
                        size = Size(size.width + outer * 2, size.height + outer * 2),
                        cornerRadius = CornerRadius(radius.x + outer),
                        alpha = 0.16f * p,
                    )
                }
                drawRoundRect(colors.raised, cornerRadius = radius)
                val stroke = Hairline.width.toPx()
                drawRoundRect(
                    lerp(colors.hairline, colors.accent, p),
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = radius,
                    style = Stroke(stroke),
                )
            }
            .padding(start = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(Sizes.iconSmall))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = type.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                interactionSource = interaction,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .then(if (fieldTag != null) Modifier.testTag(fieldTag) else Modifier)
                    .semantics { contentDescription = placeholder },
            )
            if (value.isEmpty()) Text(placeholder, style = type.body, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PayFlowIconButton(
            Icons.Outlined.Close,
            closeLabel,
            onClose,
            tint = colors.textSecondary,
            modifier = if (closeTag != null) Modifier.testTag(closeTag) else Modifier,
        )
    }
}
