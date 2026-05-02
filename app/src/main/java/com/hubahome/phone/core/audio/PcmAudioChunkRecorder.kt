package com.hubahome.phone.core.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
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
class PcmAudioChunkRecorder @Inject constructor() : AudioChunkRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordJob: Job? = null
    private val recording = AtomicBoolean(false)
    override val isRecording: Boolean get() = recording.get()

    override fun start(onChunkBase64Wav: (String) -> Unit) {
        if (recording.get()) return
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            Log.e(TAG, "Invalid min buffer size for AudioRecord: $minBufferSize")
            return
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2,
        )
        recorder.startRecording()
        recording.set(true)

        recordJob = scope.launch {
            val chunkBuffer = ByteArray(CHUNK_BYTES)
            while (isActive && recording.get()) {
                val read = recorder.read(chunkBuffer, 0, chunkBuffer.size)
                if (read <= 0) continue
                val pcm = if (read == chunkBuffer.size) chunkBuffer else chunkBuffer.copyOf(read)
                val wav = WavCodec.pcm16MonoToWav(pcm, sampleRate = SAMPLE_RATE)
                onChunkBase64Wav(WavCodec.wavToBase64(wav))
            }
            runCatching {
                recorder.stop()
                recorder.release()
            }
        }
    }

    override fun stop() {
        recording.set(false)
        recordJob?.cancel()
        recordJob = null
    }

    @Suppress("unused")
    fun release() {
        stop()
        scope.cancel()
    }

    private companion object {
        private const val TAG = "PcmAudioChunkRecorder"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_BYTES = 16_000
    }
}
