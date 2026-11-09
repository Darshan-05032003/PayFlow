package com.darshan.payflow.ui.designsystem.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

enum class ToastTone { Info, Success, Pending, Failed }

/**
 * One-off UI effects, kept out of `StateFlow<UiState>` because replaying them after a rotation would be wrong.
 *
 * Convention: a ViewModel owns one [UiEffectChannel], calls `effects.send(UiEffect.Haptic(HapticEvent.Confirm))` from its
 * logic, and exposes `effects.effects`. The screen collects it with [CollectUiEffects]. Tests assert the right effect per
 * event by collecting the same flow, or at screen level through `FakeHaptics`. Feature-specific effects such as navigation
 * implement this interface and arrive through `onOther`.
 */
interface UiEffect {
    data class Haptic(val event: HapticEvent) : UiEffect
    data class Sound(val event: UiSoundEvent) : UiEffect
    data class Toast(val message: String, val tone: ToastTone = ToastTone.Info) : UiEffect
}

/**
 * A bounded, single-consumer channel. Effects sent while nobody collects (say, during a rotation) wait for the next
 * collector. A flood drops the oldest, so a broken caller can't grow memory.
 */
class UiEffectChannel(capacity: Int = DEFAULT_CAPACITY) {
    private val channel = Channel<UiEffect>(capacity, BufferOverflow.DROP_OLDEST)
    val effects: Flow<UiEffect> = channel.receiveAsFlow()

    fun send(effect: UiEffect) {
        channel.trySend(effect)
    }

    private companion object {
        const val DEFAULT_CAPACITY = 16
    }
}

fun UiEffect.dispatchTo(
    haptics: Haptics,
    sound: UiSound,
    onToast: (String, ToastTone) -> Unit,
    onOther: (UiEffect) -> Unit,
) {
    when (this) {
        is UiEffect.Haptic -> haptics.perform(event)
        is UiEffect.Sound -> sound.play(event)
        is UiEffect.Toast -> onToast(message, tone)
        else -> onOther(this)
    }
}

/** Screen side of the convention: routes haptics and sounds to the theme's channels, the rest to the callbacks. */
@Composable
fun CollectUiEffects(
    effects: Flow<UiEffect>,
    onToast: (String, ToastTone) -> Unit = { _, _ -> },
    onOther: (UiEffect) -> Unit = {},
) {
    val haptics = LocalHaptics.current
    val sound = LocalUiSound.current
    val toast = rememberUpdatedState(onToast)
    val other = rememberUpdatedState(onOther)
    LaunchedEffect(effects) {
        effects.collect { effect ->
            effect.dispatchTo(haptics, sound, onToast = { m, t -> toast.value(m, t) }, onOther = { other.value(it) })
        }
    }
}
