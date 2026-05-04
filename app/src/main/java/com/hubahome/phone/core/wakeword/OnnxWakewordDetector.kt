package com.hubahome.phone.core.wakeword

import ai.onnxruntime.OrtException
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Singleton
class OnnxWakewordDetector @Inject constructor(
    private val runtime: OpenWakewordRuntime,
    private val wakewordClipStore: WakewordClipStore,
) : WakewordDetector {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<WakewordEvent>(extraBufferCapacity = 16)
    override val events: Flow<WakewordEvent> = _events.asSharedFlow()

    private var detectJob: Job? = null
    private var recorder: AudioRecord? = null
    private var active = false
    private var consecutiveHits = 0
    private var detectionLatched = false

    override fun start() {
        if (active) return
        try {
            runtime.ensureReady()
        } catch (error: OrtException) {
            Log.e(TAG, "Wakeword runtime initialization failed", error)
            _events.tryEmit(WakewordEvent.Error("Wakeword runtime init failed: ${error.message}"))
            return
        } catch (error: Exception) {
            Log.e(TAG, "Wakeword runtime initialization failed", error)
            _events.tryEmit(WakewordEvent.Error("Wakeword runtime init failed: ${error.message}"))
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            _events.tryEmit(WakewordEvent.Error("Wakeword AudioRecord buffer initialization failed"))
            return
        }

        val localRecorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferSize * 2, FRAME_SAMPLES * BYTES_PER_SAMPLE * 2),
        )
        if (localRecorder.state != AudioRecord.STATE_INITIALIZED) {
            localRecorder.release()
            _events.tryEmit(WakewordEvent.Error("Wakeword AudioRecord failed to initialize"))
            return
        }

        active = true
        consecutiveHits = 0
        detectionLatched = false
        runtime.reset()
        recorder = localRecorder
        localRecorder.startRecording()

        detectJob = scope.launch {
            val buffer = ShortArray(FRAME_SAMPLES)
            while (isActive && active) {
                val read = localRecorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    continue
                }

                val score = try {
                    runtime.predict(if (read == buffer.size) buffer else buffer.copyOf(read))
                } catch (error: OrtException) {
                    Log.e(TAG, "Wakeword inference failed", error)
                    _events.tryEmit(WakewordEvent.Error("Wakeword inference failed: ${error.message}"))
                    stop()
                    break
                } catch (error: Exception) {
                    Log.e(TAG, "Wakeword detector failed", error)
                    _events.tryEmit(WakewordEvent.Error("Wakeword detector failed: ${error.message}"))
                    stop()
                    break
                }

                if (score >= DETECTION_THRESHOLD) {
                    consecutiveHits += 1
                } else {
                    consecutiveHits = 0
                    detectionLatched = false
                }

                if (!detectionLatched && consecutiveHits >= REQUIRED_CONSECUTIVE_HITS) {
                    detectionLatched = true
                    val preRollSamples = runtime.snapshotRecentSamples(PRE_ROLL_SAMPLES)
                    val postRollSamples = capturePostRoll(localRecorder)
                    scope.launch {
                        runCatching {
                            wakewordClipStore.saveDetectedClip(
                                preRollSamples = preRollSamples,
                                postRollSamples = postRollSamples,
                                detectionScore = score,
                            )
                        }.onFailure { error ->
                            Log.e(TAG, "Wakeword clip save failed", error)
                        }
                    }
                    _events.tryEmit(WakewordEvent.Detected)
                }
            }
        }
    }

    override fun stop() {
        active = false
        detectJob?.cancel()
        detectJob = null
        consecutiveHits = 0
        detectionLatched = false
        runtime.reset()

        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
    }

    @Suppress("unused")
    fun release() {
        stop()
        scope.cancel()
    }

    private fun capturePostRoll(localRecorder: AudioRecord): ShortArray {
        val captured = ShortArray(POST_ROLL_SAMPLES)
        var totalCopied = 0
        val buffer = ShortArray(FRAME_SAMPLES)

        while (active && totalCopied < POST_ROLL_SAMPLES) {
            val read = localRecorder.read(buffer, 0, buffer.size)
            if (read <= 0) {
                continue
            }

            val bytesToCopy = minOf(read, POST_ROLL_SAMPLES - totalCopied)
            System.arraycopy(buffer, 0, captured, totalCopied, bytesToCopy)
            totalCopied += bytesToCopy
        }

        return if (totalCopied == captured.size) {
            captured
        } else {
            captured.copyOf(totalCopied)
        }
    }

    private companion object {
        private const val TAG = "OnnxWakewordDetector"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 1_280
        private const val BYTES_PER_SAMPLE = 2
        private const val DETECTION_THRESHOLD = 0.5f
        private const val REQUIRED_CONSECUTIVE_HITS = 2
        private const val PRE_ROLL_SAMPLES = 24_000
        private const val POST_ROLL_SAMPLES = 5_120
    }
}
