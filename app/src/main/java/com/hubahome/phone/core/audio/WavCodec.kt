package com.hubahome.phone.core.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

object WavCodec {
    fun pcm16MonoToWav(
        pcm: ByteArray,
        sampleRate: Int = 16_000,
        channels: Short = 1,
        bitsPerSample: Short = 16,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataSize = pcm.size
        val wavSize = 44 + dataSize

        val out = ByteArrayOutputStream(wavSize)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(intLe(36 + dataSize))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(intLe(16))
        out.write(shortLe(1))
        out.write(shortLe(channels))
        out.write(intLe(sampleRate))
        out.write(intLe(byteRate))
        out.write(shortLe(blockAlign))
        out.write(shortLe(bitsPerSample))
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(intLe(dataSize))
        out.write(pcm)
        return out.toByteArray()
    }

    fun wavToPcm16(wav: ByteArray): ByteArray {
        if (wav.size <= 44) return ByteArray(0)
        return wav.copyOfRange(44, wav.size)
    }

    fun wavToBase64(wav: ByteArray): String = Base64.getEncoder().encodeToString(wav)

    fun base64ToWav(base64: String): ByteArray = Base64.getDecoder().decode(base64)

    private fun intLe(value: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .array()

    private fun shortLe(value: Short): ByteArray = ByteBuffer.allocate(2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(value)
        .array()
}
