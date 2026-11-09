package com.darshan.payflow.ui.designsystem

import android.view.HapticFeedbackConstants
import com.darshan.payflow.ui.designsystem.effects.GuardedHaptics
import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import com.darshan.payflow.ui.designsystem.effects.GuardedUiSound
import com.darshan.payflow.ui.designsystem.effects.UiEffect
import com.darshan.payflow.ui.designsystem.effects.UiEffectChannel
import com.darshan.payflow.ui.designsystem.effects.UiSoundEvent
import com.darshan.payflow.ui.designsystem.effects.dispatchTo
import com.darshan.payflow.ui.designsystem.effects.feedbackConstantFor
import com.darshan.payflow.ui.designsystem.effects.perform
import com.darshan.payflow.ui.designsystem.effects.ToastTone
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EffectsTest {
    @Test
    fun every_event_reaches_its_own_method_on_the_fake() {
        val haptics = FakeHaptics()
        for (event in HapticEvent.entries) haptics.perform(event)
        assertThat(haptics.events).containsExactlyElementsIn(HapticEvent.entries).inOrder()
    }

    @Test
    fun confirm_and_reject_use_the_dedicated_constants_from_api_30() {
        assertThat(feedbackConstantFor(HapticEvent.Confirm, 30)).isEqualTo(HapticFeedbackConstants.CONFIRM)
        assertThat(feedbackConstantFor(HapticEvent.Reject, 34)).isEqualTo(HapticFeedbackConstants.REJECT)
    }

    @Test
    fun older_devices_fall_back_to_constants_that_exist_on_min_sdk() {
        assertThat(feedbackConstantFor(HapticEvent.Confirm, 26)).isEqualTo(HapticFeedbackConstants.VIRTUAL_KEY)
        assertThat(feedbackConstantFor(HapticEvent.Reject, 29)).isEqualTo(HapticFeedbackConstants.LONG_PRESS)
    }

    @Test
    fun each_event_maps_to_a_distinct_constant_on_a_modern_device() {
        val constants = HapticEvent.entries.map { feedbackConstantFor(it, 35) }
        assertThat(constants).containsNoDuplicates()
    }

    @Test
    fun the_settings_switch_silences_every_event_and_reads_the_value_live() {
        val fake = FakeHaptics()
        var enabled = false
        val guarded = GuardedHaptics(fake) { enabled }
        for (event in HapticEvent.entries) guarded.perform(event)
        assertThat(fake.events).isEmpty()
        enabled = true
        guarded.confirm()
        assertThat(fake.events).containsExactly(HapticEvent.Confirm)
    }

    @Test
    fun sound_is_silent_until_the_setting_is_on() {
        val fake = FakeUiSound()
        var enabled = false
        val guarded = GuardedUiSound(fake) { enabled }
        guarded.play(UiSoundEvent.Success)
        assertThat(fake.events).isEmpty()
        enabled = true
        guarded.play(UiSoundEvent.Success)
        assertThat(fake.events).containsExactly(UiSoundEvent.Success)
    }

    @Test
    fun the_effect_channel_delivers_in_order_to_one_collector() = runTest {
        val channel = UiEffectChannel()
        channel.send(UiEffect.Haptic(HapticEvent.Tick))
        channel.send(UiEffect.Haptic(HapticEvent.Confirm))
        channel.effects.test {
            assertThat(awaitItem()).isEqualTo(UiEffect.Haptic(HapticEvent.Tick))
            assertThat(awaitItem()).isEqualTo(UiEffect.Haptic(HapticEvent.Confirm))
        }
    }

    @Test
    fun an_effect_sent_before_anyone_collects_is_kept_not_lost() = runTest {
        val channel = UiEffectChannel()
        channel.send(UiEffect.Sound(UiSoundEvent.Success))
        channel.effects.test { assertThat(awaitItem()).isEqualTo(UiEffect.Sound(UiSoundEvent.Success)) }
    }

    @Test
    fun a_flood_of_effects_is_bounded_and_keeps_the_newest() = runTest {
        val channel = UiEffectChannel(capacity = 4)
        repeat(1_000) { channel.send(UiEffect.Toast("message $it", ToastTone.Info)) }
        channel.effects.test {
            val received = List(4) { awaitItem() }
            assertThat(received.last()).isEqualTo(UiEffect.Toast("message 999", ToastTone.Info))
            expectNoEvents()
        }
    }

    @Test
    fun dispatch_routes_each_effect_to_the_right_device_channel() {
        val haptics = FakeHaptics()
        val sound = FakeUiSound()
        val toasts = mutableListOf<String>()
        val other = mutableListOf<UiEffect>()
        val sink = { e: UiEffect -> other += e }
        UiEffect.Haptic(HapticEvent.Reject).dispatchTo(haptics, sound, onToast = { m, _ -> toasts += m }, onOther = sink)
        UiEffect.Sound(UiSoundEvent.Success).dispatchTo(haptics, sound, onToast = { m, _ -> toasts += m }, onOther = sink)
        UiEffect.Toast("Copied", ToastTone.Success).dispatchTo(haptics, sound, onToast = { m, _ -> toasts += m }, onOther = sink)
        assertThat(haptics.events).containsExactly(HapticEvent.Reject)
        assertThat(sound.events).containsExactly(UiSoundEvent.Success)
        assertThat(toasts).containsExactly("Copied")
        assertThat(other).isEmpty()
    }
}
