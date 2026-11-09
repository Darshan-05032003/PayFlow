package com.darshan.payflow.ui.navigation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darshan.payflow.R
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.components.BottomNavChange
import com.darshan.payflow.ui.designsystem.components.BottomNavState
import com.darshan.payflow.ui.designsystem.components.NavGlyphs
import com.darshan.payflow.ui.designsystem.components.NavItem
import com.darshan.payflow.ui.designsystem.components.PayFlowBottomNav
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlinx.coroutines.flow.Flow

/**
 * Height the floating bar takes above the system navigation area. A tab screen keeps this much free at the end of its scroll
 * and lifts anything anchored to the bottom by it. Zero outside the shell.
 */
val LocalBottomBarInset = compositionLocalOf { 0.dp }

/** Space the floating bar needs: its height and its margin on each side. */
val BottomBarInset: Dp = Sizes.navBarHeight + Spacing.xs * 2

/** Carries "the current tab was tapped again". */
val LocalTabReselects = staticCompositionLocalOf<Flow<ShellRoute>?> { null }

/** Runs [onReselect] when the bar's item for [tab] is tapped while already there: the convention for scrolling to the top. */
@Composable
fun TabReselectEffect(tab: ShellRoute, onReselect: suspend () -> Unit) {
    val reselects = LocalTabReselects.current ?: return
    val action by rememberUpdatedState(onReselect)
    LaunchedEffect(reselects, tab) {
        reselects.collect { if (it == tab) action() }
    }
}

@Composable
fun ScrollToTopOnReselect(tab: ShellRoute, state: ScrollState) {
    val reduced = LocalMotion.current.reduced
    TabReselectEffect(tab) { if (reduced) state.scrollTo(0) else state.animateScrollTo(0) }
}

@Composable
fun ScrollToTopOnReselect(tab: ShellRoute, state: LazyListState) {
    val reduced = LocalMotion.current.reduced
    TabReselectEffect(tab) {
        if (reduced || state.firstVisibleItemIndex > SCROLL_ANIMATION_LIMIT) state.scrollToItem(0) else state.animateScrollToItem(0)
    }
}

/** Past this many rows an animated scroll to the top would compose every row on the way. */
private const val SCROLL_ANIMATION_LIMIT = 30

/**
 * The app's bar. History carries [PayFlowTestTags.HOME_HISTORY], the tag the benchmarks use to open History from the first
 * screen. Content fades out just above the dock, and everything from the dock's top edge down is solid background, so no half
 * readable text shows beside or below it.
 */
@Composable
fun ShellBottomBar(state: BottomNavState, onChange: (BottomNavChange) -> Unit, modifier: Modifier = Modifier) {
    val homeLabel = stringResource(R.string.nav_home)
    val scanLabel = stringResource(R.string.nav_scan)
    val historyLabel = stringResource(R.string.nav_history)
    val meLabel = stringResource(R.string.nav_me)
    val items = remember(homeLabel, scanLabel, historyLabel, meLabel) {
        listOf(
            NavItem("home", homeLabel, NavGlyphs.Home, PayFlowTestTags.NAV_HOME),
            NavItem("scan", scanLabel, NavGlyphs.Scan, PayFlowTestTags.NAV_SCAN),
            NavItem("history", historyLabel, NavGlyphs.History, PayFlowTestTags.HOME_HISTORY),
            NavItem("me", meLabel, NavGlyphs.Profile, PayFlowTestTags.NAV_ME),
        )
    }
    val background = PayFlowTheme.colors.bg
    PayFlowBottomNav(
        items = items,
        state = state,
        onChange = onChange,
        modifier = modifier.drawWithCache {
            val rise = Spacing.xl.toPx()
            val solidFrom = Spacing.xs.toPx()
            val fade = Brush.verticalGradient(listOf(background.copy(alpha = 0f), background), startY = -rise, endY = solidFrom)
            onDrawBehind {
                drawRect(fade, topLeft = Offset(0f, -rise), size = Size(size.width, rise + solidFrom))
                drawRect(background, topLeft = Offset(0f, solidFrom), size = Size(size.width, size.height - solidFrom))
            }
        },
    )
}
