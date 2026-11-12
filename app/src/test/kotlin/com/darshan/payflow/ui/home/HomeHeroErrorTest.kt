package com.darshan.payflow.ui.home

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.assertInside
import com.darshan.payflow.ui.designsystem.assertTextWhole
import com.darshan.payflow.ui.designsystem.setDs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * When the month's total can't be read, the hero keeps the shape of a money figure (a dash where the digits go) and says
 * what failed on the line under it, instead of a sentence in the money face above an empty line.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class HomeHeroErrorTest(private val fontScale: Float, private val reduceMotion: Boolean) {
    @get:Rule val rule = createComposeRule()

    @Test
    fun anUnreadableTotalIsADashAndALineThatSaysWhy() {
        rule.setDs(fontScale = fontScale, reduceMotion = reduceMotion) { HomeScreen(HomeUiState.Error("db"), HomeCallbacks()) }
        rule.onNodeWithTag(PayFlowTestTags.HOME_HERO_UNAVAILABLE)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("This month's total isn't available")
        rule.onNodeWithText("Couldn't load this month's total.", useUnmergedTree = true).assertTextWhole()
        rule.onNodeWithText("Couldn't load this month's total.").assertInside(rule.onNodeWithTag(PayFlowTestTags.HOME_HERO))
        rule.onAllNodesWithText("Not available").assertCountEquals(0)
        rule.onAllNodesWithText("₹0").assertCountEquals(0)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x reduced={1}")
        fun cases() = listOf(arrayOf<Any>(1f, false), arrayOf<Any>(1.3f, false), arrayOf<Any>(2f, false), arrayOf<Any>(1f, true))
    }
}
