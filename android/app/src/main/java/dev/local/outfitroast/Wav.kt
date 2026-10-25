package dev.local.outfitroast

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wav {
    const val SAMPLE_RATE = 16000
    fun encode(pcm: ByteArray): ByteArray {
        require(pcm.size % 2 == 0) { "16-bit PCM must have an even byte count" }
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }
}
