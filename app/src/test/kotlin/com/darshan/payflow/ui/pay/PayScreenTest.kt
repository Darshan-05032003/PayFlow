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
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.data.upi.UpiApp
import com.darshan.payflow.testutil.testPayment
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
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
        var checked = false; var opened: String? = null; var done = false; var again = false
        show(
            PayUiState(phase = PayPhase.Done("p1", PaymentStatus.PENDING, null), paymentMode = PaymentMode.SANDBOX),
            PayCallbacks(onCheckStatus = { checked = true }, onOpenPayment = { opened = it }, onDone = { done = true }, onPayAgain = { again = true }),
        )
        rule.onNodeWithText("Payment pending").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.PAY_CHECK_STATUS).performClick()
        assertThat(checked).isTrue()
        rule.onNodeWithText("View payment").performClick()
        assertThat(opened).isEqualTo("p1")
        rule.onNodeWithTag(PayFlowTestTags.PAY_AGAIN).performClick()
        assertThat(again).isTrue()
        rule.onNodeWithText("Done").performClick()
        assertThat(done).isTrue()
    }

    @Test
    fun resultReceiptShowsAmountPayeeSourceAndShares() {
        var shared = false
        val payment = testPayment("p1", status = PaymentStatus.SUCCESS, payeeName = "Rahul Verma", payeeVpa = "rahul@okaxis", amountPaise = 12_345_67)
            .copy(handlerPackage = "com.example.gpay", statusSource = StatusSource.UPI_APP, upiTxnId = "GP777")
        show(
            PayUiState(phase = PayPhase.Done("p1", PaymentStatus.SUCCESS, null, payment, handlerLabel = "GPay"), paymentMode = PaymentMode.UPI_APPS),
            PayCallbacks(onShare = { shared = true }),
        )
        rule.onNodeWithText("Payment successful").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.PAY_RESULT_AMOUNT).assertTextEquals("₹12,345.67")
        rule.onNodeWithText("Rahul Verma").assertIsDisplayed()
        rule.onNodeWithText("Reported by GPay. Not verified by PayFlow.").assertIsDisplayed()
        rule.onNodeWithText("GP777").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.PAY_SHARE).performScrollTo().performClick()
        assertThat(shared).isTrue()
        rule.onAllNodesWithTag(PayFlowTestTags.PAY_CHECK_STATUS).assertCountEquals(0) // no PSP to ask in UPI-apps mode
    }

    @Test
    fun pendingUpiAppResultOffersManualMarkingInsteadOfAPspCheck() {
        var marked: Boolean? = null
        val payment = testPayment("p1", status = PaymentStatus.PENDING).copy(handlerPackage = "com.example.phonepe")
        show(
            PayUiState(phase = PayPhase.Done("p1", PaymentStatus.PENDING, null, payment, handlerLabel = "PhonePe"), paymentMode = PaymentMode.UPI_APPS),
            PayCallbacks(onMarkPaid = { marked = true }, onMarkNotPaid = { marked = false }),
        )
        rule.onNodeWithText("Not confirmed yet. Check in PhonePe.").assertIsDisplayed()
        rule.onAllNodesWithTag(PayFlowTestTags.PAY_CHECK_STATUS).assertCountEquals(0)
        rule.onNodeWithTag(PayFlowTestTags.PAY_MARK_PAID).performScrollTo().performClick()
        assertThat(marked).isTrue()
        rule.onNodeWithTag(PayFlowTestTags.PAY_MARK_NOT_PAID).performScrollTo().performClick()
        assertThat(marked).isFalse()
    }

    @Test
    fun appChooserSheetListsInstalledAppsAndReportsThePick() {
        var picked: String? = null
        var dismissed = false
        val apps = listOf(UpiApp("com.example.gpay", "GPay"), UpiApp("com.example.phonepe", "PhonePe"))
        show(
            PayUiState(vpa = "rahul@okaxis", amountText = "250", phase = PayPhase.ChoosingApp(apps), paymentMode = PaymentMode.UPI_APPS),
            PayCallbacks(onAppChosen = { picked = it }, onDismissChooser = { dismissed = true }),
        )
        rule.onNodeWithTag(PayFlowTestTags.PAY_APP_SHEET).assertExists()
        rule.onNodeWithText("₹250.00 to rahul@okaxis").assertIsDisplayed()
        rule.onNodeWithContentDescription("Pay with PhonePe").performClick()
        assertThat(picked).isEqualTo("com.example.phonepe")
        assertThat(dismissed).isFalse()
    }

    @Test
    fun amountDisplayUsesIndianGroupingAndModeBannersAreHonest() {
        show(PayUiState(amountText = "123456.7", paymentMode = PaymentMode.UPI_APPS, verification = VpaVerificationState.Skipped))
        rule.onNodeWithTag(PayFlowTestTags.PAY_AMOUNT_DISPLAY).assertContentDescriptionEquals("Amount to pay: ₹1,23,456.70")
        rule.onNodeWithText("Pay ₹1,23,456.70").assertIsDisplayed()
        rule.onNodeWithText("Your UPI app shows the registered name before you confirm.").assertIsDisplayed()
        rule.onAllNodesWithTag(PayFlowTestTags.SANDBOX_BADGE).assertCountEquals(0)
        holder.value = PayUiState(paymentMode = PaymentMode.SANDBOX)
        rule.onNodeWithTag(PayFlowTestTags.SANDBOX_BADGE).assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.PAY_AMOUNT_DISPLAY).assertContentDescriptionEquals("Amount to pay: ₹0.00")
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
