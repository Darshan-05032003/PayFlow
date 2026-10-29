package com.darshan.payflow.integration

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.core.DefaultDispatcherProvider
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.psp.FakeOutcome
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.data.upi.UpiLaunchResult
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.usecase.ConfirmPaymentUseCase
import com.darshan.payflow.domain.usecase.PayViaUpiIntentUseCase
import com.darshan.payflow.domain.usecase.VerifyVpaUseCase
import com.darshan.payflow.testutil.FakeUpiAppLauncher
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.testutil.pollerFor
import com.darshan.payflow.testutil.runOffMain
import com.darshan.payflow.testutil.waitUntilWithMainLooper
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.pay.PayCallbacks
import com.darshan.payflow.ui.pay.PayEvent
import com.darshan.payflow.ui.pay.PayPhase
import com.darshan.payflow.ui.pay.PayScreen
import com.darshan.payflow.ui.pay.PayViewModel
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

/** Room + FakePsp + use cases + PayViewModel + PayScreen, end to end on the JVM. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class UpiIntentFlowIntegrationTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var db: PayFlowDatabase
    private lateinit var repo: PaymentRepositoryImpl
    private val launcher = FakeUpiAppLauncher()
    private val events = Collections.synchronizedList(mutableListOf<PayEvent>())

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).allowMainThreadQueries().build()
        repo = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
    }

    @After
    fun tearDown() = db.close()

    private fun start(psp: FakePsp): PayViewModel {
        val vm = PayViewModel(
            PayViaUpiIntentUseCase(repo, psp, fixedClock), ConfirmPaymentUseCase(repo, psp, fixedClock), VerifyVpaUseCase(psp), pollerFor(repo, psp), com.darshan.payflow.data.security.AllowAllPaymentGuard(),
            launcher, DefaultDispatcherProvider(), SavedStateHandle(),
        )
        vm.events.onEach { events += it }.launchIn(CoroutineScope(Dispatchers.Unconfined))
        rule.setContent {
            PayFlowTheme {
                val state by vm.uiState.collectAsStateWithLifecycle()
                PayScreen(
                    state,
                    PayCallbacks(
                        onVpaChange = vm::onVpaChange, onNameChange = vm::onNameChange, onAmountChange = vm::onAmountChange,
                        onNoteChange = vm::onNoteChange, onSubmit = vm::submit, onCheckStatus = vm::checkStatus, onReset = vm::reset,
                    ),
                )
            }
        }
        rule.onNodeWithTag(PayFlowTestTags.PAY_VPA).performTextInput("alice@okbank")
        rule.onNodeWithTag(PayFlowTestTags.PAY_NAME).performTextInput("Alice")
        rule.onNodeWithTag(PayFlowTestTags.PAY_AMOUNT).performTextInput("1")
        rule.onNodeWithTag(PayFlowTestTags.PAY_SUBMIT).performClick()
        rule.waitUntilWithMainLooper { events.any { it is PayEvent.LaunchUpiApp } }
        return vm
    }

    @Test
    fun successfulIntentPaymentIsConfirmedByThePspAndStoredInRoom() {
        val psp = FakePsp(fixedClock)
        val vm = start(psp)
        val uri = (events.single() as PayEvent.LaunchUpiApp).upiUri
        assertThat(uri).startsWith("upi://pay?pa=alice%40okbank&pn=Alice%20Kumar&am=1.00&cu=INR&tr=") // PSP-registered name wins
        rule.onNodeWithText("Complete the payment in your UPI app").assertIsDisplayed()

        val txnRef = runOffMain { repo.observeRecent(1).first() }.single().txnRef
        vm.onUpiAppResult(UpiLaunchResult.Responded("txnId=APP1&responseCode=00&Status=SUCCESS&txnRef=$txnRef"))
        rule.waitUntilWithMainLooper { vm.uiState.value.phase is PayPhase.Done }
        rule.onNodeWithText("Payment successful").assertIsDisplayed()
        val stored = runOffMain { repo.getById((vm.uiState.value.phase as PayPhase.Done).paymentId)!! }
        assertThat(stored.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(stored.pspTxnId).isNotNull()
        assertThat(psp.statusCalls).isEqualTo(1)
    }

    @Test
    fun spoofedSuccessResponseIsRejectedWhenThePspSaysFailed() {
        val psp = FakePsp(fixedClock, FakePspConfig(outcome = FakeOutcome.FAILED))
        val vm = start(psp)
        vm.onUpiAppResult(UpiLaunchResult.Responded("Status=SUCCESS&txnRef=WHATEVER&ApprovalRefNo=999"))
        rule.waitUntilWithMainLooper { vm.uiState.value.phase is PayPhase.Done }
        rule.onNodeWithText("Payment failed").assertIsDisplayed()
        val stored = runOffMain { repo.getById((vm.uiState.value.phase as PayPhase.Done).paymentId)!! }
        assertThat(stored.status).isEqualTo(PaymentStatus.FAILED)
    }
}
