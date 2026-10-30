package com.darshan.payflow.stress

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.data.upi.IncomingUpiLinkStore
import com.darshan.payflow.data.upi.UpiLaunchResult
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.domain.model.UpiPayRequest
import com.darshan.payflow.domain.usecase.ManualMarkResult
import com.darshan.payflow.domain.usecase.MarkPaymentManuallyUseCase
import com.darshan.payflow.domain.usecase.PayViaUpiIntentUseCase
import com.darshan.payflow.domain.usecase.RecordUpiAppResultUseCase
import com.darshan.payflow.domain.usecase.UpiAppResult
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * UPI-apps mode stress: spoofed / replayed app responses against the real Room CAS, racing manual marks, and a flood of hostile
 * incoming `upi://` links through the activity's link store.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UpiAppModeStressTest {
    private lateinit var db: PayFlowDatabase
    private lateinit var repo: PaymentRepositoryImpl
    private val counter = AtomicInteger()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).build()
        repo = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun launch(pay: PayViaUpiIntentUseCase, ref: String): String =
        pay(UpiPayRequest("payee@okaxis", "Payee", 10_000, "", ref), PaymentMethod.UPI_INTENT, "key-$ref", PaymentMode.UPI_APPS, "com.example.gpay").getOrThrow().paymentId

    @Test
    fun tenThousandSpoofedAppResponsesForOtherReferencesNeverSettleThePayment() = runBlocking {
        val pay = PayViaUpiIntentUseCase(repo, FakePsp(fixedClock), fixedClock) { "p${counter.incrementAndGet()}" }
        val record = RecordUpiAppResultUseCase(repo)
        val id = launch(pay, "PFREAL0001")
        val random = Random(7)
        val variants = listOf(
            { i: Int -> "Status=SUCCESS&txnRef=PFOTHER$i&ApprovalRefNo=$i" },
            { i: Int -> "status=success&TXNREF=pfspoof$i" },
            { i: Int -> "Status=FAILURE&txnRef=PFWRONG$i&responseCode=U30" },
            { i: Int -> "Status=SUBMITTED&txnRef=PFREAL0001" },
            { i: Int -> "‮Status=SUCCESS&txnRef=%00PFREAL0001&x=" + "y".repeat(random.nextInt(5_000)) },
        )
        val results = ConcurrentHashMap<String, AtomicInteger>()
        val ms = measureTimeMillis {
            (1..10_000).map { i ->
                async(Dispatchers.IO) {
                    val r = record(id, UpiLaunchResult.Responded(variants[i % variants.size](i)))
                    results.getOrPut(r::class.simpleName!!) { AtomicInteger() }.incrementAndGet()
                }
            }.awaitAll()
        }
        val stored = repo.getById(id)!!
        println("STRESS [upi-app-mode] 10,000 spoofed responses: ${ms} ms, results=$results, final=${stored.status}/${stored.statusSource}")
        assertThat(stored.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(results["ReportedSuccess"]).isNull()
        assertThat(results["ReportedFailure"]).isNull()
        assertThat(stored.canBeMarkedManually).isTrue()
    }

    @Test
    fun aThousandReplayedDeliveriesOfOneSuccessSettleExactlyOnce() = runBlocking {
        val pay = PayViaUpiIntentUseCase(repo, FakePsp(fixedClock), fixedClock) { "p${counter.incrementAndGet()}" }
        val record = RecordUpiAppResultUseCase(repo)
        val mark = MarkPaymentManuallyUseCase(repo)
        val id = launch(pay, "PFREAL0002")
        val results = ConcurrentHashMap<String, AtomicInteger>()
        val ms = measureTimeMillis {
            (1..1_000).map { i ->
                async(Dispatchers.IO) {
                    // Half the callers are the UPI app's result (success and failure racing), half are the user tapping "I paid" / "not paid".
                    val name = when (i % 4) {
                        0 -> record(id, UpiLaunchResult.Responded("Status=SUCCESS&txnRef=PFREAL0002&txnId=T$i"))::class.simpleName
                        1 -> record(id, UpiLaunchResult.Responded("Status=FAILURE&txnRef=PFREAL0002&responseCode=ZA"))::class.simpleName
                        2 -> mark(id, paid = true)::class.simpleName
                        else -> mark(id, paid = false)::class.simpleName
                    }
                    results.getOrPut(name!!) { AtomicInteger() }.incrementAndGet()
                }
            }.awaitAll()
        }
        val stored = repo.getById(id)!!
        val settlers = (results["ReportedSuccess"]?.get() ?: 0) + (results["ReportedFailure"]?.get() ?: 0) + (results["Marked"]?.get() ?: 0)
        println("STRESS [upi-app-mode] 1,000 racing settlements: ${ms} ms, results=$results, final=${stored.status}/${stored.statusSource}")
        assertThat(settlers).isEqualTo(1)
        assertThat(stored.status).isAnyOf(PaymentStatus.SUCCESS, PaymentStatus.FAILED)
        assertThat(stored.statusSource).isAnyOf(StatusSource.UPI_APP, StatusSource.USER)
    }

    @Test
    fun tenThousandHostileIncomingLinksNeverThrowAndOnlyValidOnesAreOffered() {
        val store = IncomingUpiLinkStore()
        val random = Random(11)
        val pieces = listOf("upi://pay?", "UPI://PAY?", "https://evil/?", "upi://mandate?", "intent://pay?", "upi://pay/?", "")
        val params = listOf("pa=alice@okbank", "pa=javascript:alert(1)@x", "pa=‮a@b", "am=1.00", "am=-1", "am=1e9", "cu=INR", "cu=USD", "pn=%ZZ%00", "tr=" + "x".repeat(40), "url=http://evil", "pa=")
        var offered = 0
        var exceptions = 0
        val ms = measureTimeMillis {
            repeat(10_000) {
                val raw = pieces[random.nextInt(pieces.size)] + (1..random.nextInt(1, 6)).joinToString("&") { params[random.nextInt(params.size)] } +
                    if (random.nextInt(50) == 0) "&" + "z".repeat(random.nextInt(20_000)) else ""
                try { if (store.offer(raw)) { offered++; assertThat(store.consume()!!.payeeVpa).isEqualTo("alice@okbank") } } catch (e: Exception) { exceptions++ }
            }
        }
        println("STRESS [upi-app-mode] 10,000 hostile incoming links: ${ms} ms, offered=$offered, exceptions=$exceptions")
        assertThat(exceptions).isEqualTo(0)
        assertThat(offered).isGreaterThan(0)
        assertThat(store.pending.value).isNull()
    }
}
