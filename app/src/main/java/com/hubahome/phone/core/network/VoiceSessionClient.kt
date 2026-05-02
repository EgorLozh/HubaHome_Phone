package com.hubahome.phone.core.network

import kotlinx.coroutines.flow.Flow

enum class VoiceSessionStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    LISTENING,
    AWAITING_RESPONSE,
    PLAYING_RESPONSE,
    RETRYING,
    DISCONNECTED,
    ERROR,
}

interface VoiceSessionClient {
    val status: Flow<VoiceSessionStatus>
    val events: Flow<IncomingServerEvent>

    fun connect()
    fun disconnect()
    fun reconnect()

    fun sendWakewordDetected()
    fun sendFinalTranscript(text: String)
    fun sendAudioChunk(chunkId: Int, payloadB64: String)
}
