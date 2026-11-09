package com.darshan.payflow.ui.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import com.darshan.payflow.R
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.motion.PayFlowMotion
import com.darshan.payflow.ui.designsystem.tokens.Geist
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Prepared glyphs for one style and scale, so a rolling frame only translates ready-made text. */
@Immutable
private class DigitAtlas(val digits: List<TextLayoutResult>, val cellWidth: Int, val cellHeight: Int) {
    val baseline: Float get() = digits[0].firstBaseline
}

private const val DIGIT_STAGGER_MS = 16

private fun buildAtlas(measurer: TextMeasurer, style: TextStyle): DigitAtlas {
    val layouts = List(10) { measurer.measure(it.toString(), style, constraints = Constraints()) }
    return DigitAtlas(layouts, ceil(layouts.maxOf { it.size.width }.toFloat()).toInt(), layouts.maxOf { it.size.height })
}

private fun naturalWidth(measurer: TextMeasurer, slots: List<OdometerSlot>, style: TextStyle, signStyle: TextStyle, atlas: DigitAtlas): Float =
    slots.sumOf { slot ->
        when (slot) {
            is OdometerSlot.Digit -> atlas.cellWidth
            is OdometerSlot.Sign -> measurer.measure(slot.char.toString(), signStyle).size.width
            else -> measurer.measure(slot.char.toString(), style).size.width
        }
    }.toFloat()

/**
 * The amount display: each digit rolls to its new value in the direction the amount moved.
 *
 * - Indian grouping up to ₹99,99,99,999.99; larger values clamp.
 * - Shrinks to fit its width in stable steps, down to [minScale].
 * - Signs come from Geist; digits, separators and ₹ from Newsreader.
 * - Digit positions are read in the draw phase: a roll never recomposes. Reduced motion snaps to the new value.
 * - [hidden] shows the same dots for every value and says "Amount hidden" to TalkBack.
 */
@Composable
fun AmountOdometer(
    paise: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = PayFlowTheme.type.hero,
    color: Color = LocalContentColor.current,
    decimals: AmountDecimals = AmountDecimals.WhenNonZero,
    sign: AmountSign = AmountSign.NegativeOnly,
    hidden: Boolean = false,
    minScale: Float = 0.4f,
) {
    val motion = LocalMotion.current
    val slots = remember(paise, decimals, sign, hidden) { if (hidden) OdometerLogic.hiddenSlots() else OdometerLogic.slots(paise, decimals, sign) }
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val hiddenLabel = stringResource(R.string.ds_amount_hidden)
    val spoken = if (hidden) hiddenLabel else remember(paise, decimals) { OdometerLogic.spoken(paise, decimals) }
    val visibleText = if (hidden) hiddenLabel else remember(paise, decimals, sign) { OdometerLogic.text(paise, decimals, sign) }
    val previous = remember { longArrayOf(paise) }
    val settled = remember { booleanArrayOf(false) }
    val direction = OdometerLogic.direction(previous[0], paise)
    SideEffect {
        previous[0] = paise
        settled[0] = true
    }

    BoxWithConstraints(
        modifier.clearAndSetSemantics {
            contentDescription = spoken
            text = AnnotatedString(visibleText)
        },
    ) {
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else Float.MAX_VALUE
        val baseSign = style.copy(fontFamily = Geist, fontWeight = FontWeight.Normal)
        val baseAtlas = remember(style, measurer) { buildAtlas(measurer, style) }
        val width = remember(slots, style, measurer) { naturalWidth(measurer, slots, style, baseSign, baseAtlas) }
        val scale = OdometerLogic.fitScale(width, available, minScale)
        val density = LocalDensity.current
        val scaledStyle = remember(style, scale) {
            if (scale == 1f) style else style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale)
        }
        val signStyle = remember(scaledStyle) { scaledStyle.copy(fontFamily = Geist, fontWeight = FontWeight.Normal) }
        val atlas = remember(scaledStyle, measurer) { if (scale == 1f) baseAtlas else buildAtlas(measurer, scaledStyle) }
        val cellSize = with(density) { Modifier.size(atlas.cellWidth.toDp(), atlas.cellHeight.toDp()) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            val total = slots.size
            slots.forEachIndexed { index, slot ->
                key(slot.key) {
                    when (slot) {
                        is OdometerSlot.Digit -> DigitCell(
                            digit = slot.value,
                            direction = direction,
                            delayMs = (total - 1 - index) * DIGIT_STAGGER_MS,
                            startFromZero = settled[0],
                            atlas = atlas,
                            color = color,
                            motion = motion,
                            modifier = cellSize,
                        )
                        is OdometerSlot.Sign -> {
                            // The two fonts place their baselines differently in the same line box: line the sign up with the digits.
                            val lift = remember(slot.char, signStyle, atlas) {
                                (atlas.baseline - measurer.measure(slot.char.toString(), signStyle).firstBaseline).roundToInt()
                            }
                            BasicText(slot.char.toString(), Modifier.offset { IntOffset(0, lift) }, style = signStyle.copy(color = color))
                        }
                        else -> BasicText(slot.char.toString(), style = scaledStyle.copy(color = color))
                    }
                }
            }
        }
    }
}

@Composable
private fun DigitCell(
    digit: Int,
    direction: RollDirection,
    delayMs: Int,
    startFromZero: Boolean,
    atlas: DigitAtlas,
    color: Color,
    motion: PayFlowMotion,
    modifier: Modifier,
) {
    val position = remember { Animatable(if (startFromZero && !motion.reduced) 0f else digit.toFloat()) }
    LaunchedEffect(digit, motion) {
        if (motion.reduced) {
            position.snapTo(digit.toFloat())
            return@LaunchedEffect
        }
        val target = OdometerLogic.nextPosition(position.value, digit, direction)
        if (target == position.value) return@LaunchedEffect
        delay(delayMs.toLong())
        position.animateTo(target, motion.spec(MotionToken.Settle))
        // Keeps the float small however many updates arrive.
        position.snapTo(digit.toFloat())
    }
    Spacer(
        modifier
            .clipToBounds()
            .drawBehind {
                val p = position.value
                val whole = floor(p)
                val fraction = p - whole
                val lower = atlas.digits[OdometerLogic.digitAt(p)]
                val h = atlas.cellHeight.toFloat()
                val w = atlas.cellWidth.toFloat()
                drawText(lower, color = color, topLeft = Offset((w - lower.size.width) / 2f, -fraction * h))
                if (fraction > 0f) {
                    val upper = atlas.digits[OdometerLogic.digitAt(p + 1f)]
                    drawText(upper, color = color, topLeft = Offset((w - upper.size.width) / 2f, (1f - fraction) * h))
                }
            },
    )
}

/**
 * A static amount for rows and cards: same grouping and Geist sign as [AmountOdometer], no animation, cheap in long lists.
 * [hidden] shows the same mask as the odometer and says "Amount hidden" to TalkBack.
 */
@Composable
fun AmountText(
    paise: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = PayFlowTheme.type.amount,
    color: Color = Color.Unspecified,
    decimals: AmountDecimals = AmountDecimals.WhenNonZero,
    sign: AmountSign = AmountSign.NegativeOnly,
    hidden: Boolean = false,
) {
    val text = remember(paise, decimals, sign, hidden) {
        buildAnnotatedString {
            for (slot in if (hidden) OdometerLogic.hiddenSlots() else OdometerLogic.slots(paise, decimals, sign)) {
                if (slot is OdometerSlot.Sign) {
                    withStyle(SpanStyle(fontFamily = Geist, fontWeight = FontWeight.Normal)) { append(slot.char) }
                } else {
                    append(slot.char)
                }
            }
        }
    }
    val hiddenLabel = stringResource(R.string.ds_amount_hidden)
    val spoken = if (hidden) hiddenLabel else remember(paise, decimals) { OdometerLogic.spoken(paise, decimals) }
    Text(
        text,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = spoken
            this.text = text
        },
        style = style,
        color = color,
        maxLines = 1,
    )
}
