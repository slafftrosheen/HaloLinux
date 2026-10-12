package com.halo.sdk.runtime

/** Snapshot supplied by the host before preparing a response. No platform storage in cognition. */
data class VoiceResponsePolicy(
    val streamingSpeechEnabled: Boolean = true,
    val responseStyle: SpokenResponseStyle = SpokenResponseStyle.NATURAL,
    val wearablePhoneFallback: Boolean = false,
)
enum class SpokenResponseStyle { CONCISE, NATURAL, DETAILED }
enum class SpeechActivationKind { EXPLICIT_USER, VOICE_ACTIVITY, WAKE_WORD, AUTOMATIC, UNKNOWN }

fun fallbackFor(origin: SpeechInputRoute?, enabled: Boolean): SpeechOutputFallback =
    if (origin is SpeechInputRoute.Device && !enabled) SpeechOutputFallback.DISALLOW_PHONE
    else SpeechOutputFallback.ALLOW_PHONE

fun responseTarget(origin: SpeechInputRoute?): SpeechOutputTarget = when (origin) {
    is SpeechInputRoute.Device -> SpeechOutputTarget.Device(origin.deviceId, origin.platformEndpointId, origin.displayName, origin.kind)
    else -> SpeechOutputTarget.HostDefault
}

data class SpeechActivityObserved(
    val captureId: String,
    val sourceDeviceId: String?,
    val voicedSpeech: Boolean,
    val sustainedSpeechMs: Long,
    val inputRouteValid: Boolean,
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
) : HaloEvent

/** Exact, tool-free terminal response only. Contains no generated content. */
data class StreamingSpeechPrepared(
    val requestId: String,
    val interactionId: String,
    val target: SpeechOutputTarget,
    val fallback: SpeechOutputFallback,
    val sourceCaptureId: String?,
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
) : HaloEvent
