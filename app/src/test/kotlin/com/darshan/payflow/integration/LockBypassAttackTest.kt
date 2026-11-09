package com.darshan.payflow.integration

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.activity.BackEventCompat
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.MainActivity
import com.darshan.payflow.data.security.AppLock
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.testutil.waitUntilWithMainLooper
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.navigation.SecurityDestination
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * The real activity, the real Hilt graph, an attacker's inputs. Nothing behind the lock may be composed, reachable or even
 * briefly visible until the PIN is right.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = HiltTestApplication::class, qualifiers = "w411dp-h891dp")
class LockBypassAttackTest {
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var pins: PinManager
    @Inject lateinit var appLock: AppLock

    @Before
    fun inject() = hiltRule.inject()

    private fun launch(intent: Intent? = null): ActivityScenario<MainActivity> =
        if (intent == null) ActivityScenario.launch(MainActivity::class.java) else ActivityScenario.launch(intent)

    private fun viewIntent(data: String) =
        Intent(Intent.ACTION_VIEW, data.toUri()).setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)

    /** ActivityScenario finds its activity by comparing intents, and these intents are rewritten on purpose. */
    private fun withActivity(intent: Intent, block: (MainActivity) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        try {
            block(controller.get())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun setPin() = runBlocking { pins.set(PIN) }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun textExists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private val keyOne get() = PayFlowTestTags.lockKey("1")

    private fun awaitLock() = compose.waitUntilWithMainLooper(10_000) { exists(keyOne) }
    private fun awaitHome() = compose.waitUntilWithMainLooper(10_000) { exists(PayFlowTestTags.HOME_SCAN) }

    private fun enterPin(pin: String = PIN) {
        pin.forEach { compose.onNodeWithTag(PayFlowTestTags.lockKey(it.toString())).performClick() }
    }

    private fun unlock() {
        enterPin()
        awaitHome()
    }

    private fun assertNothingBehindTheLock() {
        assertThat(exists(keyOne)).isTrue()
        assertThat(exists(PayFlowTestTags.HOME_SCAN)).isFalse()
        assertThat(exists(PayFlowTestTags.PAYMENT_LIST)).isFalse()
        assertThat(exists(PayFlowTestTags.EMPTY)).isFalse()
        assertThat(exists(PayFlowTestTags.PAY_VPA)).isFalse()
        assertThat(exists(PayFlowTestTags.SECURITY_REMOVE_PIN)).isFalse()
    }

    @Test
    fun coldStartWithAPinShowsOnlyTheLock() {
        setPin()
        launch().use {
            awaitLock()
            assertNothingBehindTheLock()
        }
    }

    @Test
    fun incomingLinkWaitsBehindTheLockAndOpensPayOnlyAfterThePin() {
        setPin()
        launch(viewIntent("upi://pay?pa=test@upi&pn=Test%20Shop&am=1.00&cu=INR")).use {
            awaitLock()
            assertNothingBehindTheLock()
            enterPin("000000")
            compose.waitUntilWithMainLooper(10_000) { textExists("Wrong PIN. 4 attempts left") }
            assertNothingBehindTheLock()
            enterPin()
            compose.waitUntilWithMainLooper(10_000) { exists(PayFlowTestTags.PAY_VPA) }
            compose.onNodeWithTag(PayFlowTestTags.PAY_VPA).assertIsNotEnabled()
        }
    }

    @Test
    fun aLinkDeliveredToARunningLockedActivityWaitsToo() {
        setPin()
        launch().use { scenario ->
            awaitLock()
            scenario.onActivity { deliverNewIntent(it, viewIntent("upi://pay?pa=test@upi&pn=Shop&am=1.00&cu=INR")) }
            compose.waitForIdle()
            assertNothingBehindTheLock()
            unlockThenExpectPay()
        }
    }

    /** What the system does for a singleTop activity already on top. */
    private fun deliverNewIntent(activity: MainActivity, intent: Intent) {
        MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }.invoke(activity, intent)
    }

    private fun unlockThenExpectPay() {
        enterPin()
        compose.waitUntilWithMainLooper(10_000) { exists(PayFlowTestTags.PAY_VPA) }
    }

    /** Navigation gives every destination an implicit deep link; the activity is exported, so anyone can send this. */
    @Test
    fun aNavigationRouteDeepLinkCannotOpenAProtectedScreen() {
        withActivity(viewIntent("android-app://androidx.navigation/com.darshan.payflow.ui.navigation.SecurityDestination")) { activity ->
            awaitHome()
            compose.waitForIdle()
            assertThat(activity.intent.data).isNull()
            assertThat(exists(PayFlowTestTags.SECURITY_REMOVE_PIN) || exists(PayFlowTestTags.SECURITY_SETUP_PIN)).isFalse()
        }
    }

    /** A crafted route would prefill Pay without ever passing through the link parser. */
    @Test
    fun aCraftedPayRouteCannotSkipTheLinkParser() {
        val route = "android-app://androidx.navigation/com.darshan.payflow.ui.navigation.PayDestination" +
            "?vpa=evil@upi&name=Trusted&amountPaise=9999900&source=qr"
        withActivity(viewIntent(route)) { activity ->
            awaitHome()
            compose.waitForIdle()
            assertThat(activity.intent.data).isNull()
            assertThat(exists(PayFlowTestTags.PAY_VPA)).isFalse()
        }
    }

    /** The other way into Navigation's deep links: destination ids in extras. */
    @Test
    fun navigationDeepLinkExtrasAreStrippedBeforeNavigationSeesThem() {
        val ids = intArrayOf(routeId<SecurityDestination>())
        val intent = Intent(Intent.ACTION_MAIN).setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("android-support-nav:controller:deepLinkIds", ids)
        launch(intent).use { scenario ->
            awaitHome()
            val opened = runCatching { compose.waitUntilWithMainLooper(3_000) { exists(PayFlowTestTags.SECURITY_SETUP_PIN) } }.isSuccess
            assertThat(opened).isFalse()
            scenario.onActivity { activity ->
                assertThat(activity.intent.hasExtra("android-support-nav:controller:deepLinkIds")).isFalse()
            }
        }
    }

    @Test
    fun backOnTheLockScreenRevealsNothing() {
        setPin()
        launch().use { scenario ->
            awaitLock()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            if (scenario.state != Lifecycle.State.DESTROYED) assertNothingBehindTheLock()
        }
    }

    @Test
    fun aPredictiveBackGestureOnTheLockScreenRevealsNothingAtAnyPoint() {
        setPin()
        launch().use { scenario ->
            awaitLock()
            scenario.onActivity { activity ->
                val back = activity.onBackPressedDispatcher
                back.dispatchOnBackStarted(gesture(0f))
                back.dispatchOnBackProgressed(gesture(0.5f))
            }
            compose.waitForIdle()
            assertNothingBehindTheLock()
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(gesture(0.95f)) }
            compose.waitForIdle()
            assertNothingBehindTheLock()
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
            compose.waitForIdle()
            assertNothingBehindTheLock()
        }
    }

    /** A back gesture in progress must not hold the screen behind it on the display when the lock lands. */
    @Test
    fun aRelockDuringAPredictiveBackGestureRemovesBothScreens() {
        setPin()
        launch().use { scenario ->
            awaitLock()
            unlock()
            compose.onNodeWithTag(PayFlowTestTags.HOME_MANDATES).performScrollTo().performClick()
            compose.waitUntilWithMainLooper(10_000) { exists(PayFlowTestTags.MANDATE_CREATE) }
            compose.waitForIdle()
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.dispatchOnBackStarted(gesture(0f))
                activity.onBackPressedDispatcher.dispatchOnBackProgressed(gesture(0.5f))
            }
            compose.waitForIdle()
            scenario.onActivity { appLock.lock() }
            awaitLock()
            assertNothingBehindTheLock()
            assertThat(exists(PayFlowTestTags.MANDATE_CREATE)).isFalse()
        }
    }

    /** Every tab, and a screen reached from one, keeps the window secure with no change at any step. */
    @Test
    fun theWindowStaysSecureOnEveryTabAndThroughTheChanges() {
        launch().use { scenario ->
            awaitHome()
            fun assertSecure() = scenario.onActivity {
                assertThat(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE).isNotEqualTo(0)
                assertThat(it.window.decorView.filterTouchesWhenObscured).isTrue()
            }
            assertSecure()
            compose.onNodeWithTag(PayFlowTestTags.HOME_HISTORY).performClick()
            compose.waitForIdle()
            assertSecure()
            compose.onNodeWithTag(PayFlowTestTags.NAV_ME).performClick()
            compose.waitForIdle()
            assertSecure()
            compose.onNodeWithTag(PayFlowTestTags.NAV_HOME).performClick()
            compose.waitForIdle()
            assertSecure()
        }
    }

    /** Back on the lock finishes the activity on older phones. Reopening in the same process must not skip the lock. */
    @Test
    fun finishingTheLockedActivityAndReopeningStaysLocked() {
        setPin()
        launch().use { scenario ->
            awaitLock()
            unlock()
            scenario.onActivity { appLock.lock() }
            awaitLock()
            scenario.onActivity(Activity::finish)
        }
        launch().use {
            awaitLock()
            compose.waitForIdle()
            assertNothingBehindTheLock()
        }
    }

    @Test
    fun relockFromAnyScreenRemovesItAtOnce() {
        setPin()
        launch().use { scenario ->
            awaitLock()
            unlock()
            scenario.onActivity { appLock.lock() }
            awaitLock()
            assertNothingBehindTheLock()
        }
    }

    @Test
    fun unlockingAfterARelockReturnsToTheSameScreenAndBackStillWorks() {
        setPin()
        launch().use { scenario ->
            awaitLock()
            unlock()
            compose.onNodeWithTag(PayFlowTestTags.HOME_MANDATES).performScrollTo().performClick()
            compose.waitUntilWithMainLooper(10_000) { exists(PayFlowTestTags.MANDATE_CREATE) }
            scenario.onActivity { appLock.lock() }
            awaitLock()
            assertThat(exists(PayFlowTestTags.MANDATE_CREATE)).isFalse()
            enterPin()
            compose.waitUntilWithMainLooper(10_000) { exists(PayFlowTestTags.MANDATE_CREATE) }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitHome()
        }
    }

    @Test
    fun theLockedWindowStaysSecure() {
        setPin()
        launch().use { scenario ->
            awaitLock()
            scenario.onActivity { assertThat(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE).isNotEqualTo(0) }
            scenario.onActivity { assertThat(it.window.decorView.filterTouchesWhenObscured).isTrue() }
        }
    }

    @Test
    fun theHomeWindowIsSecureSoTheRecentsThumbnailShowsNothing() {
        launch().use { scenario ->
            awaitHome()
            scenario.onActivity { assertThat(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE).isNotEqualTo(0) }
        }
    }

    @Test
    fun fiveWrongPinsLockTheKeypadAndNothingOpens() {
        setPin()
        launch().use {
            awaitLock()
            repeat(PinManager.MAX_ATTEMPTS) { attempt ->
                enterPin("000000")
                compose.waitUntilWithMainLooper(10_000) {
                    if (attempt == PinManager.MAX_ATTEMPTS - 1) textExists("Too many attempts", substring = true)
                    else textExists("Wrong PIN. ${PinManager.MAX_ATTEMPTS - attempt - 1} attempts left")
                }
            }
            compose.onNodeWithTag(keyOne).assertIsNotEnabled()
            assertNothingBehindTheLock()
        }
    }

    private fun textExists(text: String, substring: Boolean) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun gesture(progress: Float) = BackEventCompat(0f, 100f, progress, BackEventCompat.EDGE_LEFT)

    /** Navigation's own route id: the serial name and element names hashed together. */
    private inline fun <reified T> routeId(): Int {
        val descriptor = serializer<T>().descriptor
        var hash = descriptor.serialName.hashCode()
        for (i in 0 until descriptor.elementsCount) hash = 31 * hash + descriptor.getElementName(i).hashCode()
        return hash
    }

    private companion object {
        const val PIN = "493027"
    }
}
