package com.darshan.payflow.integration

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.MainActivity
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.repository.PaymentRepository
import com.darshan.payflow.testutil.runOffMain
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.testutil.waitUntilWithMainLooper
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.SCREENSHOT_DIR
import com.darshan.payflow.ui.designsystem.effects.ThemeMode
import com.darshan.payflow.ui.designsystem.effects.UiPreferences
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.onRoot
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The bar on the real tab screens, in both themes. The screens' own content is rebuilt later; this baseline is about the shell. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = HiltTestApplication::class, qualifiers = "w411dp-h891dp-xhdpi")
class ShellScreenshotTest {
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var preferences: UiPreferences
    @Inject lateinit var payments: PaymentRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        compose.waitUntilWithMainLooper(10_000) { exists(PayFlowTestTags.NAV_HOME) }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun theme(mode: ThemeMode) {
        runOffMain { preferences.setTheme(mode) }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SCREENSHOT_DIR/shell/$name.png")
    }

    @Test
    fun homeWithTheBarLight() {
        theme(ThemeMode.Light)
        shot("home-light")
    }

    @Test
    fun homeWithTheBarDark() {
        theme(ThemeMode.Dark)
        shot("home-dark")
    }

    @Test
    fun historyWithTheBarDark() {
        theme(ThemeMode.Dark)
        repeat(12) { i -> runOffMain { payments.create(testPayment(id = "p$i", status = PaymentStatus.SUCCESS)) } }
        compose.onNodeWithTag(PayFlowTestTags.HOME_HISTORY).performClick()
        compose.waitUntilWithMainLooper(10_000) { exists(PayFlowTestTags.PAYMENT_LIST) }
        shot("history-dark")
    }

    /** Part way through Scan & Pay growing into the scanner: the one frame that shows the container transform is really running. */
    @Test
    fun homeToScanMidTransformDark() {
        theme(ThemeMode.Dark)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(PayFlowTestTags.HOME_SCAN).performClick()
        compose.mainClock.advanceTimeBy(180)
        compose.onRoot().captureRoboImage("$SCREENSHOT_DIR/shell/home-to-scan-midway-dark.png")
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun meTabLight() {
        theme(ThemeMode.Light)
        compose.onNodeWithTag(PayFlowTestTags.NAV_ME).performClick()
        compose.waitUntilWithMainLooper(10_000) { exists(PayFlowTestTags.SECURITY_SETUP_PIN) }
        shot("me-light")
    }
}
