package com.darshan.payflow.ui.collect

import app.cash.turbine.test
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.usecase.CollectActionResult
import com.darshan.payflow.domain.usecase.RefreshCollectRequestsUseCase
import com.darshan.payflow.domain.usecase.RespondToCollectUseCase
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.FakeCollectRepository
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Collect requests are a fraud vector: someone asks you to "approve" and the money leaves. These are the attacks on the
 * ViewModel and the use case under it; the screen-level ones are in CollectScreenAttackTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectAttackTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)

    /** A clock the test can move, so a request can expire between showing and answering. */
    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MovableClock(FIXED_INSTANT)
    private val collects = FakeCollectRepository()
    private val payments = FakePaymentRepository(clock)
    private val psp = FakePsp(clock).also {
        it.simulateIncomingCollect("req-1", "tara@okhdfc", "Tara Book House", 4_500, "Order 7", ttlMillis = 120_000)
    }
    private val ids = AtomicInteger()

    private fun vm() = CollectViewModel(
        collects,
        RefreshCollectRequestsUseCase(collects, psp, clock),
        RespondToCollectUseCase(collects, payments, psp, clock) { "pay-${ids.incrementAndGet()}" },
        clock,
        TestDispatcherProvider(dispatcher),
    )

    private suspend fun paymentCount() = payments.observeHistory(HistoryFilter.ALL, "").first().size

    @Test
    fun `opening and refreshing never answers a request`() = runTest(dispatcher) {
        val vm = vm()
        repeat(20) { vm.refresh(); advanceUntilIdle() }
        assertThat(paymentCount()).isEqualTo(0)
        assertThat(psp.collectRequests["req-1"]!!.status).isEqualTo(CollectStatus.PENDING)
    }

    @Test
    fun `1,000 rapid approves create exactly one payment`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        repeat(1_000) { vm.approve("req-1") }
        advanceUntilIdle()
        repeat(1_000) { vm.approve("req-1") }
        advanceUntilIdle()
        assertThat(paymentCount()).isEqualTo(1)
        assertThat(ids.get()).isEqualTo(1)
        assertThat(psp.collectRequests["req-1"]!!.status).isEqualTo(CollectStatus.APPROVED)
    }

    @Test
    fun `approve and decline racing answer once`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.decline("req-1")
        repeat(100) { vm.approve("req-1") }
        advanceUntilIdle()
        assertThat(paymentCount()).isEqualTo(0)
        assertThat(psp.collectRequests["req-1"]!!.status).isEqualTo(CollectStatus.DECLINED)
    }

    @Test
    fun `a request that expired while on screen cannot be paid`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        clock.now = FIXED_INSTANT.plusSeconds(121)
        vm.events.test {
            vm.approve("req-1")
            advanceUntilIdle()
            val answer = (awaitItem() as CollectEvent.Answered).result
            assertThat(answer).isInstanceOf(CollectActionResult.Expired::class.java)
        }
        assertThat(paymentCount()).isEqualTo(0)
        assertThat(collects.getById("req-1")!!.status).isEqualTo(CollectStatus.EXPIRED)
    }

    @Test
    fun `an answered request's events say what happened, so haptics match the outcome`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.events.test {
            vm.approve("req-1")
            advanceUntilIdle()
            val result = (awaitItem() as CollectEvent.Answered).result
            assertThat(CollectFeedback.of(result)).isEqualTo(CollectFeedback.Paid)
        }
        assertThat(CollectFeedback.of(CollectActionResult.NotFound)).isEqualTo(CollectFeedback.Problem)
        assertThat(CollectFeedback.Paid.haptic).isEqualTo(com.darshan.payflow.ui.designsystem.effects.HapticEvent.Confirm)
        assertThat(CollectFeedback.Problem.haptic).isEqualTo(com.darshan.payflow.ui.designsystem.effects.HapticEvent.Reject)
    }
}
