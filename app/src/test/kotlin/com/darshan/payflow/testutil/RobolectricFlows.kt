package com.darshan.payflow.testutil

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeTestRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf
import kotlin.concurrent.thread

/**
 * Under Robolectric the test thread is the main thread and Compose's `waitUntil` does not drain the main looper,
 * so continuations dispatched to `Dispatchers.Main` (e.g. `viewModelScope`) never run. Idling the looper inside
 * the condition lets real ViewModels work with real dispatchers.
 */
fun ComposeTestRule.waitUntilWithMainLooper(timeoutMillis: Long = 5_000, condition: () -> Boolean) =
    waitUntil(timeoutMillis) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

/** Collects [flow] on a background thread until [predicate] matches while the main looper is kept idle. */
fun <T> awaitFlowValue(flow: Flow<T>, timeoutMillis: Long = 5_000, predicate: (T) -> Boolean): T {
    var result: Result<T>? = null
    val worker = thread(name = "awaitFlowValue") {
        result = runCatching { runBlocking { withTimeout(timeoutMillis) { flow.first(predicate) } } }
    }
    val deadline = System.currentTimeMillis() + timeoutMillis + 500
    while (worker.isAlive && System.currentTimeMillis() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(5)
    }
    worker.join(500)
    return (result ?: error("awaitFlowValue timed out after $timeoutMillis ms")).getOrThrow()
}

/** Runs [block] on a background thread while idling the main looper (for suspend repository calls in UI tests). */
fun <T> runOffMain(timeoutMillis: Long = 5_000, block: suspend () -> T): T {
    var result: Result<T>? = null
    val worker = thread(name = "runOffMain") { result = runCatching { runBlocking { withTimeout(timeoutMillis) { block() } } } }
    val deadline = System.currentTimeMillis() + timeoutMillis + 500
    while (worker.isAlive && System.currentTimeMillis() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(5)
    }
    worker.join(500)
    return (result ?: error("runOffMain timed out")).getOrThrow()
}
