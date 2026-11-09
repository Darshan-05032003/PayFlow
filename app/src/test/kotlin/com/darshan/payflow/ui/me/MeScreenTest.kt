package com.darshan.payflow.ui.me

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.UpiProfile
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.FakeHaptics
import com.darshan.payflow.ui.designsystem.FakeUiSound
import com.darshan.payflow.ui.designsystem.assertInside
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.effects.InMemoryUiPreferences
import com.darshan.payflow.ui.designsystem.effects.LocalUiSound
import com.darshan.payflow.ui.designsystem.effects.ThemeMode
import com.darshan.payflow.ui.designsystem.effects.UiSettings
import com.darshan.payflow.ui.designsystem.effects.UiSoundEvent
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class MeScreenTest {
    @get:Rule val rule = createComposeRule()
    private val profile = UpiProfile("Ira Kulkarni", "ira@okaxis")

    @Test
    fun linksAndTogglesCallBack() {
        val calls = mutableListOf<String>()
        rule.setDs {
            MeScreen(
                MeUiState(loading = false, profile = profile, pinConfigured = true),
                MeCallbacks(
                    onSecurity = { calls += "security" }, onReceive = { calls += "receive" },
                    onHaptics = { calls += "haptics=$it" }, onSound = { calls += "sound=$it" },
                    onReduceMotion = { calls += "reduce=$it" }, onTheme = { calls += "theme=$it" },
                ),
                version = "1.0",
            )
        }
        rule.onNodeWithText("Ira Kulkarni").assertIsDisplayed()
        rule.onNodeWithText("App lock on · checks and payment mode").assertExists()
        rule.onNodeWithTag(PayFlowTestTags.ME_SECURITY).performClick()
        rule.onNodeWithTag(PayFlowTestTags.ME_RECEIVE).performClick()
        rule.onNodeWithTag(PayFlowTestTags.ME_HAPTICS).performScrollTo().assertIsOn().performClick()
        rule.onNodeWithTag(PayFlowTestTags.ME_SOUND).performScrollTo().assertIsOff().performClick()
        rule.onNodeWithTag(PayFlowTestTags.ME_REDUCE_MOTION).performScrollTo().assertIsOff().performClick()
        rule.onNodeWithTag(PayFlowTestTags.meTheme("System")).performScrollTo().assertIsSelected()
        rule.onNodeWithTag(PayFlowTestTags.meTheme("Dark")).performScrollTo().performClick()
        assertThat(calls).containsExactly("security", "receive", "haptics=false", "sound=true", "reduce=true", "theme=Dark").inOrder()
    }

    @Test
    fun withoutAProfileTheOnePrimaryActionSetsItUp() {
        var receive = 0
        rule.setDs { MeScreen(MeUiState(loading = false, profile = null, pinConfigured = false), MeCallbacks(onReceive = { receive++ }), version = "1.0") }
        rule.onNodeWithText("No UPI ID yet").assertIsDisplayed()
        rule.onNodeWithText("App lock is off").assertExists()
        rule.onNodeWithTag(PayFlowTestTags.ME_SETUP_PROFILE).performClick()
        assertThat(receive).isEqualTo(1)
    }

    @Test
    fun aboutOpensInAnInWindowSheet() {
        rule.setDs { MeScreen(MeUiState(loading = false, profile = profile), MeCallbacks(), version = "1.0") }
        rule.onNodeWithTag(PayFlowTestTags.ME_ABOUT).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Motion and craft inspired by best-in-class Indian consumer apps; not affiliated with any of them.").assertExists()
        // Content behind the sheet is out of TalkBack's reach while it's open.
        assertThat(rule.onAllNodes(androidx.compose.ui.test.hasTestTag(PayFlowTestTags.ME_HAPTICS)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun largeTextKeepsEveryToggleOnScreen() {
        rule.setDs(fontScale = 1.3f) { MeScreen(MeUiState(loading = false, profile = profile), MeCallbacks(), version = "1.0") }
        listOf(PayFlowTestTags.ME_HAPTICS, PayFlowTestTags.ME_SOUND, PayFlowTestTags.ME_REDUCE_MOTION, PayFlowTestTags.meTheme("Dark")).forEach {
            rule.onNodeWithTag(it).performScrollTo().assertInside(rule.onRoot())
        }
    }

    /**
     * The toggles take effect app-wide: this composes the theme exactly as MainActivity does (stored settings in, the chime
     * passed as the sound), with Me and a probe from "elsewhere in the app" inside it.
     */
    @Test
    fun everyToggleTakesEffectAcrossTheApp() {
        val prefs = InMemoryUiPreferences()
        val haptics = FakeHaptics()
        val sound = FakeUiSound()
        var probeClicks = 0
        rule.setContent {
            val settings by prefs.settings.collectAsState()
            PayFlowTheme(settings, sound = sound, haptics = haptics) {
                val motion = LocalMotion.current
                val dark = PayFlowTheme.colors.isDark
                val chime = LocalUiSound.current
                Column {
                    Box(Modifier.semantics { stateDescription = "reduced=${motion.reduced} dark=$dark" }.testTag("probe")) {
                        PrimaryButton("Elsewhere", { probeClicks++; chime.play(UiSoundEvent.Success) }, debounceMs = 0L)
                    }
                    MeScreen(
                        MeUiState(loading = false, profile = profile, settings = settings),
                        MeCallbacks(
                            onHaptics = { runBlocking { prefs.setHapticsEnabled(it) } },
                            onSound = { runBlocking { prefs.setSoundEnabled(it) } },
                            onReduceMotion = { runBlocking { prefs.setReduceMotion(it) } },
                            onTheme = { runBlocking { prefs.setTheme(it) } },
                        ),
                        version = "1.0",
                    )
                }
            }
        }
        fun probe() = rule.onNodeWithTag("probe").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        fun pressElsewhere() { ShadowSystemClock.advanceBy(Duration.ofMillis(600)); rule.onNodeWithText("Elsewhere").performClick(); rule.waitForIdle() }

        rule.onNodeWithTag(PayFlowTestTags.meTheme("Light")).performScrollTo().performClick()
        rule.waitForIdle()
        assertThat(probe()).isEqualTo("reduced=false dark=false")
        rule.onNodeWithTag(PayFlowTestTags.meTheme("Dark")).performScrollTo().performClick()
        rule.onNodeWithTag(PayFlowTestTags.ME_REDUCE_MOTION).performScrollTo().performClick()
        rule.waitForIdle()
        assertThat(probe()).isEqualTo("reduced=true dark=true")

        // Haptics on: a press elsewhere taps. Off: silence, from the same button.
        haptics.events.clear()
        pressElsewhere()
        assertThat(haptics.events).isNotEmpty()
        rule.onNodeWithTag(PayFlowTestTags.ME_HAPTICS).performScrollTo().performClick()
        rule.waitForIdle()
        haptics.events.clear()
        pressElsewhere()
        assertThat(haptics.events).isEmpty()

        // Sound is off by default; on, the same request plays.
        sound.events.clear()
        pressElsewhere()
        assertThat(sound.events).isEmpty()
        rule.onNodeWithTag(PayFlowTestTags.ME_SOUND).performScrollTo().performClick()
        rule.waitForIdle()
        pressElsewhere()
        assertThat(sound.events).containsExactly(UiSoundEvent.Success)
        assertThat(prefs.settings.value).isEqualTo(UiSettings(hapticsEnabled = false, soundEnabled = true, reduceMotion = true, theme = ThemeMode.Dark))
        assertThat(probeClicks).isEqualTo(4)
    }
}
