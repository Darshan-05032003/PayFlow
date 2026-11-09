package com.darshan.payflow.ui.designsystem.modifiers

import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken

/**
 * The brushed-metal band. A fine horizontal streak texture shows only inside the band, which reads as metal rather than a
 * plain gradient. Output is premultiplied.
 */
private const val SHEEN_AGSL = """
uniform float2 resolution;
uniform float position;
uniform float bandWidth;
layout(color) uniform half4 tint;
half4 main(float2 p) {
    float x = (p.x + p.y * 0.45) / resolution.x;
    float d = abs(x - position) / bandWidth;
    float band = 1.0 - smoothstep(0.0, 1.0, d);
    float streak = 0.88 + 0.12 * fract(sin(floor(p.y) * 12.9898) * 43758.5453);
    float a = band * band * streak * tint.a;
    return half4(tint.rgb * a, a);
}
"""

/** Off-screen rest position: drawing is skipped entirely when the band is here. */
const val SHEEN_HIDDEN = -1f
private const val SHEEN_START = -0.35f
private const val SHEEN_END = 1.35f
private const val SHEEN_BAND = 0.38f

/** True where the AGSL path can run. Below API 33 the same band is drawn with a translated gradient. */
fun sheenUsesShader(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU

/**
 * Draws a metallic band behind this element's content, at [position] (0 = left edge, 1 = right edge; [SHEEN_HIDDEN] draws
 * nothing). [position] is read in the draw phase only, so an animated band never recomposes. Shader, paint and matrix are
 * created once per size; a frame allocates nothing. Clip first: the band fills the element's rectangle.
 */
fun Modifier.metallicSheen(
    color: Color,
    intensity: Float = 0.35f,
    useShader: Boolean = sheenUsesShader(),
    position: () -> Float,
): Modifier = drawWithCache {
    val tint = color.copy(alpha = color.alpha * intensity)
    val draw: DrawScope.(Float) -> Unit =
        if (useShader && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shaderSheen(size, tint) else gradientSheen(size, tint)
    onDrawBehind {
        val p = position()
        if (p != SHEEN_HIDDEN) draw(p)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun shaderSheen(size: Size, tint: Color): DrawScope.(Float) -> Unit {
    val shader = RuntimeShader(SHEEN_AGSL).apply {
        setFloatUniform("resolution", size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
        setFloatUniform("bandWidth", SHEEN_BAND)
        setColorUniform("tint", tint.toArgb())
    }
    val paint = Paint().apply {
        this.shader = shader
        blendMode = BlendMode.SrcOver
    }
    val rect = Rect(Offset.Zero, size)
    return { p ->
        shader.setFloatUniform("position", p)
        drawIntoCanvas { it.drawRect(rect, paint) }
    }
}

private fun gradientSheen(size: Size, tint: Color): DrawScope.(Float) -> Unit {
    val band = size.width * SHEEN_BAND
    val clear = tint.copy(alpha = 0f)
    val shader = LinearGradientShader(
        from = Offset(-band, 0f),
        to = Offset(band, band * 0.45f),
        colors = listOf(clear, tint, clear),
    )
    val matrix = Matrix()
    val paint = Paint().apply { this.shader = shader }
    val rect = Rect(Offset.Zero, size)
    return { p ->
        matrix.setTranslate(p * size.width, 0f)
        shader.setLocalMatrix(matrix)
        drawIntoCanvas { it.drawRect(rect, paint) }
    }
}

/**
 * One sweep of the sheen, played when [key] changes (not on first composition). Delight is reserved for real moments, so
 * callers key it on an achievement or an amount update. With reduced motion it never plays.
 */
@Stable
class SheenSweep internal constructor() {
    internal val progress = Animatable(SHEEN_HIDDEN)

    /** Current band position, for [metallicSheen]. */
    val position: Float get() = progress.value
}

@Composable
fun rememberSheenSweep(key: Any?): SheenSweep {
    val motion = LocalMotion.current
    val sweep = remember { SheenSweep() }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(key) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        if (!motion.decorativeEnabled) return@LaunchedEffect
        sweep.progress.snapTo(SHEEN_START)
        sweep.progress.animateTo(SHEEN_END, motion.spec(MotionToken.Sheen))
        sweep.progress.snapTo(SHEEN_HIDDEN)
    }
    return sweep
}
