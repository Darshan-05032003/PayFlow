package com.darshan.payflow.ui.designsystem.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.theme.PayFlowTheme

/** Small line drawings for empty and error states, all drawn in code. */
enum class IllustrationKind { EmptyLedger, NoResults, Offline, ErrorReceipt, NoMandates, NoRequests, CameraOff, Locked }

/**
 * Line art on a 120-unit canvas: outlines in the strong line colour, one quiet tinted panel, and one champagne accent per
 * drawing. Static on purpose: it should feel composed rather than busy. Decorative, so TalkBack skips it.
 */
@Composable
fun Illustration(kind: IllustrationKind, modifier: Modifier = Modifier, size: Dp = Sizes.illustration) {
    val colors = PayFlowTheme.colors
    val line = colors.textSecondary
    val accent = if (kind == IllustrationKind.ErrorReceipt || kind == IllustrationKind.Offline) colors.failed.strong else colors.accent
    val panel = colors.raised
    Spacer(
        modifier
            .size(size)
            .drawBehind {
                val u = this.size.minDimension / 120f
                val ink = Ink(u, line, accent, panel, Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
                when (kind) {
                    IllustrationKind.EmptyLedger -> ink.emptyLedger(this)
                    IllustrationKind.NoResults -> ink.noResults(this)
                    IllustrationKind.Offline -> ink.offline(this)
                    IllustrationKind.ErrorReceipt -> ink.errorReceipt(this)
                    IllustrationKind.NoMandates -> ink.noMandates(this)
                    IllustrationKind.NoRequests -> ink.noRequests(this)
                    IllustrationKind.CameraOff -> ink.cameraOff(this)
                    IllustrationKind.Locked -> ink.locked(this)
                }
            },
    )
}

private class Ink(val u: Float, val line: Color, val accent: Color, val panel: Color, val stroke: Stroke) {
    fun o(x: Float, y: Float) = Offset(x * u, y * u)

    fun DrawScope.seg(x1: Float, y1: Float, x2: Float, y2: Float, color: Color = line) =
        drawLine(color, o(x1, y1), o(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)

    fun DrawScope.card(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Color? = panel, color: Color = line) {
        if (fill != null) drawRoundRect(fill, o(x, y), Size(w * u, h * u), CornerRadius(r * u))
        drawRoundRect(color, o(x, y), Size(w * u, h * u), CornerRadius(r * u), style = stroke)
    }

    fun DrawScope.coin(cx: Float, cy: Float, r: Float) {
        drawCircle(accent.copy(alpha = 0.16f), r * u, o(cx, cy))
        drawCircle(accent, r * u, o(cx, cy), style = stroke)
        val s = r / 10f
        seg(cx - 4 * s, cy - 5 * s, cx + 4 * s, cy - 5 * s, accent)
        seg(cx - 4 * s, cy - 2 * s, cx + 4 * s, cy - 2 * s, accent)
        seg(cx - 4 * s, cy + 1 * s, cx + 3 * s, cy + 6 * s, accent)
        drawArc(accent, -90f, 180f, false, o(cx - 3 * s, cy - 5 * s), Size(5 * s * u, 6 * s * u), style = stroke)
    }

    fun emptyLedger(d: DrawScope) = with(d) {
        card(30f, 22f, 60f, 76f, 8f)
        seg(42f, 40f, 78f, 40f)
        seg(42f, 52f, 70f, 52f)
        seg(42f, 64f, 62f, 64f)
        coin(82f, 84f, 15f)
    }

    fun noResults(d: DrawScope) = with(d) {
        card(24f, 26f, 56f, 64f, 8f)
        seg(34f, 42f, 66f, 42f)
        seg(34f, 54f, 58f, 54f)
        drawCircle(panel, 18f * u, o(74f, 66f))
        drawCircle(accent, 18f * u, o(74f, 66f), style = stroke)
        seg(87f, 79f, 100f, 92f, accent)
    }

    fun offline(d: DrawScope) = with(d) {
        drawArc(line, 215f, 110f, false, o(24f, 28f), Size(72f * u, 72f * u), style = stroke)
        drawArc(line, 215f, 110f, false, o(38f, 44f), Size(44f * u, 44f * u), style = stroke)
        drawCircle(line, 4f * u, o(60f, 80f))
        seg(32f, 28f, 90f, 96f, accent)
    }

    fun errorReceipt(d: DrawScope) = with(d) {
        val path = Path().apply {
            moveTo(34f * u, 24f * u)
            lineTo(86f * u, 24f * u)
            lineTo(86f * u, 92f * u)
            var x = 86f
            var up = true
            while (x > 34f) {
                x -= 8.667f
                lineTo(x * u, (if (up) 86f else 92f) * u)
                up = !up
            }
            close()
        }
        drawPath(path, panel)
        drawPath(path, line, style = stroke)
        seg(60f, 38f, 60f, 60f, accent)
        drawCircle(accent, 2.5f * u, o(60f, 70f))
    }

    fun noMandates(d: DrawScope) = with(d) {
        card(28f, 30f, 64f, 60f, 8f)
        seg(28f, 46f, 92f, 46f)
        seg(44f, 22f, 44f, 36f)
        seg(76f, 22f, 76f, 36f)
        drawArc(accent, 20f, 290f, false, o(48f, 54f), Size(24f * u, 24f * u), style = stroke)
        seg(70f, 56f, 73f, 63f, accent)
        seg(73f, 63f, 66f, 64f, accent)
    }

    fun noRequests(d: DrawScope) = with(d) {
        card(24f, 38f, 72f, 50f, 8f)
        val flap = Path().apply {
            moveTo(26f * u, 42f * u)
            lineTo(60f * u, 68f * u)
            lineTo(94f * u, 42f * u)
        }
        drawPath(flap, line, style = stroke)
        drawCircle(accent.copy(alpha = 0.16f), 9f * u, o(92f, 38f))
        drawCircle(accent, 9f * u, o(92f, 38f), style = stroke)
    }

    fun cameraOff(d: DrawScope) = with(d) {
        val len = 14f
        for ((cx, cy, sx, sy) in Corners) {
            seg(cx, cy, cx + sx * len, cy, line)
            seg(cx, cy, cx, cy + sy * len, line)
        }
        drawCircle(panel, 14f * u, o(60f, 60f))
        drawCircle(line, 14f * u, o(60f, 60f), style = stroke)
        seg(36f, 36f, 84f, 84f, accent)
    }

    fun locked(d: DrawScope) = with(d) {
        drawArc(line, 180f, 180f, false, o(44f, 28f), Size(32f * u, 36f * u), style = stroke)
        seg(44f, 46f, 44f, 54f)
        seg(76f, 46f, 76f, 54f)
        card(34f, 52f, 52f, 40f, 8f)
        drawCircle(accent, 5f * u, o(60f, 68f))
        seg(60f, 72f, 60f, 80f, accent)
    }

    private companion object {
        val Corners = listOf(
            Corner(26f, 26f, 1f, 1f),
            Corner(94f, 26f, -1f, 1f),
            Corner(26f, 94f, 1f, -1f),
            Corner(94f, 94f, -1f, -1f),
        )
    }

    private data class Corner(val x: Float, val y: Float, val sx: Float, val sy: Float)
}
