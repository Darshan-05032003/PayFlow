package com.darshan.payflow.ui.pay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class PayScreenTest {
    @get:Rule val rule = createComposeRule()

    private val holder = mutableStateOf(PayUiState())

    /** Renders the screen from [holder] so tests can switch states and type into fields like a real host. */
    private fun show(state: PayUiState, callbacks: PayCallbacks = PayCallbacks(), dark: Boolean = false) {
        holder.value = state
        rule.setContent {
            val current by holder
            PayFlowTheme(darkTheme = dark) { PayScreen(current, callbacks) }
        }
    }

    @Test
    fun formInputsCallBackAndSubmit() {
        var submitted = false
        show(
            PayUiState(),
            PayCallbacks(
                onVpaChange = { holder.value = holder.value.copy(vpa = it) },
                onAmountChange = { holder.value = holder.value.copy(amountText = it) },
                onSubmit = { submitted = true },
            ),
        )
        rule.onNodeWithTag(PayFlowTestTags.PAY_VPA).performTextInput("alice@okbank")
        rule.onNodeWithTag(PayFlowTestTags.PAY_AMOUNT).performTextInput("10")
        rule.onNodeWithTag(PayFlowTestTags.PAY_SUBMIT).performClick()
        assertThat(holder.value.vpa).isEqualTo("alice@okbank")
        assertThat(holder.value.amountText).isEqualTo("10")
        assertThat(submitted).isTrue()
    }

    @Test
    fun fieldErrorsAreShown() {
        show(PayUiState(errors = PayFormErrors(vpa = PayFieldError.INVALID_VPA, amount = PayFieldError.AMOUNT_TOO_LARGE)))
        rule.onNodeWithText("Enter a UPI ID like name@bank").assertIsDisplayed()
        rule.onNodeWithText("Maximum is ₹1,00,000.00 per payment").assertIsDisplayed()
    }

    @Test
    fun submittingDisablesTheButtonAndShowsProgress() {
        show(PayUiState(phase = PayPhase.Submitting), dark = true)
        rule.onNodeWithTag(PayFlowTestTags.PAY_SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag(PayFlowTestTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun awaitingAppAndConfirmingStates() {
        show(PayUiState(phase = PayPhase.AwaitingApp("p1")))
        rule.onNodeWithText("Complete the payment in your UPI app").assertIsDisplayed()
        holder.value = PayUiState(phase = PayPhase.Confirming("p1"))
        rule.onNodeWithText("Confirming with the payment provider…").assertIsDisplayed()
    }

    @Test
    fun doneStatesOfferTheRightActions() {
        var checked = false; var opened: String? = null; var reset = false
        show(
            PayUiState(phase = PayPhase.Done("p1", PaymentStatus.PENDING, null)),
            PayCallbacks(onCheckStatus = { checked = true }, onOpenPayment = { opened = it }, onReset = { reset = true }),
        )
        rule.onNodeWithText("Payment pending").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.PAY_CHECK_STATUS).performClick()
        assertThat(checked).isTrue()
        rule.onNodeWithText("View payment").performClick()
        assertThat(opened).isEqualTo("p1")
        rule.onNodeWithText("New payment").performClick()
        assertThat(reset).isTrue()
    }

    @Test
    fun failedAndUnresolvedStates() {
        show(PayUiState(phase = PayPhase.Done("p1", PaymentStatus.FAILED, "Declined by bank")))
        rule.onNodeWithText("Payment failed").assertIsDisplayed()
        rule.onNodeWithText("Declined by bank").assertIsDisplayed()
        holder.value = PayUiState(phase = PayPhase.Unresolved("p1"))
        rule.onNodeWithText("We could not reach the payment provider").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.PAY_CHECK_STATUS).assertIsDisplayed()
    }
}
