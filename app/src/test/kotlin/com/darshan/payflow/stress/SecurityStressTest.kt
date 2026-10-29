package com.darshan.payflow.stress

import com.darshan.payflow.data.security.InMemoryCryptoBox
import com.darshan.payflow.data.security.InMemorySecureStore
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.data.security.PinVerification
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.system.measureTimeMillis

/** Feature 7 stress: PBKDF2 cost under a brute force, lockout short-circuit, AES-GCM throughput. */
class SecurityStressTest {
    @Test
    fun bruteForceHitsLockoutAndStopsHashing() = runBlocking {
        val pins = PinManager(InMemorySecureStore(), fixedClock) // production iterations
        pins.set("493027")
        val oneVerify = measureTimeMillis { pins.verify("000000") }
        val results = ArrayList<PinVerification>()
        val ms = measureTimeMillis { repeat(1_000) { results += pins.verify(String.format("%06d", it)) } }
        val wrong = results.count { it is PinVerification.Wrong }
        val locked = results.count { it is PinVerification.LockedOut }
        println("STRESS [pin-bruteforce-1000] oneVerify=${oneVerify}ms total=${ms}ms wrong=$wrong lockedOut=$locked iterations=${pins.debugRecord()!!.iterations}")
        assertThat(wrong).isEqualTo(3) // the warm-up verify above used attempt 1; attempts 2-4 are "wrong", the 5th locks
        assertThat(locked).isEqualTo(997)
        assertThat(ms).isLessThan(oneVerify * 50) // locked-out attempts do not run PBKDF2
    }

    @Test
    fun tenThousandAesGcmRoundTrips() = runBlocking {
        val box = InMemoryCryptoBox()
        val payload = ByteArray(1024) { it.toByte() }
        val ms = measureTimeMillis {
            (1..10_000).map { async(Dispatchers.Default) { box.decrypt(box.encrypt(payload)) } }.awaitAll().forEach { assertThat(it).isEqualTo(payload) }
        }
        println("STRESS [aes-gcm-10k-1KB] total=${ms}ms perOp=${ms / 10_000.0}ms")
        assertThat(ms).isLessThan(20_000)
    }

    @Test
    fun hundredConcurrentPinVerificationsAreConsistent() = runBlocking {
        val pins = PinManager(InMemorySecureStore(), fixedClock, iterations = 10_000)
        pins.set("493027")
        val results = (1..100).map { i -> async(Dispatchers.Default) { pins.verify(if (i % 2 == 0) "493027" else "000000") } }.awaitAll()
        println("STRESS [pin-concurrent-100] ok=${results.count { it == PinVerification.Ok }} wrong=${results.count { it is PinVerification.Wrong }} locked=${results.count { it is PinVerification.LockedOut }}")
        assertThat(results.count { it == PinVerification.Ok } + results.count { it is PinVerification.Wrong } + results.count { it is PinVerification.LockedOut }).isEqualTo(100)
    }
}
