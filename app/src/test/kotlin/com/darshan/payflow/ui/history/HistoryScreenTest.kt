package com.darshan.payflow.ui.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.HistoryFilter
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
class HistoryScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun show(state: HistoryUiState, callbacks: HistoryCallbacks = HistoryCallbacks()) {
        rule.setContent { PayFlowTheme { HistoryScreen(state = state, callbacks = callbacks) } }
    }

    @Test
    fun statesRender() {
        show(HistoryUiState.Loading)
        rule.onNodeWithTag(PayFlowTestTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun emptyAndErrorStates() {
        var retried = false
        show(HistoryUiState.Error("db"), HistoryCallbacks(onRetry = { retried = true }))
        rule.onNodeWithTag(PayFlowTestTags.ERROR).assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertThat(retried).isTrue()
    }

    @Test
    fun emptyStateMentionsFilter() {
        show(HistoryUiState.Empty(HistoryFilter.FAILED, ""))
        rule.onNodeWithTag(PayFlowTestTags.EMPTY).assertIsDisplayed()
        rule.onNodeWithText("No failed payments").assertIsDisplayed()
    }

    @Test
    fun filterChipsAndSearchCallBack() {
        var filter: HistoryFilter? = null
        var query: String? = null
        var clicked: String? = null
        show(
            HistoryUiState.Content(
                payments = listOf(testPayment("a", status = PaymentStatus.SUCCESS, payeeName = "Alice")),
                filter = HistoryFilter.ALL,
                query = "",
            ),
            HistoryCallbacks(onFilter = { filter = it }, onQuery = { query = it }, onPaymentClick = { clicked = it }),
        )
        rule.onNodeWithTag(PayFlowTestTags.filterChip(HistoryFilter.PENDING)).performClick()
        assertThat(filter).isEqualTo(HistoryFilter.PENDING)
        rule.onNodeWithTag(PayFlowTestTags.SEARCH_FIELD).performTextInput("ali")
        assertThat(query).isEqualTo("ali")
        rule.onNodeWithText("Alice").performClick()
        assertThat(clicked).isEqualTo("a")
    }

    @Test
    fun detailScreenShowsFields() {
        val payment = testPayment("a", status = PaymentStatus.SUCCESS, payeeName = "Alice", amountPaise = 50_000)
            .copy(pspTxnId = "PSP123", upiTxnId = "UPI456")
        rule.setContent { PayFlowTheme { PaymentDetailScreen(PaymentDetailUiState.Content(payment), onBack = {}) } }
        rule.onNodeWithText("₹500.00").assertIsDisplayed()
        rule.onNodeWithText("alice@okbank").assertIsDisplayed()
        rule.onNodeWithText("PSP123").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("UPI456").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Success").assertIsDisplayed()
    }

    @Test
    fun detailNotFound() {
        rule.setContent { PayFlowTheme { PaymentDetailScreen(PaymentDetailUiState.NotFound, onBack = {}) } }
        rule.onNodeWithText("Payment not found").assertIsDisplayed()
    }
}
