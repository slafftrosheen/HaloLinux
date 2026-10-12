package com.halo.sdk.runtime.multimodal

object Pcm16WavEncoder {
    fun encode(pcm: ByteArray, sampleRateHz: Int, channels: Int): ByteArray =
        Pcm16Wav.wrap(pcm, sampleRateHz, channels) ?: throw MediaAcquisitionException(MediaFailure.UNSUPPORTED_FORMAT)
}

class PreparedAudioInput(val content: MediaAssetContent, val metadata: AudioAssetMetadata)
class AudioInputPreparer {
    suspend fun prepare(ref: MediaAssetRef, resolver: MediaAssetResolver, budget: MultimodalBudget = MultimodalBudget()): PreparedAudioInput {
        if (ref.kind != MediaKind.AUDIO) throw MediaAcquisitionException(MediaFailure.UNSUPPORTED_FORMAT)
        val metadata = resolver.metadata(ref)?.audio ?: throw MediaAcquisitionException(MediaFailure.UNSUPPORTED_FORMAT)
        return preparePcm16(resolver.open(ref), metadata, budget)
    }
    fun preparePcm16(content: MediaAssetContent, metadata: AudioAssetMetadata, budget: MultimodalBudget = MultimodalBudget()): PreparedAudioInput {
        if (metadata.encoding != "PCM16_LE" || content.mimeType != "audio/pcm" || metadata.sampleRateHz !in 8_000..96_000 || metadata.channels !in 1..2) {
            throw MediaAcquisitionException(MediaFailure.UNSUPPORTED_FORMAT)
        }
        val bytesPerFrame = metadata.channels * 2
        if (content.bytes.isEmpty() || content.bytes.size % bytesPerFrame != 0) throw MediaAcquisitionException(MediaFailure.DECODE_FAILED)
        val actualDuration = content.bytes.size.toLong() * 1000 / (metadata.sampleRateHz * bytesPerFrame)
        if (actualDuration <= 0 || actualDuration > budget.audioDurationMs || content.bytes.size.toLong() + Pcm16Wav.HEADER_BYTES > budget.audioBytes) throw MediaAcquisitionException(MediaFailure.TOO_LARGE)
        // Derive duration from frames rather than trusting host-declared duration.
        return PreparedAudioInput(MediaAssetContent(Pcm16WavEncoder.encode(content.bytes, metadata.sampleRateHz, metadata.channels), "audio/wav"), metadata.copy(durationMs = actualDuration))
    }
}
