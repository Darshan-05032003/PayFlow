package com.darshan.payflow.integration

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.core.DefaultDispatcherProvider
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.repository.MandateRepositoryImpl
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.domain.model.MandateAction
import com.darshan.payflow.domain.model.MandateStatus
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.usecase.CreateMandateUseCase
import com.darshan.payflow.domain.usecase.ExecuteMandateUseCase
import com.darshan.payflow.domain.usecase.UpdateMandateUseCase
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.testutil.runOffMain
import com.darshan.payflow.testutil.waitUntilWithMainLooper
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.mandates.CreateMandateCallbacks
import com.darshan.payflow.ui.mandates.CreateMandateEvent
import com.darshan.payflow.ui.mandates.CreateMandateScreen
import com.darshan.payflow.ui.mandates.CreateMandateViewModel
import com.darshan.payflow.ui.mandates.MandateDetailCallbacks
import com.darshan.payflow.ui.mandates.MandateDetailScreen
import com.darshan.payflow.ui.mandates.MandateDetailUiState
import com.darshan.payflow.ui.mandates.MandateDetailViewModel
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
import java.time.ZoneOffset
import java.util.Collections

/** Create → pause → resume → simulated debit → revoke, through Room, FakePsp, the ViewModels and the Compose screens. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class MandateFlowIntegrationTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var db: PayFlowDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun mandateLifecycleEndToEnd() {
        val psp = FakePsp(fixedClock)
        val mandates = MandateRepositoryImpl(db.mandateDao())
        val payments = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
        val createVm = CreateMandateViewModel(CreateMandateUseCase(mandates, psp, fixedClock, ZoneOffset.UTC), fixedClock, ZoneOffset.UTC, DefaultDispatcherProvider(), SavedStateHandle())
        val created = Collections.synchronizedList(mutableListOf<CreateMandateEvent>())
        createVm.events.onEach { created += it }.launchIn(CoroutineScope(Dispatchers.Unconfined))
        val detailVm = mutableStateOf<MandateDetailViewModel?>(null)

        rule.setContent {
            PayFlowTheme {
                val detail = detailVm.value
                if (detail == null) {
                    val s by createVm.uiState.collectAsStateWithLifecycle()
                    CreateMandateScreen(
                        s,
                        CreateMandateCallbacks(
                            onVpaChange = createVm::onVpaChange, onNameChange = createVm::onNameChange, onAmountChange = createVm::onAmountChange,
                            onFrequencyChange = createVm::onFrequencyChange, onValidityMonthsChange = createVm::onValidityMonthsChange, onSubmit = createVm::submit,
                        ),
                    )
                } else {
                    val s by detail.uiState.collectAsStateWithLifecycle()
                    MandateDetailScreen(s, MandateDetailCallbacks(onAction = detail::act, onDebitAmountChange = detail::onDebitAmountChange, onSimulateDebit = detail::simulateDebit))
                }
            }
        }
        rule.onNodeWithTag(PayFlowTestTags.MANDATE_VPA).performTextInput("netflix@okbank")
        rule.onNodeWithTag(PayFlowTestTags.MANDATE_NAME).performTextInput("Netflix")
        rule.onNodeWithTag(PayFlowTestTags.MANDATE_AMOUNT).performTextInput("649")
        rule.onNodeWithTag(PayFlowTestTags.MANDATE_SUBMIT).performScrollTo().performClick()
        rule.waitUntilWithMainLooper { created.any { it is CreateMandateEvent.Created } }
        val id = (created.single() as CreateMandateEvent.Created).mandateId
        assertThat(runOffMain { mandates.getById(id) }!!.status).isEqualTo(MandateStatus.ACTIVE)

        detailVm.value = MandateDetailViewModel(
            mandates, payments, UpdateMandateUseCase(mandates, psp, fixedClock),
            ExecuteMandateUseCase(mandates, payments, psp, fixedClock, ZoneOffset.UTC), fixedClock, ZoneOffset.UTC, DefaultDispatcherProvider(),
            SavedStateHandle(mapOf(MandateDetailViewModel.ARG_ID to id)),
        )
        val detail = detailVm.value!!
        rule.waitUntilWithMainLooper { detail.uiState.value is MandateDetailUiState.Content }
        rule.onNodeWithText("Netflix").assertIsDisplayed()

        rule.onNodeWithTag(PayFlowTestTags.mandateAction(MandateAction.PAUSE)).performClick()
        rule.waitUntilWithMainLooper { (detail.uiState.value as? MandateDetailUiState.Content)?.mandate?.status == MandateStatus.PAUSED }
        rule.onNodeWithTag(PayFlowTestTags.mandateAction(MandateAction.RESUME)).performClick()
        rule.waitUntilWithMainLooper { (detail.uiState.value as? MandateDetailUiState.Content)?.mandate?.status == MandateStatus.ACTIVE }

        rule.onNodeWithTag(PayFlowTestTags.MANDATE_DEBIT_AMOUNT).performScrollTo().performTextInput("649")
        rule.onNodeWithTag(PayFlowTestTags.MANDATE_DEBIT).performScrollTo().performClick()
        rule.waitUntilWithMainLooper { (detail.uiState.value as? MandateDetailUiState.Content)?.executions?.size == 1 }
        val payment = runOffMain { payments.observeRecent(1).first() }.single()
        assertThat(payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(payment.method).isEqualTo(PaymentMethod.MANDATE)
        assertThat(payment.mandateId).isEqualTo(id)

        rule.onNodeWithTag(PayFlowTestTags.mandateAction(MandateAction.REVOKE)).performScrollTo().performClick()
        rule.waitUntilWithMainLooper { (detail.uiState.value as? MandateDetailUiState.Content)?.mandate?.status == MandateStatus.REVOKED }
        assertThat(psp.mandates.values.single().status).isEqualTo(MandateStatus.REVOKED)
    }
}
