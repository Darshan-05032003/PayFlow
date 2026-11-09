package com.darshan.payflow.ui.designsystem.tokens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Spacing on the 4/8 grid. Screens use these instead of literal dp. */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
    val huge = 64.dp

    /** Horizontal gutter of every screen. */
    val screen = 20.dp
}

/** The only three corner radii, plus a full pill. Small: inputs, chips' parents. Medium: buttons, cards. Large: sheets, hero card. */
object Radii {
    val small = 12.dp
    val medium = 20.dp
    val large = 28.dp

    val smallShape = RoundedCornerShape(small)
    val mediumShape = RoundedCornerShape(medium)
    val largeShape = RoundedCornerShape(large)
    val topLargeShape = RoundedCornerShape(topStart = large, topEnd = large)
    val bottomLargeShape = RoundedCornerShape(bottomStart = large, bottomEnd = large)
    val pillShape = RoundedCornerShape(percent = 50)
}

/** Depth is a 1dp hairline over layered tones: PayFlow draws no drop shadows. */
object Hairline {
    val width = 1.dp
}

/** Fixed component sizes. */
object Sizes {
    /** Minimum touch target of anything tappable. */
    val touchTarget = 48.dp
    val buttonHeight = 56.dp
    val buttonHeightCompact = 48.dp
    val inputHeight = 56.dp
    val iconSmall = 16.dp
    val icon = 24.dp
    val iconLarge = 32.dp
    val avatarSmall = 32.dp
    val avatar = 40.dp
    val avatarMedium = 56.dp
    val avatarLarge = 64.dp
    val navBarHeight = 64.dp
    val sheetHandleWidth = 40.dp
    val sheetHandleHeight = 4.dp
    val chipHeight = 28.dp
    val listRowHeight = 72.dp
    val topBarHeight = 56.dp
    val illustration = 120.dp
    val loader = 40.dp
    val loaderSmall = 20.dp
    val tactileDepth = 4.dp
    val switchWidth = 52.dp
    val switchHeight = 32.dp
    val pinKey = 72.dp

    /** The one big round action on a screen, such as Scan & Pay. */
    val heroAction = 88.dp

    /** The icon well of a quick action. */
    val quickAction = 56.dp
    val pinDot = 14.dp

    /** Moves a Newsreader numeral down to the optical centre of a round key. */
    val numeralNudge = 3.dp
}

/**
 * Discovery point for the static tokens. `PayFlowTokens.spacing.md` and `Spacing.md` are the same value; colours, type and
 * motion depend on the theme and are read through `PayFlowTheme.colors`, `PayFlowTheme.type` and `PayFlowTheme.motion`.
 */
object PayFlowTokens {
    val spacing = Spacing
    val radii = Radii
    val hairline = Hairline
    val sizes = Sizes
}
