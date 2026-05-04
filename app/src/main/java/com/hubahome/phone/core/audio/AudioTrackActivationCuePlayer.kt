package com.hubahome.phone.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

@Singleton
class AudioTrackActivationCuePlayer @Inject constructor() : ActivationCuePlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cueJob: Job? = null

    override fun playReadyCue() {
        cueJob?.cancel()
        cueJob = scope.launch {
            runCatching {
                playPcm16(generateCuePcm())
            }.onFailure { error ->
                Log.e(TAG, "Activation cue playback failed", error)
            }
        }
    }

    @Suppress("unused")
    fun release() {
        cueJob?.cancel()
        scope.cancel()
    }

    private fun generateCuePcm(): ByteArray {
        val sampleCount = SAMPLE_RATE * CUE_DURATION_MS / 1000
        val samples = ShortArray(sampleCount)
        val fadeSamples = (sampleCount * FADE_FRACTION).toInt().coerceAtLeast(1)

        for (index in 0 until sampleCount) {
            val fadeMultiplier = when {
                index < fadeSamples -> index.toFloat() / fadeSamples
                index >= sampleCount - fadeSamples -> (sampleCount - index).toFloat() / fadeSamples
                else -> 1f
            }.coerceIn(0f, 1f)
            val angle = 2.0 * PI * CUE_FREQUENCY_HZ * index / SAMPLE_RATE
            val value = (sin(angle) * Short.MAX_VALUE * AMPLITUDE_SCALE * fadeMultiplier).toInt()
            samples[index] = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return WavCodec.pcm16FromShortArray(samples)
    }

    private fun playPcm16(pcm: ByteArray) {
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            pcm.size.coerceAtLeast(MIN_BUFFER),
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            while (
                track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                track.playbackHeadPosition < pcm.size / 2
            ) {
                Thread.sleep(10)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private companion object {
        private const val TAG = "ActivationCuePlayer"
        private const val SAMPLE_RATE = 16_000
        private const val MIN_BUFFER = 4096
        private const val CUE_DURATION_MS = 220
        private const val CUE_FREQUENCY_HZ = 880.0
        private const val AMPLITUDE_SCALE = 0.35
        private const val FADE_FRACTION = 0.12f
    }
}
