package com.halo.sdk.provider

/**
 * MINING-P2-001 multimodal audio preparation.
 *
 * Halo has `AiCapability.AUDIO` already; what it lacked was a *portable* way to describe audio as
 * context. This is preparation only: it forces no audio model and changes nothing about the current
 * `DEDICATED_STT` path (Sherpa remains the default and is not replaced in this batch).
 *
 * Deliberately an asset reference, not a raw `ByteArray`: durable context contracts stay small, and
 * the host owns the bytes.
 */
data class AudioContext(
    /** Host-owned asset identity, resolved through the existing asset/workspace systems. */
    val assetId: String,
    val mimeType: String,
    val durationMs: Long,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    /** Bounded, content-free origin label, e.g. "phone-mic" or a device id. */
    val provenance: String,
    val sourceDeviceId: String? = null,
    val interactionId: String? = null,
) {
    /** Bounded description safe for logs and diagnostics: never audio content. */
    fun describe(): String = buildString {
        append("mime=").append(mimeType)
        append(" durationMs=").append(durationMs)
        sampleRateHz?.let { append(" sampleRateHz=").append(it) }
        channels?.let { append(" channels=").append(it) }
        append(" provenance=").append(provenance)
        sourceDeviceId?.let { append(" device=").append(it.take(64)) }
    }
}

/**
 * How speech becomes interpretable text.
 *
 * [DEDICATED_STT] is the only enabled strategy today. [AUDIO_MODEL_TRANSCRIBE_THEN_REASON] is a
 * two-pass contract defined for a future multimodal model: audio is transcribed first and the
 * transcript — the semantic boundary — then flows through the ordinary cognition and tool loop.
 * Halo does not implement "audio straight into an uncontrolled agent answer".
 */
enum class SpeechInterpretationStrategy {
    DEDICATED_STT,
    AUDIO_MODEL_TRANSCRIBE_THEN_REASON,
    ;

    companion object {
        /** Current default. Explicit, so a future change is a deliberate one. */
        val DEFAULT: SpeechInterpretationStrategy = DEDICATED_STT
    }
}
