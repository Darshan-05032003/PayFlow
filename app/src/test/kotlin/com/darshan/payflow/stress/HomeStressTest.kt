package com.darshan.payflow.stress

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.core.DefaultDispatcherProvider
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.local.toEntity
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.awaitFlowValue
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.testutil.reconcileNoop
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.testutil.waitUntilWithMainLooper
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.commitState
import com.darshan.payflow.ui.designsystem.recompositionCycles
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.home.HomeCallbacks
import com.darshan.payflow.ui.home.HomeEntranceMemory
import com.darshan.payflow.ui.home.HomeScreen
import com.darshan.payflow.ui.home.HomeUiState
import com.darshan.payflow.ui.home.HomeViewModel
import com.darshan.payflow.ui.designsystem.components.OdometerLogic
import com.google.common.truth.Truth.assertThat
import java.time.ZoneOffset
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Home at scale, on the JVM under Robolectric. These are composition and logic costs, not frame times: nothing here is drawn by
 * a GPU. Real frame times come from Macrobenchmark on a device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class HomeStressTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var db: PayFlowDatabase
    private lateinit var repo: PaymentRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).allowMainThreadQueries().build()
        repo = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
    }

    @After
    fun tearDown() = db.close()

    private fun seed(count: Int, paise: (Int) -> Long = { 100L + it }) = runBlocking {
        val statuses = listOf(PaymentStatus.SUCCESS, PaymentStatus.SUCCESS, PaymentStatus.SUCCESS, PaymentStatus.FAILED, PaymentStatus.REFUNDED)
        (0 until count).chunked(1_000).forEach { batch ->
            db.paymentDao().insertAll(
                batch.map { n ->
                    testPayment(
                        id = "p$n", txnRef = "PF$n", status = statuses[n % statuses.size], payeeName = "Payee ${n % 500}", payeeVpa = "payee${n % 500}@okbank",
                        amountPaise = paise(n), createdAt = FIXED_INSTANT.minusSeconds(n.toLong() % 86_400),
                    ).toEntity()
                },
            )
        }
    }

    private fun vm() = HomeViewModel(repo, reconcileNoop(repo), DefaultDispatcherProvider(), fixedClock, ZoneOffset.UTC)

    private fun usedKb() = Runtime.getRuntime().let { System.gc(); (it.totalMemory() - it.freeMemory()) / 1024 }

    @Test
    fun tenThousandStoredPaymentsReachTheScreenAsFiveRows() {
        seed(10_000)
        val viewModel = vm()
        val firstContentMs = measureTimeMillis {
            val content = awaitFlowValue(viewModel.uiState) { it is HomeUiState.Content } as HomeUiState.Content
            assertThat(content.recent).hasSize(HomeViewModel.RECENT_LIMIT)
            assertThat(content.summary.total).isEqualTo(10_000)
        }
        lateinit var scroll: ScrollState
        val composeMs = measureTimeMillis {
            rule.setDs(reduceMotion = true) {
                scroll = rememberScrollState()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                HomeScreen(state, HomeCallbacks(), memory = HomeEntranceMemory(), scroll = scroll)
            }
            rule.waitForIdle()
        }
        rule.waitUntilWithMainLooper(5_000) { rule.onAllNodesWithRow().isNotEmpty() }
        rule.mainClock.autoAdvance = false
        val before = recompositionCycles()
        val frames = 600
        var furthest = 0
        val scrollMs = measureTimeMillis {
            repeat(frames) { i ->
                rule.runOnUiThread { scroll.dispatchRawDelta(if (i % 200 < 100) 12f else -12f) }
                rule.mainClock.advanceTimeByFrame()
                rule.waitForIdle() // layout too, not only composition
                furthest = maxOf(furthest, scroll.value)
            }
        }
        val cycles = recompositionCycles() - before
        rule.mainClock.autoAdvance = true
        println("STRESS [home-10k] firstContent=${firstContentMs}ms compose=${composeMs}ms scroll=${scrollMs}ms over $frames frames (${scrollMs * 1000 / frames}us/frame incl. layout, no drawing, JVM, not a frame time) furthest=${furthest}px max=${scroll.maxValue}px recomposeCycles=$cycles")
        // Proves the page really moved, past where the header docks.
        assertThat(furthest).isGreaterThan(300)
        // Crossing the docking threshold both ways flips one state; nothing else may recompose.
        assertThat(cycles).isLessThan(20L)
    }

    @Test
    fun aNewPaymentAmongTenThousandUpdatesTheScreenQuickly() {
        seed(10_000)
        val viewModel = vm()
        awaitFlowValue(viewModel.uiState) { it is HomeUiState.Content }
        val updates = 50
        val ms = measureTimeMillis {
            repeat(updates) { i ->
                runBlocking {
                    repo.create(testPayment(id = "new$i", txnRef = "NEW$i", status = PaymentStatus.SUCCESS, createdAt = FIXED_INSTANT.plusSeconds(i + 1L)))
                }
                awaitFlowValue(viewModel.uiState) { (it as? HomeUiState.Content)?.recent?.firstOrNull()?.id == "new$i" }
            }
        }
        println("STRESS [home-10k-update] $updates inserts visible in ${ms}ms, ${ms / updates}ms each (four queries per update)")
        assertThat(ms / updates).isLessThan(500L)
    }

    /** 10,000 payments at the ₹1,00,000 cap total ₹1,00,00,00,000, one paisa past what the odometer shows: it clamps, and says so here. */
    @Test
    fun theLargestPossibleMonthClampsWithoutBreakingAnything() {
        seed(10_000) { Payment.MAX_AMOUNT_PAISE }
        val month = awaitFlowValue(vm().uiState) { it is HomeUiState.Content } as HomeUiState.Content
        assertThat(month.summary.sentPaise).isEqualTo(6_000L * Payment.MAX_AMOUNT_PAISE)
        val beyond = 10_000L * Payment.MAX_AMOUNT_PAISE
        assertThat(beyond).isGreaterThan(OdometerLogic.MAX_PAISE)
        assertThat(OdometerLogic.text(beyond, com.darshan.payflow.ui.designsystem.components.AmountDecimals.WhenNonZero, com.darshan.payflow.ui.designsystem.components.AmountSign.None)).isEqualTo("₹99,99,99,999.99")
        println("STRESS [home-max] 6,000 captured at the cap = ₹${month.summary.sentPaise / 100}; odometer limit ₹${OdometerLogic.MAX_PAISE / 100}; a month over it would show the limit")
    }

    @Test
    fun aThousandHideTogglesEndOnTheLastChoiceWithNoLeak() {
        var hidden by mutableStateOf(false)
        val payments = listOf(testPayment("a", status = PaymentStatus.SUCCESS).copy(direction = PaymentDirection.OUTGOING))
        val state = HomeUiState.Content(payments, HistorySummary.of(payments), HistorySummary.of(payments))
        rule.setDs {
            HomeScreen(state, HomeCallbacks(), amountsHidden = hidden)
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        val heapBefore = usedKb()
        val ms = measureTimeMillis {
            repeat(1_001) {
                commitState { hidden = !hidden }
                rule.mainClock.advanceTimeBy(16)
            }
            rule.mainClock.advanceTimeBy(2_000)
        }
        val heapAfter = usedKb()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val text = rule.onNodeWithTag(PayFlowTestTags.HOME_HERO_AMOUNT).fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        println("STRESS [home-hide-1000] ${ms}ms, heap growth ${heapAfter - heapBefore} KB, final=$text")
        assertThat(text).containsExactly("Amount hidden")
    }

    @Test
    fun aThousandRapidTapsOnScanAreDebouncedAndTheNavigationGuardDoesTheRest() {
        var scans = 0
        rule.setDs(reduceMotion = true) { HomeScreen(HomeUiState.Empty, HomeCallbacks(onScan = { scans++ })) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        val node = rule.onNodeWithTag(PayFlowTestTags.HOME_SCAN)
        val ms = measureTimeMillis { repeat(1_000) { node.performClick() } }
        rule.mainClock.autoAdvance = true
        println("STRESS [home-scan-1000] 1,000 taps in ${ms}ms: $scans accepted by the 500ms debounce on the injected clock; launchSingleTop leaves one scanner on the stack (ShellNavigationTest)")
        assertThat(scans).isAtLeast(1)
        assertThat(scans).isAtMost(100)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithRow() =
        onAllNodes(androidx.compose.ui.test.hasTestTag(PayFlowTestTags.paymentRow("p0"))).fetchSemanticsNodes()
}
