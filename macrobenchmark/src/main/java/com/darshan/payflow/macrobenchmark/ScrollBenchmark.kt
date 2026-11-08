package com.darshan.payflow.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing while flinging home and history over 200 seeded payments. Each iteration starts a new process, so the first
 * scroll after launch is what gets measured. Selectors that must survive UI changes are listed in docs/LOAD_TEST_REPORT.md.
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun seed() = seedBenchmarkData()

    @Test
    fun homeScrollNoCompilation() = home(CompilationMode.None())

    @Test
    fun homeScrollPartial() = home(PARTIAL)

    @Test
    fun historyScrollNoCompilation() = history(CompilationMode.None())

    @Test
    fun historyScrollPartial() = history(PARTIAL)

    private fun home(compilationMode: CompilationMode) = scroll(
        compilationMode,
        setup = { },
    ) { flingDownAndUp { tallestScrollable() } }

    private fun history(compilationMode: CompilationMode) = scroll(
        compilationMode,
        setup = {
            val entry = device.wait(Until.findObject(By.res(HOME_HISTORY_TAG)), WAIT_TIMEOUT_MS)
                ?: error("No view with test tag $HOME_HISTORY_TAG on the first screen")
            entry.click()
            check(device.wait(Until.hasObject(By.res(HISTORY_LIST_TAG)), WAIT_TIMEOUT_MS)) { "No view with test tag $HISTORY_LIST_TAG" }
            device.waitForIdle()
        },
    ) { flingDownAndUp { findObject(By.res(HISTORY_LIST_TAG)) ?: error("History list is gone") } }

    private fun scroll(
        compilationMode: CompilationMode,
        setup: MacrobenchmarkScope.() -> Unit,
        measure: MacrobenchmarkScope.() -> Unit,
    ) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilationMode,
        iterations = 5,
        setupBlock = {
            killProcess()
            pressHome()
            startActivityAndWait()
            setup()
        },
        measureBlock = measure,
    )

    private companion object {
        const val HOME_HISTORY_TAG = "payflow:home:history"
        const val HISTORY_LIST_TAG = "payflow:history:list"
    }
}
