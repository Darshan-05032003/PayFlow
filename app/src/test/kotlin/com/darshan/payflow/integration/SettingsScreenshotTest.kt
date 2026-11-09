package com.darshan.payflow.integration

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.security.BindingStatus
import com.darshan.payflow.data.security.BiometricAvailability
import com.darshan.payflow.data.security.GuardReason
import com.darshan.payflow.data.security.IntegrityVerdict
import com.darshan.payflow.data.security.TamperFinding
import com.darshan.payflow.data.security.TamperReport
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.UpiProfile
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.checkout.CheckoutCallbacks
import com.darshan.payflow.ui.checkout.CheckoutOutcome
import com.darshan.payflow.ui.checkout.CheckoutScreen
import com.darshan.payflow.ui.checkout.CheckoutUiState
import com.darshan.payflow.ui.designsystem.captureScreenshot
import com.darshan.payflow.ui.designsystem.effects.ThemeMode
import com.darshan.payflow.ui.designsystem.effects.UiSettings
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.me.MeCallbacks
import com.darshan.payflow.ui.me.MeScreen
import com.darshan.payflow.ui.me.MeUiState
import com.darshan.payflow.ui.receive.ReceiveCallbacks
import com.darshan.payflow.ui.receive.ReceiveScreen
import com.darshan.payflow.ui.receive.ReceiveUiState
import com.darshan.payflow.ui.security.CheckId
import com.darshan.payflow.ui.security.IntegrityStatus
import com.darshan.payflow.ui.security.PinRemovalError
import com.darshan.payflow.ui.security.PinRemovalState
import com.darshan.payflow.ui.security.SecurityCallbacks
import com.darshan.payflow.ui.security.SecurityScreen
import com.darshan.payflow.ui.security.SecurityUiState
import com.darshan.payflow.ui.security.TamperWarning
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Security centre, tamper warning, Me, Receive and Checkout in their main states, light and dark. Every name is invented. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val sandboxPhone = SecurityUiState(
        loading = false,
        pinConfigured = true,
        binding = BindingStatus.Bound("fp", "+91 ****3210"),
        tamper = TamperReport(listOf(TamperFinding.DEBUGGABLE)),
        integrity = IntegrityStatus.NotConfigured,
        biometric = BiometricAvailability.AVAILABLE,
        paymentMode = PaymentMode.SANDBOX,
    )
    private val fresh = SecurityUiState(
        loading = false,
        pinConfigured = false,
        tamper = TamperReport.CLEAN,
        integrity = IntegrityStatus.Result(IntegrityVerdict.MEETS_DEVICE_INTEGRITY, sandboxVerifier = true),
        biometric = BiometricAvailability.AVAILABLE,
        paymentMode = PaymentMode.UPI_APPS,
    )
    private val rooted = sandboxPhone.copy(tamper = TamperReport(listOf(TamperFinding.MAGISK, TamperFinding.DEBUGGABLE)))

    private fun security(name: String, state: SecurityUiState, dark: Boolean = false, fontScale: Float = 1f, open: String? = null) {
        // Reduced motion shows every final state at once, which is what a still image should hold.
        rule.setDs(dark = dark, fontScale = fontScale, reduceMotion = true) { SecurityScreen(state, SecurityCallbacks()) }
        rule.waitForIdle()
        open?.let { rule.onNodeWithTag(it).performClick(); rule.waitForIdle() }
        rule.captureScreenshot("settings/$name")
    }

    @Test fun securityLight() = security("security_light", sandboxPhone)
    @Test fun securityDark() = security("security_dark", sandboxPhone, dark = true)
    @Test fun securityNeedsYouLight() = security("security_needs_you_light", fresh)
    @Test fun securityLargeFont() = security("security_large_font", fresh, fontScale = 1.3f)
    @Test fun securityPaymentsOffDark() = security("security_payments_off_dark", rooted, dark = true)
    @Test fun securityBindingSheetDark() = security("security_binding_sheet_dark", fresh.copy(binding = BindingStatus.OtpPending("+91 ****3210"), sandboxOtp = "482913"), dark = true, open = PayFlowTestTags.securityCheck(CheckId.BINDING.name))
    @Test fun securityRemoveSheetLight() = security(
        "security_remove_sheet_light",
        sandboxPhone.copy(removal = PinRemovalState(biometricAvailable = true, remainingAttempts = 4, error = PinRemovalError.WRONG_PIN, attempts = 1)),
    )

    /** Mid-sequence with full motion: two rows ticked, the rest still checking. */
    @Test
    fun securityMidSequenceLight() {
        rule.mainClock.autoAdvance = false
        rule.setDs { SecurityScreen(sandboxPhone, SecurityCallbacks()) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(com.darshan.payflow.ui.security.CheckReveal.STEP_MS + 200)
        rule.captureScreenshot("settings/security_mid_sequence_light")
    }

    private fun tamper(name: String, dark: Boolean, fontScale: Float = 1f) {
        rule.setDs(dark = dark, fontScale = fontScale, reduceMotion = true) { TamperWarning(listOf(GuardReason.ROOTED, GuardReason.HOOKING_FRAMEWORK), onClose = {}, onRecheck = {}) }
        rule.waitForIdle()
        rule.captureScreenshot("settings/$name")
    }

    @Test fun tamperLight() = tamper("tamper_light", dark = false)
    @Test fun tamperDark() = tamper("tamper_dark", dark = true)
    @Test fun tamperLargeFont() = tamper("tamper_large_font", dark = false, fontScale = 1.3f)

    private val ira = UpiProfile("Ira Kulkarni", "ira@okaxis")

    private fun me(name: String, state: MeUiState, dark: Boolean = false, fontScale: Float = 1f, reduce: Boolean = false) {
        rule.setDs(dark = dark, fontScale = fontScale, reduceMotion = reduce) { MeScreen(state, MeCallbacks(), version = "1.0.0") }
        rule.waitForIdle()
        rule.captureScreenshot("settings/$name")
    }

    @Test fun meLight() = me("me_light", MeUiState(loading = false, profile = ira, pinConfigured = true))
    @Test fun meDark() = me("me_dark", MeUiState(loading = false, profile = ira, pinConfigured = true, settings = UiSettings(theme = ThemeMode.Dark, reduceMotion = true)), dark = true, reduce = true)
    @Test fun meNoProfileLight() = me("me_no_profile_light", MeUiState(loading = false, profile = null, pinConfigured = false))
    @Test fun meLargeFont() = me("me_large_font", MeUiState(loading = false, profile = ira, pinConfigured = true), fontScale = 1.3f)

    private fun receive(name: String, state: ReceiveUiState, dark: Boolean = false, fontScale: Float = 1f) {
        rule.setDs(dark = dark, fontScale = fontScale, reduceMotion = true) { ReceiveScreen(state, ReceiveCallbacks()) }
        rule.waitForIdle()
        rule.captureScreenshot("settings/$name")
    }

    @Test fun receiveLight() = receive("receive_light", ReceiveUiState(loading = false, profile = ira, amountText = "250"))
    @Test fun receiveDark() = receive("receive_dark", ReceiveUiState(loading = false, profile = ira), dark = true)
    @Test fun receiveSetupLight() = receive("receive_setup_light", ReceiveUiState(loading = false, editing = true, vpaInput = "ira@", vpaError = com.darshan.payflow.domain.model.ProfileFieldError.INVALID_VPA))
    @Test fun receiveLargeFont() = receive("receive_large_font", ReceiveUiState(loading = false, profile = ira, amountText = "250"), fontScale = 1.3f)

    private fun checkout(name: String, state: CheckoutUiState, dark: Boolean = false) {
        rule.setDs(dark = dark, reduceMotion = true) { CheckoutScreen(state, CheckoutCallbacks()) }
        rule.waitForIdle()
        rule.captureScreenshot("settings/$name")
    }

    @Test fun checkoutLight() = checkout("checkout_light", CheckoutUiState())
    @Test fun checkoutOutcomeDark() = checkout(
        "checkout_outcome_dark",
        CheckoutUiState(lastOutcome = CheckoutOutcome.Recorded(testPayment("ck-1", status = PaymentStatus.SUCCESS, amountPaise = 49_900))),
        dark = true,
    )

    /** Full motion, a moment after CheckoutKit's sheet opened: the store has receded behind where the sheet will sit. */
    @Test
    fun checkoutRecededLight() {
        rule.setDs { CheckoutScreen(CheckoutUiState(activeOrder = com.darshan.payflow.domain.usecase.CheckoutOrder("PF-mug-1", 29_900, "Ceramic mug", "store@okbank", "PayFlow Store")), CheckoutCallbacks()) }
        rule.waitForIdle()
        rule.captureScreenshot("settings/checkout_receded_light")
    }
}
