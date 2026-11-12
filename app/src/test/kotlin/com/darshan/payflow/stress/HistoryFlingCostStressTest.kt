package com.darshan.payflow.stress

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.recompositionCycles
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.history.HistoryCallbacks
import com.darshan.payflow.ui.history.HistoryScreen
import com.darshan.payflow.ui.history.HistorySections
import com.darshan.payflow.ui.history.HistoryUiState
import com.darshan.payflow.ui.navigation.LocalNavAnimatedScope
import com.darshan.payflow.ui.navigation.LocalSharedTransitionScope
import com.google.common.truth.Truth.assertThat
import java.lang.management.ManagementFactory
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What a History fling costs on the main thread, measured on the JVM: wall time and bytes allocated per fling over 10,000
 * rows, inside the shell's shared-element scope (where every row is one end of a container transform) as on a phone.
 * The device numbers (gfxinfo, emulator) are in LOAD_TEST_REPORT; these are the JVM half, comparable only with each other.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class HistoryFlingCostStressTest {
    @get:Rule val rule = createComposeRule()
    private val zone = ZoneOffset.UTC
    private val today = FIXED_INSTANT.atZone(zone).toLocalDate()

    @Test
    fun flingsOverTenThousandRowsInsideTheShell() = measure(inShell = true)

    @Test
    fun flingsOverTenThousandRowsOnTheirOwn() = measure(inShell = false)

    /** The shell's shared-element layout with no row taking part: what the layout alone costs. */
    @Test
    fun flingsInsideTheShellWithNoRowTransforms() = measure(inShell = true, rowsShared = false)

    private fun measure(inShell: Boolean, rowsShared: Boolean = true) {
        val payments = List(10_000) {
            testPayment("p$it", status = PaymentStatus.entries[it % 6], payeeName = NAMES[it % NAMES.size], createdAt = FIXED_INSTANT.minusSeconds(it * 900L))
        }
        val state = HistoryUiState.Content(payments, HistoryFilter.ALL, "", HistorySections.build(payments, today, zone))
        rule.setDs {
            Shell(inShell, rowsShared) { HistoryScreen(state, HistoryCallbacks(onBack = null), zone = zone) }
        }
        rule.waitForIdle()
        val list = rule.onNodeWithTag(PayFlowTestTags.PAYMENT_LIST)
        // Warm-up: class loading and the first compositions of each row type are not what a fling costs.
        repeat(WARM_UP) { list.performTouchInput { swipeUp() }; rule.waitForIdle() }

        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id
        val bytesBefore = threads.getThreadAllocatedBytes(thread)
        val cyclesBefore = recompositionCycles()
        val start = System.nanoTime()
        repeat(FLINGS) { list.performTouchInput { swipeUp() }; rule.waitForIdle() }
        val ms = (System.nanoTime() - start) / 1_000_000
        val kbPerFling = (threads.getThreadAllocatedBytes(thread) - bytesBefore) / 1024 / FLINGS
        val cycles = recompositionCycles() - cyclesBefore
        val label = if (!rowsShared) "shell-layout-only" else if (inShell) "shell" else "alone"
        println("STRESS [history-fling-cost-$label] flings=$FLINGS total=${ms}ms perFling=${ms / FLINGS}ms allocPerFling=${kbPerFling}KB recompositionCycles=$cycles")
        // Before the row-16 fix every row was a shared element and the shell recomposed 1,373 times over these 60 flings.
        // Now only the row under the finger is, which hands over once per fling.
        assertThat(cycles).isAtMost(FLINGS * 2L)
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    private fun Shell(inShell: Boolean, rowsShared: Boolean, content: @Composable () -> Unit) {
        if (!inShell) {
            content()
            return
        }
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                AnimatedContent(targetState = SCREEN, label = "screen") { screen ->
                    CompositionLocalProvider(LocalNavAnimatedScope provides if (rowsShared) this else null) { if (screen == SCREEN) content() }
                }
            }
        }
    }

    private companion object {
        const val WARM_UP = 10
        const val SCREEN = "history"
        const val FLINGS = 60
        val NAMES = listOf("Asha Rao", "Kiran Mehta", "Neel Varma", "Tara Book House", "Copper Kettle Cafe", "Meera Iyer")
    }
}
