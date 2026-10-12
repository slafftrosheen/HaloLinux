package com.halo.sdk.runtime

sealed interface SpeechOutputTarget {
    /** Android chooses the initial route; playback pins it for the utterance. */
    data object HostDefault : SpeechOutputTarget
    data object Phone : SpeechOutputTarget

    data class Device(
        val deviceId: String?,
        val platformEndpointId: Int?,
        val displayName: String?,
        val kind: AudioDeviceKind
    ) : SpeechOutputTarget
}

enum class SpeechOutputFallback {
    DISALLOW_PHONE,
    ALLOW_PHONE
}

enum class SpeechReplyPolicy {
    SAME_DEVICE,
    PHONE,
    USER_SELECTED
}

enum class SpeechRoutePendingReason {
    PAIRING_REQUIRED,
    WAITING_FOR_ENDPOINT
}

enum class SpeechRouteFailureReason {
    DEVICE_DISCONNECTED,
    COMMAND_FAILED,
    AUDIO_TRANSPORT_NOT_CONNECTED,
    WRONG_DEVICE
}

enum class SpeechRouteMode { EXACT_DEVICE, SYSTEM_DEFAULT, PHONE_SPEAKER, MANAGED_DEVICE }

data class SpeechRouteEvidence(
    val vendorRouteCommandSent: Boolean,
    val androidDeviceType: Int?,
    val androidDeviceName: String?,
    val androidDeviceAddress: String?,
    val verifiedAtMs: Long = System.currentTimeMillis(),
    val androidDeviceId: Int? = null,
    val mode: SpeechRouteMode = SpeechRouteMode.EXACT_DEVICE
)

/** All available evidence must agree. Names never establish route identity. */
fun matchesSpeechRoute(evidence: SpeechRouteEvidence, id: Int, type: Int, address: String?): Boolean =
    (evidence.androidDeviceId != null || !evidence.androidDeviceAddress.isNullOrBlank()) &&
        (evidence.androidDeviceType == null || evidence.androidDeviceType == type) &&
        (evidence.androidDeviceId == null || evidence.androidDeviceId == id) &&
        (evidence.androidDeviceAddress.isNullOrBlank() || evidence.androidDeviceAddress.equals(address, ignoreCase = true))

sealed interface SpeechRoutePreparation {
    data class Ready(
        val target: SpeechOutputTarget,
        val evidence: SpeechRouteEvidence
    ) : SpeechRoutePreparation

    data class Pending(
        val target: SpeechOutputTarget,
        val reason: SpeechRoutePendingReason
    ) : SpeechRoutePreparation

    data class Unavailable(
        val target: SpeechOutputTarget,
        val reason: SpeechRouteFailureReason
    ) : SpeechRoutePreparation
}

data class SpeechTurnContext(
    val captureId: String?,
    val sourceDeviceId: String?,
    val inputRoute: SpeechInputRoute?,
    val outputTarget: SpeechOutputTarget
)
