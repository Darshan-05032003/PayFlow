package com.darshan.payflow.ui.pay

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.darshan.payflow.data.psp.FakeOutcome
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.data.upi.UpiLaunchResult
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.usecase.ConfirmPaymentUseCase
import com.darshan.payflow.domain.usecase.PayViaUpiIntentUseCase
import com.darshan.payflow.domain.usecase.MarkPaymentManuallyUseCase
import com.darshan.payflow.domain.usecase.RecordUpiAppResultUseCase
import com.darshan.payflow.data.settings.InMemoryPaymentModeRepository
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.FakeUpiAppLauncher
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
class PayViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val repo = FakePaymentRepository()
    private val launcher = FakeUpiAppLauncher()
    private var guard: com.darshan.payflow.data.security.PaymentGuard = com.darshan.payflow.data.security.AllowAllPaymentGuard()
    private val modes = InMemoryPaymentModeRepository(PaymentMode.SANDBOX)
    private var ids = 0

    private fun vm(psp: FakePsp = FakePsp(fixedClock), handle: SavedStateHandle = SavedStateHandle()) = PayViewModel(
        payViaUpiIntent = PayViaUpiIntentUseCase(repo, psp, fixedClock) { "pay-${++ids}" },
        confirmPayment = ConfirmPaymentUseCase(repo, psp, fixedClock),
        recordUpiAppResult = RecordUpiAppResultUseCase(repo),
        markPaymentManually = MarkPaymentManuallyUseCase(repo),
        verifyVpa = com.darshan.payflow.domain.usecase.VerifyVpaUseCase(psp),
        pollPaymentStatus = com.darshan.payflow.domain.usecase.PollPaymentStatusUseCase(
            ConfirmPaymentUseCase(repo, psp, fixedClock),
            com.darshan.payflow.core.BackoffPolicy(initialDelayMillis = 1_000, maxDelayMillis = 4_000, multiplier = 2.0, jitterFactor = 0.0, maxAttempts = 3),
            kotlin.random.Random(1),
        ),
        paymentGuard = guard,
        paymentModes = modes,
        upiAppLauncher = launcher,
        dispatchers = TestDispatcherProvider(dispatcher),
        savedState = handle,
    )

    private fun PayViewModel.fillValidForm() {
        onVpaChange("alice@okbank"); onNameChange("Alice"); onAmountChange("123.45"); onNoteChange("Lunch")
    }

    @Test
    fun `form validation blocks submit and surfaces field errors`() = runTest(dispatcher) {
        val vm = vm()
        vm.submit()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.errors.vpa).isNotNull()
        assertThat(s.errors.amount).isNotNull()
        assertThat(s.phase).isEqualTo(PayPhase.Idle)
        vm.onVpaChange("alice"); vm.onAmountChange("0")
        vm.submit(); advanceUntilIdle()
        assertThat(vm.uiState.value.errors.vpa).isEqualTo(PayFieldError.INVALID_VPA)
        assertThat(vm.uiState.value.errors.amount).isEqualTo(PayFieldError.INVALID_AMOUNT)
        vm.onAmountChange("100001")
        vm.submit(); advanceUntilIdle()
        assertThat(vm.uiState.value.errors.amount).isEqualTo(PayFieldError.AMOUNT_TOO_LARGE)
        assertThat(repo.getById("pay-1")).isNull()
    }

    @Test
    fun `valid submit creates a pending payment and emits a launch event`() = runTest(dispatcher) {
        val vm = vm()
        vm.fillValidForm()
        vm.events.test {
            vm.submit()
            val event = awaitItem() as PayEvent.LaunchUpiApp
            assertThat(event.upiUri).contains("am=123.45")
            assertThat(event.upiUri).contains("pa=alice%40okbank")
        }
        assertThat(vm.uiState.value.phase).isEqualTo(PayPhase.AwaitingApp("pay-1"))
        assertThat(repo.getById("pay-1")!!.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    fun `rapid double tap creates exactly one payment`() = runTest(dispatcher) {
        val vm = vm()
        vm.fillValidForm()
        vm.submit(); vm.submit(); vm.submit()
        advanceUntilIdle()
        assertThat(repo.getById("pay-1")).isNotNull()
        assertThat(repo.getById("pay-2")).isNull()
    }

    @Test
    fun `no upi app installed is reported and nothing is created`() = runTest(dispatcher) {
        launcher.installed = false
        val vm = vm()
        vm.fillValidForm()
        vm.events.test {
            vm.submit()
            assertThat(awaitItem()).isEqualTo(PayEvent.NoUpiApp)
        }
        assertThat(repo.getById("pay-1")).isNull()
        assertThat(vm.uiState.value.phase).isEqualTo(PayPhase.Idle)
    }

    @Test
    fun `app result is confirmed through the psp and shown as done`() = runTest(dispatcher) {
        val vm = vm()
        vm.fillValidForm(); vm.submit(); advanceUntilIdle()
        vm.onUpiAppResult(UpiLaunchResult.Responded("txnId=A1&Status=SUCCESS&txnRef=${repo.getById("pay-1")!!.txnRef}"))
        advanceUntilIdle()
        val phase = vm.uiState.value.phase as PayPhase.Done
        assertThat(phase.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(phase.paymentId).isEqualTo("pay-1")
    }

    @Test
    fun `spoofed app success with a failing psp shows failed`() = runTest(dispatcher) {
        val vm = vm(FakePsp(fixedClock, FakePspConfig(outcome = FakeOutcome.FAILED)))
        vm.fillValidForm(); vm.submit(); advanceUntilIdle()
        vm.onUpiAppResult(UpiLaunchResult.Responded("Status=SUCCESS&txnRef=${repo.getById("pay-1")!!.txnRef}&ApprovalRefNo=1"))
        advanceUntilIdle()
        assertThat((vm.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.FAILED)
    }

    @Test
    fun `cancelled app result still asks the psp and a pending answer is polled to its real outcome`() = runTest(dispatcher) {
        val psp = FakePsp(fixedClock, FakePspConfig(pendingPolls = 2))
        val vm = vm(psp)
        vm.fillValidForm(); vm.submit(); advanceUntilIdle()
        vm.onUpiAppResult(UpiLaunchResult.Cancelled); dispatcher.scheduler.runCurrent()
        assertThat((vm.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.PENDING)
        assertThat(vm.uiState.value.polling).isTrue()
        advanceUntilIdle() // virtual backoff elapses, the poller asks again
        assertThat((vm.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(psp.statusCalls).isEqualTo(3) // confirm (pending) + poll (pending) + poll (success)
    }

    @Test
    fun `in flight payment survives process death and the late app result is still confirmed`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val psp = FakePsp(fixedClock)
        val vm1 = vm(psp, handle)
        vm1.fillValidForm(); vm1.submit(); advanceUntilIdle()
        assertThat(handle.get<String>(PayViewModel.KEY_IN_FLIGHT)).isEqualTo("pay-1")

        val vm2 = vm(psp, handle)
        assertThat(vm2.uiState.value.phase).isEqualTo(PayPhase.AwaitingApp("pay-1"))
        assertThat(vm2.uiState.value.vpa).isEqualTo("alice@okbank")
        vm2.onUpiAppResult(UpiLaunchResult.Responded("Status=SUCCESS&txnRef=${repo.getById("pay-1")!!.txnRef}")); advanceUntilIdle()
        assertThat((vm2.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(handle.get<String>(PayViewModel.KEY_IN_FLIGHT)).isNull()
    }

    @Test
    fun `unexpected app result while idle is ignored`() = runTest(dispatcher) {
        val vm = vm()
        vm.onUpiAppResult(UpiLaunchResult.Responded("Status=SUCCESS&txnRef=X")); advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isEqualTo(PayPhase.Idle)
    }

    @Test
    fun `reset returns to an empty form`() = runTest(dispatcher) {
        val vm = vm()
        vm.fillValidForm(); vm.submit(); advanceUntilIdle()
        vm.onUpiAppResult(UpiLaunchResult.Cancelled); advanceUntilIdle()
        vm.reset()
        assertThat(vm.uiState.value).isEqualTo(PayUiState(paymentMode = PaymentMode.SANDBOX))
    }

    @Test
    fun `amount text accepts only sane input`() = runTest(dispatcher) {
        val vm = vm()
        vm.onAmountChange("12.345")
        assertThat(vm.uiState.value.amountText).isEqualTo("")
        vm.onAmountChange("abc")
        assertThat(vm.uiState.value.amountText).isEqualTo("")
        vm.onAmountChange("12.34")
        assertThat(vm.uiState.value.amountText).isEqualTo("12.34")
        vm.onAmountChange("1234567890123")
        assertThat(vm.uiState.value.amountText).isEqualTo("12.34")
    }

    @Test
    fun `qr route arguments prefill the form, lock the payee and record the QR method`() = runTest(dispatcher) {
        val handle = SavedStateHandle(
            mapOf(
                PayViewModel.ARG_PREFILL_VPA to "shop@okbank", PayViewModel.ARG_PREFILL_NAME to "Chai Point",
                PayViewModel.ARG_PREFILL_AMOUNT to 4_500L, PayViewModel.ARG_PREFILL_NOTE to "Table 4", PayViewModel.ARG_SOURCE to "qr",
            ),
        )
        val vm = vm(handle = handle)
        val s = vm.uiState.value
        assertThat(s.vpa).isEqualTo("shop@okbank")
        assertThat(s.name).isEqualTo("Chai Point")
        assertThat(s.amountText).isEqualTo("45.00")
        assertThat(s.note).isEqualTo("Table 4")
        assertThat(s.payeeLocked).isTrue()
        vm.onVpaChange("mallory@evil") // locked: ignored
        assertThat(vm.uiState.value.vpa).isEqualTo("shop@okbank")
        vm.submit(); advanceUntilIdle()
        assertThat(repo.getById("pay-1")!!.method).isEqualTo(com.darshan.payflow.domain.model.PaymentMethod.QR_SCAN)
        assertThat(repo.getById("pay-1")!!.amountPaise).isEqualTo(4_500)
    }

    @Test
    fun `qr without amount leaves the amount editable`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf(PayViewModel.ARG_PREFILL_VPA to "shop@okbank", PayViewModel.ARG_SOURCE to "qr"))
        val vm = vm(handle = handle)
        assertThat(vm.uiState.value.amountText).isEmpty()
        assertThat(vm.uiState.value.amountLocked).isFalse()
        vm.onAmountChange("10"); vm.onNameChange("x")
        assertThat(vm.uiState.value.amountText).isEqualTo("10")
        assertThat(vm.uiState.value.name).isEqualTo("shop@okbank") // name locked with the payee
    }

    // --- feature 4: VPA verification ---

    @Test
    fun `typing a vpa verifies it after a debounce and fills the registered payee name`() = runTest(dispatcher) {
        val psp = FakePsp(fixedClock)
        val vm = vm(psp)
        vm.onVpaChange("alice@okb"); vm.onVpaChange("alice@okbank")
        assertThat(vm.uiState.value.verification).isEqualTo(VpaVerificationState.Idle)
        dispatcher.scheduler.advanceTimeBy(PayViewModel.VERIFY_DEBOUNCE_MS + 1); dispatcher.scheduler.runCurrent()
        assertThat(vm.uiState.value.verification).isEqualTo(VpaVerificationState.Verified("Alice Kumar"))
        assertThat(vm.uiState.value.name).isEqualTo("Alice Kumar")
        assertThat(psp.verifyCalls).isEqualTo(1) // only the final value was verified
    }

    @Test
    fun `a vpa the psp does not know blocks submit`() = runTest(dispatcher) {
        val vm = vm()
        vm.onVpaChange("unknown@okbank"); vm.onNameChange("Someone"); vm.onAmountChange("10")
        dispatcher.scheduler.advanceTimeBy(PayViewModel.VERIFY_DEBOUNCE_MS + 1); dispatcher.scheduler.runCurrent()
        assertThat(vm.uiState.value.verification).isEqualTo(VpaVerificationState.NotFound)
        vm.submit(); advanceUntilIdle()
        assertThat(vm.uiState.value.errors.vpa).isEqualTo(PayFieldError.VPA_NOT_FOUND)
        assertThat(repo.getById("pay-1")).isNull()
    }

    @Test
    fun `verification unavailable allows paying with the typed name and a warning`() = runTest(dispatcher) {
        val psp = FakePsp(fixedClock, FakePspConfig(timeoutRate = 1.0))
        val vm = vm(psp)
        vm.onVpaChange("alice@okbank"); vm.onNameChange("Alice"); vm.onAmountChange("10")
        dispatcher.scheduler.advanceTimeBy(PayViewModel.VERIFY_DEBOUNCE_MS + 1); dispatcher.scheduler.runCurrent()
        assertThat(vm.uiState.value.verification).isEqualTo(VpaVerificationState.Unavailable)
        assertThat(vm.uiState.value.name).isEqualTo("Alice")
    }

    @Test
    fun `submit verifies first when the user never paused typing`() = runTest(dispatcher) {
        val vm = vm()
        vm.onVpaChange("alice@okbank"); vm.onAmountChange("10")
        vm.submit(); advanceUntilIdle()
        assertThat(vm.uiState.value.name).isEqualTo("Alice Kumar")
        assertThat(repo.getById("pay-1")!!.payeeName).isEqualTo("Alice Kumar")
    }

    @Test
    fun `a qr payee is verified on open and the registered name replaces the qr text`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf(PayViewModel.ARG_PREFILL_VPA to "shop@okbank", PayViewModel.ARG_PREFILL_NAME to "Government Refund Desk", PayViewModel.ARG_SOURCE to "qr"))
        val vm = vm(handle = handle)
        advanceUntilIdle()
        assertThat(vm.uiState.value.verification).isEqualTo(VpaVerificationState.Verified("Chai Point"))
        assertThat(vm.uiState.value.name).isEqualTo("Chai Point")
    }

    // --- feature 6: idempotency key + auto polling ---

    @Test
    fun `an idempotency key is created once per attempt, saved before submit and reused on retry`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val down = FakePsp(fixedClock, FakePspConfig(serverErrorRate = 1.0))
        val vm = vm(down, handle)
        vm.fillValidForm(); vm.submit(); advanceUntilIdle()
        val key = repo.getById("pay-1")!!.idempotencyKey
        assertThat(key).isNotNull() // written on the row before the PSP was called
        assertThat((vm.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.FAILED)
        assertThat(handle.get<String>(PayViewModel.KEY_IDEMPOTENCY)).isNull() // a failed attempt's key is spent
        // New attempt after a failure gets a fresh key
        vm.reset(); vm.fillValidForm(); vm.submit(); advanceUntilIdle()
        assertThat(repo.getById("pay-2")!!.idempotencyKey).isNotEqualTo(key)
    }

    @Test
    fun `a submit that dies before the psp answers is deduped after process death`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val psp = FakePsp(fixedClock)
        val vm1 = vm(psp, handle)
        vm1.fillValidForm(); vm1.submit(); advanceUntilIdle()
        assertThat(handle.get<String>(PayViewModel.KEY_IN_FLIGHT)).isEqualTo("pay-1")
        // Simulate the process dying right after the row was stored: a new VM sees the in-flight id and the key; a second submit is a no-op.
        val vm2 = vm(psp, handle)
        vm2.submit(); advanceUntilIdle()
        assertThat(repo.getById("pay-2")).isNull()
        assertThat(psp.initiateCalls).isEqualTo(1)
    }

    @Test
    fun `a pending result is polled automatically with backoff until the psp settles`() = runTest(dispatcher) {
        val psp = FakePsp(fixedClock, FakePspConfig(pendingPolls = 3))
        val vm = vm(psp)
        vm.fillValidForm(); vm.submit(); advanceUntilIdle()
        vm.onUpiAppResult(UpiLaunchResult.Responded("Status=SUBMITTED")); dispatcher.scheduler.runCurrent()
        // First confirm: pending (poll 1). The poller then waits 1 s, 2 s before the 4th status call succeeds.
        assertThat((vm.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.PENDING)
        assertThat(vm.uiState.value.polling).isTrue()
        dispatcher.scheduler.advanceTimeBy(1_001); dispatcher.scheduler.runCurrent()
        assertThat((vm.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.PENDING)
        dispatcher.scheduler.advanceTimeBy(2_001); dispatcher.scheduler.runCurrent()
        assertThat((vm.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(vm.uiState.value.polling).isFalse()
        assertThat(psp.statusCalls).isEqualTo(4)
    }

    // --- feature 7: device checks gate the payment ---

    @Test
    fun `a blocked device cannot pay and a warning still pays`() = runTest(dispatcher) {
        guard = object : com.darshan.payflow.data.security.PaymentGuard {
            override suspend fun evaluate(paymentId: String, amountPaise: Long, payeeVpa: String) =
                com.darshan.payflow.data.security.GuardDecision.Block(listOf(com.darshan.payflow.data.security.GuardReason.ROOTED))
        }
        val vm = vm()
        vm.fillValidForm()
        vm.events.test {
            vm.submit()
            assertThat(awaitItem()).isEqualTo(PayEvent.Blocked(listOf(com.darshan.payflow.data.security.GuardReason.ROOTED)))
        }
        assertThat(vm.uiState.value.phase).isEqualTo(PayPhase.Idle)
        assertThat(repo.getById("pay-1")).isNull()

        guard = object : com.darshan.payflow.data.security.PaymentGuard {
            override suspend fun evaluate(paymentId: String, amountPaise: Long, payeeVpa: String) =
                com.darshan.payflow.data.security.GuardDecision.Warn(listOf(com.darshan.payflow.data.security.GuardReason.DEVICE_NOT_BOUND))
        }
        val vm2 = vm()
        vm2.fillValidForm()
        vm2.events.test {
            vm2.submit()
            assertThat(awaitItem()).isInstanceOf(PayEvent.Warning::class.java)
            assertThat(awaitItem()).isInstanceOf(PayEvent.LaunchUpiApp::class.java)
        }
        assertThat(repo.getById("pay-1")).isNotNull()
    }

    // --- UPI-apps mode: real apps, app-reported results ---

    private fun realVm(handle: SavedStateHandle = SavedStateHandle()): PayViewModel {
        modes.setBlocking(PaymentMode.UPI_APPS)
        return vm(handle = handle)
    }

    private fun InMemoryPaymentModeRepository.setBlocking(mode: PaymentMode) = kotlinx.coroutines.runBlocking { set(mode) }

    @Test
    fun `upi apps mode skips directory verification and shows PayFlow's own app chooser before anything is stored`() = runTest(dispatcher) {
        val psp = FakePsp(fixedClock)
        val vm = realVm(); advanceUntilIdle()
        assertThat(vm.uiState.value.paymentMode).isEqualTo(PaymentMode.UPI_APPS)
        vm.onVpaChange("someone@okaxis"); vm.onAmountChange("250.5") // no name: optional in this mode
        dispatcher.scheduler.advanceTimeBy(PayViewModel.VERIFY_DEBOUNCE_MS + 1); advanceUntilIdle()
        assertThat(vm.uiState.value.verification).isEqualTo(VpaVerificationState.Skipped)
        assertThat(psp.verifyCalls).isEqualTo(0)
        vm.submit(); advanceUntilIdle()
        val phase = vm.uiState.value.phase as PayPhase.ChoosingApp
        assertThat(phase.apps.map { it.label }).containsExactly("GPay", "PhonePe").inOrder()
        assertThat(repo.getById("pay-1")).isNull() // nothing is created until an app is picked
        vm.dismissChooser()
        assertThat(vm.uiState.value.phase).isEqualTo(PayPhase.Idle)
        assertThat(repo.getById("pay-1")).isNull()
    }

    @Test
    fun `picking an app stores a pending payment for that app and launches it directly, without the psp`() = runTest(dispatcher) {
        val psp = FakePsp(fixedClock)
        modes.setBlocking(PaymentMode.UPI_APPS)
        val vm = vm(psp); advanceUntilIdle()
        vm.onVpaChange("someone@okaxis"); vm.onAmountChange("250.50")
        vm.submit(); advanceUntilIdle()
        vm.events.test {
            vm.onAppChosen("com.example.phonepe")
            val event = awaitItem() as PayEvent.LaunchUpiApp
            assertThat(event.packageName).isEqualTo("com.example.phonepe")
            assertThat(event.upiUri).contains("pa=someone%40okaxis")
            assertThat(event.upiUri).contains("am=250.50")
            assertThat(event.upiUri).contains("pn=someone") // name falls back to the handle
        }
        val stored = repo.getById("pay-1")!!
        assertThat(stored.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(stored.handlerPackage).isEqualTo("com.example.phonepe")
        assertThat(psp.initiateCalls).isEqualTo(0)
        assertThat(vm.uiState.value.phase).isEqualTo(PayPhase.AwaitingApp("pay-1"))
        // An app that was not in the sheet cannot be picked.
        vm.onAppChosen("com.evil.app"); advanceUntilIdle()
        assertThat(repo.getById("pay-2")).isNull()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.launchedReal(pkg: String = "com.example.gpay"): PayViewModel {
        val vm = realVm(); advanceUntilIdle()
        vm.onVpaChange("someone@okaxis"); vm.onNameChange("Someone"); vm.onAmountChange("10")
        vm.submit(); advanceUntilIdle()
        vm.onAppChosen(pkg); advanceUntilIdle()
        return vm
    }

    @Test
    fun `the app's success is shown as reported by that app, never as verified`() = runTest(dispatcher) {
        val vm = launchedReal()
        val txnRef = repo.getById("pay-1")!!.txnRef
        vm.onUpiAppResult(UpiLaunchResult.Responded("txnId=GP123&responseCode=00&Status=SUCCESS&txnRef=$txnRef&ApprovalRefNo=A1")); advanceUntilIdle()
        val done = vm.uiState.value.phase as PayPhase.Done
        assertThat(done.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(done.payment!!.statusSource).isEqualTo(StatusSource.UPI_APP)
        assertThat(done.payment!!.upiTxnId).isEqualTo("GP123")
        assertThat(done.payment!!.pspTxnId).isNull()
        assertThat(done.handlerLabel).isEqualTo("GPay")
        assertThat(PayViewModel.receiptText(done.payment!!, done.handlerLabel)).contains("Status: Success (reported by GPay)")
        assertThat(PayViewModel.receiptText(done.payment!!, done.handlerLabel)).doesNotContain("verified")
        vm.events.test {
            vm.shareReceipt()
            assertThat((awaitItem() as PayEvent.ShareReceipt).text).contains("someone@okaxis")
        }
    }

    @Test
    fun `a spoofed response for another reference is ignored and the payment stays pending`() = runTest(dispatcher) {
        val vm = launchedReal()
        vm.onUpiAppResult(UpiLaunchResult.Responded("Status=SUCCESS&txnRef=OTHERREF&ApprovalRefNo=1")); advanceUntilIdle()
        val done = vm.uiState.value.phase as PayPhase.Done
        assertThat(done.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(done.reason).isEqualTo(PayViewModel.MISMATCH_REASON)
        assertThat(repo.getById("pay-1")!!.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    fun `coming back without a result leaves the payment pending and the user can mark it by hand`() = runTest(dispatcher) {
        val vm = launchedReal("com.example.phonepe")
        vm.onUpiAppResult(UpiLaunchResult.Cancelled); advanceUntilIdle()
        val pending = vm.uiState.value.phase as PayPhase.Done
        assertThat(pending.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(pending.payment!!.canBeMarkedManually).isTrue()
        assertThat(pending.handlerLabel).isEqualTo("PhonePe")
        assertThat(vm.uiState.value.polling).isFalse() // no PSP to poll
        vm.checkStatus(); advanceUntilIdle() // no PSP to ask either: a no-op
        assertThat(vm.uiState.value.phase).isEqualTo(pending)
        vm.markManually(paid = true); advanceUntilIdle()
        val marked = vm.uiState.value.phase as PayPhase.Done
        assertThat(marked.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(marked.payment!!.statusSource).isEqualTo(StatusSource.USER)
        // Marking again is refused by the use case; the phase keeps the settled payment.
        vm.markManually(paid = false); advanceUntilIdle()
        assertThat((vm.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.SUCCESS)
    }

    @Test
    fun `a failure reported by the app is recorded with its code and pay again keeps the payee`() = runTest(dispatcher) {
        val vm = launchedReal()
        val txnRef = repo.getById("pay-1")!!.txnRef
        vm.onUpiAppResult(UpiLaunchResult.Responded("Status=FAILURE&responseCode=ZA&txnRef=$txnRef")); advanceUntilIdle()
        val done = vm.uiState.value.phase as PayPhase.Done
        assertThat(done.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(done.reason).isEqualTo("Reported as failed by GPay (code ZA)")
        vm.payAgain()
        val s = vm.uiState.value
        assertThat(s.phase).isEqualTo(PayPhase.Idle)
        assertThat(s.vpa).isEqualTo("someone@okaxis")
        assertThat(s.amountText).isEqualTo("10")
        vm.submit(); advanceUntilIdle(); vm.onAppChosen("com.example.gpay"); advanceUntilIdle()
        assertThat(repo.getById("pay-2")!!.idempotencyKey).isNotEqualTo(repo.getById("pay-1")!!.idempotencyKey)
    }

    @Test
    fun `without a visible app list the system chooser is used, and with no upi app nothing is created`() = runTest(dispatcher) {
        launcher.apps = emptyList()
        val vm = realVm(); advanceUntilIdle()
        vm.onVpaChange("someone@okaxis"); vm.onAmountChange("10")
        vm.events.test {
            vm.submit()
            val event = awaitItem() as PayEvent.LaunchUpiApp
            assertThat(event.packageName).isNull()
        }
        assertThat(repo.getById("pay-1")!!.handlerPackage).isNull()
        assertThat(repo.getById("pay-1")!!.status).isEqualTo(PaymentStatus.PENDING)

        launcher.installed = false
        val vm2 = realVm(); advanceUntilIdle()
        vm2.onVpaChange("someone@okaxis"); vm2.onAmountChange("10")
        vm2.events.test {
            vm2.submit()
            assertThat(awaitItem()).isEqualTo(PayEvent.NoUpiApp)
        }
        assertThat(repo.getById("pay-2")).isNull()
    }

    @Test
    fun `an incoming link locks payee and amount and records the deep link method`() = runTest(dispatcher) {
        val handle = SavedStateHandle(
            mapOf(PayViewModel.ARG_PREFILL_VPA to "test@upi", PayViewModel.ARG_PREFILL_NAME to "Test", PayViewModel.ARG_PREFILL_AMOUNT to 100L, PayViewModel.ARG_SOURCE to PayViewModel.SOURCE_LINK),
        )
        val vm = realVm(handle); advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.payeeLocked).isTrue()
        assertThat(s.amountLocked).isTrue()
        assertThat(s.amountText).isEqualTo("1.00")
        vm.onAmountChange("999"); vm.onVpaChange("mallory@evil")
        assertThat(vm.uiState.value.amountText).isEqualTo("1.00")
        assertThat(vm.uiState.value.vpa).isEqualTo("test@upi")
        vm.submit(); advanceUntilIdle(); vm.onAppChosen("com.example.gpay"); advanceUntilIdle()
        assertThat(repo.getById("pay-1")!!.method).isEqualTo(com.darshan.payflow.domain.model.PaymentMethod.DEEP_LINK)
    }

    @Test
    fun `a recent payee prefill is editable and verified in sandbox mode`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf(PayViewModel.ARG_PREFILL_VPA to "alice@okbank", PayViewModel.ARG_PREFILL_NAME to "Alice", PayViewModel.ARG_SOURCE to PayViewModel.SOURCE_PAYEE))
        val vm = vm(handle = handle); advanceUntilIdle()
        assertThat(vm.uiState.value.payeeLocked).isFalse()
        assertThat(vm.uiState.value.verification).isEqualTo(VpaVerificationState.Verified("Alice Kumar"))
        vm.onVpaChange("bob@upi")
        assertThat(vm.uiState.value.vpa).isEqualTo("bob@upi")
    }
}
