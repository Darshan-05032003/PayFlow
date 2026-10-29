package com.darshan.payflow.integration

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.core.DefaultDispatcherProvider
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.repository.CollectRepositoryImpl
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.usecase.RefreshCollectRequestsUseCase
import com.darshan.payflow.domain.usecase.RespondToCollectUseCase
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.testutil.runOffMain
import com.darshan.payflow.testutil.waitUntilWithMainLooper
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.collect.CollectCallbacks
import com.darshan.payflow.ui.collect.CollectScreen
import com.darshan.payflow.ui.collect.CollectUiState
import com.darshan.payflow.ui.collect.CollectViewModel
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Room + FakePsp + use cases + CollectViewModel + CollectScreen: approve a request, see the payment land in history. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CollectFlowIntegrationTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var db: PayFlowDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun approveAnIncomingRequestEndToEnd() {
        val psp = FakePsp(fixedClock).also { it.simulateIncomingCollect("req-1", "shop@okbank", "Chai Point", 4_500, "Table 4", 600_000) }
        val payments = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
        val collects = CollectRepositoryImpl(db.collectDao())
        val vm = CollectViewModel(
            collects, RefreshCollectRequestsUseCase(collects, psp, fixedClock),
            RespondToCollectUseCase(payments = payments, collects = collects, psp = psp, clock = fixedClock),
            fixedClock, DefaultDispatcherProvider(),
        )
        rule.setContent {
            PayFlowTheme {
                val state by vm.uiState.collectAsStateWithLifecycle()
                CollectScreen(state, CollectCallbacks(onApprove = vm::approve, onDecline = vm::decline, onRefresh = vm::refresh))
            }
        }
        rule.waitUntilWithMainLooper { vm.uiState.value is CollectUiState.Content }
        rule.onNodeWithText("Chai Point").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.collectApprove("req-1")).performClick()
        rule.waitUntilWithMainLooper {
            (vm.uiState.value as? CollectUiState.Content)?.requests?.single()?.request?.status == CollectStatus.APPROVED
        }
        rule.onNodeWithText("Approved").assertIsDisplayed()
        val payment = runOffMain { payments.observeRecent(1).first() }.single()
        assertThat(payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(payment.method).isEqualTo(PaymentMethod.COLLECT)
        assertThat(payment.amountPaise).isEqualTo(4_500)
        assertThat(runOffMain { collects.getById("req-1") }!!.paymentId).isEqualTo(payment.id)
    }
}
