package com.darshan.payflow.ui.collect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.CollectRequest
import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CollectScreenTest {
    @get:Rule val rule = createComposeRule()
    private val holder = mutableStateOf<CollectUiState>(CollectUiState.Loading)

    private fun request(id: String, status: CollectStatus = CollectStatus.PENDING) = CollectRequest(
        id = id, payeeVpa = "shop@okbank", payeeName = "Chai Point", amountPaise = 4_500, note = "Table 4",
        createdAt = FIXED_INSTANT, expiresAt = FIXED_INSTANT.plusSeconds(600), updatedAt = FIXED_INSTANT, status = status, paymentId = null,
    )

    private fun show(state: CollectUiState, callbacks: CollectCallbacks = CollectCallbacks()) {
        holder.value = state
        rule.setContent {
            val current by holder
            PayFlowTheme { CollectScreen(current, callbacks) }
        }
    }

    @Test
    fun statesRender() {
        var retried = false
        show(CollectUiState.Loading, CollectCallbacks(onRefresh = { retried = true }))
        rule.onNodeWithTag(PayFlowTestTags.LOADING).assertIsDisplayed()
        holder.value = CollectUiState.Empty
        rule.onNodeWithText("No payment requests").assertIsDisplayed()
        holder.value = CollectUiState.Error("x")
        rule.onNodeWithText("Retry").performClick()
        assertThat(retried).isTrue()
    }

    @Test
    fun pendingRequestOffersApproveAndDecline() {
        var approved: String? = null; var declined: String? = null
        show(
            CollectUiState.Content(listOf(CollectItem(request("req-1"), busy = false)), refreshing = false),
            CollectCallbacks(onApprove = { approved = it }, onDecline = { declined = it }),
        )
        rule.onNodeWithText("Chai Point").assertIsDisplayed()
        rule.onNodeWithText("₹45.00").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.collectApprove("req-1")).performClick()
        rule.onNodeWithTag(PayFlowTestTags.collectDecline("req-1")).performClick()
        assertThat(approved).isEqualTo("req-1")
        assertThat(declined).isEqualTo("req-1")
    }

    @Test
    fun busyAndAnsweredItemsHideOrDisableActions() {
        show(CollectUiState.Content(listOf(CollectItem(request("req-1"), busy = true)), refreshing = false))
        rule.onNodeWithTag(PayFlowTestTags.collectApprove("req-1")).assertIsNotEnabled()
        holder.value = CollectUiState.Content(
            listOf(
                CollectItem(request("a", CollectStatus.APPROVED), false),
                CollectItem(request("b", CollectStatus.DECLINED), false),
                CollectItem(request("c", CollectStatus.EXPIRED), false),
            ),
            refreshing = false,
        )
        rule.onNodeWithText("Approved").assertIsDisplayed()
        rule.onNodeWithText("Declined").assertIsDisplayed()
        rule.onNodeWithText("Expired").assertIsDisplayed()
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag(PayFlowTestTags.collectApprove("a"))).assertCountEquals(0)
    }
}
