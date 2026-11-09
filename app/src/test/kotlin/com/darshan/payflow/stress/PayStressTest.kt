package com.darshan.payflow.stress

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.settings.InMemoryPaymentModeRepository
import com.darshan.payflow.data.upi.UpiLaunchResult
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.domain.usecase.ConfirmPaymentUseCase
import com.darshan.payflow.domain.usecase.MarkPaymentManuallyUseCase
import com.darshan.payflow.domain.usecase.PayViaUpiIntentUseCase
import com.darshan.payflow.domain.usecase.RecordUpiAppResultUseCase
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.FakeUpiAppLauncher
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.pay.AmountInput
import com.darshan.payflow.ui.pay.AmountKey
import com.darshan.payflow.ui.pay.AmountPress
import com.darshan.payflow.ui.pay.PayPhase
import com.darshan.payflow.ui.pay.PayViewModel
import com.darshan.payflow.ui.pay.ReceiptContent
import com.darshan.payflow.ui.pay.ReceiptImage
import com.darshan.payflow.ui.pay.SwipeSignal
import com.darshan.payflow.ui.pay.SwipeToPayHolder
import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** JVM numbers: time on this laptop's JVM, not frame times on a phone. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PayStressTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)

    private fun vm(repo: FakePaymentRepository, psp: FakePsp, handle: SavedStateHandle = SavedStateHandle(), ids: () -> String) = PayViewModel(
        PayViaUpiIntentUseCase(repo, psp, fixedClock, idGenerator = ids), ConfirmPaymentUseCase(repo, psp, fixedClock), RecordUpiAppResultUseCase(repo),
        MarkPaymentManuallyUseCase(repo), com.darshan.payflow.domain.usecase.VerifyVpaUseCase(psp),
        com.darshan.payflow.domain.usecase.PollPaymentStatusUseCase(ConfirmPaymentUseCase(repo, psp, fixedClock), com.darshan.payflow.core.BackoffPolicy.NONE, Random(1)),
        com.darshan.payflow.data.security.AllowAllPaymentGuard(), InMemoryPaymentModeRepository(PaymentMode.SANDBOX), FakeUpiAppLauncher(),
        TestDispatcherProvider(dispatcher), handle,
    )

    @Test
    fun `1,000 swipes, 1,000 more after the launch, and a process death in between make one payment`() = runTest(dispatcher) {
        val repo = FakePaymentRepository()
        val psp = FakePsp(fixedClock)
        var n = 0
        val handle = SavedStateHandle()
        val first = vm(repo, psp, handle) { "p${++n}" }
        first.onVpaChange("alice@okbank"); first.onAmountChange("100"); advanceUntilIdle()
        val ms = measureTimeMillis {
            repeat(1_000) { first.submit() }
            advanceUntilIdle()
            repeat(1_000) { first.submit() }
            advanceUntilIdle()
        }
        val second = vm(repo, psp, handle) { "p${++n}" }
        advanceUntilIdle()
        repeat(1_000) { second.submit() }
        advanceUntilIdle()
        assertThat(n).isEqualTo(1)
        assertThat(psp.initiateCalls).isEqualTo(1)
        assertThat(second.uiState.value.phase).isInstanceOf(PayPhase.AwaitingApp::class.java)
        println("STRESS [pay-1k-swipes] 3,000 submits incl. a process death: payments=$n psp initiate calls=${psp.initiateCalls} in ${ms}ms (JVM)")
    }

    @Test
    fun `1,000 sequential payments through the state holder end clean`() = runTest(dispatcher) {
        val repo = FakePaymentRepository()
        val psp = FakePsp(fixedClock)
        var n = 0
        val vm = vm(repo, psp) { "p${++n}" }
        vm.onVpaChange("alice@okbank"); vm.onAmountChange("1"); advanceUntilIdle()
        val rt = Runtime.getRuntime()
        System.gc(); val before = rt.totalMemory() - rt.freeMemory()
        val ms = measureTimeMillis {
            repeat(1_000) {
                vm.submit(); advanceUntilIdle()
                vm.onUpiAppResult(UpiLaunchResult.Responded("Status=SUCCESS")); advanceUntilIdle()
                assertThat((vm.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.SUCCESS)
                vm.payAgain()
            }
        }
        System.gc(); val after = rt.totalMemory() - rt.freeMemory()
        assertThat(n).isEqualTo(1_000)
        assertThat(vm.uiState.value.polling).isFalse()
        // Nothing left scheduled: a leaked poller or animation timer would still have work queued.
        val start = dispatcher.scheduler.currentTime
        advanceUntilIdle()
        val idle = dispatcher.scheduler.currentTime == start
        assertThat(idle).isTrue()
        println("STRESS [pay-1k-sequential] 1,000 payments in ${ms}ms (${ms * 1000 / 1000}us each, JVM); heap delta ${(after - before) / 1024} KB incl. 1,000 stored rows; nothing left scheduled=$idle")
    }

    @Test
    fun `100,000 random keypad presses never leave the limit`() {
        val random = Random(42)
        var text = ""
        var refused = 0
        var maxPaise = 0L
        val ms = measureTimeMillis {
            repeat(100_000) {
                val key = AmountKey.entries[random.nextInt(AmountKey.entries.size)]
                when (val r = AmountInput.press(text, key)) {
                    is AmountPress.Accepted -> text = r.text
                    is AmountPress.Refused -> refused++
                }
                val paise = AmountInput.paiseOf(text) ?: 0L
                assertThat(paise).isAtMost(Payment.MAX_AMOUNT_PAISE)
                if (paise > maxPaise) maxPaise = paise
            }
        }
        println("STRESS [pay-keypad-100k] 100,000 random presses in ${ms}ms (JVM), refused=$refused, highest=₹${maxPaise / 100}.${(maxPaise % 100).toString().padStart(2, '0')}, never over ₹1,00,000.00")
    }

    @Test
    fun `1,000 gestures on the swipe holder commit exactly once`() {
        val holder = SwipeToPayHolder()
        val random = Random(7)
        var commits = 0
        var signals = 0
        val ms = measureTimeMillis {
            repeat(1_000) {
                holder.begin(enabled = true)
                repeat(20) { signals += holder.dragBy(random.nextFloat() * 0.12f - 0.02f).size }
                val end = if (random.nextBoolean()) holder.release() else holder.cancel()
                commits += end.count { it == SwipeSignal.Committed }
                if (holder.phase.name == "SpringingBack") holder.settled()
            }
        }
        assertThat(commits).isEqualTo(1)
        println("STRESS [pay-swipe-1k] 1,000 random drags (half cancelled) -> commits=$commits, $signals haptic signals, ${ms}ms (JVM)")
    }

    @Test
    fun `receipt image renders the longest names quickly and at a bounded size`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val longName = "Shri Venkateswara Lakshmi Narasimha Enterprises and Sons Private Limited Bengaluru ".repeat(4)
        val payment = testPayment("p1", status = PaymentStatus.SUCCESS, payeeName = longName, amountPaise = Payment.MAX_AMOUNT_PAISE)
            .copy(statusSource = StatusSource.UPI_APP, handlerPackage = "com.example.gpay", upiTxnId = "U".repeat(35))
        val receipt = ReceiptContent.from(payment, "GPay")
        ReceiptImage.render(receipt, context) // warm-up
        var height = 0
        val ms = measureTimeMillis { repeat(20) { height = ReceiptImage.render(receipt, context).height } }
        assertThat(height).isLessThan(2_400)
        println("STRESS [pay-receipt-image] 20 renders of a 336-char name in ${ms}ms (${ms / 20}ms each, JVM native graphics), 1080x$height px")
    }
}
