package com.darshan.payflow.ui.home

import app.cash.turbine.test
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import com.darshan.payflow.ui.designsystem.effects.InMemoryUiPreferences
import com.darshan.payflow.ui.designsystem.effects.UiEffect
import com.darshan.payflow.ui.designsystem.effects.UiPreferences
import com.darshan.payflow.ui.designsystem.effects.UiSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val repo = FakePaymentRepository()

    private val psp = com.darshan.payflow.data.psp.FakePsp(com.darshan.payflow.testutil.fixedClock, com.darshan.payflow.data.psp.FakePspConfig(pendingPolls = 1))
    private fun reconcile() = com.darshan.payflow.domain.usecase.ReconcilePendingPaymentsUseCase(
        repo,
        com.darshan.payflow.domain.usecase.PollPaymentStatusUseCase(
            com.darshan.payflow.domain.usecase.ConfirmPaymentUseCase(repo, psp, com.darshan.payflow.testutil.fixedClock),
            com.darshan.payflow.core.BackoffPolicy(initialDelayMillis = 10, maxDelayMillis = 100, multiplier = 2.0, jitterFactor = 0.0, maxAttempts = 2),
            kotlin.random.Random(1),
        ),
        com.darshan.payflow.testutil.fixedClock,
    )

    private fun vm() = HomeViewModel(repo, reconcile(), TestDispatcherProvider(dispatcher))

    @Test
    fun `starts loading then shows empty when there are no payments`() = runTest(dispatcher) {
        vm().uiState.test {
            assertThat(awaitItem()).isEqualTo(HomeUiState.Loading)
            assertThat(awaitItem()).isEqualTo(HomeUiState.Empty)
        }
    }

    @Test
    fun `shows recent payments and summary`() = runTest(dispatcher) {
        repo.seed(
            testPayment("a", status = PaymentStatus.SUCCESS, amountPaise = 100),
            testPayment("b", status = PaymentStatus.PENDING, amountPaise = 200, createdAt = testPayment().createdAt.plusSeconds(1)),
        )
        vm().uiState.test {
            awaitItem() // loading
            val content = awaitItem() as HomeUiState.Content
            assertThat(content.recent.map { it.id }).containsExactly("b", "a").inOrder()
            assertThat(content.summary.total).isEqualTo(2)
            assertThat(content.summary.sentPaise).isEqualTo(100)
            assertThat(content.recentPayees.map { it.vpa }).containsExactly("alice@okbank") // distinct by VPA
        }
    }

    @Test
    fun `month summary only counts payments created this month`() = runTest(dispatcher) {
        // fixedClock is 2026-10-29T10:15:30Z; "b" was created in September.
        repo.seed(
            testPayment("a", status = PaymentStatus.SUCCESS, amountPaise = 100),
            testPayment("b", status = PaymentStatus.SUCCESS, amountPaise = 200, createdAt = java.time.Instant.parse("2026-09-30T23:59:59Z")),
        )
        val vm = HomeViewModel(repo, reconcile(), TestDispatcherProvider(dispatcher), com.darshan.payflow.testutil.fixedClock, java.time.ZoneOffset.UTC)
        vm.uiState.test {
            awaitItem() // loading
            val content = awaitItem() as HomeUiState.Content
            assertThat(content.summary.sentPaise).isEqualTo(300)
            assertThat(content.month.sentPaise).isEqualTo(100)
            assertThat(content.month.total).isEqualTo(1)
        }
    }

    @Test
    fun `repository failure becomes error state and retry recovers`() = runTest(dispatcher) {
        repo.failure = IOException("disk")
        val vm = vm()
        vm.uiState.test {
            awaitItem()
            assertThat(awaitItem()).isInstanceOf(HomeUiState.Error::class.java)
            repo.failure = null
            vm.retry()
            assertThat(awaitItem()).isEqualTo(HomeUiState.Loading)
            assertThat(awaitItem()).isEqualTo(HomeUiState.Empty)
        }
    }

    @Test
    fun `recent list is capped`() = runTest(dispatcher) {
        repo.seed(*(1..20).map { testPayment("p$it", txnRef = "t$it", createdAt = testPayment().createdAt.plusSeconds(it.toLong())) }.toTypedArray())
        vm().uiState.test {
            awaitItem()
            val content = awaitItem() as HomeUiState.Content
            assertThat(content.recent).hasSize(HomeViewModel.RECENT_LIMIT)
            assertThat(content.recent.first().id).isEqualTo("p20")
        }
    }

    @Test
    fun `home start reconciles stale pending payments with the psp`() = runTest(dispatcher) {
        val stale = testPayment("stale", txnRef = "STALE1", status = PaymentStatus.PENDING, createdAt = testPayment().createdAt.minusSeconds(3_600))
        repo.seed(stale)
        psp.initiatePayment(com.darshan.payflow.data.psp.PspInitiateRequest("STALE1", stale.amountPaise, stale.payeeVpa, stale.payeeName, "", null))
        vm().uiState.test {
            awaitItem()
            awaitItem() // content with the pending row
            val settled = awaitItem() as HomeUiState.Content
            assertThat(settled.recent.single().status).isEqualTo(PaymentStatus.SUCCESS)
        }
    }

    // Pull to refresh

    private fun vmWith(prefs: UiPreferences = InMemoryUiPreferences()) =
        HomeViewModel(repo, reconcile(), TestDispatcherProvider(dispatcher), com.darshan.payflow.testutil.fixedClock, java.time.ZoneOffset.UTC, prefs)

    @Test
    fun `refresh shows while it runs, for long enough to be seen, then ends`() = runTest(dispatcher) {
        val vm = vmWith()
        runCurrent()
        assertThat(vm.refreshing.value).isFalse()
        vm.refresh()
        runCurrent()
        assertThat(vm.refreshing.value).isTrue()
        advanceTimeBy(HomeViewModel.MIN_REFRESH_MS - 1)
        runCurrent()
        assertThat(vm.refreshing.value).isTrue()
        advanceUntilIdle()
        assertThat(vm.refreshing.value).isFalse()
    }

    @Test
    fun `refresh settles stale pending payments and says so`() = runTest(dispatcher) {
        val vm = vmWith()
        advanceUntilIdle()
        val stale = testPayment("stale", txnRef = "STALE1", status = PaymentStatus.PENDING, createdAt = testPayment().createdAt.minusSeconds(3_600))
        repo.seed(stale)
        psp.initiatePayment(com.darshan.payflow.data.psp.PspInitiateRequest("STALE1", stale.amountPaise, stale.payeeVpa, stale.payeeName, "", null))
        vm.effects.test {
            vm.refresh()
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(HomeEffect.Refreshed(checked = 1, settled = 1))
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(repo.getById("stale")!!.status).isEqualTo(PaymentStatus.SUCCESS)
    }

    @Test
    fun `refresh with nothing to check says that rather than pretending`() = runTest(dispatcher) {
        val vm = vmWith()
        advanceUntilIdle()
        vm.effects.test {
            vm.refresh()
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(HomeEffect.Refreshed(checked = 0, settled = 0))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failed refresh ends, reports it with a reject haptic and leaves the list alone`() = runTest(dispatcher) {
        val failing = object : com.darshan.payflow.domain.repository.PaymentRepository by repo {
            override suspend fun pendingOlderThan(before: java.time.Instant): List<com.darshan.payflow.domain.model.Payment> = throw IOException("disk")
        }
        val vm = HomeViewModel(
            failing,
            com.darshan.payflow.domain.usecase.ReconcilePendingPaymentsUseCase(
                failing,
                com.darshan.payflow.domain.usecase.PollPaymentStatusUseCase(
                    com.darshan.payflow.domain.usecase.ConfirmPaymentUseCase(failing, psp, com.darshan.payflow.testutil.fixedClock),
                    com.darshan.payflow.core.BackoffPolicy(initialDelayMillis = 10, maxDelayMillis = 100, multiplier = 2.0, jitterFactor = 0.0, maxAttempts = 2),
                    kotlin.random.Random(1),
                ),
                com.darshan.payflow.testutil.fixedClock,
            ),
            TestDispatcherProvider(dispatcher),
        )
        vm.uiState.test {
            awaitItem()
            assertThat(awaitItem()).isEqualTo(HomeUiState.Empty)
            vm.effects.test {
                vm.refresh()
                advanceUntilIdle()
                assertThat(awaitItem()).isEqualTo(UiEffect.Haptic(HapticEvent.Reject))
                assertThat(awaitItem()).isEqualTo(HomeEffect.RefreshFailed)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(vm.refreshing.value).isFalse()
            expectNoEvents()
        }
    }

    @Test
    fun `1,000 refresh requests while one runs start exactly one`() = runTest(dispatcher) {
        var calls = 0
        val counting = object : com.darshan.payflow.domain.repository.PaymentRepository by repo {
            override suspend fun pendingOlderThan(before: java.time.Instant): List<com.darshan.payflow.domain.model.Payment> {
                calls++
                return emptyList()
            }
        }
        val vm = HomeViewModel(
            counting,
            com.darshan.payflow.domain.usecase.ReconcilePendingPaymentsUseCase(
                counting,
                com.darshan.payflow.domain.usecase.PollPaymentStatusUseCase(
                    com.darshan.payflow.domain.usecase.ConfirmPaymentUseCase(counting, psp, com.darshan.payflow.testutil.fixedClock),
                    com.darshan.payflow.core.BackoffPolicy(initialDelayMillis = 10, maxDelayMillis = 100, multiplier = 2.0, jitterFactor = 0.0, maxAttempts = 2),
                    kotlin.random.Random(1),
                ),
                com.darshan.payflow.testutil.fixedClock,
            ),
            TestDispatcherProvider(dispatcher),
        )
        advanceUntilIdle()
        val before = calls
        repeat(1_000) { vm.refresh() }
        advanceUntilIdle()
        assertThat(calls - before).isEqualTo(1)
    }

    @Test
    fun `refresh out of an error state reloads the list`() = runTest(dispatcher) {
        repo.failure = IOException("disk")
        val vm = vmWith()
        vm.uiState.test {
            awaitItem()
            assertThat(awaitItem()).isInstanceOf(HomeUiState.Error::class.java)
            repo.failure = null
            vm.refresh()
            assertThat(awaitItem()).isEqualTo(HomeUiState.Loading)
            assertThat(awaitItem()).isEqualTo(HomeUiState.Empty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Hide amounts

    @Test
    fun `hiding amounts is stored, so it survives the app being closed`() = runTest(dispatcher) {
        val prefs = InMemoryUiPreferences()
        val vm = vmWith(prefs)
        vm.amountsHidden.test {
            assertThat(awaitItem()).isFalse()
            vm.toggleAmountsHidden()
            assertThat(awaitItem()).isTrue()
            assertThat(prefs.settings.value.amountsHidden).isTrue()
            vm.toggleAmountsHidden()
            assertThat(awaitItem()).isFalse()
            assertThat(prefs.settings.value.amountsHidden).isFalse()
        }
    }

    @Test
    fun `a new ViewModel starts hidden when the setting is on, from its first value`() = runTest(dispatcher) {
        val vm = vmWith(InMemoryUiPreferences(UiSettings(amountsHidden = true)))
        assertThat(vm.amountsHidden.value).isTrue()
    }

    @Test
    fun `amounts stay masked until the stored setting has been read, so a hidden amount can't flash on launch`() = runTest(dispatcher) {
        val loaded = MutableStateFlow(false)
        val settings = MutableStateFlow(UiSettings())
        val prefs = object : UiPreferences by InMemoryUiPreferences() {
            override val settings = settings
            override val loaded = loaded
        }
        val vm = vmWith(prefs)
        assertThat(vm.amountsHidden.value).isTrue()
        vm.amountsHidden.test {
            assertThat(awaitItem()).isTrue()
            settings.value = UiSettings(amountsHidden = false)
            loaded.value = true
            runCurrent()
            assertThat(expectMostRecentItem()).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the entrance memory lives in the ViewModel, so it outlasts the screen`() = runTest(dispatcher) {
        val vm = vmWith()
        vm.entranceMemory.lastShownSentPaise = 500
        assertThat(vm.entranceMemory.lastShownSentPaise).isEqualTo(500)
    }
}
