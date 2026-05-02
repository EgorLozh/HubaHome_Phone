package com.hubahome.phone.core.audio

interface AudioChunkRecorder {
    fun start(onChunkBase64Wav: (String) -> Unit)
    fun stop()
    val isRecording: Boolean
}
