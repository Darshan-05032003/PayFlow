package com.darshan.payflow.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.RecentPayee
import com.darshan.payflow.ui.components.LocalPaymentMode
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HomeScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun show(state: HomeUiState, dark: Boolean = false, callbacks: HomeCallbacks = HomeCallbacks()) {
        rule.setContent { PayFlowTheme(darkTheme = dark) { HomeScreen(state = state, callbacks = callbacks) } }
    }

    @Test
    fun loadingState() {
        show(HomeUiState.Loading)
        rule.onNodeWithTag(PayFlowTestTags.LOADING).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun emptyState() {
        show(HomeUiState.Empty)
        // The dashboard puts the summary and quick actions above the list, so scroll the empty card into view first.
        rule.onNodeWithTag(PayFlowTestTags.EMPTY).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("No payments yet").assertIsDisplayed()
    }

    @Test
    fun errorStateRetries() {
        var retried = false
        show(HomeUiState.Error("x"), callbacks = HomeCallbacks(onRetry = { retried = true }))
        rule.onNodeWithTag(PayFlowTestTags.ERROR).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertThat(retried).isTrue()
    }

    @Test
    fun contentShowsRecentPaymentsAndNavigates() {
        var clicked: String? = null
        var viewAll = false
        val payments = listOf(
            testPayment("a", status = PaymentStatus.SUCCESS, payeeName = "Alice", amountPaise = 12_345),
            testPayment("b", status = PaymentStatus.FAILED, payeeName = "Bob"),
        )
        show(
            HomeUiState.Content(payments, HistorySummary.of(payments)),
            dark = true,
            callbacks = HomeCallbacks(onPaymentClick = { clicked = it }, onViewAll = { viewAll = true }),
        )
        rule.onNodeWithText("Alice").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("₹123.45").onFirst().performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Failed").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Alice").performClick()
        assertThat(clicked).isEqualTo("a")
        rule.onNodeWithText("View all").performScrollTo().performClick()
        assertThat(viewAll).isTrue()
        rule.onNodeWithContentDescription("Payment to Alice, ₹123.45, Success").assertExists()
        // Every quick action is labelled and reachable.
        listOf("Scan QR", "Requests", "AutoPay", "Checkout", "Security", "Receive", "History").forEach { rule.onNodeWithContentDescription(it).assertExists() }
    }

    @Test
    fun recentPayeesRowOpensPayPrefilledAndSandboxBadgeFollowsTheMode() {
        var payee: RecentPayee? = null
        var receive = false
        val payments = listOf(testPayment("a", status = PaymentStatus.SUCCESS))
        val payees = listOf(RecentPayee("alice@okbank", "Alice Kumar", testPayment().createdAt), RecentPayee("bob@upi", "Bob", testPayment().createdAt))
        rule.setContent {
            PayFlowTheme {
                CompositionLocalProvider(LocalPaymentMode provides PaymentMode.SANDBOX) {
                    HomeScreen(
                        HomeUiState.Content(payments, HistorySummary.of(payments), recentPayees = payees),
                        HomeCallbacks(onPayee = { payee = it }, onReceive = { receive = true }),
                    )
                }
            }
        }
        rule.onNodeWithTag(PayFlowTestTags.SANDBOX_BADGE).assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.HOME_PAYEES).performScrollTo()
        rule.onNodeWithContentDescription("Pay Alice Kumar again (alice@okbank)").performClick()
        assertThat(payee).isEqualTo(payees[0])
        rule.onNodeWithTag(PayFlowTestTags.HOME_RECEIVE).performScrollTo().performClick()
        assertThat(receive).isTrue()
        rule.onNodeWithText("Sandbox mode: payments are settled by an in-app fake provider, no real money moves. Switch modes under Security.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun upiAppsModeHasNoBadgeAndAnHonestFooter() {
        show(HomeUiState.Empty)
        rule.onAllNodesWithTag(PayFlowTestTags.SANDBOX_BADGE).assertCountEquals(0)
        rule.onNodeWithText("UPI apps mode: payments go through the UPI apps installed on this phone. Results are reported by that app; PayFlow has no bank connection to verify them.").performScrollTo().assertIsDisplayed()
    }
}
