package com.darshan.payflow.data.security

import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.ZoneOffset

class PinManagerTest {
    private val store = InMemorySecureStore()
    private fun manager(clock: Clock = fixedClock, iterations: Int = 1_000) = PinManager(store, clock, iterations)

    @Test
    fun `pin rules - six digits, not trivial`() {
        assertThat(PinManager.validateNewPin("493027")).isEqualTo(PinRule.Ok)
        assertThat(PinManager.validateNewPin("12345")).isEqualTo(PinRule.WrongLength)
        assertThat(PinManager.validateNewPin("1234567")).isEqualTo(PinRule.WrongLength)
        assertThat(PinManager.validateNewPin("12a456")).isEqualTo(PinRule.NotDigits)
        assertThat(PinManager.validateNewPin("١٢٣٤٥٦")).isEqualTo(PinRule.NotDigits)
        assertThat(PinManager.validateNewPin("111111")).isEqualTo(PinRule.TooSimple)
        assertThat(PinManager.validateNewPin("123456")).isEqualTo(PinRule.TooSimple)
        assertThat(PinManager.validateNewPin("654321")).isEqualTo(PinRule.TooSimple)
        assertThat(PinManager.validateNewPin("121212")).isEqualTo(PinRule.TooSimple)
    }

    @Test
    fun `set then verify, wrong pins count down to lockout`() = runTest {
        val m = manager()
        assertThat(m.isSet()).isFalse()
        assertThat(m.verify("493027")).isEqualTo(PinVerification.NotSet)
        m.set("493027")
        assertThat(m.isSet()).isTrue()
        assertThat(m.verify("493027")).isEqualTo(PinVerification.Ok)
        assertThat(m.verify("000000")).isEqualTo(PinVerification.Wrong(remaining = 4))
        assertThat(m.verify("000001")).isEqualTo(PinVerification.Wrong(remaining = 3))
        assertThat(m.verify("000002")).isEqualTo(PinVerification.Wrong(remaining = 2))
        assertThat(m.verify("000003")).isEqualTo(PinVerification.Wrong(remaining = 1))
        val locked = m.verify("000004")
        assertThat(locked).isEqualTo(PinVerification.LockedOut(untilMillis = FIXED_INSTANT.toEpochMilli() + 30_000))
        // Even the right PIN is refused while locked, and it does not reset anything.
        assertThat(m.verify("493027")).isEqualTo(PinVerification.LockedOut(untilMillis = FIXED_INSTANT.toEpochMilli() + 30_000))
    }

    @Test
    fun `lockout grows with each round and is capped, a success resets it`() = runTest {
        var now = FIXED_INSTANT
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = now
        }
        val m = manager(clock)
        m.set("493027")
        fun burn() = repeat(5) { kotlinx.coroutines.runBlocking { m.verify("000000") } }
        burn()
        assertThat(m.lockoutRemainingMillis()).isEqualTo(30_000)
        now = now.plusSeconds(30)
        assertThat(m.lockoutRemainingMillis()).isEqualTo(0)
        burn()
        assertThat(m.lockoutRemainingMillis()).isEqualTo(60_000)
        now = now.plusSeconds(60); burn()
        assertThat(m.lockoutRemainingMillis()).isEqualTo(120_000)
        now = now.plusSeconds(120); burn()
        now = now.plusSeconds(240); burn()
        now = now.plusSeconds(480); burn()
        now = now.plusSeconds(960); burn()
        now = now.plusSeconds(1_920); burn()
        assertThat(m.lockoutRemainingMillis()).isEqualTo(PinManager.MAX_LOCKOUT_MILLIS)
        now = now.plusMillis(PinManager.MAX_LOCKOUT_MILLIS)
        assertThat(m.verify("493027")).isEqualTo(PinVerification.Ok)
        assertThat(m.verify("000000")).isEqualTo(PinVerification.Wrong(remaining = 4))
        m.verify("493027")
        burn()
        assertThat(m.lockoutRemainingMillis()).isEqualTo(30_000) // reset after success
    }

    @Test
    fun `clock moved backwards cannot shorten a lockout`() = runTest {
        var now = FIXED_INSTANT
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = now
        }
        val m = manager(clock)
        m.set("493027")
        repeat(5) { m.verify("000000") }
        now = now.minusSeconds(3_600)
        assertThat(m.verify("493027")).isInstanceOf(PinVerification.LockedOut::class.java)
    }

    @Test
    fun `pbkdf2 - random salt, strong iterations, same pin hashes differently, never stores the pin`() = runTest {
        val a = PinManager(InMemorySecureStore(), fixedClock)
        val b = PinManager(InMemorySecureStore(), fixedClock)
        a.set("493027"); b.set("493027")
        val recA = a.debugRecord()!!
        val recB = b.debugRecord()!!
        assertThat(recA.iterations).isAtLeast(100_000)
        assertThat(recA.salt.size).isEqualTo(16)
        assertThat(recA.salt).isNotEqualTo(recB.salt)
        assertThat(recA.hash).isNotEqualTo(recB.hash)
        assertThat(recA.hash.size).isEqualTo(32)
        assertThat(store.getAll().values.joinToString()).doesNotContain("493027")
    }

    @Test
    fun `lockout state survives process death`() = runTest {
        val m1 = manager()
        m1.set("493027")
        repeat(5) { m1.verify("000000") }
        val m2 = manager()
        assertThat(m2.verify("493027")).isInstanceOf(PinVerification.LockedOut::class.java)
        assertThat(m2.isSet()).isTrue()
    }

    @Test
    fun `changing the pin requires the old one and clear removes everything`() = runTest {
        val m = manager()
        m.set("493027")
        assertThat(m.change(old = "000000", new = "583921")).isEqualTo(PinVerification.Wrong(remaining = 4))
        assertThat(m.change(old = "493027", new = "583921")).isEqualTo(PinVerification.Ok)
        assertThat(m.verify("583921")).isEqualTo(PinVerification.Ok)
        m.clear()
        assertThat(m.isSet()).isFalse()
    }
}
