package com.hubahome.phone.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Singleton
class AudioTrackOutputPlayer @Inject constructor() : AudioOutputPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = LinkedBlockingQueue<String>()
    private val isPlaying = AtomicBoolean(false)
    private var playJob: Job? = null

    override fun playBase64Wav(base64Wav: String) {
        queue.offer(base64Wav)
        if (!isPlaying.get()) {
            startLoop()
        }
    }

    override fun stop() {
        queue.clear()
        playJob?.cancel()
        playJob = null
        isPlaying.set(false)
    }

    private fun startLoop() {
        if (isPlaying.getAndSet(true)) return
        playJob = scope.launch {
            while (isActive) {
                val base64 = queue.take()
                runCatching {
                    val wav = WavCodec.base64ToWav(base64)
                    val pcm = WavCodec.wavToPcm16(wav)
                    if (pcm.isNotEmpty()) {
                        playPcm16(pcm)
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Playback failed", error)
                }
                if (queue.isEmpty()) {
                    isPlaying.set(false)
                    break
                }
            }
        }
    }

    private fun playPcm16(pcm: ByteArray) {
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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
        track.write(pcm, 0, pcm.size)
        track.play()
        while (track.playState == AudioTrack.PLAYSTATE_PLAYING &&
            track.playbackHeadPosition < pcm.size / 2
        ) {
            Thread.sleep(20)
        }
        track.stop()
        track.release()
    }

    @Suppress("unused")
    fun release() {
        stop()
        scope.cancel()
    }

    private companion object {
        private const val TAG = "AudioTrackOutputPlayer"
        private const val SAMPLE_RATE = 16_000
        private const val MIN_BUFFER = 4096
    }
}
