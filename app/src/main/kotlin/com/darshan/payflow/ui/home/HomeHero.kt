package com.darshan.payflow.ui.home

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import com.darshan.payflow.R
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.SandboxBadgeIfActive
import com.darshan.payflow.ui.designsystem.components.AmountOdometer
import com.darshan.payflow.ui.designsystem.components.PayFlowIconButton
import com.darshan.payflow.ui.designsystem.components.PayFlowSurface
import com.darshan.payflow.ui.designsystem.components.SkeletonBlock
import com.darshan.payflow.ui.designsystem.components.SkeletonGroup
import com.darshan.payflow.ui.designsystem.components.SurfaceLevel
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.modifiers.SHEEN_HIDDEN
import com.darshan.payflow.ui.designsystem.modifiers.metallicSheen
import com.darshan.payflow.ui.designsystem.modifiers.rememberSheenSweep
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlin.random.Random

/**
 * Where the header's parts rest and dock, as states read only in layer lambdas: a scroll frame moves layers and never recomposes.
 * The one exception is [docked], which flips once at the end of the collapse.
 */
@Stable
internal class HomeHeaderState(private val scroll: ScrollState) {
    var rangePx by mutableIntStateOf(0)
    var topInsetPx by mutableFloatStateOf(0f)
    var barPx by mutableFloatStateOf(0f)
    var labelRestY by mutableFloatStateOf(0f)
    var labelHeight by mutableFloatStateOf(0f)
    var amountRestY by mutableFloatStateOf(0f)
    var amountHeight by mutableFloatStateOf(0f)
    var dockScale by mutableFloatStateOf(1f)

    val progress: Float get() = HomeHeaderLogic.progress(scroll.value, rangePx)
    val collapsed: Float get() = HomeHeaderLogic.collapsedPx(scroll.value, rangePx)
    val pin: Float get() = HomeHeaderLogic.pinOffset(scroll.value, rangePx)
    val docked by derivedStateOf { HomeHeaderLogic.isDocked(progress) }

    private val labelDockY: Float get() = HomeHeaderLogic.labelDockY(topInsetPx, barPx, labelHeight, amountHeight, dockScale)

    val labelTranslation: Float get() = HomeHeaderLogic.dockTranslation(progress, labelRestY, labelDockY, rangePx)
    val amountTranslation: Float get() = HomeHeaderLogic.dockTranslation(progress, amountRestY, labelDockY + labelHeight, rangePx)
}

/** What the hero knows about the month. */
internal sealed interface HeroContent {
    data object Loading : HeroContent
    data object Unavailable : HeroContent
    data class Known(val month: HistorySummary) : HeroContent
}

/**
 * The graphite header, full-bleed under the status bar. Scrolling collapses it into the top bar: the wordmark gives way, the
 * label and amount travel up and the amount scales down, the eye and any sandbox badge stay where they are. Touches on it stop
 * here, so nothing scrolled beneath the docked bar can be tapped through it.
 */
@Composable
internal fun HomeHero(
    header: HomeHeaderState,
    content: HeroContent,
    caption: String,
    amountsHidden: Boolean,
    memory: HomeEntranceMemory,
    onToggleAmounts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val density = LocalDensity.current
    val topInsetPx = WindowInsets.statusBars.getTop(density).toFloat()
    val barPx = with(density) { Sizes.topBarHeight.toPx() }
    val heroFont = type.hero.fontSize.value
    val dockFont = type.amount.fontSize.value
    header.topInsetPx = topInsetPx
    header.barPx = barPx
    header.dockScale = HomeHeaderLogic.dockScale(heroFont, dockFont)

    val sent = (content as? HeroContent.Known)?.month?.sentPaise
    // Rolls only from a total the user has already seen. The first total after loading appears as it is: counting up from ₹0
    // would show a number that was never true.
    var lastShown by remember { mutableStateOf(memory.lastShownSentPaise) }
    var rolls by remember { mutableIntStateOf(0) }
    if (sent != null) {
        LaunchedEffect(sent) {
            if (HomeHeaderLogic.rollsTo(lastShown, sent)) rolls++
            lastShown = sent
            memory.lastShownSentPaise = sent
        }
    }
    val shownSent = HomeHeaderLogic.shownTotal(lastShown, sent)
    val sweep = rememberSheenSweep(rolls)

    PayFlowSurface(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { header.rangePx = (it.height - (topInsetPx + barPx).toInt()).coerceAtLeast(0) }
            .graphicsLayer { translationY = header.pin }
            .testTag(PayFlowTestTags.HOME_HERO)
            .pointerInput(Unit) {},
        level = SurfaceLevel.Hero,
        shape = Radii.bottomLargeShape,
        hairline = false,
    ) {
        Spacer(
            Modifier
                .matchParentSize()
                .metallicSheen(colors.sheen, intensity = colors.sheenStrength, position = { if (amountsHidden) SHEEN_HIDDEN else sweep.position }),
        )
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            HeroTopRow(header, amountsHidden, onToggleAmounts)
            Text(
                stringResource(R.string.home_sent_this_month),
                style = type.label,
                color = colors.onHero,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xs)
                    .onPlaced {
                        header.labelRestY = it.positionInParent().y
                        header.labelHeight = it.size.height.toFloat()
                    }
                    .graphicsLayer { translationY = header.labelTranslation },
            )
            HeroAmount(header, content, shownSent, amountsHidden, memory, onToggleAmounts)
            HeroDetails(header, content, caption)
            // Room for the Scan & Pay action, which sits across the header's bottom edge.
            Spacer(Modifier.height(Sizes.heroAction / 2 + Spacing.xl))
        }
    }
}

@Composable
private fun HeroTopRow(header: HomeHeaderState, amountsHidden: Boolean, onToggleAmounts: () -> Unit) {
    val colors = PayFlowTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(Sizes.topBarHeight)
            .zIndex(1f)
            .graphicsLayer { translationY = header.collapsed }
            .padding(start = Spacing.screen, end = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.app_name),
            style = PayFlowTheme.type.amount,
            color = colors.onHero,
            maxLines = 1,
            modifier = Modifier.weight(1f).graphicsLayer { alpha = HomeHeaderLogic.wordmarkAlpha(header.progress) },
        )
        SandboxBadgeIfActive(Modifier.padding(end = Spacing.xxs))
        PayFlowIconButton(
            icon = if (amountsHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = stringResource(if (amountsHidden) R.string.home_show_amounts else R.string.home_hide_amounts),
            onClick = onToggleAmounts,
            tint = colors.onHero,
            modifier = Modifier.testTag(PayFlowTestTags.HOME_HIDE_AMOUNTS),
        )
    }
}

@Composable
private fun HeroAmount(header: HomeHeaderState, content: HeroContent, sentPaise: Long, amountsHidden: Boolean, memory: HomeEntranceMemory, onToggleAmounts: () -> Unit) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val haptics = LocalHaptics.current
    val interaction = remember { MutableInteractionSource() }
    val place = Modifier
        .fillMaxWidth()
        .padding(horizontal = Spacing.screen)
        .onPlaced {
            header.amountRestY = it.positionInParent().y
            header.amountHeight = it.size.height.toFloat()
        }
        .graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0f)
            translationY = header.amountTranslation
            val scale = HomeHeaderLogic.scaleAt(header.progress, header.dockScale)
            scaleX = scale
            scaleY = scale
        }
    when (content) {
        HeroContent.Loading -> SkeletonGroup(place) {
            // The real amount's height, drawn invisibly, so the header doesn't jump when the number arrives.
            Box(Modifier.fillMaxWidth(HERO_SKELETON_WIDTH)) {
                AmountOdometer(0, style = type.hero, color = Color.Transparent)
                SkeletonBlock(Modifier.matchParentSize(), Radii.smallShape, base = colors.onHero.copy(alpha = HERO_SKELETON_BASE), highlight = colors.onHero.copy(alpha = HERO_SKELETON_HIGHLIGHT))
            }
        }
        HeroContent.Unavailable -> Box(place, contentAlignment = Alignment.CenterStart) {
            AmountOdometer(0, style = type.hero, color = Color.Transparent, modifier = Modifier.clearAndSetSemantics {})
            Text(stringResource(R.string.home_hero_unavailable), style = type.display, color = colors.onHero)
        }
        is HeroContent.Known -> VeiledAmount(
            paise = sentPaise,
            hidden = amountsHidden,
            style = type.hero,
            random = memory.scrambleRandom,
            modifier = place
                .testTag(PayFlowTestTags.HOME_HERO_AMOUNT)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                    haptics.light()
                    onToggleAmounts()
                },
        )
    }
}

@Composable
private fun HeroDetails(header: HomeHeaderState, content: HeroContent, caption: String) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    Column(
        Modifier
            .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs)
            .graphicsLayer { alpha = HomeHeaderLogic.detailsAlpha(header.progress) },
    ) {
        when (content) {
            HeroContent.Loading -> SkeletonGroup {
                Box(Modifier.fillMaxWidth(HERO_SKELETON_LINE_WIDTH)) {
                    Text("", style = type.body)
                    SkeletonBlock(Modifier.matchParentSize().padding(vertical = Spacing.xxs), Radii.pillShape, base = colors.onHero.copy(alpha = HERO_SKELETON_BASE), highlight = colors.onHero.copy(alpha = HERO_SKELETON_HIGHLIGHT))
                }
            }
            // Keeps the line's height: the header stays the same size in every state.
            HeroContent.Unavailable -> Text("", style = type.body)
            is HeroContent.Known -> {
                val parts = content.month.heroCounts().map { count ->
                    when (count) {
                        is HeroCount.Paid -> stringResource(R.string.home_count_paid, count.count)
                        is HeroCount.Pending -> stringResource(R.string.home_count_pending, count.count)
                        is HeroCount.Failed -> stringResource(R.string.home_count_failed, count.count)
                    }
                }
                Text(
                    if (parts.isEmpty()) stringResource(R.string.home_hero_none) else parts.joinToString(" · "),
                    style = type.body,
                    color = colors.onHero,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(caption, style = type.label, color = colors.onHero.copy(alpha = 0.72f), modifier = Modifier.padding(top = Spacing.xxs))
    }
}

/**
 * The amount with its hide and show animation: the content blurs and fades while a random, mask-length scramble takes over,
 * swaps at the peak where nothing of it can be read, then clears. Interruptible: a toggle mid-way simply heads for the new target.
 * Reduced motion keeps only the short fade. The swap never waits on anything but the veil covering it.
 */
@Composable
private fun VeiledAmount(paise: Long, hidden: Boolean, style: TextStyle, random: Random, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val colors = PayFlowTheme.colors
    var shownHidden by remember { mutableStateOf(hidden) }
    val phase = remember { Animatable(0f) }
    LaunchedEffect(hidden) {
        if (shownHidden != hidden) {
            phase.animateTo(AmountVeilLogic.COVERED, motion.spec(MotionToken.Micro))
            shownHidden = hidden
        }
        if (phase.value != 0f) {
            phase.animateTo(AmountVeilLogic.DONE, motion.spec(MotionToken.Small))
            phase.snapTo(0f)
        }
    }
    val active by remember { derivedStateOf { phase.value != 0f } }
    val blurPx = with(LocalDensity.current) { Spacing.xxs.toPx() }
    val blurs = remember(blurPx, motion) { blurSteps(blurPx, enabled = !motion.reduced) }
    Box(
        modifier.graphicsLayer {
            renderEffect = blurs[AmountVeilLogic.blurLevel(AmountVeilLogic.veil(phase.value), blurs.size)]
        },
    ) {
        AmountOdometer(
            paise = paise,
            hidden = shownHidden,
            style = style,
            color = colors.onHero,
            modifier = Modifier.graphicsLayer { alpha = 1f - AmountVeilLogic.veil(phase.value) },
        )
        if (active && !motion.reduced) {
            val measurer = rememberTextMeasurer(cacheSize = AmountVeilLogic.FRAMES)
            val frames = remember(style) { AmountVeilLogic.scrambleFrames(random).map { measurer.measure(it, style) } }
            Spacer(
                Modifier.matchParentSize().drawBehind {
                    drawText(frames[AmountVeilLogic.frameAt(phase.value)], color = colors.onHero, alpha = AmountVeilLogic.veil(phase.value))
                },
            )
        }
    }
}

private const val BLUR_STEPS = 5
private const val HERO_SKELETON_WIDTH = 0.55f
private const val HERO_SKELETON_LINE_WIDTH = 0.4f
private const val HERO_SKELETON_BASE = 0.10f
private const val HERO_SKELETON_HIGHLIGHT = 0.22f

/** Built once, so a frame only picks one. Blur exists from API 31; below that the veil is the fade and the scramble. */
private fun blurSteps(stepPx: Float, enabled: Boolean, sdkInt: Int = Build.VERSION.SDK_INT): List<RenderEffect?> =
    List(BLUR_STEPS) { step ->
        if (step == 0 || !enabled || sdkInt < Build.VERSION_CODES.S) null else BlurEffect(step * stepPx, step * stepPx, TileMode.Decal)
    }
