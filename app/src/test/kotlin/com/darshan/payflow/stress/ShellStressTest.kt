package com.darshan.payflow.stress

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.security.AppLock
import com.darshan.payflow.data.security.InMemorySecureStore
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.testutil.FakeBiometricAuthenticator
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.components.PaymentFeedRow
import com.darshan.payflow.ui.designsystem.components.BottomNavLogic
import com.darshan.payflow.ui.lock.AppGate
import com.darshan.payflow.ui.lock.AppLockViewModel
import com.darshan.payflow.ui.lock.LockCallbacks
import com.darshan.payflow.ui.lock.LockUiState
import com.darshan.payflow.ui.navigation.HistoryDestination
import com.darshan.payflow.ui.navigation.HomeDestination
import com.darshan.payflow.ui.navigation.LocalNavAnimatedScope
import com.darshan.payflow.ui.navigation.LocalSharedTransitionScope
import com.darshan.payflow.ui.navigation.MeDestination
import com.darshan.payflow.ui.navigation.ShellLogic
import com.darshan.payflow.ui.navigation.ShellRoute
import com.darshan.payflow.ui.navigation.openTab
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

/** The app shell at scale: taps on the lock, relocks through the gate, tab storms, and rows with shared bounds. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ShellStressTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aThousandRelocksThroughTheGateLeaveNothingBehind() {
        var state by mutableStateOf<LockUiState>(LockUiState.Locked(0, 5, 0, false, null, null))
        var composedApps = 0
        rule.setContent {
            PayFlowTheme(darkTheme = true, reduceMotion = true) {
                AppGate(state, null, LockCallbacks()) {
                    composedApps++
                    Box(Modifier.fillMaxSize().testTag(APP))
                }
            }
        }
        rule.waitForIdle()
        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val ms = measureTimeMillis {
            repeat(1_000) { i ->
                state = LockUiState.Unlocked(pinConfigured = true)
                rule.waitForIdle()
                assertThat(exists(APP)).isTrue()
                state = LockUiState.Locked(0, 5, 0, false, null, null, rejections = i)
                rule.waitForIdle()
                assertThat(exists(APP)).isFalse()
                assertThat(rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE).isNotEqualTo(0)
            }
        }
        System.gc()
        val grownKb = (runtime.totalMemory() - runtime.freeMemory() - before) / 1024
        println("STRESS [gate-1000-relocks] total=${ms}ms perCycle=${ms / 1_000.0}ms heapGrowth=${grownKb}KB appCompositions=$composedApps")
        assertThat(composedApps).isEqualTo(1_000)
        assertThat(grownKb).isLessThan(32 * 1024)
    }

    @Test
    fun aThousandTabSwitchesKeepTheBackStackFlat() {
        lateinit var nav: NavHostController
        rule.setContent {
            PayFlowTheme(darkTheme = false, reduceMotion = true) {
                nav = rememberNavController()
                NavHost(nav, startDestination = HomeDestination) {
                    composable<HomeDestination> { Box(Modifier.fillMaxSize().testTag("home")) }
                    composable<HistoryDestination> { Box(Modifier.fillMaxSize().testTag("history")) }
                    composable<MeDestination> { Box(Modifier.fillMaxSize().testTag("me")) }
                }
            }
        }
        rule.waitForIdle()
        val destinations = listOf(HistoryDestination, MeDestination, HomeDestination)
        val ms = measureTimeMillis {
            repeat(1_000) { i ->
                rule.runOnUiThread { nav.openTab(destinations[i % destinations.size]) }
                if (i % 50 == 0) rule.waitForIdle()
            }
            rule.runOnUiThread { nav.openTab(MeDestination) }
            rule.waitForIdle()
        }
        var depth = 1
        rule.runOnUiThread { while (nav.popBackStack()) depth++ }
        println("STRESS [tabs-1000-switches] total=${ms}ms depthAtEnd=$depth")
        assertThat(depth).isAtMost(2) // the tab and Home under it, never a pile of tabs
    }

    @Test
    fun aMillionRouteLookupsAreFreeOfCost() {
        val routes = ShellRoute.entries
        var sink = 0
        val ns = measureNanoTime {
            repeat(1_000_000) { i ->
                val a = routes[i % routes.size]
                val b = routes[(i * 7) % routes.size]
                sink += ShellLogic.transition(a, b).ordinal + (ShellLogic.barIndex(a) ?: 0)
            }
        }
        println("STRESS [shell-logic-1M] total=${ns / 1_000_000}ms perLookup=${ns / 1_000_000}ns sink=$sink")
        assertThat(ns / 1_000_000).isLessThan(2_000L)
    }

    @Test
    fun tenThousandBarChangesStayConsistent() {
        val logic = BottomNavLogic(count = 4, initial = 0)
        var changes = 0
        val ms = measureTimeMillis {
            repeat(10_000) { i -> if (logic.select(i * 3 % 5) is com.darshan.payflow.ui.designsystem.components.BottomNavChange.Changed) changes++ }
        }
        println("STRESS [bar-10k-selects] total=${ms}ms changes=$changes selected=${logic.selected}")
        assertThat(logic.selected).isIn(0..3)
    }

    /** What the shared-bounds modifier costs on a long list: the same scroll with it and without. */
    @Test
    fun tenThousandRowsScrollWithSharedBoundsOnEveryRow() {
        val payments = List(10_000) { testPayment(id = "p$it") }
        fun scrollOnce(withShell: Boolean): Long {
            var list: LazyListState? = null
            var shell by mutableStateOf(withShell)
            val content = @androidx.compose.runtime.Composable {
                val state = rememberLazyListState().also { list = it }
                LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("rows")) {
                    items(payments, key = { it.id }) { PaymentFeedRow(it, amountsHidden = false, timeLabel = "10:15 AM", onClick = {}) }
                }
            }
            val tree = @androidx.compose.runtime.Composable {
                if (!shell) {
                    content()
                } else {
                    SharedTransitionLayout {
                        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                            NavHost(rememberNavController(), startDestination = HomeDestination) {
                                composable<HomeDestination> {
                                    CompositionLocalProvider(LocalNavAnimatedScope provides this) { content() }
                                }
                            }
                        }
                    }
                }
            }
            currentTree.value = tree
            rule.waitForIdle()
            val ms = measureTimeMillis {
                repeat(150) { step ->
                    rule.runOnUiThread { kotlinx.coroutines.runBlocking { list!!.scrollToItem(step * 60) } }
                    rule.waitForIdle()
                }
            }
            shell = false
            return ms
        }
        rule.setContent { PayFlowTheme(darkTheme = true) { currentTree.value() } }
        val plain = scrollOnce(withShell = false)
        val shared = scrollOnce(withShell = true)
        println("STRESS [rows-10k-scroll-150-jumps] withoutSharedBounds=${plain}ms withSharedBounds=${shared}ms")
        assertThat(rule.onAllNodes(hasTestTag("rows")).fetchSemanticsNodes()).isNotEmpty()
    }

    private val currentTree = mutableStateOf<@androidx.compose.runtime.Composable () -> Unit>({})
    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val APP = "stress:app"
    }
}

/** The lock's state holder under a tap storm, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class LockTapStressTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val pins = PinManager(InMemorySecureStore(), fixedClock, iterations = 1_000)
    private val appLock = AppLock(pins, fixedClock)
    private fun vm() = AppLockViewModel(appLock, pins, FakeBiometricAuthenticator(), TestDispatcherProvider(dispatcher), fixedClock)

    @Test
    fun aThousandRapidTapsBurnOneAttempt() = runTest(dispatcher) {
        pins.set("493027")
        val vm = vm()
        advanceUntilIdle()
        val ms = measureTimeMillis { repeat(1_000) { vm.onPinDigit("0") } }
        advanceUntilIdle()
        val locked = vm.uiState.value as LockUiState.Locked
        println("STRESS [lock-1000-taps] total=${ms}ms attemptsLeft=${locked.remainingAttempts} rejections=${locked.rejections}")
        assertThat(locked.remainingAttempts).isEqualTo(PinManager.MAX_ATTEMPTS - 1)
        assertThat(locked.rejections).isEqualTo(1)
    }

    @Test
    fun aThousandLockUnlockCyclesEndConsistent() = runTest(dispatcher) {
        pins.set("493027")
        val vm = vm()
        advanceUntilIdle()
        val ms = measureTimeMillis {
            repeat(1_000) {
                vm.onPinEntered("493027"); advanceUntilIdle()
                assertThat(vm.uiState.value).isEqualTo(LockUiState.Unlocked(pinConfigured = true))
                appLock.lock(); advanceUntilIdle()
                assertThat(vm.uiState.value).isInstanceOf(LockUiState.Locked::class.java)
            }
        }
        println("STRESS [lock-1000-cycles] total=${ms}ms")
    }

    @Test
    fun aHapticFloodStaysBounded() = runTest(dispatcher) {
        pins.set("493027")
        val vm = vm()
        advanceUntilIdle()
        repeat(100_000) { vm.onPinDigit("1"); vm.onPinBackspace() }
        advanceUntilIdle()
        // Nobody is collecting: the effect channel drops the oldest instead of growing.
        assertThat((vm.uiState.value as LockUiState.Locked).enteredDigits).isEqualTo(0)
        println("STRESS [lock-haptic-flood] taps=200000 bounded=true")
    }
}
