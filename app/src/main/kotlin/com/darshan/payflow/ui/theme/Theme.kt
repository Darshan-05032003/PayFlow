package com.darshan.payflow.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.darshan.payflow.ui.designsystem.effects.AndroidHaptics
import com.darshan.payflow.ui.designsystem.effects.GuardedHaptics
import com.darshan.payflow.ui.designsystem.effects.GuardedUiSound
import com.darshan.payflow.ui.designsystem.effects.Haptics
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.effects.LocalUiSound
import com.darshan.payflow.ui.designsystem.effects.NoOpUiSound
import com.darshan.payflow.ui.designsystem.effects.ThemeMode
import com.darshan.payflow.ui.designsystem.effects.UiSettings
import com.darshan.payflow.ui.designsystem.effects.UiSound
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.PayFlowMotion
import com.darshan.payflow.ui.designsystem.motion.resolveMotionLevel
import com.darshan.payflow.ui.designsystem.tokens.AurumDark
import com.darshan.payflow.ui.designsystem.tokens.AurumLight
import com.darshan.payflow.ui.designsystem.tokens.DefaultPayFlowType
import com.darshan.payflow.ui.designsystem.tokens.PayFlowColors
import com.darshan.payflow.ui.designsystem.tokens.PayFlowType
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.designsystem.tokens.toMaterialTypography

internal val PayFlowShapes = Shapes(
    extraSmall = Radii.smallShape,
    small = Radii.smallShape,
    medium = Radii.mediumShape,
    large = Radii.mediumShape,
    extraLarge = Radii.largeShape,
)

val LocalPayFlowColors = staticCompositionLocalOf { AurumLight }
val LocalPayFlowType = staticCompositionLocalOf { DefaultPayFlowType }

/** Resolves the theme setting against the system, for the theme itself and for the system bars. */
@Composable
fun UiSettings.isDark(): Boolean = when (theme) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/** App entry point: everything comes from the stored UI settings. */
@Composable
fun PayFlowTheme(settings: UiSettings, content: @Composable () -> Unit) {
    PayFlowTheme(
        darkTheme = settings.isDark(),
        reduceMotion = settings.reduceMotion,
        hapticsEnabled = settings.hapticsEnabled,
        soundEnabled = settings.soundEnabled,
        content = content,
    )
}

/**
 * Aurum Noir. Dynamic colour is deliberately off so PayFlow looks the same on every device.
 *
 * Provides the tokens ([PayFlowTheme.colors], [PayFlowTheme.type]), [LocalMotion] and [LocalHaptics]. Tests pass a fake
 * [haptics] and set [reduceMotion] directly.
 */
@Composable
fun PayFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    reduceMotion: Boolean = false,
    hapticsEnabled: Boolean = true,
    soundEnabled: Boolean = false,
    haptics: Haptics? = null,
    sound: UiSound = NoOpUiSound,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) AurumDark else AurumLight
    val animatorScale = rememberSystemAnimatorScale()
    val level = resolveMotionLevel(reduceMotion, animatorScale)
    val motion = remember(level) { PayFlowMotion(level) }
    val view = LocalView.current
    val hapticsOn by rememberUpdatedState(hapticsEnabled)
    val soundOn by rememberUpdatedState(soundEnabled)
    val guardedHaptics = remember(view, haptics) { GuardedHaptics(haptics ?: AndroidHaptics(view)) { hapticsOn } }
    val guardedSound = remember(sound) { GuardedUiSound(sound) { soundOn } }
    val colorScheme = remember(colors) { colors.toColorScheme() }
    val status = remember(colors) { colors.toStatusPalette() }
    val typography = remember { DefaultPayFlowType.toMaterialTypography() }

    CompositionLocalProvider(
        LocalPayFlowColors provides colors,
        LocalPayFlowType provides DefaultPayFlowType,
        LocalStatusPalette provides status,
        LocalMotion provides motion,
        LocalHaptics provides guardedHaptics,
        LocalUiSound provides guardedSound,
        LocalContentColor provides colors.text,
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = PayFlowShapes, content = content)
    }
}

/** Reads the system animator scale and follows changes while the theme is on screen. */
@Composable
private fun rememberSystemAnimatorScale(): Float {
    val resolver = LocalContext.current.applicationContext.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    var scale by remember(resolver) { mutableFloatStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = read()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return scale
}

/** Theme-dependent tokens. Static ones live in `PayFlowTokens` (`Spacing`, `Radii`, `Sizes`). */
object PayFlowTheme {
    val colors: PayFlowColors
        @Composable @ReadOnlyComposable get() = LocalPayFlowColors.current

    val type: PayFlowType
        @Composable @ReadOnlyComposable get() = LocalPayFlowType.current

    val motion: PayFlowMotion
        @Composable @ReadOnlyComposable get() = LocalMotion.current

    val haptics: Haptics
        @Composable @ReadOnlyComposable get() = LocalHaptics.current

    /** Semantic status colours, e.g. `PayFlowTheme.status.success.content`. New code should prefer `colors.success`. */
    val status: StatusPalette
        @Composable @ReadOnlyComposable get() = LocalStatusPalette.current
}

/** Standard horizontal screen padding. */
val ScreenPadding = Spacing.screen
