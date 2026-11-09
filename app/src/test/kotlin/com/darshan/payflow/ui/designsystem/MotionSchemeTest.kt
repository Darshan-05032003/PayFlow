package com.darshan.payflow.ui.designsystem

import com.darshan.payflow.ui.designsystem.motion.MotionDurations
import com.darshan.payflow.ui.designsystem.motion.MotionLevel
import com.darshan.payflow.ui.designsystem.motion.MotionPlan
import com.darshan.payflow.ui.designsystem.motion.MotionRole
import com.darshan.payflow.ui.designsystem.motion.MotionSprings
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.motion.PayFlowMotion
import com.darshan.payflow.ui.designsystem.motion.resolveMotionLevel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MotionSchemeTest {
    private val full = PayFlowMotion(MotionLevel.Full)
    private val reduced = PayFlowMotion(MotionLevel.Reduced)

    @Test
    fun durations_stay_in_the_documented_bands() {
        for (ms in listOf(MotionDurations.MicroFast, MotionDurations.Micro, MotionDurations.MicroSlow)) assertThat(ms).isIn(90..150)
        for (ms in listOf(MotionDurations.SmallFast, MotionDurations.Small, MotionDurations.SmallSlow)) assertThat(ms).isIn(200..300)
        for (ms in listOf(MotionDurations.ScreenFast, MotionDurations.Screen, MotionDurations.ScreenSlow)) assertThat(ms).isIn(350..500)
        assertThat(MotionDurations.Stagger).isEqualTo(40)
    }

    @Test
    fun springs_are_the_direction_values() {
        assertThat(MotionSprings.Snappy.stiffness).isEqualTo(1200f)
        assertThat(MotionSprings.Snappy.dampingRatio).isEqualTo(1.0f)
        assertThat(MotionSprings.Bouncy.stiffness).isEqualTo(420f)
        assertThat(MotionSprings.Bouncy.dampingRatio).isEqualTo(0.72f)
        assertThat(MotionSprings.Gentle.stiffness).isEqualTo(160f)
        assertThat(MotionSprings.Gentle.dampingRatio).isEqualTo(0.95f)
    }

    @Test
    fun level_is_full_only_with_animators_on_and_the_setting_off() {
        assertThat(resolveMotionLevel(reduceMotionSetting = false, systemAnimatorScale = 1f)).isEqualTo(MotionLevel.Full)
        assertThat(resolveMotionLevel(reduceMotionSetting = false, systemAnimatorScale = 3f)).isEqualTo(MotionLevel.Full)
        assertThat(resolveMotionLevel(reduceMotionSetting = false, systemAnimatorScale = 0.5f)).isEqualTo(MotionLevel.Full)
    }

    @Test
    fun the_in_app_setting_reduces_motion() {
        assertThat(resolveMotionLevel(reduceMotionSetting = true, systemAnimatorScale = 1f)).isEqualTo(MotionLevel.Reduced)
    }

    @Test
    fun system_animator_scale_zero_reduces_motion() {
        assertThat(resolveMotionLevel(reduceMotionSetting = false, systemAnimatorScale = 0f)).isEqualTo(MotionLevel.Reduced)
    }

    @Test
    fun a_corrupt_animator_scale_never_enables_decoration() {
        assertThat(resolveMotionLevel(reduceMotionSetting = false, systemAnimatorScale = Float.NaN)).isEqualTo(MotionLevel.Reduced)
        assertThat(resolveMotionLevel(reduceMotionSetting = false, systemAnimatorScale = -1f)).isEqualTo(MotionLevel.Reduced)
    }

    @Test
    fun full_motion_uses_springs_for_touched_things() {
        assertThat(full.plan(MotionToken.Press)).isEqualTo(MotionPlan.Spring(MotionSprings.Snappy))
        assertThat(full.plan(MotionToken.Release)).isEqualTo(MotionPlan.Spring(MotionSprings.Bouncy))
        assertThat(full.plan(MotionToken.Settle)).isEqualTo(MotionPlan.Spring(MotionSprings.Gentle))
    }

    @Test
    fun no_token_resolves_to_linear_easing() {
        for (motion in listOf(full, reduced)) {
            for (token in MotionToken.entries) {
                val plan = motion.plan(token)
                if (plan is MotionPlan.Tween) assertThat(plan.easing.name).isNotEqualTo("Linear")
            }
        }
    }

    @Test
    fun reduced_motion_turns_decorative_tokens_off() {
        for (token in MotionToken.entries.filter { it.role == MotionRole.Decorative }) {
            assertThat(reduced.plan(token)).isEqualTo(MotionPlan.None)
            assertThat(full.plan(token)).isNotEqualTo(MotionPlan.None)
        }
        assertThat(reduced.decorativeEnabled).isFalse()
        assertThat(full.decorativeEnabled).isTrue()
    }

    @Test
    fun reduced_motion_turns_essential_transitions_into_short_fades() {
        for (token in MotionToken.entries.filter { it.role == MotionRole.Essential }) {
            val plan = reduced.plan(token)
            assertThat(plan).isInstanceOf(MotionPlan.Fade::class.java)
            assertThat((plan as MotionPlan.Fade).durationMs).isAtMost(MotionDurations.ReducedFade)
        }
    }

    @Test
    fun reduced_motion_keeps_press_feedback_because_it_confirms_a_touch() {
        for (token in MotionToken.entries.filter { it.role == MotionRole.Feedback }) {
            val plan = reduced.plan(token)
            assertThat(plan).isInstanceOf(MotionPlan.Tween::class.java)
            assertThat((plan as MotionPlan.Tween).durationMs).isAtMost(MotionDurations.MicroFast)
        }
    }

    @Test
    fun stagger_grows_with_the_index_and_is_capped_so_long_lists_do_not_crawl() {
        assertThat(full.staggerDelayMs(0)).isEqualTo(0)
        assertThat(full.staggerDelayMs(3)).isEqualTo(120)
        assertThat(full.staggerDelayMs(10_000)).isEqualTo(MotionDurations.StaggerMax)
        assertThat(reduced.staggerDelayMs(3)).isEqualTo(0)
    }

    @Test
    fun specs_are_built_for_every_token_at_both_levels() {
        for (motion in listOf(full, reduced)) {
            for (token in MotionToken.entries) assertThat(motion.spec<Float>(token)).isNotNull()
        }
    }
}
