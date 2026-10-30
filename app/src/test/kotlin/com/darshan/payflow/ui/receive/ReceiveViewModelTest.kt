package com.darshan.payflow.ui.receive

import com.darshan.payflow.data.profile.SecureUpiProfileRepository
import com.darshan.payflow.data.security.InMemorySecureStore
import com.darshan.payflow.domain.model.ProfileFieldError
import com.darshan.payflow.domain.model.UpiProfile
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiveViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val profiles = SecureUpiProfileRepository(InMemorySecureStore())

    private fun vm() = ReceiveViewModel(profiles, TestDispatcherProvider(dispatcher))

    @Test
    fun `starts in setup when there is no profile, validates and saves`() = runTest(dispatcher) {
        val vm = vm(); advanceUntilIdle()
        assertThat(vm.uiState.value.loading).isFalse()
        assertThat(vm.uiState.value.editing).isTrue()
        assertThat(vm.uiState.value.qrContent).isNull()
        vm.save(); advanceUntilIdle()
        assertThat(vm.uiState.value.nameError).isEqualTo(ProfileFieldError.REQUIRED)
        assertThat(vm.uiState.value.vpaError).isEqualTo(ProfileFieldError.REQUIRED)
        vm.onNameChange("Darshan"); vm.onVpaChange("not a vpa"); vm.save(); advanceUntilIdle()
        assertThat(vm.uiState.value.vpaError).isEqualTo(ProfileFieldError.INVALID_VPA)
        vm.onVpaChange("Darshan@OKAXIS"); vm.save(); advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.editing).isFalse()
        assertThat(s.profile).isEqualTo(UpiProfile("Darshan", "darshan@okaxis"))
        assertThat(s.qrContent).isEqualTo("upi://pay?pa=darshan%40okaxis&pn=Darshan&cu=INR")
        assertThat(profiles.get()).isEqualTo(s.profile)
    }

    @Test
    fun `an optional amount goes into the qr and bad amounts are ignored`() = runTest(dispatcher) {
        profiles.save(UpiProfile("Darshan", "darshan@okaxis"))
        val vm = vm(); advanceUntilIdle()
        assertThat(vm.uiState.value.editing).isFalse()
        vm.onAmountChange("abc"); assertThat(vm.uiState.value.amountText).isEmpty()
        vm.onAmountChange("150.5")
        assertThat(vm.uiState.value.amountPaise).isEqualTo(15_050)
        assertThat(vm.uiState.value.qrContent).isEqualTo("upi://pay?pa=darshan%40okaxis&pn=Darshan&am=150.50&cu=INR")
        vm.onAmountChange("0")
        assertThat(vm.uiState.value.qrContent).doesNotContain("am=")
        vm.onAmountChange("9999999") // above the UPI cap: not encoded
        assertThat(vm.uiState.value.amountPaise).isNull()
    }

    @Test
    fun `edit can be cancelled only when a profile exists`() = runTest(dispatcher) {
        profiles.save(UpiProfile("Darshan", "darshan@okaxis"))
        val vm = vm(); advanceUntilIdle()
        vm.edit()
        assertThat(vm.uiState.value.editing).isTrue()
        vm.onNameChange("Changed")
        vm.cancelEdit()
        assertThat(vm.uiState.value.editing).isFalse()
        assertThat(vm.uiState.value.profile!!.name).isEqualTo("Darshan")
    }
}
