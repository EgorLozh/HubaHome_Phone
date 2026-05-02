package com.hubahome.phone.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WsModelsTest {
    @Test
    fun parseIncomingServerEvent_parsesAssistantText() {
        val raw = """{"event":"assistant_text","text":"Привет","correlationId":"abc"}"""

        val result = parseIncomingServerEvent(raw)

        assertTrue(result is IncomingServerEvent.AssistantText)
        val event = result as IncomingServerEvent.AssistantText
        assertEquals("Привет", event.text)
        assertEquals("abc", event.correlationId)
    }

    @Test
    fun parseIncomingServerEvent_parsesError() {
        val raw = """{"event":"error","message":"Internal server error"}"""

        val result = parseIncomingServerEvent(raw)

        assertTrue(result is IncomingServerEvent.Error)
        val event = result as IncomingServerEvent.Error
        assertEquals("Internal server error", event.message)
    }
}
