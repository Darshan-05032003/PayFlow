package com.darshan.payflow.integration

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.upi.UpiApp
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.captureScreenshot
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.pay.PayCallbacks
import com.darshan.payflow.ui.pay.PayPhase
import com.darshan.payflow.ui.pay.PayScreen
import com.darshan.payflow.ui.pay.PayUiState
import com.darshan.payflow.ui.pay.VpaVerificationState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pay in each main state, light and dark. Static frames: the animated moments are checked by the motion tests. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class PayScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val empty = PayUiState(paymentMode = PaymentMode.SANDBOX)
    private val filled = PayUiState(
        vpa = "asha@okicici", name = "Asha Rao", amountText = "1250",
        verification = VpaVerificationState.Verified("Asha Rao"), paymentMode = PaymentMode.SANDBOX,
    )
    private val locked = PayUiState(
        vpa = "shop@okbank", name = "Tulsi Tea Corner", amountText = "45.00", note = "Table 4",
        payeeLocked = true, amountLocked = true, verification = VpaVerificationState.Verified("Tulsi Tea Corner"), paymentMode = PaymentMode.SANDBOX,
    )
    private val apps = listOf(UpiApp("com.example.gpay", "GPay"), UpiApp("com.example.phonepe", "PhonePe"), UpiApp("com.example.bhim", "BHIM"))

    private fun record(status: PaymentStatus, source: StatusSource = StatusSource.PSP, handler: String? = null): Payment =
        testPayment("p1", txnRef = "PF0MV8K2", status = status, payeeName = "Asha Rao", payeeVpa = "asha@okicici", amountPaise = 1_25_000)
            .copy(statusSource = source, handlerPackage = handler, upiTxnId = "UPI412345678901", note = "Dinner")

    private fun show(name: String, state: PayUiState, dark: Boolean = false, fontScale: Float = 1f, callbacks: PayCallbacks = PayCallbacks(), open: String? = null) {
        val holder = mutableStateOf(state)
        rule.setDs(dark = dark, fontScale = fontScale, reduceMotion = true) {
            val current by holder
            PayScreen(current, callbacks)
        }
        rule.waitForIdle()
        open?.let { rule.onNodeWithTag(it).performClick() }
        rule.waitForIdle()
        rule.captureScreenshot("pay/$name")
    }

    @Test fun formEmptyLight() = show("form_empty_light", empty)
    @Test fun formEmptyDark() = show("form_empty_dark", empty, dark = true)
    @Test fun formFilledLight() = show("form_filled_light", filled)
    @Test fun formFilledDark() = show("form_filled_dark", filled, dark = true)
    @Test fun formFilledLarge() = show("form_filled_large_font", filled, fontScale = 1.3f)
    @Test fun formLockedLight() = show("form_locked_light", locked)
    @Test fun formLockedDark() = show("form_locked_dark", locked, dark = true)
    @Test fun formMaxDisplayCase() = show("form_max_display", locked.copy(amountText = "999999999.99"))
    @Test fun formLongNames() = show(
        "form_long_names",
        locked.copy(name = "Shri Venkateswara Lakshmi Narasimha Enterprises and Sons Private Limited Bengaluru", vpa = "a-very-long-merchant-handle-for-testing-layouts@okhdfcbank", note = "N".repeat(50), verification = VpaVerificationState.Skipped, paymentMode = PaymentMode.UPI_APPS),
        fontScale = 1.3f,
    )
    @Test fun confirmSheetLight() = show("confirm_sheet_light", filled, open = PayFlowTestTags.PAY_PLAIN_ACTION)
    @Test fun confirmSheetDark() = show("confirm_sheet_dark", filled, dark = true, open = PayFlowTestTags.PAY_PLAIN_ACTION)
    @Test fun appSheetLight() = show("app_sheet_light", filled.copy(phase = PayPhase.ChoosingApp(apps), paymentMode = PaymentMode.UPI_APPS))
    @Test fun appSheetPickedDark() = show("app_sheet_picked_dark", filled.copy(phase = PayPhase.ChoosingApp(apps, picked = "com.example.phonepe"), paymentMode = PaymentMode.UPI_APPS), dark = true)
    @Test fun awaitingLight() = show("awaiting_light", filled.copy(phase = PayPhase.AwaitingApp("p1")))
    @Test fun confirmingDark() = show("confirming_dark", filled.copy(phase = PayPhase.Confirming("p1")), dark = true)
    @Test fun successLight() = show("success_light", filled.copy(phase = PayPhase.Done("p1", PaymentStatus.SUCCESS, null, record(PaymentStatus.SUCCESS))))
    @Test fun successDark() = show("success_dark", filled.copy(phase = PayPhase.Done("p1", PaymentStatus.SUCCESS, null, record(PaymentStatus.SUCCESS))), dark = true)
    @Test fun successLarge() = show("success_large_font", filled.copy(phase = PayPhase.Done("p1", PaymentStatus.SUCCESS, null, record(PaymentStatus.SUCCESS))), fontScale = 1.3f)
    @Test fun reportedLight() = show("reported_light", filled.copy(paymentMode = PaymentMode.UPI_APPS, phase = PayPhase.Done("p1", PaymentStatus.SUCCESS, null, record(PaymentStatus.SUCCESS, StatusSource.UPI_APP, "com.example.gpay"), "GPay")))
    @Test fun markedDark() = show("marked_dark", filled.copy(paymentMode = PaymentMode.UPI_APPS, phase = PayPhase.Done("p1", PaymentStatus.SUCCESS, null, record(PaymentStatus.SUCCESS, StatusSource.USER, "com.example.gpay"), "GPay")), dark = true)
    @Test fun pendingLight() = show("pending_light", filled.copy(polling = true, phase = PayPhase.Done("p1", PaymentStatus.PENDING, null, record(PaymentStatus.PENDING))))
    @Test fun pendingRealDark() = show("pending_real_dark", filled.copy(paymentMode = PaymentMode.UPI_APPS, phase = PayPhase.Done("p1", PaymentStatus.PENDING, null, record(PaymentStatus.PENDING, handler = "com.example.gpay"), "GPay")), dark = true)
    @Test fun failedLight() = show("failed_light", filled.copy(phase = PayPhase.Done("p1", PaymentStatus.FAILED, "Declined by the payer (sandbox)", record(PaymentStatus.FAILED))))
    @Test fun failedDark() = show("failed_dark", filled.copy(phase = PayPhase.Done("p1", PaymentStatus.FAILED, "Declined by the payer (sandbox)", record(PaymentStatus.FAILED))), dark = true)
    @Test fun refundLight() = show("refund_light", filled.copy(phase = PayPhase.Done("p1", PaymentStatus.REFUNDED, null, record(PaymentStatus.REFUNDED))))
    @Test fun unresolvedDark() = show("unresolved_dark", filled.copy(phase = PayPhase.Unresolved("p1")), dark = true)
    @Test fun mismatchLight() = show("mismatch_light", filled.copy(phase = PayPhase.Unresolved("p1", mismatch = true)))
    @Test fun shareSheetLight() = show("share_sheet_light", filled.copy(phase = PayPhase.Done("p1", PaymentStatus.SUCCESS, null, record(PaymentStatus.SUCCESS))), open = PayFlowTestTags.PAY_SHARE)
}
