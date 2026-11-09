package com.darshan.payflow.ui.history

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.components.AmountDecimals
import com.darshan.payflow.ui.designsystem.components.AmountText
import com.darshan.payflow.ui.designsystem.components.StatusKind
import com.darshan.payflow.ui.designsystem.components.StatusPill
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * The demo scratch card. A graphite foil is erased under the finger (a cleared stroke on an offscreen layer); past
 * [ScratchCoverage.REVEAL_AT] the rest falls away and a ring of sparkles opens from the middle. Underneath it says "Demo" and
 * that nothing is credited, from the first frame, for TalkBack too. TalkBack's activate reveals it without scratching.
 * Reduced motion keeps the scratch and drops the sparkles.
 */
@Composable
fun ScratchCard(reward: DemoReward, revealed: Boolean, onRevealed: () -> Unit, modifier: Modifier = Modifier) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val motion = LocalMotion.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val coverage = remember(reward.paymentId) { ScratchCoverage() }
    val path = remember(reward.paymentId) { Path() }
    var strokes by remember { mutableIntStateOf(0) }
    var played by remember(reward.paymentId) { mutableStateOf(revealed) }
    val foil = remember(reward.paymentId) { Animatable(if (revealed) 0f else 1f) }
    val sparkle = remember(reward.paymentId) { Animatable(if (revealed) 1f else 0f) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val brushPx = with(LocalDensity.current) { Sizes.scratchBrush.toPx() }
    val notify by rememberUpdatedState(onRevealed)
    val decorative = motion.decorativeEnabled
    val foilShown by remember(reward.paymentId) { androidx.compose.runtime.derivedStateOf { foil.value > 0f } }

    fun reveal() {
        if (played) return
        played = true
        coverage.clearAll()
        haptics.confirm()
        notify()
        scope.launch { foil.animateTo(0f, motion.spec(MotionToken.Small)) }
        if (decorative) scope.launch { sparkle.snapTo(0f); sparkle.animateTo(1f, motion.spec(MotionToken.Screen)) }
    }

    val amount = Money.formatInr(reward.amountPaise)
    val hiddenLabel = stringResource(R.string.reward_scratch)
    val revealLabel = stringResource(R.string.reward_scratch_action)
    val revealedLabel = stringResource(R.string.reward_revealed_description, amount)
    val disclaimer = stringResource(R.string.reward_disclaimer)
    val foilTop = colors.heroStart
    val foilBottom = colors.heroEnd
    val sheen = colors.sheen
    val spark = colors.accent

    Box(
        modifier
            .fillMaxWidth()
            .height(Sizes.scratchCard)
            .clip(Radii.largeShape)
            .onSizeChanged { cardSize = it }
            // The sheet is raised in dark and surface in light; the card sits a tone below it in both, with a hairline.
            .drawBehind { drawRect(if (colors.isDark) colors.surface else colors.bg) }
            .border(Hairline.width, colors.hairline, Radii.largeShape)
            .testTag(PayFlowTestTags.DETAIL_SCRATCH)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = if (played) revealedLabel else "$hiddenLabel. $disclaimer"
                if (!played) onClick(revealLabel) { reveal(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            StatusPill(StatusKind.Neutral, stringResource(R.string.reward_demo_tag), animate = false)
            Text(stringResource(R.string.reward_title), style = type.label, color = colors.textSecondary)
            AmountText(reward.amountPaise, style = type.display, color = colors.accent, decimals = AmountDecimals.WhenNonZero)
            Text(disclaimer, style = type.label, color = colors.textSecondary, textAlign = TextAlign.Center)
        }
        // Sparkles: a ring of four-point glints opening from the centre and fading. Positions are computed per frame from
        // fixed angles; nothing is allocated while it plays.
        Spacer(
            Modifier.matchParentSize().drawBehind {
                val p = sparkle.value
                if (p <= 0f || p >= 1f) return@drawBehind
                val center = Offset(size.width / 2, size.height / 2)
                val reach = size.minDimension * 0.62f * p
                val arm = Sizes.iconSmall.toPx() * (1f - p * 0.5f) / 2
                val stroke = Hairline.width.toPx() * 2
                for (i in 0 until SPARKS) {
                    val angle = (i * (360f / SPARKS) + 12f) * DEG
                    val c = Offset(center.x + cos(angle) * reach * (if (i % 2 == 0) 1f else 0.7f), center.y + sin(angle) * reach * (if (i % 2 == 0) 1f else 0.7f))
                    val a = 1f - p
                    drawLine(spark, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), stroke, StrokeCap.Round, alpha = a)
                    drawLine(spark, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), stroke, StrokeCap.Round, alpha = a)
                }
            },
        )
        // The foil. Offscreen, so the cleared strokes punch through to the reward underneath.
        if (foilShown) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = foil.value
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithCache {
                        val fill = Brush.linearGradient(listOf(foilTop, foilBottom, foilTop))
                        val glint = Brush.linearGradient(listOf(Color.Transparent, sheen.copy(alpha = 0.18f), Color.Transparent))
                        val ink = Stroke(width = brushPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        onDrawWithContent {
                            // Reading the stroke count ties this draw to new strokes; the path itself isn't observable.
                            if (strokes < 0) return@onDrawWithContent
                            drawRect(fill)
                            drawRect(glint)
                            drawContent()
                            drawPath(path, Color.Black, style = ink, blendMode = BlendMode.Clear)
                        }
                    }
                    // Scratching starts on touch, with no slop: the first contact already marks the foil. Moves are consumed
                    // so a scratch never scrolls the sheet.
                    .pointerInput(reward.paymentId) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (played) return@awaitEachGesture
                            val w = cardSize.width.toFloat()
                            val h = cardSize.height.toFloat()
                            path.moveTo(down.position.x, down.position.y)
                            path.lineTo(down.position.x + 0.1f, down.position.y)
                            coverage.scratch(down.position.x, down.position.y, brushPx / 2, w, h)
                            strokes++
                            down.consume()
                            while (!played) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val at = change.position
                                path.lineTo(at.x, at.y)
                                if (coverage.scratch(at.x, at.y, brushPx / 2, w, h) && coverage.cleared % TICK_EVERY == 0) haptics.tick()
                                strokes++
                                change.consume()
                                if (coverage.revealed) reveal()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(stringResource(R.string.reward_demo_tag), style = type.labelStrong, color = colors.onHero.copy(alpha = 0.72f))
                    Text(hiddenLabel, style = type.title, color = colors.onHero)
                }
            }
        }
    }
}

private const val SPARKS = 10
private const val DEG = (Math.PI / 180).toFloat()
private const val TICK_EVERY = 12
