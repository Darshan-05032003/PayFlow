package com.darshan.payflow.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.HistorySummary
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
        rule.onNodeWithTag(PayFlowTestTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun emptyState() {
        show(HomeUiState.Empty)
        rule.onNodeWithTag(PayFlowTestTags.EMPTY).assertIsDisplayed()
        rule.onNodeWithText("No payments yet").assertIsDisplayed()
    }

    @Test
    fun errorStateRetries() {
        var retried = false
        show(HomeUiState.Error("x"), callbacks = HomeCallbacks(onRetry = { retried = true }))
        rule.onNodeWithTag(PayFlowTestTags.ERROR).assertIsDisplayed()
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
        rule.onNodeWithText("Alice").assertIsDisplayed()
        rule.onAllNodesWithText("₹123.45").onFirst().assertIsDisplayed()
        rule.onNodeWithText("Failed").assertIsDisplayed()
        rule.onNodeWithText("Alice").performClick()
        assertThat(clicked).isEqualTo("a")
        rule.onNodeWithText("View all").performClick()
        assertThat(viewAll).isTrue()
        rule.onNodeWithContentDescription("Payment to Alice, ₹123.45, Success").assertExists()
    }
}
