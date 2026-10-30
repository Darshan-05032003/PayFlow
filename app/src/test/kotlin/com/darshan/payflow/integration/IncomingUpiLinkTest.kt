package com.darshan.payflow.integration

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.MainActivity
import com.darshan.payflow.testutil.waitUntilWithMainLooper
import com.darshan.payflow.ui.PayFlowTestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A `upi://pay` VIEW intent from another app opens PayFlow on the Pay screen, prefilled and locked; junk links land on Home. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = HiltTestApplication::class)
class IncomingUpiLinkTest {
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createEmptyComposeRule()

    private fun launch(data: String) = ActivityScenario.launch<MainActivity>(
        Intent(Intent.ACTION_VIEW, data.toUri()).setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java),
    )

    @Test
    fun payLinkOpensThePayScreenPrefilledAndLocked() {
        hiltRule.inject()
        launch("upi://pay?pa=test@upi&pn=Test%20Shop&am=1.00&cu=INR&tn=Chai").use {
            composeRule.waitUntilWithMainLooper(10_000) { composeRule.onAllNodes(hasTestTag(PayFlowTestTags.PAY_VPA)).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag(PayFlowTestTags.PAY_VPA).assertIsNotEnabled()
            composeRule.onNodeWithTag(PayFlowTestTags.PAY_AMOUNT).assertIsNotEnabled()
            composeRule.onNodeWithText("test@upi").assertIsDisplayed()
            composeRule.onNodeWithText("Pay ₹1.00").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun aHostileLinkIsDroppedAndHomeOpens() {
        hiltRule.inject()
        launch("upi://pay?pa=javascript:alert(1)@x&am=-5").use {
            composeRule.waitUntilWithMainLooper(10_000) { composeRule.onAllNodes(hasTestTag(PayFlowTestTags.EMPTY)).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText("PayFlow").assertIsDisplayed()
            composeRule.onAllNodes(hasTestTag(PayFlowTestTags.PAY_VPA)).fetchSemanticsNodes().let { assert(it.isEmpty()) }
        }
    }
}
