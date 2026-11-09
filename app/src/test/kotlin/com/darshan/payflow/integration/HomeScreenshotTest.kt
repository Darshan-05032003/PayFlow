package com.darshan.payflow.integration

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.RecentPayee
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.components.LocalPaymentMode
import com.darshan.payflow.ui.designsystem.captureScreenshot
import com.darshan.payflow.ui.designsystem.commitState
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.home.HomeCallbacks
import com.darshan.payflow.ui.home.HomeEntranceMemory
import com.darshan.payflow.ui.home.HomeScreen
import com.darshan.payflow.ui.home.HomeUiState
import com.darshan.payflow.ui.navigation.LocalBottomBarInset
import kotlin.random.Random
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Home in each main state, light and dark. The floating bar is the shell's; its inset is provided so the end of scroll is real. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class HomeScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var scroll: ScrollState

    private val at = testPayment().createdAt

    private val payments: List<Payment> = listOf(
        row("1", "Rahul Mehta", "rahul@okicici", 30_000, PaymentStatus.SUCCESS),
        row("2", "Blue Tokai Coffee", "bluetokai@ybl", 42_000, PaymentStatus.PENDING),
        row("3", "Asha Rao", "asha@okaxis", 1_25_000, PaymentStatus.SUCCESS, incoming = true),
        row("4", "Metro Recharge", "dmrc@upi", 50_000, PaymentStatus.FAILED),
        row("5", "Karan Shah", "karan@oksbi", 2_00_000, PaymentStatus.REFUNDED),
    )

    private val payees = listOf(
        RecentPayee("rahul@okicici", "Rahul Mehta", at),
        RecentPayee("asha@okaxis", "Asha Rao", at),
        RecentPayee("karan@oksbi", "Karan Shah", at),
        RecentPayee("priya@ybl", "Priya N", at),
        RecentPayee("dmrc@upi", "Metro Recharge", at),
    )

    private fun row(id: String, name: String, vpa: String, paise: Long, status: PaymentStatus, incoming: Boolean = false) =
        testPayment(id, txnRef = "T$id", payeeName = name, payeeVpa = vpa, amountPaise = paise, status = status)
            .copy(direction = if (incoming) PaymentDirection.INCOMING else PaymentDirection.OUTGOING)

    private fun content(sent: Long = 1_23_456_00) =
        HomeUiState.Content(payments, HistorySummary.of(payments).copy(sentPaise = 12_34_567_00, total = 248), HistorySummary.of(payments).copy(sentPaise = sent), payees)

    private fun show(
        state: HomeUiState,
        name: String,
        dark: Boolean,
        hidden: Boolean = false,
        pinConfigured: Boolean = true,
        fontScale: Float = 1f,
        mode: PaymentMode = PaymentMode.UPI_APPS,
        scrollTo: Float = 0f,
    ) {
        // Paused, so the loading shimmer doesn't keep the clock from settling; the entrance is advanced past by hand.
        rule.mainClock.autoAdvance = false
        rule.setDs(fontScale = fontScale, dark = dark) {
            scroll = rememberScrollState()
            CompositionLocalProvider(LocalPaymentMode provides mode, LocalBottomBarInset provides 80.dp) {
                Box { HomeScreen(state, HomeCallbacks(), pinConfigured = pinConfigured, amountsHidden = hidden, scroll = scroll) }
            }
        }
        rule.mainClock.advanceTimeBy(1_200)
        if (scrollTo != 0f) {
            rule.runOnUiThread { scroll.dispatchRawDelta(scrollTo) }
            rule.mainClock.advanceTimeBy(100)
        }
        rule.captureScreenshot("home/$name")
    }

    /** Mid-pull, finger down: the coin in its graphite well. */
    @Test
    fun pullingDark() {
        rule.mainClock.autoAdvance = false
        rule.setDs(dark = true) {
            scroll = rememberScrollState()
            CompositionLocalProvider(LocalBottomBarInset provides 80.dp) { HomeScreen(content(), HomeCallbacks(), scroll = scroll) }
        }
        rule.mainClock.advanceTimeBy(1_200)
        rule.onRoot().performTouchInput {
            down(center)
            repeat(12) { moveBy(androidx.compose.ui.geometry.Offset(0f, 24f)) }
        }
        rule.mainClock.advanceTimeBy(200)
        rule.captureScreenshot("home/pulling-dark")
    }

    /** The middle of hiding: blurred scramble, with nothing of the amount readable. */
    @Test
    fun hidingMidwayDark() {
        var hidden by mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setDs(dark = true) {
            scroll = rememberScrollState()
            // A seeded scramble: the production one is random, and this baseline must repeat.
            HomeScreen(content(), HomeCallbacks(), amountsHidden = hidden, memory = HomeEntranceMemory(Random(1)), scroll = scroll)
        }
        rule.mainClock.advanceTimeBy(1_200)
        commitState { hidden = true }
        rule.mainClock.advanceTimeBy(112)
        rule.captureScreenshot("home/hiding-midway-dark")
    }

    @Test fun contentLight() = show(content(), "content-light", dark = false)
    @Test fun contentDark() = show(content(), "content-dark", dark = true)
    @Test fun contentSandboxDark() = show(content(), "content-sandbox-dark", dark = true, mode = PaymentMode.SANDBOX)
    @Test fun lockBannerLight() = show(content(), "lock-banner-light", dark = false, pinConfigured = false)
    @Test fun lockBannerDark() = show(content(), "lock-banner-dark", dark = true, pinConfigured = false)
    @Test fun emptyLight() = show(HomeUiState.Empty, "empty-light", dark = false)
    @Test fun emptyDark() = show(HomeUiState.Empty, "empty-dark", dark = true)
    @Test fun loadingLight() = show(HomeUiState.Loading, "loading-light", dark = false)
    @Test fun loadingDark() = show(HomeUiState.Loading, "loading-dark", dark = true)
    @Test fun errorLight() = show(HomeUiState.Error("x"), "error-light", dark = false)
    @Test fun errorDark() = show(HomeUiState.Error("x"), "error-dark", dark = true)
    @Test fun hiddenLight() = show(content(), "hidden-light", dark = false, hidden = true)
    @Test fun hiddenDark() = show(content(), "hidden-dark", dark = true, hidden = true)
    @Test fun dockedLight() = show(content(), "docked-light", dark = false, scrollTo = 100_000f)
    @Test fun dockedDark() = show(content(), "docked-dark", dark = true, scrollTo = 100_000f)
    @Test fun halfwayDark() = show(content(), "halfway-dark", dark = true, scrollTo = 200f)
    @Test fun largestAmountLight() = show(content(sent = 99_99_99_999_99L), "largest-amount-light", dark = false)
    @Test fun scaled13xLight() = show(content(sent = 99_99_99_999_99L), "scaled-1_3x-light", dark = false, fontScale = 1.3f)
    @Test fun scaled13xDark() = show(content(), "scaled-1_3x-dark", dark = true, fontScale = 1.3f, pinConfigured = false)
    @Test fun scaled13xDockedDark() = show(content(sent = 99_99_99_999_99L), "scaled-1_3x-docked-dark", dark = true, fontScale = 1.3f, scrollTo = 100_000f)
}
