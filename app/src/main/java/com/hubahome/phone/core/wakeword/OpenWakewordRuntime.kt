package com.hubahome.phone.core.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.FloatBuffer
import java.util.ArrayDeque
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class OpenWakewordRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val environment = OrtEnvironment.getEnvironment()
    private val sessionOptions by lazy { OrtSession.SessionOptions() }
    private val initLock = Any()
    @Volatile private var initialized = false

    private lateinit var melspectrogramSession: OrtSession
    private lateinit var embeddingSession: OrtSession
    private lateinit var wakewordSession: OrtSession
    private lateinit var wakewordInputName: String
    private var wakewordInputFrames: Int = DEFAULT_WAKEWORD_INPUT_FRAMES

    private val rawAudioBuffer = ArrayDeque<Short>(RAW_AUDIO_BUFFER_MAX_SAMPLES)
    private val melspectrogramFrames = ArrayDeque<FloatArray>(MELSPECTROGRAM_BUFFER_MAX_FRAMES)
    private val featureFrames = ArrayDeque<FloatArray>(FEATURE_BUFFER_MAX_FRAMES)
    private val pendingSamples = ArrayDeque<Short>(FRAME_SAMPLES)

    private var lastScore = 0f
    private var warmupPredictions = 0

    fun reset() {
        rawAudioBuffer.clear()
        melspectrogramFrames.clear()
        featureFrames.clear()
        pendingSamples.clear()
        lastScore = 0f
        warmupPredictions = 0
    }

    fun snapshotRecentSamples(sampleCount: Int): ShortArray {
        return getTailSamples(sampleCount)
    }

    @Throws(OrtException::class)
    fun ensureReady() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            try {
                melspectrogramSession = createSession(MELSPECTROGRAM_MODEL_ASSET)
                embeddingSession = createSession(EMBEDDING_MODEL_ASSET)
                wakewordSession = createSession(WAKEWORD_MODEL_ASSET)
                wakewordInputName = wakewordSession.inputInfo.keys.first()
                wakewordInputFrames = resolveWakewordInputFrames()
                initialized = true
            } catch (error: OrtException) {
                throw enrichInitializationError(error)
            } catch (error: Exception) {
                throw OrtException(
                    OrtException.OrtErrorCode.ORT_RUNTIME_EXCEPTION,
                    "Failed to initialize wakeword runtime: ${error.message}",
                )
            }
        }
    }

    @Throws(OrtException::class)
    fun predict(samples: ShortArray): Float {
        if (samples.isEmpty()) {
            return 0f
        }
        ensureReady()

        for (sample in samples) {
            pendingSamples.addLast(sample)
        }

        var latestPrediction = lastScore
        while (pendingSamples.size >= FRAME_SAMPLES) {
            val frame = ShortArray(FRAME_SAMPLES) { pendingSamples.removeFirst() }
            latestPrediction = processFrame(frame)
        }
        lastScore = latestPrediction
        return latestPrediction
    }

    private fun processFrame(frame: ShortArray): Float {
        appendRawAudio(frame)
        appendMelspectrogram(frame.size)

        val melspecList = melspectrogramFrames.toList()
        val latestWindow = melspecList.takeLast(MELSPECTROGRAM_WINDOW_FRAMES)
        if (latestWindow.size == MELSPECTROGRAM_WINDOW_FRAMES) {
            appendFeature(runEmbedding(latestWindow))
        }

        val featureInput = getLatestFeatures(wakewordInputFrames) ?: return 0f
        val prediction = runWakeword(featureInput)

        if (warmupPredictions < WARMUP_FRAMES) {
            warmupPredictions += 1
            return 0f
        }
        return prediction
    }

    private fun appendRawAudio(frame: ShortArray) {
        for (sample in frame) {
            if (rawAudioBuffer.size == RAW_AUDIO_BUFFER_MAX_SAMPLES) {
                rawAudioBuffer.removeFirst()
            }
            rawAudioBuffer.addLast(sample)
        }
    }

    private fun appendMelspectrogram(frameSampleCount: Int) {
        val rawWindow = getTailSamples(frameSampleCount + MEL_LOOKBACK_SAMPLES)
        if (rawWindow.isEmpty()) return

        val rows = runMelspectrogram(rawWindow)
        for (row in rows) {
            if (melspectrogramFrames.size == MELSPECTROGRAM_BUFFER_MAX_FRAMES) {
                melspectrogramFrames.removeFirst()
            }
            melspectrogramFrames.addLast(row)
        }
    }

    private fun appendFeature(feature: FloatArray) {
        if (featureFrames.size == FEATURE_BUFFER_MAX_FRAMES) {
            featureFrames.removeFirst()
        }
        featureFrames.addLast(feature)
    }

    private fun getLatestFeatures(count: Int): FloatArray? {
        if (featureFrames.size < count) return null

        val features = featureFrames.toList().takeLast(count)
        val flattened = FloatArray(count * EMBEDDING_SIZE)
        var offset = 0
        for (frame in features) {
            System.arraycopy(frame, 0, flattened, offset, EMBEDDING_SIZE)
            offset += EMBEDDING_SIZE
        }
        return flattened
    }

    private fun runMelspectrogram(rawSamples: ShortArray): List<FloatArray> {
        val inputTensor = createFloatTensor(rawSamples.map { it.toFloat() }.toFloatArray(), longArrayOf(1, rawSamples.size.toLong()))
        try {
            val results = melspectrogramSession.run(Collections.singletonMap(MELSPECTROGRAM_INPUT_NAME, inputTensor))
            try {
                val flattened = flattenToFloatArray(results[0].value)
                val rowCount = flattened.size / MEL_BINS
                return List(rowCount) { rowIndex ->
                    FloatArray(MEL_BINS) { columnIndex ->
                        flattened[rowIndex * MEL_BINS + columnIndex] / MEL_SCALE_DIVISOR + MEL_SCALE_OFFSET
                    }
                }
            } finally {
                results.close()
            }
        } finally {
            inputTensor.close()
        }
    }

    private fun runEmbedding(melspectrogramWindow: List<FloatArray>): FloatArray {
        val flattened = FloatArray(MELSPECTROGRAM_WINDOW_FRAMES * MEL_BINS)
        var offset = 0
        for (row in melspectrogramWindow) {
            System.arraycopy(row, 0, flattened, offset, MEL_BINS)
            offset += MEL_BINS
        }
        val inputTensor = createFloatTensor(
            flattened,
            longArrayOf(1, MELSPECTROGRAM_WINDOW_FRAMES.toLong(), MEL_BINS.toLong(), 1),
        )
        try {
            val results = embeddingSession.run(Collections.singletonMap(EMBEDDING_INPUT_NAME, inputTensor))
            try {
                val flattenedOutput = flattenToFloatArray(results[0].value)
                return if (flattenedOutput.size >= EMBEDDING_SIZE) {
                    flattenedOutput.copyOfRange(0, EMBEDDING_SIZE)
                } else {
                    FloatArray(EMBEDDING_SIZE)
                }
            } finally {
                results.close()
            }
        } finally {
            inputTensor.close()
        }
    }

    private fun runWakeword(featureInput: FloatArray): Float {
        val inputTensor = createFloatTensor(
            featureInput,
            longArrayOf(1, wakewordInputFrames.toLong(), EMBEDDING_SIZE.toLong()),
        )
        try {
            val results = wakewordSession.run(Collections.singletonMap(wakewordInputName, inputTensor))
            try {
                val flattened = flattenToFloatArray(results[0].value)
                return flattened.maxOrNull()?.coerceIn(0f, 1f) ?: 0f
            } finally {
                results.close()
            }
        } finally {
            inputTensor.close()
        }
    }

    private fun createFloatTensor(flattened: FloatArray, shape: LongArray): OnnxTensor {
        val floatBuffer = FloatBuffer.allocate(flattened.size)
        floatBuffer.put(flattened)
        floatBuffer.rewind()
        return OnnxTensor.createTensor(environment, floatBuffer, shape)
    }

    private fun getTailSamples(sampleCount: Int): ShortArray {
        val tailCount = max(0, minOf(sampleCount, rawAudioBuffer.size))
        if (tailCount == 0) return ShortArray(0)

        val values = rawAudioBuffer.toList()
        val startIndex = values.size - tailCount
        return ShortArray(tailCount) { index -> values[startIndex + index] }
    }

    private fun createSession(assetPath: String): OrtSession {
        val modelFile = copyAssetToLocalFile(assetPath)
        copyOptionalAssetSidecar(assetPath, modelFile)
        return environment.createSession(modelFile.absolutePath, sessionOptions)
    }

    private fun resolveWakewordInputFrames(): Int {
        val inputInfo = wakewordSession.inputInfo.values.first().info as TensorInfo
        val shape = inputInfo.shape
        return shape.getOrNull(1)?.toInt()?.takeIf { it > 0 } ?: DEFAULT_WAKEWORD_INPUT_FRAMES
    }

    private fun copyAssetToLocalFile(assetPath: String): File {
        val outputDir = File(context.filesDir, LOCAL_MODEL_DIR).apply { mkdirs() }
        val fileName = assetPath.substringAfterLast('/')
        val outputFile = File(outputDir, fileName)
        if (outputFile.exists()) {
            return outputFile
        }
        context.assets.open(assetPath).use { input ->
            outputFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outputFile
    }

    private fun copyOptionalAssetSidecar(assetPath: String, modelFile: File) {
        val sidecarAsset = "$assetPath.data"
        val outputDir = modelFile.parentFile ?: return
        val sidecarFile = File(outputDir, "${modelFile.name}.data")
        if (sidecarFile.exists()) {
            return
        }
        try {
            context.assets.open(sidecarAsset).use { input ->
                sidecarFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (_: FileNotFoundException) {
            // Some ONNX models are self-contained and don't need external data.
        } catch (_: IOException) {
            // If the sidecar is absent we let ONNX Runtime report the failure on session creation.
        }
    }

    private fun enrichInitializationError(error: OrtException): OrtException {
        val message = error.message.orEmpty()
        if (message.contains("${WAKEWORD_MODEL_FILE_NAME}.data")) {
            return OrtException(
                OrtException.OrtErrorCode.ORT_RUNTIME_EXCEPTION,
                "Wakeword model is incomplete: missing external data file " +
                    "'${WAKEWORD_MODEL_FILE_NAME}.data'. Re-export or copy the sidecar next to '${WAKEWORD_MODEL_FILE_NAME}'.",
            )
        }
        return error
    }

    @Suppress("UNCHECKED_CAST")
    private fun flattenToFloatArray(value: Any?): FloatArray {
        val flattened = ArrayList<Float>()
        flattenInto(flattened, value)
        return flattened.toFloatArray()
    }

    private fun flattenInto(output: MutableList<Float>, value: Any?) {
        when (value) {
            null -> Unit
            is Float -> output.add(value)
            is Double -> output.add(value.toFloat())
            is Number -> output.add(value.toFloat())
            is FloatArray -> value.forEach { output.add(it) }
            is DoubleArray -> value.forEach { output.add(it.toFloat()) }
            is LongArray -> value.forEach { output.add(it.toFloat()) }
            is IntArray -> value.forEach { output.add(it.toFloat()) }
            is Array<*> -> value.forEach { flattenInto(output, it) }
            is Iterable<*> -> value.forEach { flattenInto(output, it) }
            else -> error("Unsupported ONNX output type: ${value::class.java.name}")
        }
    }

    private companion object {
        private const val WAKEWORD_MODEL_ASSET = "wakeword/huba_ru_v3.onnx"
        private const val WAKEWORD_MODEL_FILE_NAME = "huba_ru_v3.onnx"
        private const val MELSPECTROGRAM_MODEL_ASSET = "wakeword/melspectrogram.onnx"
        private const val EMBEDDING_MODEL_ASSET = "wakeword/embedding_model.onnx"
        private const val LOCAL_MODEL_DIR = "wakeword-models"

        private const val MELSPECTROGRAM_INPUT_NAME = "input"
        private const val EMBEDDING_INPUT_NAME = "input_1"

        private const val FRAME_SAMPLES = 1_280
        private const val MEL_LOOKBACK_SAMPLES = 480
        private const val MEL_BINS = 32
        private const val MELSPECTROGRAM_WINDOW_FRAMES = 76
        private const val EMBEDDING_SIZE = 96
        private const val DEFAULT_WAKEWORD_INPUT_FRAMES = 16
        private const val RAW_AUDIO_BUFFER_MAX_SAMPLES = 160_000
        private const val MELSPECTROGRAM_BUFFER_MAX_FRAMES = 970
        private const val FEATURE_BUFFER_MAX_FRAMES = 120
        private const val WARMUP_FRAMES = 5
        private const val MEL_SCALE_DIVISOR = 10f
        private const val MEL_SCALE_OFFSET = 2f
    }
}
