package com.darshan.payflow.ui.designsystem.effects

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * PayFlow's one sound: a soft two-note chime, synthesised in code (no audio asset, nothing borrowed). A fifth upwards, each note
 * a sine with a fast attack and an exponential tail, mixed well under full scale. Pure, so its shape is tested on the JVM.
 */
object ChimeSynth {
    const val SAMPLE_RATE = 44_100
    const val DURATION_MS = 360
    private const val FIRST_HZ = 1_046.5 // C6
    private const val SECOND_HZ = 1_568.0 // G6
    private const val SECOND_AT_MS = 90
    private const val ATTACK_MS = 6.0
    private const val DECAY_PER_S = 14.0
    const val PEAK = 0.22

    fun success(sampleRate: Int = SAMPLE_RATE): ShortArray {
        val total = sampleRate * DURATION_MS / 1_000
        val secondAt = sampleRate * SECOND_AT_MS / 1_000
        val fadeOut = sampleRate * 20 / 1_000
        return ShortArray(total) { i ->
            val first = note(i.toDouble() / sampleRate, FIRST_HZ)
            val second = if (i >= secondAt) note((i - secondAt).toDouble() / sampleRate, SECOND_HZ) else 0.0
            // A short fade at the very end, so the buffer stops at silence rather than with a click.
            val tail = ((total - i).toDouble() / fadeOut).coerceAtMost(1.0)
            (((first * 0.55 + second * 0.45) * PEAK * tail) * Short.MAX_VALUE).roundToInt().toShort()
        }
    }

    private fun note(t: Double, hz: Double): Double {
        val attack = (t * 1_000 / ATTACK_MS).coerceAtMost(1.0)
        return sin(2 * PI * hz * t) * attack * exp(-DECAY_PER_S * t)
    }
}

/**
 * Plays [ChimeSynth] for a confirmed success. Only when the user turned sound on (the theme wraps this in [GuardedUiSound]) and
 * the phone's ringer is on normal: a silent or vibrate phone stays quiet. Any audio failure is swallowed; a chime is never worth
 * an error in a payments app.
 */
class ChimeUiSound(context: Context) : UiSound {
    private val appContext = context.applicationContext
    private val pcm: ShortArray by lazy { ChimeSynth.success() }

    override fun play(event: UiSoundEvent) {
        if (event != UiSoundEvent.Success) return
        val audio = appContext.getSystemService(AudioManager::class.java) ?: return
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(ChimeSynth.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
                .build()
            track.write(pcm, 0, pcm.size)
            track.notificationMarkerPosition = pcm.size
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = t.release()
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            })
            track.play()
        }
    }
}
