package com.darshan.payflow.ui.checkout

import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.usecase.CheckoutOrder
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.commitState
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.designsystem.tokens.AurumDark
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CheckoutScreenTest {
    @get:Rule val rule = createComposeRule()
    private val order = CheckoutOrder("PF-beans-1", 49_900, "Coffee beans 1 kg", CheckoutViewModel.MERCHANT_VPA, CheckoutViewModel.MERCHANT_NAME)

    private fun storeWidth() = rule.onNodeWithTag(PayFlowTestTags.CHECKOUT_STORE).fetchSemanticsNode().boundsInRoot.width
    private fun rootWidth() = rule.onRoot().fetchSemanticsNode().boundsInRoot.width

    @Test
    fun theStoreRecedesOnASpringWhileTheSheetIsUpAndComesBack() {
        var state by mutableStateOf(CheckoutUiState())
        rule.mainClock.autoAdvance = false
        rule.setDs { CheckoutScreen(state, CheckoutCallbacks()) }
        rule.mainClock.advanceTimeBy(500)
        assertThat(storeWidth()).isWithin(1f).of(rootWidth())
        commitState { state = CheckoutUiState(activeOrder = order) }
        rule.mainClock.advanceTimeBy(64)
        val midway = storeWidth()
        assertThat(midway).isLessThan(rootWidth())
        rule.mainClock.advanceTimeBy(2_000)
        assertThat(storeWidth()).isLessThan(midway)
        assertThat(storeWidth() / rootWidth()).isWithin(0.01f).of(0.94f)
        commitState { state = CheckoutUiState(activeOrder = order, recording = true) }
        rule.mainClock.advanceTimeBy(2_000)
        assertThat(storeWidth()).isWithin(1f).of(rootWidth())
    }

    @Test
    fun reducedMotionKeepsTheStoreStill() {
        rule.setDs(reduceMotion = true) { CheckoutScreen(CheckoutUiState(activeOrder = order), CheckoutCallbacks()) }
        rule.waitForIdle()
        assertThat(storeWidth()).isWithin(1f).of(rootWidth())
    }

    @Test
    fun buyIsQuietAndLockedWhileAnOrderIsOut() {
        val bought = mutableListOf<String>()
        var state by mutableStateOf(CheckoutUiState())
        rule.setDs { CheckoutScreen(state, CheckoutCallbacks(onBuy = { bought += it.id })) }
        rule.onNodeWithTag(PayFlowTestTags.checkoutBuy("mug")).performScrollTo().assertIsEnabled().performClick()
        assertThat(bought).containsExactly("mug")
        state = CheckoutUiState(activeOrder = order)
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.checkoutBuy("grinder")).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theOutcomeCardShowsItsStatusAndOpensThePayment() {
        var opened: String? = null
        val payment = testPayment("ck-1", status = PaymentStatus.FAILED, amountPaise = 29_900).copy(failureReason = "Declined by the bank (ZA)")
        rule.setDs { CheckoutScreen(CheckoutUiState(lastOutcome = CheckoutOutcome.Recorded(payment)), CheckoutCallbacks(onOpenPayment = { opened = it })) }
        rule.onNodeWithTag(PayFlowTestTags.CHECKOUT_OUTCOME).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Payment failed").assertExists()
        rule.onNodeWithText("Declined by the bank (ZA)").assertExists()
        rule.onNodeWithText("View payment", substring = true).performScrollTo().performClick()
        assertThat(opened).isEqualTo("ck-1")
    }

    /** Like Copy reference on the payment sheet: a secondary action sits on its card's gutter, not pushed in by its own padding. */
    @Test fun viewPaymentSitsOnTheOutcomeCardsGutter() = assertViewPaymentOnGutter(1f)
    @Test fun viewPaymentSitsOnTheOutcomeCardsGutterAtLargeText() = assertViewPaymentOnGutter(1.3f)
    @Test fun viewPaymentSitsOnTheOutcomeCardsGutterAtDoubleText() = assertViewPaymentOnGutter(2f)

    private fun assertViewPaymentOnGutter(fontScale: Float) {
        val payment = testPayment("ck-1", status = PaymentStatus.SUCCESS, amountPaise = 49_900)
        rule.setDs(fontScale = fontScale) { CheckoutScreen(CheckoutUiState(lastOutcome = CheckoutOutcome.Recorded(payment)), CheckoutCallbacks()) }
        val button = rule.onNodeWithTag(PayFlowTestTags.CHECKOUT_VIEW_PAYMENT).performScrollTo().getBoundsInRoot()
        val title = rule.onNodeWithText("Payment successful").getBoundsInRoot()
        assertThat(button.left).isEqualTo(title.left)
        assertThat((button.right - button.left).value).isGreaterThan((title.right - title.left).value)
    }

    /** CheckoutKit has no theming API; it reads the ambient Material theme, so PayFlow's tokens go in through these slots. */
    @Test
    fun theSheetThemeCarriesPayFlowTokens() {
        val scheme = lightColorScheme().forCheckoutKit(AurumDark)
        assertThat(scheme.surfaceContainerLow).isEqualTo(AurumDark.surface)
        assertThat(scheme.onSurface).isEqualTo(AurumDark.text)
        assertThat(scheme.primary).isEqualTo(AurumDark.primary)
        assertThat(scheme.scrim).isEqualTo(AurumDark.scrim)
        assertThat(scheme.error).isEqualTo(AurumDark.failed.strong)
    }
}
