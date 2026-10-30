package com.darshan.payflow.ui.components

import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.BuildConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SecureWindowTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun secureFlag() = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE

    @Test
    fun `screenshot mode can only exist in debug builds`() {
        // Release always compiles ALLOW_SCREENSHOTS = false (build.gradle.kts); the gate runs without -PscreenshotMode.
        assertThat(BuildConfig.DEBUG || !BuildConfig.ALLOW_SCREENSHOTS).isTrue()
        assertThat(BuildConfig.ALLOW_SCREENSHOTS).isFalse()
    }

    @Test
    fun `default secures the window unless screenshot mode is on`() {
        rule.setContent { SecureWindow() }
        rule.waitForIdle()
        if (BuildConfig.ALLOW_SCREENSHOTS) assertThat(secureFlag()).isEqualTo(0) else assertThat(secureFlag()).isNotEqualTo(0)
    }

    @Test
    fun `disabled secure window never sets the flag`() {
        rule.setContent { SecureWindow(enabled = false) }
        rule.waitForIdle()
        assertThat(secureFlag()).isEqualTo(0)
    }

    @Test
    fun `toggling enabled applies and clears the flag`() {
        val enabled = mutableStateOf(true)
        rule.setContent { SecureWindow(enabled = enabled.value) }
        rule.waitForIdle()
        assertThat(secureFlag()).isNotEqualTo(0)
        enabled.value = false
        rule.waitForIdle()
        assertThat(secureFlag()).isEqualTo(0)
        enabled.value = true
        rule.waitForIdle()
        assertThat(secureFlag()).isNotEqualTo(0)
    }

    @Test
    fun `nested secure screens keep the flag until the last one leaves`() {
        val inner = mutableStateOf(true)
        rule.setContent {
            SecureWindow(enabled = true)
            if (inner.value) SecureWindow(enabled = true)
        }
        rule.waitForIdle()
        inner.value = false
        rule.waitForIdle()
        assertThat(secureFlag()).isNotEqualTo(0)
    }
}
