package com.darshan.payflow.data.security

import com.darshan.payflow.StartupPrefetch
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.darshan.payflow.ui.designsystem.effects.InMemoryUiPreferences
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * What a cold start waits for before it may show anything: whether a PIN exists and, when one does, the lock's details.
 * The answer must be cheap (no Keystore work just to learn that a PIN exists) and must fail closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupLockPathTest {
    private var now = FIXED_INSTANT
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId) = this
        override fun instant() = now
    }
    private val box = CountingCryptoBox()
    private val store = CountingStore(InMemorySecureStore(box))

    /** A fresh manager over the same store: what a new process sees. */
    private fun pins() = PinManager(store, clock, iterations = 1_000)

    private suspend fun pinSetInAnEarlierProcess() {
        pins().set(PIN)
        box.reset()
        store.reset()
    }

    @Test
    fun `whether a pin exists is answered without decrypting anything`() = runTest {
        pinSetInAnEarlierProcess()
        val pins = pins()
        assertThat(pins.knownIsSet()).isNull()
        assertThat(pins.isSet()).isTrue()
        assertThat(box.decrypts.get()).isEqualTo(0)
        assertThat(pins.knownIsSet()).isTrue()
        // Asked again, the answer comes from memory.
        pins.isSet()
        assertThat(store.reads.get()).isEqualTo(1)
    }

    @Test
    fun `the lock's details cost one decrypt and stay in step with every write`() = runTest {
        pinSetInAnEarlierProcess()
        val pins = pins()
        assertThat(pins.knownLockStatus()).isNull()
        val status = pins.lockStatus()
        assertThat(status.remainingAttempts).isEqualTo(PinManager.MAX_ATTEMPTS)
        assertThat(pins.remainingAttempts()).isEqualTo(PinManager.MAX_ATTEMPTS)
        assertThat(pins.lockoutRemainingMillis()).isEqualTo(0)
        assertThat(box.decrypts.get()).isEqualTo(1)

        assertThat(pins.verify("000000")).isEqualTo(PinVerification.Wrong(remaining = 4))
        assertThat(pins.knownLockStatus()!!.remainingAttempts).isEqualTo(4)
        repeat(4) { pins.verify("000000") }
        assertThat(pins.lockoutRemainingMillis()).isEqualTo(PinManager.BASE_LOCKOUT_MILLIS)
        // A second process reads the same bookkeeping from the store.
        assertThat(pins().lockStatus()).isEqualTo(pins.knownLockStatus())

        now = now.plusMillis(PinManager.BASE_LOCKOUT_MILLIS)
        assertThat(pins.verify(PIN)).isEqualTo(PinVerification.Ok)
        assertThat(pins.remainingAttempts()).isEqualTo(PinManager.MAX_ATTEMPTS)
        pins.clear()
        assertThat(pins.knownIsSet()).isFalse()
        assertThat(pins.knownLockStatus()!!.remainingAttempts).isEqualTo(PinManager.MAX_ATTEMPTS)
    }

    @Test
    fun `a pin record that can't be decrypted still locks the app`() = runTest {
        pinSetInAnEarlierProcess()
        store.corrupt(PinManager.KEY)
        val pins = pins()
        val lock = AppLock(pins, clock)
        lock.refresh()
        // Before, an unreadable record read as "no PIN" and the app opened with no lock at all.
        assertThat(lock.isLocked.value).isTrue()
        assertThat(pins.isSet()).isTrue()
        // Nothing can verify against it: the dead record is dropped, and the app says plainly that no PIN is set.
        assertThat(pins.verify(PIN)).isEqualTo(PinVerification.NotSet)
        assertThat(pins.isSet()).isFalse()
        assertThat(store.contains(PinManager.KEY)).isFalse()
    }

    @Test
    fun `the lock's answer is unknown until the store has given it`() = runTest {
        pinSetInAnEarlierProcess()
        val lock = AppLock(pins(), clock)
        assertThat(lock.resolvedLockState()).isNull()
        assertThat(lock.refresh()).isTrue()
        assertThat(lock.resolvedLockState()).isTrue()
        lock.unlock()
        assertThat(lock.resolvedLockState()).isFalse()
        lock.lock()
        assertThat(lock.resolvedLockState()).isTrue()

        val open = AppLock(PinManager(InMemorySecureStore(), clock, iterations = 1_000), clock)
        assertThat(open.refresh()).isFalse()
        assertThat(open.resolvedLockState()).isFalse()
    }

    @Test
    fun `the prefetch answers the lock before any screen asks, with one decrypt`() = runTest {
        pinSetInAnEarlierProcess()
        val pins = pins()
        val lock = AppLock(pins, clock)
        prefetch(lock, pins).start(this)
        advanceUntilIdle()
        assertThat(lock.resolvedLockState()).isTrue()
        assertThat(pins.knownLockStatus()).isEqualTo(LockStatus(PinManager.MAX_ATTEMPTS, 0))
        assertThat(box.decrypts.get()).isEqualTo(1)
        assertThat(store.reads.get()).isEqualTo(2)
    }

    @Test
    fun `with no pin the prefetch decrypts nothing`() = runTest {
        val pins = pins()
        val lock = AppLock(pins, clock)
        prefetch(lock, pins).start(this)
        advanceUntilIdle()
        assertThat(lock.resolvedLockState()).isFalse()
        assertThat(box.decrypts.get()).isEqualTo(0)
        assertThat(store.reads.get()).isEqualTo(1)
    }

    @Test
    fun `a failing store leaves the lock unanswered, never open`() = runTest {
        pinSetInAnEarlierProcess()
        store.failing = true
        val pins = pins()
        val lock = AppLock(pins, clock)
        prefetch(lock, pins).start(this)
        advanceUntilIdle()
        // Unknown keeps the splash and the Checking state up; the lock's own read decides later.
        assertThat(lock.resolvedLockState()).isNull()
        assertThat(lock.isLocked.value).isFalse()
    }

    private fun TestScope.prefetch(lock: AppLock, pins: PinManager) =
        StartupPrefetch(lock, pins, InMemoryUiPreferences(), TestDispatcherProvider(StandardTestDispatcher(testScheduler)))

    private companion object {
        const val PIN = "493027"
    }
}

/** Counts what crosses the crypto boundary: on a phone each decrypt is a Keystore operation. */
class CountingCryptoBox(private val inner: CryptoBox = InMemoryCryptoBox()) : CryptoBox {
    val decrypts = AtomicInteger()
    fun reset() = decrypts.set(0)
    override fun encrypt(plain: ByteArray, aad: ByteArray?): ByteArray = inner.encrypt(plain, aad)
    override fun decrypt(cipher: ByteArray, aad: ByteArray?): ByteArray = inner.decrypt(cipher, aad).also { decrypts.incrementAndGet() }
}

/** Counts store reads, can fail every call, and can overwrite a value with bytes no key decrypts. */
class CountingStore(private val inner: InMemorySecureStore) : SecureStore {
    val reads = AtomicInteger()
    var failing = false
    private val corrupted = mutableSetOf<String>()
    fun reset() = reads.set(0)
    fun corrupt(key: String) { corrupted += key }

    override suspend fun get(key: String): String? {
        check(!failing) { "store unavailable" }
        reads.incrementAndGet()
        return if (key in corrupted) null else inner.get(key)
    }

    override suspend fun contains(key: String): Boolean {
        check(!failing) { "store unavailable" }
        reads.incrementAndGet()
        return inner.contains(key)
    }

    override suspend fun put(key: String, value: String) { corrupted -= key; inner.put(key, value) }
    override suspend fun remove(key: String) { corrupted -= key; inner.remove(key) }
}
