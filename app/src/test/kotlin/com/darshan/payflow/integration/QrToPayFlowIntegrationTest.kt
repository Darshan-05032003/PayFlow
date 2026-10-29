package com.darshan.payflow.integration

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.core.DefaultDispatcherProvider
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.data.upi.UpiLaunchResult
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.usecase.ConfirmPaymentUseCase
import com.darshan.payflow.domain.usecase.PayViaUpiIntentUseCase
import com.darshan.payflow.testutil.FakeCameraController
import com.darshan.payflow.testutil.FakeUpiAppLauncher
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.testutil.runOffMain
import com.darshan.payflow.testutil.waitUntilWithMainLooper
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.testutil.toSavedStateHandle
import com.darshan.payflow.ui.navigation.PayDestination
import com.darshan.payflow.ui.navigation.toPayDestination
import com.darshan.payflow.ui.pay.PayCallbacks
import com.darshan.payflow.ui.pay.PayPhase
import com.darshan.payflow.ui.pay.PayScreen
import com.darshan.payflow.ui.pay.PayViewModel
import com.darshan.payflow.ui.scan.ScanCallbacks
import com.darshan.payflow.ui.scan.ScanEvent
import com.darshan.payflow.ui.scan.ScanScreen
import com.darshan.payflow.ui.scan.ScanUiState
import com.darshan.payflow.ui.scan.ScanViewModel
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

/** Camera frame (fake) → ScanViewModel → PayDestination args → PayViewModel → FakePsp → Room, with method QR_SCAN. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class QrToPayFlowIntegrationTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var db: PayFlowDatabase
    private lateinit var repo: PaymentRepositoryImpl
    private val camera = FakeCameraController()
    private val psp = FakePsp(fixedClock)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).allowMainThreadQueries().build()
        repo = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun scannedQrFlowsIntoAPrefilledPaymentThatIsConfirmedAndStoredAsQrScan() {
        val scanVm = ScanViewModel(SavedStateHandle())
        val events = Collections.synchronizedList(mutableListOf<ScanEvent>())
        scanVm.events.onEach { events += it }.launchIn(CoroutineScope(Dispatchers.Unconfined))
        var destination: PayDestination? = null
        val payVmHolder = mutableStateOf<PayViewModel?>(null)

        // One composition that switches from the scan screen to the pay screen, as the NavHost would.
        rule.setContent {
            PayFlowTheme {
                val payVm = payVmHolder.value
                if (payVm == null) {
                    val state by scanVm.uiState.collectAsStateWithLifecycle()
                    ScanScreen(
                        state,
                        ScanCallbacks(onBarcode = scanVm::onBarcode, onPay = { destination = (state as ScanUiState.Found).payload.toPayDestination() }, onRescan = scanVm::rescan),
                        camera,
                    )
                } else {
                    val state by payVm.uiState.collectAsStateWithLifecycle()
                    PayScreen(state, PayCallbacks(onSubmit = payVm::submit))
                }
            }
        }
        scanVm.onPermissionResult(granted = true, canAskAgain = true)
        rule.waitUntilWithMainLooper { camera.started }
        camera.emit("upi://pay?pa=shop@okbank&pn=Chai%20Point&am=45.00&cu=INR&tn=Table%204")
        rule.waitUntilWithMainLooper { scanVm.uiState.value is ScanUiState.Found }
        assertThat(events.single()).isInstanceOf(ScanEvent.PayloadReady::class.java)
        rule.onNodeWithText("Chai Point").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.SCAN_PAY).performClick()
        val dest = destination!!
        assertThat(dest).isEqualTo(PayDestination(vpa = "shop@okbank", name = "Chai Point", amountPaise = 4_500, note = "Table 4", source = "qr"))

        // Navigation would create the Pay screen with these args in its SavedStateHandle:
        val payVm = PayViewModel(
            PayViaUpiIntentUseCase(repo, psp, fixedClock), ConfirmPaymentUseCase(repo, psp, fixedClock), FakeUpiAppLauncher(),
            DefaultDispatcherProvider(), dest.toSavedStateHandle(),
        )
        payVmHolder.value = payVm
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.PAY_VPA).assertIsNotEnabled()
        rule.onNodeWithTag(PayFlowTestTags.PAY_AMOUNT).assertIsNotEnabled()
        rule.onNodeWithTag(PayFlowTestTags.PAY_SUBMIT).performClick()
        rule.waitUntilWithMainLooper { payVm.uiState.value.phase is PayPhase.AwaitingApp }
        val txnRef = runOffMain { repo.observeRecent(1).first() }.single().txnRef
        payVm.onUpiAppResult(UpiLaunchResult.Responded("Status=SUCCESS&txnRef=$txnRef"))
        rule.waitUntilWithMainLooper { payVm.uiState.value.phase is PayPhase.Done }
        val stored = runOffMain { repo.observeRecent(1).first() }.single()
        assertThat(stored.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(stored.method).isEqualTo(PaymentMethod.QR_SCAN)
        assertThat(stored.payeeVpa).isEqualTo("shop@okbank")
        assertThat(stored.amountPaise).isEqualTo(4_500)
    }
}
