package com.halo.sdk.runtime.multimodal

import java.io.ByteArrayOutputStream

/**
 * MINING-P2-001 multimodal audio preparation.
 *
 * A compatibility lesson worth keeping: on-device audio models commonly refuse raw PCM and require a
 * media container. This utility wraps PCM16 LE in a minimal WAV container so a *future* multimodal
 * adapter can hand the model something it accepts.
 *
 * It is not on any current path. Sherpa's audio never goes through this, and the current
 * `SpeechInterpretationStrategy.DEDICATED_STT` behaviour is unchanged.
 *
 * Platform-free by design: no Android types, no audio APIs, just bytes.
 */
object Pcm16Wav {

    const val BITS_PER_SAMPLE = 16
    const val HEADER_BYTES = 44

    /**
     * Wraps interleaved little-endian 16-bit PCM in a WAV container.
     *
     * Returns null for impossible parameters rather than emitting a malformed container: a corrupt
     * WAV would surface as an unintelligible transcription, which is worse than a clear refusal.
     */
    fun wrap(
        pcm: ByteArray,
        sampleRateHz: Int,
        channels: Int,
    ): ByteArray? {
        if (sampleRateHz !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) return null
        if (channels !in 1..MAX_CHANNELS) return null
        if (pcm.isEmpty() || pcm.size > 8 * 1024 * 1024 || pcm.size % (channels * (BITS_PER_SAMPLE / 8)) != 0) return null

        val byteRate = sampleRateHz * channels * (BITS_PER_SAMPLE / 8)
        val blockAlign = channels * (BITS_PER_SAMPLE / 8)
        val output = ByteArrayOutputStream(HEADER_BYTES + pcm.size)

        output.writeAscii("RIFF")
        output.writeIntLe(36 + pcm.size)
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeIntLe(16)              // PCM fmt chunk size
        output.writeShortLe(1)             // audio format: PCM
        output.writeShortLe(channels)
        output.writeIntLe(sampleRateHz)
        output.writeIntLe(byteRate)
        output.writeShortLe(blockAlign)
        output.writeShortLe(BITS_PER_SAMPLE)
        output.writeAscii("data")
        output.writeIntLe(pcm.size)
        output.write(pcm)
        return output.toByteArray()
    }

    /** True when [bytes] already looks like a RIFF/WAVE container. */
    fun looksLikeWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()

    private const val MIN_SAMPLE_RATE = 8_000
    private const val MAX_SAMPLE_RATE = 96_000
    private const val MAX_CHANNELS = 2

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }
}
