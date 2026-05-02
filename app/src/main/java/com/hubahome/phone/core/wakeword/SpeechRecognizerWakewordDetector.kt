package com.hubahome.phone.core.wakeword

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Singleton
class SpeechRecognizerWakewordDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) : WakewordDetector {
    private val _events = MutableSharedFlow<WakewordEvent>(extraBufferCapacity = 16)
    override val events: Flow<WakewordEvent> = _events.asSharedFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var active = false

    override fun start() {
        if (active) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _events.tryEmit(WakewordEvent.Error("Speech recognition unavailable on device"))
            return
        }
        active = true
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
        startListening()
    }

    override fun stop() {
        active = false
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    private fun startListening() {
        if (!active) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        }
        speechRecognizer?.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            if (!active) return
            _events.tryEmit(WakewordEvent.Error("Wakeword recognizer error: $error"))
            startListening()
        }

        override fun onResults(results: Bundle?) {
            processBundle(results)
            startListening()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            processBundle(partialResults)
        }
    }

    private fun processBundle(bundle: Bundle?) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val hasWakeword = matches.any { phrase ->
            phrase.lowercase().contains("хуба") || phrase.lowercase().contains("huba")
        }
        if (hasWakeword) {
            Log.i(TAG, "Wakeword detected")
            _events.tryEmit(WakewordEvent.Detected)
        }
    }

    private companion object {
        private const val TAG = "WakewordDetector"
    }
}
