package com.darshan.payflow.ui.home

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.RecentPayee
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.LocalPaymentMode
import com.darshan.payflow.ui.components.PaymentFeedRow
import com.darshan.payflow.ui.components.relativeLabel
import com.darshan.payflow.ui.designsystem.components.AmountDecimals
import com.darshan.payflow.ui.designsystem.components.AmountSign
import com.darshan.payflow.ui.designsystem.components.ClickDebouncer
import com.darshan.payflow.ui.designsystem.components.DisplayText
import com.darshan.payflow.ui.designsystem.components.EmptyState
import com.darshan.payflow.ui.designsystem.components.ErrorState
import com.darshan.payflow.ui.designsystem.components.GhostButton
import com.darshan.payflow.ui.designsystem.components.HairlineDivider
import com.darshan.payflow.ui.designsystem.components.IllustrationKind
import com.darshan.payflow.ui.designsystem.components.OdometerLogic
import com.darshan.payflow.ui.designsystem.components.PayFlowAvatar
import com.darshan.payflow.ui.designsystem.components.PayFlowListRow
import com.darshan.payflow.ui.designsystem.components.PayFlowToastHost
import com.darshan.payflow.ui.designsystem.components.SectionHeader
import com.darshan.payflow.ui.designsystem.components.SkeletonList
import com.darshan.payflow.ui.designsystem.components.StatusKind
import com.darshan.payflow.ui.designsystem.components.StatusPill
import com.darshan.payflow.ui.designsystem.components.ToastHostState
import com.darshan.payflow.ui.designsystem.components.rememberToastHostState
import com.darshan.payflow.ui.designsystem.effects.CollectUiEffects
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.effects.ToastTone
import com.darshan.payflow.ui.designsystem.modifiers.StaggeredEntrance
import com.darshan.payflow.ui.designsystem.modifiers.metallicSheen
import com.darshan.payflow.ui.designsystem.modifiers.pressScale
import com.darshan.payflow.ui.designsystem.modifiers.rememberPressFeedback
import com.darshan.payflow.ui.designsystem.modifiers.trackPress
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.navigation.LocalBottomBarInset
import com.darshan.payflow.ui.navigation.SCAN_CONTAINER_KEY
import com.darshan.payflow.ui.navigation.ScrollToTopOnReselect
import com.darshan.payflow.ui.navigation.ShellRoute
import com.darshan.payflow.ui.navigation.containerTransform
import com.darshan.payflow.ui.theme.PayFlowTheme

data class HomeCallbacks(
    val onPaymentClick: (String) -> Unit = {},
    val onViewAll: () -> Unit = {},
    val onPay: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onCollect: () -> Unit = {},
    val onMandates: () -> Unit = {},
    val onCheckout: () -> Unit = {},
    val onReceive: () -> Unit = {},
    val onPayee: (RecentPayee) -> Unit = {},
    val onSetupPin: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onToggleAmounts: () -> Unit = {},
)

@Composable
fun HomeRoute(callbacks: HomeCallbacks, pinConfigured: Boolean = true, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hidden by viewModel.amountsHidden.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val toasts = rememberToastHostState()
    val resources = LocalResources.current
    CollectUiEffects(
        viewModel.effects,
        onOther = { effect ->
            if (effect is HomeEffect) {
                val failed = effect is HomeEffect.RefreshFailed
                toasts.show(refreshMessage(effect, resources), if (failed) ToastTone.Failed else ToastTone.Info)
            }
        },
    )
    LightStatusBarIconsOnHero()
    HomeScreen(
        state = state,
        callbacks = callbacks.copy(onRetry = viewModel::retry, onRefresh = viewModel::refresh, onToggleAmounts = viewModel::toggleAmountsHidden),
        pinConfigured = pinConfigured,
        amountsHidden = hidden,
        refreshing = refreshing,
        memory = viewModel.entranceMemory,
        toasts = toasts,
    )
}

internal fun refreshMessage(effect: HomeEffect, resources: android.content.res.Resources): String = when (effect) {
    HomeEffect.RefreshFailed -> resources.getString(R.string.home_refresh_failed)
    is HomeEffect.Refreshed -> when {
        effect.settled > 0 -> resources.getQuantityString(R.plurals.home_refresh_settled, effect.settled, effect.settled)
        effect.checked > 0 -> resources.getQuantityString(R.plurals.home_refresh_waiting, effect.checked, effect.checked)
        else -> resources.getString(R.string.home_refresh_nothing)
    }
}

/** The header is graphite in both themes and runs under the status bar, so the icons must be light here whatever the theme. */
@Composable
private fun LightStatusBarIconsOnHero() {
    val window = LocalActivity.current?.window ?: return
    val darkTheme = PayFlowTheme.colors.isDark
    DisposableEffect(window, darkTheme) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = !darkTheme }
    }
}

/**
 * Home: a graphite header that docks into the top bar, Scan & Pay across its edge as the one primary action, four quick actions,
 * people to pay again, and the latest payments. The states replace the feed only, so every action stays reachable.
 *
 * A scrolling column on purpose: its children are bounded (5 payments, 8 payees), so a scroll frame moves layers and composes
 * nothing, and every node stays in the tree for TalkBack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    callbacks: HomeCallbacks,
    pinConfigured: Boolean = true,
    amountsHidden: Boolean = false,
    refreshing: Boolean = false,
    memory: HomeEntranceMemory = remember { HomeEntranceMemory() },
    toasts: ToastHostState = rememberToastHostState(),
    scroll: ScrollState = rememberScrollState(),
) {
    val colors = PayFlowTheme.colors
    val haptics = LocalHaptics.current
    val density = LocalDensity.current
    val barInset = LocalBottomBarInset.current
    val sandbox = LocalPaymentMode.current == PaymentMode.SANDBOX
    ScrollToTopOnReselect(ShellRoute.Home, scroll)

    val header = remember(scroll) { HomeHeaderState(scroll) }
    val play = remember { !memory.sectionsPlayed }
    SideEffect { memory.sectionsPlayed = true }

    val pull = rememberPullToRefreshState()
    val thresholdPx = with(density) { PullToRefreshDefaults.PositionalThreshold.toPx() }
    val fraction = { pull.distanceFraction }
    val isRefreshing by rememberUpdatedState(refreshing)
    LaunchedEffect(pull) {
        var previous = 0f
        snapshotFlow { pull.distanceFraction }.collect { current ->
            if (!isRefreshing && PullLogic.crossedThreshold(previous, current)) haptics.tick()
            previous = current
        }
    }

    val refreshLabel = stringResource(R.string.home_refresh_action)
    val onRefresh by rememberUpdatedState(callbacks.onRefresh)
    val hero = when (state) {
        HomeUiState.Loading -> HeroContent.Loading
        is HomeUiState.Error -> HeroContent.Unavailable
        HomeUiState.Empty -> HeroContent.Known(HistorySummary.EMPTY)
        is HomeUiState.Content -> HeroContent.Known(state.month)
    }

    var viewportPx by remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewportPx = it.height }
            .pullToRefresh(isRefreshing = refreshing, state = pull, onRefresh = callbacks.onRefresh)
            .semantics { customActions = listOf(CustomAccessibilityAction(refreshLabel) { onRefresh(); true }) }
            .drawBehind {
                drawRect(colors.bg)
                // The well the page is pulled away from: the header's own graphite, so the header reads as stretching.
                val stretch = PullLogic.stretchPx(fraction(), thresholdPx)
                if (stretch > 0f) drawRect(colors.heroStart, size = Size(size.width, stretch + Spacing.xl.toPx()))
            },
    ) {
        PullCoin(
            fraction = fraction,
            thresholdPx = thresholdPx,
            topInsetPx = WindowInsets.statusBars.getTop(density).toFloat(),
            refreshing = refreshing,
            refreshingLabel = stringResource(R.string.home_refreshing),
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            // The pull moves this inner layer, not the scroller: moving the scroller would shift the pointer it measures from.
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = PullLogic.stretchPx(fraction(), thresholdPx) }
                    // Always tall enough to dock: a short page must not stop half-collapsed, with Scan & Pay half faded.
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minHeight = viewportPx + header.rangePx))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
            ) {
                HomeHero(
                    header = header,
                    content = hero,
                    caption = stringResource(if (sandbox) R.string.home_hero_caption_sandbox else R.string.home_hero_caption_upi),
                    amountsHidden = amountsHidden,
                    memory = memory,
                    onToggleAmounts = callbacks.onToggleAmounts,
                    modifier = Modifier.zIndex(1f),
                )
                ScanAndPay(header, play, callbacks.onScan)
                QuickActions(callbacks, sandbox, play)
                if (!pinConfigured) StaggeredEntrance(5, play = play) { LockBanner(callbacks.onSetupPin) }
                val payees = (state as? HomeUiState.Content)?.recentPayees.orEmpty()
                if (payees.isNotEmpty()) StaggeredEntrance(6, play = play) { RecentPayeesRow(payees, callbacks.onPayee) }
                Feed(state, callbacks, amountsHidden, memory, play)
                DemoStoreRow(callbacks.onCheckout, play)
                Footer(sandbox)
                // Keeps the end of the page clear of the floating bar and the system navigation.
                Spacer(Modifier.navigationBarsPadding().height(barInset + Spacing.xl))
            }
        }
        PayFlowToastHost(toasts, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = barInset))
    }
}

/** Lets the content above it overlap by [overlap], taking that much off this element's height in the layout. */
private fun Modifier.overlapAbove(overlap: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val up = overlap.roundToPx()
    layout(placeable.width, (placeable.height - up).coerceAtLeast(0)) { placeable.place(0, -up) }
}

private val RingMargin = Spacing.xs

/**
 * The one primary action, sitting across the header's edge in a notch of the page colour with a breathing ring. It fades into
 * the bar as the header docks, and then drops below the bar, which stops touches from reaching it.
 */
@Composable
private fun ScanAndPay(header: HomeHeaderState, play: Boolean, onScan: () -> Unit) {
    val colors = PayFlowTheme.colors
    val haptics = LocalHaptics.current
    val press = rememberPressFeedback()
    val debouncer = remember { ClickDebouncer() }
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(R.string.home_scan_pay)
    val docked = header.docked
    val breath = breathing(active = !docked)
    StaggeredEntrance(
        index = 0,
        play = play,
        modifier = Modifier
            .fillMaxWidth()
            .overlapAbove(Sizes.heroAction / 2 + RingMargin)
            .zIndex(if (docked) 0f else 2f)
            .graphicsLayer {
                alpha = HomeHeaderLogic.scanActionAlpha(header.progress)
                val scale = HomeHeaderLogic.scanActionScale(header.progress)
                scaleX = scale
                scaleY = scale
            },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier
                    .testTag(PayFlowTestTags.HOME_SCAN)
                    .clip(Radii.mediumShape)
                    .trackPress(press)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                        if (debouncer.tryAcquire()) {
                            haptics.light()
                            onScan()
                        }
                    }
                    .semantics(mergeDescendants = true) { contentDescription = label },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(Sizes.heroAction + RingMargin * 2), contentAlignment = Alignment.Center) {
                    // The notch, cut into the header in the page colour.
                    Spacer(Modifier.matchParentSize().drawBehind { drawCircle(colors.bg) })
                    Spacer(
                        Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                val b = breath?.value ?: 0.5f
                                val scale = RING_SCALE_MIN + (1f - RING_SCALE_MIN) * b
                                scaleX = scale
                                scaleY = scale
                                alpha = if (breath == null) RING_ALPHA_REST else RING_ALPHA_MIN + (1f - RING_ALPHA_MIN) * b
                            }
                            .drawWithCache {
                                val stroke = Stroke(Hairline.width.toPx() * 1.5f)
                                onDrawBehind { drawCircle(colors.accent, radius = size.minDimension / 2f - stroke.width, style = stroke) }
                            },
                    )
                    Box(
                        Modifier
                            .size(Sizes.heroAction)
                            .containerTransform(SCAN_CONTAINER_KEY)
                            .pressScale(press)
                            .clip(Radii.pillShape)
                            .drawWithCache {
                                val fill = Brush.verticalGradient(listOf(colors.primaryTop, colors.primaryBottom))
                                onDrawBehind {
                                    drawRect(fill)
                                    drawRect(colors.primaryPressed, alpha = press.progress)
                                }
                            }
                            .metallicSheen(colors.sheen, intensity = colors.sheenStrength, position = { SCAN_SHEEN_REST + SCAN_SHEEN_TRAVEL * press.progress }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(Sizes.iconLarge))
                    }
                }
                Text(
                    label,
                    style = PayFlowTheme.type.button,
                    color = colors.text,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = Spacing.md).padding(bottom = Spacing.xs),
                )
            }
        }
    }
}

private const val RING_SCALE_MIN = 0.94f
private const val RING_ALPHA_MIN = 0.35f
private const val RING_ALPHA_REST = 0.7f
private const val SCAN_SHEEN_REST = 0.2f
private const val SCAN_SHEEN_TRAVEL = 0.5f

private data class QuickAction(val label: Int, val icon: ImageVector, val tag: String, val sandboxOnly: Boolean, val onClick: () -> Unit)

/** Scan, History and Security aren't here: the first is the hero above, the others are in the bar below. */
@Composable
private fun QuickActions(callbacks: HomeCallbacks, sandboxMode: Boolean, play: Boolean) {
    val actions = listOf(
        QuickAction(R.string.home_action_pay, Icons.Default.Send, "${PayFlowTestTags.HOME_PAY}:tile", false, callbacks.onPay),
        QuickAction(R.string.home_action_receive, Icons.Outlined.QrCode2, PayFlowTestTags.HOME_RECEIVE, false, callbacks.onReceive),
        QuickAction(R.string.home_action_requests, Icons.Outlined.RequestQuote, PayFlowTestTags.HOME_COLLECT, true, callbacks.onCollect),
        QuickAction(R.string.home_action_autopay, Icons.Outlined.EventRepeat, PayFlowTestTags.HOME_MANDATES, true, callbacks.onMandates),
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.xs).padding(top = Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        actions.forEachIndexed { index, action ->
            StaggeredEntrance(index + 1, Modifier.weight(1f), play = play) {
                // In sandbox mode the whole app says so already. In UPI apps mode these two are the ones that still aren't real.
                QuickActionTile(action, labelSandbox = action.sandboxOnly && !sandboxMode)
            }
        }
    }
}

@Composable
private fun QuickActionTile(action: QuickAction, labelSandbox: Boolean) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val haptics = LocalHaptics.current
    val magnet = rememberMagneticPress()
    val debouncer = remember { ClickDebouncer() }
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(action.label)
    val description = if (labelSandbox) stringResource(R.string.home_action_sandbox_description, label) else label
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(action.tag)
            .magneticPress(magnet)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                if (debouncer.tryAcquire()) {
                    haptics.light()
                    action.onClick()
                }
            }
            .padding(vertical = Spacing.xs)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(Sizes.quickAction)
                .clip(Radii.mediumShape)
                .drawBehind { drawRect(colors.surface) }
                .border(Hairline.width, colors.hairline, Radii.mediumShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(action.icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(Sizes.icon))
        }
        Text(
            label,
            style = type.label,
            color = colors.text,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        if (labelSandbox) {
            Text(stringResource(R.string.home_sandbox_tag), style = type.label, color = colors.textSecondary, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LockBanner(onSetupPin: () -> Unit) {
    val colors = PayFlowTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen, vertical = Spacing.xs)
            .testTag(PayFlowTestTags.HOME_LOCK_BANNER)
            .clip(Radii.mediumShape)
            .drawBehind { drawRect(colors.pending.container) }
            .padding(start = Spacing.md, end = Spacing.xxs, top = Spacing.xxs, bottom = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(Icons.Outlined.Shield, contentDescription = null, tint = colors.pending.content, modifier = Modifier.size(Sizes.icon))
        Text(stringResource(R.string.home_lock_banner), style = PayFlowTheme.type.label, color = colors.pending.content, modifier = Modifier.weight(1f))
        GhostButton(stringResource(R.string.home_lock_setup), onSetupPin, outlined = false, debounceMs = 0L)
    }
}

/** People paid before. A tap opens Pay with the payee filled in, straight away: the ripple plays over the transition, not before it. */
@Composable
private fun RecentPayeesRow(payees: List<RecentPayee>, onPayee: (RecentPayee) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
        SectionHeader(stringResource(R.string.home_recent_payees), Modifier.padding(horizontal = Spacing.screen))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.sm),
            modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.HOME_PAYEES),
        ) {
            items(payees, key = { it.vpa }, contentType = { "payee" }) { payee -> PayeeItem(payee, onPayee) }
        }
    }
}

@Composable
private fun PayeeItem(payee: RecentPayee, onPayee: (RecentPayee) -> Unit) {
    val colors = PayFlowTheme.colors
    val haptics = LocalHaptics.current
    val press = rememberPressFeedback()
    val ripple = rememberAvatarRipple()
    val interaction = remember { MutableInteractionSource() }
    val name = remember(payee.name) { DisplayText.safe(payee.name) }
    val shortName = remember(name, payee.vpa) { name.substringBefore(' ').ifBlank { payee.vpa } }
    val description = stringResource(R.string.home_payee_description, name, payee.vpa)
    Column(
        Modifier
            .width(Sizes.avatarMedium + Spacing.xl)
            .testTag(PayFlowTestTags.homePayee(payee.vpa))
            .trackPress(press)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                haptics.light()
                ripple.play()
                onPayee(payee)
            }
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .padding(vertical = Spacing.xs)
                .pressScale(press, pressedScale = 0.94f)
                .drawWithCache {
                    val stroke = Stroke(Hairline.width.toPx() * 2f)
                    val reach = Spacing.xs.toPx()
                    onDrawBehind {
                        val p = ripple.value
                        if (p > 0f) drawCircle(colors.accent, radius = size.minDimension / 2f + reach * p, alpha = 1f - p, style = stroke)
                    }
                },
        ) {
            PayFlowAvatar(name, size = Sizes.avatarMedium)
        }
        Text(
            shortName,
            style = PayFlowTheme.type.label,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Spacing.xxs),
        )
    }
}

private const val FEED_FIRST_INDEX = 7

@Composable
private fun Feed(state: HomeUiState, callbacks: HomeCallbacks, amountsHidden: Boolean, memory: HomeEntranceMemory, play: Boolean) {
    val overline = (state as? HomeUiState.Content)?.summary?.let {
        val amount = if (amountsHidden) maskedAmount() else OdometerLogic.text(it.sentPaise, AmountDecimals.WhenNonZero, AmountSign.None)
        pluralStringResource(R.plurals.home_all_time, it.total, amount, it.total)
    }
    StaggeredEntrance(FEED_FIRST_INDEX, play = play) {
        SectionHeader(
            stringResource(R.string.home_recent),
            Modifier.padding(start = Spacing.screen, end = Spacing.xs, top = Spacing.md),
            overline = overline,
            actionLabel = if (state is HomeUiState.Content) stringResource(R.string.home_view_all) else null,
            onAction = callbacks.onViewAll,
        )
    }
    when (state) {
        HomeUiState.Loading -> SkeletonList(Modifier.fillMaxWidth().testTag(PayFlowTestTags.LOADING), count = HomeViewModel.RECENT_LIMIT)
        HomeUiState.Empty -> EmptyState(
            title = stringResource(R.string.home_empty_title),
            message = stringResource(R.string.home_empty_subtitle),
            illustration = IllustrationKind.EmptyLedger,
            modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.EMPTY),
        )
        is HomeUiState.Error -> ErrorState(
            title = stringResource(R.string.error_generic),
            retryLabel = stringResource(R.string.retry),
            onRetry = callbacks.onRetry,
            modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.ERROR),
        )
        is HomeUiState.Content -> Column(Modifier.fillMaxWidth()) {
            state.recent.forEachIndexed { index, payment ->
                key(payment.id) {
                    val playRow = remember { memory.firstSight(payment.id) }
                    StaggeredEntrance(FEED_FIRST_INDEX + 1 + index, play = playRow) {
                        Column {
                            PaymentFeedRow(payment, amountsHidden, payment.createdAt.relativeLabel(), callbacks.onPaymentClick)
                            if (index < state.recent.lastIndex) HairlineDivider(Modifier.padding(start = Spacing.screen + Sizes.avatar + Spacing.sm, end = Spacing.screen))
                        }
                    }
                }
            }
        }
    }
}

/** The store runs on the sandbox in both modes, so it says "Demo" whichever mode the app is in. */
@Composable
private fun DemoStoreRow(onCheckout: () -> Unit, play: Boolean) {
    val colors = PayFlowTheme.colors
    val title = stringResource(R.string.home_demo_store_title)
    val subtitle = stringResource(R.string.home_demo_store_subtitle)
    val tag = stringResource(R.string.home_demo_tag)
    StaggeredEntrance(FEED_FIRST_INDEX + 1, play = play) {
        Column(Modifier.padding(top = Spacing.xs)) {
            HairlineDivider()
            PayFlowListRow(
                title = title,
                subtitle = subtitle,
                leading = {
                    Box(Modifier.size(Sizes.avatar), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.ShoppingBag, contentDescription = null, tint = colors.accent, modifier = Modifier.size(Sizes.icon))
                    }
                },
                trailing = { StatusPill(StatusKind.Neutral, tag, animate = false) },
                onClick = onCheckout,
                modifier = Modifier
                    .testTag(PayFlowTestTags.HOME_CHECKOUT)
                    .semantics(mergeDescendants = true) { contentDescription = "$title, $tag" },
            )
            HairlineDivider()
        }
    }
}

@Composable
private fun Footer(sandbox: Boolean) {
    val colors = PayFlowTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(PayFlowTestTags.HOME_FOOTER)
            .padding(horizontal = Spacing.screen)
            .padding(top = Spacing.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(if (sandbox) Icons.Outlined.Science else Icons.Outlined.Info, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(Sizes.iconSmall))
        Text(stringResource(if (sandbox) R.string.home_sandbox_footer else R.string.home_real_footer), style = PayFlowTheme.type.label, color = colors.textSecondary)
    }
}
