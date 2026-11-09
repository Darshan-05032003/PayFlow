package com.darshan.payflow.ui.designsystem.components

import android.graphics.Matrix
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import com.darshan.payflow.R
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionDurations
import com.darshan.payflow.ui.designsystem.motion.MotionEasing
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme

/** Position of the shared shimmer band, so every block of a group sweeps together. */
private val LocalShimmer = compositionLocalOf<State<Float>?> { null }

private const val REST = -1f

@Composable
private fun shimmerState(): State<Float> {
    val motion = LocalMotion.current
    return if (motion.decorativeEnabled) {
        rememberInfiniteTransition(label = "shimmer").animateFloat(
            initialValue = -0.5f,
            targetValue = 1.5f,
            animationSpec = infiniteRepeatable(tween(MotionDurations.Shimmer, easing = MotionEasing.Standard.curve), RepeatMode.Restart),
            label = "shimmerPosition",
        )
    } else {
        remember { mutableFloatStateOf(REST) }
    }
}

/** Shares one animation among its children. Wrap a whole loading screen in it. */
@Composable
fun SkeletonGroup(modifier: Modifier = Modifier, label: String = stringResource(R.string.loading), content: @Composable () -> Unit) {
    val shared = shimmerState()
    Box(modifier.clearAndSetSemantics { contentDescription = label }) {
        CompositionLocalProvider(LocalShimmer provides shared, content = content)
    }
}

/**
 * A shimmering placeholder block. The sweep translates one retained gradient in the draw phase, with no per-frame allocation
 * or recomposition. Reduced motion holds a flat tone.
 */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, shape: Shape = Radii.smallShape) {
    val colors = PayFlowTheme.colors
    val state = LocalShimmer.current ?: shimmerState()
    SkeletonSurface(modifier, shape, colors.shimmerBase, colors.shimmerHighlight, state)
}

@Composable
private fun SkeletonSurface(modifier: Modifier, shape: Shape, base: Color, highlight: Color, state: State<Float>) {
    Box(
        modifier
            .clip(shape)
            .drawWithCache {
                val width = size.width
                val shader = LinearGradientShader(
                    from = Offset(-width * 0.4f, 0f),
                    to = Offset(width * 0.4f, 0f),
                    colors = listOf(highlight.copy(alpha = 0f), highlight, highlight.copy(alpha = 0f)),
                )
                val matrix = Matrix()
                val paint = Paint().apply { this.shader = shader }
                val rect = Rect(Offset.Zero, size)
                onDrawBehind {
                    drawRect(base)
                    val position = state.value
                    if (position != REST) {
                        matrix.setTranslate(position * width, 0f)
                        shader.setLocalMatrix(matrix)
                        drawIntoCanvas { it.drawRect(rect, paint) }
                    }
                }
            },
    )
}

/** One line of text-shaped placeholder, as wide as [widthFraction] of its parent. */
@Composable
fun SkeletonLine(modifier: Modifier = Modifier, widthFraction: Float = 1f, height: Dp = Spacing.sm) {
    SkeletonBlock(modifier.fillMaxWidth(widthFraction).height(height), Radii.pillShape)
}

@Composable
fun SkeletonCircle(size: Dp, modifier: Modifier = Modifier) {
    SkeletonBlock(modifier.size(size), Radii.pillShape)
}

/** The shape of a transaction row while it loads. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(Sizes.listRowHeight)
            .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        SkeletonCircle(Sizes.avatar)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            SkeletonLine(widthFraction = 0.6f)
            SkeletonLine(widthFraction = 0.36f, height = Spacing.xs)
        }
        SkeletonBlock(Modifier.width(Spacing.huge).height(Spacing.md), Radii.pillShape)
    }
}

/** A loading list of [count] rows sharing one sweep. */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, count: Int = 5) {
    SkeletonGroup(modifier) {
        Column { repeat(count) { SkeletonRow() } }
    }
}
