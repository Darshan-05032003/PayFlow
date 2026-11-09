package com.darshan.payflow.ui.history

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.usecase.MarkPaymentManuallyUseCase
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.FakeUpiAppLauncher
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.testutil.reconcileNoop
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.designsystem.effects.InMemoryUiPreferences
import com.darshan.payflow.ui.designsystem.effects.UiSettings
import com.google.common.truth.Truth.assertThat
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The money-screens row: day sections, hide amounts, and the row actions' results. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryMoneyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val repo = FakePaymentRepository()
    private val prefs = InMemoryUiPreferences()

    private fun vm(handle: SavedStateHandle = SavedStateHandle()) =
        HistoryViewModel(repo, reconcileNoop(repo), TestDispatcherProvider(dispatcher), handle, prefs, fixedClock, ZoneOffset.UTC)

    @Test
    fun `content carries day sections built in the user's zone`() = runTest(dispatcher) {
        repo.seed(
            testPayment("a", status = PaymentStatus.SUCCESS, amountPaise = 1_000),
            testPayment("b", status = PaymentStatus.SUCCESS, createdAt = FIXED_INSTANT.minusSeconds(86_400)),
        )
        vm().uiState.test {
            awaitItem()
            val content = awaitItem() as HistoryUiState.Content
            val days = content.entries.filterIsInstance<HistoryEntry.Day>()
            assertThat(days.map { it.label }).containsExactly(DayLabel.Today, DayLabel.Yesterday).inOrder()
            assertThat(days.first().sentPaise).isEqualTo(1_000)
        }
    }

    @Test
    fun `history honours Home's hide-amounts setting and can turn it off`() = runTest(dispatcher) {
        prefs.setAmountsHidden(true)
        val vm = vm()
        vm.amountsHidden.test {
            assertThat(awaitItem()).isTrue()
            vm.toggleAmountsHidden()
            assertThat(awaitItem()).isFalse()
        }
        assertThat(prefs.settings.value.amountsHidden).isFalse()
    }

    @Test
    fun `check status asks the provider about this payment straight away and reports back`() = runTest(dispatcher) {
        repo.seed(testPayment("p", status = PaymentStatus.PENDING, createdAt = FIXED_INSTANT))
        val vm = vm()
        vm.effects.test {
            vm.checkPending()
            advanceUntilIdle()
            // The provider here knows nothing, so the honest answer is "still waiting", not success.
            assertThat(awaitItem()).isEqualTo(HistoryEffect.Checked(checked = 1, settled = 0))
        }
    }

    @Test
    fun `a second check while one runs is dropped`() = runTest(dispatcher) {
        repo.seed(testPayment("p", status = PaymentStatus.PENDING))
        val vm = vm()
        vm.effects.test {
            repeat(1_000) { vm.checkPending() }
            advanceUntilIdle()
            assertThat(awaitItem()).isInstanceOf(HistoryEffect.Checked::class.java)
            expectNoEvents()
        }
    }

    @Test
    fun `payment detail masks the amount until the stored choice is read, then follows it`() = runTest(dispatcher) {
        val unread = object : com.darshan.payflow.ui.designsystem.effects.UiPreferences by InMemoryUiPreferences(UiSettings(amountsHidden = false)) {
            override val loaded = kotlinx.coroutines.flow.MutableStateFlow(false)
        }
        repo.seed(testPayment("a", status = PaymentStatus.SUCCESS))
        val detail = PaymentDetailViewModel(
            repo, MarkPaymentManuallyUseCase(repo), FakeUpiAppLauncher(), TestDispatcherProvider(dispatcher),
            SavedStateHandle(mapOf(PaymentDetailViewModel.ARG_ID to "a")), unread,
        )
        detail.amountsHidden.test {
            assertThat(awaitItem()).isTrue()
            unread.loaded.value = true
            assertThat(awaitItem()).isFalse()
        }
    }
}
