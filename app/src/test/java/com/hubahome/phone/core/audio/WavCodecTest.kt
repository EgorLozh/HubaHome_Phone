package com.hubahome.phone.core.audio

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class WavCodecTest {
    @Test
    fun pcmToWavRoundtrip_preservesPayloadSize() {
        val pcm = Random.nextBytes(4096)
        val wav = WavCodec.pcm16MonoToWav(pcm)
        val decoded = WavCodec.wavToPcm16(wav)

        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.size == pcm.size)
    }
}
