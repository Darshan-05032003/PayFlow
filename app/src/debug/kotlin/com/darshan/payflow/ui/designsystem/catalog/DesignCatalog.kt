package com.darshan.payflow.ui.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.darshan.payflow.R
import com.darshan.payflow.ui.designsystem.components.AmountDecimals
import com.darshan.payflow.ui.designsystem.components.AmountOdometer
import com.darshan.payflow.ui.designsystem.components.AmountSign
import com.darshan.payflow.ui.designsystem.components.AmountText
import com.darshan.payflow.ui.designsystem.components.AvatarBadge
import com.darshan.payflow.ui.designsystem.components.AvatarRing
import com.darshan.payflow.ui.designsystem.components.BrandLoader
import com.darshan.payflow.ui.designsystem.components.DrawnCheckmark
import com.darshan.payflow.ui.designsystem.components.DrawnCross
import com.darshan.payflow.ui.designsystem.components.EmptyState
import com.darshan.payflow.ui.designsystem.components.ErrorState
import com.darshan.payflow.ui.designsystem.components.GhostButton
import com.darshan.payflow.ui.designsystem.components.GhostTone
import com.darshan.payflow.ui.designsystem.components.HairlineDivider
import com.darshan.payflow.ui.designsystem.components.Illustration
import com.darshan.payflow.ui.designsystem.components.IllustrationKind
import com.darshan.payflow.ui.designsystem.components.NavGlyphs
import com.darshan.payflow.ui.designsystem.components.NavItem
import com.darshan.payflow.ui.designsystem.components.OdometerLogic
import com.darshan.payflow.ui.designsystem.components.PayFlowAvatar
import com.darshan.payflow.ui.designsystem.components.PayFlowBottomNav
import com.darshan.payflow.ui.designsystem.components.PayFlowBottomSheet
import com.darshan.payflow.ui.designsystem.components.PayFlowChoiceChip
import com.darshan.payflow.ui.designsystem.components.PayFlowIconButton
import com.darshan.payflow.ui.designsystem.components.PayFlowListRow
import com.darshan.payflow.ui.designsystem.components.PayFlowSurface
import com.darshan.payflow.ui.designsystem.components.PayFlowSwitch
import com.darshan.payflow.ui.designsystem.components.PayFlowTextField
import com.darshan.payflow.ui.designsystem.components.PayFlowToast
import com.darshan.payflow.ui.designsystem.components.PayFlowToastHost
import com.darshan.payflow.ui.designsystem.components.PayFlowTopBar
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.components.SectionHeader
import com.darshan.payflow.ui.designsystem.components.SkeletonBlock
import com.darshan.payflow.ui.designsystem.components.SkeletonCircle
import com.darshan.payflow.ui.designsystem.components.SkeletonLine
import com.darshan.payflow.ui.designsystem.components.SkeletonList
import com.darshan.payflow.ui.designsystem.components.StatusKind
import com.darshan.payflow.ui.designsystem.components.StatusPill
import com.darshan.payflow.ui.designsystem.components.SurfaceLevel
import com.darshan.payflow.ui.designsystem.components.TactileButton
import com.darshan.payflow.ui.designsystem.components.rememberBottomNavState
import com.darshan.payflow.ui.designsystem.components.rememberToastHostState
import com.darshan.payflow.ui.designsystem.effects.ToastTone
import com.darshan.payflow.ui.designsystem.modifiers.filmGrain
import com.darshan.payflow.ui.designsystem.modifiers.metallicSheen
import com.darshan.payflow.ui.designsystem.modifiers.rememberPressFeedback
import com.darshan.payflow.ui.designsystem.modifiers.rememberSheenSweep
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme

/** One named group of the catalog. Screenshot tests render each one in light and dark. */
data class CatalogSection(val key: String, val title: String, val content: @Composable ColumnScope.() -> Unit)

/** Every component in every state. Only sample values appear here; nothing is read from the user's data. */
val CatalogSections: List<CatalogSection> = listOf(
    CatalogSection("colors", "Colours") { Colors() },
    CatalogSection("type", "Type scale") { TypeScale() },
    CatalogSection("surfaces", "Surfaces and depth") { Surfaces() },
    CatalogSection("buttons_primary", "Primary button") { PrimaryButtons() },
    CatalogSection("buttons_tactile", "Tactile button") { TactileButtons() },
    CatalogSection("buttons_ghost", "Ghost button") { GhostButtons() },
    CatalogSection("buttons_icon", "Icon button") { IconButtons() },
    CatalogSection("inputs", "Inputs") { Inputs() },
    CatalogSection("odometer", "Amount odometer") { Odometers() },
    CatalogSection("avatars", "Avatars") { Avatars() },
    CatalogSection("status", "Status pills") { StatusPills() },
    CatalogSection("sheet", "Bottom sheet") { Sheets() },
    CatalogSection("skeleton", "Skeleton") { Skeletons() },
    CatalogSection("toasts", "Toasts") { Toasts() },
    CatalogSection("states", "Empty and error") { StateViews() },
    CatalogSection("illustrations", "Illustrations") { Illustrations() },
    CatalogSection("nav", "Bottom navigation") { Navigation() },
    CatalogSection("structure", "Headers, bars, rows") { Structure() },
    CatalogSection("selection", "Chips and switches") { Selection() },
    CatalogSection("loaders", "Loaders and drawn marks") { Loaders() },
    CatalogSection("effects", "Sheen and grain") { Effects() },
)

@Composable
fun DesignCatalog(
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    reduceMotion: Boolean,
    onReduceMotionChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PayFlowTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.huge),
    ) {
        PayFlowTopBar("Design catalog")
        Column(Modifier.padding(horizontal = Spacing.screen)) {
            PayFlowSwitch(dark, onDarkChange, "Dark theme", Modifier.fillMaxWidth())
            PayFlowSwitch(reduceMotion, onReduceMotionChange, "Reduce motion", Modifier.fillMaxWidth())
        }
        LiveDemo()
        CatalogSections.forEach { section -> CatalogSectionView(section) }
    }
}

/** One section with its heading, as tests and the scrolling catalog show it. */
@Composable
fun CatalogSectionView(section: CatalogSection, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(PayFlowTheme.colors.bg)
            .testTag("catalog:${section.key}")
            .padding(horizontal = Spacing.screen, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        SectionHeader(section.title)
        section.content(this)
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = PayFlowTheme.type.label, color = PayFlowTheme.colors.textSecondary)
}

/** Press-and-feel area: the things a screenshot can't show. */
@Composable
private fun LiveDemo() {
    var amount by remember { mutableLongStateOf(12_34_500L) }
    val toasts = rememberToastHostState()
    var sheet by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(Spacing.screen)) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SectionHeader("Live", overline = "Touch these")
            val sweep = rememberSheenSweep(amount)
            val sheen = PayFlowTheme.colors.sheen
            PayFlowSurface(Modifier.fillMaxWidth(), level = SurfaceLevel.Hero, shape = Radii.largeShape) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .metallicSheen(sheen, position = { sweep.position })
                        .padding(Spacing.xl),
                ) {
                    AmountOdometer(amount, decimals = AmountDecimals.Always)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                TactileButton("+ ₹1,234", onClick = { amount += 1_234_00L }, Modifier.weight(1f), debounceMs = 0L)
                GhostButton("− ₹987", onClick = { amount -= 987_00L }, Modifier.weight(1f), debounceMs = 0L)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                GhostButton("Toast", onClick = { toasts.show("Copied to clipboard", ToastTone.Success) }, Modifier.weight(1f), debounceMs = 0L)
                GhostButton("Sheet", onClick = { sheet = true }, Modifier.weight(1f), debounceMs = 0L)
            }
            PayFlowToastHost(toasts)
        }
    }
    if (sheet) {
        Box(Modifier.fillMaxWidth().height(SheetDemoHeight)) {
            PayFlowBottomSheet(visible = true, onDismissRequest = { sheet = false }) {
                Text("Drag me down", style = PayFlowTheme.type.title, modifier = Modifier.padding(Spacing.screen))
                Spacer(Modifier.height(Spacing.huge))
            }
        }
    }
}

private val SheetDemoHeight = Sizes.buttonHeight * 6

private data class Swatch(val name: String, val color: Color, val on: Color)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Colors() {
    val c = PayFlowTheme.colors
    val swatches = listOf(
        Swatch("bg", c.bg, c.text),
        Swatch("surface", c.surface, c.text),
        Swatch("raised", c.raised, c.text),
        Swatch("primary", c.primary, c.onPrimary),
        Swatch("pressed", c.primaryPressed, c.onPrimary),
        Swatch("disabled", c.primaryDisabled, c.onPrimaryDisabled),
        Swatch("accent", c.accent, c.surface),
        Swatch("tint", c.accentTint, c.onAccentTint),
        Swatch("toast", c.toast, c.onToast),
        Swatch("success", c.success.container, c.success.content),
        Swatch("pending", c.pending.container, c.pending.content),
        Swatch("failed", c.failed.container, c.failed.content),
        Swatch("refund", c.refund.container, c.refund.content),
        Swatch("neutral", c.neutral.container, c.neutral.content),
        Swatch("hairline", c.hairline, c.text),
        Swatch("outline", c.outline, c.bg),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs), maxItemsInEachRow = 4) {
        swatches.forEach { s ->
            Box(
                Modifier
                    .weight(1f)
                    .height(Spacing.huge)
                    .clip(Radii.smallShape)
                    .background(s.color)
                    .padding(Spacing.xs),
                contentAlignment = Alignment.BottomStart,
            ) { Text(s.name, style = PayFlowTheme.type.label, color = s.on, maxLines = 1) }
        }
    }
}

@Composable
private fun TypeScale() {
    val t = PayFlowTheme.type
    val c = PayFlowTheme.colors.text
    Text("₹1,23,456", style = t.hero, color = c)
    Caption("hero 44/48, Newsreader")
    Text("₹12,500.75", style = t.display, color = c)
    Caption("display 30/36, Newsreader")
    Text("₹1,250", style = t.amount, color = c)
    Caption("amount 20/26, Newsreader")
    Text("Sent this month", style = t.title, color = c)
    Caption("title 20/26, Geist")
    Text("Paid to Asha from your bank account", style = t.body, color = c)
    Caption("body 15/22, Geist")
    Text("Medium for emphasis", style = t.bodyStrong, color = c)
    Text("Pay now", style = t.button, color = c)
    Caption("button 15/22, Geist")
    Text("PF0MV73K2 · 30 Oct, 10:38 PM", style = t.numeric, color = c)
    Caption("numeric 15/22, tabular")
    Text("Reported by the UPI app", style = t.label, color = c)
    Text("LABEL STRONG", style = t.labelStrong, color = c)
    Caption("label 12/16, Geist")
}

@Composable
private fun Surfaces() {
    for (level in SurfaceLevel.entries) {
        PayFlowSurface(Modifier.fillMaxWidth().height(Spacing.huge + Spacing.md), level = level, shape = if (level == SurfaceLevel.Hero) Radii.largeShape else Radii.mediumShape) {
            Text(level.name, style = PayFlowTheme.type.bodyStrong, modifier = Modifier.align(Alignment.Center))
        }
    }
    HairlineDivider()
    Caption("Radii 12, 20, 28")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        for (shape in listOf(Radii.smallShape, Radii.mediumShape, Radii.largeShape)) {
            PayFlowSurface(Modifier.weight(1f).height(Spacing.huge), level = SurfaceLevel.Raised, shape = shape) {}
        }
    }
}

@Composable
private fun PrimaryButtons() {
    val full = Modifier.fillMaxWidth()
    PrimaryButton("Pay ₹1,250", {}, full)
    PrimaryButton("Pressed", {}, full, press = rememberPressFeedback(initial = 1f))
    PrimaryButton("Scan & Pay", {}, full, leadingIcon = Icons.Outlined.QrCodeScanner)
    PrimaryButton("Disabled", {}, full, enabled = false)
    PrimaryButton("Loading", {}, full, loading = true)
    PrimaryButton("A long label that wraps onto a second line when the phone uses a big font", {}, full)
    PrimaryButton("Hug", {})
}

@Composable
private fun TactileButtons() {
    val full = Modifier.fillMaxWidth()
    TactileButton("Send reminder", {}, full)
    TactileButton("Pressed", {}, full, press = rememberPressFeedback(initial = 1f))
    TactileButton("With icon", {}, full, leadingIcon = Icons.Outlined.Share)
    TactileButton("Disabled", {}, full, enabled = false)
    TactileButton("Loading", {}, full, loading = true)
    TactileButton("Hug", {})
}

@Composable
private fun GhostButtons() {
    val full = Modifier.fillMaxWidth()
    GhostButton("Accent", {}, full)
    GhostButton("Pressed", {}, full, press = rememberPressFeedback(initial = 1f))
    GhostButton("Neutral", {}, full, tone = GhostTone.Neutral)
    GhostButton("Danger", {}, full, tone = GhostTone.Danger)
    GhostButton("With icon", {}, full, leadingIcon = Icons.Outlined.ContentCopy)
    GhostButton("Text only", {}, full, outlined = false)
    GhostButton("Disabled", {}, full, enabled = false)
    GhostButton("Loading", {}, full, loading = true)
}

@Composable
private fun IconButtons() {
    val icon = Icons.Outlined.Share
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        PayFlowIconButton(icon, "Plain", {}, tint = PayFlowTheme.colors.text)
        PayFlowIconButton(icon, "Tonal", {}, tonal = true, tint = PayFlowTheme.colors.text)
        PayFlowIconButton(icon, "Accent", {}, tonal = true, tint = PayFlowTheme.colors.accent)
        PayFlowIconButton(icon, "Pressed", {}, tonal = true, tint = PayFlowTheme.colors.text, press = rememberPressFeedback(initial = 1f))
        PayFlowIconButton(icon, "Disabled", {}, enabled = false)
    }
}

@Composable
private fun Inputs() {
    val full = Modifier.fillMaxWidth()
    PayFlowTextField("", {}, "Empty", full, placeholder = "name@bank")
    PayFlowTextField("asha@okicici", {}, "Filled", full, helper = "Helper text explains what is expected")
    val focus = remember { FocusRequester() }
    PayFlowTextField("Focused", {}, "Focused, with glow", full.focusRequester(focus))
    LaunchedEffect(Unit) { focus.requestFocus() }
    PayFlowTextField("not-a-vpa", {}, "Error", full, error = "Enter a UPI ID like name@bank")
    PayFlowTextField("Locked value", {}, "Disabled", full, enabled = false)
    PayFlowTextField("Read only", {}, "Read only", full, readOnly = true)
    PayFlowTextField(
        "Asha",
        {},
        "Leading and trailing",
        full,
        leading = { Icon(Icons.Outlined.Search, null, tint = PayFlowTheme.colors.textSecondary) },
        trailing = { PayFlowIconButton(Icons.Outlined.ContentCopy, "Copy", {}, tint = PayFlowTheme.colors.textSecondary) },
    )
}

@Composable
private fun Odometers() {
    AmountOdometer(0L, decimals = AmountDecimals.Always)
    AmountOdometer(99_999L, decimals = AmountDecimals.Always)
    AmountOdometer(1_23_456_00L)
    AmountOdometer(-1_250_00L, sign = AmountSign.Always)
    AmountOdometer(500_00L, sign = AmountSign.Always, color = PayFlowTheme.colors.success.strong)
    AmountOdometer(OdometerLogic.MAX_PAISE, decimals = AmountDecimals.Always)
    AmountOdometer(1_23_456_00L, hidden = true)
    Caption("Display style")
    AmountOdometer(12_500_75L, style = PayFlowTheme.type.display, decimals = AmountDecimals.Always)
    Caption("Narrow slot: shrinks to fit")
    AmountOdometer(OdometerLogic.MAX_PAISE, Modifier.width(Sizes.avatarLarge * 3), decimals = AmountDecimals.Always)
    Caption("Static, for rows")
    AmountText(1_250_00L)
    AmountText(-300_00L, color = PayFlowTheme.colors.text)
    AmountText(2_499_50L, sign = AmountSign.Always, color = PayFlowTheme.colors.success.strong)
}

@Composable
private fun Avatars() {
    val c = PayFlowTheme.colors
    val names = listOf("Asha", "Rahul", "Tara", "Chetan", "Priya", "Zoya")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) { names.forEach { PayFlowAvatar(it) } }
    Caption("Sizes")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        PayFlowAvatar("Asha", size = Sizes.avatarSmall)
        PayFlowAvatar("Asha")
        PayFlowAvatar("Asha", size = Sizes.avatarLarge)
    }
    Caption("Rings")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        PayFlowAvatar("Asha", ring = AvatarRing.Accent)
        PayFlowAvatar("Rahul", ring = AvatarRing.Success)
        PayFlowAvatar("Tara", ring = AvatarRing.Pending)
    }
    Caption("Badges")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        PayFlowAvatar("Asha", badge = AvatarBadge.Verified)
        PayFlowAvatar("Rahul", badge = AvatarBadge.Count(3))
        PayFlowAvatar("Tara", badge = AvatarBadge.Count(120))
        PayFlowAvatar("Chetan", badge = AvatarBadge.Dot(c.pending))
        PayFlowAvatar("Priya", ring = AvatarRing.Accent, badge = AvatarBadge.Verified, size = Sizes.avatarLarge)
    }
    Caption("Edge cases: symbols, emoji")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        PayFlowAvatar("!!@@zed")
        PayFlowAvatar("😀")
        PayFlowAvatar("")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusPills() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        StatusPill(StatusKind.Success, "Paid")
        StatusPill(StatusKind.Pending, "Pending")
        StatusPill(StatusKind.Failed, "Failed")
        StatusPill(StatusKind.Refund, "Refunded")
        StatusPill(StatusKind.Neutral, "Created")
    }
    Caption("Without entrance animation, for lists")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        StatusKind.entries.forEach { StatusPill(it, it.name, animate = false) }
    }
}

@Composable
private fun Sheets() {
    Caption("Open, with handle")
    Box(Modifier.fillMaxWidth().height(SheetDemoHeight).clip(Radii.mediumShape).background(PayFlowTheme.colors.surface)) {
        PayFlowBottomSheet(visible = true, onDismissRequest = {}) {
            PayFlowListRow("Google Pay", subtitle = "Handles this payment", leading = { PayFlowAvatar("Google Pay") })
            PayFlowListRow("PhonePe", subtitle = "Handles this payment", leading = { PayFlowAvatar("PhonePe") })
        }
    }
    Caption("Locked while a payment is in flight: no handle, no dismiss")
    Box(Modifier.fillMaxWidth().height(SheetDemoHeight).clip(Radii.mediumShape).background(PayFlowTheme.colors.surface)) {
        PayFlowBottomSheet(visible = true, onDismissRequest = {}, dismissible = false) {
            Column(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BrandLoader()
                Text("Confirming your payment", style = PayFlowTheme.type.bodyStrong, color = PayFlowTheme.colors.text)
            }
        }
    }
}

@Composable
private fun Skeletons() {
    SkeletonBlock(Modifier.fillMaxWidth().height(Spacing.huge + Spacing.xl), Radii.largeShape)
    SkeletonLine()
    SkeletonLine(widthFraction = 0.6f)
    SkeletonCircle(Sizes.avatar)
    SkeletonList(count = 3)
}

@Composable
private fun Toasts() {
    ToastTone.entries.forEach { PayFlowToast("${it.name}: copied to clipboard", it) }
    PayFlowToast("A long failure message explains exactly what went wrong and what to try next, across several lines.", ToastTone.Failed)
}

@Composable
private fun StateViews() {
    EmptyState(
        "No payments yet",
        message = "Your first payment shows up here.",
        actionLabel = "Pay someone",
        onAction = {},
        modifier = Modifier.fillMaxWidth(),
    )
    EmptyState("Nothing matches", message = "Try another name or amount.", illustration = IllustrationKind.NoResults, modifier = Modifier.fillMaxWidth())
    ErrorState("Couldn't load your history", stringResource(R.string.retry), {}, message = "Check your connection and try again.", modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Illustrations() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.md), maxItemsInEachRow = 3) {
        IllustrationKind.entries.forEach { kind ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Illustration(kind, size = Sizes.avatarLarge * 1.5f)
                Caption(kind.name)
            }
        }
        // Keeps the last row's cells the same width as the others.
        Spacer(Modifier.weight(1f))
    }
}

private val SampleNav = listOf(
    NavItem("home", "Home", NavGlyphs.Home),
    NavItem("scan", "Scan", NavGlyphs.Scan),
    NavItem("history", "History", NavGlyphs.History),
    NavItem("me", "You", NavGlyphs.Profile),
)

@Composable
private fun Navigation() {
    for (index in SampleNav.indices) {
        val state = rememberBottomNavState(SampleNav.size, initial = index)
        PayFlowBottomNav(SampleNav, state, onChange = {})
    }
}

@Composable
private fun Structure() {
    SectionHeader("Recent", overline = "This week", actionLabel = "View all", onAction = {})
    SectionHeader("Without an action")
    HairlineDivider()
    PayFlowTopBar("Top bar", onBack = {}, actions = { PayFlowIconButton(Icons.Outlined.Search, "Search", {}, tint = PayFlowTheme.colors.text) })
    PayFlowTopBar("No back arrow")
    HairlineDivider()
    PayFlowListRow(
        "Asha Kulkarni",
        subtitle = "asha@okicici · Today, 10:38 PM",
        leading = { PayFlowAvatar("Asha Kulkarni") },
        trailing = { AmountText(-1_250_00L, color = PayFlowTheme.colors.text) },
        onClick = {},
    )
    PayFlowListRow(
        "A very long merchant name that does not fit on a single line of a list row at all",
        subtitle = "merchant.with.a.long.handle@somebank · Yesterday",
        leading = { PayFlowAvatar("Merchant", badge = AvatarBadge.Verified) },
        trailing = { StatusPill(StatusKind.Pending, "Pending", animate = false) },
    )
    PayFlowListRow("Row with no extras")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Selection() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        PayFlowChoiceChip("₹100", selected = false, onClick = {})
        PayFlowChoiceChip("₹500", selected = true, onClick = {})
        PayFlowChoiceChip("₹1,000", selected = false, onClick = {}, enabled = false)
    }
    PayFlowSwitch(true, {}, "Haptics on", Modifier.fillMaxWidth())
    PayFlowSwitch(false, {}, "Sound off", Modifier.fillMaxWidth())
    PayFlowSwitch(true, {}, "Disabled, on", Modifier.fillMaxWidth(), enabled = false)
    PayFlowSwitch(false, {}, "Disabled, off", Modifier.fillMaxWidth(), enabled = false)
}

@Composable
private fun Loaders() {
    val c = PayFlowTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        BrandLoader()
        BrandLoader(size = Sizes.loaderSmall)
        DrawnCheckmark({ 1f }, c.success.strong, Modifier.size(Sizes.iconLarge))
        DrawnCheckmark({ 0.5f }, c.success.strong, Modifier.size(Sizes.iconLarge))
        DrawnCross({ 1f }, c.failed.strong, Modifier.size(Sizes.iconLarge))
        DrawnCross({ 0.5f }, c.failed.strong, Modifier.size(Sizes.iconLarge))
    }
}

@Composable
private fun Effects() {
    val c = PayFlowTheme.colors
    for ((label, shader) in listOf("AGSL band (API 33+)" to true, "Gradient band (below 33)" to false)) {
        Caption(label)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            for (position in listOf(0.15f, 0.5f, 0.85f)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(Spacing.huge)
                        .clip(Radii.smallShape)
                        .background(c.heroEnd)
                        .metallicSheen(c.sheen, intensity = 0.6f, useShader = shader, position = { position }),
                )
            }
        }
    }
    for ((label, shader) in listOf("AGSL grain (API 33+), exaggerated" to true, "Tile grain (below 33), exaggerated" to false)) {
        Caption(label)
        Box(
            Modifier
                .fillMaxWidth()
                .height(Spacing.huge)
                .clip(Radii.smallShape)
                .background(c.heroEnd)
                .filmGrain(0.2f, useShader = shader),
        )
    }
}
