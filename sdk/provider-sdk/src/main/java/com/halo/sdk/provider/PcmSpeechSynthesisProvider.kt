package com.halo.sdk.provider

/** Portable interleaved PCM. SDK contracts never expose Android AudioFormat constants. */
enum class PcmEncoding(val bytesPerSample: Int) { UNSIGNED_8(1), SIGNED_16_LE(2), FLOAT_32_LE(4) }
data class SpeechPcmRange(val start: Int, val end: Int, val frame: Long)

data class PcmSpeech(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: PcmEncoding,
    val chunks: List<ByteArray>,
    val ranges: List<SpeechPcmRange> = emptyList(),
) {
    init {
        require(sampleRateHz in 8_000..192_000 && channelCount in 1..2)
        require(chunks.isNotEmpty() && chunks.sumOf { it.size.toLong() } <= 32L * 1024 * 1024)
        require(chunks.all { it.size % (channelCount * encoding.bytesPerSample) == 0 })
    }
}

interface PcmSpeechSynthesisProvider : SpeechSynthesisProvider {
    suspend fun synthesizeToPcm(request: SpeechRequest): PcmSpeech?
}
