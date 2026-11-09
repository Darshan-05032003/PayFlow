package com.darshan.payflow.ui.designsystem.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.darshan.payflow.R
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionDurations
import com.darshan.payflow.ui.designsystem.motion.MotionEasing
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * PayFlow's loader: a fine ring with a champagne arc, and a small coin at the arc's head. Use this instead of a stock
 * spinner. The angle is read in the draw phase. With reduced motion the arc holds still and [label] still announces it.
 */
@Composable
fun BrandLoader(
    modifier: Modifier = Modifier,
    size: Dp = Sizes.loader,
    color: Color = PayFlowTheme.colors.accent,
    trackColor: Color = PayFlowTheme.colors.hairline,
    label: String = stringResource(R.string.loading),
) {
    val motion = LocalMotion.current
    val angle: State<Float> = if (motion.decorativeEnabled) {
        rememberInfiniteTransition(label = "loader").animateFloat(
            initialValue = START_ANGLE,
            targetValue = START_ANGLE + 360f,
            animationSpec = infiniteRepeatable(tween(LOOP_MS, easing = MotionEasing.Standard.curve), RepeatMode.Restart),
            label = "loaderAngle",
        )
    } else {
        remember { mutableFloatStateOf(START_ANGLE) }
    }
    Spacer(
        modifier
            .size(size)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            }
            .drawWithCache {
                val strokePx = maxOf(Hairline.width.toPx(), this.size.minDimension * STROKE_FRACTION)
                val track = Stroke(width = Hairline.width.toPx())
                val arc = Stroke(width = strokePx, cap = StrokeCap.Round)
                val inset = strokePx * 1.5f
                val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                val radius = arcSize.width / 2
                val center = Offset(this.size.width / 2, this.size.height / 2)
                onDrawBehind {
                    drawCircle(trackColor, radius = radius, center = center, style = track)
                    val a = angle.value
                    drawArc(color, a, ARC_SWEEP, useCenter = false, topLeft = topLeft, size = arcSize, style = arc)
                    val head = Math.toRadians((a + ARC_SWEEP).toDouble())
                    drawCircle(
                        color,
                        radius = strokePx * 1.3f,
                        center = Offset(center.x + radius * cos(head).toFloat(), center.y + radius * sin(head).toFloat()),
                    )
                }
            },
    )
}

private const val START_ANGLE = -90f
private const val ARC_SWEEP = 96f
private const val STROKE_FRACTION = 0.05f
private const val LOOP_MS = MotionDurations.Ambient / 2
