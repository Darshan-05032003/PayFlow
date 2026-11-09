package com.darshan.payflow.ui.designsystem

import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.R
import com.darshan.payflow.ui.designsystem.components.GhostButton
import com.darshan.payflow.ui.designsystem.components.PayFlowSurface
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.components.SurfaceLevel
import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.effects.ThemeMode
import com.darshan.payflow.ui.designsystem.effects.UiSettings
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionLevel
import com.darshan.payflow.ui.designsystem.motion.PayFlowMotion
import com.darshan.payflow.ui.designsystem.tokens.AurumDark
import com.darshan.payflow.ui.designsystem.tokens.AurumLight
import com.darshan.payflow.ui.designsystem.tokens.Geist
import com.darshan.payflow.ui.designsystem.tokens.Newsreader
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ThemeTest {
    @get:Rule val rule = createComposeRule()

    private val resolver get() = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

    @After
    fun resetScale() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun motionFor(reduceMotion: Boolean): PayFlowMotion {
        lateinit var motion: PayFlowMotion
        rule.setContent {
            PayFlowTheme(reduceMotion = reduceMotion) { motion = LocalMotion.current }
        }
        return motion
    }

    @Test
    fun the_theme_provides_full_motion_by_default() {
        assertThat(motionFor(false).level).isEqualTo(MotionLevel.Full)
    }

    @Test
    fun the_setting_reduces_motion_through_the_local() {
        assertThat(motionFor(true).level).isEqualTo(MotionLevel.Reduced)
    }

    @Test
    fun system_animator_scale_zero_reduces_motion() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertThat(motionFor(false).level).isEqualTo(MotionLevel.Reduced)
    }

    @Test
    fun system_animator_scale_three_keeps_full_motion() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 3f)
        assertThat(motionFor(false).level).isEqualTo(MotionLevel.Full)
    }

    @Test
    fun material_slots_follow_the_tokens_in_both_themes() {
        var light: Color? = null
        var lightPrimary: Color? = null
        var content: Color? = null
        rule.setContent {
            PayFlowTheme(darkTheme = false) {
                light = MaterialTheme.colorScheme.background
                lightPrimary = MaterialTheme.colorScheme.primary
                content = LocalContentColor.current
            }
        }
        assertThat(light).isEqualTo(AurumLight.bg)
        assertThat(lightPrimary).isEqualTo(AurumLight.primary)
        assertThat(content).isEqualTo(AurumLight.text)
    }

    @Test
    fun dark_slots_and_status_palette_map_to_dark_tokens() {
        var bg: Color? = null
        var success: Color? = null
        var family: Any? = null
        var titleFamily: Any? = null
        rule.setContent {
            PayFlowTheme(darkTheme = true) {
                bg = MaterialTheme.colorScheme.background
                success = PayFlowTheme.status.success.content
                family = MaterialTheme.typography.bodyLarge.fontFamily
                titleFamily = MaterialTheme.typography.displaySmall.fontFamily
            }
        }
        assertThat(bg).isEqualTo(AurumDark.bg)
        assertThat(success).isEqualTo(AurumDark.success.content)
        assertThat(family).isEqualTo(Geist)
        assertThat(titleFamily).isEqualTo(Newsreader)
    }

    @Test
    fun the_stored_setting_picks_the_theme_whatever_the_system_says() {
        var light: Boolean? = null
        var dark: Boolean? = null
        rule.setContent {
            PayFlowTheme(UiSettings(theme = ThemeMode.Light)) { light = PayFlowTheme.colors.isDark }
            PayFlowTheme(UiSettings(theme = ThemeMode.Dark)) { dark = PayFlowTheme.colors.isDark }
        }
        assertThat(light).isFalse()
        assertThat(dark).isTrue()
    }

    @Test
    fun the_haptics_setting_silences_the_local_for_all_components() {
        val fake = FakeHaptics()
        rule.setContent {
            PayFlowTheme(hapticsEnabled = false, haptics = fake) { LocalHaptics.current.confirm() }
        }
        assertThat(fake.events).isEmpty()
    }

    @Test
    fun the_haptics_setting_on_passes_events() {
        val fake = FakeHaptics()
        rule.setContent {
            PayFlowTheme(haptics = fake) { LocalHaptics.current.confirm() }
        }
        assertThat(fake.events).containsExactly(HapticEvent.Confirm)
    }

    @Test
    fun the_fonts_exist_in_the_resources_under_lowercase_names() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertThat(context.resources.getResourceEntryName(R.font.newsreader_variable)).isEqualTo("newsreader_variable")
        assertThat(context.resources.getResourceEntryName(R.font.geist_variable)).isEqualTo("geist_variable")
    }
}

/**
 * The same components under Robolectric's default graphics mode, which most screen tests use: AGSL paths must not crash a
 * test that never asked for native graphics.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LegacyGraphicsCompatibilityTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun metallic_and_hero_components_render_in_the_default_mode() {
        rule.setDs(dark = true) {
            Column {
                PrimaryButton("Pay", {})
                GhostButton("Later", {})
                PayFlowSurface(level = SurfaceLevel.Hero) {}
            }
        }
        rule.waitForIdle()
    }

    /** Design QA: the hero is graphite in both themes, so its text and whatever sits on it look the same in both. */
    @Test
    fun hero_content_reads_the_same_palette_in_both_themes() {
        assertThat(AurumLight.onHero).isEqualTo(AurumDark.onHero)
        val seen = mutableMapOf<Boolean, Pair<Color, Color>>()
        var dark by mutableStateOf(false)
        rule.setContent {
            PayFlowTheme(darkTheme = dark) {
                PayFlowSurface(level = SurfaceLevel.Hero) {
                    seen[dark] = PayFlowTheme.colors.primary to LocalContentColor.current
                }
            }
        }
        rule.waitForIdle()
        dark = true
        rule.waitForIdle()
        assertThat(seen[false]).isEqualTo(seen[true])
        assertThat(seen.getValue(false).first).isEqualTo(AurumDark.primary)
        assertThat(seen.getValue(false).second).isEqualTo(AurumDark.onHero)
    }
}
