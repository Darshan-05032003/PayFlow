package com.darshan.payflow.ui.designsystem.modifiers

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import java.util.Random

/** Static per-pixel noise. Output is premultiplied grey. */
private const val GRAIN_AGSL = """
uniform float strength;
float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
half4 main(float2 p) {
    float n = hash(floor(p));
    return half4(half3(n * strength), strength);
}
"""

private const val TILE_SIZE = 128
private const val TILE_SEED = 7L

/** Below API 33: the same noise baked once per process into a small tile. */
private val grainTile: Bitmap by lazy {
    val random = Random(TILE_SEED)
    val pixels = IntArray(TILE_SIZE * TILE_SIZE) {
        val v = random.nextInt(256)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    Bitmap.createBitmap(pixels, TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
}

/**
 * Fine film grain over this element's background (3 % on dark surfaces, off in light theme by token). The texture never
 * changes, so nothing animates and a frame allocates nothing.
 *
 * Use it on hero surfaces, not across whole lists: on software renderers per-pixel noise on a scrolling screen is costly.
 */
fun Modifier.filmGrain(strength: Float, useShader: Boolean = sheenUsesShader()): Modifier =
    if (strength <= 0f) {
        this
    } else {
        drawWithCache {
            val paint = if (useShader && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shaderPaint(strength) else tilePaint(strength)
            val rect = Rect(Offset.Zero, Size(size.width, size.height))
            onDrawBehind { drawIntoCanvas { it.drawRect(rect, paint) } }
        }
    }

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun shaderPaint(strength: Float) = Paint().apply {
    shader = RuntimeShader(GRAIN_AGSL).apply { setFloatUniform("strength", strength) }
}

private fun tilePaint(strength: Float) = Paint().apply {
    alpha = strength
    shader = ImageShader(grainTile.asImageBitmap(), TileMode.Repeated, TileMode.Repeated)
}
