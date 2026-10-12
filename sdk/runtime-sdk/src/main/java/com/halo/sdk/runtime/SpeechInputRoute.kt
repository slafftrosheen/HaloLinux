package com.halo.sdk.runtime

import com.halo.sdk.host.ObservationSource

enum class AudioDeviceKind {
    WEARABLE,
    HEADSET,
    EARBUDS,
    USB_AUDIO,
    HOST_EXTERNAL,
    UNKNOWN_EXTERNAL
}

sealed interface SpeechInputRoute {
    data object Phone : SpeechInputRoute
    data class Device(
        val deviceId: String?,
        val platformEndpointId: Int?,
        val displayName: String?,
        val kind: AudioDeviceKind
    ) : SpeechInputRoute
}

enum class CaptureBoundary { VOICE_ACTIVITY, EXTERNAL }

data class SpeechInputTurn(
    val captureId: String,
    val route: SpeechInputRoute = SpeechInputRoute.Phone,
    val source: ObservationSource = ObservationSource.AndroidPhoneMic,
    val boundary: CaptureBoundary = CaptureBoundary.VOICE_ACTIVITY,
    val activation: SpeechActivationKind = SpeechActivationKind.UNKNOWN,
    val conversationId: String? = null
)

data class SpeechInputStatus(
    val turn: SpeechInputTurn? = null,
    val capturing: Boolean = false,
    val inputLevel: Float = 0f,
    val rejection: String? = null
)
