package com.darshan.payflow.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates `app/src/main/generated/baselineProfiles/baseline-prof.txt` from the critical journey:
 * cold start → home → history → back.
 *
 * Run on a device or emulator: `./gradlew :app:generateBaselineProfile`
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
        maxIterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(HOME_HISTORY_TAG)), 10_000)
        device.findObject(By.res(HOME_HISTORY_TAG))?.click()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE = "com.darshan.payflow"
        const val HOME_HISTORY_TAG = "payflow:home:history"
    }
}
