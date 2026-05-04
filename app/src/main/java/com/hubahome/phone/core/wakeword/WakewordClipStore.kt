package com.hubahome.phone.core.wakeword

import android.content.Context
import com.hubahome.phone.core.audio.WavCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class WakewordClipStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun saveDetectedClip(
        preRollSamples: ShortArray,
        postRollSamples: ShortArray,
        detectionScore: Float,
    ): File = withContext(ioDispatcher) {
        val allSamples = ShortArray(preRollSamples.size + postRollSamples.size)
        System.arraycopy(preRollSamples, 0, allSamples, 0, preRollSamples.size)
        System.arraycopy(postRollSamples, 0, allSamples, preRollSamples.size, postRollSamples.size)

        val outputDir = context.getExternalFilesDir(CAPTURES_DIR_NAME)
            ?: File(context.filesDir, CAPTURES_DIR_NAME)
        outputDir.mkdirs()

        val sanitizedScore = String.format(Locale.US, "%.3f", detectionScore).replace('.', '_')
        val outputFile = File(outputDir, "wakeword_${System.currentTimeMillis()}_s${sanitizedScore}.wav")
        outputFile.writeBytes(WavCodec.pcm16MonoToWav(allSamples, sampleRate = SAMPLE_RATE))
        outputFile
    }

    private companion object {
        private const val CAPTURES_DIR_NAME = "wakeword-captures"
        private const val SAMPLE_RATE = 16_000
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    }
}
