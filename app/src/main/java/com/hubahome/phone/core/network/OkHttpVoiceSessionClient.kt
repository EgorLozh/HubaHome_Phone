package com.hubahome.phone.core.network

import android.util.Log
import com.hubahome.phone.core.settings.ConnectionSettingsStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

@Singleton
class OkHttpVoiceSessionClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val settingsStore: ConnectionSettingsStore,
) : VoiceSessionClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(VoiceSessionStatus.IDLE)
    override val status: Flow<VoiceSessionStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<IncomingServerEvent>(extraBufferCapacity = 64)
    override val events: Flow<IncomingServerEvent> = _events.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var sessionCorrelationId: String = UUID.randomUUID().toString()
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var manualDisconnect = false

    override fun connect() {
        manualDisconnect = false
        if (webSocket != null || _status.value == VoiceSessionStatus.CONNECTING) return
        reconnectJob?.cancel()
        scope.launch {
            val settings = settingsStore.settings.first()
            _status.value = VoiceSessionStatus.CONNECTING
            sessionCorrelationId = UUID.randomUUID().toString()
            val request = Request.Builder()
                .url(settings.wsUrl)
                .addHeader("x-api-key", settings.apiKey)
                .addHeader("x-correlation-id", sessionCorrelationId)
                .build()
            webSocket = okHttpClient.newWebSocket(request, socketListener)
        }
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectAttempts = 0
        reconnectJob?.cancel()
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        _status.value = VoiceSessionStatus.DISCONNECTED
    }

    override fun reconnect() {
        disconnect()
        manualDisconnect = false
        connect()
    }

    override fun sendWakewordDetected() {
        val payload = WakewordDetectedEvent(correlationId = sessionCorrelationId)
        webSocket?.send(json.encodeToString(payload)) ?: run {
            _events.tryEmit(IncomingServerEvent.Error("Socket is not connected", sessionCorrelationId))
        }
        _status.value = VoiceSessionStatus.LISTENING
    }

    override fun sendFinalTranscript(text: String) {
        val payload = FinalTranscriptEvent(text = text, correlationId = sessionCorrelationId)
        webSocket?.send(json.encodeToString(payload)) ?: run {
            _events.tryEmit(IncomingServerEvent.Error("Socket is not connected", sessionCorrelationId))
            return
        }
        _status.value = VoiceSessionStatus.AWAITING_RESPONSE
    }

    override fun sendAudioChunk(chunkId: Int, payloadB64: String) {
        val payload = AudioChunkEvent(
            chunkId = chunkId,
            payloadB64 = payloadB64,
            correlationId = sessionCorrelationId,
        )
        webSocket?.send(json.encodeToString(payload))
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts = 0
            _status.value = VoiceSessionStatus.CONNECTED
            sendWakewordDetected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = parseIncomingServerEvent(text)
            when (event) {
                is IncomingServerEvent.AssistantText -> _status.value = VoiceSessionStatus.CONNECTED
                is IncomingServerEvent.AssistantAudioChunk -> _status.value = VoiceSessionStatus.PLAYING_RESPONSE
                is IncomingServerEvent.Error -> _status.value = VoiceSessionStatus.ERROR
                is IncomingServerEvent.Unknown -> Unit
            }
            _events.tryEmit(event)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: code=$code reason=$reason")
            this@OkHttpVoiceSessionClient.webSocket = null
            if (manualDisconnect) {
                _status.value = VoiceSessionStatus.DISCONNECTED
                return
            }
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            this@OkHttpVoiceSessionClient.webSocket = null
            _status.value = VoiceSessionStatus.ERROR
            _events.tryEmit(
                IncomingServerEvent.Error(
                    message = t.message ?: "WebSocket failure",
                    correlationId = sessionCorrelationId,
                )
            )
            if (!manualDisconnect) {
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _status.value = VoiceSessionStatus.DISCONNECTED
            _events.tryEmit(
                IncomingServerEvent.Error(
                    message = "Reconnect limit reached",
                    correlationId = sessionCorrelationId,
                )
            )
            return
        }
        reconnectAttempts += 1
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            _status.value = VoiceSessionStatus.RETRYING
            val delayMs = (BASE_RECONNECT_DELAY_MS * reconnectAttempts).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            delay(delayMs)
            connect()
        }
    }

    companion object {
        private const val TAG = "VoiceSessionClient"
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 10_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }
}
