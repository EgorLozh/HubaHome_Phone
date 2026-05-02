package com.hubahome.phone.feature.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hubahome.phone.core.audio.AudioChunkRecorder
import com.hubahome.phone.core.audio.AudioOutputPlayer
import com.hubahome.phone.core.network.IncomingServerEvent
import com.hubahome.phone.core.network.VoiceSessionClient
import com.hubahome.phone.core.network.VoiceSessionStatus
import com.hubahome.phone.core.settings.ConnectionSettingsStore
import com.hubahome.phone.core.wakeword.WakewordDetector
import com.hubahome.phone.core.wakeword.WakewordEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val voiceSessionClient: VoiceSessionClient,
    private val settingsStore: ConnectionSettingsStore,
    private val wakewordDetector: WakewordDetector,
    private val audioChunkRecorder: AudioChunkRecorder,
    private val audioOutputPlayer: AudioOutputPlayer,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()
    private var chunkId = 0
    private var recordStopJob: Job? = null

    val sessionStatus: StateFlow<VoiceSessionStatus> = voiceSessionClient.status.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = VoiceSessionStatus.IDLE,
    )

    init {
        observeSettings()
        observeSessionStatus()
        observeServerEvents()
        observeWakewordEvents()
    }

    fun connect() {
        voiceSessionClient.connect()
    }

    fun disconnect() {
        voiceSessionClient.disconnect()
        stopCapture()
        stopWakeword()
    }

    fun reconnect() {
        voiceSessionClient.reconnect()
    }

    fun updateInput(value: String) {
        _uiState.update { it.copy(transcriptInput = value) }
    }

    fun updateWsUrl(value: String) {
        _uiState.update { it.copy(wsUrlInput = value) }
    }

    fun updateApiKey(value: String) {
        _uiState.update { it.copy(apiKeyInput = value) }
    }

    fun saveConnectionSettings() {
        val current = _uiState.value
        settingsStore.save(current.wsUrlInput, current.apiKeyInput)
            .onSuccess {
                addSystemMessage("Connection settings saved")
                reconnect()
            }
            .onFailure { error ->
                addSystemMessage("Settings error: ${error.message}")
                _uiState.update { it.copy(lastError = error.message) }
            }
    }

    fun resetConnectionSettings() {
        settingsStore.resetToDefault()
        addSystemMessage("Connection settings reset to defaults")
    }

    fun sendTranscript() {
        val text = uiState.value.transcriptInput.trim()
        if (text.isBlank()) return
        voiceSessionClient.sendFinalTranscript(text)
        _uiState.update {
            it.copy(
                transcriptInput = "",
                messages = it.messages + AssistantMessage(
                    sender = AssistantMessage.Sender.USER,
                    text = text,
                ),
            )
        }
    }

    fun toggleWakeword(enabled: Boolean) {
        _uiState.update { it.copy(isWakewordEnabled = enabled) }
        if (enabled) {
            wakewordDetector.start()
            addSystemMessage("Wakeword detection enabled")
        } else {
            wakewordDetector.stop()
            addSystemMessage("Wakeword detection disabled")
        }
    }

    fun startCapture() {
        if (audioChunkRecorder.isRecording) return
        chunkId = 0
        audioChunkRecorder.start { chunkBase64 ->
            voiceSessionClient.sendAudioChunk(chunkId++, chunkBase64)
        }
        _uiState.update { it.copy(isRecording = true) }
        addSystemMessage("Recording started")
    }

    fun stopCapture() {
        if (!audioChunkRecorder.isRecording) return
        audioChunkRecorder.stop()
        _uiState.update { it.copy(isRecording = false) }
        voiceSessionClient.sendFinalTranscript("")
        addSystemMessage("Recording stopped; final_transcript sent")
    }

    private fun observeSessionStatus() {
        viewModelScope.launch {
            sessionStatus.collectLatest { status ->
                _uiState.update {
                    it.copy(
                        sessionStatus = status,
                        isConnected = status == VoiceSessionStatus.CONNECTED,
                    )
                }
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.settings.collectLatest { settings ->
                _uiState.update {
                    it.copy(
                        wsUrlInput = settings.wsUrl,
                        apiKeyInput = settings.apiKey,
                    )
                }
            }
        }
    }

    private fun observeServerEvents() {
        viewModelScope.launch {
            voiceSessionClient.events.collectLatest { event ->
                when (event) {
                    is IncomingServerEvent.AssistantText -> {
                        _uiState.update {
                            it.copy(
                                messages = it.messages + AssistantMessage(
                                    sender = AssistantMessage.Sender.ASSISTANT,
                                    text = event.text,
                                ),
                                lastError = null,
                            )
                        }
                    }

                    is IncomingServerEvent.AssistantAudioChunk -> {
                        audioOutputPlayer.playBase64Wav(event.payloadB64)
                        _uiState.update {
                            it.copy(
                                messages = it.messages + AssistantMessage(
                                    sender = AssistantMessage.Sender.SYSTEM,
                                    text = "Получен аудио-чанк #${event.chunkId}",
                                ),
                            )
                        }
                    }

                    is IncomingServerEvent.Error -> {
                        _uiState.update {
                            it.copy(
                                messages = it.messages + AssistantMessage(
                                    sender = AssistantMessage.Sender.SYSTEM,
                                    text = "Ошибка сервера: ${event.message}",
                                ),
                                lastError = event.message,
                            )
                        }
                    }

                    is IncomingServerEvent.Unknown -> {
                        _uiState.update {
                            it.copy(
                                messages = it.messages + AssistantMessage(
                                    sender = AssistantMessage.Sender.SYSTEM,
                                    text = "Неизвестное событие: ${event.raw}",
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun observeWakewordEvents() {
        viewModelScope.launch {
            wakewordDetector.events.collectLatest { event ->
                when (event) {
                    is WakewordEvent.Detected -> {
                        addSystemMessage("Wakeword detected")
                        if (!uiState.value.isConnected) {
                            connect()
                        }
                        voiceSessionClient.sendWakewordDetected()
                        startCapture()
                        recordStopJob?.cancel()
                        recordStopJob = viewModelScope.launch {
                            delay(RECORDING_WINDOW_MS)
                            stopCapture()
                        }
                    }

                    is WakewordEvent.Error -> {
                        addSystemMessage("Wakeword error: ${event.message}")
                    }
                }
            }
        }
    }

    private fun stopWakeword() {
        wakewordDetector.stop()
        _uiState.update { it.copy(isWakewordEnabled = false) }
    }

    private fun addSystemMessage(message: String) {
        _uiState.update {
            it.copy(
                messages = it.messages + AssistantMessage(
                    sender = AssistantMessage.Sender.SYSTEM,
                    text = message,
                ),
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopCapture()
        stopWakeword()
        audioOutputPlayer.stop()
    }

    private companion object {
        private const val RECORDING_WINDOW_MS = 5_000L
    }
}
