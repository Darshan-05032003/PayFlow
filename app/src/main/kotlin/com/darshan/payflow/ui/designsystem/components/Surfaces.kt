package com.darshan.payflow.ui.designsystem.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.darshan.payflow.R
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.modifiers.filmGrain
import com.darshan.payflow.ui.designsystem.modifiers.pressScale
import com.darshan.payflow.ui.designsystem.modifiers.rememberPressFeedback
import com.darshan.payflow.ui.designsystem.modifiers.trackPress
import com.darshan.payflow.ui.designsystem.tokens.AurumDark
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.PayFlowColors
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.LocalPayFlowColors
import com.darshan.payflow.ui.theme.PayFlowTheme

/** Depth layers: flat on the background, one card up, one more for things on cards, or the graphite hero with grain. */
enum class SurfaceLevel { Flat, Card, Raised, Hero }

/**
 * The standard card: a tone step plus a 1dp hairline, no shadow. [SurfaceLevel.Hero] adds the graphite gradient and film
 * grain. Sets the content colour for its level.
 *
 * A hero is graphite in both themes, so whatever sits on it (buttons, pills, avatars) reads the dark palette: a light-theme
 * graphite button on a graphite card would vanish, and a pale pill would glare. The content colour is [PayFlowColors.onHero].
 */
@Composable
fun PayFlowSurface(
    modifier: Modifier = Modifier,
    level: SurfaceLevel = SurfaceLevel.Card,
    shape: Shape = Radii.mediumShape,
    hairline: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = PayFlowTheme.colors
    val heroBrush = remember(colors) { Brush.verticalGradient(listOf(colors.heroStart, colors.heroEnd)) }
    val contentColor = if (level == SurfaceLevel.Hero) colors.onHero else colors.text
    Box(
        modifier
            .clip(shape)
            .drawBehind {
                when (level) {
                    SurfaceLevel.Flat -> Unit
                    SurfaceLevel.Card -> drawRect(colors.surface)
                    SurfaceLevel.Raised -> drawRect(colors.raised)
                    SurfaceLevel.Hero -> drawRect(heroBrush)
                }
            }
            .then(if (level == SurfaceLevel.Hero) Modifier.filmGrain(colors.grainAlpha.coerceAtLeast(MIN_HERO_GRAIN)) else Modifier)
            .then(if (hairline) Modifier.border(Hairline.width, colors.hairline.takeUnless { level == SurfaceLevel.Hero } ?: colors.sheen.copy(alpha = 0.12f), shape) else Modifier),
    ) {
        if (level == SurfaceLevel.Hero) {
            CompositionLocalProvider(LocalPayFlowColors provides HeroPalette, LocalContentColor provides contentColor) { content() }
        } else {
            CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
        }
    }
}

/** What content on a hero reads: the dark palette, because the hero is graphite in both themes. */
val HeroPalette: PayFlowColors = AurumDark

/** The hero card is graphite in both themes, so it keeps a touch of grain in light too. */
private const val MIN_HERO_GRAIN = 0.02f

/** A 1dp separator in the hairline colour. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    val color = PayFlowTheme.colors.hairline
    Spacer(modifier.fillMaxWidth().height(Hairline.width).drawBehind { drawRect(color) })
}

/** Section title with an optional small [overline] above and an optional text [actionLabel] at the end. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    Row(modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.touchTarget), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            if (overline != null) Text(overline, style = type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = type.title, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (actionLabel != null && onAction != null) {
            GhostButton(actionLabel, onAction, outlined = false, debounceMs = 0L)
        }
    }
}

/**
 * Plain top bar: back arrow, title, trailing actions. Stays out of the way so the content can lead.
 *
 * Title rule for the whole app: a tab root (Home's wordmark, History, Me) names itself in the serif `display` face, set
 * [large]; a screen pushed on top of it, or a sheet, uses the sans `title`, so depth reads from the type alone.
 */
@Composable
fun PayFlowTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    large: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = PayFlowTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = Sizes.topBarHeight)
            .padding(horizontal = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            PayFlowIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), onBack, tint = colors.text)
        } else {
            // Lines the title up with the screen gutter.
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(
            title,
            style = if (large) PayFlowTheme.type.display else PayFlowTheme.type.title,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.xs).semantics { heading() },
        )
        actions()
    }
}

/**
 * The list row: leading slot, title and subtitle, trailing slot, at least 72dp tall. Everything in it reads as one TalkBack
 * item. Title and subtitle pass through [DisplayText.safe]. Cheap enough for 10,000-row lists; give the lazy item a `key` and
 * `contentType`.
 *
 * [subtitleMaxLines] is 1 for lists of records, where rows keep one rhythm and a long UPI ID may ellipsize. A settings row
 * whose subtitle explains something sets it higher, so large text wraps instead of cutting the explanation.
 */
@Composable
fun PayFlowListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    subtitleMaxLines: Int = 1,
) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val haptics = LocalHaptics.current
    val press = rememberPressFeedback()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.listRowHeight)
            .then(
                if (onClick != null) {
                    Modifier
                        .pressScale(press, pressedScale = 0.985f)
                        .drawBehind { drawRect(colors.raised, alpha = press.progress) }
                        .trackPress(press)
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                            haptics.light()
                            onClick()
                        }
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            )
            .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(DisplayText.safe(title), style = type.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(DisplayText.safe(subtitle), style = type.label, color = colors.textSecondary, maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

