package com.darshan.payflow.ui.designsystem.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * A status colour set.
 * - [strong] is the direction's status colour, for text or icons directly on `bg`/`surface`.
 * - [content] on [container] is the tinted chip pair; every such pair meets WCAG AA.
 */
@Immutable
data class StatusTone(val strong: Color, val content: Color, val container: Color)

/** A muted avatar pair. Avatars pick one by hashing the name, so a payee keeps a recognisable colour. */
@Immutable
data class AvatarTone(val content: Color, val container: Color)

/**
 * Aurum Noir colour tokens. Read them with `PayFlowTheme.colors`; screens never use raw hex.
 *
 * Depth comes from three layered tones ([bg] → [surface] → [raised]) plus a 1dp [hairline], not from drop shadows.
 * Champagne ([accent], [primary] in dark) is the only accent. Status colours appear only where they carry meaning.
 */
@Immutable
data class PayFlowColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val raised: Color,
    val text: Color,
    val textSecondary: Color,
    val primary: Color,
    val onPrimary: Color,
    /** Metallic gradient of the primary button, top and bottom. */
    val primaryTop: Color,
    val primaryBottom: Color,
    val primaryPressed: Color,
    val primaryDisabled: Color,
    val onPrimaryDisabled: Color,
    val accent: Color,
    /** Selected-item backdrop (nav pill, selected chip), with [onAccentTint] on top. */
    val accentTint: Color,
    val onAccentTint: Color,
    /** Layer separation, decorative. Not a control boundary. */
    val hairline: Color,
    /** Control boundary (inputs, ghost buttons, toggles): at least 3:1 against [bg] and [surface]. */
    val outline: Color,
    val focusGlow: Color,
    val scrim: Color,
    val tactileCap: Color,
    val onTactile: Color,
    /** The lower block of the tactile button: the cap travels down onto it when pressed. */
    val tactileBase: Color,
    val toast: Color,
    val onToast: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val onHero: Color,
    val success: StatusTone,
    val pending: StatusTone,
    val failed: StatusTone,
    val refund: StatusTone,
    val neutral: StatusTone,
    val shimmerBase: Color,
    val shimmerHighlight: Color,
    /** Light band of the metallic sheen sweep. */
    val sheen: Color,
    /** Strength of the film grain on this theme (0..1). */
    val grainAlpha: Float,
    val avatarTones: List<AvatarTone>,
)

val AurumLight = PayFlowColors(
    isDark = false,
    bg = Color(0xFFF6F3EC),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFEEEAE0),
    text = Color(0xFF16171B),
    textSecondary = Color(0xFF5A574F),
    primary = Color(0xFF17181C),
    onPrimary = Color(0xFFEFD9AE),
    primaryTop = Color(0xFF2C2D33),
    primaryBottom = Color(0xFF17181C),
    primaryPressed = Color(0xFF2C2D33),
    primaryDisabled = Color(0xFFDFDACE),
    onPrimaryDisabled = Color(0xFF5F5C54),
    accent = Color(0xFF7A581C),
    accentTint = Color(0xFFE4DAC3),
    onAccentTint = Color(0xFF16171B),
    hairline = Color(0xFFE3DFD5),
    outline = Color(0xFF8F8B7F),
    focusGlow = Color(0xFF7A581C),
    scrim = Color(0x8016171B),
    tactileCap = Color(0xFFEFD9AE),
    onTactile = Color(0xFF16171B),
    tactileBase = Color(0xFFB99D68),
    toast = Color(0xFF1F2228),
    onToast = Color(0xFFF3F0E9),
    heroStart = Color(0xFF2C2D33),
    heroEnd = Color(0xFF17181C),
    onHero = Color(0xFFEFD9AE),
    success = StatusTone(strong = Color(0xFF1B7A4C), content = Color(0xFF176B42), container = Color(0xFFDCEFE4)),
    pending = StatusTone(strong = Color(0xFF855B00), content = Color(0xFF755000), container = Color(0xFFF5E7C6)),
    failed = StatusTone(strong = Color(0xFFB3362C), content = Color(0xFFA02F26), container = Color(0xFFF8E1DD)),
    refund = StatusTone(strong = Color(0xFF2F5FA8), content = Color(0xFF28548F), container = Color(0xFFE0E8F5)),
    neutral = StatusTone(strong = Color(0xFF5A574F), content = Color(0xFF5A574F), container = Color(0xFFEEEAE0)),
    shimmerBase = Color(0xFFEEEAE0),
    shimmerHighlight = Color(0xFFFBF9F4),
    sheen = Color(0xFFEFD9AE),
    grainAlpha = 0f,
    avatarTones = listOf(
        AvatarTone(Color(0xFF5C4314), Color(0xFFEBDFC6)),
        AvatarTone(Color(0xFF23483A), Color(0xFFD9E8E0)),
        AvatarTone(Color(0xFF2A4466), Color(0xFFDCE5F1)),
        AvatarTone(Color(0xFF5E3A34), Color(0xFFEFDEDA)),
        AvatarTone(Color(0xFF46405F), Color(0xFFE3DFEE)),
        AvatarTone(Color(0xFF3F3E39), Color(0xFFE4E1D8)),
    ),
)

val AurumDark = PayFlowColors(
    isDark = true,
    bg = Color(0xFF0B0C0F),
    surface = Color(0xFF15171B),
    raised = Color(0xFF1F2228),
    text = Color(0xFFF3F0E9),
    textSecondary = Color(0xFFAAA69C),
    primary = Color(0xFFE2C48D),
    onPrimary = Color(0xFF1A1408),
    primaryTop = Color(0xFFECD5A7),
    primaryBottom = Color(0xFFD6B97F),
    primaryPressed = Color(0xFFC9AC78),
    primaryDisabled = Color(0xFF2A2C32),
    onPrimaryDisabled = Color(0xFFA3A096),
    accent = Color(0xFFE2C48D),
    accentTint = Color(0xFF2E2A20),
    onAccentTint = Color(0xFFE2C48D),
    hairline = Color(0xFF2A2D33),
    outline = Color(0xFF77746C),
    focusGlow = Color(0xFFE2C48D),
    scrim = Color(0xB3000000),
    tactileCap = Color(0xFFE2C48D),
    onTactile = Color(0xFF1A1408),
    tactileBase = Color(0xFF8C7344),
    toast = Color(0xFFEEEAE0),
    onToast = Color(0xFF16171B),
    heroStart = Color(0xFF1F2228),
    heroEnd = Color(0xFF15171B),
    onHero = Color(0xFFF3F0E9),
    success = StatusTone(strong = Color(0xFF6ED3A1), content = Color(0xFF6ED3A1), container = Color(0xFF12291F)),
    pending = StatusTone(strong = Color(0xFFE9B95F), content = Color(0xFFE9B95F), container = Color(0xFF2B2212)),
    failed = StatusTone(strong = Color(0xFFF2948A), content = Color(0xFFF2948A), container = Color(0xFF351B19)),
    refund = StatusTone(strong = Color(0xFF93B9F2), content = Color(0xFF93B9F2), container = Color(0xFF162437)),
    neutral = StatusTone(strong = Color(0xFFAAA69C), content = Color(0xFFAAA69C), container = Color(0xFF1F2228)),
    shimmerBase = Color(0xFF1F2228),
    shimmerHighlight = Color(0xFF2E3138),
    sheen = Color(0xFFFFF6E3),
    grainAlpha = 0.03f,
    avatarTones = listOf(
        AvatarTone(Color(0xFFE2C48D), Color(0xFF2E2717)),
        AvatarTone(Color(0xFF9ED9BC), Color(0xFF1B2E26)),
        AvatarTone(Color(0xFFA9C6EE), Color(0xFF1C2A3C)),
        AvatarTone(Color(0xFFEDB7AE), Color(0xFF35221F)),
        AvatarTone(Color(0xFFC5BBEA), Color(0xFF272338)),
        AvatarTone(Color(0xFFCFCBC1), Color(0xFF2A2B2C)),
    ),
)
