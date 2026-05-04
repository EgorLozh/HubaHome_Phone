package com.hubahome.phone.feature.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hubahome.phone.core.audio.ActivationCuePlayer
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
    private val activationCuePlayer: ActivationCuePlayer,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()
    private var captureStartJob: Job? = null
    private var recordStopJob: Job? = null
    private var pendingWakewordTurn = false
    private var restartWakewordJob: Job? = null

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
        finishCapture(sendToServer = false)
        voiceSessionClient.disconnect()
        stopWakeword()
        updateVoicePhase("Disconnected")
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
            startWakewordListening()
            addSystemMessage("Wakeword detection enabled")
        } else {
            stopWakeword()
            addSystemMessage("Wakeword detection disabled")
        }
    }

    fun startCapture() {
        if (audioChunkRecorder.isRecording || !isSocketReady(sessionStatus.value)) return
        wakewordDetector.stop()
        pendingWakewordTurn = false
        captureStartJob?.cancel()
        recordStopJob?.cancel()
        voiceSessionClient.sendWakewordDetected()
        activationCuePlayer.playReadyCue()
        updateVoicePhase("Wakeword detected")
        captureStartJob = viewModelScope.launch {
            delay(READY_CUE_DELAY_MS)
            if (!isSocketReady(sessionStatus.value)) return@launch
            audioChunkRecorder.start()
            _uiState.update { it.copy(isRecording = true) }
            updateVoicePhase("Recording command")
            addSystemMessage("Recording started")
            recordStopJob = viewModelScope.launch {
                delay(RECORDING_WINDOW_MS)
                stopCapture()
            }
        }
    }

    fun stopCapture() {
        finishCapture(sendToServer = true)
    }

    private fun finishCapture(sendToServer: Boolean) {
        captureStartJob?.cancel()
        recordStopJob?.cancel()
        if (!audioChunkRecorder.isRecording) return
        val audioWavBase64 = audioChunkRecorder.stop()
        _uiState.update { it.copy(isRecording = false) }
        if (!sendToServer) {
            addSystemMessage("Recording cancelled")
            return
        }
        if (!isSocketReady(sessionStatus.value)) {
            addSystemMessage("Recording stopped, but socket is disconnected")
            restartWakewordIfEnabled()
            return
        }
        if (audioWavBase64.isNullOrBlank()) {
            addSystemMessage("Recording stopped without audio")
            restartWakewordIfEnabled()
            return
        }
        voiceSessionClient.sendAudioChunk(chunkId = 0, payloadB64 = audioWavBase64)
        voiceSessionClient.sendFinalTranscript("")
        updateVoicePhase("Waiting for server")
        addSystemMessage("Recording stopped; utterance sent for server STT")
    }

    private fun observeSessionStatus() {
        viewModelScope.launch {
            sessionStatus.collectLatest { status ->
                _uiState.update {
                    it.copy(
                        sessionStatus = status,
                        isConnected = isSocketReady(status),
                    )
                }
                if (status == VoiceSessionStatus.CONNECTED && pendingWakewordTurn) {
                    pendingWakewordTurn = false
                    startCapture()
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
                        if (event.text == READY_PROMPT_TEXT) {
                            return@collectLatest
                        }
                        _uiState.update {
                            it.copy(
                                messages = it.messages + AssistantMessage(
                                    sender = AssistantMessage.Sender.ASSISTANT,
                                    text = event.text,
                                ),
                                lastError = null,
                            )
                        }
                        updateVoicePhase("Assistant replied")
                        scheduleWakewordRestart()
                    }

                    is IncomingServerEvent.AssistantAudioChunk -> {
                        restartWakewordJob?.cancel()
                        updateVoicePhase("Playing response")
                        audioOutputPlayer.playBase64Wav(event.payloadB64) {
                            restartWakewordIfEnabled()
                        }
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
                        updateVoicePhase("Server error")
                        restartWakewordIfEnabled()
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
                            pendingWakewordTurn = true
                            updateVoicePhase("Connecting to server")
                            connect()
                            return@collectLatest
                        }
                        startCapture()
                    }

                    is WakewordEvent.Error -> {
                        addSystemMessage("Wakeword error: ${event.message}")
                        updateVoicePhase("Wakeword error")
                    }
                }
            }
        }
    }

    private fun startWakewordListening() {
        restartWakewordJob?.cancel()
        wakewordDetector.start()
        updateVoicePhase("Listening for wakeword")
    }

    private fun scheduleWakewordRestart() {
        if (!uiState.value.isWakewordEnabled || uiState.value.isRecording) return
        restartWakewordJob?.cancel()
        restartWakewordJob = viewModelScope.launch {
            delay(RESTART_WAKEWORD_DELAY_MS)
            restartWakewordIfEnabled()
        }
    }

    private fun restartWakewordIfEnabled() {
        if (!uiState.value.isWakewordEnabled || uiState.value.isRecording) return
        startWakewordListening()
    }

    private fun stopWakeword() {
        restartWakewordJob?.cancel()
        wakewordDetector.stop()
        _uiState.update { it.copy(isWakewordEnabled = false) }
        if (!uiState.value.isRecording) {
            updateVoicePhase("Wakeword disabled")
        }
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

    private fun updateVoicePhase(value: String) {
        _uiState.update { it.copy(voicePhase = value) }
    }

    private fun isSocketReady(status: VoiceSessionStatus): Boolean {
        return when (status) {
            VoiceSessionStatus.CONNECTED,
            VoiceSessionStatus.LISTENING,
            VoiceSessionStatus.AWAITING_RESPONSE,
            VoiceSessionStatus.PLAYING_RESPONSE,
            -> true

            VoiceSessionStatus.IDLE,
            VoiceSessionStatus.CONNECTING,
            VoiceSessionStatus.RETRYING,
            VoiceSessionStatus.DISCONNECTED,
            VoiceSessionStatus.ERROR,
            -> false
        }
    }

    override fun onCleared() {
        super.onCleared()
        finishCapture(sendToServer = false)
        stopWakeword()
        audioOutputPlayer.stop()
    }

    private companion object {
        private const val RECORDING_WINDOW_MS = 5_000L
        private const val READY_CUE_DELAY_MS = 220L
        private const val RESTART_WAKEWORD_DELAY_MS = 750L
        private const val READY_PROMPT_TEXT = "Слушаю, говори команду."
    }
}
