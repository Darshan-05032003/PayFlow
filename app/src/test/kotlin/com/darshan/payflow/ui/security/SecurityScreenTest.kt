package com.darshan.payflow.ui.security

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SecurityScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun paymentModeToggleExplainsBothModesAndCallsBack() {
        var chosen: PaymentMode? = null
        rule.setContent {
            PayFlowTheme {
                SecurityScreen(
                    SecurityUiState(loading = false, pinConfigured = true, paymentMode = PaymentMode.UPI_APPS),
                    SecurityCallbacks(onPaymentMode = { chosen = it }),
                )
            }
        }
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_MODE_UPI).assertIsSelected()
        rule.onNodeWithText("Pay through GPay, PhonePe, Paytm or any UPI app on this phone. Real money moves. The result is reported by that app; PayFlow cannot verify it with a bank.").assertIsDisplayed()
        rule.onNodeWithText("This setting is protected by the app lock.").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_MODE_SANDBOX).performScrollTo().performClick()
        assertThat(chosen).isEqualTo(PaymentMode.SANDBOX)
    }

    @Test
    fun upiAppsOptionIsDisabledWithoutAHandlerAndSandboxShowsTheBadge() {
        rule.setContent {
            PayFlowTheme {
                SecurityScreen(SecurityUiState(loading = false, paymentMode = PaymentMode.SANDBOX, upiAppAvailable = false), SecurityCallbacks())
            }
        }
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_MODE_UPI).assertIsNotEnabled()
        rule.onNodeWithText("No UPI app is installed on this device, so UPI apps mode cannot be used.").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.SANDBOX_BADGE).assertIsDisplayed()
    }
}
