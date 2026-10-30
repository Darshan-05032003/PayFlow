package com.darshan.payflow.ui.security

import com.darshan.payflow.data.security.BindingStatus
import com.darshan.payflow.data.security.DeviceBindingManager
import com.darshan.payflow.data.security.DeviceIdentity
import com.darshan.payflow.data.security.FakeIntegrityVerifier
import com.darshan.payflow.data.security.FakeOtpSender
import com.darshan.payflow.data.security.InMemorySecureStore
import com.darshan.payflow.data.security.IntegrityChecker
import com.darshan.payflow.data.security.IntegrityResult
import com.darshan.payflow.data.security.IntegrityVerdict
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.data.security.TamperDetector
import com.darshan.payflow.data.security.TamperFinding
import com.darshan.payflow.data.security.TamperReport
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val store = InMemorySecureStore()
    private val sender = FakeOtpSender()
    private val pins = PinManager(store, fixedClock, iterations = 1_000)
    private val binding = DeviceBindingManager(store, object : DeviceIdentity { override fun fingerprint() = "fp" }, sender, fixedClock, kotlin.random.Random(3))
    private var report = TamperReport.CLEAN
    private val tamper = object : TamperDetector { override suspend fun scan() = report }
    private var integrity: IntegrityResult = IntegrityResult.Token("tok")
    private val modes = com.darshan.payflow.data.settings.InMemoryPaymentModeRepository(com.darshan.payflow.domain.model.PaymentMode.UPI_APPS)
    private val launcher = com.darshan.payflow.testutil.FakeUpiAppLauncher()

    private fun vm() = SecurityViewModel(
        pins, binding, sender, tamper,
        object : IntegrityChecker { override val isConfigured get() = integrity !is IntegrityResult.NotConfigured; override suspend fun requestToken(requestHash: String) = integrity },
        FakeIntegrityVerifier(IntegrityVerdict.MEETS_DEVICE_INTEGRITY), modes, launcher, TestDispatcherProvider(dispatcher),
    )

    @Test
    fun `payment mode is read, switched and refused when no upi app exists`() = runTest(dispatcher) {
        val vm = vm(); advanceUntilIdle()
        assertThat(vm.uiState.value.paymentMode).isEqualTo(com.darshan.payflow.domain.model.PaymentMode.UPI_APPS)
        assertThat(vm.uiState.value.upiAppAvailable).isTrue()
        vm.setPaymentMode(com.darshan.payflow.domain.model.PaymentMode.SANDBOX); advanceUntilIdle()
        assertThat(vm.uiState.value.paymentMode).isEqualTo(com.darshan.payflow.domain.model.PaymentMode.SANDBOX)
        assertThat(modes.current()).isEqualTo(com.darshan.payflow.domain.model.PaymentMode.SANDBOX)

        launcher.installed = false
        val vm2 = vm(); advanceUntilIdle()
        assertThat(vm2.uiState.value.upiAppAvailable).isFalse()
        vm2.setPaymentMode(com.darshan.payflow.domain.model.PaymentMode.UPI_APPS); advanceUntilIdle()
        assertThat(modes.current()).isEqualTo(com.darshan.payflow.domain.model.PaymentMode.SANDBOX)
    }

    @Test
    fun `loads pin, binding and tamper status`() = runTest(dispatcher) {
        pins.set("493027")
        report = TamperReport(listOf(TamperFinding.DEBUGGER))
        val vm = vm(); advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.loading).isFalse()
        assertThat(s.pinConfigured).isTrue()
        assertThat(s.binding).isEqualTo(BindingStatus.Unbound)
        assertThat(s.tamper!!.findings).containsExactly(TamperFinding.DEBUGGER)
    }

    @Test
    fun `binding flow through the sandbox otp`() = runTest(dispatcher) {
        val vm = vm(); advanceUntilIdle()
        vm.onPhoneChange("12"); vm.requestOtp(); advanceUntilIdle()
        assertThat(vm.uiState.value.bindingError).isEqualTo(BindingError.INVALID_PHONE)
        vm.onPhoneChange("9876543210"); vm.requestOtp(); advanceUntilIdle()
        assertThat(vm.uiState.value.binding).isEqualTo(BindingStatus.OtpPending("+91 ****3210"))
        assertThat(vm.uiState.value.sandboxOtp).isEqualTo(sender.lastCode)
        vm.onOtpChange("00000x0"); assertThat(vm.uiState.value.otpInput).isEqualTo("000000")
        vm.verifyOtp(); advanceUntilIdle()
        assertThat(vm.uiState.value.bindingError).isEqualTo(BindingError.WRONG_OTP)
        vm.onOtpChange(sender.lastCode!!); vm.verifyOtp(); advanceUntilIdle()
        assertThat(vm.uiState.value.binding).isInstanceOf(BindingStatus.Bound::class.java)
        vm.unbind(); advanceUntilIdle()
        assertThat(vm.uiState.value.binding).isEqualTo(BindingStatus.Unbound)
    }

    @Test
    fun `integrity check reports verdict, not configured and errors`() = runTest(dispatcher) {
        val vm = vm(); advanceUntilIdle()
        vm.checkIntegrity(); advanceUntilIdle()
        assertThat(vm.uiState.value.integrity).isEqualTo(IntegrityStatus.Result(IntegrityVerdict.MEETS_DEVICE_INTEGRITY, sandboxVerifier = true))
        integrity = IntegrityResult.NotConfigured
        vm.checkIntegrity(); advanceUntilIdle()
        assertThat(vm.uiState.value.integrity).isEqualTo(IntegrityStatus.NotConfigured)
        integrity = IntegrityResult.Error("API_NOT_AVAILABLE")
        vm.checkIntegrity(); advanceUntilIdle()
        assertThat(vm.uiState.value.integrity).isEqualTo(IntegrityStatus.Error("API_NOT_AVAILABLE"))
    }

    @Test
    fun `remove pin`() = runTest(dispatcher) {
        pins.set("493027")
        val vm = vm(); advanceUntilIdle()
        vm.removePin(); advanceUntilIdle()
        assertThat(vm.uiState.value.pinConfigured).isFalse()
        assertThat(pins.isSet()).isFalse()
    }
}
