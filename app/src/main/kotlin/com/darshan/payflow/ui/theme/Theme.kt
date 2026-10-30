package com.darshan.payflow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

internal val PayFlowShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Brand theme. Dynamic colour is deliberately off so PayFlow looks the same on every device. */
@Composable
fun PayFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalStatusPalette provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = PayFlowTypography,
            shapes = PayFlowShapes,
            content = content,
        )
    }
}

/** Access to the semantic status colours, e.g. `PayFlowTheme.status.success.content`. */
object PayFlowTheme {
    val status: StatusPalette
        @Composable @ReadOnlyComposable get() = LocalStatusPalette.current
}

/** Standard horizontal screen padding. */
val ScreenPadding = 16.dp
