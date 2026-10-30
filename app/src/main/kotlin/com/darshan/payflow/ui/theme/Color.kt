package com.darshan.payflow.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * PayFlow brand palette: a deep teal primary (trust, money) with a warm amber accent for attention
 * (pending, sandbox notes). Hand-tuned Material 3 tonal roles for light and dark; dynamic colour is off so the
 * brand stays consistent across devices.
 */
internal object Brand {
    val Teal10 = Color(0xFF00201C)
    val Teal20 = Color(0xFF00382F)
    val Teal30 = Color(0xFF005046)
    val Teal40 = Color(0xFF00675B)
    val Teal80 = Color(0xFF55DBC8)
    val Teal90 = Color(0xFF74F8E5)
    val Teal95 = Color(0xFFB4FFF2)

    val Sage10 = Color(0xFF05201C)
    val Sage20 = Color(0xFF1C3531)
    val Sage30 = Color(0xFF334B47)
    val Sage40 = Color(0xFF4A635F)
    val Sage80 = Color(0xFFB1CCC6)
    val Sage90 = Color(0xFFCCE8E2)

    val Amber10 = Color(0xFF2C1600)
    val Amber20 = Color(0xFF4A2800)
    val Amber30 = Color(0xFF693C00)
    val Amber40 = Color(0xFF8A5100)
    val Amber80 = Color(0xFFFFB77C)
    val Amber90 = Color(0xFFFFDCBF)

    val Red10 = Color(0xFF410002)
    val Red20 = Color(0xFF690005)
    val Red30 = Color(0xFF93000A)
    val Red40 = Color(0xFFBA1A1A)
    val Red80 = Color(0xFFFFB4AB)
    val Red90 = Color(0xFFFFDAD6)

    val Neutral4 = Color(0xFF090F0E)
    val Neutral6 = Color(0xFF0E1513)
    val Neutral10 = Color(0xFF171D1B)
    val Neutral12 = Color(0xFF1B2120)
    val Neutral17 = Color(0xFF252B2A)
    val Neutral22 = Color(0xFF303634)
    val Neutral30 = Color(0xFF3F4947)
    val Neutral50 = Color(0xFF6F7976)
    val Neutral60 = Color(0xFF89938F)
    val Neutral80 = Color(0xFFBEC9C5)
    val Neutral90 = Color(0xFFDAE5E1)
    val Neutral92 = Color(0xFFDEE4E1)
    val Neutral94 = Color(0xFFE4EAE7)
    val Neutral96 = Color(0xFFEAEFED)
    val Neutral97 = Color(0xFFF0F5F3)
    val Neutral98 = Color(0xFFF6FBF9)
    val White = Color(0xFFFFFFFF)
}

internal val LightColors = lightColorScheme(
    primary = Brand.Teal40,
    onPrimary = Brand.White,
    primaryContainer = Brand.Teal90,
    onPrimaryContainer = Brand.Teal10,
    inversePrimary = Brand.Teal80,
    secondary = Brand.Sage40,
    onSecondary = Brand.White,
    secondaryContainer = Brand.Sage90,
    onSecondaryContainer = Brand.Sage10,
    tertiary = Brand.Amber40,
    onTertiary = Brand.White,
    tertiaryContainer = Brand.Amber90,
    onTertiaryContainer = Brand.Amber10,
    error = Brand.Red40,
    onError = Brand.White,
    errorContainer = Brand.Red90,
    onErrorContainer = Brand.Red10,
    background = Brand.Neutral98,
    onBackground = Brand.Neutral10,
    surface = Brand.Neutral98,
    onSurface = Brand.Neutral10,
    surfaceVariant = Brand.Neutral90,
    onSurfaceVariant = Brand.Neutral30,
    surfaceTint = Brand.Teal40,
    inverseSurface = Brand.Neutral22,
    inverseOnSurface = Brand.Neutral96,
    outline = Brand.Neutral50,
    outlineVariant = Brand.Neutral80,
    surfaceContainerLowest = Brand.White,
    surfaceContainerLow = Brand.Neutral97,
    surfaceContainer = Brand.Neutral96,
    surfaceContainerHigh = Brand.Neutral94,
    surfaceContainerHighest = Brand.Neutral92,
    surfaceBright = Brand.Neutral98,
    surfaceDim = Brand.Neutral90,
)

internal val DarkColors = darkColorScheme(
    primary = Brand.Teal80,
    onPrimary = Brand.Teal20,
    primaryContainer = Brand.Teal30,
    onPrimaryContainer = Brand.Teal90,
    inversePrimary = Brand.Teal40,
    secondary = Brand.Sage80,
    onSecondary = Brand.Sage20,
    secondaryContainer = Brand.Sage30,
    onSecondaryContainer = Brand.Sage90,
    tertiary = Brand.Amber80,
    onTertiary = Brand.Amber20,
    tertiaryContainer = Brand.Amber30,
    onTertiaryContainer = Brand.Amber90,
    error = Brand.Red80,
    onError = Brand.Red20,
    errorContainer = Brand.Red30,
    onErrorContainer = Brand.Red90,
    background = Brand.Neutral6,
    onBackground = Brand.Neutral92,
    surface = Brand.Neutral6,
    onSurface = Brand.Neutral92,
    surfaceVariant = Brand.Neutral30,
    onSurfaceVariant = Brand.Neutral80,
    surfaceTint = Brand.Teal80,
    inverseSurface = Brand.Neutral92,
    inverseOnSurface = Brand.Neutral22,
    outline = Brand.Neutral60,
    outlineVariant = Brand.Neutral30,
    surfaceContainerLowest = Brand.Neutral4,
    surfaceContainerLow = Brand.Neutral10,
    surfaceContainer = Brand.Neutral12,
    surfaceContainerHigh = Brand.Neutral17,
    surfaceContainerHighest = Brand.Neutral22,
    surfaceBright = Brand.Neutral30,
    surfaceDim = Brand.Neutral6,
)

/** A semantic status colour pair: text/icon [content] on a tinted [container]. */
@Immutable
data class StatusColor(val content: Color, val container: Color)

/** Gradient used by the home summary card: [start] → [end] with [content] on top. */
@Immutable
data class HeroColors(val start: Color, val end: Color, val content: Color)

/** Semantic payment-status colours (and the hero gradient), tuned separately for light and dark so they stay legible in both. */
@Immutable
data class StatusPalette(
    val success: StatusColor,
    val pending: StatusColor,
    val failed: StatusColor,
    val refunded: StatusColor,
    val neutral: StatusColor,
    val hero: HeroColors,
)

internal val LightStatus = StatusPalette(
    success = StatusColor(content = Color(0xFF156B36), container = Color(0xFFD5F3DF)),
    pending = StatusColor(content = Color(0xFF7A4F00), container = Color(0xFFFFE8B3)),
    failed = StatusColor(content = Color(0xFFB3261E), container = Color(0xFFFFDAD6)),
    refunded = StatusColor(content = Color(0xFF1D5CA6), container = Color(0xFFD8E6FF)),
    neutral = StatusColor(content = Brand.Neutral30, container = Brand.Neutral92),
    hero = HeroColors(start = Brand.Teal40, end = Color(0xFF004A41), content = Brand.White),
)

internal val DarkStatus = StatusPalette(
    success = StatusColor(content = Color(0xFF86DB9F), container = Color(0xFF123F25)),
    pending = StatusColor(content = Color(0xFFFFC95C), container = Color(0xFF4B3500)),
    failed = StatusColor(content = Color(0xFFFFB4AB), container = Color(0xFF5E1A14)),
    refunded = StatusColor(content = Color(0xFFA6C8FF), container = Color(0xFF14365F)),
    neutral = StatusColor(content = Brand.Neutral80, container = Brand.Neutral22),
    hero = HeroColors(start = Brand.Teal30, end = Color(0xFF002B25), content = Brand.Teal90),
)

val LocalStatusPalette = staticCompositionLocalOf { LightStatus }
