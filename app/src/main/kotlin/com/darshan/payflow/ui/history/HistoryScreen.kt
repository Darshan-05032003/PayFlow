package com.darshan.payflow.ui.history

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.PaymentFeedRow
import com.darshan.payflow.ui.components.SandboxBadgeIfActive
import com.darshan.payflow.ui.designsystem.components.AmountDecimals
import com.darshan.payflow.ui.designsystem.components.AmountSign
import com.darshan.payflow.ui.designsystem.components.BrandLoader
import com.darshan.payflow.ui.designsystem.components.EmptyState
import com.darshan.payflow.ui.designsystem.components.ErrorState
import com.darshan.payflow.ui.designsystem.components.HairlineDivider
import com.darshan.payflow.ui.designsystem.components.IllustrationKind
import com.darshan.payflow.ui.designsystem.components.OdometerLogic
import com.darshan.payflow.ui.designsystem.components.PayFlowChoiceChip
import com.darshan.payflow.ui.designsystem.components.PayFlowIconButton
import com.darshan.payflow.ui.designsystem.components.PayFlowSearchField
import com.darshan.payflow.ui.designsystem.components.PayFlowToastHost
import com.darshan.payflow.ui.designsystem.components.SkeletonList
import com.darshan.payflow.ui.designsystem.components.ToastHostState
import com.darshan.payflow.ui.designsystem.components.rememberToastHostState
import com.darshan.payflow.ui.designsystem.effects.CollectUiEffects
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.effects.ToastTone
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.navigation.LocalBottomBarInset
import com.darshan.payflow.ui.navigation.ScrollToTopOnReselect
import com.darshan.payflow.ui.navigation.ShellRoute
import com.darshan.payflow.ui.navigation.paymentContainerKey
import com.darshan.payflow.ui.theme.PayFlowTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

data class HistoryCallbacks(
    /** Null on a tab, where back is the system's job and an arrow would suggest there is somewhere to go up to. */
    val onBack: (() -> Unit)? = {},
    val onFilter: (HistoryFilter) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onPaymentClick: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    /** Opens Pay with the payee filled in. Never the amount: a repeat payment is typed again on purpose. */
    val onPayAgain: (Payment) -> Unit = {},
    val onCheckStatus: (String) -> Unit = {},
    val onToggleAmounts: () -> Unit = {},
)

@Composable
fun HistoryRoute(
    onBack: (() -> Unit)?,
    onPaymentClick: (String) -> Unit,
    onPayAgain: (Payment) -> Unit = {},
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hidden by viewModel.amountsHidden.collectAsStateWithLifecycle()
    val toasts = rememberToastHostState()
    val resources = LocalResources.current
    CollectUiEffects(
        viewModel.effects,
        onOther = { effect ->
            if (effect is HistoryEffect) toasts.show(checkMessage(effect, resources), if (effect is HistoryEffect.CheckFailed) ToastTone.Failed else ToastTone.Info)
        },
    )
    HistoryScreen(
        state = state,
        callbacks = HistoryCallbacks(
            onBack = onBack,
            onFilter = viewModel::setFilter,
            onQuery = viewModel::setQuery,
            onPaymentClick = onPaymentClick,
            onRetry = viewModel::retry,
            onRefresh = viewModel::refresh,
            onPayAgain = onPayAgain,
            onCheckStatus = { viewModel.checkPending() },
            onToggleAmounts = viewModel::toggleAmountsHidden,
        ),
        amountsHidden = hidden,
        zone = viewModel.zone,
        toasts = toasts,
    )
}

/** The same words as Home's refresh, since it's the same question to the provider. */
internal fun checkMessage(effect: HistoryEffect, resources: android.content.res.Resources): String = when (effect) {
    HistoryEffect.CheckFailed -> resources.getString(R.string.home_refresh_failed)
    is HistoryEffect.Checked -> when {
        effect.settled > 0 -> resources.getQuantityString(R.plurals.home_refresh_settled, effect.settled, effect.settled)
        effect.checked > 0 -> resources.getQuantityString(R.plurals.home_refresh_waiting, effect.checked, effect.checked)
        else -> resources.getString(R.string.home_refresh_nothing)
    }
}

@Composable
fun HistoryFilter.label(): String = stringResource(
    when (this) {
        HistoryFilter.ALL -> R.string.filter_all
        HistoryFilter.SUCCESS -> R.string.filter_success
        HistoryFilter.PENDING -> R.string.filter_pending
        HistoryFilter.FAILED -> R.string.filter_failed
        HistoryFilter.REFUNDED -> R.string.filter_refunded
    },
)

@Composable
private fun HistoryFilter.emptyTitle(): String = stringResource(
    when (this) {
        HistoryFilter.ALL -> R.string.history_empty_all
        HistoryFilter.SUCCESS -> R.string.history_empty_success
        HistoryFilter.PENDING -> R.string.history_empty_pending
        HistoryFilter.FAILED -> R.string.history_empty_failed
        HistoryFilter.REFUNDED -> R.string.history_empty_refunded
    },
)

@Composable
fun DayLabel.text(): String = when (this) {
    DayLabel.Today -> stringResource(R.string.history_day_today)
    DayLabel.Yesterday -> stringResource(R.string.history_day_yesterday)
    is DayLabel.Date -> remember(this) { (if (showYear) DAY_WITH_YEAR else DAY_ONLY).format(date) }
}

private val DAY_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
private val DAY_WITH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
private val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/**
 * History: a title that gives way to a search field, filter chips, then the payments in days under sticky headers. Rows
 * swipe left for their actions, which TalkBack also offers. Amounts follow Home's hide setting. Only the visible rows are
 * composed, so 10,000+ payments scroll like 50.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    callbacks: HistoryCallbacks,
    amountsHidden: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
    toasts: ToastHostState = rememberToastHostState(),
) {
    val colors = PayFlowTheme.colors
    // A refresh the user asked for keeps the list in view under the spinner until the new answer arrives.
    var refreshingInPlace by remember { mutableStateOf(false) }
    val lastAnswer = remember { arrayOfNulls<HistoryUiState>(1) }
    val shown = HistoryReload.shown(state, lastAnswer[0], refreshingInPlace)
    if (state !is HistoryUiState.Loading) lastAnswer[0] = state
    LaunchedEffect(state is HistoryUiState.Loading) { if (state !is HistoryUiState.Loading) refreshingInPlace = false }
    val screenCallbacks = remember(callbacks) {
        callbacks.copy(
            onRefresh = {
                refreshingInPlace = true
                callbacks.onRefresh()
            },
        )
    }
    val selectedFilter = when (shown) {
        is HistoryUiState.Content -> shown.filter
        is HistoryUiState.Empty -> shown.filter
        else -> HistoryFilter.ALL
    }
    val currentQuery = when (shown) {
        is HistoryUiState.Content -> shown.query
        is HistoryUiState.Empty -> shown.query
        else -> ""
    }
    var text by rememberSaveable(currentQuery) { mutableStateOf(currentQuery) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    ScrollToTopOnReselect(ShellRoute.History, listState)
    val clearSearch = {
        searchOpen = false
        text = ""
        callbacks.onQuery("")
    }

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            HistoryHeader(
                text = text,
                onText = { text = it; callbacks.onQuery(it) },
                searchOpen = searchOpen,
                onSearchOpen = { open ->
                    searchOpen = open
                    if (!open && text.isNotEmpty()) {
                        text = ""
                        callbacks.onQuery("")
                    }
                },
                amountsHidden = amountsHidden,
                callbacks = screenCallbacks,
            )
            FilterChips(selectedFilter, callbacks.onFilter)
            HistoryBody(shown, screenCallbacks, amountsHidden, zone, listState, HistoryReload.spinner(state, refreshingInPlace), clearSearch)
        }
        PayFlowToastHost(toasts, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = LocalBottomBarInset.current))
    }
}

/**
 * The title row and the search field share one slot. Opening search grows the field out of the search button while the
 * title fades; both are read from one progress value in the layout and layer phases, so the morph recomposes nothing.
 */
@Composable
private fun HistoryHeader(
    text: String,
    onText: (String) -> Unit,
    searchOpen: Boolean,
    onSearchOpen: (Boolean) -> Unit,
    amountsHidden: Boolean,
    callbacks: HistoryCallbacks,
) {
    val colors = PayFlowTheme.colors
    val motion = LocalMotion.current
    val expanded = SearchMorph.expanded(searchOpen, text)
    val progress = remember { Animatable(if (expanded) 1f else 0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(expanded) {
        progress.animateTo(if (expanded) 1f else 0f, motion.spec(MotionToken.Snap))
    }
    LaunchedEffect(searchOpen) { if (searchOpen) runCatching { focus.requestFocus() } }
    val fieldShown by remember { derivedStateOf { progress.value > 0f } }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = Sizes.topBarHeight)
            .padding(end = Spacing.xxs),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = 1f - progress.value }
                .then(if (expanded) Modifier.clearAndSetSemantics {} else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            callbacks.onBack?.let { onBack ->
                PayFlowIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), onBack, tint = colors.text)
            }
            Text(
                stringResource(R.string.history_title),
                style = PayFlowTheme.type.display,
                color = colors.text,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    // The title sits on the screen gutter, exactly where the rows' avatars start.
                    .padding(start = if (callbacks.onBack == null) Spacing.screen else Spacing.xxs)
                    .semantics { heading() },
            )
            SandboxBadgeIfActive(Modifier.padding(end = Spacing.xxs))
            PayFlowIconButton(
                if (amountsHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                stringResource(if (amountsHidden) R.string.home_show_amounts else R.string.home_hide_amounts),
                callbacks.onToggleAmounts,
                tint = colors.textSecondary,
                modifier = Modifier.testTag(PayFlowTestTags.HISTORY_HIDE_AMOUNTS),
            )
            PayFlowIconButton(Icons.Outlined.Refresh, stringResource(R.string.history_refresh), callbacks.onRefresh, tint = colors.textSecondary, modifier = Modifier.testTag(PayFlowTestTags.HISTORY_REFRESH))
            PayFlowIconButton(Icons.Outlined.Search, stringResource(R.string.history_search_open), { onSearchOpen(true) }, tint = colors.text, modifier = Modifier.testTag(PayFlowTestTags.HISTORY_SEARCH_OPEN))
        }
        if (expanded || fieldShown) {
            val gutter = Spacing.screen
            PayFlowSearchField(
                value = text,
                onValueChange = onText,
                placeholder = stringResource(R.string.history_search),
                closeLabel = stringResource(R.string.history_search_close),
                onClose = { onSearchOpen(false) },
                focusRequester = focus,
                fieldTag = PayFlowTestTags.SEARCH_FIELD,
                closeTag = PayFlowTestTags.HISTORY_SEARCH_CLOSE,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(start = gutter, end = gutter - Spacing.xxs)
                    // Laid out at full width from the start, and revealed from the search button outward: a pill that grows
                    // from the button's circle to the full field. The reveal is read in the draw phase.
                    .drawWithCache {
                        val path = Path()
                        val button = Sizes.touchTarget.toPx()
                        onDrawWithContent {
                            val p = progress.value.coerceIn(0f, 1f)
                            val visible = button + (size.width - button) * p
                            val left = if (rtl) 0f else size.width - visible
                            path.reset()
                            path.addRoundRect(RoundRect(left, 0f, left + visible, size.height, CornerRadius(size.height / 2)))
                            clipPath(path) { this@onDrawWithContent.drawContent() }
                        }
                    }
                    .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                    .then(if (expanded) Modifier else Modifier.clearAndSetSemantics {}),
            )
        }
    }
}

@Composable
private fun FilterChips(selected: HistoryFilter, onFilter: (HistoryFilter) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        items(HistoryFilter.entries, key = { it.name }) { filter ->
            PayFlowChoiceChip(
                label = filter.label(),
                selected = filter == selected,
                onClick = { onFilter(filter) },
                modifier = Modifier.testTag(PayFlowTestTags.filterChip(filter)),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HistoryBody(
    state: HistoryUiState,
    callbacks: HistoryCallbacks,
    amountsHidden: Boolean,
    zone: ZoneId,
    listState: androidx.compose.foundation.lazy.LazyListState,
    refreshing: Boolean,
    onClearSearch: () -> Unit,
) {
    val colors = PayFlowTheme.colors
    val haptics = LocalHaptics.current
    val density = LocalDensity.current
    val barInset = LocalBottomBarInset.current
    val pull = rememberPullToRefreshState()
    val thresholdPx = with(density) { PullToRefreshDefaults.PositionalThreshold.toPx() }
    var openRow by remember { mutableStateOf<String?>(null) }
    // Only the row under the finger, or the last one opened (for the way back), is an end of the container transform. A shared
    // element registers with the shell as it composes, which recomposed the visible rows on every fling when every row had one.
    // A tap's finger lands at least a frame before its click, which is what the transform needs to find its pair. A click with
    // no finger (TalkBack, a keyboard) opens with the plain cross-fade.
    var transformRow by rememberSaveable { mutableStateOf<String?>(null) }
    val openPayment: (String) -> Unit = remember(callbacks) {
        { id ->
            transformRow = id
            callbacks.onPaymentClick(id)
        }
    }
    LaunchedEffect(pull) {
        var previous = 0f
        androidx.compose.runtime.snapshotFlow { pull.distanceFraction }.collect { current ->
            if (previous < 1f && current >= 1f) haptics.tick()
            previous = current
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .pullToRefresh(isRefreshing = refreshing, state = pull, onRefresh = callbacks.onRefresh)
            .testTag(PayFlowTestTags.HISTORY_PULL),
    ) {
        when (state) {
            HistoryUiState.Loading -> SkeletonList(Modifier.fillMaxWidth().padding(top = Spacing.xs).testTag(PayFlowTestTags.LOADING), count = SKELETON_ROWS)
            is HistoryUiState.Error -> ErrorState(
                title = stringResource(R.string.history_error_title),
                message = stringResource(R.string.error_try_again_hint),
                retryLabel = stringResource(R.string.retry),
                onRetry = callbacks.onRetry,
                modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.ERROR),
            )
            // A search or a filter that finds nothing leads back to everything; an empty history has nowhere to go back to.
            is HistoryUiState.Empty -> when {
                state.query.isNotBlank() -> EmptyState(
                    title = stringResource(R.string.history_empty_search, state.query),
                    message = stringResource(R.string.history_empty_search_hint),
                    illustration = IllustrationKind.NoResults,
                    actionLabel = stringResource(R.string.history_clear_search),
                    onAction = onClearSearch,
                    actionModifier = Modifier.testTag(PayFlowTestTags.HISTORY_EMPTY_ACTION),
                    modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.EMPTY),
                )
                state.filter != HistoryFilter.ALL -> EmptyState(
                    title = state.filter.emptyTitle(),
                    message = stringResource(R.string.history_empty_filtered_hint),
                    illustration = IllustrationKind.EmptyLedger,
                    actionLabel = stringResource(R.string.history_show_all),
                    onAction = { callbacks.onFilter(HistoryFilter.ALL) },
                    actionModifier = Modifier.testTag(PayFlowTestTags.HISTORY_EMPTY_ACTION),
                    modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.EMPTY),
                )
                else -> EmptyState(
                    title = state.filter.emptyTitle(),
                    message = stringResource(R.string.history_empty_hint),
                    illustration = IllustrationKind.EmptyLedger,
                    modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.EMPTY),
                )
            }
            is HistoryUiState.Content -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.PAYMENT_LIST),
            ) {
                item(key = "count", contentType = "count") {
                    Text(
                        stringResource(R.string.history_count, state.payments.size),
                        style = PayFlowTheme.type.label,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = Spacing.screen).padding(top = Spacing.xxs),
                    )
                }
                state.entries.forEach { entry ->
                    when (entry) {
                        is HistoryEntry.Day -> stickyHeader(key = entry.key, contentType = entry.contentType) { DayHeader(entry, amountsHidden) }
                        is HistoryEntry.Row -> item(key = entry.key, contentType = entry.contentType) {
                            SwipeRow(entry, amountsHidden, zone, openRow, { openRow = it }, callbacks, entry.payment.id == transformRow, { transformRow = it }, openPayment)
                        }
                    }
                }
                item(key = "end", contentType = "end") { Spacer(Modifier.navigationBarsPadding().height(barInset + Spacing.xl)) }
            }
        }
        // The pull's own indicator: the brand loader slides down with the finger and spins while the list reloads.
        BrandLoader(
            size = Sizes.loaderSmall,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    val f = if (refreshing) 1f else pull.distanceFraction.coerceIn(0f, 1.5f)
                    translationY = f * thresholdPx / 2 - Sizes.loaderSmall.toPx()
                    alpha = f.coerceIn(0f, 1f)
                }
                .clearAndSetSemantics {},
        )
    }
}

private const val SKELETON_ROWS = 8
@Composable
private fun DayHeader(day: HistoryEntry.Day, amountsHidden: Boolean) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val label = day.label.text()
    val sent = if (amountsHidden) OdometerLogic.hiddenText() else OdometerLogic.text(day.sentPaise, AmountDecimals.WhenNonZero, AmountSign.None)
    val spokenSent = if (amountsHidden) stringResource(R.string.ds_amount_hidden) else Money.formatInr(day.sentPaise)
    val description = pluralStringResource(R.plurals.history_day_description, day.count, label, day.count, spokenSent)
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .heightIn(min = Sizes.touchTarget)
            .padding(horizontal = Spacing.screen)
            .testTag(PayFlowTestTags.historyDay(day.date.toString()))
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = description
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = type.labelStrong, color = colors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (day.sentPaise > 0) Text(stringResource(R.string.history_day_sent, sent), style = type.label, color = colors.textSecondary, maxLines = 1)
    }
}

/**
 * A payment that slides left to show its actions. The slide lives in [RowSwipeHolder]; this feeds it drag deltas, springs
 * to where it decides, and reads the offset in the layer phase. One row is open at a time.
 */
@Composable
private fun SwipeRow(
    entry: HistoryEntry.Row,
    amountsHidden: Boolean,
    zone: ZoneId,
    openRow: String?,
    onOpenRow: (String?) -> Unit,
    callbacks: HistoryCallbacks,
    transformEnd: Boolean,
    onFingerDown: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val payment = entry.payment
    val colors = PayFlowTheme.colors
    val motion = LocalMotion.current
    val haptics = LocalHaptics.current
    val actions = remember(payment.status, payment.direction, payment.handlerPackage) { HistoryRowActions.of(payment) }
    val trayPx = with(LocalDensity.current) { Sizes.rowSwipeAction.toPx() } * actions.size
    val holder = remember(trayPx) { RowSwipeHolder(trayPx) }
    var offset by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val direction = if (rtl) -1f else 1f

    fun settle(target: RowSwipeTarget, velocity: Float = 0f) {
        scope.launch {
            animate(offset, holder.targetOffset(target), velocity, motion.spec(MotionToken.Snap)) { value, _ ->
                holder.snapTo(value)
                offset = holder.offset
            }
        }
        if (target == RowSwipeTarget.Open) {
            haptics.tick()
            onOpenRow(entry.key)
        } else if (openRow == entry.key) {
            onOpenRow(null)
        }
    }
    LaunchedEffect(openRow) { if (openRow != entry.key && offset != 0f) settle(RowSwipeTarget.Closed) }

    val labels = actions.map { it.label() }
    val runAction: (RowAction) -> Unit = { action ->
        settle(RowSwipeTarget.Closed)
        when (action) {
            RowAction.PayAgain -> callbacks.onPayAgain(payment)
            RowAction.CheckStatus -> callbacks.onCheckStatus(payment.id)
        }
    }
    val time = remember(payment.createdAt, zone) { TIME.format(payment.createdAt.atZone(zone)) }

    Box(Modifier.fillMaxWidth()) {
        if (actions.isNotEmpty()) {
            Row(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = (-offset / trayPx).coerceIn(0f, 1f) }
                    .clearAndSetSemantics {},
                horizontalArrangement = Arrangement.End,
            ) {
                actions.forEachIndexed { i, action -> TrayAction(action, labels[i], payment.id) { runAction(action) } }
            }
        }
        val drag = rememberDraggableState { delta ->
            holder.dragBy(delta * direction)
            offset = holder.offset
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset * direction }
                .background(colors.bg)
                // After the slide's layer, so it moves with the row and never covers the tray it uncovers.
                .pointerInput(payment.id) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        onFingerDown(payment.id)
                    }
                }
                .draggable(
                    drag,
                    Orientation.Horizontal,
                    enabled = actions.isNotEmpty(),
                    onDragStarted = { if (openRow != entry.key) onOpenRow(entry.key) },
                    onDragStopped = { velocity -> settle(holder.release(velocity * direction), velocity * direction) },
                ),
        ) {
            Column {
                PaymentFeedRow(
                    payment = payment,
                    amountsHidden = amountsHidden,
                    timeLabel = time,
                    onClick = { id -> if (offset != 0f) settle(RowSwipeTarget.Closed) else onOpen(id) },
                    containerKey = if (transformEnd) paymentContainerKey(payment.id) else null,
                    modifier = Modifier.semantics {
                        customActions = actions.mapIndexed { i, action -> CustomAccessibilityAction(labels[i]) { runAction(action); true } }
                    },
                )
                if (!entry.last) HairlineDivider(Modifier.padding(start = Spacing.screen + Sizes.avatar + Spacing.sm, end = Spacing.screen))
            }
        }
    }
}

@Composable
private fun RowAction.label(): String = stringResource(
    when (this) {
        RowAction.PayAgain -> R.string.history_action_pay_again
        RowAction.CheckStatus -> R.string.history_action_check
    },
)

private fun RowAction.icon(): ImageVector = when (this) {
    RowAction.PayAgain -> Icons.Outlined.Replay
    RowAction.CheckStatus -> Icons.Outlined.Sync
}

@Composable
private fun TrayAction(action: RowAction, label: String, paymentId: String, onClick: () -> Unit) {
    val colors = PayFlowTheme.colors
    val tone = if (action == RowAction.CheckStatus) colors.pending else colors.success
    val tint = if (action == RowAction.PayAgain) colors.accent else tone.strong
    Column(
        Modifier
            .width(Sizes.rowSwipeAction)
            .fillMaxHeight()
            .background(colors.raised)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(PayFlowTestTags.historyAction(paymentId, action.name)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs, Alignment.CenterVertically),
    ) {
        Icon(action.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(Sizes.icon))
        Text(label, style = PayFlowTheme.type.label, color = colors.text, textAlign = TextAlign.Center, maxLines = 2)
    }
}
