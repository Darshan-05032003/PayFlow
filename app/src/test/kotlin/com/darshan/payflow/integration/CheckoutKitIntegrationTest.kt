package com.darshan.payflow.integration

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.checkoutkit.core.CheckoutConfig
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.PendingPolicy
import com.darshan.checkoutkit.core.retry.RetryPolicy
import com.darshan.checkoutkit.ui.CheckoutSheet
import com.darshan.checkoutkit.ui.CheckoutSheetOptions
import com.darshan.checkoutkit.ui.CheckoutSheetTestTags
import com.darshan.checkoutkit.ui.rememberCheckoutSheet
import com.darshan.payflow.data.checkout.CheckoutMerchant
import com.darshan.payflow.data.checkout.PayFlowPaymentGateway
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.psp.FakeOutcome
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.data.psp.PspClient
import com.darshan.payflow.data.psp.PspException
import com.darshan.payflow.data.psp.PspPaymentStatus
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.usecase.CheckoutOrder
import com.darshan.payflow.domain.usecase.RecordCheckoutResultUseCase
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.testutil.runOffMain
import com.darshan.payflow.testutil.waitUntilWithMainLooper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The real CheckoutKit sheet (JitPack artifact) bound to PayFlow's sandbox PSP through [PayFlowPaymentGateway]; every
 * outcome is recorded into PayFlow's Room history as a CHECKOUT payment.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CheckoutKitIntegrationTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var db: PayFlowDatabase
    private lateinit var repo: PaymentRepositoryImpl
    private val merchant = CheckoutMerchant("payflow.store@okbank", "PayFlow Store")
    private val order = CheckoutOrder("PF-TEST-1", 49_900, "Coffee beans", merchant.vpa, merchant.name)
    private val results = Collections.synchronizedList(mutableListOf<CheckoutResult>())

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).allowMainThreadQueries().build()
        repo = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
    }

    @After
    fun tearDown() = db.close()

    private fun open(psp: PspClient): PayFlowPaymentGateway {
        val gateway = PayFlowPaymentGateway(psp, merchant)
        val kit = CheckoutKit(
            gateway,
            CheckoutConfig(
                retryPolicy = RetryPolicy(maxAttempts = 2, initialDelay = 10.milliseconds, attemptTimeout = 2.seconds),
                pendingPolicy = PendingPolicy(initialInterval = 50.milliseconds, maxInterval = 100.milliseconds, maxPolls = 10),
            ),
        )
        val record = RecordCheckoutResultUseCase(repo, fixedClock) { orderId -> gateway.txnRefForOrder(orderId) }
        rule.setContent {
            val sheet = rememberCheckoutSheet(kit, CheckoutSheetOptions(merchantName = merchant.name)) { result ->
                results += result
                runOffMainLater { record(order, result) }
            }
            Button(onClick = { sheet.launch(CheckoutRequest(order.orderId, order.amountPaise, description = order.description)) }) { Text("Checkout") }
            CheckoutSheet(sheet)
        }
        rule.onNodeWithText("Checkout").performClick()
        waitForTag(CheckoutSheetTestTags.SHEET)
        return gateway
    }

    private val pendingRecord = AtomicReference<(suspend () -> Unit)?>(null)
    private fun runOffMainLater(block: suspend () -> Unit) { pendingRecord.set(block) }
    private fun flushRecord() { pendingRecord.getAndSet(null)?.let { block -> runOffMain { block() } } }

    private fun waitForTag(tag: String) = rule.waitUntilWithMainLooper(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun payWithUpi(vpa: String = "alice@okbank") {
        rule.onNodeWithTag(CheckoutSheetTestTags.VPA_FIELD).performTextInput(vpa)
        rule.onNodeWithTag(CheckoutSheetTestTags.PAY_BUTTON).performScrollTo().performClick()
    }

    private fun closeSheet() {
        rule.onNodeWithTag(CheckoutSheetTestTags.CLOSE_BUTTON).performClick()
        rule.waitUntilWithMainLooper { results.isNotEmpty() }
        flushRecord()
    }

    private fun storedPayment() = runOffMain { repo.observeRecent(1).first() }.single()

    @Test
    fun success_isRecordedAsACheckoutPayment() {
        val psp = FakePsp(fixedClock)
        open(psp)
        payWithUpi()
        waitForTag(CheckoutSheetTestTags.SUCCESS)
        closeSheet()
        assertThat(results.single()).isInstanceOf(CheckoutResult.Success::class.java)
        val payment = storedPayment()
        assertThat(payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(payment.method).isEqualTo(PaymentMethod.CHECKOUT)
        assertThat(payment.amountPaise).isEqualTo(49_900)
        assertThat(payment.payeeVpa).isEqualTo(merchant.vpa)
        assertThat(psp.orders.values.single().payeeVpa).isEqualTo(merchant.vpa)
    }

    @Test
    fun pendingThenSuccess_showsWaitingThenSuccess() {
        val psp = FakePsp(fixedClock, FakePspConfig(pendingPolls = 3))
        open(psp)
        payWithUpi()
        waitForTag(CheckoutSheetTestTags.PENDING)
        waitForTag(CheckoutSheetTestTags.SUCCESS)
        closeSheet()
        assertThat(results.single()).isInstanceOf(CheckoutResult.Success::class.java)
        assertThat(storedPayment().status).isEqualTo(PaymentStatus.SUCCESS)
    }

    @Test
    fun pendingThenFailed_whenThePayerDeclinesInTheirApp() {
        val psp = FakePsp(fixedClock, FakePspConfig(outcome = FakeOutcome.PENDING_UNTIL_SETTLED))
        open(psp)
        payWithUpi()
        waitForTag(CheckoutSheetTestTags.PENDING)
        psp.settle(psp.orders.keys.single(), approved = false)
        waitForTag(CheckoutSheetTestTags.FAILED)
        closeSheet()
        assertThat(results.single()).isInstanceOf(CheckoutResult.Failed::class.java)
        assertThat(storedPayment().status).isEqualTo(PaymentStatus.FAILED)
    }

    @Test
    fun declined_isRecordedAsFailedWithTheError() {
        open(FakePsp(fixedClock, FakePspConfig(outcome = FakeOutcome.FAILED)))
        payWithUpi()
        waitForTag(CheckoutSheetTestTags.FAILED)
        rule.onNodeWithTag(CheckoutSheetTestTags.CLOSE_BUTTON).performClick()
        rule.waitUntilWithMainLooper { results.isNotEmpty() }
        flushRecord()
        val failed = results.single() as CheckoutResult.Failed
        assertThat(storedPayment().status).isEqualTo(PaymentStatus.FAILED)
        assertThat(storedPayment().failureReason).contains(failed.error.code)
    }

    @Test
    fun unresolved_whenThePspCapturesButNeverAnswers_isStoredPendingForReconciliation() {
        val inner = FakePsp(fixedClock)
        val mute = object : PspClient by inner {
            override suspend fun getPaymentStatus(txnRef: String): PspPaymentStatus = throw PspException.Timeout()
        }
        val gateway = open(mute)
        payWithUpi()
        waitForTag(CheckoutSheetTestTags.UNRESOLVED)
        closeSheet()
        assertThat(results.single()).isInstanceOf(CheckoutResult.Unresolved::class.java)
        val payment = storedPayment()
        assertThat(payment.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(payment.txnRef).isEqualTo(gateway.txnRefForOrder(order.orderId))
        assertThat(inner.orders.containsKey(payment.txnRef)).isTrue() // the PSP really did capture it
    }

    @Test
    fun cancelled_storesNothing() {
        val psp = FakePsp(fixedClock)
        open(psp)
        closeSheet()
        assertThat(results.single()).isEqualTo(CheckoutResult.Cancelled)
        assertThat(runOffMain { db.paymentDao().count() }).isEqualTo(0)
        assertThat(psp.initiateCalls).isEqualTo(0)
    }
}
