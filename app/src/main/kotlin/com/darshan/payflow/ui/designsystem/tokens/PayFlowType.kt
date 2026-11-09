package com.darshan.payflow.ui.designsystem.tokens

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.darshan.payflow.R

private const val DISPLAY_OPTICAL_SIZE = 60f

private fun newsreader(weight: FontWeight) = Font(
    resId = R.font.newsreader_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.Setting("opsz", DISPLAY_OPTICAL_SIZE),
    ),
)

private fun geist(weight: FontWeight) = Font(
    resId = R.font.geist_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Numerals and display. Digits are tabular by default in this font; its minus sign is not used (see [Geist]). */
val Newsreader = FontFamily(newsreader(FontWeight.Normal), newsreader(FontWeight.Medium), newsreader(FontWeight.SemiBold))

/** All UI text, and every sign in an amount: Newsreader's minus sits too low and reads like an underscore. */
val Geist = FontFamily(geist(FontWeight.Normal), geist(FontWeight.Medium), geist(FontWeight.SemiBold), geist(FontWeight.Bold))

private val Centered = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)

private fun display(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = -0.01f) = TextStyle(
    fontFamily = Newsreader, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em,
    lineHeightStyle = Centered,
)

private fun ui(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) = TextStyle(
    fontFamily = Geist, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em,
    lineHeightStyle = Centered,
)

/**
 * The type scale from the design direction: hero 44/48, display 30/36, title 20/26, body 15/22, label 12/16.
 * Read it with `PayFlowTheme.type`. Text scales with the system font size; layouts are tested up to 1.3×.
 */
@Immutable
data class PayFlowType(
    /** The biggest number on a screen. Newsreader. */
    val hero: TextStyle = display(44, 48),
    /** Secondary numbers and screen headlines. Newsreader. */
    val display: TextStyle = display(30, 36),
    /** Amounts in cards and rows. Newsreader at the title size. */
    val amount: TextStyle = display(20, 26, FontWeight.Medium, tracking = 0f),
    /** Section and screen titles. Geist. */
    val title: TextStyle = ui(20, 26, FontWeight.SemiBold, tracking = -0.005f),
    val body: TextStyle = ui(15, 22),
    val bodyStrong: TextStyle = ui(15, 22, FontWeight.Medium),
    val button: TextStyle = ui(15, 22, FontWeight.SemiBold, tracking = 0.005f),
    val label: TextStyle = ui(12, 16, FontWeight.Medium, tracking = 0.01f),
    val labelStrong: TextStyle = ui(12, 16, FontWeight.SemiBold, tracking = 0.02f),
    /** Tabular UI numerals for references, times and small amounts. */
    val numeric: TextStyle = ui(15, 22, FontWeight.Medium).copy(fontFeatureSettings = "tnum"),
)

val DefaultPayFlowType = PayFlowType()

/** Maps the Aurum Noir scale onto Material slots, so screens still using Material text styles pick up the new look. */
internal fun PayFlowType.toMaterialTypography(): Typography = Typography(
    displayLarge = hero,
    displayMedium = hero,
    displaySmall = display,
    headlineLarge = display,
    headlineMedium = display,
    headlineSmall = amount,
    titleLarge = title,
    titleMedium = bodyStrong.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = bodyStrong,
    bodyLarge = body,
    bodyMedium = body,
    bodySmall = label.copy(fontWeight = FontWeight.Normal),
    labelLarge = button,
    labelMedium = label,
    labelSmall = label,
)
