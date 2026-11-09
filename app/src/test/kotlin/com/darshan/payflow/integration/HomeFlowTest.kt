package com.darshan.payflow.integration

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.MainActivity
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.repository.PaymentRepository
import com.darshan.payflow.testutil.runOffMain
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.testutil.waitUntilWithMainLooper
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.effects.UiPreferences
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Home on the real graph: the choices that have to reach storage, the provider and the rest of the app. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = HiltTestApplication::class, qualifiers = "w411dp-h891dp")
class HomeFlowTest {
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var payments: PaymentRepository
    @Inject lateinit var preferences: UiPreferences

    @Before
    fun setUp() {
        hiltRule.inject()
        compose.waitUntilWithMainLooper(10_000) { exists(PayFlowTestTags.HOME_SCAN) }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun hidingAmountsIsStoredAndSurvivesRecreation() {
        runOffMain { payments.create(testPayment(id = "p1", status = PaymentStatus.SUCCESS, amountPaise = 77_700)) }
        compose.waitUntilWithMainLooper(10_000) { exists(PayFlowTestTags.HOME_HERO_AMOUNT) }
        compose.onNodeWithContentDescription("Hide amounts").performClick()
        compose.waitUntilWithMainLooper(5_000) { preferences.settings.value.amountsHidden }
        compose.waitUntilWithMainLooper(5_000) { compose.onAllNodes(hasContentDescription("Show amounts")).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntilWithMainLooper(10_000) { exists(PayFlowTestTags.HOME_HERO_AMOUNT) }
        compose.onNodeWithContentDescription("Show amounts").assertExists()
        assertThat(compose.onAllNodes(hasText("₹777")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun aPullRefreshesThroughTheRealViewModelAndSaysWhatHappened() {
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeDown(startY = centerY / 2, endY = bottom, durationMillis = 400) }
        compose.waitUntilWithMainLooper(10_000) { compose.onAllNodes(hasText("Nothing new to check")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theScanActionOpensTheScannerAndTheHeroAmountRemains() {
        runOffMain { payments.create(testPayment(id = "p1", status = PaymentStatus.SUCCESS)) }
        compose.waitUntilWithMainLooper(10_000) { exists(PayFlowTestTags.HOME_HERO_AMOUNT) }
        compose.onNodeWithTag(PayFlowTestTags.HOME_HERO_AMOUNT).assertIsDisplayed()
        compose.onNodeWithTag(PayFlowTestTags.HOME_SCAN).performClick()
        compose.waitUntilWithMainLooper(10_000) { !exists(PayFlowTestTags.HOME_SCAN) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilWithMainLooper(10_000) { exists(PayFlowTestTags.HOME_SCAN) }
    }
}
