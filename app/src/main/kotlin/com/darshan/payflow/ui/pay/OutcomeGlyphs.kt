package com.darshan.payflow.ui.pay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.components.BrandLoader
import com.darshan.payflow.ui.designsystem.components.DrawnCheckmark
import com.darshan.payflow.ui.designsystem.components.DrawnCross
import com.darshan.payflow.ui.designsystem.components.ShakeCurve
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionDurations
import com.darshan.payflow.ui.designsystem.motion.MotionEasing
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Particle layout for one burst: fixed once, so a frame only reads floats. */
class BurstParticles(count: Int = 28, seed: Int = 7) {
    val angle = FloatArray(count)
    val reach = FloatArray(count)
    val radius = FloatArray(count)
    val spin = FloatArray(count)

    init {
        val random = Random(seed)
        for (i in 0 until count) {
            angle[i] = (i.toFloat() / count + random.nextFloat() * 0.04f) * 2f * PI.toFloat()
            reach[i] = 0.62f + random.nextFloat() * 0.38f
            radius[i] = 0.5f + random.nextFloat() * 0.5f
            spin[i] = random.nextFloat()
        }
    }

    val size: Int get() = angle.size
}

/** Where a particle is at [t]: it flies out fast and slows, and fades out over the last stretch. Pure, for tests. */
object BurstCurve {
    fun travel(t: Float): Float {
        val p = t.coerceIn(0f, 1f)
        return 1f - (1f - p) * (1f - p) * (1f - p)
    }

    fun alpha(t: Float): Float {
        val p = t.coerceIn(0f, 1f)
        return if (p < 0.55f) 1f else ((1f - p) / 0.45f)
    }
}

/**
 * Coins flying out of the check. Only for a payment the provider confirmed. Runs once, off the UI's way, and is a [Spacer] with no
 * semantics: nothing can depend on it. Reduced motion doesn't compose it.
 */
@Composable
fun CoinBurst(modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    if (!motion.decorativeEnabled) return
    val color = PayFlowTheme.colors.accent
    val particles = remember { BurstParticles() }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, motion.spec(MotionToken.Sheen)) }
    val coin = with(LocalDensity.current) { Spacing.xxs.toPx() }
    Spacer(
        modifier.testTag(PayFlowTestTags.PAY_BURST).drawBehind {
            val t = progress.value
            if (t <= 0f || t >= 1f) return@drawBehind
            val out = BurstCurve.travel(t)
            val a = BurstCurve.alpha(t)
            val r = size.minDimension / 2
            val c = center
            for (i in 0 until particles.size) {
                val distance = r * particles.reach[i] * out
                val theta = particles.angle[i] + particles.spin[i] * t
                drawCircle(
                    color,
                    radius = coin * particles.radius[i],
                    center = Offset(c.x + cos(theta) * distance, c.y + sin(theta) * distance),
                    alpha = a,
                )
            }
        },
    )
}

@Composable
private fun GlyphDisc(container: Color, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.size(Sizes.heroAction).drawBehind { drawCircle(container) },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A check drawn along its path. [play] false is the finished frame: reduced motion, or a rotation after the moment passed. */
@Composable
fun SuccessGlyph(play: Boolean, modifier: Modifier = Modifier) {
    val tone = PayFlowTheme.colors.success
    val motion = LocalMotion.current
    val draw = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(play) { if (play) draw.animateTo(1f, motion.spec(MotionToken.Screen)) else draw.snapTo(1f) }
    GlyphDisc(tone.container, modifier) {
        DrawnCheckmark({ draw.value }, tone.content, Modifier.size(Sizes.iconLarge + Spacing.md))
    }
}

/** A plain, finished check for an outcome that isn't PayFlow's to celebrate, in the neutral tone. */
@Composable
fun MarkedGlyph(failed: Boolean, modifier: Modifier = Modifier) {
    val tone = PayFlowTheme.colors.neutral
    GlyphDisc(tone.container, modifier) {
        if (failed) {
            DrawnCross({ 1f }, tone.content, Modifier.size(Sizes.iconLarge + Spacing.md))
        } else {
            DrawnCheckmark({ 1f }, tone.content, Modifier.size(Sizes.iconLarge + Spacing.md))
        }
    }
}

/** A cross drawn stroke by stroke, then a shake. Reduced motion shows the finished cross, without the shake. */
@Composable
fun FailedGlyph(play: Boolean, modifier: Modifier = Modifier) {
    val tone = PayFlowTheme.colors.failed
    val motion = LocalMotion.current
    val draw = remember { Animatable(if (play) 0f else 1f) }
    val shake = remember { Animatable(1f) }
    LaunchedEffect(play) {
        if (play) {
            draw.animateTo(1f, motion.spec(MotionToken.Small))
            if (!motion.reduced) {
                shake.snapTo(0f)
                shake.animateTo(1f, motion.spec(MotionToken.Shake))
            }
        } else {
            draw.snapTo(1f)
        }
    }
    val shakePx = with(LocalDensity.current) { Spacing.xs.toPx() }
    GlyphDisc(tone.container, modifier.graphicsLayer { translationX = ShakeCurve.offset(shake.value) * shakePx }) {
        DrawnCross({ draw.value }, tone.content, Modifier.size(Sizes.iconLarge + Spacing.md))
    }
}

/** A clock whose ring pulses while the answer is out. Holds still when nothing is being checked, or under reduced motion. */
@Composable
fun PendingGlyph(live: Boolean, modifier: Modifier = Modifier) {
    val tone = PayFlowTheme.colors.pending
    val motion = LocalMotion.current
    val pulse: State<Float> = if (live && motion.decorativeEnabled) {
        rememberInfiniteTransition(label = "pending").animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(MotionDurations.Ambient, easing = MotionEasing.Standard.curve), RepeatMode.Restart),
            label = "pendingPulse",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val stroke = with(LocalDensity.current) { (Hairline.width * 2).toPx() }
    Spacer(
        modifier.size(Sizes.heroAction).drawWithCache {
            val hands = Stroke(width = stroke * 1.4f, cap = StrokeCap.Round)
            val ring = Stroke(width = stroke)
            val r = size.minDimension / 2
            val mid = Offset(size.width / 2, size.height / 2)
            val path = Path().apply {
                moveTo(mid.x, mid.y - r * 0.32f)
                lineTo(mid.x, mid.y)
                lineTo(mid.x + r * 0.24f, mid.y + r * 0.14f)
            }
            onDrawBehind {
                drawCircle(tone.container, radius = r * 0.76f)
                val p = pulse.value
                if (p > 0f) drawCircle(tone.content, radius = r * (0.76f + 0.24f * p), alpha = 0.5f * (1f - p), style = ring)
                drawCircle(tone.content, radius = r * 0.5f, style = ring)
                drawPath(path, tone.content, style = hands)
            }
        },
    )
}

/** Money coming back: an arrow draws from the payee's side to the user's, against the direction of a payment. */
@Composable
fun RefundGlyph(play: Boolean, modifier: Modifier = Modifier) {
    val tone = PayFlowTheme.colors.refund
    val motion = LocalMotion.current
    val flow = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(play) { if (play) flow.animateTo(1f, motion.spec(MotionToken.Screen)) else flow.snapTo(1f) }
    val stroke = with(LocalDensity.current) { (Hairline.width * 3).toPx() }
    Spacer(
        modifier.size(Sizes.heroAction).drawBehind {
            val r = size.minDimension / 2
            drawCircle(tone.container, radius = r)
            val p = flow.value
            val startX = center.x + r * 0.5f
            val endX = startX - r * p
            drawLine(tone.content, Offset(startX, center.y), Offset(endX, center.y), stroke, StrokeCap.Round)
            val head = r * 0.22f * p
            drawLine(tone.content, Offset(endX, center.y), Offset(endX + head, center.y - head), stroke, StrokeCap.Round)
            drawLine(tone.content, Offset(endX, center.y), Offset(endX + head, center.y + head), stroke, StrokeCap.Round)
        },
    )
}

/** The wait: a large ring with a few slow motes around it. Motes are decorative, and absent under reduced motion. */
@Composable
fun ProcessingRing(modifier: Modifier = Modifier, label: String) {
    val motion = LocalMotion.current
    val color = PayFlowTheme.colors.accent
    Box(modifier.size(Sizes.heroAction + Spacing.xxxl), contentAlignment = Alignment.Center) {
        if (motion.decorativeEnabled) {
            val drift = rememberInfiniteTransition(label = "motes").animateFloat(
                0f,
                1f,
                infiniteRepeatable(tween(MotionDurations.Ambient * 3, easing = MotionEasing.Standard.curve), RepeatMode.Restart),
                label = "motesDrift",
            )
            val particles = remember { BurstParticles(count = 9, seed = 11) }
            val mote = with(LocalDensity.current) { (Hairline.width * 2).toPx() }
            Spacer(
                Modifier.size(Sizes.heroAction + Spacing.xxxl).drawBehind {
                    val r = size.minDimension / 2
                    val t = drift.value
                    for (i in 0 until particles.size) {
                        val theta = particles.angle[i] + t * 2f * PI.toFloat() * (0.4f + particles.spin[i] * 0.4f)
                        val distance = r * (0.74f + 0.26f * particles.reach[i])
                        drawCircle(color, radius = mote * particles.radius[i], center = Offset(center.x + cos(theta) * distance, center.y + sin(theta) * distance), alpha = 0.55f)
                    }
                },
            )
        }
        BrandLoader(size = Sizes.heroAction, label = label)
    }
}
