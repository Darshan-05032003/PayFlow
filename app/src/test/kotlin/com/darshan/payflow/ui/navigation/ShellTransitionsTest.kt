package com.darshan.payflow.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionDurations
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The real transition table on a small NavHost, read through the positions of the screens mid-flight. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ShellTransitionsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var nav: NavHostController

    private fun show(reduceMotion: Boolean = false) {
        rule.setContent {
            PayFlowTheme(darkTheme = false, reduceMotion = reduceMotion) {
                val motion = LocalMotion.current
                val transitions = remember(motion) { ShellTransitions(motion) }
                nav = rememberNavController()
                fun route(entry: androidx.navigation.NavBackStackEntry) = entry.destination.shellRoute()
                NavHost(
                    navController = nav,
                    startDestination = HomeDestination,
                    enterTransition = { transitions.enter(route(initialState), route(targetState)) },
                    exitTransition = { transitions.exit(route(initialState), route(targetState)) },
                    popEnterTransition = { transitions.popEnter(route(initialState), route(targetState)) },
                    popExitTransition = { transitions.popExit(route(initialState), route(targetState)) },
                    sizeTransform = null,
                ) {
                    composable<HomeDestination> { Box(Modifier.fillMaxSize().testTag(HOME)) }
                    composable<HistoryDestination> { Box(Modifier.fillMaxSize().testTag(HISTORY)) }
                    composable<ReceiveDestination> { Box(Modifier.fillMaxSize().testTag(RECEIVE)) }
                    composable<ScanDestination> { Box(Modifier.fillMaxSize().testTag(SCAN)) }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun left(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left
    private fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
    private fun midFlight() {
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        rule.mainClock.advanceTimeBy(MotionDurations.MicroFast.toLong())
    }
    private fun settle() {
        rule.mainClock.advanceTimeBy(MotionDurations.ScreenSlow * 2L)
    }
    private fun navigate(block: () -> Unit) {
        rule.runOnUiThread(block)
        rule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun aPushSlidesInFromTheEndAndSettlesInPlace() {
        show()
        navigate { nav.navigate(ReceiveDestination) }
        midFlight()
        assertThat(left(RECEIVE)).isGreaterThan(0f)
        settle()
        assertThat(left(RECEIVE)).isEqualTo(0f)
        assertThat(exists(HOME)).isFalse()
    }

    @Test
    fun aPopComesBackFromTheStart() {
        show()
        navigate { nav.navigate(ReceiveDestination) }
        settle()
        navigate { nav.popBackStack() }
        midFlight()
        assertThat(left(HOME)).isLessThan(0f)
        settle()
        assertThat(left(HOME)).isEqualTo(0f)
        assertThat(exists(RECEIVE)).isFalse()
    }

    @Test
    fun tabsDoNotSlide() {
        show()
        navigate { nav.navigate(HistoryDestination) }
        midFlight()
        assertThat(exists(HISTORY)).isTrue()
        assertThat(left(HISTORY)).isEqualTo(0f)
        settle()
        assertThat(left(HISTORY)).isEqualTo(0f)
    }

    @Test
    fun theScannerRisesFromBelowWithoutMovingSideways() {
        show()
        navigate { nav.navigate(ScanDestination) }
        midFlight()
        assertThat(top(SCAN)).isGreaterThan(0f)
        assertThat(left(SCAN)).isEqualTo(0f)
        settle()
        assertThat(top(SCAN)).isEqualTo(0f)
    }

    @Test
    fun reducedMotionOnlyFadesAndFinishesQuickly() {
        show(reduceMotion = true)
        navigate { nav.navigate(ReceiveDestination) }
        midFlight()
        assertThat(left(RECEIVE)).isEqualTo(0f)
        rule.mainClock.advanceTimeBy(MotionDurations.ReducedFade.toLong() + REDUCED_SLACK_MS)
        assertThat(exists(HOME)).isFalse()

        navigate { nav.navigate(ScanDestination) }
        midFlight()
        assertThat(top(SCAN)).isEqualTo(0f)
    }

    @Test
    fun aPredictiveBackGestureShowsThePreviousScreenAndCancelsCleanly() {
        show()
        navigate { nav.navigate(ReceiveDestination) }
        settle()
        val back = rule.activity.onBackPressedDispatcher
        rule.runOnUiThread {
            back.dispatchOnBackStarted(gesture(0f))
            back.dispatchOnBackProgressed(gesture(0.5f))
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertThat(back.hasEnabledCallbacks()).isTrue()
        assertThat(exists(HOME)).isTrue()
        assertThat(exists(RECEIVE)).isTrue()
        assertThat(nav.currentDestination?.hasRoute<ReceiveDestination>()).isTrue()

        rule.runOnUiThread { back.dispatchOnBackCancelled() }
        rule.waitForIdle()
        assertThat(exists(RECEIVE)).isTrue()
        assertThat(exists(HOME)).isFalse()
        assertThat(nav.currentDestination?.hasRoute<ReceiveDestination>()).isTrue()
    }

    @Test
    fun aPredictiveBackGestureCompletes() {
        show()
        navigate { nav.navigate(ReceiveDestination) }
        settle()
        val back = rule.activity.onBackPressedDispatcher
        rule.runOnUiThread {
            back.dispatchOnBackStarted(gesture(0f))
            back.dispatchOnBackProgressed(gesture(0.8f))
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        assertThat(exists(HOME)).isTrue()
        assertThat(exists(RECEIVE)).isFalse()
        assertThat(nav.currentDestination?.hasRoute<HomeDestination>()).isTrue()
    }

    private fun gesture(progress: Float) = BackEventCompat(0f, 100f, progress, BackEventCompat.EDGE_LEFT)

    private companion object {
        const val HOME = "t:home"
        const val HISTORY = "t:history"
        const val RECEIVE = "t:receive"
        const val SCAN = "t:scan"
        const val REDUCED_SLACK_MS = 150L
    }
}
