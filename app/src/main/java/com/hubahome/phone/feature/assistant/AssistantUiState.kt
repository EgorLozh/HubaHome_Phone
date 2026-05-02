package com.hubahome.phone.feature.assistant

import com.hubahome.phone.core.network.VoiceSessionStatus

data class AssistantMessage(
    val sender: Sender,
    val text: String,
) {
    enum class Sender {
        USER,
        ASSISTANT,
        SYSTEM,
    }
}

data class AssistantUiState(
    val sessionStatus: VoiceSessionStatus = VoiceSessionStatus.IDLE,
    val isConnected: Boolean = false,
    val transcriptInput: String = "",
    val messages: List<AssistantMessage> = emptyList(),
    val lastError: String? = null,
    val wsUrlInput: String = "",
    val apiKeyInput: String = "",
    val isWakewordEnabled: Boolean = false,
    val isRecording: Boolean = false,
)
