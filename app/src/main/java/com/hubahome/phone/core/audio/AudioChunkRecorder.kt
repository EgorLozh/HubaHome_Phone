package com.hubahome.phone.core.audio

interface AudioChunkRecorder {
    fun start()
    fun stop(): String?
    val isRecording: Boolean
}
