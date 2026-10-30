package com.darshan.payflow.ui.receive

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.ProfileFieldError
import com.darshan.payflow.domain.model.UpiProfile
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ReceiveScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun show(state: ReceiveUiState, callbacks: ReceiveCallbacks = ReceiveCallbacks(), dark: Boolean = false) {
        rule.setContent { PayFlowTheme(darkTheme = dark) { ReceiveScreen(state, callbacks) } }
    }

    @Test
    fun loadingThenSetupForm() {
        show(ReceiveUiState(loading = true))
        rule.onNodeWithTag(PayFlowTestTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun setupFormValidationAndSave() {
        var name = ""; var saved = false
        show(
            ReceiveUiState(loading = false, editing = true, vpaError = ProfileFieldError.INVALID_VPA),
            ReceiveCallbacks(onNameChange = { name = it }, onSave = { saved = true }),
        )
        rule.onNodeWithText("Set up your UPI ID").assertIsDisplayed()
        rule.onNodeWithText("Enter a UPI ID like name@bank").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.RECEIVE_NAME).performTextInput("Darshan")
        assertThat(name).isEqualTo("Darshan")
        rule.onNodeWithTag(PayFlowTestTags.RECEIVE_SAVE).performClick()
        assertThat(saved).isTrue()
    }

    @Test
    fun qrCardShowsProfileAmountAndActions() {
        var copied = false; var sharedText = false; var sharedImage = false; var edit = false
        show(
            ReceiveUiState(loading = false, profile = UpiProfile("Darshan Sonawane", "darshan@okaxis"), amountText = "150"),
            ReceiveCallbacks(onCopy = { copied = true }, onShareText = { sharedText = true }, onShareImage = { sharedImage = true }, onEdit = { edit = true }),
            dark = true,
        )
        rule.onNodeWithText("Darshan Sonawane").assertIsDisplayed()
        rule.onNodeWithText("darshan@okaxis").assertIsDisplayed()
        rule.onNodeWithContentDescription("UPI QR code for darshan@okaxis").assertIsDisplayed()
        rule.onNodeWithText("₹150.00").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.RECEIVE_COPY).performClick()
        rule.onNodeWithTag(PayFlowTestTags.RECEIVE_SHARE).performScrollTo().performClick()
        rule.onNodeWithTag(PayFlowTestTags.RECEIVE_SHARE_IMAGE).performScrollTo().performClick()
        rule.onNodeWithTag(PayFlowTestTags.RECEIVE_EDIT).performClick()
        assertThat(listOf(copied, sharedText, sharedImage, edit)).containsExactly(true, true, true, true)
    }
}
