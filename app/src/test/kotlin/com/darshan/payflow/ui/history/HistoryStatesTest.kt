package com.darshan.payflow.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.assertInside
import com.darshan.payflow.ui.designsystem.setDs
import com.google.common.truth.Truth.assertThat
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * History's loading and empty states say where the user is and offer the way back: a filter or a search that finds nothing
 * leads back to everything, the first load is the skeleton alone, and a pull-to-refresh keeps the list in view under its
 * spinner instead of swapping it for skeletons.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class HistoryStatesTest(private val fontScale: Float, private val reduceMotion: Boolean) {
    @get:Rule val rule = createComposeRule()
    private val zone = ZoneOffset.UTC
    private val payments = List(3) { testPayment("p$it", status = PaymentStatus.SUCCESS, payeeName = "Asha $it", createdAt = FIXED_INSTANT.minusSeconds(it * 600L)) }
    private val content = HistoryUiState.Content(payments, HistoryFilter.ALL, "", HistorySections.build(payments, FIXED_INSTANT.atZone(zone).toLocalDate(), zone))

    private fun show(state: () -> HistoryUiState, callbacks: HistoryCallbacks) =
        rule.setDs(fontScale = fontScale, reduceMotion = reduceMotion) { HistoryScreen(state(), callbacks, zone = zone) }

    @Test
    fun aFilterThatFindsNothingLeadsBackToAllPayments() {
        var filter: HistoryFilter? = null
        show({ HistoryUiState.Empty(HistoryFilter.PENDING, "") }, HistoryCallbacks(onFilter = { filter = it }))
        rule.onNodeWithText("No pending payments").assertIsDisplayed()
        // Not "payments you make will show up here": there are payments, just none pending.
        rule.onAllNodesWithText("Payments you make will show up here.").assertCountEquals(0)
        rule.onNodeWithTag(PayFlowTestTags.HISTORY_EMPTY_ACTION).assertIsDisplayed().assertInside(rule.onRoot())
        rule.onNodeWithText("Show all payments").performClick()
        assertThat(filter).isEqualTo(HistoryFilter.ALL)
    }

    @Test
    fun aSearchThatFindsNothingOffersToClearIt() {
        var query: String? = null
        var state by mutableStateOf<HistoryUiState>(content)
        show({ state }, HistoryCallbacks(onBack = null, onQuery = { query = it }))
        rule.onNodeWithTag(PayFlowTestTags.HISTORY_SEARCH_OPEN).performClick()
        rule.onNodeWithTag(PayFlowTestTags.SEARCH_FIELD).performTextInput("zzz")
        state = HistoryUiState.Empty(HistoryFilter.ALL, "zzz")
        rule.waitForIdle()
        rule.onNodeWithText("Clear search").performClick()
        assertThat(query).isEqualTo("")
        // The field closes too: the title is back.
        rule.waitForIdle()
        rule.onAllNodesWithTag(PayFlowTestTags.SEARCH_FIELD).assertCountEquals(0)
    }

    @Test
    fun noPaymentsAtAllOffersNoWayBack() {
        show({ HistoryUiState.Empty(HistoryFilter.ALL, "") }, HistoryCallbacks())
        rule.onNodeWithText("Payments you make will show up here.").assertIsDisplayed()
        rule.onAllNodesWithTag(PayFlowTestTags.HISTORY_EMPTY_ACTION).assertCountEquals(0)
    }

    @Test
    fun aPullToRefreshKeepsTheListInViewWhileItReloads() {
        var state by mutableStateOf<HistoryUiState>(content)
        var refreshes = 0
        show({ state }, HistoryCallbacks(onBack = null, onRefresh = { refreshes++; state = HistoryUiState.Loading }))
        rule.onNodeWithTag(PayFlowTestTags.HISTORY_REFRESH).performClick()
        rule.waitForIdle()
        assertThat(refreshes).isEqualTo(1)
        rule.onNodeWithTag(PayFlowTestTags.paymentRow("p0")).assertIsDisplayed()
        rule.onAllNodesWithTag(PayFlowTestTags.LOADING).assertCountEquals(0)
        state = content
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.paymentRow("p0")).assertIsDisplayed()
    }

    @Test
    fun theFirstLoadIsTheSkeleton() {
        show({ HistoryUiState.Loading }, HistoryCallbacks())
        rule.onNodeWithTag(PayFlowTestTags.LOADING).assertIsDisplayed()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x reduced={1}")
        fun cases() = listOf(arrayOf<Any>(1f, false), arrayOf<Any>(1.3f, false), arrayOf<Any>(2f, false), arrayOf<Any>(1f, true))
    }
}
