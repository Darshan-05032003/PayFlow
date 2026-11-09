package com.darshan.payflow.data.security

import com.darshan.payflow.testutil.FIXED_INSTANT
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset

/** The session lock's rules, attacked directly: a lock must never be undone by anything but a correct PIN or biometric. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockTest {
    private var now = FIXED_INSTANT
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId) = this
        override fun instant() = now
    }
    private val store = HangableStore()
    private val pins = PinManager(store, clock, iterations = 1_000)
    private val lock = AppLock(pins, clock)

    @Test
    fun `no pin means no lock, however long the app was away`() = runTest {
        lock.refresh()
        assertThat(lock.isLocked.value).isFalse()
        lock.onBackground()
        now = now.plusSeconds(AppLock.GRACE_SECONDS * 10)
        lock.onForeground()
        assertThat(lock.isLocked.value).isFalse()
    }

    @Test
    fun `a relock survives the next refresh, which is what a recreated activity runs`() = runTest {
        pins.set(PIN)
        lock.refresh()
        lock.unlock()
        lock.onBackground()
        now = now.plusSeconds(AppLock.GRACE_SECONDS + 1)
        lock.onForeground()
        assertThat(lock.isLocked.value).isTrue()

        lock.refresh() // back on the lock screen finishes the activity below API 31; reopening runs this again
        assertThat(lock.isLocked.value).isTrue()
    }

    @Test
    fun `an explicit lock survives the next refresh`() = runTest {
        pins.set(PIN)
        lock.refresh()
        lock.unlock()
        lock.lock()
        lock.refresh()
        assertThat(lock.isLocked.value).isTrue()
    }

    @Test
    fun `reopening after the grace period locks whichever of refresh and foreground runs first`() = runTest {
        pins.set(PIN)
        lock.refresh()
        lock.unlock()
        lock.onBackground()
        now = now.plusSeconds(AppLock.GRACE_SECONDS + 1)
        lock.onForeground()
        lock.refresh()
        assertThat(lock.isLocked.value).isTrue()

        lock.unlock()
        lock.onBackground()
        now = now.plusSeconds(AppLock.GRACE_SECONDS + 1)
        lock.refresh()
        lock.onForeground()
        assertThat(lock.isLocked.value).isTrue()
    }

    @Test
    fun `within the grace period the session stays open`() = runTest {
        pins.set(PIN)
        lock.refresh()
        lock.unlock()
        lock.onBackground()
        now = now.plusSeconds(AppLock.GRACE_SECONDS - 1)
        lock.onForeground()
        lock.refresh()
        assertThat(lock.isLocked.value).isFalse()
    }

    @Test
    fun `moving the system clock back while away cannot keep the session open`() = runTest {
        pins.set(PIN)
        lock.refresh()
        lock.unlock()
        lock.onBackground()
        now = now.minusSeconds(3_600)
        lock.onForeground()
        assertThat(lock.isLocked.value).isTrue()
    }

    @Test
    fun `the lock lands before the store answers, so no frame can show the unlocked app`() = runTest(UnconfinedTestDispatcher()) {
        pins.set(PIN)
        lock.refresh()
        lock.unlock()
        lock.onBackground()
        now = now.plusSeconds(AppLock.GRACE_SECONDS + 1)
        store.hang = true
        val job = launch(start = CoroutineStart.UNDISPATCHED) { lock.onForeground() }
        assertThat(lock.isLocked.value).isTrue()
        store.release()
        job.join()
        assertThat(lock.isLocked.value).isTrue()
    }

    @Test
    fun `a pin removed while unlocked clears the provisional lock`() = runTest {
        pins.set(PIN)
        lock.refresh()
        lock.unlock()
        pins.clear()
        lock.onBackground()
        now = now.plusSeconds(AppLock.GRACE_SECONDS + 1)
        lock.onForeground()
        assertThat(lock.isLocked.value).isFalse()
    }

    @Test
    fun `a pin set while unlocked protects the session from then on`() = runTest {
        lock.refresh()
        pins.set(PIN)
        lock.unlock() // what the setup flow does after storing the PIN
        lock.onBackground()
        now = now.plusSeconds(AppLock.GRACE_SECONDS + 1)
        lock.onForeground()
        assertThat(lock.isLocked.value).isTrue()
    }

    private class HangableStore : SecureStore {
        private val inner = InMemorySecureStore()
        var hang = false
        private val gate = CompletableDeferred<Unit>()
        fun release() = gate.complete(Unit)
        override suspend fun get(key: String): String? {
            if (hang) gate.await()
            return inner.get(key)
        }
        override suspend fun put(key: String, value: String) = inner.put(key, value)
        override suspend fun remove(key: String) = inner.remove(key)
    }

    private companion object {
        const val PIN = "493027"
    }
}
