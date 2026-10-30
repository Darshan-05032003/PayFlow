package com.darshan.payflow.integration

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.getValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.core.DefaultDispatcherProvider
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.PaymentEvent
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.awaitFlowValue
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.testutil.runOffMain
import com.darshan.payflow.testutil.waitUntilWithMainLooper
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.history.HistoryScreen
import com.darshan.payflow.ui.history.HistoryUiState
import com.darshan.payflow.ui.history.HistoryViewModel
import com.darshan.payflow.ui.home.HomeScreen
import com.darshan.payflow.ui.home.HomeUiState
import com.darshan.payflow.ui.home.HomeViewModel
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.darshan.payflow.testutil.reconcileNoop

/** Room (in-memory) + repository + ViewModel + Compose screen wired together. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HomeHistoryFlowIntegrationTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var db: PayFlowDatabase
    private lateinit var repo: PaymentRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun homeGoesFromEmptyToContentWhenAPaymentIsStored() {
        val vm = HomeViewModel(repo, reconcileNoop(repo), DefaultDispatcherProvider())
        rule.setContent {
            PayFlowTheme {
                val state by vm.uiState.collectAsStateWithLifecycle()
                HomeScreen(state = state, callbacks = com.darshan.payflow.ui.home.HomeCallbacks())
            }
        }
        rule.waitUntilWithMainLooper(5_000) { vm.uiState.value is HomeUiState.Empty }
        rule.onNodeWithTag(PayFlowTestTags.EMPTY).performScrollTo().assertIsDisplayed()

        runOffMain {
            repo.create(testPayment("a", payeeName = "Alice", amountPaise = 9_900))
            repo.applyEvent("a", PaymentEvent.Initiate)
            repo.applyEvent("a", PaymentEvent.Confirm("psp", "upi"))
        }
        rule.waitUntilWithMainLooper(5_000) { vm.uiState.value is HomeUiState.Content }
        rule.onNodeWithTag(PayFlowTestTags.paymentRow("a")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Pay Alice again (alice@okbank)").assertExists() // the recent-payee row
        rule.onAllNodesWithText("₹99.00").onFirst().performScrollTo().assertIsDisplayed()
    }

    @Test
    fun historyFilterSurvivesViewModelRecreation() {
        runOffMain {
            repo.create(testPayment("ok", status = PaymentStatus.SUCCESS, payeeName = "Alice"))
            repo.create(testPayment("bad", status = PaymentStatus.FAILED, payeeName = "Bob"))
        }
        val handle = SavedStateHandle()
        val vm1 = HistoryViewModel(repo, com.darshan.payflow.testutil.reconcileNoop(repo), DefaultDispatcherProvider(), handle)
        rule.setContent {
            PayFlowTheme {
                val state by vm1.uiState.collectAsStateWithLifecycle()
                HistoryScreen(state = state, callbacks = com.darshan.payflow.ui.history.HistoryCallbacks(onFilter = vm1::setFilter))
            }
        }
        rule.waitUntilWithMainLooper(5_000) { vm1.uiState.value is HistoryUiState.Content }
        rule.onNodeWithTag(PayFlowTestTags.filterChip(HistoryFilter.FAILED)).performClick()
        rule.waitUntilWithMainLooper(5_000) { (vm1.uiState.value as? HistoryUiState.Content)?.filter == HistoryFilter.FAILED }
        rule.onNodeWithText("Bob").assertIsDisplayed()

        // "Process death": a new ViewModel over the same SavedStateHandle and database.
        val vm2 = HistoryViewModel(repo, com.darshan.payflow.testutil.reconcileNoop(repo), DefaultDispatcherProvider(), handle)
        val restored = awaitFlowValue(vm2.uiState) { it is HistoryUiState.Content } as HistoryUiState.Content
        assertThat(restored.filter).isEqualTo(HistoryFilter.FAILED)
        assertThat(restored.payments.map { it.id }).containsExactly("bad")
    }
}
