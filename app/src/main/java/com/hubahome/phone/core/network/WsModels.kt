package com.hubahome.phone.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class WakewordDetectedEvent(
    val event: String = "wakeword_detected",
    @SerialName("wakeWord") val wakeWord: String = "huba",
    @SerialName("correlationId") val correlationId: String? = null,
)

@Serializable
data class AudioChunkEvent(
    val event: String = "audio_chunk",
    @SerialName("chunkId") val chunkId: Int = 0,
    @SerialName("payloadB64") val payloadB64: String,
    @SerialName("correlationId") val correlationId: String? = null,
)

@Serializable
data class FinalTranscriptEvent(
    val event: String = "final_transcript",
    val text: String,
    @SerialName("correlationId") val correlationId: String? = null,
)

sealed class IncomingServerEvent {
    data class AssistantText(val text: String, val correlationId: String?) : IncomingServerEvent()
    data class AssistantAudioChunk(
        val chunkId: Int,
        val payloadB64: String,
        val correlationId: String?,
    ) : IncomingServerEvent()

    data class Error(val message: String, val correlationId: String?) : IncomingServerEvent()
    data class Unknown(val raw: String) : IncomingServerEvent()
}

fun parseIncomingServerEvent(raw: String): IncomingServerEvent {
    return runCatching {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(raw) as JsonObject
        val event = json["event"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val correlationId = json["correlationId"]?.jsonPrimitive?.contentOrNull
        when (event) {
            "assistant_text" -> IncomingServerEvent.AssistantText(
                text = json["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                correlationId = correlationId,
            )

            "assistant_audio_chunk" -> IncomingServerEvent.AssistantAudioChunk(
                chunkId = json["chunkId"]?.jsonPrimitive?.intOrNull ?: 0,
                payloadB64 = json["payloadB64"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                correlationId = correlationId,
            )

            "error" -> IncomingServerEvent.Error(
                message = json["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown server error",
                correlationId = correlationId,
            )

            else -> IncomingServerEvent.Unknown(raw)
        }
    }.getOrElse { IncomingServerEvent.Unknown(raw) }
}

fun outgoingErrorEvent(message: String): String {
    val payload = buildJsonObject {
        put("event", JsonPrimitive("error"))
        put("message", JsonPrimitive(message))
    }
    return kotlinx.serialization.json.Json.encodeToString(JsonObject.serializer(), payload)
}
