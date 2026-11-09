package com.darshan.payflow.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.darshan.payflow.ui.designsystem.tokens.AurumLight
import com.darshan.payflow.ui.designsystem.tokens.PayFlowColors
import com.darshan.payflow.ui.designsystem.tokens.StatusTone

/** Maps Aurum Noir onto Material slots, so screens not yet rebuilt on the design system pick up the look. */
internal fun PayFlowColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = accentTint,
        onPrimaryContainer = onAccentTint,
        inversePrimary = accent,
        secondary = accent,
        onSecondary = surface,
        secondaryContainer = raised,
        onSecondaryContainer = text,
        tertiary = pending.strong,
        onTertiary = surface,
        tertiaryContainer = pending.container,
        onTertiaryContainer = pending.content,
        error = failed.strong,
        onError = surface,
        errorContainer = failed.container,
        onErrorContainer = failed.content,
        background = bg,
        onBackground = text,
        surface = bg,
        onSurface = text,
        surfaceVariant = raised,
        onSurfaceVariant = textSecondary,
        surfaceTint = Color.Transparent,
        inverseSurface = toast,
        inverseOnSurface = onToast,
        outline = outline,
        outlineVariant = hairline,
        surfaceBright = raised,
        surfaceDim = bg,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = raised,
    )
}

/** A semantic status colour pair: text/icon [content] on a tinted [container]. */
@Immutable
data class StatusColor(val content: Color, val container: Color)

/** Gradient used by the home summary card: [start] → [end] with [content] on top. */
@Immutable
data class HeroColors(val start: Color, val end: Color, val content: Color)

/** Semantic payment-status colours (and the hero gradient), as read by screens through `PayFlowTheme.status`. */
@Immutable
data class StatusPalette(
    val success: StatusColor,
    val pending: StatusColor,
    val failed: StatusColor,
    val refunded: StatusColor,
    val neutral: StatusColor,
    val hero: HeroColors,
)

private fun StatusTone.pair() = StatusColor(content = content, container = container)

internal fun PayFlowColors.toStatusPalette() = StatusPalette(
    success = success.pair(),
    pending = pending.pair(),
    failed = failed.pair(),
    refunded = refund.pair(),
    neutral = neutral.pair(),
    hero = HeroColors(start = heroStart, end = heroEnd, content = onHero),
)

val LocalStatusPalette = staticCompositionLocalOf { AurumLight.toStatusPalette() }
