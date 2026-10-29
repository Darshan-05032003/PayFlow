package com.darshan.payflow.ui.mandates

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.Mandate
import com.darshan.payflow.domain.model.MandateAction
import com.darshan.payflow.domain.model.MandateFrequency
import com.darshan.payflow.domain.model.MandateStatus
import com.darshan.payflow.domain.model.MandateValidation
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class MandateScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun mandate(status: MandateStatus = MandateStatus.ACTIVE) = Mandate(
        "m-1", "UMN123", "netflix@okbank", "Netflix", 64_900, MandateFrequency.MONTHLY, LocalDate.of(2026, 10, 29), LocalDate.of(2027, 10, 29),
        status, "Premium plan", FIXED_INSTANT, FIXED_INSTANT, null, 0,
    )

    @Test
    fun listStatesAndNavigation() {
        val holder = mutableStateOf<MandatesUiState>(MandatesUiState.Loading)
        var opened: String? = null; var create = false
        rule.setContent { val s by holder; PayFlowTheme { MandatesScreen(s, MandatesCallbacks(onOpen = { opened = it }, onCreate = { create = true })) } }
        rule.onNodeWithTag(PayFlowTestTags.LOADING).assertIsDisplayed()
        holder.value = MandatesUiState.Empty
        rule.onNodeWithText("No AutoPay mandates").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.MANDATE_CREATE).performClick()
        assertThat(create).isTrue()
        holder.value = MandatesUiState.Content(listOf(mandate(), mandate(MandateStatus.PAUSED).copy(id = "m-2", payeeName = "Spotify", maxAmountPaise = 11_900)))
        rule.onNodeWithText("Netflix").assertIsDisplayed()
        rule.onNodeWithText("Up to ₹649.00 · Monthly").assertIsDisplayed()
        rule.onNodeWithText("Paused").assertIsDisplayed()
        rule.onNodeWithText("Spotify").performClick()
        assertThat(opened).isEqualTo("m-2")
    }

    @Test
    fun createFormInputsAndErrors() {
        val holder = mutableStateOf(CreateMandateUiState())
        var submitted = false
        rule.setContent {
            val s by holder
            PayFlowTheme {
                CreateMandateScreen(
                    s,
                    CreateMandateCallbacks(
                        onVpaChange = { holder.value = holder.value.copy(vpa = it) },
                        onAmountChange = { holder.value = holder.value.copy(amountText = it) },
                        onFrequencyChange = { holder.value = holder.value.copy(frequency = it) },
                        onValidityMonthsChange = { holder.value = holder.value.copy(validityMonths = it) },
                        onSubmit = { submitted = true },
                    ),
                )
            }
        }
        rule.onNodeWithTag(PayFlowTestTags.MANDATE_VPA).performTextInput("netflix@okbank")
        rule.onNodeWithTag(PayFlowTestTags.MANDATE_AMOUNT).performTextInput("649")
        rule.onNodeWithTag(PayFlowTestTags.mandateFrequency(MandateFrequency.WEEKLY)).performClick()
        rule.onNodeWithTag(PayFlowTestTags.mandateValidity(6)).performClick()
        rule.onNodeWithTag(PayFlowTestTags.MANDATE_SUBMIT).performScrollTo().performClick()
        assertThat(holder.value.vpa).isEqualTo("netflix@okbank")
        assertThat(holder.value.frequency).isEqualTo(MandateFrequency.WEEKLY)
        assertThat(holder.value.validityMonths).isEqualTo(6)
        assertThat(submitted).isTrue()
        holder.value = holder.value.copy(ruleError = MandateValidation.InvalidPayee)
        rule.onNodeWithText("This UPI ID cannot receive AutoPay mandates").assertIsDisplayed()
    }

    @Test
    fun detailActionsAndExecutions() {
        val execution = testPayment("p1", status = PaymentStatus.SUCCESS, method = PaymentMethod.MANDATE, amountPaise = 64_900, payeeName = "Netflix")
        val holder = mutableStateOf<MandateDetailUiState>(
            MandateDetailUiState.Content(mandate(), listOf(execution), availableActions = listOf(MandateAction.PAUSE, MandateAction.REVOKE), busy = false, debitAmountText = ""),
        )
        var action: MandateAction? = null; var debit = false
        rule.setContent {
            val s by holder
            PayFlowTheme { MandateDetailScreen(s, MandateDetailCallbacks(onAction = { action = it }, onSimulateDebit = { debit = true }, onDebitAmountChange = { holder.value = (holder.value as MandateDetailUiState.Content).copy(debitAmountText = it) })) }
        }
        rule.onNodeWithText("UMN123").assertIsDisplayed()
        rule.onAllNodesWithText("Netflix").onFirst().assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.mandateAction(MandateAction.PAUSE)).performClick()
        assertThat(action).isEqualTo(MandateAction.PAUSE)
        rule.onNodeWithTag(PayFlowTestTags.MANDATE_DEBIT_AMOUNT).performScrollTo().performTextInput("100")
        rule.onNodeWithTag(PayFlowTestTags.MANDATE_DEBIT).performScrollTo().performClick()
        assertThat(debit).isTrue()
        rule.onNodeWithTag(PayFlowTestTags.paymentRow("p1")).performScrollTo().assertIsDisplayed()
        holder.value = MandateDetailUiState.NotFound
        rule.onNodeWithText("Mandate not found").assertIsDisplayed()
    }
}
