package com.darshan.payflow.ui.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.modifiers.pressScale
import com.darshan.payflow.ui.designsystem.modifiers.rememberPressFeedback
import com.darshan.payflow.ui.designsystem.modifiers.trackPress
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlin.math.abs

sealed interface BottomNavChange {
    data class Changed(val from: Int, val to: Int) : BottomNavChange

    /** Tapping the current tab: screens scroll to top. */
    data class Reselected(val index: Int) : BottomNavChange
    data object Ignored : BottomNavChange
}

/** The nav bar's pure state holder. All selection rules and pill geometry live here and are tested on the JVM. */
class BottomNavLogic(val count: Int, initial: Int) {
    init {
        require(count > 0) { "A bottom nav needs at least one item" }
        require(initial in 0 until count) { "Initial selection $initial is outside 0 until $count" }
    }

    var selected: Int = initial
        private set

    fun select(index: Int): BottomNavChange {
        if (index !in 0 until count) return BottomNavChange.Ignored
        if (index == selected) return BottomNavChange.Reselected(index)
        val from = selected
        selected = index
        return BottomNavChange.Changed(from, index)
    }

    companion object {
        /** How selected item [index] looks while the pill is at [pillPosition]: 1 under the pill, 0 one cell away. */
        fun itemProgress(pillPosition: Float, index: Int): Float = (1f - abs(pillPosition - index)).coerceIn(0f, 1f)

        fun pillLeft(position: Float, itemWidth: Float, pillWidth: Float): Float = position * itemWidth + (itemWidth - pillWidth) / 2f
    }
}

/** Compose wrapper for [BottomNavLogic]. Survives rotation and process death through `rememberSaveable`. */
@Stable
class BottomNavState internal constructor(count: Int, initial: Int) {
    private val logic = BottomNavLogic(count, initial)
    var selected by mutableIntStateOf(initial)
        private set

    fun select(index: Int): BottomNavChange {
        val change = logic.select(index)
        selected = logic.selected
        return change
    }
}

@Composable
fun rememberBottomNavState(count: Int, initial: Int = 0): BottomNavState {
    var saved by rememberSaveable { mutableIntStateOf(initial.coerceIn(0, count - 1)) }
    val state = remember(count) { BottomNavState(count, saved.coerceIn(0, count - 1)) }
    LaunchedEffect(state.selected) { saved = state.selected }
    return state
}

/** Draws a nav icon. [progress] 0 is the idle outline, 1 the selected form. Implementations must not allocate. */
@Stable
fun interface NavGlyph {
    fun DrawScope.draw(progress: Float, line: Color, fill: Color)
}

@Immutable
data class NavItem(val key: String, val label: String, val glyph: NavGlyph, val testTag: String? = null)

/**
 * The floating dock: a raised bar with a pill that slides between items and icons that morph as it arrives.
 *
 * Everything per-frame (pill position, icon morph, label colour) is read in the draw phase from one spring, so a tab change
 * recomposes the bar once rather than on every frame. One tick haptic per real change. Reduced motion moves the pill at once.
 */
@Composable
fun PayFlowBottomNav(
    items: List<NavItem>,
    state: BottomNavState,
    onChange: (BottomNavChange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PayFlowTheme.colors
    val motion = LocalMotion.current
    val haptics = LocalHaptics.current
    val pill = remember { Animatable(state.selected.toFloat()) }
    LaunchedEffect(state.selected, motion) {
        if (motion.reduced) pill.snapTo(state.selected.toFloat()) else pill.animateTo(state.selected.toFloat(), motion.spec(MotionToken.Bounce))
    }
    val count = items.size
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Sizes.navBarHeight)
                .drawBehind {
                    val radius = Radii.large.toPx()
                    drawRoundRect(colors.raised, cornerRadius = CornerRadius(radius))
                    val inset = PillInset.toPx()
                    val itemWidth = size.width / count
                    val pillWidth = itemWidth - inset * 2
                    drawRoundRect(
                        colors.accentTint,
                        topLeft = Offset(BottomNavLogic.pillLeft(pill.value, itemWidth, pillWidth), inset),
                        size = Size(pillWidth, size.height - inset * 2),
                        cornerRadius = CornerRadius(Radii.medium.toPx()),
                    )
                }
                .border(Hairline.width, colors.hairline, Radii.largeShape)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val press = rememberPressFeedback()
                val interaction = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(if (item.testTag != null) Modifier.testTag(item.testTag) else Modifier)
                        .trackPress(press)
                        .selectable(
                            selected = state.selected == index,
                            role = Role.Tab,
                            interactionSource = interaction,
                            indication = null,
                        ) {
                            val change = state.select(index)
                            if (change is BottomNavChange.Changed) haptics.tick()
                            onChange(change)
                        }
                        .pressScale(press, pressedScale = 0.92f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    val idle = colors.textSecondary
                    val active = colors.onAccentTint
                    Spacer(
                        Modifier
                            .size(Sizes.icon)
                            .drawWithCache {
                                val unit = this.size.minDimension / NavGlyphs.GRID
                                onDrawBehind {
                                    val p = BottomNavLogic.itemProgress(pill.value, index)
                                    scale(unit, unit, pivot = Offset.Zero) {
                                        with(item.glyph) { draw(p, lerp(idle, active, p), active) }
                                    }
                                }
                            },
                    )
                    BasicText(
                        item.label,
                        style = PayFlowTheme.type.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = { lerp(idle, active, BottomNavLogic.itemProgress(pill.value, index)) },
                    )
                }
            }
        }
    }
}

private val PillInset = 6.dp

/** PayFlow's own nav icons, on a 24-unit grid. Paths are built once. Each morph only transforms and fades them. */
object NavGlyphs {
    const val GRID = 24f
    private const val LINE = 1.7f
    private val line = Stroke(width = LINE, cap = StrokeCap.Round, join = StrokeJoin.Round)

    val Home = NavGlyph { progress, stroke, fill ->
        drawPath(HomePaths.fill, fill, alpha = progress)
        translate(top = -0.6f * progress) { drawPath(HomePaths.roof, stroke, style = line) }
        drawPath(HomePaths.walls, stroke, style = line)
    }

    /** Brackets close in on the code as it selects, and the centre mark fills. */
    val Scan = NavGlyph { progress, stroke, fill ->
        val inward = 1.4f * progress
        translate(inward, inward) { drawPath(ScanPaths.topLeft, stroke, style = line) }
        translate(-inward, inward) { drawPath(ScanPaths.topRight, stroke, style = line) }
        translate(inward, -inward) { drawPath(ScanPaths.bottomLeft, stroke, style = line) }
        translate(-inward, -inward) { drawPath(ScanPaths.bottomRight, stroke, style = line) }
        val r = 2.2f + 1.2f * progress
        drawCircle(fill, radius = r, center = ScanPaths.centre, alpha = 0.35f + 0.65f * progress)
    }

    /** The clock hands sweep round to now. */
    val History = NavGlyph { progress, stroke, fill ->
        drawCircle(fill, radius = 8.6f, center = HistoryPaths.centre, alpha = 0.22f * progress)
        drawCircle(stroke, radius = 8.6f, center = HistoryPaths.centre, style = line)
        rotate(-360f * (1f - progress), pivot = HistoryPaths.centre) { drawPath(HistoryPaths.hands, stroke, style = line) }
    }

    val Profile = NavGlyph { progress, stroke, fill ->
        drawCircle(fill, radius = 3.8f, center = ProfilePaths.head, alpha = progress)
        drawCircle(stroke, radius = 3.8f, center = ProfilePaths.head, style = line)
        drawPath(ProfilePaths.shoulders, fill, alpha = progress)
        drawPath(ProfilePaths.shoulders, stroke, style = line)
    }
}

private object HomePaths {
    val roof = Path().apply {
        moveTo(3.5f, 11.5f)
        lineTo(12f, 4f)
        lineTo(20.5f, 11.5f)
    }
    val walls = Path().apply {
        moveTo(5.8f, 9.8f)
        lineTo(5.8f, 20f)
        lineTo(18.2f, 20f)
        lineTo(18.2f, 9.8f)
        moveTo(10f, 20f)
        lineTo(10f, 14.5f)
        lineTo(14f, 14.5f)
        lineTo(14f, 20f)
    }
    val fill = Path().apply {
        fillType = PathFillType.EvenOdd
        moveTo(12f, 4.5f)
        lineTo(18.2f, 10f)
        lineTo(18.2f, 20f)
        lineTo(5.8f, 20f)
        lineTo(5.8f, 10f)
        close()
        moveTo(10f, 20f)
        lineTo(14f, 20f)
        lineTo(14f, 14.5f)
        lineTo(10f, 14.5f)
        close()
    }
}

private object ScanPaths {
    val centre = Offset(12f, 12f)
    val topLeft = corner(3.5f, 8.5f, 3.5f, 3.5f, 8.5f, 3.5f)
    val topRight = corner(15.5f, 3.5f, 20.5f, 3.5f, 20.5f, 8.5f)
    val bottomLeft = corner(3.5f, 15.5f, 3.5f, 20.5f, 8.5f, 20.5f)
    val bottomRight = corner(15.5f, 20.5f, 20.5f, 20.5f, 20.5f, 15.5f)

    private fun corner(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) = Path().apply {
        moveTo(ax, ay)
        lineTo(bx, by)
        lineTo(cx, cy)
    }
}

private object HistoryPaths {
    val centre = Offset(12f, 12f)
    val hands = Path().apply {
        moveTo(12f, 7.2f)
        lineTo(12f, 12f)
        lineTo(15.4f, 14f)
    }
}

private object ProfilePaths {
    val head = Offset(12f, 8f)
    val shoulders = Path().apply {
        moveTo(4.8f, 20.5f)
        cubicTo(5.2f, 16.4f, 8f, 14.4f, 12f, 14.4f)
        cubicTo(16f, 14.4f, 18.8f, 16.4f, 19.2f, 20.5f)
        close()
    }
}
