package com.darshan.payflow.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

internal const val PACKAGE = "com.darshan.payflow"

/** Picks up a Baseline Profile once the app ships one; warm-up runs alone stand in until then. */
internal val PARTIAL = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable, warmupIterations = 3)

private const val SEED_RECEIVER = ".benchmark.SeedDataReceiver"
internal const val WAIT_TIMEOUT_MS = 15_000L
private const val FLINGS = 3

/** Fills the app with benchmark data. Fails loudly: an empty list would produce believable but meaningless numbers. */
internal fun seedBenchmarkData() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val output = device.executeShellCommand("am broadcast --include-stopped-packages -n $PACKAGE/$SEED_RECEIVER")
    check("seeded=" in output) { "Seeding failed: $output" }
}

/** The screen's main list, whichever component draws it: horizontal rows and small nested scrollers lose to the tallest one. */
internal fun UiDevice.tallestScrollable(): UiObject2 {
    wait(Until.hasObject(By.scrollable(true)), WAIT_TIMEOUT_MS)
    return findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() } ?: error("Nothing scrollable on screen")
}

/** Flings down and back up. The target is looked up again each time because Compose may replace the node. */
internal fun MacrobenchmarkScope.flingDownAndUp(findList: UiDevice.() -> UiObject2) {
    listOf(Direction.DOWN, Direction.UP).forEach { direction ->
        repeat(FLINGS) {
            val list = device.findList()
            list.setGestureMargin(device.displayWidth / 5)
            list.fling(direction)
        }
    }
    device.waitForIdle()
}
