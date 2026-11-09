package com.darshan.payflow.stress

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.repository.CollectRepositoryImpl
import com.darshan.payflow.data.repository.MandateRepositoryImpl
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.Mandate
import com.darshan.payflow.domain.model.MandateAction
import com.darshan.payflow.domain.model.MandateFrequency
import com.darshan.payflow.domain.model.MandateStatus
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.usecase.CreateMandateRequest
import com.darshan.payflow.domain.usecase.CreateMandateUseCase
import com.darshan.payflow.domain.usecase.RefreshCollectRequestsUseCase
import com.darshan.payflow.domain.usecase.RespondToCollectUseCase
import com.darshan.payflow.domain.usecase.UpdateMandateUseCase
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.collect.CollectItem
import com.darshan.payflow.ui.collect.CollectSections
import com.darshan.payflow.ui.designsystem.recompositionCycles
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.history.DemoReward
import com.darshan.payflow.ui.history.HistoryCallbacks
import com.darshan.payflow.ui.history.HistoryScreen
import com.darshan.payflow.ui.history.HistorySections
import com.darshan.payflow.ui.history.HistoryUiState
import com.darshan.payflow.ui.history.ScratchCoverage
import com.darshan.payflow.ui.mandates.MandateSchedule
import com.google.common.truth.Truth.assertThat
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Money screens at scale: 100k history rows, 1,000 racing approvals, 1,000 pause/resume cycles, 1M scratch strokes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class MoneyScreensStressTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var db: PayFlowDatabase
    private val zone = ZoneOffset.UTC
    private val today = FIXED_INSTANT.atZone(zone).toLocalDate()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun hundredThousandPaymentsGroupIntoDaysQuickly() {
        val payments = List(100_000) { testPayment("p$it", status = PaymentStatus.SUCCESS, amountPaise = 1_00_000_00, createdAt = FIXED_INSTANT.minusSeconds(it * 300L)) }
        var entries = emptyList<com.darshan.payflow.ui.history.HistoryEntry>()
        val ms = measureTimeMillis { entries = HistorySections.build(payments, today, zone) }
        val days = entries.count { it is com.darshan.payflow.ui.history.HistoryEntry.Day }
        val biggestDay = entries.filterIsInstance<com.darshan.payflow.ui.history.HistoryEntry.Day>().maxOf { it.sentPaise }
        println("STRESS [history-sections-100k] build=${ms}ms entries=${entries.size} days=$days biggestDaySent=$biggestDay")
        assertThat(entries.map { it.key }.toSet()).hasSize(entries.size)
        // 288 payments of the maximum in one day: the total stays exact in Long paise.
        assertThat(biggestDay).isEqualTo(288L * 1_00_000_00)
        assertThat(ms).isLessThan(5_000)
    }

    @Test
    fun tenThousandRowsScrollWithStickyDaysAndSwipeRows() {
        val payments = List(10_000) { testPayment("p$it", status = PaymentStatus.entries[it % 6], payeeName = NAMES[it % NAMES.size], createdAt = FIXED_INSTANT.minusSeconds(it * 900L)) }
        val state = HistoryUiState.Content(payments, HistoryFilter.ALL, "", HistorySections.build(payments, today, zone))
        val hidden = mutableStateOf(false)
        rule.setDs { HistoryScreen(state, HistoryCallbacks(onBack = null), amountsHidden = hidden.value, zone = zone) }
        rule.waitForIdle()
        val list = rule.onNodeWithTag(PayFlowTestTags.PAYMENT_LIST)
        val cyclesBefore = recompositionCycles()
        val ms = measureTimeMillis {
            repeat(300) {
                list.performTouchInput { swipeUp() }
                rule.waitForIdle()
            }
        }
        val cycles = recompositionCycles() - cyclesBefore
        val jumpMs = measureTimeMillis {
            list.performScrollToIndex(state.entries.size)
            rule.waitForIdle()
        }
        println("STRESS [history-scroll-10k] swipes=300 total=${ms}ms perSwipe=${ms / 300}ms recompositionCycles=$cycles jumpToEnd=${jumpMs}ms")
        rule.onNodeWithTag(PayFlowTestTags.paymentRow("p9999")).assertExists()
    }

    @Test
    fun thousandConcurrentApprovesOfOneRequestPayOnce() = runBlocking {
        val psp = FakePsp(fixedClock).also { it.simulateIncomingCollect("req-1", "tara@okhdfc", "Tara Book House", 4_500, "Order 7", 600_000) }
        val payments = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
        val collects = CollectRepositoryImpl(db.collectDao())
        RefreshCollectRequestsUseCase(collects, psp, fixedClock).invoke()
        val respond = RespondToCollectUseCase(collects, payments, psp, fixedClock)
        val ms = measureTimeMillis {
            (1..1_000).map { async(Dispatchers.IO) { respond("req-1", approve = it % 7 != 0) } }.awaitAll()
        }
        val all = payments.observeHistory(HistoryFilter.ALL, "").first()
        val status = collects.getById("req-1")!!.status
        println("STRESS [collect-1000-racing-answers] total=${ms}ms payments=${all.size} status=$status")
        assertThat(status).isAnyOf(CollectStatus.APPROVED, CollectStatus.DECLINED)
        assertThat(all.size).isEqualTo(if (status == CollectStatus.APPROVED) 1 else 0)
    }

    @Test
    fun tenThousandRequestsSplitAndCountDownQuickly() {
        val items = List(10_000) { i ->
            CollectItem(
                com.darshan.payflow.domain.model.CollectRequest(
                    "r$i", "user$i@okbank", "User $i", 100L + i, "", FIXED_INSTANT.minusSeconds(600),
                    FIXED_INSTANT.plusSeconds((i % 1_200 - 600).toLong()), FIXED_INSTANT, if (i % 3 == 0) CollectStatus.DECLINED else CollectStatus.PENDING, null,
                ),
                busy = false,
            )
        }
        var open = 0
        val ms = measureTimeMillis { repeat(60) { open = CollectSections.split(items, FIXED_INSTANT.plusSeconds(it.toLong())).open.size } }
        println("STRESS [collect-split-10k] 60 ticks total=${ms}ms perTick=${ms / 60}ms open=$open")
        assertThat(ms / 60).isLessThan(500)
    }

    @Test
    fun thousandPauseResumeCyclesKeepProviderAndStoreInStep() = runBlocking {
        val psp = FakePsp(fixedClock)
        val mandates = MandateRepositoryImpl(db.mandateDao())
        CreateMandateUseCase(mandates, psp, fixedClock, zone) { "m-1" }
            .invoke(CreateMandateRequest("streambox@okbank", "Streambox", 64_900, MandateFrequency.MONTHLY, today, today.plusYears(1), ""))
        val update = UpdateMandateUseCase(mandates, psp, fixedClock)
        val applied = AtomicInteger()
        val ms = measureTimeMillis {
            repeat(1_000) { i ->
                val r = update("m-1", if (i % 2 == 0) MandateAction.PAUSE else MandateAction.RESUME)
                if (r is com.darshan.payflow.domain.usecase.UpdateMandateResult.Updated) applied.incrementAndGet()
            }
            // Then a burst of racing taps in both directions.
            (1..500).map { i -> async(Dispatchers.IO) { update("m-1", if (i % 2 == 0) MandateAction.PAUSE else MandateAction.RESUME) } }.awaitAll()
        }
        val local = mandates.getById("m-1")!!.status
        val remote = psp.mandates.values.single().status
        println("STRESS [mandate-1000-pause-resume] total=${ms}ms appliedSequential=${applied.get()} local=$local remote=$remote")
        assertThat(applied.get()).isEqualTo(1_000)
        assertThat(local).isEqualTo(remote)
    }

    @Test
    fun schedulesForTenThousandMandates() {
        val mandates = List(10_000) { i ->
            Mandate(
                "m$i", "U$i", "m$i@okbank", "M$i", 1_00_000_00, MandateFrequency.entries[i % 6], today.minusDays((i % 400).toLong()), today.plusYears(10),
                MandateStatus.ACTIVE, "", FIXED_INSTANT, FIXED_INSTANT, if (i % 2 == 0) FIXED_INSTANT else null, 0,
            )
        }
        var dates = 0
        val ms = measureTimeMillis { mandates.forEach { dates += MandateSchedule.upcoming(it, today, zone, 4).size } }
        val dailyTenYears = measureTimeMillis { MandateSchedule.upcoming(mandates[0].copy(frequency = MandateFrequency.DAILY), today, zone, 3_650) }
        println("STRESS [mandate-schedule-10k] total=${ms}ms dates=$dates daily3650=${dailyTenYears}ms")
        assertThat(ms).isLessThan(5_000)
    }

    @Test
    fun aMillionScratchStrokesAndTenThousandRewards() {
        val coverage = ScratchCoverage()
        var revealedAt = -1
        val ms = measureTimeMillis {
            for (i in 0 until 1_000_000) {
                coverage.scratch((i * 37 % 371).toFloat(), (i * 13 % 168).toFloat(), 14f, 371f, 168f)
                if (revealedAt < 0 && coverage.revealed) revealedAt = i
            }
        }
        val rewardsMs = measureTimeMillis {
            repeat(10_000) { assertThat(DemoReward.forPayment(testPayment("p$it", status = PaymentStatus.SUCCESS))!!.amountPaise).isAtMost(DemoReward.MAX_PAISE) }
        }
        println("STRESS [scratch-1M] total=${ms}ms revealedAtStroke=$revealedAt rewards10k=${rewardsMs}ms")
        assertThat(coverage.revealed).isTrue()
    }

    private companion object {
        val NAMES = listOf(
            "Asha Rao", "‮evil‬ name", "😀 Emoji Store 🎉", "مقهى الصباح", "शर्मा जनरल स्टोर",
            "Shri Venkateswara Lakshmi Narasimha Enterprises and Sons Private Limited Bengaluru " + "X".repeat(200),
        )
    }
}
