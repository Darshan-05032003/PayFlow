package com.darshan.payflow.integration

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.darshan.payflow.ui.PayFlowTestTags
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.MainActivity
import com.darshan.payflow.testutil.waitUntilWithMainLooper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The real Hilt graph boots the activity; navigation home → history works. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = HiltTestApplication::class)
class MainActivityTest {
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesHomeAndNavigatesToHistory() {
        hiltRule.inject()
        composeRule.onNodeWithText("PayFlow").assertIsDisplayed()
        composeRule.waitUntilWithMainLooper(5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag(PayFlowTestTags.EMPTY)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(PayFlowTestTags.HOME_HISTORY).assertIsDisplayed().performClick() // History in the bottom bar
        composeRule.waitUntilWithMainLooper(5_000) {
            composeRule.onAllNodesWithText("PayFlow").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag(PayFlowTestTags.HOME_HISTORY).assertIsSelected()
        composeRule.onAllNodesWithText("History").assertCountEquals(2) // the screen's title and the bar's label
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("PayFlow").assertIsDisplayed()
        composeRule.onNodeWithTag(PayFlowTestTags.NAV_HOME).assertIsSelected()
    }
}
