package com.darshan.payflow.ui.designsystem

import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import com.darshan.payflow.ui.designsystem.effects.Haptics
import com.darshan.payflow.ui.designsystem.effects.UiSound
import com.darshan.payflow.ui.designsystem.effects.UiSoundEvent

/** Records every haptic. Pass it to `PayFlowTheme(haptics = ...)` and assert on [events]. */
class FakeHaptics : Haptics {
    val events = mutableListOf<HapticEvent>()
    override fun tick() { events += HapticEvent.Tick }
    override fun light() { events += HapticEvent.Light }
    override fun confirm() { events += HapticEvent.Confirm }
    override fun reject() { events += HapticEvent.Reject }
    override fun heavy() { events += HapticEvent.Heavy }
}

/** Records every sound request. */
class FakeUiSound : UiSound {
    val events = mutableListOf<UiSoundEvent>()
    override fun play(event: UiSoundEvent) { events += event }
}
