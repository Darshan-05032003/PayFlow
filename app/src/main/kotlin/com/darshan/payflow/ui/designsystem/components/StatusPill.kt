package com.darshan.payflow.ui.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionDurations
import com.darshan.payflow.ui.designsystem.motion.MotionEasing
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.designsystem.tokens.StatusTone
import com.darshan.payflow.ui.theme.PayFlowTheme

enum class StatusKind { Success, Pending, Failed, Refund, Neutral }

private val GlyphSize = Sizes.iconSmall
private const val GLYPH_STROKE = 0.14f

/**
 * A check drawn along its path. [progress] is read in the draw phase, so it animates without recomposition, and the retained
 * paths mean a frame allocates nothing. For success moments, pass a bigger [modifier] size.
 */
@Composable
fun DrawnCheckmark(progress: () -> Float, color: Color, modifier: Modifier = Modifier, strokeFraction: Float = GLYPH_STROKE) {
    TrimmedPathGlyph(progress, color, modifier, strokeFraction) { w, h ->
        listOf(
            Path().apply {
                moveTo(w * 0.18f, h * 0.54f)
                lineTo(w * 0.42f, h * 0.76f)
                lineTo(w * 0.84f, h * 0.26f)
            },
        )
    }
}

/** A cross drawn stroke by stroke, for failures. */
@Composable
fun DrawnCross(progress: () -> Float, color: Color, modifier: Modifier = Modifier, strokeFraction: Float = GLYPH_STROKE) {
    TrimmedPathGlyph(progress, color, modifier, strokeFraction) { w, h ->
        listOf(
            Path().apply {
                moveTo(w * 0.24f, h * 0.24f)
                lineTo(w * 0.76f, h * 0.76f)
            },
            Path().apply {
                moveTo(w * 0.76f, h * 0.24f)
                lineTo(w * 0.24f, h * 0.76f)
            },
        )
    }
}

@Composable
private fun TrimmedPathGlyph(progress: () -> Float, color: Color, modifier: Modifier, strokeFraction: Float, build: (Float, Float) -> List<Path>) {
    Spacer(
        modifier.drawWithCache {
            val strokes = build(size.width, size.height)
            val partial = Path()
            val measure = PathMeasure()
            val lengths = FloatArray(strokes.size) { index ->
                measure.setPath(strokes[index], false)
                measure.length
            }
            val total = lengths.sum()
            val stroke = Stroke(width = size.minDimension * strokeFraction, cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind {
                val p = progress().coerceIn(0f, 1f)
                var remaining = total * p
                for (index in strokes.indices) {
                    if (remaining <= 0f) break
                    val length = lengths[index]
                    if (remaining >= length) {
                        drawPath(strokes[index], color, style = stroke)
                    } else {
                        partial.reset()
                        measure.setPath(strokes[index], false)
                        measure.getSegment(0f, remaining, partial, startWithMoveTo = true)
                        drawPath(partial, color, style = stroke)
                    }
                    remaining -= length
                }
            }
        },
    )
}

/** A hollow ring whose arrow runs backwards: money returning. */
@Composable
private fun RefundGlyph(progress: () -> Float, color: Color) {
    Spacer(
        Modifier.size(GlyphSize).drawWithCache {
            val stroke = Stroke(width = size.minDimension * GLYPH_STROKE, cap = StrokeCap.Round)
            val inset = size.minDimension * 0.18f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val head = Path().apply {
                moveTo(size.width * 0.14f, size.height * 0.2f)
                lineTo(size.width * 0.2f, size.height * 0.46f)
                lineTo(size.width * 0.46f, size.height * 0.4f)
            }
            onDrawBehind {
                rotate(-360f * (1f - progress().coerceIn(0f, 1f))) {
                    drawArc(color, 200f, 290f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = stroke)
                    drawPath(head, color, style = stroke)
                }
            }
        },
    )
}

/** A solid dot inside a ring that breathes while the outcome is unknown. */
@Composable
private fun PendingGlyph(pulse: State<Float>, color: Color) {
    Spacer(
        Modifier.size(GlyphSize).drawBehind {
            val r = size.minDimension / 2
            val p = pulse.value
            drawCircle(color, radius = r * (0.45f + 0.5f * p), alpha = 0.5f * (1f - p))
            drawCircle(color, radius = r * 0.36f)
        },
    )
}

/**
 * Status pill with a micro-animation of its own. Success draws its check, failed draws a cross with a small shake, refund's
 * arrow turns backwards, and pending's dot breathes for as long as it stays pending.
 *
 * Pass [animate] false in long lists, so rows that scroll back don't replay. Reduced motion shows the final frame; pending
 * holds still.
 */
@Composable
fun StatusPill(kind: StatusKind, label: String, modifier: Modifier = Modifier, animate: Boolean = true) {
    val colors = PayFlowTheme.colors
    val tone: StatusTone = when (kind) {
        StatusKind.Success -> colors.success
        StatusKind.Pending -> colors.pending
        StatusKind.Failed -> colors.failed
        StatusKind.Refund -> colors.refund
        StatusKind.Neutral -> colors.neutral
    }
    val motion = LocalMotion.current
    val play = animate && !motion.reduced
    val progress = remember(kind) { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(kind, play) {
        if (play) progress.animateTo(1f, motion.spec(MotionToken.Small)) else progress.snapTo(1f)
    }
    val value = { progress.value }
    Row(
        modifier
            .defaultMinSize(minHeight = Sizes.chipHeight)
            .clip(Radii.pillShape)
            .drawBehind { drawRect(tone.container) }
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        when (kind) {
            StatusKind.Success -> DrawnCheckmark(value, tone.content, Modifier.size(GlyphSize))
            StatusKind.Failed -> FailedGlyph(value, tone.content)
            StatusKind.Refund -> RefundGlyph(value, tone.content)
            StatusKind.Pending -> {
                val pulse = if (play) {
                    rememberInfiniteTransition(label = "pending").animateFloat(
                        0f,
                        1f,
                        infiniteRepeatable(tween(MotionDurations.Ambient / 2, easing = MotionEasing.Standard.curve), RepeatMode.Restart),
                        label = "pendingPulse",
                    )
                } else {
                    remember { mutableFloatStateOf(0f) }
                }
                PendingGlyph(pulse, tone.content)
            }
            StatusKind.Neutral -> Spacer(
                Modifier.size(GlyphSize).drawBehind { drawCircle(tone.content, radius = size.minDimension * 0.18f) },
            )
        }
        Text(label, style = PayFlowTheme.type.label, color = tone.content, maxLines = 1)
    }
}

@Composable
private fun FailedGlyph(progress: () -> Float, color: Color) {
    Spacer(
        Modifier.size(GlyphSize).drawWithCache {
            val a = Path().apply {
                moveTo(size.width * 0.24f, size.height * 0.24f)
                lineTo(size.width * 0.76f, size.height * 0.76f)
            }
            val b = Path().apply {
                moveTo(size.width * 0.76f, size.height * 0.24f)
                lineTo(size.width * 0.24f, size.height * 0.76f)
            }
            val stroke = Stroke(width = size.minDimension * GLYPH_STROKE, cap = StrokeCap.Round)
            onDrawBehind {
                val p = progress().coerceIn(0f, 1f)
                val settle = ShakeCurve.offset(p)
                val grow = 0.6f + 0.4f * p
                rotate(settle * 14f) {
                    scale(grow) {
                        drawPath(a, color, style = stroke)
                        drawPath(b, color, style = stroke)
                    }
                }
            }
        },
    )
}
