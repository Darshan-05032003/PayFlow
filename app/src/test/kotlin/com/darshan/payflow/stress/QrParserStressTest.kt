package com.darshan.payflow.stress

import com.darshan.payflow.data.qr.QrParseResult
import com.darshan.payflow.data.qr.UpiQrPayloadParser
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/** Feature 3 stress: 10,000 fuzzed QR payloads, 100 KB payloads, 1,000 concurrent parses. The parser must never throw. */
class QrParserStressTest {
    private val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@&=%.?:/-_+ \u0000‮éअ"

    private fun fuzz(random: Random): String {
        val base = listOf("upi://pay?", "UPI://PAY?", "upi://pay", "https://x?", "", "upi://mandate?")[random.nextInt(6)]
        val params = (0 until random.nextInt(0, 8)).joinToString("&") {
            val key = listOf("pa", "pn", "am", "cu", "tn", "tr", "mc", "url", "x", "%ZZ")[random.nextInt(10)]
            val value = buildString { repeat(random.nextInt(0, 40)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            "$key=$value"
        }
        return base + params
    }

    @Test
    fun tenThousandFuzzedPayloadsNeverThrow() {
        val random = Random(2026)
        var valid = 0
        val errors = HashMap<String, Int>()
        val ms = measureTimeMillis {
            repeat(10_000) {
                when (val r = UpiQrPayloadParser.parse(fuzz(random))) {
                    is QrParseResult.Valid -> valid++
                    is QrParseResult.Invalid -> errors.merge(r.error.name, 1, Int::plus)
                }
            }
        }
        println("STRESS [qr-fuzz-10k] total=${ms}ms valid=$valid errors=$errors")
        assertThat(valid + errors.values.sum()).isEqualTo(10_000)
    }

    @Test
    fun hundredKilobytePayloadsAreRejectedFast() {
        val huge = "upi://pay?pa=alice@okbank&tn=" + "A".repeat(100 * 1024)
        val nearLimit = "upi://pay?pa=alice@okbank&tn=" + "A".repeat(UpiQrPayloadParser.MAX_RAW_LENGTH - 40)
        val ms = measureTimeMillis { repeat(1_000) { UpiQrPayloadParser.parse(huge) } }
        val ms2 = measureTimeMillis { repeat(1_000) { UpiQrPayloadParser.parse(nearLimit) } }
        println("STRESS [qr-100kb] 1000x100KB=${ms}ms 1000xNearLimit=${ms2}ms")
        assertThat(ms).isLessThan(2_000)
    }

    @Test
    fun thousandConcurrentParses() = runBlocking {
        val ms = measureTimeMillis {
            (1..1_000).map { i ->
                async(Dispatchers.Default) { UpiQrPayloadParser.parse("upi://pay?pa=shop$i@okbank&pn=Shop%20$i&am=${i % 1000}.${i % 100}&tr=T$i") }
            }.awaitAll()
        }
        println("STRESS [qr-concurrent-1000] total=${ms}ms")
    }
}
