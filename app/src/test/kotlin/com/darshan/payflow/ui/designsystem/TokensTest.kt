package com.darshan.payflow.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darshan.payflow.ui.designsystem.tokens.AurumDark
import com.darshan.payflow.ui.designsystem.tokens.AurumLight
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.PayFlowColors
import com.darshan.payflow.ui.designsystem.tokens.PayFlowTokens
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class TokensTest {
    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(a.luminance(), b.luminance()).toDouble()
        val lo = minOf(a.luminance(), b.luminance()).toDouble()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun textPairs(c: PayFlowColors): List<Triple<String, Color, Color>> = buildList {
        for ((bgName, bg) in listOf("bg" to c.bg, "surface" to c.surface, "raised" to c.raised)) {
            add(Triple("text/$bgName", c.text, bg))
            add(Triple("textSecondary/$bgName", c.textSecondary, bg))
            add(Triple("accent/$bgName", c.accent, bg))
        }
        for ((bgName, bg) in listOf("bg" to c.bg, "surface" to c.surface)) {
            add(Triple("success/$bgName", c.success.strong, bg))
            add(Triple("pending/$bgName", c.pending.strong, bg))
            add(Triple("failed/$bgName", c.failed.strong, bg))
            add(Triple("refund/$bgName", c.refund.strong, bg))
        }
        add(Triple("onPrimary/primary", c.onPrimary, c.primary))
        add(Triple("onPrimary/primaryPressed", c.onPrimary, c.primaryPressed))
        add(Triple("onPrimary/primaryTop", c.onPrimary, c.primaryTop))
        add(Triple("onPrimary/primaryBottom", c.onPrimary, c.primaryBottom))
        add(Triple("onPrimaryDisabled/primaryDisabled", c.onPrimaryDisabled, c.primaryDisabled))
        add(Triple("onTactile/tactileCap", c.onTactile, c.tactileCap))
        add(Triple("onToast/toast", c.onToast, c.toast))
        add(Triple("onHero/heroStart", c.onHero, c.heroStart))
        add(Triple("onHero/heroEnd", c.onHero, c.heroEnd))
        add(Triple("accent/accentTint", c.onAccentTint, c.accentTint))
        for ((name, tone) in listOf("success" to c.success, "pending" to c.pending, "failed" to c.failed, "refund" to c.refund, "neutral" to c.neutral)) {
            add(Triple("$name chip", tone.content, tone.container))
        }
        c.avatarTones.forEachIndexed { i, t -> add(Triple("avatar $i", t.content, t.container)) }
    }

    @Test
    fun every_light_text_pair_meets_wcag_aa() = assertPairs(AurumLight)

    @Test
    fun every_dark_text_pair_meets_wcag_aa() = assertPairs(AurumDark)

    private fun assertPairs(c: PayFlowColors) {
        for ((name, fg, bg) in textPairs(c)) {
            assertWithMessage("$name contrast").that(contrast(fg, bg)).isAtLeast(4.5)
        }
    }

    @Test
    fun input_outline_reaches_three_to_one_against_every_surface() {
        for (c in listOf(AurumLight, AurumDark)) {
            for (bg in listOf(c.bg, c.surface)) assertThat(contrast(c.outline, bg)).isAtLeast(3.0)
        }
    }

    @Test
    fun palette_keeps_the_exact_direction_values() {
        assertThat(AurumLight.bg).isEqualTo(Color(0xFFF6F3EC))
        assertThat(AurumLight.primary).isEqualTo(Color(0xFF17181C))
        assertThat(AurumLight.onPrimary).isEqualTo(Color(0xFFEFD9AE))
        assertThat(AurumLight.accent).isEqualTo(Color(0xFF7A581C))
        assertThat(AurumLight.success.strong).isEqualTo(Color(0xFF1B7A4C))
        assertThat(AurumDark.bg).isEqualTo(Color(0xFF0B0C0F))
        assertThat(AurumDark.surface).isEqualTo(Color(0xFF15171B))
        assertThat(AurumDark.raised).isEqualTo(Color(0xFF1F2228))
        assertThat(AurumDark.primary).isEqualTo(Color(0xFFE2C48D))
        assertThat(AurumDark.failed.strong).isEqualTo(Color(0xFFF2948A))
    }

    @Test
    fun spacing_sits_on_the_four_point_grid() {
        val all: List<Dp> = with(Spacing) { listOf(xxs, xs, sm, md, lg, xl, xxl, xxxl, huge, screen) }
        for (value in all) assertThat(value.value % 4f).isEqualTo(0f)
    }

    @Test
    fun radii_are_the_three_direction_values() {
        assertThat(Radii.small).isEqualTo(12.dp)
        assertThat(Radii.medium).isEqualTo(20.dp)
        assertThat(Radii.large).isEqualTo(28.dp)
        assertThat(Hairline.width).isEqualTo(1.dp)
    }

    @Test
    fun touch_target_token_is_at_least_48dp() {
        assertThat(Sizes.touchTarget).isAtLeast(48.dp)
        assertThat(PayFlowTokens.spacing).isSameInstanceAs(Spacing)
    }
}
