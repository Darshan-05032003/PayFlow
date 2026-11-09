package com.darshan.payflow.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionDurations
import com.darshan.payflow.ui.designsystem.motion.MotionEasing
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.motion.PayFlowMotion
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val MAGNETIC_PRESSED_SCALE = 0.94f

/** A tile that leans towards the finger and settles back on a spring. Under reduced motion it only scales. */
@Stable
internal class MagneticPress(private val scope: CoroutineScope, private val motion: PayFlowMotion, private val maxPx: Float) {
    private val lean = Animatable(Offset.Zero, Offset.VectorConverter)
    private val pressed = Animatable(0f)

    val x: Float get() = lean.value.x
    val y: Float get() = lean.value.y
    val scale: Float get() = 1f - (1f - MAGNETIC_PRESSED_SCALE) * pressed.value

    fun moveTo(pointer: Offset, size: IntSize) {
        if (motion.reduced) return
        scope.launch { lean.animateTo(MagneticLogic.pull(pointer, size.toSize(), maxPx), motion.spec(MotionToken.Press)) }
    }

    fun down() {
        scope.launch { pressed.animateTo(1f, motion.spec(MotionToken.Press)) }
    }

    fun release() {
        scope.launch { lean.animateTo(Offset.Zero, motion.spec(MotionToken.Release)) }
        scope.launch { pressed.animateTo(0f, motion.spec(MotionToken.Release)) }
    }
}

@Composable
internal fun rememberMagneticPress(): MagneticPress {
    val scope = rememberCoroutineScope()
    val motion = LocalMotion.current
    val maxPx = with(LocalDensity.current) { Spacing.xs.toPx() }
    return remember(motion, maxPx) { MagneticPress(scope, motion, maxPx) }
}

/**
 * Feeds [state] from raw pointer events and moves the element in its layer. The pointer is read outside the layer, so the lean
 * doesn't feed back into the position it is measured from. Nothing is consumed; moving past the touch slop means the page is
 * scrolling, so the tile lets go.
 */
internal fun Modifier.magneticPress(state: MagneticPress): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            state.down()
            state.moveTo(down.position, size)
            val slop = viewConfiguration.touchSlop
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if ((change.position - down.position).getDistance() > slop) break
                state.moveTo(change.position, size)
            }
            state.release()
        }
    }.graphicsLayer {
        translationX = state.x
        translationY = state.y
        scaleX = state.scale
        scaleY = state.scale
    }

/** One ring spreading from an avatar when it is tapped. Decorative, so it never holds up the tap, and off under reduced motion. */
@Stable
internal class AvatarRipple(private val scope: CoroutineScope, private val motion: PayFlowMotion) {
    private val progress = Animatable(0f)
    val value: Float get() = progress.value

    fun play() {
        if (!motion.decorativeEnabled) return
        scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, motion.spec(MotionToken.Screen))
            progress.snapTo(0f)
        }
    }
}

@Composable
internal fun rememberAvatarRipple(): AvatarRipple {
    val scope = rememberCoroutineScope()
    val motion = LocalMotion.current
    return remember(motion) { AvatarRipple(scope, motion) }
}

/** The breathing of the Scan & Pay ring: 0 to 1 and back. Null means hold still. */
@Composable
internal fun breathing(active: Boolean): State<Float>? {
    if (!active || !LocalMotion.current.decorativeEnabled) return null
    return rememberInfiniteTransition(label = "scanRing").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(MotionDurations.Ambient, easing = MotionEasing.Standard.curve), RepeatMode.Reverse),
        label = "scanRingBreath",
    )
}

/**
 * The refresh coin: a ₹ on one face, a plain ring on the other, turning with the pull and spinning while the refresh runs.
 * Only the layer and the face change per frame. Under reduced motion it shows its face and holds still.
 */
@Composable
internal fun PullCoin(fraction: () -> Float, thresholdPx: Float, topInsetPx: Float, refreshing: Boolean, refreshingLabel: String, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val colors = PayFlowTheme.colors
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val glyphStyle = PayFlowTheme.type.title.copy(color = colors.heroEnd)
    val spin: State<Float>? = if (refreshing && motion.decorativeEnabled) {
        rememberInfiniteTransition(label = "coin").animateFloat(
            initialValue = PullLogic.FLIPS_DEGREES,
            targetValue = PullLogic.FLIPS_DEGREES + FULL_TURNS,
            animationSpec = infiniteRepeatable(tween(MotionDurations.Ambient, easing = MotionEasing.Standard.curve), RepeatMode.Restart),
            label = "coinSpin",
        )
    } else {
        null
    }
    val degrees = { if (motion.decorativeEnabled) spin?.value ?: PullLogic.coinDegrees(fraction()) else 0f }
    val density = LocalDensity.current
    Spacer(
        modifier
            .testTag(PayFlowTestTags.HOME_PULL_COIN)
            .then(if (refreshing) Modifier.semantics { contentDescription = refreshingLabel } else Modifier)
            .size(Sizes.loader)
            .graphicsLayer {
                val stretch = PullLogic.stretchPx(fraction(), thresholdPx)
                translationY = (stretch + topInsetPx - size.height) / 2f
                alpha = fraction().coerceIn(0f, 1f)
                rotationY = degrees()
                cameraDistance = COIN_CAMERA_DISTANCE * density.density
            }
            .drawWithCache {
                val face = Brush.linearGradient(listOf(colors.sheen, colors.accent))
                val back = Brush.linearGradient(listOf(colors.accent, colors.sheen))
                val glyph = measurer.measure("₹", glyphStyle)
                val glyphAt = Offset((size.width - glyph.size.width) / 2f, (size.height - glyph.size.height) / 2f)
                val ring = Stroke(Hairline.width.toPx() * 2f)
                onDrawBehind {
                    if (PullLogic.showsFront(degrees())) {
                        drawCircle(face)
                        drawText(glyph, topLeft = glyphAt)
                    } else {
                        drawCircle(back)
                        drawCircle(colors.heroEnd, radius = size.minDimension * COIN_RING, style = ring)
                    }
                }
            },
    )
}

private const val FULL_TURNS = 720f
private const val COIN_CAMERA_DISTANCE = 12f
private const val COIN_RING = 0.3f
