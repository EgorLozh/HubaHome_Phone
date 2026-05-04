package com.hubahome.phone.core.audio

interface AudioOutputPlayer {
    fun playBase64Wav(base64Wav: String, onComplete: (() -> Unit)? = null)
    fun stop()
}
