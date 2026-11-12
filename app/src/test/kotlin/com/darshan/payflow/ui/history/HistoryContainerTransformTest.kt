package com.darshan.payflow.ui.history

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.commitState
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.navigation.LocalNavAnimatedScope
import com.darshan.payflow.ui.navigation.LocalSharedTransitionScope
import com.darshan.payflow.ui.navigation.containerTransform
import com.darshan.payflow.ui.navigation.paymentContainerKey
import com.google.common.truth.Truth.assertThat
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The row grows into the payment's sheet and shrinks back into it: a shell-shaped harness (shared-element layout around an
 * animated screen switch) checks that both ways find a matching pair, which is what makes it a container transform rather
 * than a cross-fade.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class HistoryContainerTransformTest {
    @get:Rule val rule = createComposeRule()
    private val zone = ZoneOffset.UTC

    @Test
    fun theOpenedRowGrowsIntoItsSheetAndTheSheetShrinksBackIntoIt() {
        val payments = List(40) { testPayment("p$it", status = PaymentStatus.SUCCESS, createdAt = FIXED_INSTANT.minusSeconds(it * 900L)) }
        val state = HistoryUiState.Content(payments, HistoryFilter.ALL, "", HistorySections.build(payments, FIXED_INSTANT.atZone(zone).toLocalDate(), zone))
        var opened by mutableStateOf<String?>(null)
        lateinit var shared: SharedTransitionScope
        rule.setDs {
            SharedTransitionLayout {
                shared = this
                CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                    // Like NavHost: each screen's saved state outlives its trip off screen.
                    val saved = rememberSaveableStateHolder()
                    AnimatedContent(opened, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { target ->
                        CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                            saved.SaveableStateProvider(target ?: "history") {
                                if (target == null) {
                                    HistoryScreen(state, HistoryCallbacks(onBack = null, onPaymentClick = { opened = it }), zone = zone)
                                } else {
                                    Box(Modifier.size(300.dp).containerTransform(paymentContainerKey(target)))
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false

        // A real tap: the finger rests on the row for a few frames before it lifts.
        val row = rule.onNodeWithTag(PayFlowTestTags.paymentRow("p2"))
        row.performTouchInput { down(center) }
        repeat(FRAMES_PRESSED) { rule.mainClock.advanceTimeByFrame() }
        row.performTouchInput { up() }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertThat(opened).isEqualTo("p2")
        assertThat(shared.isTransitionActive).isTrue()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        assertThat(shared.isTransitionActive).isFalse()

        commitState { opened = null }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertThat(shared.isTransitionActive).isTrue()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        assertThat(shared.isTransitionActive).isFalse()
    }

    private companion object {
        const val SETTLE_MS = 2_000L
        const val FRAMES_PRESSED = 4
    }
}
